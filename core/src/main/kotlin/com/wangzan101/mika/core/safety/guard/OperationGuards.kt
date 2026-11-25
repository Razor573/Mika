package com.wangzan101.mika.core.safety.guard

import com.wangzan101.mika.core.prompt.Feedback
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.floor

enum class Severity(val wire: String) { Warn("warn"), Stop("stop") }

/** 每步执行后计算出的屏幕/提取哈希与循环计数遥测。 */
data class StepHashOutcome(
    val screenHash: String?,
    val extractedHash: String?,
    val screenChanged: Boolean?,
    val extractedItemsNew: Boolean?,
    val staleCycleCount: Int,
    val historyReadReason: String? = null,
    val toolSequence: List<String>? = null,
    val breakerTrigger: String? = null,
)

/**
 * 守卫的一条结论。提醒([Severity.Warn])带 [nudge]:说清卡在哪、给几条出路的一段话(发给模型);叫停的没有。
 */
data class GuardVerdict(val code: String, val severity: Severity, val evidence: JsonObject, val nudge: String? = null) {
    fun toJson(): JsonObject = buildJsonObject {
        put("code", code); put("severity", severity.wire); put("evidence", evidence)
        nudge?.let { put("nudge", it) }
    }
}

/** 键排序后的稳定序列化,使参数顺序不影响签名。 */
private fun stable(e: JsonElement): String = when (e) {
    is JsonObject -> e.keys.sorted().joinToString(",", "{", "}") { "${JsonPrimitive(it)}:${stable(e[it]!!)}" }
    is JsonArray -> e.joinToString(",", "[", "]") { stable(it) }
    else -> e.toString()
}

