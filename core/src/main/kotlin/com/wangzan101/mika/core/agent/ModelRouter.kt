package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.tool.Verb

/** 决定下一步用哪个模型时看的上一步情况。 */
data class StepContext(
    val step: Int,
    val lastOk: Boolean,
    val lastHadGuard: Boolean,
    /** 上一步之后画面变没变;读屏一类不报时为 null。 */
    val lastChanged: Boolean?,
    /** 回放交还、批量停止、核查退回、接管或追问恢复。 */
    val lastWasRecovery: Boolean,
    val sameAppAsLast: Boolean,
    /** 要发出的上下文里有截图;不看图的轻量模型不能接。 */
    val hasImages: Boolean = false,
    /** 这一步需要散文回答、收尾总结或特权工具,应在调用前直接路由给主模型。 */
    val needsMainUpfront: Boolean = false,
    val lastVerb: Verb? = null,
    val requestedTool: String? = null,
    val finalSummary: Boolean = false,
)

/**
 * 实验开关 lightSteps 的分工:简单步给轻量模型,其余给主模型。
 * 轻量模型没配、不支持工具调用时恒用主模型。一步出了错误、守卫提示或画面没变,接下来 [TROUBLE_STEPS] 步强制主模型。
 *
 * 轻量侧沿用多级备援(Light Waterfall Chain):当前轻量模型不可用时依次切到下一个,全部用尽才回退主模型。
 * 主模型、轻量模型都可以是模型组([ModelPool]);[pick] 返回的是池本身,池再按成员选,失败换成员。
 */
