package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.screen.observation.ObsForm
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.wangzan101.mika.core.tool.ToolIndex
import com.wangzan101.mika.core.tool.Verb

/**
 * 压缩时裁掉过时的内容:旧的界面早已不是现状,旧的大结果可以按位置重读,留着只会撑大上下文。
 * 只在 [ContextCompressor] 压缩的那一次调用(平常发过的消息原样再发);只返回副本,调用方保存的完整历史不变;
 * 调用与结果的 id 配对、其他消息都原样保留。
 *
 * 按一条裁剪线([Cut]):线之后的往返原样,线之前的按结果里读到的观察是什么样的东西([ObsForm],见 [BY_FORM])缩短:
 * 界面状态(读界面、动作带回的界面、回看的元素文字)一律换成 [OMITTED];页面内容、命令输出、文件、回复只在结果很大
 * (超过 [BIG_RESULT_TOKENS] 个 token)时把长字段换成 [OMITTED_READ];用户说的(记忆)和参考资料不裁。没有观察的结果按动作认。
 * 缩短后的结果留着结局(ok、错误与原因)和指回证据账本的观察编号([OBS_IDS])。线之前最近的一次全量界面不裁:之后的动作、差异要靠它才看得懂。
 * 线之前的图换成占位(界面画面是可回看的 [olderImage],读到的图片文件是 [OMITTED_IMAGE]);用户的话连同照片不裁。
 *
 * 服务商有明确的图片上限时([limitImages]),超出才从最早的助手图片开始换占位。
 */
object HistoryPruner {
    const val OMITTED = "(screen read earlier, omitted; read it again if needed)"
    const val OMITTED_IMAGE = "(old image omitted)"

    /** 更早的界面画面的占位。 */
    fun olderImage(imageId: Int) = "(earlier screen image omitted; to see it again use view_past_screens with ids [$imageId])"
    const val OMITTED_READ = "(content read earlier, omitted; read it again with nextOffset, path and so on if needed)"
    /** 缩短后的结果里指回证据账本的观察编号。 */
    const val OBS_IDS = "obsIds"
    /**
     * 超过这么多 token 的工具结果算「大结果」。工具结果多是英文和 JSON(约 4 个字符一个 token),
     * 取原来 4000 字符那条线对应的 token 数,英文结果的裁法不变;中文结果按真实占用算,比原来更早算大。
     */
    const val BIG_RESULT_TOKENS = 1_000

    /** 线之前各种观察怎么缩短。 */
    private enum class Shrink { Interface, IfBig, Never }

    /** 按观察的形态定缩短方式:界面状态一律缩短,页面、命令输出、文件、回复、事实只在大时缩短,用户说的不缩。 */
    private val BY_FORM = mapOf(
        ObsForm.Interface to Shrink.Interface,
        ObsForm.Page to Shrink.IfBig, ObsForm.Command to Shrink.IfBig, ObsForm.File to Shrink.IfBig,
        ObsForm.Reply to Shrink.IfBig, ObsForm.Fact to Shrink.IfBig, ObsForm.Image to Shrink.IfBig,
        ObsForm.UserSaid to Shrink.Never,
    )

    /** 模型照着做的参考资料:不按大小裁。 */
    private val REFERENCE_VERBS = setOf(Verb.KnowledgeSearch, Verb.KnowledgeRead, Verb.SkillSearch, Verb.SkillRead, Verb.MemorySearch)
    /** 没带观察的结果:读界面的动作照样按界面状态缩短。 */
    private val INTERFACE_VERBS = setOf(Verb.Observe, Verb.ListElements, Verb.Diagnose)
    private const val LONG_TEXT = 300
    /** 全量的界面读取(回看结果里的元素文字同样会过时;回看本身不算一次读取)。 */
    private val FULL_VERBS = setOf(Verb.Observe, Verb.ListElements)
    private const val SCREEN = "screen"
    private val INTERFACE_KEYS = setOf("elements", ScreenElementTable.KEY, "tree", "treeChanges", "windows", "nodes")
    private val BATCH_VIEW_MARKERS = listOf("\"elements\"", "\"elementTables\"", "\"tree\"", "\"snapshotId\"")
    private val PINNED_VERBS = setOf(Verb.HandToUser, Verb.AskUser, Verb.RequestConfirmation)
    /** 缩短时一定留着的结局字段。 */
    private val OUTCOME = setOf("ok", "error", "message", "recoverable", "exitCode")

