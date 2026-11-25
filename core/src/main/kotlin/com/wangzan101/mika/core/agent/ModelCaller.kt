package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.run.trace.RunTrace
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.CallOptions
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.LightPrompt
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelErrorDiagnostics
import com.wangzan101.mika.core.model.ModelNeed
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StreamingChatModel
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.model.usage.ConversationMeter
import com.wangzan101.mika.core.model.usage.LoopCallScope
import com.wangzan101.mika.core.run.record.Redactor
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.UsageCostEstimator
import com.wangzan101.mika.core.safety.guard.sha256Hex12
import com.wangzan101.mika.core.tool.ToolDispatcher
import com.wangzan101.mika.core.tool.parseArgs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal data class CallDiagnostics(
    val provider: String? = null,
    val attempt: Int = 1,
    val retryCount: Int = 0,
    val retryWaitMs: Long = 0L,
    val cacheMissReason: String? = null,
    val systemPromptHash: String? = null,
    val promptVersion: String? = null,
    val toolListHash: String? = null,
    val promptTransformEffect: String? = null,
    val compactionTrigger: String? = null,
    val compactionReason: String? = null,
    val foldedSteps: Int = 0,
    val capCheckResult: String = "ok",
    val estimatedInputTokens: Int = 0,
    val hasImages: Boolean? = null,
    val vision: Boolean? = null,
    val sameApp: Boolean? = null,
    val lastChanged: Boolean? = null,
    val forcedMain: Boolean = false,
    val lightRetried: Boolean? = null,
    val escalatedFromLight: Boolean? = null,
    val lightRejectionReason: String? = null,
)

/**
 * 一次模型调用:历史只追加,估算越过触发点才压缩一次([ContextCompressor],完整历史不动)、主模型一时的故障按 [retry] 重试、上下文超长时压得更狠再发一次,
 * lightSteps 打开时按 [router] 在大小模型之间分工(轻量模型出错不重试,这一步直接改问主模型)。每个任务开始时 [reset]。
 *
 * 主模型、轻量模型都可以是模型组([ModelPool]):一次调用按成员能力和策略选一个,失败按 [FailoverPolicy] 换下一个;
 * 每个成员各有一份压缩状态、用量锚点,换成员时用 [viewFor] 去掉别家模型的私有数据。
 */