class ModelRouter(
    private val main: ChatModel,
    private val light: ChatModel?,
    private val fallbacks: List<ChatModel> = emptyList(),
) {
    private val mainPool: ModelPool = main.asPool()
    /** 轻量侧备援链;每一级都可能是单个模型或一个模型组。 */
    private val lightChain: List<ChatModel> = listOfNotNull(light) + fallbacks
    private val lightPools: List<ModelPool> = lightChain.map { it.asPool() }
    private var lightIndex = 0
    private var forcedMain = 0
    private var disabled = false

    /** 当前活跃的轻量模型(可能是组)。 */
    val currentLight: ChatModel? get() = lightChain.getOrNull(lightIndex)
    private val currentPool: ModelPool? get() = lightPools.getOrNull(lightIndex)
    val lightModelName: String? get() = currentLight?.name
    val lightProviderName: String? get() =
        currentLight?.provider ?: currentPool?.members?.minByOrNull { it.priority }?.model?.provider

    /** 轻量模型能不能接手机步;不能时 lightSteps 不生效。组里只要有一个支持工具的成员就行,具体到每一步再按 need 过滤。 */
    val active: Boolean get() = !disabled && currentPool?.members?.any { it.model.capabilities.toolCalls } == true

    data class RouteDecision(val model: ChatModel, val usedLight: Boolean, val reason: String)

    /**
     * 轻量模型出错:有备援轻量模型就切换到下一个;没有备援时不再永久禁用,
     * 而是冷却 [TROUBLE_STEPS] 步后重新尝试轻量模型。
     */
    fun noteLightError() {
        if (!switchToNextLight()) {
            noteTrouble()
        }
    }

    /** 兼容旧调用:单次错误不永久杀死轻量模型,改为切换备援或冷却 [TROUBLE_STEPS] 步。 */
    fun disable() {
        noteLightError()
    }

    /** 尝试切到下一个轻量备援模型，成功返回 true，无更多备援返回 false（不永久禁用当前轻量模型）。 */
    fun switchToNextLight(): Boolean {
        if (lightIndex + 1 < lightChain.size) {
            lightIndex++
            return true
        }
        return false
    }

    fun pick(ctx: StepContext): ChatModel = pickWithReason(ctx).model

    fun pickWithReason(ctx: StepContext): RouteDecision {
        if (!ctx.lastOk || ctx.lastHadGuard || ctx.lastChanged == false) noteTrouble()
        val l = currentLight?.takeIf { active }
        fun skipped(why: String): RouteDecision {
            val detail = "reason=$why step=${ctx.step} hasImages=${ctx.hasImages} vision=${l?.capabilities?.vision ?: false} sameApp=${ctx.sameAppAsLast} lastChanged=${ctx.lastChanged}"
            return RouteDecision(main, usedLight = false, reason = detail)
        }
        if (forcedMain > 0) {
            forcedMain--
            return skipped("forced_main")
        }
        if (ctx.needsMainUpfront) return skipped("upfront_main")
        if (ctx.finalSummary) return skipped("final_summary")
        if (ctx.lastVerb != null && mainOnly("", ctx.lastVerb)) return skipped("main_only_last_verb:${ctx.lastVerb}")
        if (ctx.requestedTool != null && mainOnly(ctx.requestedTool, null)) return skipped("main_only_tool:${ctx.requestedTool}")
        if (l == null) return skipped("no_light_active")
        if (ctx.step <= 1) return skipped("step_1")
        if (ctx.lastWasRecovery) return skipped("recovery")
        if (!ctx.sameAppAsLast) return skipped("app_changed")
        // Image steps are allowed on Light: vision-capable Light sees the screenshot,
        // and non-vision Light receives the UI tree text with images stripped by ModelCaller.viewFor().
        return RouteDecision(l, usedLight = true, reason = "light")
    }

    /** 出了状况(含轻量模型调用失败、回复被改派给主模型):之后 [TROUBLE_STEPS] 步用主模型。 */
    fun noteTrouble() { forcedMain = TROUBLE_STEPS }

    /** 这个模型是不是轻量侧(含备援)的成员(不是按对象相等:组里换过成员也要认得出);轻量池本身也算。 */
    fun isLight(m: ChatModel): Boolean = lightChain.any { it === m } || lightPools.any { it.owns(m) }

    /** 这个成员属于哪个模型组;单模型为 null。组本身作为成员传进来时也算。 */
    fun groupOf(m: ChatModel): String? {
        val pool = if (isLight(m)) {
            lightChain.firstOrNull { it === m }?.asPool() ?: lightPools.firstOrNull { it.owns(m) }
        } else {
            mainPool.takeIf { it === m || it.owns(m) }
        }
        return pool?.groupName
    }

    fun shouldRouteToMainUpfront(
        history: List<Message>,
        specs: List<ToolSpec>,
        verbOf: (String) -> Verb?,
        finalSummary: Boolean = false,
    ): Boolean = Companion.shouldRouteToMainUpfront(history, specs, verbOf, finalSummary)

    companion object {
        const val TROUBLE_STEPS = 2
        /** 轻量模型给出这些调用(还有 do_steps)时不执行,这一步改问主模型:放弃、确认、接管、追问、沙箱命令都由主模型决定。 */
        private val MAIN_ONLY_VERBS = setOf(
            Verb.EndNotDone, Verb.RequestConfirmation, Verb.HandToUser, Verb.AskUser, Verb.HandleObstacle,
            Verb.PhoneRun, Verb.Collect, Verb.PhoneFind, Verb.PhoneDialog, Verb.PhoneGesture, Verb.PhoneCommand,
            Verb.RootCommand,
        ) +
            // 沙箱里跑的是模型写的命令:只让主模型写
            setOf(Verb.RunCommand, Verb.RunBundledScript, Verb.ReadFile, Verb.WriteFile, Verb.SearchFiles, Verb.ShareFile, Verb.ViewImage, Verb.ListFolderFiles)

        private val MAIN_ONLY_NAMES = setOf(
            DO_STEPS_TOOL,
            "phone_system_command",
            "system_command",
            "run_command",
            "ask_user",
            "end_not_done",
            "request_confirmation",
            "hand_to_user",
            "root_command",
        )

        private val UPFRONT_MAIN_MARKERS = listOf(
            AgentLoop.STEER_MARKER,
            "[TASK_COMPLETE]",
            "The step limit has been reached",
            "The user is discussing or reviewing",
            "The result of the last action has not been read yet",
            "final summary",
            "final_summary",
            "phone_system_command",
            "system_command",
            "run_command",
            "ask_user",
        )

        /** 只能由主模型给出的调用([name] 是工具名,[verb] 是它做的动作)。 */
        fun mainOnly(name: String, verb: Verb?): Boolean = name in MAIN_ONLY_NAMES || verb in MAIN_ONLY_VERBS

        /**
         * 判断当前步是否应在调用前直接路由到主模型(不再先尝试轻量模型):
         * - 收尾总结步、无工具可调、或提供的工具全部是主模型专属工具;
         * - 上一轮助手消息调用了主模型专属工具(如 ask_user / phone_system_command / run_command / request_confirmation / hand_to_user)或未调工具;
         * - 上一轮助手消息之后的新消息里包含插话、收尾/讨论/核对提示或点名要主模型专属工具。
         */
        fun shouldRouteToMainUpfront(
            history: List<Message>,
            specs: List<ToolSpec>,
            verbOf: (String) -> Verb?,
            finalSummary: Boolean = false,
        ): Boolean {
            if (finalSummary || specs.isEmpty() || specs.none { !mainOnly(it.name, verbOf(it.name)) }) {
                return true
            }
            val lastAssistantIdx = history.indexOfLast { it.role == Role.Assistant }
            if (lastAssistantIdx >= 0) {
                val lastCalls = history[lastAssistantIdx].parts.filterIsInstance<Part.ToolCall>()
                if (lastCalls.isEmpty() || lastCalls.any { mainOnly(it.name, verbOf(it.name)) }) {
                    return true
                }
            }
            val tailMessages = if (lastAssistantIdx >= 0) history.subList(lastAssistantIdx + 1, history.size) else history
            for (msg in tailMessages) {
                for (part in msg.parts) {
                    if (part is Part.Text && UPFRONT_MAIN_MARKERS.any { marker -> part.text.contains(marker, ignoreCase = true) }) {
                        return true
                    }
                }
            }
            return false
        }

        /** 这些动作之后的一步算恢复(等过用户)。 */
        internal val RECOVERY_VERBS = setOf(Verb.HandToUser, Verb.AskUser, Verb.RequestConfirmation)
        /** 这些动作会换前台 App 或网页(打开网址、换标签);之后的一步不算同一个地方。 */
        internal val APP_SWITCH_VERBS = setOf(Verb.LaunchApp, Verb.OpenLink, Verb.ShareText, Verb.ShowAlarms, Verb.AddCalendarEvent, Verb.Navigate, Verb.Tabs)
    }
}