    /** 裁剪线:最近 [window] 次工具往返原样,之前的缩短。 */
    data class Cut(val window: Int) {
        init { require(window >= 1) { "need window >= 1" } }

        /** 共 [rounds] 次往返时线之前有几次。 */
        fun before(rounds: Int): Int = (rounds - window).coerceAtLeast(0)
    }

    /** 压缩后仍超:只留最近一次往返原样。 */
    val LAST = Cut(1)

    /** [tools]:按调用的工具名查它做的动作。 */
    fun prune(input: List<Message>, tools: ToolIndex, cut: Cut): List<Message> {
        val starts = input.indices.filter { i -> input[i].role == Role.Assistant && input[i].parts.any { it is Part.ToolCall } }
        val k = cut.before(starts.size)
        if (k == 0) return input
        val line = starts[k]
        val names = callVerbs(input, tools)
        val screens = screenResults(input)
        // 线之前最近的全量界面
        val anchor = (0 until line).lastOrNull { i ->
            input[i].parts.any { it is Part.ToolResult && (names[it.callId] in FULL_VERBS || screens[it.callId] == true) }
        }
        // 用户的话(连同照片)不裁
        return input.mapIndexed { i, m -> if (i >= line || i == anchor || m.author == Author.User) m else shrinkMessage(m, names, screens) }
    }

    /**
     * 压到最重仍放不下时的兜底:从最新的开始,把超过 [BIG_RESULT_TOKENS] 个 token 的工具结果(不论哪种)的长字段换成 [OMITTED_READ],
     * 结局字段和观察编号留着,直到 [fits]。用户的话不动。
     */
    fun shrinkOversized(messages: List<Message>, fits: (List<Message>) -> Boolean): List<Message> {
        val out = messages.toMutableList()
        for (i in out.indices.reversed()) {
            if (fits(out)) break
            val m = out[i]
            if (m.author == Author.User || m.parts.none { it is Part.ToolResult && TokenEstimate.of(it.content) > BIG_RESULT_TOKENS }) continue
            out[i] = m.copy(parts = m.parts.map { p ->
                if (p is Part.ToolResult && TokenEstimate.of(p.content) > BIG_RESULT_TOKENS) withObsIds(p, shrinkRead(p.content)) else p
            })
        }
        return out
    }

    /** [maxImages]:服务商确知的一次请求图片上限;超出时从最早的助手画面开始换占位,用户原话与附件保持原样。 */
    fun limitImages(messages: List<Message>, maxImages: Int): List<Message> {
        require(maxImages >= 0)
        var excess = messages.sumOf { m -> m.parts.count { it is Part.Image } } - maxImages
        if (excess <= 0) return messages
        return messages.map { m ->
            if (m.author == Author.User || excess <= 0) m else {
                val parts = m.parts.map { p ->
                    if (p is Part.Image && excess > 0) {
                        excess--
                        Part.Text(p.screenshotId?.let(::olderImage) ?: OMITTED_IMAGE)
                    } else p
                }
                if (parts == m.parts) m else m.copy(parts = parts)
            }
        }
    }

    private fun shrinkMessage(m: Message, names: Map<String, Verb?>, screens: Map<String, Boolean>): Message {
        val parts = m.parts.map { p ->
            when (p) {
                is Part.Image -> Part.Text(p.screenshotId?.let(::olderImage) ?: OMITTED_IMAGE)
                is Part.ToolResult -> shrinkResult(p, names[p.callId], screens)
                else -> p
            }
        }
        return if (parts == m.parts) m else m.copy(parts = parts)
    }

