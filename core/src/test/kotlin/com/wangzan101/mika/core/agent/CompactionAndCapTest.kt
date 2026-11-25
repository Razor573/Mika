package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.StreamingChatModel
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.testing.TOOLS
import com.wangzan101.mika.core.testing.answerReply
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiElement
import com.wangzan101.mika.core.screen.uistate.UiTreeNode
import com.wangzan101.mika.core.screen.uistate.UiWindowTree
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactionAndCapTest {
    private var seq = 0
    private val task = Message(Role.User, listOf(Part.Text("Inspect settings and report")), Author.User)

    private fun obsRound(step: Int, treePad: String = "item-$step"): List<Message> {
        val id = "o${++seq}"
        val tree = """[{"id":1,"type":"application","layer":1,"nodes":[{"role":"FrameLayout","bounds":[0,0,1080,2400],"children":[{"role":"TextView","text":"$treePad","bounds":[24,100,900,180]}]}]}]"""
        val json = """{"ok":true,"currentApp":"com.example","snapshotId":"s$step","elements":[{"ref":"@e$step","role":"Button","name":"$treePad"}],"tree":$tree}"""
        return listOf(
            Message(Role.Assistant, listOf(Part.ToolCall(id, "phone_observe", "{}")), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult(id, json, false)), Author.Mika),
        )
    }

    private fun tapRound(step: Int): List<Message> {
        val id = "t${++seq}"
        return listOf(
            Message(Role.Assistant, listOf(Part.ToolCall(id, "phone_tap", """{"target":"btn-$step"}""")), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult(id, """{"ok":true,"changed":true}""", false)), Author.Mika),
        )
    }

    @Test fun compactionTriggersEvery15StepsSummarisesCompletedStepsAndKeepsLast3Observations() {
        val compressor = ContextCompressor(TOOLS)
        val allObs = (1..30).map { obsRound(it, treePad = "screen-tree-$it") }
        fun historyAt(steps: Int) = listOf(task) + allObs.take(steps).flatten()

        for (step in 1..14) {
            val out = compressor.compress(historyAt(step), contextTokens = 1_000_000)
            assertFalse("step $step should not compact", out.rewritten)
            assertEquals(0, out.foldedSteps)
        }

        val at15 = compressor.compress(historyAt(15), contextTokens = 1_000_000)
        assertTrue(at15.rewritten)
        assertEquals(12, at15.foldedSteps)
        val summary15 = at15.messages.firstNotNullOf { m -> m.plainText().takeIf { it.startsWith(ContextCompressor.HEADER) } }
        assertTrue(summary15, "Step 1 phone_observe" in summary15 && "Step 12 phone_observe" in summary15 && "Step 13" !in summary15)
        val trees15 = at15.messages.flatMap { it.parts }.filterIsInstance<Part.ToolResult>().map { it.content }
        assertEquals(3, trees15.size)
        assertTrue((13..15).all { n -> trees15.any { "\"screen-tree-$n\"" in it } })
        assertTrue((1..12).all { n -> trees15.none { "\"screen-tree-$n\"" in it } })

        for (step in 16..29) {
            val out = compressor.compress(historyAt(step), contextTokens = 1_000_000)
            assertFalse("step $step should stay append-only", out.rewritten)
        }

        val at30 = compressor.compress(historyAt(30), contextTokens = 1_000_000)
        assertTrue(at30.rewritten)
        assertEquals(27, at30.foldedSteps)
        val trees30 = at30.messages.flatMap { it.parts }.filterIsInstance<Part.ToolResult>().map { it.content }
        assertEquals(3, trees30.size)
        assertTrue((28..30).all { n -> trees30.any { "\"screen-tree-$n\"" in it } })
        assertTrue((1..27).all { n -> trees30.none { "\"screen-tree-$n\"" in it } })
    }

    @Test fun agentLoopPeriodicCompactionKeepsLast3ObservationsAndScreenshots() = runTest {
        var reads = 0
        var ver = 0
        val screen = object : ScreenPort by FakeScreen() {
            private fun snap(id: Int): ScreenSnapshot {
                val node = UiTreeNode("TextView", listOf(0, 0, 100, 50), text = "full-node-$id-$ver")
                val win = UiWindowTree(1, "application", 1, "App", "com.x", true, true, listOf(node))
                val el = UiElement("@e1", "button", "btn-$id-$ver", 50, 25, listOf(0, 0, 100, 50), null, true, false, false)
                return ScreenSnapshot("snap-$id-$ver", "com.x", listOf(el), 1080, 2400, windows = listOf(win))
            }
            override suspend fun snapshot(): ScreenSnapshot {
                reads++
                return snap(reads)
            }
            override suspend fun peek(): ScreenSnapshot = snap(reads)
            override suspend fun screenshotPng() = byteArrayOf(1, 2, 3)
            override suspend fun tapXY(x: Int, y: Int): com.wangzan101.mika.core.screen.uistate.ActionResult {
                ver++
                return com.wangzan101.mika.core.screen.uistate.ActionResult.Ok(changed = true)
            }
        }
        var callSeq = 0
        val replies = (1..30).map { step ->
            val name = when {
                step >= 28 -> "phone_observe"
                step % 3 == 0 -> "phone_tap"
                step % 2 == 1 -> "phone_observe"
                else -> "phone_list_elements"
            }
            val args = if (name == "phone_tap") """{"x":100,"y":100}""" else "{}"
            ModelReply(listOf(Part.ToolCall("c${++callSeq}", name, args)), StopReason.ToolUse, null)
        } + answerReply("finished")
        val model = FakeModel(*replies.toTypedArray(), contextTokens = 1_000_000)
        val end = AgentLoop(model, toolDispatcher(screen, visionEnabled = true), "BASE").run("check 30 screens")
        assertTrue(end is TaskState.Succeeded)

        val finalReq = model.requests.last()
        assertTrue(finalReq.any { it.plainText().startsWith(ContextCompressor.HEADER) })
        val fullNodesKept = finalReq.flatMap { it.parts }.filterIsInstance<Part.ToolResult>().count { "btn-" in it.content }
        assertEquals(3, fullNodesKept)
        val imagesKept = finalReq.flatMap { it.parts }.filterIsInstance<Part.Image>()
        assertEquals(3, imagesKept.size)
        assertTrue(TokenEstimate.of(finalReq) <= ContextLimits.MAX_INPUT_TOKENS)
    }

    @Test fun inputTokenCap100kEnforcedInLimitsCompressorAndModelCaller() = runTest {
        assertEquals(100_000, ContextLimits.MAX_INPUT_TOKENS)
        val limits128 = ContextLimits.of(128_000, maxInputTokens = ContextLimits.MAX_INPUT_TOKENS)
        val limits1m = ContextLimits.of(1_000_000, maxInputTokens = ContextLimits.MAX_INPUT_TOKENS)
        for (l in listOf(limits128, limits1m)) {
            assertEquals(100_000, l.usable)
            assertEquals(87_000, l.trigger)
            assertEquals(50_000, l.target)
            assertEquals(50_000, l.minTrigger)
        }
        assertEquals(100_000, ContextLimits.of(1_000_000, triggerOverride = 600_000, maxInputTokens = ContextLimits.MAX_INPUT_TOKENS).trigger)

        val compressor = ContextCompressor(TOOLS, maxInputTokens = ContextLimits.MAX_INPUT_TOKENS)
        val bigTree = "node-text-".repeat(12_000)
        val over100kBefore15 = listOf(task) + (1..6).flatMap { obsRound(it, treePad = bigTree) }
        assertTrue(TokenEstimate.of(over100kBefore15) > 100_000)
        val compressed = compressor.compress(over100kBefore15, contextTokens = 1_000_000, fixedOverhead = 5_000)
        assertTrue(compressed.rewritten)
        assertTrue(TokenEstimate.of(compressed.messages) + 5_000 <= ContextLimits.MAX_INPUT_TOKENS)
        assertTrue(UsageMeter.estimate(TokenEstimate.of(compressed.messages) + 5_000, compressor.scale) <= ContextLimits.MAX_INPUT_TOKENS)

        val fake = FakeModel(answerReply("ok"), contextTokens = 1_000_000)
        val caller = ModelCaller(fake, toolDispatcher(FakeScreen()), null, SpeedFlags.OFF, RetryPolicy.NONE, {}, { 0 })
        caller.reset("SYSTEM")

        val hugePayload180k = listOf(
            task,
            Message(Role.Assistant, listOf(Part.ToolCall("big1", "run_command", "{}")), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("big1", """{"ok":false,"error":"too_big","message":"${"a".repeat(720_000)}"}""", true)), Author.Mika),
        )
        assertTrue(TokenEstimate.of(hugePayload180k) > 175_000)
        caller.ask(hugePayload180k, emptyList(), step = 1)
        val sentToolCap = fake.requests.single()
        assertTrue("${TokenEstimate.of(sentToolCap)}", TokenEstimate.of(sentToolCap) <= ContextLimits.MAX_INPUT_TOKENS)
        assertTrue(UsageMeter.estimate(TokenEstimate.of(sentToolCap) + TokenEstimate.of("SYSTEM"), ContextCompressor.UNCALIBRATED_SCALE) <= ContextLimits.MAX_INPUT_TOKENS)

        val streamedRequests = mutableListOf<List<Message>>()
        val streaming = object : StreamingChatModel {
            override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 1_000_000, reasoning = false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply = error("unused")
            override suspend fun completeStreaming(system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit): ModelReply {
                streamedRequests += messages
                return answerReply("ok")
            }
        }
        val streamCaller = ModelCaller(streaming, toolDispatcher(FakeScreen()), null, SpeedFlags.OFF, RetryPolicy.NONE, {}, { 0 })
        streamCaller.reset("SYSTEM")
        val hugeUser180k = listOf(Message(Role.User, listOf(Part.Text("u".repeat(720_000))), Author.User))
        assertTrue(TokenEstimate.of(hugeUser180k) > 175_000)
        streamCaller.ask(hugeUser180k, emptyList(), step = 1) {}
        val sentStreamCap = streamedRequests.single()
        assertTrue("${TokenEstimate.of(sentStreamCap)}", TokenEstimate.of(sentStreamCap) <= ContextLimits.MAX_INPUT_TOKENS)
        assertTrue(UsageMeter.estimate(TokenEstimate.of(sentStreamCap) + TokenEstimate.of("SYSTEM"), ContextCompressor.UNCALIBRATED_SCALE) <= ContextLimits.MAX_INPUT_TOKENS)
    }

    @Test fun periodicCompactionWithMixedTapsAndObservationsPreservesLast3Obs() {
        val compressor = ContextCompressor(TOOLS)
        val rounds = (1..15).flatMap { step ->
            if (step in listOf(1, 4, 7, 10, 12, 14, 15)) obsRound(step, treePad = "mixed-tree-$step")
            else tapRound(step)
        }
        val h = listOf(task) + rounds
        val c = compressor.compress(h, contextTokens = 1_000_000)
        assertTrue(c.rewritten)
        assertEquals(11, c.foldedSteps)
        val keptObs = c.messages.flatMap { it.parts }.filterIsInstance<Part.ToolResult>().map { it.content }.filter { "mixed-tree-" in it }
        assertEquals(3, keptObs.size)
        assertTrue(listOf(12, 14, 15).all { n -> keptObs.any { "mixed-tree-$n" in it } })
    }

    @Test fun periodicCompactionWorksIndependentlyWhenInputCapIsOff() = runTest {
        // disableInputTokenCap = true turns the 100k cap off while 15-step compaction still runs on its own.
        val fake = FakeModel(answerReply("ok"), contextTokens = 1_000_000)
        val caller = ModelCaller(fake, toolDispatcher(FakeScreen()), null, SpeedFlags(disableInputTokenCap = true), RetryPolicy.NONE, {}, { 0 })
        caller.reset("SYSTEM")
        val bigUser120k = listOf(Message(Role.User, listOf(Part.Text("u".repeat(480_000))), Author.User))
        assertTrue(TokenEstimate.of(bigUser120k) > ContextLimits.MAX_INPUT_TOKENS)
        caller.ask(bigUser120k, emptyList(), step = 1)
        assertTrue(
            "when inputTokenCap is off, >100k request should not be clipped on a 1M window",
            TokenEstimate.of(fake.requests.single()) > ContextLimits.MAX_INPUT_TOKENS,
        )
    }

    @Test fun dropOldestRoundsPreservesPinnedCallAfterTaskProgress() {
        val progress = Part.ToolCall("p1", "task_progress", """{"criteria":["step 1"]}""")
        val handoff = Part.ToolCall("h1", "hand_to_user", """{"reason":"enter password"}""")
        val pinnedRound = listOf(
            Message(Role.Assistant, listOf(progress, handoff), Author.Mika),
            Message(
                Role.Tool,
                listOf(
                    Part.ToolResult("p1", """{"ok":true}""", false),
                    Part.ToolResult("h1", """{"ok":true,"done":true}""", false),
                ),
                Author.Mika,
            ),
        )
        val unpinnedRound = listOf(
            Message(Role.Assistant, listOf(Part.ToolCall("t1", "phone_tap", """{"target":"x"}""")), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("t1", "x".repeat(4_000), false)), Author.Mika),
        )
        val latestRound = obsRound(99, treePad = "latest")
        val history = listOf(task) + pinnedRound + unpinnedRound + latestRound
        // Budget tight enough to require dropping an older round, but large enough to keep task + pinnedRound + latestRound.
        val budget = TokenEstimate.of(listOf(task) + pinnedRound + latestRound) + 50
        val capped = HistoryPruner.enforceCap(history, TOOLS) { TokenEstimate.of(it) <= budget }
        val keptCallNames = capped.flatMap { it.parts }.filterIsInstance<Part.ToolCall>().map { it.name }
        assertTrue("hand_to_user after task_progress must not be dropped: $keptCallNames", "hand_to_user" in keptCallNames)
        assertFalse("unpinned tap round should be dropped instead", "phone_tap" in keptCallNames)
    }

    @Test fun inputCapCountsTransformedSystemPrompt() = runTest {
        val fake = FakeModel(answerReply("ok"), contextTokens = 1_000_000)
        // Original prompt is tiny, but promptTransform expands it to ~80k tokens.
        val expanded = "p".repeat(320_000)
        val caller = ModelCaller(
            fake, toolDispatcher(FakeScreen()), null, SpeedFlags.OFF, RetryPolicy.NONE, {}, { 0 },
        )
        caller.promptTransform = { expanded }
        caller.reset("TINY")
        val user50k = listOf(Message(Role.User, listOf(Part.Text("u".repeat(200_000))), Author.User))
        caller.ask(user50k, emptyList(), step = 1)
        val sent = fake.requests.single()
        val totalWithTransformedPrompt = TokenEstimate.of(sent) + TokenEstimate.of(expanded)
        assertTrue(
            "sent ($totalWithTransformedPrompt) must stay within 100k cap including transformed prompt",
            totalWithTransformedPrompt <= ContextLimits.MAX_INPUT_TOKENS,
        )
    }

    @Test fun inputCapFailsClearlyWhenUserImagesCannotFit() = runTest {
        val fake = FakeModel(answerReply("should-not-be-called"), contextTokens = 1_000_000)
        val caller = ModelCaller(fake, toolDispatcher(FakeScreen()), null, SpeedFlags.OFF, RetryPolicy.NONE, {}, { 0 })
        caller.reset("SYSTEM")
        // 80 user images (~1500 tokens each -> ~120k tokens) cannot fit under 100k even after pruning.
        val manyUserImages = listOf(
            Message(
                Role.User,
                listOf(Part.Text("compare these")) + List(150) { Part.Image("image/png", "AQID") },
                Author.User,
            ),
        )
        var failed: Throwable? = null
        try {
            caller.ask(manyUserImages, emptyList(), step = 1)
        } catch (e: Throwable) {
            failed = e
        }
        assertTrue("expected ContextTooLong, got $failed", failed is com.wangzan101.mika.core.model.ModelError.ContextTooLong)
        assertTrue("over-cap request must not be dispatched to model", fake.requests.isEmpty())
    }

    @Test fun doesNotFirePrematureSizeTriggerBelow100kWhenPeriodic15IsActive() {
        val compressor = ContextCompressor(TOOLS, maxInputTokens = ContextLimits.MAX_INPUT_TOKENS)
        // Build 10 steps whose calibrated total sits in the old size_trigger zone (87k..100k):
        // old code fired size_trigger prematurely every few steps; now it must NOT compact before step 15!
        val mediumPad = "word ".repeat(7_500)
        val obs = obsRound(1, treePad = mediumPad)
        val smallRounds = (2..15).map { idx -> obsRound(idx, treePad = "item-$idx") }
        val at10 = listOf(task) + obs + smallRounds.take(9).flatten()
        val est10 = TokenEstimate.of(at10) + 75_000
        assertTrue("fixture should sit in 87k..100k zone (was $est10)", est10 in 88_000..99_500)
        val out10 = compressor.compress(at10, contextTokens = 1_000_000, currentTokens = est10, fixedOverhead = 5_000)
        assertFalse("must not fire size_trigger below 100k cap (trigger=${out10.trigger})", out10.rewritten)

        // At step 15, periodic_15 fires cleanly with explicit trigger & reason.
        val at15 = listOf(task) + obs + smallRounds.take(14).flatten()
        val out15 = compressor.compress(at15, contextTokens = 1_000_000, currentTokens = 92_000, fixedOverhead = 5_000)
        assertTrue(out15.rewritten)
        assertEquals("periodic_15", out15.trigger)
        assertTrue(out15.reason?.contains("steps_since_compact=15") == true)
    }
}
