package com.wangzan101.mika.core.safety.guard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull

import org.junit.Assert.assertTrue
import org.junit.Test

class OperationGuardsTest {
    private fun obj(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test fun coordinateBucketing() {
        // 坐标按 50 取整、忽略 reason 和键顺序、滑动只记方向。
        assertEquals(
            actionSignature("phone_tap", obj("""{"x":101,"y":149,"reason":"one"}""")),
            actionSignature("phone_tap", obj("""{"reason":"two","y":145,"x":105}""")),
        )
        assertEquals(
            actionSignature("phone_swipe", obj("""{"startX":10,"startY":90,"endX":15,"endY":10}""")),
            actionSignature("phone_swipe", obj("""{"startX":30,"startY":70,"endX":31,"endY":20}""")),
        )
        assertNotEquals(
            actionSignature("phone_tap", obj("""{"x":149,"y":100}""")),
            actionSignature("phone_tap", obj("""{"x":150,"y":100}""")),
        )
        assertNotEquals(
            actionSignature("phone_swipe", obj("""{"startX":10,"startY":90,"endX":15,"endY":10}""")),
            actionSignature("phone_swipe", obj("""{"startX":10,"startY":10,"endX":15,"endY":90}""")),
        )
    }

    @Test fun lookingWithoutActingIsNotNoProgress() {
        val g = OperationGuards()
        g.frame("initial", 0L)
        repeat(20) { g.frame("look-$it", 0L) }
        assertEquals(0, g.unchangedFrames)
        g.noteAction()
        g.frame("after-action", 0L)
        assertEquals(1, g.unchangedFrames)
    }

    @Test fun unchangedScreenNudgesAt3_6_9StopsAt12() {
        val g = OperationGuards()
        g.frame("f0", 42L)
        val perAction = (1..12).map { g.noteAction(); g.frame("f$it", 42L) }
        assertEquals(listOf(3, 6, 9, 12), perAction.withIndex().filter { it.value.any { v -> v.code == "no_progress" } }.map { it.index + 1 })
        val v = perAction.flatten().filter { it.code == "no_progress" }
        assertEquals(listOf(Severity.Warn, Severity.Warn, Severity.Warn, Severity.Stop), v.map { it.severity })
        assertEquals(12, v.last().evidence["unchanged"]!!.jsonPrimitive.int)
    }

    @Test fun reportedNoEffectCountsOnceNotAgainForTheFrame() {
        val g = OperationGuards()
        g.frame("f0", 42L)
        val perAction = (1..3).map { g.noteAction(); g.effect("no_effect") + g.frame("f$it", 42L) }
        assertEquals(3, g.unchangedFrames)
        assertEquals("no_progress", perAction.last().single().code)
        // 小的局部变化也是进展，应清零
        g.noteAction(); g.effect("small_change"); assertEquals(0, g.unchangedFrames)
        g.noteAction(); g.effect("done"); assertEquals(0, g.unchangedFrames)
    }

    @Test fun treeBlindNoVisibleEffectWarnsAtTwoAndStopsAtThree() {
        val g = OperationGuards()
        assertTrue(g.effect("no_effect", treeBlind = true).isEmpty())
        val warned = g.effect("no_effect", treeBlind = true).single()
        assertEquals(Severity.Warn, warned.severity)
        assertEquals("no_progress", warned.code)
        val stopped = g.effect("no_effect", treeBlind = true).single()
        assertEquals(Severity.Stop, stopped.severity)
        assertEquals(3, stopped.evidence["unchanged"]!!.jsonPrimitive.int)
        assertEquals("true", stopped.evidence["treeBlind"]!!.jsonPrimitive.content)
        assertEquals("no_progress", g.stop?.code)

        val recovered = OperationGuards()
        repeat(2) { recovered.effect("no_effect", treeBlind = true) }
        assertTrue(recovered.effect("done", treeBlind = true).isEmpty())
        assertTrue(recovered.effect("no_effect", treeBlind = true).isEmpty())
        assertNull(recovered.stop)
    }

    @Test fun treeBlindStopNeedsItsOwnEvidenceWithinTheNoEffectRun() {
        val unrelated = OperationGuards()
        repeat(2) { assertTrue(unrelated.effect("no_effect").isEmpty()) }
        val ordinary = unrelated.effect("no_effect", treeBlind = true).single()
        assertEquals(Severity.Warn, ordinary.severity)
        assertNull(ordinary.evidence["treeBlind"])
        assertNull(unrelated.stop)

        // 同一轮无效果里已有两次空树点击,紧接的滚动也没效果时可以在第三次停下;
        // 单独滚动到底仍只走普通阶梯,不会被当成注入不响应。
        val mixed = OperationGuards()
        assertTrue(mixed.effect("no_effect", treeBlind = true).isEmpty())
        assertEquals(Severity.Warn, mixed.effect("no_effect", treeBlind = true).single().severity)
        val stopped = mixed.effect("no_effect").single()
        assertEquals(Severity.Stop, stopped.severity)
        assertEquals(2, stopped.evidence["treeBlindActions"]!!.jsonPrimitive.int)
    }

    @Test fun backAndForthBetweenTwoStatesDoesNotStopProgress() {
        val g = OperationGuards()
        g.frame("s0", 1L)
        val v = listOf(2L, 1L, 2L).mapIndexed { i, h -> g.noteAction(); g.frame("s${i + 1}", h) }.flatten()
        assertTrue(v.isEmpty())
    }

    @Test fun threeStateLoopIsCaught() {
        val g = OperationGuards()
        g.frame("s0", 1L)
        // 1→2→3 转满三圈(trail 攒到 9)时提醒兜圈;周期循环全程没有 same_state(由 cycle 通道单独说)
        val seq = listOf(2L, 3L, 1L, 2L, 3L, 1L, 2L, 3L)
        val verdicts = seq.mapIndexed { i, h -> g.noteAction(); g.frame("s${i + 1}", h) }
        assertTrue(verdicts.dropLast(1).flatten().none { it.code == "state_cycle" || it.code == "same_state" })
        assertEquals(listOf("state_cycle"), verdicts.last().map { it.code })
        assertEquals(Severity.Warn, verdicts.last().single().severity)
    }

    @Test fun revisitingKnownScreensWithoutNewContentPromptsReassessment() {
        val g = OperationGuards()
        g.frame("initial", 1L)
        g.noteAction(); g.effect("done"); g.frame("new", 2L)
        val warnings = (1..10).flatMap { i ->
            g.noteAction()
            g.effect("done") + g.frame("repeat-$i", if (i % 2 == 0) 2L else 1L)
        }
        assertTrue(warnings.toString(), warnings.any { it.code == "no_new_state" && it.severity == Severity.Warn })
        assertNull(g.stop)
    }

    @Test fun rereadingTheSameContentAfterNavigationDoesNotResetTaskProgress() {
        val g = OperationGuards()
        g.frame("initial", 1L)
        g.read("phone_observe", g.firstSeen(100L), fingerprint = 100L)
        g.noteAction(); g.effect("done"); g.frame("other", 2L)
        val warnings = (1..10).flatMap { i ->
            g.noteAction(); g.effect("done")
            val frame = g.frame("back-$i", if (i % 2 == 0) 2L else 1L)
            val fp = if (i % 2 == 0) 200L else 100L
            frame + g.read("phone_observe", g.firstSeen(fp), fingerprint = fp)
        }
        assertTrue(warnings.toString(), warnings.any { it.code == "no_new_state" })
    }

    @Test fun freshContentDuringLongScrollKeepsResettingTaskProgress() {
        val g = OperationGuards()
        g.frame("initial", 1L)
        val warnings = (1..40).flatMap { i ->
            g.noteAction(); g.effect("done")
            val frame = g.frame("scroll-$i", 1L)
            frame + g.read("phone_observe", g.firstSeen(1000L + i), fingerprint = 1000L + i)
        }
        assertTrue(warnings.toString(), warnings.none { it.code == "no_new_state" })
        assertNull(g.stop)
    }

    @Test fun cycleStopFollowsTheStepBudget() {
        val g = OperationGuards(stepBudget = 30)
        g.frame("s0", 1L)
        // budget 30 → 梯 [3, 4, max(6, 30/5)=6]:第 5 帧(rounds 3)、第 7 帧(rounds 4)提醒,第 11 帧(rounds 6)叫停
        var stopAt = 0
        val warnAt = mutableListOf<Int>()
        for (i in 1..11) {
            g.noteAction()
            val v = g.frame("s$i", if (i % 2 == 1) 2L else 1L)
            if (v.any { it.code == "state_cycle" && it.severity == Severity.Warn }) warnAt += i
            if (g.stop != null) { stopAt = i; break }
        }
        assertEquals(listOf(5, 7), warnAt)
        assertEquals(11, stopAt)
        assertEquals("state_cycle", g.stop?.code)
    }

    @Test fun returningToTheSameScreenWithoutAnythingNewNudgesAtThree() {
        val g = OperationGuards()
        g.frame("s0", 1L)
        // 每次经不同的新页面回到同一屏(不规则回访,不构成周期):第二次到 1 不提醒,第三次提醒
        val out1 = listOf(2L, 3L, 4L, 1L).mapIndexed { i, h -> g.noteAction(); g.frame("a$i", h) }.flatten()
        assertTrue(out1.toString(), out1.none { it.code == "same_state" || it.code == "state_cycle" })
        val out2 = listOf(5L, 6L, 7L).mapIndexed { i, h -> g.noteAction(); g.frame("b$i", h) }.flatten()
        assertTrue(out2.toString(), out2.isEmpty())
        g.noteAction()
        val last = g.frame("b3", 1L)
        assertEquals(listOf("same_state"), last.map { it.code })
        assertEquals(Severity.Warn, last.single().severity)
        assertTrue(last.single().nudge!!.contains("do_each"))
    }

    @Test fun manyActionsWithoutANewScreenNudgeThenStopLate() {
        val g = OperationGuards()
        g.frame("s0", 1L)
        g.noteAction(); g.frame("s1", 2L)
        val v = (1..30).map { i -> g.noteAction(); g.effect("small_change") + g.frame("x$i", 2L) }
            .withIndex().filter { (_, l) -> l.any { it.code == "no_new_state" } }.map { it.index + 1 }
        assertEquals(listOf(8, 16, 30), v)
        assertNull(g.stop)
        // 步数上限高的任务,安全网跟着往后
        val big = OperationGuards(stepBudget = 100)
        big.frame("s0", 1L); big.noteAction(); big.frame("s1", 2L)
        repeat(49) { i -> big.noteAction(); big.effect("small_change"); big.frame("y$i", 2L) }
        assertNull(big.stop)
        big.noteAction(); big.frame("y-last", 2L)
        assertNull(big.stop)
    }

    @Test fun changedScreenResetsNoProgress() {
        val g = OperationGuards()
        g.frame("f0", 1L)
        repeat(2) { g.noteAction(); g.frame("a$it", 1L) }
        g.noteAction(); assertTrue(g.frame("b", 2L).isEmpty())
        assertEquals(0, g.unchangedFrames)
        // 同一 id 重复上报不计。
        g.noteAction(); g.frame("b", 2L)
        assertEquals(0, g.unchangedFrames)
    }

    @Test fun freshContentKeepsLongScrollAliveEvenWhenActionReportsNoEffect() {
        val g = OperationGuards()
        g.frame("initial", 1L)
        repeat(80) { i ->
            assertTrue(g.beforeAction("web_scroll", obj("""{"direction":"down"}""")).isEmpty())
            g.noteAction()
            g.effect("no_effect")
            assertTrue(g.read("web_read_page", g.firstSeen(1000L + i)).isEmpty())
        }
        assertNull(g.stop)
        assertEquals(0, g.unchangedFrames)
    }

    @Test fun changedFrameCancelsProvisionalNoProgressStop() {
        val g = OperationGuards()
        g.frame("initial", 1L)
        repeat(11) { i -> g.noteAction(); g.effect("no_effect"); g.frame("same-$i", 1L) }
        g.noteAction()
        assertEquals(Severity.Stop, g.effect("no_effect").single().severity)
        g.frame("changed", 2L)
        assertNull(g.stop)
        assertEquals(0, g.unchangedFrames)
    }

    @Test fun failureCircuit() {
        val fail = obj("""{"ok":false,"error":"action_failed","recoverable":true}""")
        val g = OperationGuards()
        assertTrue(g.result("phone_tap", fail).isEmpty())
        val counts = (2..8).map { g.result("phone_tap", fail) }
        assertEquals(List(6) { Severity.Warn } + Severity.Stop, counts.map { it.single().severity })
        assertEquals("too_many_failures", counts.last().single().code)
        assertEquals(8, counts.last().single().evidence["consecutive"]!!.jsonPrimitive.int)
        // 第 4 次起提醒里附出路
        assertFalse(counts[0].single().nudge!!.contains("ask the user"))
        assertTrue(counts[2].single().nudge!!.contains("ask the user"))

        // 成功或 counts_as_failure=false 清零。
        val g2 = OperationGuards()
        g2.result("phone_tap", fail)
        g2.result("phone_tap", obj("""{"ok":true}"""))
        assertTrue(g2.result("phone_tap", fail).isEmpty())
        g2.result("end_not_done", obj("""{"ok":false,"counts_as_failure":false}"""))
        assertTrue(g2.result("phone_tap", fail).isEmpty())
    }
    @Test fun unrecoverableFailureWarnsOnceStopsAtThree() {
        val denied = obj("""{"ok":false,"error":"denied","recoverable":false}""")
        val g = OperationGuards()
        val v = (1..3).map { g.result("phone_tap", denied).single() }
        assertEquals(listOf(Severity.Warn, Severity.Warn, Severity.Stop), v.map { it.severity })
        assertEquals(3, v.last().evidence["unrecoverable"]!!.jsonPrimitive.int)
        assertEquals("denied", (v.last().evidence["recent"] as kotlinx.serialization.json.JsonArray).last().jsonObject["error"]!!.jsonPrimitive.content)
    }

    @Test fun verdictJsonForModel() {
        val v = GuardVerdict("repeating_action", Severity.Warn, JsonObject(emptyMap()))
        assertEquals("""{"code":"repeating_action","severity":"warn","evidence":{}}""", v.toJson().toString())
    }

    // 页面没变、换着工具和参数反复读:读不出新东西,5、8 次提醒,12 次停;读到新内容、画面变了都从头算
    @Test fun rereadingAnUnchangedPageWarnsThenStops() {
        val g = OperationGuards()
        assertTrue(g.firstSeen(1L))
        assertTrue(g.read("phone_observe", fresh = true).isEmpty())
        val verdicts = (1..12).map { i -> g.read(if (i % 2 == 0) "web_evaluate_js" else "web_read_page", g.firstSeen(1L)) }
        assertEquals(listOf(5, 8, 12), verdicts.withIndex().filter { it.value.isNotEmpty() }.map { it.index + 1 })
        assertEquals(listOf(Severity.Warn, Severity.Warn, Severity.Stop), verdicts.flatten().map { it.severity })
        assertTrue(verdicts.flatten().all { it.code == "read_loop" })
        assertEquals("read_loop", g.stop?.code)
    }

    @Test fun newContentOrChangedPageResetsRereads() {
        val g = OperationGuards()
        g.firstSeen(1L)
        repeat(4) { assertTrue(g.read("phone_observe", g.firstSeen(1L)).isEmpty()) }
        // 读整页换了起点:新内容,不算重读
        repeat(10) { assertTrue(g.read("web_read_page", g.firstSeen(100L + it)).isEmpty()) }
        // 画面变了:之前读到的不再作数
        g.frame("f0", 5L); g.noteAction(); g.frame("f1", 6L)
        assertTrue(g.firstSeen(1L))
        repeat(4) { assertTrue(g.read("phone_observe", g.firstSeen(1L)).isEmpty()) }
        g.pageChanged()
        repeat(4) { assertTrue(g.read("phone_observe", g.firstSeen(1L)).isEmpty()) }
    }

    @Test fun toolSequenceBreakerCatchesChangingHashScrollsAndOffScreenLoopsAndResetsOnProgress() {
        val g = OperationGuards()
        // 3 phone_scroll calls with CHANGING screenHash (no tap/extract progress)
        repeat(3) { i ->
            val outcome = g.recordStepOutcome("phone_scroll", screenHash = "h$i", extractedHash = null, itemHashes = emptyList(), writing = true, ok = true)
            assertEquals(true, outcome.screenChanged)
        }
        val firstBlock = g.checkBeforeTool("phone_scroll")
        assertNotNull(firstBlock)
        assertEquals("tool_sequence_loop_blocked", firstBlock!!.code)
        assertEquals(Severity.Warn, firstBlock.severity)

        val secondBlock = g.checkBeforeTool("phone_scroll")
        assertNotNull(secondBlock)
        assertEquals("tool_sequence_loop", secondBlock!!.code)
        assertEquals(Severity.Stop, secondBlock.severity)

        // Multi-step sequence [phone_scroll, phone_observe] x 3, and offScreen tool x 3, plus reset on tap progress
        val g2 = OperationGuards()
        repeat(3) { i ->
            g2.recordStepOutcome("phone_scroll", "s${i * 2}", null, emptyList(), writing = true, ok = true)
            g2.recordStepOutcome("phone_observe", "s${i * 2 + 1}", null, emptyList(), writing = false, ok = true)
        }
        assertEquals("tool_sequence_loop_blocked", g2.checkBeforeTool("phone_scroll")?.code)
        // Progress resets stalled sequence
        g2.recordStepOutcome("phone_tap", "s_tapped", null, emptyList(), writing = true, ok = true)
        assertNull(g2.checkBeforeTool("phone_scroll"))

        val off = OperationGuards()
        repeat(3) {
            off.recordStepOutcome("phone_system_command", screenHash = null, extractedHash = null, itemHashes = emptyList(), writing = false, ok = true)
        }
        assertEquals("tool_sequence_loop_blocked", off.checkBeforeTool("phone_system_command")?.code)
        assertEquals("tool_sequence_loop", off.checkBeforeTool("phone_system_command")?.code)
    }

    @Test fun allToolsTrackedInSequenceBreakerIncludingMemoryViewPastScreensHistoryAndObserve() {
        for (tool in listOf(
            "task_memory_search",
            "task_memory_timeline",
            "task_memory_step",
            "view_past_screens",
            "phone_observe",
            "phone_list_elements",
            "phone_collect",
        )) {
            val g = OperationGuards()
            // Changing hashes keep stuck_screen inactive so we isolate tool_sequence_loop.
            repeat(3) { i ->
                g.recordStepOutcome(tool, screenHash = "h_${tool}_$i", extractedHash = null, itemHashes = emptyList(), writing = false, ok = true)
            }
            val warn = g.checkBeforeTool(tool)
            assertNotNull("Expected sequence warn for $tool", warn)
            assertEquals("tool_sequence_loop_blocked", warn!!.code)
            assertEquals(Severity.Warn, warn.severity)
            assertEquals("tool_sequence_3x:$tool", g.currentBreakerTrigger)

            val stop = g.checkBeforeTool(tool)
            assertNotNull("Expected sequence stop for $tool", stop)
            assertEquals("tool_sequence_loop", stop!!.code)
            assertEquals(Severity.Stop, stop.severity)
            assertEquals("tool_sequence_3x_stop:$tool", g.currentBreakerTrigger)
        }

        // history_read is tracked in stalledToolSeq (caught in [phone_observe, history_read] x 3)
        val histSeq = OperationGuards()
        repeat(3) { i ->
            histSeq.recordStepOutcome("phone_observe", "ho_$i", null, emptyList(), writing = false, ok = true)
            histSeq.recordStepOutcome("history_read", "ho_$i", null, emptyList(), writing = false, ok = true)
        }
        assertEquals("tool_sequence_loop_blocked", histSeq.checkBeforeTool("phone_observe")?.code)
        assertEquals("tool_sequence_loop", histSeq.checkBeforeTool("phone_observe")?.code)

        // Mixed sequence [phone_observe, view_past_screens] x 3 and no-effect phone_tap does NOT clear stalledToolSeq.
        val mixed = OperationGuards()
        repeat(3) { i ->
            mixed.recordStepOutcome("phone_observe", "m${i * 2}", null, emptyList(), writing = false, ok = true)
            mixed.recordStepOutcome("view_past_screens", "m${i * 2 + 1}", null, emptyList(), writing = false, ok = true)
        }
        assertEquals("tool_sequence_loop_blocked", mixed.checkBeforeTool("phone_observe")?.code)

        val noEffectTap = OperationGuards()
        noEffectTap.recordStepOutcome("phone_observe", "s0", null, emptyList(), writing = false, ok = true)
        noEffectTap.recordStepOutcome("view_past_screens", "s0", null, emptyList(), writing = false, ok = true)
        noEffectTap.recordStepOutcome("phone_tap", "s0", null, emptyList(), writing = true, ok = true, changed = false)
        assertEquals(listOf("phone_observe", "view_past_screens", "phone_tap"), noEffectTap.currentToolSequence)

        // Effective phone_tap (screenChanged == true, changed == true) clears stalledToolSeq.
        noEffectTap.recordStepOutcome("phone_tap", "s1", null, emptyList(), writing = true, ok = true, changed = true)
        assertTrue(noEffectTap.currentToolSequence.isEmpty())
    }

    @Test fun stuckScreenRuleBlocksReadsAndGiveUpUntilTapOrSwipeTried() {
        val g = OperationGuards()
        // Baseline read sets screenHash = "h1" (screenChanged = true -> consecutiveUnchangedScreen = 0)
        val first = g.recordStepOutcome("phone_observe", "h1", null, emptyList(), writing = false, ok = true)
        assertEquals(true, first.screenChanged)
        assertEquals(0, g.currentConsecutiveUnchangedScreen)

        // 3 consecutive screenChanged == false steps trigger stuck-screen rule
        val s2 = g.recordStepOutcome("phone_list_elements", "h1", null, emptyList(), writing = false, ok = true)
        val s3 = g.recordStepOutcome("view_past_screens", "h1", null, emptyList(), writing = false, ok = true)
        val s4 = g.recordStepOutcome("task_memory_search", "h1", null, emptyList(), writing = false, ok = true)
        assertEquals(false, s2.screenChanged)
        assertEquals(false, s3.screenChanged)
        assertEquals(false, s4.screenChanged)
        assertEquals(3, g.currentConsecutiveUnchangedScreen)

        // Off-screen call (screenHash = null -> screenChanged = null) persists consecutiveUnchangedScreen
        val off = g.recordStepOutcome("phone_system_command", null, null, emptyList(), writing = false, ok = true)
        assertNull(off.screenChanged)
        assertEquals(3, g.currentConsecutiveUnchangedScreen)

        // All non-action tools and end_not_done are blocked until tap/swipe is tried
        for (blocked in listOf(
            "phone_observe", "phone_list_elements", "phone_collect", "phone_scroll",
            "view_past_screens", "history_read", "task_memory_search", "task_memory_timeline", "task_memory_step",
        )) {
            val freshStuck = OperationGuards()
            freshStuck.recordStepOutcome("phone_observe", "h1", null, emptyList(), writing = false, ok = true)
            repeat(3) {
                freshStuck.recordStepOutcome("phone_list_elements", "h1", null, emptyList(), writing = false, ok = true)
            }
            val v = freshStuck.checkBeforeTool(blocked)
            assertNotNull("Expected stuck block for $blocked", v)
            assertEquals("stuck_screen_blocked", v!!.code)
            assertEquals(Severity.Warn, v.severity)
            assertEquals("stuck_screen_3x", freshStuck.currentBreakerTrigger)
            assertNull(freshStuck.stop)
        }
        val giveUp = g.checkBeforeTool("end_not_done")
        assertNotNull(giveUp)
        assertEquals("stuck_screen_giveup_blocked", giveUp!!.code)
        assertEquals(Severity.Warn, giveUp.severity)
        assertEquals("stuck_screen_giveup_blocked", g.currentBreakerTrigger)
        assertNull(g.stop)

        // Trying phone_tap with no change unlocks end_not_done
        val tapNoEffect = g.recordStepOutcome("phone_tap", "h1", null, emptyList(), writing = true, ok = true, changed = false)
        assertEquals(false, tapNoEffect.screenChanged)
        assertEquals(4, g.currentConsecutiveUnchangedScreen)
        assertNull(g.checkBeforeTool("end_not_done"))

        // If model keeps calling non-action tools after tap failed to change screen, warn then stop
        val warnAfterTap = g.checkBeforeTool("phone_observe")
        assertEquals("stuck_screen_blocked", warnAfterTap?.code)
        assertEquals(Severity.Warn, warnAfterTap?.severity)
        val stopAfterTap = g.checkBeforeTool("phone_observe")
        assertEquals("stuck_screen", stopAfterTap?.code)
        assertEquals(Severity.Stop, stopAfterTap?.severity)
        assertEquals("stuck_screen_3x_stop", g.currentBreakerTrigger)

        // Separate guard: if tap/swipe DOES change screen, all counters reset and observe is allowed
        val gRecover = OperationGuards()
        gRecover.recordStepOutcome("phone_observe", "h1", null, emptyList(), writing = false, ok = true)
        repeat(3) {
            gRecover.recordStepOutcome("phone_list_elements", "h1", null, emptyList(), writing = false, ok = true)
        }
        assertEquals(3, gRecover.currentConsecutiveUnchangedScreen)
        assertEquals("stuck_screen_giveup_blocked", gRecover.checkBeforeTool("end_not_done")?.code)
        gRecover.recordStepOutcome("phone_swipe", "h2", null, emptyList(), writing = true, ok = true, changed = true)
        assertEquals(0, gRecover.currentConsecutiveUnchangedScreen)
        assertNull(gRecover.checkBeforeTool("phone_observe"))
    }

    @Test fun blockedTelemetryAndSnapshotTelemetryPopulateScreenHashAndChanged() {
        val g = OperationGuards()
        g.recordStepOutcome("phone_observe", "abc123", null, emptyList(), writing = false, ok = true)
        repeat(3) {
            g.recordStepOutcome("task_memory_search", "abc123", null, emptyList(), writing = false, ok = true)
        }
        val verdict = g.checkBeforeTool("task_memory_search")
        assertNotNull(verdict)
        val blocked = g.blockedTelemetry("task_memory_search")
        assertEquals("abc123", blocked.screenHash)
        assertEquals(false, blocked.screenChanged)
        assertEquals("stuck_screen_3x", blocked.breakerTrigger)
        assertTrue(blocked.toolSequence?.contains("task_memory_search") == true)

        val snap = g.snapshotTelemetry("ask_user", screenHash = "abc123")
        assertEquals("abc123", snap.screenHash)
        assertEquals(false, snap.screenChanged)
        assertEquals(listOf("phone_observe", "task_memory_search", "task_memory_search", "task_memory_search"), snap.toolSequence)
    }

    @Test fun stuckScreenWarnEscalatesToStopEvenWithoutTapAttempt() {
        fun stuckGuard(): OperationGuards = OperationGuards().apply {
            recordStepOutcome("phone_observe", "h1", null, emptyList(), writing = false, ok = true)
            repeat(3) {
                recordStepOutcome("phone_list_elements", "h1", null, emptyList(), writing = false, ok = true)
            }
        }

        // 1. Repeated end_not_done: 2x Warn, 3rd -> Stop (stuck_screen_giveup_stop)
        val gGiveUp = stuckGuard()
        assertEquals(Severity.Warn, gGiveUp.checkBeforeTool("end_not_done")?.severity)
        assertEquals(Severity.Warn, gGiveUp.checkBeforeTool("end_not_done")?.severity)
        val giveUpStop = gGiveUp.checkBeforeTool("end_not_done")
        assertNotNull(giveUpStop)
        assertEquals(Severity.Stop, giveUpStop!!.severity)
        assertEquals("stuck_screen_giveup_stop", giveUpStop.code)
        assertEquals("stuck_screen_giveup_stop", gGiveUp.currentBreakerTrigger)
        assertEquals(giveUpStop, gGiveUp.stop)

        // 2. Repeated stuck_screen_blocked tool (phone_scroll / task_memory_search): 2x Warn, 3rd -> Stop
        val gScroll = stuckGuard()
        assertEquals(Severity.Warn, gScroll.checkBeforeTool("phone_scroll")?.severity)
        assertEquals(Severity.Warn, gScroll.checkBeforeTool("phone_scroll")?.severity)
        val scrollStop = gScroll.checkBeforeTool("phone_scroll")
        assertNotNull(scrollStop)
        assertEquals(Severity.Stop, scrollStop!!.severity)
        assertEquals("stuck_screen", scrollStop.code)
        assertEquals("stuck_screen_3x_stop", gScroll.currentBreakerTrigger)

        // 3. Repeated no_progress_cycle while stuckActive && !tapAlreadyTried: 2x Warn, 3rd -> Stop
        val gCycle = OperationGuards()
        gCycle.recordStepOutcome("phone_observe", "h1", null, emptyList(), writing = false, ok = true)
        gCycle.recordStepOutcome("phone_extract", "h1", "e1", listOf("i1"), writing = false, ok = true)
        gCycle.recordStepOutcome("phone_scroll", "h1", null, emptyList(), writing = true, ok = true)
        gCycle.recordStepOutcome("phone_observe", "h1", null, emptyList(), writing = false, ok = true)
        gCycle.recordStepOutcome("phone_extract", "h1", "e1", listOf("i1"), writing = false, ok = true)
        assertEquals(Severity.Warn, gCycle.checkBeforeTool("phone_scroll")?.severity)
        assertEquals(Severity.Warn, gCycle.checkBeforeTool("phone_scroll")?.severity)
        val cycleStop = gCycle.checkBeforeTool("phone_scroll")
        assertNotNull(cycleStop)
        assertEquals(Severity.Stop, cycleStop!!.severity)
        assertEquals("no_progress_cycle", cycleStop.code)
        assertEquals("screen_hash_cycle_stop", gCycle.currentBreakerTrigger)

        // 4. Repeated tool_sequence_loop on allowed non-tap tool (phone_back) while stuckActive && !tapAlreadyTried: 2x Warn, 3rd -> Stop
        val gSeq = stuckGuard()
        repeat(3) {
            gSeq.recordStepOutcome("phone_back", "h1", null, emptyList(), writing = true, ok = true, changed = false)
        }
        assertEquals(Severity.Warn, gSeq.checkBeforeTool("phone_back")?.severity)
        assertEquals(Severity.Warn, gSeq.checkBeforeTool("phone_back")?.severity)
        val seqStop = gSeq.checkBeforeTool("phone_back")
        assertNotNull(seqStop)
        assertEquals(Severity.Stop, seqStop!!.severity)
        assertEquals("tool_sequence_loop", seqStop.code)
        assertEquals("tool_sequence_3x_stop:phone_back", gSeq.currentBreakerTrigger)

        // 5. Alternating end_not_done + phone_scroll + phone_observe + end_not_done: 3x Warn, 4th -> Stop
        val gAlt = stuckGuard()
        assertEquals(Severity.Warn, gAlt.checkBeforeTool("end_not_done")?.severity)
        assertEquals(Severity.Warn, gAlt.checkBeforeTool("phone_scroll")?.severity)
        assertEquals(Severity.Warn, gAlt.checkBeforeTool("phone_observe")?.severity)
        val altStop = gAlt.checkBeforeTool("end_not_done")
        assertNotNull(altStop)
        assertEquals(Severity.Stop, altStop!!.severity)
        assertEquals("stuck_screen_giveup_stop", gAlt.currentBreakerTrigger)
    }

    @Test fun failedTapOrSwipeDoesNotUnlockGiveUpOnStuckScreen() {
        // Case A: failed tap with screenChanged == false
        val g = OperationGuards()
        g.recordStepOutcome("phone_observe", "h1", null, emptyList(), writing = false, ok = true)
        repeat(3) {
            g.recordStepOutcome("phone_list_elements", "h1", null, emptyList(), writing = false, ok = true)
        }
        g.recordStepOutcome("phone_tap", "h1", null, emptyList(), writing = true, ok = false, changed = false)
        val stillBlocked = g.checkBeforeTool("end_not_done")
        assertNotNull(stillBlocked)
        assertEquals("stuck_screen_giveup_blocked", stillBlocked!!.code)
        assertEquals(Severity.Warn, stillBlocked.severity)

        // Case B: failed swipe with screenChanged == null (stale/no screen payload)
        val gNull = OperationGuards()
        gNull.recordStepOutcome("phone_observe", "h1", null, emptyList(), writing = false, ok = true)
        repeat(3) {
            gNull.recordStepOutcome("phone_list_elements", "h1", null, emptyList(), writing = false, ok = true)
        }
        gNull.recordStepOutcome("phone_swipe", null, null, emptyList(), writing = true, ok = false, changed = false)
        val stillBlockedNull = gNull.checkBeforeTool("end_not_done")
        assertNotNull(stillBlockedNull)
        assertEquals("stuck_screen_giveup_blocked", stillBlockedNull!!.code)

        // Succeeding tap (ok = true, changed = false) DOES unlock end_not_done
        g.recordStepOutcome("phone_tap", "h1", null, emptyList(), writing = true, ok = true, changed = false)
        assertNull(g.checkBeforeTool("end_not_done"))

        // Case C: 3x [phone_back(ok=true), phone_tap(ok=false)] repeating sequence does NOT unlock end_not_done
        val gSeqFail = OperationGuards()
        gSeqFail.recordStepOutcome("phone_observe", "h1", null, emptyList(), writing = false, ok = true)
        repeat(3) {
            gSeqFail.recordStepOutcome("phone_list_elements", "h1", null, emptyList(), writing = false, ok = true)
        }
        repeat(3) {
            gSeqFail.recordStepOutcome("phone_back", "h1", null, emptyList(), writing = true, ok = true, changed = false)
            gSeqFail.recordStepOutcome("phone_tap", "h1", null, emptyList(), writing = true, ok = false, changed = false)
        }
        val seqFailGiveUp = gSeqFail.checkBeforeTool("end_not_done")
        assertNotNull(seqFailGiveUp)
        assertEquals("stuck_screen_giveup_blocked", seqFailGiveUp!!.code)
        assertEquals(Severity.Warn, seqFailGiveUp.severity)
    }

    @Test fun toolSequenceLoopNudgeOnTapOrSwipeIsNotSelfContradictory() {
        val g = OperationGuards()
        g.recordStepOutcome("phone_observe", "h1", null, emptyList(), writing = false, ok = true)
        repeat(3) {
            g.recordStepOutcome("phone_tap", "h1", null, emptyList(), writing = true, ok = true, changed = false)
        }
        val verdict = g.checkBeforeTool("phone_tap")
        assertNotNull(verdict)
        assertEquals("tool_sequence_loop_blocked", verdict!!.code)
        assertEquals(Severity.Warn, verdict.severity)
        val nudge = verdict.nudge.orEmpty()
        assertTrue(nudge, nudge.contains("Do NOT call phone_tap again right now"))
        assertFalse(nudge, nudge.contains("Tap a different unopened item"))
        assertFalse(nudge, nudge.contains("tap the next unopened item"))
        assertTrue(nudge, nudge.contains("phone_swipe"))
        assertTrue(nudge, nudge.contains("end_not_done"))

        // phone_swipe sequence warning must not advise swiping again
        val gSwipe = OperationGuards()
        gSwipe.recordStepOutcome("phone_observe", "h1", null, emptyList(), writing = false, ok = true)
        repeat(3) {
            gSwipe.recordStepOutcome("phone_swipe", "h1", null, emptyList(), writing = true, ok = true, changed = false)
        }
        val swipeVerdict = gSwipe.checkBeforeTool("phone_swipe")
        assertNotNull(swipeVerdict)
        val swipeNudge = swipeVerdict!!.nudge.orEmpty()
        assertTrue(swipeNudge, swipeNudge.contains("Do NOT call phone_swipe again right now"))
        assertFalse(swipeNudge, swipeNudge.contains("swipe to a new area"))
        assertTrue(swipeNudge, swipeNudge.contains("phone_tap"))

        // When stuckActive && !tapAlreadyTried (3x failed phone_swipe), nudge must not advise end_not_done yet
        val gSwipeFail = OperationGuards()
        gSwipeFail.recordStepOutcome("phone_observe", "h1", null, emptyList(), writing = false, ok = true)
        repeat(3) {
            gSwipeFail.recordStepOutcome("phone_swipe", "h1", null, emptyList(), writing = true, ok = false, changed = false)
        }
        val swipeFailNudge = gSwipeFail.checkBeforeTool("phone_swipe")?.nudge.orEmpty()
        assertFalse(swipeFailNudge, swipeFailNudge.contains("end_not_done"))
        assertTrue(swipeFailNudge, swipeFailNudge.contains("before giving up"))
    }

    @Test fun verdictStopPreservesBreakerTriggerAcrossRecordStepOutcomeAndBlockedTelemetry() {
        val g = OperationGuards()
        val fail = obj("""{"ok":false,"error":"denied","recoverable":false}""")
        g.recordStepOutcome("phone_observe", "h1", null, emptyList(), writing = false, ok = true)
        repeat(2) {
            g.result("phone_tap", fail)
            g.recordStepOutcome("phone_tap", "h1", null, emptyList(), writing = true, ok = false, changed = false)
        }
        val stopVerdict = g.result("phone_tap", fail).single()
        assertEquals(Severity.Stop, stopVerdict.severity)
        assertEquals("too_many_failures", stopVerdict.code)
        assertEquals("too_many_failures", g.currentBreakerTrigger)

        val outcome = g.recordStepOutcome("phone_tap", "h1", null, emptyList(), writing = true, ok = false, changed = false)
        assertEquals("too_many_failures", outcome.breakerTrigger)
        assertEquals("too_many_failures", g.blockedTelemetry("do_steps").breakerTrigger)
    }

    @Test fun alternatingFiveDistinctAllowedToolsOnStuckScreenIsBounded() {
        val g = OperationGuards()
        g.recordStepOutcome("phone_observe", "h1", null, emptyList(), writing = false, ok = true)
        repeat(3) {
            g.recordStepOutcome("phone_list_elements", "h1", null, emptyList(), writing = false, ok = true)
        }
        val allowed = listOf("phone_back", "phone_press_key", "phone_set_wheel", "phone_clear_text", "phone_dialog")
        var stopped: GuardVerdict? = null
        var warned = 0
        for (i in 0 until 20) {
            val tool = allowed[i % allowed.size]
            val v = g.checkBeforeTool(tool)
            if (v != null) {
                if (v.severity == Severity.Warn) warned++
                if (v.severity == Severity.Stop) {
                    stopped = v
                    break
                }
            } else {
                g.recordStepOutcome(tool, "h1", null, emptyList(), writing = true, ok = true, changed = false)
            }
        }
        assertNotNull("Expected stuck_screen Stop for 5 alternating allowed tools", stopped)
        assertEquals("stuck_screen", stopped!!.code)
        assertEquals("stuck_screen_3x_stop", g.currentBreakerTrigger)
        assertEquals(1, warned)
    }

    @Test fun crossScreenStalledToolOkSeqDoesNotUnlockGiveUpOnNewStuckScreen() {
        // An ok=true tap on h1 must NOT leak across a screen transition (h1 -> h2) to unlock end_not_done on h2 when all taps on h2 fail.
        val g = OperationGuards()
        g.recordStepOutcome("phone_observe", "h1", null, emptyList(), writing = false, ok = true)
        g.recordStepOutcome("phone_back", "h1", null, emptyList(), writing = true, ok = true, changed = false)
        g.recordStepOutcome("phone_tap", "h1", null, emptyList(), writing = true, ok = true, changed = false)
        // Transition to h2 via phone_back
        val backToH2 = g.recordStepOutcome("phone_back", "h2", null, emptyList(), writing = true, ok = true, changed = true)
        assertEquals(true, backToH2.screenChanged)
        assertEquals(0, g.currentConsecutiveUnchangedScreen)
        // On h2, all taps fail
        g.recordStepOutcome("phone_tap", "h2", null, emptyList(), writing = true, ok = false, changed = false)
        g.recordStepOutcome("phone_back", "h2", null, emptyList(), writing = true, ok = true, changed = false)
        g.recordStepOutcome("phone_tap", "h2", null, emptyList(), writing = true, ok = false, changed = false)
        assertEquals(3, g.currentConsecutiveUnchangedScreen)

        val giveUp = g.checkBeforeTool("end_not_done")
        assertNotNull("Expected end_not_done to stay blocked on h2 when all taps on h2 failed", giveUp)
        assertEquals("stuck_screen_giveup_blocked", giveUp!!.code)
        assertEquals(Severity.Warn, giveUp.severity)

        // A screen-changing phone_swipe (h1 -> h2) must also not count as a tap/swipe on h2 when subsequent swipes on h2 fail
        val gSwipeTransition = OperationGuards()
        gSwipeTransition.recordStepOutcome("phone_observe", "h1", null, emptyList(), writing = false, ok = true)
        val swipeToH2 = gSwipeTransition.recordStepOutcome("phone_swipe", "h2", null, emptyList(), writing = true, ok = true, changed = true)
        assertEquals(true, swipeToH2.screenChanged)
        gSwipeTransition.recordStepOutcome("phone_observe", "h2", null, emptyList(), writing = false, ok = true)
        gSwipeTransition.recordStepOutcome("phone_swipe", "h2", null, emptyList(), writing = true, ok = false, changed = false)
        gSwipeTransition.recordStepOutcome("phone_observe", "h2", null, emptyList(), writing = false, ok = true)
        gSwipeTransition.recordStepOutcome("phone_swipe", "h2", null, emptyList(), writing = true, ok = false, changed = false)
        gSwipeTransition.recordStepOutcome("phone_observe", "h2", null, emptyList(), writing = false, ok = true)
        assertEquals(5, gSwipeTransition.currentConsecutiveUnchangedScreen)
        val swipeGiveUp = gSwipeTransition.checkBeforeTool("end_not_done")
        assertNotNull("Transition swipe from h1->h2 must not unlock end_not_done on h2", swipeGiveUp)
        assertEquals("stuck_screen_giveup_blocked", swipeGiveUp!!.code)

        // Same-screen ok=true tap in a repeating sequence DOES still unlock end_not_done on that same screen
        val gSameScreen = OperationGuards()
        gSameScreen.recordStepOutcome("phone_observe", "h1", null, emptyList(), writing = false, ok = true)
        gSameScreen.recordStepOutcome("phone_back", "h2", null, emptyList(), writing = true, ok = true, changed = true)
        repeat(3) {
            gSameScreen.recordStepOutcome("phone_back", "h2", null, emptyList(), writing = true, ok = true, changed = false)
            gSameScreen.recordStepOutcome("phone_tap", "h2", null, emptyList(), writing = true, ok = true, changed = false)
        }
        assertNull(gSameScreen.checkBeforeTool("end_not_done"))
    }

    @Test fun multiToolElseBranchSequenceNudgeDoesNotRecommendRepeatingToolsOrPrematureGiveUp() {
        // Case 1: [phone_back, phone_tap] x 3 -> checkBeforeTool("phone_back") must NOT advise tapping
        val g = OperationGuards()
        g.recordStepOutcome("phone_observe", "h1", null, emptyList(), writing = false, ok = true)
        repeat(3) {
            g.recordStepOutcome("phone_back", "h1", null, emptyList(), writing = true, ok = true, changed = false)
            g.recordStepOutcome("phone_tap", "h1", null, emptyList(), writing = true, ok = true, changed = false)
        }
        val verdict = g.checkBeforeTool("phone_back")
        assertNotNull(verdict)
        assertEquals("tool_sequence_loop_blocked", verdict!!.code)
        assertEquals(Severity.Warn, verdict.severity)
        val nudge = verdict.nudge.orEmpty()
        val advice = nudge.substringAfter("Force a different action:")
        assertFalse(nudge, nudge.contains("tap the next unopened item"))
        assertFalse(advice, advice.contains("phone_tap"))
        assertFalse(advice, advice.contains("phone_back"))
        assertTrue(advice, advice.contains("phone_swipe"))
        // Following the suggested alternative must NOT hard-stop on the next step
        assertNull(g.checkBeforeTool("phone_swipe"))

        // Case 2: stuckActive && !tapAlreadyTried with [phone_back(ok=true), phone_tap(ok=false)] x 3
        val gStuck = OperationGuards()
        gStuck.recordStepOutcome("phone_observe", "h1", null, emptyList(), writing = false, ok = true)
        repeat(3) {
            gStuck.recordStepOutcome("phone_back", "h1", null, emptyList(), writing = true, ok = true, changed = false)
            gStuck.recordStepOutcome("phone_tap", "h1", null, emptyList(), writing = true, ok = false, changed = false)
        }
        val stuckVerdict = gStuck.checkBeforeTool("phone_back")
        assertNotNull(stuckVerdict)
        assertEquals("tool_sequence_loop_blocked", stuckVerdict!!.code)
        val stuckNudge = stuckVerdict.nudge.orEmpty()
        val stuckAdvice = stuckNudge.substringAfter("Force a different action:")
        assertFalse(stuckNudge, stuckNudge.contains("tap the next unopened item"))
        assertFalse(stuckAdvice, stuckAdvice.contains("phone_tap"))
        assertFalse(stuckAdvice, stuckAdvice.contains("end_not_done"))
        assertTrue(stuckAdvice, stuckAdvice.contains("phone_swipe"))
        assertNull(gStuck.checkBeforeTool("phone_swipe"))

        // Case 3: [phone_back, phone_tap, phone_swipe] x 3 -> excludes tap, swipe, and back
        val gAllThree = OperationGuards()
        gAllThree.recordStepOutcome("phone_observe", "h1", null, emptyList(), writing = false, ok = true)
        repeat(3) {
            gAllThree.recordStepOutcome("phone_back", "h1", null, emptyList(), writing = true, ok = true, changed = false)
            gAllThree.recordStepOutcome("phone_tap", "h1", null, emptyList(), writing = true, ok = true, changed = false)
            gAllThree.recordStepOutcome("phone_swipe", "h1", null, emptyList(), writing = true, ok = true, changed = false)
        }
        val allThreeVerdict = gAllThree.checkBeforeTool("phone_back")
        assertNotNull(allThreeVerdict)
        val allThreeNudge = allThreeVerdict!!.nudge.orEmpty()
        val allThreeAdvice = allThreeNudge.substringAfter("Force a different action:")
        assertFalse(allThreeAdvice, allThreeAdvice.contains("phone_tap"))
        assertFalse(allThreeAdvice, allThreeAdvice.contains("phone_swipe"))
        assertFalse(allThreeAdvice, allThreeAdvice.contains("phone_back"))
        assertTrue(allThreeAdvice, allThreeAdvice.contains("try a completely different tool"))
        assertNull(gAllThree.checkBeforeTool("phone_type_text"))
    }
}