/** AgentLoop 用:记下每一步的情况,拼出下一步的 [StepContext]。 */
internal class StepTrack {
    private var ok = true
    private var guard = false
    private var changed: Boolean? = null
    private var recovery = false
    private var app: String? = null
    private var appBefore: String? = null
    private var switched = false
    private var lastVerb: Verb? = null
    private var needsMainUpfront = false

    fun context(step: Int, hasImages: Boolean) =
        StepContext(
            step = step,
            lastOk = ok,
            lastHadGuard = guard,
            lastChanged = changed,
            lastWasRecovery = recovery,
            sameAppAsLast = !switched && app != null && app == appBefore,
            hasImages = hasImages,
            needsMainUpfront = needsMainUpfront,
            lastVerb = lastVerb,
        )

    /** [app] 为 null 表示这一步的结果没报前台 App,沿用之前的。 */
    /** 刚从回放交还、批量停止这类状况里恢复:下一步按恢复步算。在 [after] 之后调用。 */
    fun markRecovery() { recovery = true }

    /** 标记下一步必须直接路由到主模型。 */
    fun markMainUpfront() { needsMainUpfront = true }

    fun after(verb: Verb?, ok: Boolean, guard: Boolean, changed: Boolean?, recovery: Boolean, app: String?) {
        this.ok = ok
        this.guard = guard
        this.changed = changed
        this.recovery = recovery || verb in ModelRouter.RECOVERY_VERBS
        this.lastVerb = verb
        this.needsMainUpfront = verb != null && ModelRouter.mainOnly("", verb)
        appBefore = this.app
        if (app != null) this.app = app
        switched = verb in ModelRouter.APP_SWITCH_VERBS
    }
}