    private fun shrinkResult(p: Part.ToolResult, verb: Verb?, screens: Map<String, Boolean>): Part.ToolResult {
        val ways = p.reads.mapNotNull { BY_FORM[it.form] }.toSet()
        val content = when {
            Shrink.Never in ways || verb in REFERENCE_VERBS -> p.content
            p.callId in screens -> shrinkScreen(p.content)
            Shrink.Interface in ways || verb in INTERFACE_VERBS -> shrinkInterface(p.content)
            verb == Verb.ViewPastScreens -> shrinkRecall(p.content)
            TokenEstimate.of(p.content) > BIG_RESULT_TOKENS -> shrinkRead(p.content)
            else -> p.content
        }
        return if (content == p.content) p else withObsIds(p, content)
    }

    /** 缩短的结果指回证据账本里那几条观察。 */
    private fun withObsIds(p: Part.ToolResult, content: String): Part.ToolResult {
        val ids = p.reads.map { it.obsId }.distinct()
        val withIds = if (ids.isEmpty()) content else parse(content)?.let { o ->
            JsonObject(o + (OBS_IDS to JsonArray(ids.map(::JsonPrimitive)))).toString()
        } ?: content
        return p.copy(content = withIds)
    }

    fun pruneOlderObservations(
        messages: List<Message>,
        tools: ToolIndex,
        keepFull: Int = ContextCompressor.KEEP_FULL_OBSERVATIONS,
    ): List<Message> {
        require(keepFull >= 0)
        val verbs = callVerbs(messages, tools)
        val obsIndices = messages.indices.filter { isObservationMessage(messages[it], verbs) }
        if (obsIndices.size <= keepFull) return messages
        val pruneObs = obsIndices.take(obsIndices.size - keepFull).toSet()
        val pruneAttachedImages = HashSet<Int>()
        for (idx in pruneObs) {
            if (messages[idx].role == Role.Tool) {
                val next = idx + 1
                if (next < messages.size && next !in obsIndices &&
                    messages[next].role == Role.User && messages[next].author == Author.Mika &&
                    messages[next].parts.any { it is Part.Image }) {
                    pruneAttachedImages += next
                }
            }
        }
        var changed = false
        val out = messages.mapIndexed { i, m ->
            when {
                i in pruneObs -> {
                    val parts = m.parts.map { p ->
                        when {
                            p is Part.ToolResult && isUnprunedUiResult(p, verbs[p.callId]) ->
                                shrinkObservationResult(p, verbs[p.callId])
                            p is Part.Image -> Part.Text(p.screenshotId?.let(::olderImage) ?: OMITTED_IMAGE)
                            m.role == Role.User && m.author == Author.Mika && isUnprunedBatchViewPart(p) ->
                                Part.Text(OMITTED)
                            else -> p
                        }
                    }
                    if (parts == m.parts) m else { changed = true; m.copy(parts = parts) }
                }
                i in pruneAttachedImages -> {
                    val parts = m.parts.map { p ->
                        if (p is Part.Image) Part.Text(p.screenshotId?.let(::olderImage) ?: OMITTED_IMAGE) else p
                    }
                    if (parts == m.parts) m else { changed = true; m.copy(parts = parts) }
                }
                else -> m
            }
        }
        return if (changed) out else messages
    }

    fun enforceCap(
        messages: List<Message>,
        tools: ToolIndex,
        fits: (List<Message>) -> Boolean,
    ): List<Message> {
        if (fits(messages)) return messages
        var out = pruneOlderObservations(messages, tools, ContextCompressor.KEEP_FULL_OBSERVATIONS)
        if (fits(out)) return out
        out = shrinkOversized(out, fits)
        if (fits(out)) return out
        out = pruneOlderObservations(out, tools, 1)
        if (fits(out)) return out
        out = pruneOlderObservations(out, tools, 0)
        if (fits(out)) return out
        out = stripNonUserImages(out)
        if (fits(out)) return out
        out = dropOldestRounds(out, tools, fits)
        if (fits(out)) return out
        return clipOversizedParts(out, fits)
    }

    internal fun observationRounds(messages: List<Message>, tools: ToolIndex, toolStarts: List<Int>): List<Int> {
        if (toolStarts.isEmpty()) return emptyList()
        val verbs = callVerbs(messages, tools)
        return toolStarts.indices.filter { k ->
            val start = toolStarts[k]
            val end = toolStarts.getOrElse(k + 1) { messages.size }
            (start until end).any { idx -> isObservationMessage(messages[idx], verbs) }
        }
    }