internal class ModelCaller(
    private val model: ChatModel,
    private val tools: ToolDispatcher,
    private val router: ModelRouter?,
    private val flags: SpeedFlags,
    private val retry: RetryPolicy,
    private val emit: (RunEvent) -> Unit,
    private val now: () -> Long,
    private val autonomous: Boolean = false,
    private val trace: RunTrace? = null,
    /** 主模型的备援(项目 3):主模型这一步失败且 [Failover] 说换,就按序接手;默认没有,行为和原来一样。 */
    private val fallbacks: List<ChatModel> = emptyList(),
    /** 与主模型链平行的服务商 ID，用来判断每日预算是否适用于备援。 */
    private val chainProviderIds: List<String?> = emptyList(),
) {
    /** 按角色(main / light)各持一份压缩器、压缩状态与粘性池成员，避免 ModelPool/GroupPool 换对象身份时反复新建压缩器。 */
    private val inputTokenCap: Int? = if (flags.disableInputTokenCap) null else ContextLimits.MAX_INPUT_TOKENS
    private val compressors = HashMap<String, ContextCompressor>()
    private fun roleKey(m: ChatModel): String = if (router?.isLight(m) == true) ROLE_LIGHT else ROLE_MAIN
    private fun compressorFor(m: ChatModel): ContextCompressor {
        val key = roleKey(m)
        return compressors.getOrPut(key) {
            ContextCompressor(
                tools.index,
                structured = flags.structuredCompaction,
                diffScreens = tools.inlineScreen,
                triggerOverride = m.capabilities.compactionTriggerTokens,
                maxInputTokens = inputTokenCap,
            )
        }
    }
    private val compactions = HashMap<String, CompactionState>()
    private val stickyMembers = HashMap<String, ChatModel>()
    /** 本任务里已为重试等过的时间(对着 [RetryPolicy.taskWaitBudgetMs])。 */
    private var taskRetryWaitMs = 0L

    /** 主模型链:[model] 加上备援。切过去之后本任务余下都用它(和轻量模型停用一个道理),[reset] 回到主模型。 */
    private val chain: List<ChatModel> = listOf(model) + fallbacks
    private var chainIndex = 0

    /** 本任务里主模型最初的错误、和每个链上模型先犯的错误(整条链失败时的说法用,MK-1 第 2 批);[reset] 清空。 */
    private var headError: ModelError? = null
    private val trialLog = ArrayList<Pair<Int, ModelError>>()

    /** 最近一次主模型调用实际用的模型:切到备援后就是备援;[routed] 按它记账。 */
    var lastMainUsed: ChatModel = model
        private set

    /** 本任务的系统提示词:开工时按发给模型的同一份工具列表拼([SystemPrompt.assemble]),任务内不变。 */
    var systemPrompt: String = ""

    /** 插件提示词钩子:拼好的系统提示词发模型前过它;为 null 时原样发。 */
    var promptTransform: (suspend (String) -> String)? = null

    /** 最近一次调用在成功那次请求之前花掉的时间(失败的请求、退避、换成员);从第一次真正发出请求起算。 */
    var lastRetryWaitMs = 0L
        private set

    /** 最近一次主循环模型调用(或最近一次尝试)的诊断遥测，由 [AgentLoop] 写入运行记录。 */
    var lastCallDiagnostics: CallDiagnostics? = null
        private set

    /** 任务启动时计算的提示词版本摘要，由 [AgentLoop] 注入。 */
    var lastPromptVersion: String? = null

    /** 按角色记录上一次成功请求的前缀状态(systemPromptHash, toolListHash, model|provider)。 */
    private val prevPrefixByRole = HashMap<String, Triple<String, String, String>>()
    private var lastAttemptModel: ChatModel? = null

    /** 每条已进历史的回复是哪个模型给的(按消息身份);换模型时按它去掉别家模型的私有数据。没记到的(任务开始前就在历史里的)视同主模型给的。 */
    private val producers = java.util.IdentityHashMap<Message, ChatModel>()

    /** 带真实用量的回复(按消息身份)和角色,是量请求大小的锚点([UsageMeter]);压缩改写前缀后清空。 */
    private val anchors = java.util.IdentityHashMap<Message, Pair<String, UsageMeter.Anchor>>()
    /** 每个角色最近一次请求的固定开销估算;回复进历史时和用量一起记进锚点。 */
    private val lastOverhead = HashMap<String, Int>()

    fun reset(prompt: String) {
        systemPrompt = prompt
        compactions.clear()
        stickyMembers.clear()
        taskRetryWaitMs = 0
        chainIndex = 0
        headError = null
        trialLog.clear()
        lastMainUsed = model
        lastRetryWaitMs = 0L
        lastCallDiagnostics = null
        lastPromptVersion = null
        lastAttemptModel = null
        prevPrefixByRole.clear()
        compressors.values.forEach { it.forget() }
        producers.clear()
        anchors.clear()
        lastOverhead.clear()
    }

    /** 回复进了历史:记下是哪个模型给的;有真实 usage 的记成上下文大小的锚点。 */
    fun noteReply(m: Message, used: ChatModel, usage: Usage?) {
        if (m.role == Role.Assistant) producers[m] = used
        val key = roleKey(used)
        val overhead = lastOverhead[key]
        if (usage != null && overhead != null) anchors[m] = key to UsageMeter.Anchor(usage, overhead)
    }

    /** 发给 [using] 所在角色的整份请求有多大:最近一条该角色带真实用量的回复作锚点,没有就全量估算(见 [UsageMeter])。 */
    private fun requestTokens(messages: List<Message>, using: ChatModel, overhead: Int, scale: Double): Int {
        val key = roleKey(using)
        return UsageMeter.of(messages, overhead, scale) { m -> anchors[m]?.takeIf { it.first == key }?.second }
    }

    /** 本任务里分工是否在起作用:开关打开、轻量模型能接手机步、没因不可恢复的错误停用。 */
    private val routing: Boolean get() = flags.lightSteps && router?.active == true

    /** 开了分工就在运行记录里记每次调用的模型和角色(停用之后也记,便于对比)。 */
    val recordRoles: Boolean get() = flags.lightSteps && router != null

    fun roleOf(m: ChatModel) = if (router?.isLight(m) == true) ROLE_LIGHT else ROLE_MAIN

    /** 这次调用用的成员属于哪个模型组;单模型为 null。子任务没有 router 时回退到主池。 */
    fun groupOf(m: ChatModel): String? {
        router?.groupOf(m)?.let { return it }
        val mainPool = model.asPool()
        return if (mainPool === m || mainPool.owns(m)) mainPool.groupName else null
    }

    private data class LightAttempt(
        val reply: ModelReply?,
        val model: ChatModel,
        val error: Exception?,
        val startedAt: Long,
        val endedAt: Long,
        val diag: CallDiagnostics?,
    )

    private suspend fun runLightAttempt(
        messages: List<Message>,
        specs: List<ToolSpec>,
        picked: ChatModel,
        step: Int,
        onText: ((String) -> Unit)?,
        startedAt: Long,
    ): LightAttempt {
        lastAttemptModel = null
        lastCallDiagnostics = null
        return try {
            val (reply, used) = complete(messages, specs, picked.asPool(), RetryPolicy.NONE, step, onText, CallOptions.LIGHT_TOOL_ONLY)
            LightAttempt(
                reply = reply,
                model = used,
                error = null,
                startedAt = startedAt,
                endedAt = now(),
                diag = lastCallDiagnostics,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelError.TaskBudgetExceeded) {
            throw e
        } catch (e: Exception) {
            LightAttempt(
                reply = null,
                model = lastAttemptModel ?: picked,
                error = e,
                startedAt = startedAt,
                endedAt = now(),
                diag = lastCallDiagnostics,
            )
        }
    }

    private fun emitRejectedLightReply(
        attempt: LightAttempt,
        rejection: LightValidation.Rejected,
        step: Int,
        stepCtx: StepContext,
        picked: ChatModel,
        group: String?,
        attemptNum: Int,
        retryCount: Int,
        retryWaitMs: Long,
        lightRetried: Boolean,
    ) {
        val attemptReply = attempt.reply
        val attemptModel = attempt.model
        val errInfo = attempt.error?.let(ModelErrorDiagnostics::extract)
        val text = attemptReply?.parts?.filterIsInstance<Part.Text>()?.joinToString("") { it.text }?.trim().orEmpty()
        val firstCall = attemptReply?.parts?.firstActionCall()
        val inTok = attemptReply?.usage?.inputTokens ?: 0
        val outTok = attemptReply?.usage?.outputTokens ?: 0
        val cacheTok = attemptReply?.usage?.cachedInputTokens ?: 0
        val usageReported = attemptReply?.usage != null
        val lightOutTokens = attemptReply?.usage?.outputTokens
            ?: attemptReply?.let { TokenEstimate.of(listOf(Message(Role.Assistant, it.parts, Author.Mika))) }
            ?: 0
        val rejectedTool = rejection.tool ?: "None"
        val diag = attempt.diag
        val resolvedProvider = attemptModel.provider ?: diag?.provider
        val costEstimate = UsageCostEstimator.estimate(
            model = attemptModel.name,
            role = ROLE_LIGHT,
            inputTokens = inTok.toLong(),
            cachedTokens = cacheTok.toLong(),
            outputTokens = outTok.toLong(),
            provider = resolvedProvider,
        )
        emit(RunEvent.ModelReply(
            atMs = attempt.endedAt,
            step = step,
            text = RunEvent.clip(Redactor.text(text)),
            tool = rejectedTool,
            argsJson = firstCall?.let { Redactor.args(tools.verb(it.name), it.argsJson, secretField = true) },
            inputTokens = inTok,
            outputTokens = outTok,
            cachedTokens = cacheTok,
            latencyMs = (attempt.endedAt - attempt.startedAt).coerceAtLeast(0L),
            model = attemptModel.name,
            role = ROLE_LIGHT,
            group = group,
            retryWaitMs = retryWaitMs,
            repliedAtMs = null,
            reason = rejection.reason,
            decisionScore = rejection.decisionScore,
            validatorReason = rejection.validatorReason,
            lightTool = rejectedTool,
            lightOutputTokens = lightOutTokens,
            lightRetried = lightRetried,
            provider = resolvedProvider,
            accepted = false,
            attempt = attemptNum,
            retryCount = retryCount,
            httpStatus = errInfo?.httpStatus,
            errorCode = errInfo?.errorCode,
            errorBody = errInfo?.errorBody,
            errorField = errInfo?.errorField,
            usageReported = usageReported,
            hasImages = stepCtx.hasImages,
            vision = picked.capabilities.vision,
            sameApp = stepCtx.sameAppAsLast,
            lastChanged = stepCtx.lastChanged,
            forcedMain = false,
            cacheMissReason = diag?.cacheMissReason,
            systemPromptHash = diag?.systemPromptHash,
            promptVersion = diag?.promptVersion ?: lastPromptVersion,
            toolListHash = diag?.toolListHash,
            promptTransformEffect = diag?.promptTransformEffect,
            compactionTrigger = diag?.compactionTrigger,
            compactionReason = diag?.compactionReason,
            foldedSteps = diag?.foldedSteps,
            capCheckResult = diag?.capCheckResult,
            estimatedInputTokens = diag?.estimatedInputTokens,
            costUsd = costEstimate.costUsd,
            priceInputPerM = costEstimate.price.inputPerM,
            priceOutputPerM = costEstimate.price.outputPerM,
            priceCachedPerM = costEstimate.price.cachedInputPerM,
            priceSource = costEstimate.price.source,
            priceFetchedAt = costEstimate.price.fetchedAt,
            priceTier = costEstimate.price.tier,
            priceFallback = costEstimate.price.fallback,
        ))
    }

    private fun buildEscalationNotice(rejection: LightValidation.Rejected, reply: ModelReply?): Message? {
        if (rejection.reason != "unsafe_tool") return null
        val call = reply?.parts?.firstActionCall()
        val toolName = rejection.tool ?: call?.name ?: "unknown"
        val safeArgs = call?.let {
            Redactor.escalationArgs(tools.verb(it.name), it.name, it.argsJson)
                .trim()
                .take(240)
        }?.takeIf { it.isNotEmpty() && it != "{}" }
        val targetPart = safeArgs?.let { " (target/args: $it)" }.orEmpty()
        val body = buildString {
            append("Light proposed `")
            append(toolName)
            append("`")
            append(targetPart)
            append(" and was rejected (")
            append(rejection.validatorReason)
            append("), so this step was escalated immediately to Main. Execute the needed action or main-only tool directly on the current screen right now instead of calling phone_observe or phone_list_elements to re-observe or re-list the same screen or bouncing back. Perform confirmation taps (such as Confirm, OK, Allow, Continue, 确认, 确定, 删除) directly yourself rather than leaving them for the user (ask_user/hand_to_user), unless the action genuinely spends money (request_confirmation / payment) or requires the user's hands (password, OTP, CAPTCHA).")
        }
        return Message(
            Role.User,
            listOf(Part.Text(ContextBuilder.envelope(ContextKind.Notice, "light_escalation", body))),
            Author.Mika,
        )
    }

    /**
     * 按 [router] 选模型。轻量模型出错、没调工具(不带调用的回复是最终回答,只由主模型给),或要放弃、确认、接管、追问、做不可逆的事,
     * 它的回复不执行(记为 [ROLE_LIGHT] 且 `accepted = false`),这一步改问主模型,改问前用 [restarted] 报新的起问时刻。
     * 返回回复和实际用的模型。
     */
    suspend fun routed(
        history: List<Message>, specs: List<ToolSpec>, track: StepTrack, step: Int, restarted: (Long) -> Unit,
        onText: ((String) -> Unit)? = null,
    ): Pair<ModelReply, ChatModel> {
        val r = router?.takeIf { routing } ?: return askRouted(history, specs, step, onText = onText)
        val upfrontMain = r.shouldRouteToMainUpfront(history, specs, tools::verb)
        val hasImages = history.any { m -> m.parts.any { it is Part.Image } }
        val stepCtx = track.context(step, hasImages).copy(needsMainUpfront = upfrontMain)
        val decision = r.pickWithReason(stepCtx)
        val picked = decision.model
        suspend fun fallbackToMain(
            forcedMain: Boolean,
            escalatedFromLight: Boolean? = null,
            lightRejectionReason: String? = null,
            ephemeralTail: Message? = null,
        ): Pair<ModelReply, ChatModel> {
            val (reply, used) = askRouted(history, specs, step, onText = onText, ephemeralTail = ephemeralTail)
            lastCallDiagnostics = (lastCallDiagnostics ?: CallDiagnostics(provider = used.provider)).copy(
                hasImages = stepCtx.hasImages,
                sameApp = stepCtx.sameAppAsLast,
                lastChanged = stepCtx.lastChanged,
                forcedMain = forcedMain,
                escalatedFromLight = escalatedFromLight,
                lightRejectionReason = lightRejectionReason,
            )
            return reply to used
        }
        if (!decision.usedLight || !r.isLight(picked) || upfrontMain) {
            emit(RunEvent.State(now(), "light_skipped", decision.reason))
            return fallbackToMain(forcedMain = true)
        }
        if (specs.none { !ModelRouter.mainOnly(it.name, tools.verb(it.name)) }) {
            emit(RunEvent.State(now(), "light_skipped", "reason=no_safe_tool step=$step hasImages=$hasImages vision=${picked.capabilities.vision} sameApp=${stepCtx.sameAppAsLast} lastChanged=${stepCtx.lastChanged}"))
            return fallbackToMain(forcedMain = true)
        }

        val asked = now()
        restarted(asked)
        val attempt1 = runLightAttempt(history, specs, picked, step, onText, asked)
        when (val v1 = LightCallValidator.validate(attempt1.reply, specs, tools, autonomous, retried = false)) {
            is LightValidation.Accepted -> {
                r.noteLightAccepted()
                lastCallDiagnostics = (attempt1.diag ?: CallDiagnostics(provider = attempt1.model.provider)).copy(
                    attempt = 1,
                    retryCount = 0,
                    retryWaitMs = attempt1.diag?.retryWaitMs ?: 0L,
                    hasImages = stepCtx.hasImages,
                    vision = picked.capabilities.vision,
                    sameApp = stepCtx.sameAppAsLast,
                    lastChanged = stepCtx.lastChanged,
                    forcedMain = false,
                    lightRetried = false,
                )
                return v1.reply to attempt1.model
            }
            is LightValidation.Rejected -> {
                emitRejectedLightReply(
                    attempt = attempt1,
                    rejection = v1,
                    step = step,
                    stepCtx = stepCtx,
                    picked = picked,
                    group = r.groupOf(picked),
                    attemptNum = 1,
                    retryCount = 0,
                    retryWaitMs = 0L,
                    lightRetried = false,
                )
                val firstReply = attempt1.reply
                val shouldRetryError = firstReply == null
                val shouldRetryNoTool = firstReply != null && firstReply.parts.firstActionCall() == null
                if (!shouldRetryError && !shouldRetryNoTool) {
                    if (v1.reason == "unsafe_tool") r.noteUnsafeEscalation() else r.noteTrouble()
                    emit(RunEvent.State(now(), "light_to_main", v1.tool ?: "no_tool"))
                    onText?.invoke("")
                    restarted(now())
                    return fallbackToMain(
                        forcedMain = true,
                        escalatedFromLight = true,
                        lightRejectionReason = "${v1.reason}:${v1.validatorReason}",
                        ephemeralTail = buildEscalationNotice(v1, attempt1.reply),
                    )
                }

                emit(RunEvent.State(now(), "light_retry", if (shouldRetryError) "model_error" else "no_tool"))
                onText?.invoke("")
                val retryHistory = if (shouldRetryNoTool) {
                    val statePart = history.lastOrNull()?.parts?.filterIsInstance<Part.Text>()
                        ?.lastOrNull { it.text.startsWith("Current place:") }
                    val retryParts = listOfNotNull(Part.Text(LightPrompt.RETRY_REMINDER), statePart)
                    history + Message(Role.User, retryParts, Author.Mika)
                } else {
                    history
                }
                val attempt2Start = now()
                val retryWaitMs = (attempt2Start - asked).coerceAtLeast(0L)
                val attempt2 = runLightAttempt(retryHistory, specs, picked, step, onText, attempt2Start)
                when (val v2 = LightCallValidator.validate(attempt2.reply, specs, tools, autonomous, retried = true)) {
                    is LightValidation.Accepted -> {
                        r.noteLightAccepted()
                        lastRetryWaitMs = retryWaitMs
                        lastCallDiagnostics = (attempt2.diag ?: CallDiagnostics(provider = attempt2.model.provider)).copy(
                            attempt = 2,
                            retryCount = 1,
                            retryWaitMs = retryWaitMs,
                            hasImages = stepCtx.hasImages,
                            vision = picked.capabilities.vision,
                            sameApp = stepCtx.sameAppAsLast,
                            lastChanged = stepCtx.lastChanged,
                            forcedMain = false,
                            lightRetried = true,
                        )
                        return v2.reply to attempt2.model
                    }
                    is LightValidation.Rejected -> {
                        emitRejectedLightReply(
                            attempt = attempt2,
                            rejection = v2,
                            step = step,
                            stepCtx = stepCtx,
                            picked = picked,
                            group = r.groupOf(picked),
                            attemptNum = 2,
                            retryCount = 1,
                            retryWaitMs = retryWaitMs,
                            lightRetried = true,
                        )
                        if (v2.reason == "unsafe_tool") r.noteUnsafeEscalation() else r.noteTrouble()
                        if (attempt2.reply == null && attempt2.error != null) {
                            r.noteLightError()
                        }
                        emit(RunEvent.State(now(), "light_to_main", v2.tool ?: if (attempt2.reply == null) "light_error" else "no_tool"))
                        onText?.invoke("")
                        restarted(now())
                        return fallbackToMain(
                            forcedMain = true,
                            escalatedFromLight = true,
                            lightRejectionReason = "${v2.reason}:${v2.validatorReason}",
                            ephemeralTail = buildEscalationNotice(v2, attempt2.reply),
                        )
                    }
                }
            }
        }
    }

    /**
     * 问 [using](默认主模型):发出的是给它看的那份历史([viewFor])压缩后的副本。
     * 问主模型时走主模型链:[complete] 放弃(重试耗尽或不可重试)且 [Failover] 说换,就换链上下一个再问;
     * 链用完、或 [Failover] 说不换,原样抛出,失败说法和没有备援时一样。问别的模型照旧单发。
     */
    suspend fun ask(history: List<Message>, specs: List<ToolSpec>, step: Int, using: ChatModel = model, onText: ((String) -> Unit)? = null): ModelReply =
        askRouted(history, specs, step, using, onText).first

    /** 同 [ask],返回实际回答的模型(组里可能是任一个成员)。问主模型时同样走主模型链。 */
    suspend fun askRouted(
        history: List<Message>, specs: List<ToolSpec>, step: Int, using: ChatModel = model, onText: ((String) -> Unit)? = null,
        ephemeralTail: Message? = null,
    ): Pair<ModelReply, ChatModel> {
        if (using !== model) return complete(viewFor(history, using), specs, using.asPool(), retry, step, onText, ephemeralTail = ephemeralTail)
        // 整条链都失败时的说法(MK-1 第 2 批):以主模型最初的错误为主、附每个备援错在哪(见 [terminalError]);
        // 只试过一个模型就原样交出,和没有备援时一致。每步先把错误记进 [trialLog]:切过去之后,
        // 后面的步直接由备援起问,主模型的错不在手边,靠这份记录才拼得回整条链的说法。
        val clock = AttemptClock()
        while (true) {
            val current = chain[chainIndex]
            try {
                val result = complete(viewFor(history, current), specs, current.asPool(), retry, step, onText, clock = clock, ephemeralTail = ephemeralTail)
                lastMainUsed = result.second
                return result
            } catch (e: ModelError) {
                if (trialLog.none { it.first == chainIndex }) trialLog += chainIndex to e
                if (chainIndex == 0 && headError == null) headError = e
                val next = Failover.nextIndex(e, chainIndex, chainProviderIds, chain.size) ?: throw terminalError(e)
                onText?.invoke("")
                emit(RunEvent.State(now(), "model_switch", "${current.name} -> ${chain[next].name} ${RetryPolicy.label(e)}"))
                chainIndex = next
            }
        }
    }

    /** 链上不再换了,决定交出哪个错误:预算错误原样;只试过一个模型原样;试过整条链包成 [ModelError.ChainFailed]。 */
    private fun terminalError(e: ModelError): ModelError {
        // 当前错误就是预算错误时以它为准;否则链上较早的预算错误优先于包装(审 T-1112-74a8f2 D1):预算错误不包 ChainFailed,原样交出
        if (e is ModelError.TaskBudgetExceeded || e is ModelError.DailyBudgetExceeded) return e
        trialLog.map { it.second }.firstOrNull {
            it is ModelError.TaskBudgetExceeded || it is ModelError.DailyBudgetExceeded
        }?.let { return it }
        return if (trialLog.size < 2) e
        else ModelError.ChainFailed(headError ?: e, trialLog.map { (index, err) -> chain[index].name to err })
    }

    /**
     * 发给 [to] 的历史:不是 [to] 给出的回复去掉思考内容和调用上的私有数据(这些只能原样回给产生它的那个模型)。
     * 没记到来源的回复视同主模型给的:发给主模型(或含主模型的组)时留着,发给轻量模型或备援时都剥。只看字段在不在,不分服务商。
     */
    private fun viewFor(history: List<Message>, to: ChatModel): List<Message> {
        val stripImages = router?.isLight(to) == true && !to.capabilities.vision && history.any { m -> m.parts.any { it is Part.Image } }
        if (!stripImages && producers.isEmpty() && to === model) return history
        return history.map { m ->
            val from = producers[m]
            val foreign = m.role == Role.Assistant && (from?.let { !belongsTo(to, it) } ?: !belongsTo(to, model))
            val needsForeignStrip = foreign && m.parts.any { it is Part.Thinking || (it is Part.ToolCall && it.opaque != null) }
            val needsImageStrip = stripImages && m.parts.any { it is Part.Image }
            if (!needsForeignStrip && !needsImageStrip) m
            else m.copy(parts = m.parts.mapNotNull { p ->
                when {
                    needsForeignStrip && p is Part.Thinking -> null
                    needsForeignStrip && p is Part.ToolCall && p.opaque != null -> p.copy(opaque = null)
                    needsImageStrip && p is Part.Image -> Part.Text("[screenshot omitted; use UI tree]")
                    else -> p
                }
            })
        }
    }

    /** [from] 是不是 [to] 这个池里的成员;单模型按对象相同。 */
    private fun belongsTo(to: ChatModel, from: ChatModel): Boolean =
        (to as? ModelPool)?.owns(from) ?: (to === from)

    /** 一次调用里第一次真正发出请求的时刻与累计尝试次数;换成员后也保留,等待时长从它起算。 */
    private class AttemptClock {
        var firstAttemptAt: Long? = null
        var attempts: Int = 0
    }

    /**
     * 一次调用:按策略排好成员,逐个尝试;成功返回回复和实际用的模型。
     * 组里有多个候选时,同一成员最多只重试一次,把机会留给下一个;单模型保持原来的重试次数。
     */
    private suspend fun complete(
        history: List<Message>, specs: List<ToolSpec>, pool: ModelPool, policy: RetryPolicy, step: Int,
        onText: ((String) -> Unit)? = null, options: CallOptions = CallOptions.DEFAULT,
        clock: AttemptClock = AttemptClock(), ephemeralTail: Message? = null,
    ): Pair<ModelReply, ChatModel> {
        if (clock.firstAttemptAt == null) lastRetryWaitMs = 0
        // 单模型池不看选择条件:不必为它整份估算历史(长对话下这一步很贵)
        val planned = if (pool is SingleMemberPool) pool.members else pool.ordered(needOf(if (ephemeralTail != null) history + ephemeralTail else history, specs))
        if (planned.isEmpty()) throw ModelError.GroupUnavailable(pool.groupName ?: "", "没有可用成员")
        val stickyKey = roleKey(planned.first().model)
        val pinned = stickyMembers[stickyKey]
        val candidates = if (pinned != null && planned.any { it.model === pinned }) {
            listOf(planned.first { it.model === pinned }) + planned.filterNot { it.model === pinned }
        } else planned
        var last: Throwable? = null
        for ((index, member) in candidates.withIndex()) {
            val using = member.model
            val memberBegan = now()
            try {
                val reply = attemptMember(
                    viewFor(history, using), specs, using, policy, step, onText, clock,
                    maxRetries = if (candidates.size > 1) minOf(policy.maxRetries, 1) else policy.maxRetries,
                    options = options,
                    ephemeralTail = ephemeralTail,
                )
                stickyMembers[roleKey(using)] = using
                pool.noteResult(member, now() - memberBegan, null)
                return reply to using
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModelError) {
                last = e
                pool.noteResult(member, now() - memberBegan, e)
                if (!FailoverPolicy.canFailover(e) || index == candidates.lastIndex) throw e
                onText?.invoke("")
                val next = candidates.getOrNull(index + 1)
                emit(RunEvent.State(
                    now(), "model_route",
                    "${pool.groupName ?: "group"} ${member.label} -> ${next?.label ?: "-"} ${RetryPolicy.label(e)}",
                ))
            } catch (e: Exception) {
                last = e
                pool.noteResult(member, now() - memberBegan, e)
                if (index == candidates.lastIndex) throw e
                onText?.invoke("")
            }
        }
        throw last ?: ModelError.GroupUnavailable(pool.groupName ?: "")
    }

    private fun computeCacheMissReason(
        prev: Triple<String, String, String>?,
        compactionTrigger: String?,
        modelProviderKey: String,
        systemPromptHash: String,
        toolListHash: String,
        promptTransformEffect: String,
        usage: Usage?,
    ): String? = when {
        prev == null -> "cold_start"
        compactionTrigger != null -> "compacted:$compactionTrigger"
        modelProviderKey != prev.third -> "model_or_provider_switched"
        systemPromptHash != prev.first -> if (promptTransformEffect == "modified") "prompt_transform_changed" else "system_prompt_changed"
        toolListHash != prev.second -> "tool_list_changed"
        (usage?.cachedInputTokens ?: 0) == 0 && (usage?.inputTokens ?: 0) > 0 -> "provider_cache_miss"
        else -> null
    }

    /** 对 [using] 的一次完整尝试:压缩、发请求、同成员重试、超长再压。 */
    private suspend fun attemptMember(
        history: List<Message>, specs: List<ToolSpec>, using: ChatModel, policy: RetryPolicy, step: Int,
        onText: ((String) -> Unit)?, clock: AttemptClock, maxRetries: Int, options: CallOptions = CallOptions.DEFAULT,
        ephemeralTail: Message? = null,
    ): ModelReply {
        var retries = 0
        val effectivePrompt = promptTransform?.invoke(systemPrompt) ?: systemPrompt
        val promptTransformEffect = when {
            promptTransform == null -> "none"
            effectivePrompt == systemPrompt -> "unchanged"
            else -> "modified"
        }
        val prompt = if (options.forceToolCall) LightPrompt.ensure(effectivePrompt) else effectivePrompt
        val sysHash = sha256Hex12(prompt)
        val toolHash = sha256Hex12(specs.joinToString("") { "\n\n" + it.name + "\n" + it.description + "\n" + it.parametersJsonSchema })
        val overhead = TokenEstimate.of(prompt) + specs.sumOf { TokenEstimate.of(it.name + it.description + it.parametersJsonSchema) }
        val contextTokens = using.capabilities.contextTokens
        val role = roleKey(using)
        val compressor = compressorFor(using)
        var compaction = compactions[role] ?: CompactionState()
        var overflowRetried = false
        var overflowRewrote = false
        var pendingTrigger: String? = null
        val hasImages = history.any { m -> m.parts.any { it is Part.Image } }
        val modelProviderKey = "${using.name}|${using.provider}"
        while (true) {
            val current = ContextRecovery.ensure(history, compressor.current(history, contextTokens))
            val currentTokens = requestTokens(current, using, overhead, compressor.scale)
            var raw = compressor.compress(history, contextTokens, currentTokens, compaction.aggressive, overhead, using.capabilities.maxImages)
            if (raw.rewritten) anchors.clear()
            var restored = ContextRecovery.ensure(history, raw.messages)
            var sent = if (restored === raw.messages) raw else raw.copy(messages = restored, estimate = TokenEstimate.of(restored))
            fun withTail(ms: List<Message>): List<Message> = if (ephemeralTail == null) ms else ms + ephemeralTail
            fun estimatedTotal(ms: List<Message>): Int {
                val est = TokenEstimate.of(ms)
                return maxOf(
                    requestTokens(ms, using, overhead, compressor.scale),
                    UsageMeter.estimate(est + overhead, compressor.scale),
                    est + overhead,
                )
            }
            fun exceedsCap(ms: List<Message>): Boolean =
                inputTokenCap != null && estimatedTotal(ms) > inputTokenCap
            var finalMessages = withTail(sent.messages)
            var capTrigger: String? = null
            var capCheckResult = "ok"
            if (inputTokenCap != null && exceedsCap(finalMessages)) {
                capTrigger = "cap_100k"
                capCheckResult = "compacted"
                if (!compaction.aggressive || !raw.rewritten) {
                    raw = compressor.compress(history, contextTokens, currentTokens = Int.MAX_VALUE, aggressive = true, fixedOverhead = overhead, maxImages = using.capabilities.maxImages)
                    if (raw.rewritten) anchors.clear()
                    restored = ContextRecovery.ensure(history, raw.messages)
                    sent = if (restored === raw.messages) raw else raw.copy(messages = restored, estimate = TokenEstimate.of(restored))
                    compressor.adopt(history, contextTokens, sent.messages, sent.foldedSteps, aggressive = compaction.aggressive)
                    finalMessages = withTail(sent.messages)
                }
                if (exceedsCap(finalMessages)) {
                    capCheckResult = "pruned"
                    val capped = HistoryPruner.enforceCap(sent.messages, tools.index) { !exceedsCap(withTail(it)) }
                    if (capped !== sent.messages) {
                        anchors.clear()
                        sent = sent.copy(messages = capped, estimate = TokenEstimate.of(capped), rewritten = true)
                        compressor.adopt(history, contextTokens, capped, sent.foldedSteps, aggressive = compaction.aggressive)
                        finalMessages = withTail(sent.messages)
                    }
                }
                if (exceedsCap(finalMessages)) {
                    capCheckResult = "too_long"
                    val prev = prevPrefixByRole[role]
                    val preMiss = computeCacheMissReason(prev, null, modelProviderKey, sysHash, toolHash, promptTransformEffect, null)
                    lastAttemptModel = using
                    lastCallDiagnostics = CallDiagnostics(
                        provider = using.provider,
                        attempt = clock.attempts + 1,
                        retryCount = clock.attempts,
                        retryWaitMs = lastRetryWaitMs,
                        cacheMissReason = preMiss,
                        systemPromptHash = sysHash,
                        promptVersion = lastPromptVersion,
                        toolListHash = toolHash,
                        promptTransformEffect = promptTransformEffect,
                        compactionTrigger = sent.trigger ?: capTrigger ?: pendingTrigger,
                        compactionReason = sent.reason ?: capTrigger?.let { "estimated=${estimatedTotal(finalMessages)} exceeded cap=$inputTokenCap" },
                        foldedSteps = sent.foldedSteps,
                        capCheckResult = capCheckResult,
                        estimatedInputTokens = estimatedTotal(finalMessages),
                        hasImages = hasImages,
                        vision = using.capabilities.vision,
                    )
                    throw ModelError.ContextTooLong()
                }
            }
            val effectiveTrigger = when {
                overflowRewrote -> "overflow"
                capTrigger != null && (sent.trigger == null || (!compaction.aggressive && sent.trigger == "overflow")) -> capTrigger
                else -> sent.trigger ?: pendingTrigger
            }
            val effectiveCompactionReason = when {
                overflowRewrote -> "provider reported context too long"
                effectiveTrigger == "cap_100k" -> sent.reason ?: "estimated=${estimatedTotal(finalMessages)} exceeded cap=$inputTokenCap"
                else -> sent.reason
            }
            if (effectiveTrigger != null) pendingTrigger = effectiveTrigger
            val estInputTokens = estimatedTotal(finalMessages)
            val (nextCompaction, reportCompressed) = CompactionPolicy.sent(compaction, sent.foldedSteps)
            try {
                // 差异的 base 被裁掉或折叠时,下一次写操作要发全量
                if (tools.inlineScreen) tools.setVisibleObservations(visibleReads(finalMessages))
                // 从第一次发出请求起算:失败的请求、退避和换成员都算重试等待,第一次发出之前的准备不算
                val sentAt = now()
                lastRetryWaitMs = clock.firstAttemptAt?.let { sentAt - it } ?: 0L
                if (clock.firstAttemptAt == null) clock.firstAttemptAt = sentAt
                clock.attempts += 1
                val currentAttempt = clock.attempts
                val currentRetryCount = currentAttempt - 1
                val prev = prevPrefixByRole[role]
                val preMissReason = computeCacheMissReason(prev, effectiveTrigger, modelProviderKey, sysHash, toolHash, promptTransformEffect, null)
                lastAttemptModel = using
                lastCallDiagnostics = CallDiagnostics(
                    provider = using.provider,
                    attempt = currentAttempt,
                    retryCount = currentRetryCount,
                    retryWaitMs = lastRetryWaitMs,
                    cacheMissReason = preMissReason,
                    systemPromptHash = sysHash,
                    promptVersion = lastPromptVersion,
                    toolListHash = toolHash,
                    promptTransformEffect = promptTransformEffect,
                    compactionTrigger = effectiveTrigger,
                    compactionReason = effectiveCompactionReason,
                    foldedSteps = sent.foldedSteps,
                    capCheckResult = capCheckResult,
                    estimatedInputTokens = estInputTokens,
                    hasImages = hasImages,
                    vision = using.capabilities.vision,
                )
                suspend fun invokeModel(): ModelReply = withContext(LoopCallScope()) {
                    if (onText == null) {
                        if (options == CallOptions.DEFAULT) using.complete(prompt, finalMessages, specs)
                        else using.complete(prompt, finalMessages, specs, options)
                    } else if (using is StreamingChatModel) {
                        if (options == CallOptions.DEFAULT) using.completeStreaming(prompt, finalMessages, specs, onText)
                        else using.completeStreaming(prompt, finalMessages, specs, options, onText)
                    } else {
                        val res = if (options == CallOptions.DEFAULT) using.complete(prompt, finalMessages, specs)
                        else using.complete(prompt, finalMessages, specs, options)
                        res.also { result ->
                            result.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
                                .takeIf(String::isNotEmpty)?.let(onText)
                        }
                    }
                }
                suspend fun send(): ModelReply =
                    if (currentCoroutineContext()[ConversationMeter] != null && roleOf(using) == ROLE_MAIN)
                        withContext(ConversationMeter.MainRequest(step)) { invokeModel() }
                    else invokeModel()
                val reply = if (trace == null) send() else {
                    val change = when {
                        overflowRewrote -> "overflow_compaction folded=" + sent.foldedSteps
                        sent.rewritten && current != sent.messages -> "history_rewritten folded=" + sent.foldedSteps
                        else -> null
                    }
                    trace.modelCall(step, using, tools::verb, change, finalMessages) { send() }
                }
                overflowRewrote = false
                compaction = nextCompaction
                compactions[role] = compaction
                if (reportCompressed) {
                    emit(RunEvent.Compressed(now(), sent.foldedSteps, role = role, trigger = effectiveTrigger, reason = effectiveCompactionReason))
                }
                val finalMissReason = computeCacheMissReason(prev, effectiveTrigger, modelProviderKey, sysHash, toolHash, promptTransformEffect, reply.usage)
                prevPrefixByRole[role] = Triple(sysHash, toolHash, modelProviderKey)
                lastCallDiagnostics = lastCallDiagnostics?.copy(cacheMissReason = finalMissReason)
                lastOverhead[role] = overhead
                reply.usage?.let {
                    val sentEst = if (ephemeralTail == null) sent.estimate else TokenEstimate.of(finalMessages)
                    compressor.calibrate(sentEst + overhead, it.inputTokens)
                    compressor.noteActual(contextTokens, it.inputTokens)
                }
                return reply
            } catch (e: ModelError.ContextTooLong) {
                onText?.invoke("")
                val (after, again) = CompactionPolicy.overflow(compaction, overflowRetried) {
                    compressor.overflow(history, contextTokens, overhead)
                }
                compaction = after
                compactions[role] = compaction
                overflowRetried = true
                if (again) { anchors.clear(); overflowRewrote = true }
                if (!again) throw e
            } catch (e: ModelError) {
                onText?.invoke("")
                if (e is ModelError.RateLimited && chainIndex < chain.lastIndex && (e.retryAfterMs ?: 0L) > RATE_LIMIT_FAILOVER_WAIT_BUDGET_MS) throw e
                val wait = policy.delayFor(e, retries, taskRetryWaitMs, maxRetries) ?: throw e
                retries += 1
                taskRetryWaitMs += wait
                emit(RunEvent.State(now(), "model_retry", "${roleOf(using)} ${RetryPolicy.label(e)} #$retries ${wait}ms"))
                // 等待中取消任务就此结束
                delay(wait)
            }
        }
    }

    /** 这次请求对成员的要求:要不要工具、有没有图、估算多大。 */
    private fun needOf(history: List<Message>, specs: List<ToolSpec>): ModelNeed {
        val images = history.sumOf { m -> m.parts.count { it is Part.Image } }
        val overhead = TokenEstimate.of(systemPrompt) + specs.sumOf { TokenEstimate.of(it.name + it.description + it.parametersJsonSchema) }
        return ModelNeed(
            toolCalls = specs.isNotEmpty(), vision = images > 0, images = images,
            estimatedTokens = TokenEstimate.of(history) + overhead,
        )
    }

    companion object {
        /** [RunEvent.ModelReply.role] 的取值;分工没起作用时为 null。 */
        const val ROLE_MAIN = "main"
        const val ROLE_LIGHT = "light"
        /** 轻量模型的回复没执行,这一步改问了主模型;仍是一次模型调用,用量照记。 */
        const val ROLE_LIGHT_REJECTED = "light_rejected"
        /** 有备援时只给当前主模型一次 429 重试机会，避免在单模型冷却里耗尽任务预算。 */
        private const val RATE_LIMIT_FAILOVER_RETRIES = 1
        /** 有备援时，超过这段 Retry-After 就直接切换备援。 */
        private const val RATE_LIMIT_FAILOVER_WAIT_BUDGET_MS = 5_000L
    }
}

/** 发出的历史里还完整可见的读取(按编号):带元素列表的读取结果与带 screen 对象的动作结果。 */
internal fun visibleReads(messages: List<Message>): Set<String> {
    val out = HashSet<String>()
    for (m in messages) for (p in m.parts) {
        if (p !is Part.ToolResult) continue
        val obj = try { Json.parseToJsonElement(p.content) as? JsonObject } catch (e: SerializationException) { null } ?: continue
        val shown = (obj["screen"] as? JsonObject) ?: obj.takeIf(ScreenElementTable::hasRows) ?: continue
        (shown["snapshotId"] as? JsonPrimitive)?.contentOrNull?.let(out::add)
    }
    return out
}