private fun num(e: JsonElement?): Double? = (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

private val SWIPE_KEYS = listOf("startX", "startY", "endX", "endY")

/** 动作签名:忽略 reason,坐标按 50 取整,滑动只记方向。 */
fun actionSignature(tool: String, args: JsonObject): String {
    val n = args.toMutableMap()
    n.remove("reason")
    val x = num(n["x"]); val y = num(n["y"])
    if (x != null && y != null) {
        n["x"] = JsonPrimitive(floor(x / 50).toLong())
        n["y"] = JsonPrimitive(floor(y / 50).toLong())
    }
    val s = SWIPE_KEYS.map { num(n[it]) }
    if (s.all { it != null }) {
        val dx = s[2]!! - s[0]!!; val dy = s[3]!! - s[1]!!
        val vertical = abs(dy) >= abs(dx)
        n["swipeDirection"] = JsonPrimitive("${if (vertical) "V" else "H"}${if ((if (vertical) dy else dx) < 0) "-" else "+"}")
        SWIPE_KEYS.forEach { n.remove(it) }
    }
    return "$tool|${stable(JsonObject(n))}"
}

/**
 * 每次任务一份的操作守卫。只有动作确实没有效果、重复读取没有新内容或工具连续失败时才提醒并最终叫停。
 * 相同的点击/滚动和已见过的界面指纹本身不代表停滞；新的页面或读取内容会清掉无进展计数。
 */
class OperationGuards(private val stepBudget: Int? = null) {

    private var frameId: String? = null
    private var frameHash: Long? = null
    private var acted = false
    /** 上一个动作已按它报的实际效果记过「有没有效果」:接着的界面不再按指纹重复记。 */
    private var effectNoted = false

    /** 连续没有效果的动作数。 */
    private var unchanged = 0
    private var unchangedWarnedAt = 0
    /** 同一轮无效果里,有几次是在树读不出操作位置时点的;单独滚动到底不借用这项证据提前停。 */
    private var treeBlindActions = 0

    /** 到过的每个界面状态(元素表指纹)。 */
    private val seenStates = HashSet<Long>()
    /** 自上次到过新状态起,回到各状态的次数与已提醒到的门槛。 */
    private val visits = HashMap<Long, Int>()
    private val visitsWarnedAt = HashMap<Long, Int>()
    /** 先后看到的各状态(相邻的不同),最近几个。 */
    private val trail = ArrayDeque<Long>()
    private var cycleWarnedAt = 0
    /** 自上次到过新状态起做了几个动作。 */
    private var sinceNewState = 0
    private var sinceNewWarnedAt = 0

    /** 任务的计数;画面每变一次 [epoch] 加一,各段读的计数据此作废。 */
    private val reads = GuardScope(canStop = true)
    /** 跨页面记住读过的内容；返回同一页时不把旧内容当作任务新进展。 */
    private val taskReads = HashSet<Long>()
    private var epoch = 0

    private var consecutiveFailures = 0
    private var unrecoverableFailures = 0
    private val recentFailures = ArrayDeque<JsonObject>()

    private var lastScreenHash: String? = null
    private var lastExtractedHash: String? = null
    private val seenExtractedItemHashes = HashSet<String>()
    private var staleCycleCount = 0
    private var consecutiveCycleBlocks = 0
    private var cyclePendingFromScroll = false
    private var cycleSeenExtract = false
    private var lastToolWasHistoryRead = false
    private var historyReadsSinceScreenChange = 0
    private var phoneSessionActive = false
    private val stalledToolSeq = ArrayDeque<String>()
    private val stalledToolOkSeq = ArrayDeque<Boolean>()
    private val stalledToolEpochSeq = ArrayDeque<Int>()
    private var screenEpoch = 0
    private var consecutiveSequenceBlocks = 0
    private var lastBreakerTrigger: String? = null
    private var consecutiveUnchangedScreen = 0
    private var triedTapOnStuckScreen = false
    private var consecutiveStuckBlocks = 0
    private var consecutiveGiveUpBlocks = 0
    private var consecutiveStuckWarnBlocks = 0
    private var consecutiveAllowedStuckBlocks = 0

    internal val currentStaleCycleCount: Int get() = staleCycleCount
    internal val currentToolSequence: List<String> get() = stalledToolSeq.toList()
    internal val currentBreakerTrigger: String? get() = lastBreakerTrigger
    internal val currentConsecutiveUnchangedScreen: Int get() = consecutiveUnchangedScreen
    internal val currentLastScreenHash: String? get() = lastScreenHash

    var stop: GuardVerdict? = null
        private set
    val unchangedFrames: Int get() = unchanged
    var progressVersion: Long = 0
        private set

    /** 提醒附上卡在哪(guard.<code> 那条,填 [count])和出路;叫停的不附。 */
    private fun verdict(code: String, severity: Severity, evidence: JsonObject, count: Int, waysOut: Boolean = true, period: Int? = null): List<GuardVerdict> {
        val nudge = if (severity == Severity.Warn) listOfNotNull(
            if (period != null) Feedback.format(LEADS.getValue(code), "count" to count, "states" to period) else Feedback.format(LEADS.getValue(code), "count" to count),
            Feedback["guard.ways_out"].takeIf { waysOut },
        ).joinToString(" ") else null
        val v = GuardVerdict(code, severity, evidence, nudge)
        if (severity == Severity.Stop) {
            stop = v
            lastBreakerTrigger = code
        }
        return listOf(v)
    }

    private fun <T> ArrayDeque<T>.pushCapped(v: T, cap: Int = 10) { addLast(v); if (size > cap) removeFirst() }

    /** 保留调用入口；动作签名不参与停滞判定。 */
    fun beforeAction(tool: String, args: JsonObject, diagnostic: Boolean = false, scope: GuardScope = reads): List<GuardVerdict> {
        // 相同动作可以逐页读取新内容；仅凭动作签名不能判断任务停滞。
        return emptyList()
    }

    /** 执行模型单步前检查是否连续重读历史或陷入无进展循环。 */
    fun checkBeforeTool(tool: String): GuardVerdict? {
        lastBreakerTrigger = null
        val stuckActive = consecutiveUnchangedScreen >= STUCK_SCREEN_THRESHOLD
        val repeating = detectRepeatingSequence(stalledToolSeq.toList())
        val repeatingHadSucceededTapOrSwipe = if (repeating != null) {
            val start = stalledToolSeq.size - repeating.size * SEQUENCE_MIN_REPEATS
            (start until stalledToolSeq.size).any { idx ->
                idx in stalledToolOkSeq.indices &&
                    idx in stalledToolEpochSeq.indices &&
                    stalledToolSeq[idx] in STUCK_SCREEN_TAP_OR_SWIPE &&
                    stalledToolOkSeq[idx] &&
                    stalledToolEpochSeq[idx] == screenEpoch
            }
        } else {
            false
        }
        val tapAlreadyTried = triedTapOnStuckScreen || repeatingHadSucceededTapOrSwipe
        if (tool == "end_not_done") {
            if (stuckActive && !tapAlreadyTried) {
                consecutiveGiveUpBlocks += 1
                consecutiveStuckWarnBlocks += 1
                if (consecutiveGiveUpBlocks >= 3 || consecutiveStuckWarnBlocks >= 4) {
                    lastBreakerTrigger = "stuck_screen_giveup_stop"
                    stop = GuardVerdict(
                        "stuck_screen_giveup_stop",
                        Severity.Stop,
                        buildJsonObject {
                            put("consecutiveUnchangedScreen", consecutiveUnchangedScreen)
                            put("blockedTool", tool)
                            put("tapAlreadyTried", false)
                            put("consecutiveGiveUpBlocks", consecutiveGiveUpBlocks)
                            put("consecutiveStuckWarnBlocks", consecutiveStuckWarnBlocks)
                        },
                        "Stopped: screen remained unchanged for $consecutiveUnchangedScreen steps and repeated give-up attempts ignored the requirement to try phone_tap or phone_swipe first.",
                    )
                    return stop
                }
                lastBreakerTrigger = "stuck_screen_giveup_blocked"
                return GuardVerdict(
                    "stuck_screen_giveup_blocked",
                    Severity.Warn,
                    buildJsonObject {
                        put("consecutiveUnchangedScreen", consecutiveUnchangedScreen)
                        put("blockedTool", tool)
                    },
                    "Screen has not changed for $consecutiveUnchangedScreen steps, and no tap or swipe has been tried on the stuck screen yet. Do NOT give up yet: call phone_tap on the next unopened item or phone_swipe to a new area first.",
                )
            }
            return null
        }
        if (!stuckActive && tool == "history_read" && (lastToolWasHistoryRead || (phoneSessionActive && historyReadsSinceScreenChange >= 1))) {
            lastBreakerTrigger = "history_read_consecutive_limit"
            return GuardVerdict(
                "history_read_consecutive_limit",
                Severity.Warn,
                buildJsonObject {
                    put("tool", tool)
                    put("historyReadsSinceScreenChange", historyReadsSinceScreenChange)
                },
                "Do not call history_read again in a row without an intervening phone action that changes the screen. Use the already-read history or take a phone action.",
            )
        }
        if (staleCycleCount >= 2 && tool in CYCLE_REPEAT_TOOLS) {
            consecutiveCycleBlocks += 1
            if (stuckActive && !tapAlreadyTried) consecutiveStuckWarnBlocks += 1
            val shouldStop = if (!stuckActive || tapAlreadyTried) {
                consecutiveCycleBlocks >= 2
            } else {
                consecutiveCycleBlocks >= 3 || consecutiveStuckWarnBlocks >= 4
            }
            if (shouldStop) {
                lastBreakerTrigger = "screen_hash_cycle_stop"
                stop = GuardVerdict(
                    "no_progress_cycle",
                    Severity.Stop,
                    buildJsonObject {
                        put("staleCycleCount", staleCycleCount)
                        put("blockedTool", tool)
                        put("tapAlreadyTried", tapAlreadyTried)
                    },
                    "Stopped: screen and extracted items repeated without progress; no new item or action succeeded.",
                )
                return stop
            }
            lastBreakerTrigger = "screen_hash_cycle_2x"
            return GuardVerdict(
                "no_progress_cycle_blocked",
                Severity.Warn,
                buildJsonObject {
                    put("staleCycleCount", staleCycleCount)
                    put("blockedTool", tool)
                },
                "Screen and extracted items have repeated $staleCycleCount times in a row without progress. Do NOT repeat $tool. Force a different action: tap the next unopened item on screen, change the search query/filter, navigate back, or end the task with a clear reason if no new item exists.",
            )
        }
        if (stuckActive && tool !in STUCK_SCREEN_ALLOWED_TOOLS) {
            consecutiveStuckBlocks += 1
            if (!tapAlreadyTried) consecutiveStuckWarnBlocks += 1
            val shouldStop = if (tapAlreadyTried) {
                consecutiveStuckBlocks >= 2
            } else {
                consecutiveStuckBlocks >= 3 || consecutiveStuckWarnBlocks >= 4
            }
            if (shouldStop) {
                lastBreakerTrigger = "stuck_screen_3x_stop"
                stop = GuardVerdict(
                    "stuck_screen",
                    Severity.Stop,
                    buildJsonObject {
                        put("consecutiveUnchangedScreen", consecutiveUnchangedScreen)
                        put("blockedTool", tool)
                        put("tapAlreadyTried", tapAlreadyTried)
                    },
                    if (tapAlreadyTried) {
                        "Stopped: screen remained unchanged after trying a tap or swipe and no further progress was made."
                    } else {
                        "Stopped: screen remained unchanged for $consecutiveUnchangedScreen steps and repeated warnings without trying a tap or swipe were ignored."
                    },
                )
                return stop
            }
            lastBreakerTrigger = "stuck_screen_3x"
            val nextGuidance = if (tapAlreadyTried) {
                "Screen has remained unchanged for $consecutiveUnchangedScreen steps even after a tap/swipe. Try another phone_tap or phone_swipe on a different area, or call end_not_done with a clear reason."
            } else {
                "Screen has not changed for $consecutiveUnchangedScreen steps in a row. Do NOT repeat $tool. Try a real phone action first: call phone_tap on the next unopened item or phone_swipe to a new area before giving up."
            }
            return GuardVerdict(
                "stuck_screen_blocked",
                Severity.Warn,
                buildJsonObject {
                    put("consecutiveUnchangedScreen", consecutiveUnchangedScreen)
                    put("blockedTool", tool)
                    put("tapAlreadyTried", tapAlreadyTried)
                },
                nextGuidance,
            )
        }
        if (repeating != null && tool in repeating) {
            val seqLabel = repeating.joinToString("->")
            consecutiveSequenceBlocks += 1
            if (stuckActive && !tapAlreadyTried) consecutiveStuckWarnBlocks += 1
            val shouldStop = if (!stuckActive || tapAlreadyTried) {
                consecutiveSequenceBlocks >= 2
            } else {
                consecutiveSequenceBlocks >= 3 || consecutiveStuckWarnBlocks >= 4
            }
            if (shouldStop) {
                lastBreakerTrigger = "tool_sequence_3x_stop:$seqLabel"
                stop = GuardVerdict(
                    "tool_sequence_loop",
                    Severity.Stop,
                    buildJsonObject {
                        put("pattern", JsonArray(repeating.map(::JsonPrimitive)))
                        put("repeats", SEQUENCE_MIN_REPEATS)
                        put("blockedTool", tool)
                        put("tapAlreadyTried", tapAlreadyTried)
                    },
                    "Stopped: tool sequence '$seqLabel' repeated ${SEQUENCE_MIN_REPEATS}+ times without progress toward the goal.",
                )
                return stop
            }
            lastBreakerTrigger = "tool_sequence_3x:$seqLabel"
            val nudge = when (tool) {
                "phone_tap", "web_tap" -> {
                    val swipeTool = if (tool == "phone_tap") "phone_swipe" else "web_swipe"
                    val backTool = if (tool == "phone_tap") "phone_back" else "web_back"
                    val alternatives = buildList {
                        if (swipeTool !in repeating) add("swipe to a new area ($swipeTool)")
                        if (backTool !in repeating) add("navigate back ($backTool)")
                        if (isEmpty()) add("try a different tool")
                    }
                    val tail = if (!stuckActive || tapAlreadyTried) {
                        ", or call end_not_done with a clear reason if nothing works."
                    } else {
                        " before giving up."
                    }
                    "Tool sequence '$seqLabel' repeated $SEQUENCE_MIN_REPEATS times in a row with no progress toward the goal. " +
                        "Do NOT call $tool again right now (repeating it will stop the task). " +
                        "Instead, ${alternatives.joinToString(", ")}$tail"
                }
                "phone_swipe", "web_swipe" -> {
                    val tapTool = if (tool == "phone_swipe") "phone_tap" else "web_tap"
                    val backTool = if (tool == "phone_swipe") "phone_back" else "web_back"
                    val alternatives = buildList {
                        if (tapTool !in repeating) add("tap an unopened item ($tapTool)")
                        if (backTool !in repeating) add("navigate back ($backTool)")
                        if (isEmpty()) add("try a different tool")
                    }
                    val tail = if (!stuckActive || tapAlreadyTried) {
                        ", or call end_not_done with a clear reason if nothing works."
                    } else {
                        " before giving up."
                    }
                    "Tool sequence '$seqLabel' repeated $SEQUENCE_MIN_REPEATS times in a row with no progress toward the goal. " +
                        "Do NOT call $tool again right now (repeating it will stop the task). " +
                        "Instead, ${alternatives.joinToString(", ")}$tail"
                }
                else -> {
                    val isWeb = tool.startsWith("web_") || repeating.any { it.startsWith("web_") }
                    val tapTool = if (isWeb) "web_tap" else "phone_tap"
                    val swipeTool = if (isWeb) "web_swipe" else "phone_swipe"
                    val backTool = if (isWeb) "web_back" else "phone_back"
                    val hasTap = "phone_tap" in repeating || "web_tap" in repeating
                    val hasSwipe = "phone_swipe" in repeating || "web_swipe" in repeating
                    val hasBack = "phone_back" in repeating || "web_back" in repeating
                    val alternatives = buildList {
                        if (!hasTap) add("tap the next unopened item ($tapTool)")
                        if (!hasSwipe) add("swipe to a new area ($swipeTool)")
                        if (!hasBack) add("navigate back ($backTool)")
                        if (!stuckActive) add("change the search query/filter")
                        if (isEmpty()) add("try a completely different tool")
                    }
                    val tail = if (!stuckActive || tapAlreadyTried) {
                        ", or call end_not_done with a clear reason if no new item exists."
                    } else {
                        " first before giving up."
                    }
                    "Tool sequence '$seqLabel' repeated $SEQUENCE_MIN_REPEATS times in a row with no progress toward the goal. " +
                        "Do NOT repeat $tool. Force a different action: ${alternatives.joinToString(", ")}$tail"
                }
            }
            return GuardVerdict(
                "tool_sequence_loop_blocked",
                Severity.Warn,
                buildJsonObject {
                    put("pattern", JsonArray(repeating.map(::JsonPrimitive)))
                    put("repeats", SEQUENCE_MIN_REPEATS)
                    put("blockedTool", tool)
                },
                nudge,
            )
        }
        if (consecutiveUnchangedScreen >= STUCK_SCREEN_MAX_UNCHANGED) {
            consecutiveAllowedStuckBlocks += 1
            if (!tapAlreadyTried) consecutiveStuckWarnBlocks += 1
            if (consecutiveAllowedStuckBlocks >= 2 || (!tapAlreadyTried && consecutiveStuckWarnBlocks >= 4)) {
                lastBreakerTrigger = "stuck_screen_3x_stop"
                stop = GuardVerdict(
                    "stuck_screen",
                    Severity.Stop,
                    buildJsonObject {
                        put("consecutiveUnchangedScreen", consecutiveUnchangedScreen)
                        put("blockedTool", tool)
                        put("tapAlreadyTried", tapAlreadyTried)
                    },
                    if (tapAlreadyTried) {
                        "Stopped: screen remained unchanged for $consecutiveUnchangedScreen steps even after trying a tap or swipe and no further progress was made."
                    } else {
                        "Stopped: screen remained unchanged for $consecutiveUnchangedScreen steps without trying a tap or swipe or making progress."
                    },
                )
                return stop
            }
            lastBreakerTrigger = "stuck_screen_3x"
            val guidance = if (tapAlreadyTried) {
                "Screen has remained unchanged for $consecutiveUnchangedScreen steps despite multiple actions. Try another screen-changing action or call end_not_done with a clear reason."
            } else {
                "Screen has remained unchanged for $consecutiveUnchangedScreen steps. Do NOT keep cycling $tool without effect: call phone_tap on an unopened item or phone_swipe to a new area first."
            }
            return GuardVerdict(
                "stuck_screen_blocked",
                Severity.Warn,
                buildJsonObject {
                    put("consecutiveUnchangedScreen", consecutiveUnchangedScreen)
                    put("blockedTool", tool)
                    put("tapAlreadyTried", tapAlreadyTried)
                },
                guidance,
            )
        }
        return null
    }

    internal fun blockedTelemetry(
        tool: String,
        screenHash: String? = lastScreenHash,
        historyReadReason: String? = null,
    ): StepHashOutcome {
        val effectiveHash = screenHash ?: lastScreenHash
        return StepHashOutcome(
            screenHash = effectiveHash,
            extractedHash = lastExtractedHash,
            screenChanged = effectiveHash?.let { false },
            extractedItemsNew = null,
            staleCycleCount = staleCycleCount,
            historyReadReason = historyReadReason,
            toolSequence = (stalledToolSeq.toList() + tool).takeLast(SEQUENCE_WINDOW_LOG),
            breakerTrigger = lastBreakerTrigger ?: stop?.code,
        )
    }

    internal fun snapshotTelemetry(
        tool: String,
        screenHash: String? = lastScreenHash,
        historyReadReason: String? = null,
    ): StepHashOutcome {
        val screenChanged = screenHash?.let { h ->
            if (lastScreenHash == null) true else h != lastScreenHash
        }
        return StepHashOutcome(
            screenHash = screenHash,
            extractedHash = lastExtractedHash,
            screenChanged = screenChanged,
            extractedItemsNew = null,
            staleCycleCount = staleCycleCount,
            historyReadReason = historyReadReason,
            toolSequence = stalledToolSeq.toList().takeLast(SEQUENCE_WINDOW_LOG).takeIf { it.isNotEmpty() },
            breakerTrigger = lastBreakerTrigger,
        )
    }

    /** 记录工具执行后的屏幕/提取哈希与周期计数变化。 */
    fun recordStepOutcome(
        tool: String,
        screenHash: String?,
        extractedHash: String?,
        itemHashes: List<String>,
        writing: Boolean,
        ok: Boolean,
        changed: Boolean? = null,
    ): StepHashOutcome {
        if (tool.startsWith("phone_")) phoneSessionActive = true
        val screenChanged = screenHash?.let { h -> lastScreenHash == null || h != lastScreenHash }
        val extractedItemsNew = if (itemHashes.isNotEmpty()) itemHashes.any { it !in seenExtractedItemHashes } else null
        seenExtractedItemHashes.addAll(itemHashes)

        val actionEpoch = screenEpoch
        if (screenChanged == true || extractedItemsNew == true || (screenChanged == null && tool in STUCK_SCREEN_TAP_OR_SWIPE && ok && changed == true)) {
            screenEpoch += 1
            staleCycleCount = 0
            consecutiveCycleBlocks = 0
            cyclePendingFromScroll = false
            cycleSeenExtract = (tool in CYCLE_ADVANCE_TOOLS && extractedItemsNew == true)
            consecutiveUnchangedScreen = 0
            triedTapOnStuckScreen = false
            consecutiveStuckBlocks = 0
            consecutiveGiveUpBlocks = 0
            consecutiveStuckWarnBlocks = 0
            consecutiveAllowedStuckBlocks = 0
            if (screenChanged == true && lastScreenHash != null) {
                historyReadsSinceScreenChange = 0
            }
        } else if (screenChanged == false) {
            if (ok && consecutiveUnchangedScreen >= STUCK_SCREEN_THRESHOLD && tool in STUCK_SCREEN_TAP_OR_SWIPE) {
                triedTapOnStuckScreen = true
                consecutiveStuckBlocks = 0
                consecutiveGiveUpBlocks = 0
                consecutiveStuckWarnBlocks = 0
                consecutiveAllowedStuckBlocks = 0
            }
            consecutiveUnchangedScreen += 1
            if (
                tool in CYCLE_TRACKED_TOOLS &&
                screenHash == lastScreenHash &&
                (extractedHash == null || extractedHash == lastExtractedHash || extractedItemsNew == false ||
                    (tool in CYCLE_ADVANCE_TOOLS && lastExtractedHash == null))
            ) {
                when (tool) {
                    "phone_scroll" -> {
                        if (cyclePendingFromScroll || !cycleSeenExtract) {
                            staleCycleCount += 1
                        } else {
                            cyclePendingFromScroll = true
                            staleCycleCount += 1
                        }
                    }
                    in CYCLE_ADVANCE_TOOLS -> {
                        cyclePendingFromScroll = false
                        cycleSeenExtract = true
                        staleCycleCount += 1
                    }
                }
            }
        } else if (ok && consecutiveUnchangedScreen >= STUCK_SCREEN_THRESHOLD && tool in STUCK_SCREEN_TAP_OR_SWIPE) {
            triedTapOnStuckScreen = true
            consecutiveStuckBlocks = 0
            consecutiveGiveUpBlocks = 0
            consecutiveStuckWarnBlocks = 0
            consecutiveAllowedStuckBlocks = 0
        }

        val goalProgressed = extractedItemsNew == true ||
            (tool in GOAL_PROGRESS_TOOLS && ok && screenChanged != false && changed != false)
        if (goalProgressed) {
            stalledToolSeq.clear()
            stalledToolOkSeq.clear()
            stalledToolEpochSeq.clear()
            consecutiveSequenceBlocks = 0
            lastBreakerTrigger = stop?.code
        } else {
            stalledToolSeq.pushCapped(tool, cap = SEQUENCE_TRACK_KEEP)
            stalledToolOkSeq.pushCapped(ok, cap = SEQUENCE_TRACK_KEEP)
            stalledToolEpochSeq.pushCapped(actionEpoch, cap = SEQUENCE_TRACK_KEEP)
            val seqPattern = detectRepeatingSequence(stalledToolSeq.toList())
            lastBreakerTrigger = stop?.code ?: when {
                seqPattern != null -> "tool_sequence_3x:${seqPattern.joinToString("->")}"
                consecutiveUnchangedScreen >= STUCK_SCREEN_THRESHOLD -> "stuck_screen_3x"
                else -> null
            }
        }
        val loggedSeq = stalledToolSeq.toList().takeLast(SEQUENCE_WINDOW_LOG).takeIf { it.isNotEmpty() }

        lastScreenHash = screenHash ?: lastScreenHash
        lastExtractedHash = extractedHash ?: lastExtractedHash
        lastToolWasHistoryRead = (tool == "history_read")
        if (tool == "history_read" && ok) {
            historyReadsSinceScreenChange += 1
        }
        return StepHashOutcome(
            screenHash = screenHash,
            extractedHash = extractedHash,
            screenChanged = screenChanged,
            extractedItemsNew = extractedItemsNew,
            staleCycleCount = staleCycleCount,
            toolSequence = loggedSeq,
            breakerTrigger = lastBreakerTrigger,
        )
    }

    /** 一个会改变界面的动作要做了。 */
    fun noteAction() {
        acted = true
        effectNoted = false
        sinceNewState += 1
    }

    /**
     * 动作报回的实际效果(effect 的 kind,见 core.uistate.EffectKind):no_effect 记一次「没有效果」,
     * 明显变了、换了 App 或换了页就从头计;小的局部变化也从头计。
     * 同一轮无效果中有两次 [treeBlind] 时先提醒,总次数到三次就叫停:连画面也没有可见变化,
     * 既不能用控件树查隐藏状态,也不该换着坐标一直试。只有一次空树证据时仍走普通阶梯,
     * 不把之前无关的滚动到底等无效果当成「注入不响应」的佐证。
     */
    fun effect(kind: String, treeBlind: Boolean = false): List<GuardVerdict> {
        effectNoted = true
        when (kind) {
            NO_EFFECT -> {
                unchanged += 1
                if (treeBlind) treeBlindActions += 1
            }
            else -> { resetNoEffect(); return emptyList() }
        }
        return noProgress()
    }

    private fun resetNoEffect() {
        unchanged = 0
        unchangedWarnedAt = 0
        treeBlindActions = 0
    }

    private fun noProgress(): List<GuardVerdict> {
        if (treeBlindActions >= 2) {
            return verdict("no_progress", if (unchanged >= 3) Severity.Stop else Severity.Warn, buildJsonObject {
                put("unchanged", unchanged); put("treeBlind", true); put("treeBlindActions", treeBlindActions)
            }, unchanged)
        }
        val rung = GuardRules.climb(unchanged, GuardRules.NO_EFFECT_LADDER, unchangedWarnedAt, canStop = true) ?: return emptyList()
        unchangedWarnedAt = rung.threshold
        return verdict("no_progress", rung.severity, buildJsonObject { put("unchanged", unchanged) }, unchanged)
    }

    /**
     * 模型拿到了一份界面(读界面,或动作结果里带的),[hash] 是它的指纹。只有「动过之后界面仍一样」才算没有效果
     * (动作已报过效果的不再重复记);单纯反复看不算。动作之后到了哪个状态还用来看回到同一状态、兜圈、一直没到新状态。
     */
    fun frame(id: String, hash: Long): List<GuardVerdict> {
        if (frameId == id) return emptyList()
        val same = frameHash == hash
        val didAct = acted
        val noted = effectNoted
        acted = false
        effectNoted = false
        frameId = id
        frameHash = hash
        val out = mutableListOf<GuardVerdict>()
        if (!same) {
            pageChanged()
            if (trail.lastOrNull() != hash) {
                trail.addLast(hash)
                while (trail.size > TRAIL_KEEP) trail.removeFirst()
            }
            if (seenStates.add(hash)) progress() else out += arrived(hash)
            out += cycle()
        }
        if (didAct && !noted) {
            if (same) { unchanged += 1; out += noProgress() } else resetNoEffect()
        }
        // 旧界面本身不能证明失败，只提醒模型评估是否有新内容或换路线。
        if (didAct) out += noNewState()
        return out
    }

    /** 动作之后回到一个已到过的状态。正在兜圈时由 [cycle] 说,这里不重复。 */
    private fun arrived(hash: Long): List<GuardVerdict> {
        val n = (visits[hash] ?: 1) + 1
        visits[hash] = n
        if (GuardRules.cycle(trail.toList()) != null) return emptyList()
        val rung = GuardRules.climb(n, GuardRules.sameStateLadder(stepBudget), visitsWarnedAt[hash] ?: 0, canStop = true) ?: return emptyList()
        visitsWarnedAt[hash] = rung.threshold
        return verdict("same_state", rung.severity, buildJsonObject { put("visits", n) }, n)
    }

    private fun cycle(): List<GuardVerdict> {
        val c = GuardRules.cycle(trail.toList())
        if (c == null) { cycleWarnedAt = 0; return emptyList() }
        val rung = GuardRules.climb(c.rounds, GuardRules.cycleLadder(stepBudget), cycleWarnedAt, canStop = true) ?: return emptyList()
        cycleWarnedAt = rung.threshold
        return verdict("state_cycle", rung.severity, buildJsonObject { put("states", c.period); put("rounds", c.rounds) }, c.rounds, period = c.period)
    }

    private fun noNewState(): List<GuardVerdict> {
        val rung = GuardRules.climb(sinceNewState, GuardRules.noNewStateLadder(stepBudget), sinceNewWarnedAt, canStop = false) ?: return emptyList()
        sinceNewWarnedAt = rung.threshold
        return verdict("no_new_state", rung.severity, buildJsonObject { put("actions", sinceNewState) }, sinceNewState)
    }

    /**
     * 一次读屏/读网页/网页脚本:画面没变、也没做有效操作时反复读,读不出新东西(要找的东西不在这一页上)。
     * [fresh] 为 true 表示这次读到了之前没读到过的内容(如读整页换了起点),不算重读。
     * 换着工具、换着脚本读也算:页面没变,答案就是页面上现有的这些。
     * [window] 给了就在它里面单独计数(页面脚本一次调用里的读),不动任务的计数。
     */
    fun read(tool: String, fresh: Boolean, window: GuardScope = reads, fingerprint: Long? = null): List<GuardVerdict> {
        if (fresh) {
            if (fingerprint == null || taskReads.add(fingerprint)) progress()
            return emptyList()
        }
        window.sync(epoch)
        window.rereads += 1
        val n = window.rereads
        val rung = GuardRules.climb(n, GuardRules.REREAD_LADDER, window.warnedAt, window.canStop) ?: return emptyList()
        window.warnedAt = rung.threshold
        return verdict("read_loop", rung.severity, buildJsonObject {
            put("tool", tool); put("rereadsOfUnchangedPage", n)
        }, n)
    }

    /** 这份读到的结果是不是画面上次变化以来头一回读到。 */
    fun firstSeen(fingerprint: Long, window: GuardScope = reads): Boolean {
        window.sync(epoch)
        return window.seen.add(fingerprint)
    }

    /** 画面变了(或做了有效的操作):之前读到的不再作数(所有读的计数一起作废)。 */
    fun pageChanged() {
        epoch += 1
    }

    private fun progress() {
        progressVersion += 1
        resetNoEffect()
        sinceNewState = 0
        sinceNewWarnedAt = 0
        if (stop?.code == "no_progress" || stop?.code == "read_loop") {
            if (lastBreakerTrigger == stop?.code) lastBreakerTrigger = null
            stop = null
        }
    }

    fun result(tool: String, content: JsonObject, neutral: Boolean = false): List<GuardVerdict> {
        val ok = (content["ok"] as? JsonPrimitive)?.booleanOrNull
        val countsAsFailure = (content["counts_as_failure"] as? JsonPrimitive)?.booleanOrNull
        if (neutral || countsAsFailure == false || ok != false) {
            consecutiveFailures = 0
            unrecoverableFailures = 0
            return emptyList()
        }
        val recoverable = (content["recoverable"] as? JsonPrimitive)?.booleanOrNull != false
        consecutiveFailures += 1
        if (!recoverable) unrecoverableFailures += 1
        recentFailures.pushCapped(buildJsonObject {
            put("tool", tool); put("recoverable", recoverable)
            (content["error"] as? JsonPrimitive)?.takeIf { it.isString }?.let { put("error", it.content) }
        })
        val severity = GuardRules.failures(consecutiveFailures, unrecoverableFailures) ?: return emptyList()
        return verdict("too_many_failures", severity, buildJsonObject {
            put("consecutive", consecutiveFailures)
            put("unrecoverable", unrecoverableFailures)
            put("recent", JsonArray(recentFailures.toList().takeLast(5)))
        }, consecutiveFailures, waysOut = consecutiveFailures >= GuardRules.FAILURE_WAYS_OUT || unrecoverableFailures > 0)
    }

    private companion object {
        const val NO_EFFECT = "no_effect"
        /** 记最近多少个状态(够看出最长的兜圈安全网)。 */
        const val TRAIL_KEEP = 64

        val CYCLE_REPEAT_TOOLS = setOf(
            "phone_scroll", "phone_observe", "phone_extract", "phone_collect",
            "phone_list_elements", "phone_query", "history_read",
        )
        val CYCLE_TRACKED_TOOLS = setOf("phone_scroll", "phone_observe", "phone_extract", "phone_collect")
        val CYCLE_ADVANCE_TOOLS = setOf("phone_extract", "phone_collect")
        val GOAL_PROGRESS_TOOLS = setOf(
            "phone_tap", "phone_type_text", "phone_clear_text", "phone_type", "phone_input",
            "phone_launch_app", "phone_open_link", "phone_set_wheel",
            "do_each", "phone_run", "do_steps",
            "web_tap", "web_type_text", "web_clear_text", "web_click", "web_type",
            "web_navigate", "web_interact", "web_open",
            // A successful transcription returns new text, so it is
            // progress, not a loop: it clears the tool sequence. Without
            // this, audio_transcribe called repeatedly (success, then
            // unknown_tool while transcription is off, then success
            // again) builds up a 3x sequence and the next offered call
            // gets blocked as tool_sequence_loop_blocked.
            "audio_transcribe",
        )
        const val STUCK_SCREEN_THRESHOLD = 3
        const val STUCK_SCREEN_MAX_UNCHANGED = 12
        val STUCK_SCREEN_TAP_OR_SWIPE = setOf("phone_tap", "phone_swipe", "web_tap", "web_swipe")
        val STUCK_SCREEN_ALLOWED_TOOLS = STUCK_SCREEN_TAP_OR_SWIPE + setOf(
            "phone_type_text", "phone_clear_text", "phone_type", "phone_input",
            "phone_back", "phone_press_key",
            "phone_launch_app", "phone_open_link", "phone_set_wheel", "phone_gesture",
            "phone_dialog", "phone_run", "do_each", "do_steps",
            "web_type_text", "web_clear_text", "web_click", "web_type",
            "web_back", "web_press_key", "web_navigate", "web_interact", "web_open", "web_upload", "web_tabs",
            "ask_user", "hand_to_user", "request_confirmation", "handle_obstacle",
        )
        const val SEQUENCE_TRACK_KEEP = 16
        const val SEQUENCE_WINDOW_LOG = 8
        const val SEQUENCE_MIN_REPEATS = 3
        const val SEQUENCE_MAX_PERIOD = 4

        internal fun detectRepeatingSequence(seq: List<String>, minRepeats: Int = SEQUENCE_MIN_REPEATS, maxPeriod: Int = SEQUENCE_MAX_PERIOD): List<String>? {
            for (period in 1..maxPeriod) {
                val needed = period * minRepeats
                if (seq.size < needed) continue
                val start = seq.size - needed
                val unit = seq.subList(seq.size - period, seq.size)
                var allMatch = true
                for (r in 0 until minRepeats - 1) {
                    val off = start + r * period
                    if (seq.subList(off, off + period) != unit) {
                        allMatch = false
                        break
                    }
                }
                if (allMatch) return unit
            }
            return null
        }

        /** 各条提醒开头说卡在哪的那句(prompts/feedback/tools.md)。 */
        val LEADS = mapOf(
            "repeating_action" to "guard.repeating_action", "no_progress" to "guard.no_progress", "same_state" to "guard.same_state",
            "state_cycle" to "guard.state_cycle", "no_new_state" to "guard.no_new_state", "read_loop" to "guard.read_loop",
            "too_many_failures" to "guard.too_many_failures",
        )
    }
}

/**
 * 一段计数:最近的动作签名(重复操作),读到过的结果指纹、又读了一遍没变的页面的次数。
 * [canStop] 为 false 时最多提醒,不叫停任务。读的计数在画面变了([OperationGuards.pageChanged])时作废重计。
 */
class GuardScope(val canStop: Boolean) {
    internal val loop = ArrayDeque<String>()
    internal var loopWarnedAt = 0
    internal val seen = HashSet<Long>()
    internal var rereads = 0
    internal var warnedAt = 0
    private var epoch = 0

    internal fun sync(now: Int) {
        if (now == epoch) return
        epoch = now
        seen.clear(); rereads = 0; warnedAt = 0
    }
}