    private fun isObservationMessage(m: Message, verbs: Map<String, Verb?>): Boolean {
        if (m.author == Author.User) return false
        if (m.parts.any { it is Part.ToolResult && isUnprunedUiResult(it, verbs[it.callId]) }) return true
        return m.role == Role.User && m.author == Author.Mika && m.parts.any(::isUnprunedBatchViewPart)
    }

    private fun isUnprunedBatchViewPart(p: Part): Boolean =
        p is Part.Text && ContextBuilder.kindOf(p.text) == null && OMITTED !in p.text &&
            BATCH_VIEW_MARKERS.any { it in p.text }

    private fun isUnprunedUiResult(p: Part.ToolResult, verb: Verb?): Boolean {
        val obj = parse(p.content)
        if (obj != null) {
            if (obj[SCREEN] is JsonObject) return true
            if (INTERFACE_KEYS.any { k -> val v = obj[k]; v != null && v != JsonPrimitive(OMITTED) }) return true
            if (verb == Verb.ViewPastScreens) {
                val shots = obj["screenshots"] as? JsonArray
                if (shots != null && shots.any { (it as? JsonObject)?.get("elementsText")?.let { et -> et != JsonPrimitive(OMITTED) } == true }) return true
            }
        }
        if (OMITTED in p.content || OMITTED_READ in p.content || (obj != null && OBS_IDS in obj)) return false
        return verb in INTERFACE_VERBS || verb == Verb.ViewPastScreens || p.reads.any { it.form == ObsForm.Interface }
    }

    private fun shrinkObservationResult(p: Part.ToolResult, verb: Verb?): Part.ToolResult {
        val obj = parse(p.content)
        var content = p.content
        if (obj != null && obj[SCREEN] is JsonObject) {
            content = shrinkScreen(content)
        }
        val afterScreen = parse(content)
        val hasInterfaceKeys = afterScreen != null && INTERFACE_KEYS.any { k ->
            val v = afterScreen[k]; v != null && v != JsonPrimitive(OMITTED)
        }
        content = when {
            verb == Verb.ViewPastScreens -> shrinkRecall(content)
            hasInterfaceKeys || verb in INTERFACE_VERBS || p.reads.any { it.form == ObsForm.Interface } -> shrinkInterface(content)
            else -> content
        }
        return if (content == p.content) p else withObsIds(p, content)
    }

    private fun stripNonUserImages(messages: List<Message>): List<Message> {
        var changed = false
        val out = messages.map { m ->
            if (m.author == Author.User || m.parts.none { it is Part.Image }) m
            else {
                changed = true
                m.copy(parts = m.parts.map { p ->
                    if (p is Part.Image) Part.Text(p.screenshotId?.let(::olderImage) ?: OMITTED_IMAGE) else p
                })
            }
        }
        return if (changed) out else messages
    }

    private fun isSummaryMessage(m: Message): Boolean =
        m.author == Author.Mika && m.role == Role.User &&
            (ContextBuilder.bodyOf(m)?.startsWith(ContextCompressor.HEADER) == true ||
                m.parts.any { it is Part.Text && it.text.startsWith(ContextCompressor.HEADER) })

    private fun isPinnedMessage(m: Message): Boolean =
        m.author == Author.User || ContextBuilder.kindOf(m)?.pinned == true || isSummaryMessage(m)

    private fun dropOldestRounds(
        messages: List<Message>,
        tools: ToolIndex,
        fits: (List<Message>) -> Boolean,
    ): List<Message> {
        var work = messages
        while (!fits(work)) {
            val toolStarts = work.indices.filter { work[it].role == Role.Assistant && work[it].parts.any { p -> p is Part.ToolCall } }
            if (toolStarts.size <= 1) break
            val aIdx = toolStarts.dropLast(1).firstOrNull { idx ->
                work[idx].parts.filterIsInstance<Part.ToolCall>().none { tools.verb(it.name) in PINNED_VERBS }
            } ?: break
            val end = work.indices.firstOrNull { it > aIdx && work[it].role == Role.Assistant } ?: work.size
            val callIds = work[aIdx].parts.filterIsInstance<Part.ToolCall>().map { it.id }.toSet()
            val next = ArrayList<Message>(work.size)
            next.addAll(work.subList(0, aIdx))
            for (j in (aIdx + 1) until end) {
                val m = work[j]
                when {
                    m.role == Role.Tool -> {
                        val remaining = m.parts.filterNot { it is Part.ToolResult && it.callId in callIds }
                        if (remaining.isNotEmpty()) next += m.copy(parts = remaining)
                    }
                    isPinnedMessage(m) -> {
                        val stripped = ContextBuilder.withoutRuntimeState(m)
                        if (stripped.parts.isNotEmpty()) next += stripped
                    }
                }
            }
            next.addAll(work.subList(end, work.size))
            work = next
        }
        while (!fits(work)) {
            val idx = (0 until work.lastIndex).firstOrNull { j ->
                val m = work[j]
                m.role != Role.Tool && !isPinnedMessage(m) && m.parts.none { it is Part.ToolCall }
            } ?: break
            work = work.toMutableList().also { it.removeAt(idx) }
        }
        return work
    }

    private fun clipOversizedParts(
        messages: List<Message>,
        fits: (List<Message>) -> Boolean,
    ): List<Message> {
        val work = messages.toMutableList()
        fun applyPart(msgIdx: Int, partIdx: Int, s: String) {
            val m = work[msgIdx]
            val updated = m.parts.toMutableList()
            updated[partIdx] = when (val p = updated[partIdx]) {
                is Part.Thinking -> p.copy(text = s)
                is Part.ToolResult -> p.copy(content = s)
                is Part.Text -> p.copy(text = s)
                else -> p
            }
            work[msgIdx] = m.copy(parts = updated)
        }
        fun partText(p: Part): String? = when (p) {
            is Part.Thinking -> p.text
            is Part.ToolResult -> p.content
            is Part.Text -> p.text
            else -> null
        }
        if (!fits(emptyList())) {
            for (i in work.indices) {
                if (work[i].author == Author.User) continue
                for (j in work[i].parts.indices) {
                    val raw = partText(work[i].parts[j]) ?: continue
                    if (TokenEstimate.of(raw) > BIG_RESULT_TOKENS) {
                        applyPart(i, j, TokenEstimate.clip(raw, BIG_RESULT_TOKENS))
                    }
                }
            }
            return work
        }
        fun clipCandidateList(candidates: List<Pair<Int, Int>>) {
            for ((msgIdx, partIdx) in candidates) {
                if (fits(work)) return
                val raw = partText(work[msgIdx].parts[partIdx]) ?: continue
                if (TokenEstimate.of(raw) == 0) continue
                applyPart(msgIdx, partIdx, TokenEstimate.clip(raw, 0))
                if (!fits(work)) continue
                var lo = 0
                var hi = TokenEstimate.of(raw)
                while (lo < hi) {
                    val mid = (lo + hi + 1) / 2
                    applyPart(msgIdx, partIdx, TokenEstimate.clip(raw, mid))
                    if (fits(work)) lo = mid else hi = mid - 1
                }
                applyPart(msgIdx, partIdx, TokenEstimate.clip(raw, lo))
                return
            }
        }
        fun ranked(predicate: (Message, Part) -> Boolean): List<Pair<Int, Int>> =
            work.indices.flatMap { i ->
                work[i].parts.indices.mapNotNull { j ->
                    val p = work[i].parts[j]
                    if (!predicate(work[i], p)) null else {
                        val size = partText(p)?.let(TokenEstimate::of) ?: 0
                        if (size > 0) Triple(i, j, size) else null
                    }
                }
            }.sortedByDescending { it.third }.map { it.first to it.second }

        clipCandidateList(ranked { _, p -> p is Part.Thinking })
        if (fits(work)) return work
        clipCandidateList(ranked { m, p -> m.author != Author.User && p is Part.ToolResult })
        if (fits(work)) return work
        clipCandidateList(ranked { m, p -> m.author != Author.User && !isPinnedMessage(m) && p is Part.Text })
        if (fits(work)) return work
        clipCandidateList(ranked { m, p -> m.author != Author.User && isPinnedMessage(m) && !isSummaryMessage(m) && p is Part.Text })
        if (fits(work)) return work
        clipCandidateList(ranked { m, p -> isSummaryMessage(m) && p is Part.Text })
        return work
    }

    /** 长字段(长字符串、大数组/对象)换成 [OMITTED_READ],结局字段留着;不是 JSON 的整段换掉。 */
    private fun shrinkRead(content: String): String {
        val obj = parse(content) ?: return OMITTED_READ
        val firstPass = JsonObject(obj.mapValues { (k, v) ->
            val long = if (v is JsonPrimitive) v.isString && v.content.length > LONG_TEXT else v.toString().length > LONG_TEXT
            if (long && k !in OUTCOME) JsonPrimitive(OMITTED_READ) else v
        })
        if (TokenEstimate.of(firstPass.toString()) <= BIG_RESULT_TOKENS) return firstPass.toString()
        val clippedOutcome = JsonObject(firstPass.mapValues { (k, v) ->
            if (k in OUTCOME) {
                val raw = if (v is JsonPrimitive && v.isString) v.content else v.toString()
                if (TokenEstimate.of(raw) > BIG_RESULT_TOKENS / 4) {
                    if (v is JsonPrimitive && v.isString) JsonPrimitive(TokenEstimate.clip(raw, BIG_RESULT_TOKENS / 4))
                    else JsonPrimitive(OMITTED_READ)
                } else v
            } else v
        })
        if (TokenEstimate.of(clippedOutcome.toString()) <= BIG_RESULT_TOKENS) return clippedOutcome.toString()
        val minimal = buildJsonObject {
            for (k in OUTCOME) clippedOutcome[k]?.let { put(k, it) }
            clippedOutcome["snapshotId"]?.let { put("snapshotId", it) }
            put("omitted", OMITTED_READ)
        }
        return if (TokenEstimate.of(minimal.toString()) <= BIG_RESULT_TOKENS) minimal.toString() else OMITTED_READ
    }

    private fun callVerbs(messages: List<Message>, tools: ToolIndex): Map<String, Verb?> {
        val verbs = HashMap<String, Verb?>()
        messages.forEach { m -> m.parts.filterIsInstance<Part.ToolCall>().forEach { verbs[it.id] = tools.verb(it.name) } }
        return verbs
    }

    /** 带 screen 对象的工具结果:callId → screen 是不是全量。 */
    private fun screenResults(messages: List<Message>): Map<String, Boolean> {
        val out = HashMap<String, Boolean>()
        messages.forEach { m ->
            m.parts.filterIsInstance<Part.ToolResult>().filter { "\"$SCREEN\":{" in it.content }.forEach { r ->
                (parse(r.content)?.get(SCREEN) as? JsonObject)?.let { out[r.callId] = it["full"] == JsonPrimitive(true) }
            }
        }
        return out
    }

    private fun shrinkRecall(content: String): String {
        val obj = parse(content) ?: return content
        val shots = obj["screenshots"] as? JsonArray ?: return content
        val small = JsonArray(shots.map { e ->
            val o = e as? JsonObject
            if (o == null || "elementsText" !in o) e else JsonObject(o + ("elementsText" to JsonPrimitive(OMITTED)))
        })
        return JsonObject(obj + ("screenshots" to small)).toString()
    }

    private fun shrinkScreen(content: String): String {
        val obj = parse(content) ?: return content
        return JsonObject(obj + (SCREEN to JsonPrimitive(OMITTED))).toString()
    }

    private fun parse(content: String): JsonObject? = try {
        Json.parseToJsonElement(content) as? JsonObject
    } catch (e: SerializationException) {
        null
    }

    /** 界面状态:元素表、窗口树和长字段换成 [OMITTED],结局字段留着。 */
    private fun shrinkInterface(content: String): String {
        val obj = parse(content) ?: return content
        val small = obj.mapValues { (k, v) ->
            val long = if (v is JsonPrimitive) v.isString && v.content.length > LONG_TEXT else v.toString().length > LONG_TEXT
            if (k in INTERFACE_KEYS || (long && k !in OUTCOME)) JsonPrimitive(OMITTED) else v
        }
        return JsonObject(small).toString()
    }
}
