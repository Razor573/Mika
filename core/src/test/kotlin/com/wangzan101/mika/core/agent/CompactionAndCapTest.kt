package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.learn.memory.TaskMemory
import com.wangzan101.mika.core.learn.memory.TaskMemoryPersistence
import com.wangzan101.mika.core.learn.memory.TaskMemoryStep
import com.wangzan101.mika.core.learn.memory.TaskMemoryStore
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.GeminiCodec
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.StreamingChatModel
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.model.usage.UsagePurpose
import com.wangzan101.mika.core.model.usage.UsageSink
import com.wangzan101.mika.core.model.usage.UsageSource
import com.wangzan101.mika.core.model.usage.metered
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunSink
import com.wangzan101.mika.core.testing.TOOLS
import com.wangzan101.mika.core.testing.answerReply
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiElement
import com.wangzan101.mika.core.screen.uistate.UiTreeNode
import com.wangzan101.mika.core.screen.uistate.UiWindowTree
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

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

    @Test fun prefixStaysAppendOnlyUntilTriggerIsCrossed() {
        val compressor = ContextCompressor(TOOLS)
        val allObs = (1..30).map { obsRound(it, treePad = "screen-tree-$it") }
        fun historyAt(steps: Int) = listOf(task) + allObs.take(steps).flatten()

        for (step in 1..30) {
            val out = compressor.compress(historyAt(step), contextTokens = 1_000_000)
            assertFalse("step $step should stay append-only (trigger=${out.trigger})", out.rewritten)
            assertEquals(0, out.foldedSteps)
            assertNull(out.trigger)
        }
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
        val caller = ModelCaller(fake, toolDispatcher(FakeScreen()), null, SpeedFlags(inputTokenCap = true), RetryPolicy.NONE, {}, { 0 })
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
        val streamCaller = ModelCaller(streaming, toolDispatcher(FakeScreen()), null, SpeedFlags(inputTokenCap = true), RetryPolicy.NONE, {}, { 0 })
        streamCaller.reset("SYSTEM")
        streamCaller.ask(hugePayload180k, emptyList(), step = 1) {}
        val sentStreamCap = streamedRequests.single()
        assertTrue("${TokenEstimate.of(sentStreamCap)}", TokenEstimate.of(sentStreamCap) <= ContextLimits.MAX_INPUT_TOKENS)
        assertTrue(UsageMeter.estimate(TokenEstimate.of(sentStreamCap) + TokenEstimate.of("SYSTEM"), ContextCompressor.UNCALIBRATED_SCALE) <= ContextLimits.MAX_INPUT_TOKENS)
    }

    @Test fun authorUserTextIsNeverClippedAndOversizedUserMessageFailsWithContextTooLong() = runTest {
        val hugeUserText = "u".repeat(720_000)
        val hugeUserMsg = Message(Role.User, listOf(Part.Text(hugeUserText)), Author.User)
        val prunerResult = HistoryPruner.enforceCap(listOf(hugeUserMsg), TOOLS) { TokenEstimate.of(it) <= 10_000 }
        assertEquals(hugeUserText, prunerResult.single().plainText())

        val fake = FakeModel(answerReply("should-not-be-called"), contextTokens = 1_000_000)
        val caller = ModelCaller(fake, toolDispatcher(FakeScreen()), null, SpeedFlags(inputTokenCap = true), RetryPolicy.NONE, {}, { 0 })
        caller.reset("SYSTEM")
        var thrown: Throwable? = null
        try {
            caller.ask(listOf(hugeUserMsg), emptyList(), step = 1)
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue("Expected ContextTooLong, got $thrown", thrown is ModelError.ContextTooLong)
        assertTrue("Oversized Author.User message must not be sent or silently clipped", fake.requests.isEmpty())
    }

    @Test fun inputTokenCapIsOffByDefaultAndOnlyEnforcedWhenEnabled() = runTest {
        assertFalse(SpeedFlags.OFF.inputTokenCap)
        val fake = FakeModel(answerReply("ok"), contextTokens = 1_000_000)
        val caller = ModelCaller(fake, toolDispatcher(FakeScreen()), null, SpeedFlags.OFF, RetryPolicy.NONE, {}, { 0 })
        caller.reset("SYSTEM")
        val bigUser120k = listOf(Message(Role.User, listOf(Part.Text("u".repeat(480_000))), Author.User))
        assertTrue(TokenEstimate.of(bigUser120k) > ContextLimits.MAX_INPUT_TOKENS)
        caller.ask(bigUser120k, emptyList(), step = 1)
        assertTrue(
            "when inputTokenCap is off (default), >100k request should not be clipped or rejected on a 1M window",
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
            fake, toolDispatcher(FakeScreen()), null, SpeedFlags(inputTokenCap = true), RetryPolicy.NONE, {}, { 0 },
        )
        caller.promptTransform = { expanded }
        caller.reset("TINY")
        val mika50k = listOf(task, Message(Role.User, listOf(Part.Text("u".repeat(200_000))), Author.Mika))
        caller.ask(mika50k, emptyList(), step = 1)
        val sent = fake.requests.single()
        val totalWithTransformedPrompt = TokenEstimate.of(sent) + TokenEstimate.of(expanded)
        assertTrue(
            "sent ($totalWithTransformedPrompt) must stay within 100k cap including transformed prompt",
            totalWithTransformedPrompt <= ContextLimits.MAX_INPUT_TOKENS,
        )
    }

    @Test fun inputCapFailsClearlyWhenUserImagesCannotFit() = runTest {
        val fake = FakeModel(answerReply("should-not-be-called"), contextTokens = 1_000_000)
        val caller = ModelCaller(fake, toolDispatcher(FakeScreen()), null, SpeedFlags(inputTokenCap = true), RetryPolicy.NONE, {}, { 0 })
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

    private fun geminiWireTokenCount(system: String, messages: List<Message>, tools: List<ToolSpec>, vision: Boolean = true): Int {
        val toolTokens = tools.sumOf { spec ->
            val el = Json.parseToJsonElement(spec.parametersJsonSchema)
            val clean = GeminiCodec.sanitize(el, stripArrayBounds = false)
            val hasProps = (clean as? JsonObject)?.get("properties")?.let { it is JsonObject && it.isNotEmpty() } == true
            val params = if (hasProps) clean.toString() else ""
            TokenEstimate.of(spec.name + spec.description + params)
        }
        val msgTokens = messages.sumOf { m ->
            m.parts.sumOf { p ->
                when (p) {
                    is Part.Image -> if (vision) 258 else 14
                    is Part.Text -> TokenEstimate.of(p.text)
                    is Part.Thinking -> 0
                    is Part.ToolCall -> TokenEstimate.of(p.name) + TokenEstimate.of(p.argsJson)
                    is Part.ToolResult -> TokenEstimate.of(p.content)
                }
            }
        }
        return TokenEstimate.of(system) + toolTokens + msgTokens
    }

    @Test fun geminiEstimatorTracksActualTokensWithScreenshotsThinkingSchemasAndMemoryWithoutEveryStepCompaction() = runTest {
        var reads = 0
        val largeTreeChildren = (1..30).map { i ->
            UiTreeNode("TextView", listOf(0, i * 60, 1000, i * 60 + 50), text = "Product row $i specification details and pricing \$${500 + i * 25}")
        }
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot(): ScreenSnapshot {
                reads++
                val win = UiWindowTree(1, "application", 1, "Shop-$reads", "com.shop.app", true, true, largeTreeChildren)
                val els = (1..8).map { i ->
                    UiElement("@e$i", "button", "Item-$reads-$i", 100, i * 60, listOf(0, i * 60, 500, i * 60 + 50), null, true, false, false)
                }
                return ScreenSnapshot("snap-$reads", "com.shop.app", els, 1080, 2400, windows = listOf(win))
            }
            override suspend fun screenshotPng() = byteArrayOf(1, 2, 3, 4)
            override suspend fun tapRef(ref: String, snapshotId: String) = ActionResult.Ok(changed = true)
        }
        val memStore = TaskMemoryStore(
            object : TaskMemoryPersistence {
                override fun load() = listOf(
                    TaskMemory(
                        id = "mem-1",
                        goal = "Compare phones",
                        conclusion = "Checked prices",
                        apps = listOf("com.shop.app"),
                        sites = emptyList(),
                        createdAt = 1L,
                        steps = listOf(
                            TaskMemoryStep(10L, "open app", "opened"),
                            TaskMemoryStep(20L, "search phone", "detail ".repeat(400)),
                        ),
                    ),
                )
                override fun save(items: List<TaskMemory>) {}
            },
            clock = { 100L },
            ids = { "mem-1" },
        )

        var callNum = 0
        val rawMain = object : ChatModel {
            override val name = "gemini-4-hydrogen"
            override val provider = "vertex"
            override val capabilities = Capabilities(vision = true, contextTokens = 1_000_000, toolCalls = true, reasoning = true)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                val cur = ++callNum
                val actualIn = geminiWireTokenCount(system, messages, tools, vision = true)
                val usage = Usage(inputTokens = actualIn, outputTokens = 120, cachedInputTokens = (actualIn * 0.7).toInt(), reasoningTokens = 90)
                if (cur > 32) {
                    return ModelReply(listOf(Part.Thinking("t".repeat(1_200)), Part.Text("Done 32 steps")), StopReason.EndTurn, usage)
                }
                val toolCall = when (cur % 4) {
                    1 -> Part.ToolCall("g$cur", "phone_observe", "{}")
                    2 -> Part.ToolCall("g$cur", "task_memory_search", """{"query":"phones"}""")
                    3 -> Part.ToolCall("g$cur", "task_memory_step", """{"id":"mem-1","step":2}""")
                    else -> Part.ToolCall("g$cur", "phone_tap", """{"ref":"@e1","snapshotId":"snap-$reads"}""")
                }
                return ModelReply(
                    listOf(Part.Thinking("reasoning ".repeat(180)), toolCall),
                    StopReason.ToolUse,
                    usage,
                )
            }
        }
        // Wrap with metered() using a user-friendly provider name ("Vertex AI") to verify wireProvider unwrapping works in prod!
        val meteredMain = rawMain.metered(UsageSource("v1", "Vertex AI", rawMain.name, UsagePurpose.Main), UsageSink { _, _ -> })

        val events = mutableListOf<RunEvent>()
        val sink = object : RunSink {
            override fun event(e: RunEvent) { events += e }
        }
        val end = AgentLoop(
            model = meteredMain,
            tools = toolDispatcher(screen, visionEnabled = true, taskMemory = memStore),
            basePrompt = SystemPrompt.identity(),
            maxSteps = 40,
            sink = sink,
        ).run("Browse 32 steps with screenshots and memory")

        assertTrue("Expected Succeeded, got $end", end is TaskState.Succeeded)
        val replies = events.filterIsInstance<RunEvent.ModelReply>()
        assertEquals(33, replies.size)

        // Estimator must closely track actual inputTokens on all steps after step 1 (no 1.2x-3x blowup).
        for (r in replies.drop(1)) {
            val est = r.estimatedInputTokens ?: 0
            val actual = r.inputTokens ?: 0
            assertTrue("Step ${r.step}: actual=$actual must be <= 100k", actual <= ContextLimits.MAX_INPUT_TOKENS)
            val diffRatio = abs(est - actual).toDouble() / actual.coerceAtLeast(1)
            assertTrue("Step ${r.step}: est=$est diverged from actual=$actual (ratio=$diffRatio)", diffRatio <= 0.15)
        }

        // Trigger-based compaction does NOT fire on step count alone when 32 steps fit well under the 1M window.
        val compactedSteps = replies.filter { it.compactionTrigger != null }.map { it.step to it.compactionTrigger }
        assertEquals(emptyList<Pair<Int, String?>>(), compactedSteps)
        val compressedEvents = events.filterIsInstance<RunEvent.Compressed>()
        assertEquals(0, compressedEvents.size)
    }

    @Test fun cap100kCompactsThenDropsOlderObservationsAndMemoryReadsAndNeverSendsOver100k() = runTest {
        var reads = 0
        val hugeTreeChildren = (1..120).map { i ->
            UiTreeNode("TextView", listOf(0, i * 30, 1000, i * 30 + 25), text = "Entry-$i " + "payload-".repeat(150))
        }
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot(): ScreenSnapshot {
                reads++
                val win = UiWindowTree(1, "application", 1, "Big-$reads", "com.big.app", true, true, hugeTreeChildren)
                val els = listOf(UiElement("@e1", "button", "Next-$reads", 100, 100, listOf(0, 100, 300, 180), null, true, false, false))
                return ScreenSnapshot("big-$reads", "com.big.app", els, 1080, 2400, windows = listOf(win))
            }
            override suspend fun screenshotPng() = byteArrayOf(1, 2, 3, 4)
            override suspend fun tapRef(ref: String, snapshotId: String) = ActionResult.Ok(changed = true)
        }
        val observedActualInputs = mutableListOf<Int>()
        var stepNum = 0
        val model = object : ChatModel {
            override val name = "gemini-4-hydrogen"
            override val provider = "vertex"
            override val capabilities = Capabilities(vision = true, contextTokens = 1_000_000, toolCalls = true, reasoning = false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                val cur = ++stepNum
                val actualIn = geminiWireTokenCount(system, messages, tools, vision = true)
                observedActualInputs += actualIn
                val usage = Usage(actualIn, 40, (actualIn * 0.5).toInt())
                return when {
                    cur <= 12 && cur % 2 == 1 -> ModelReply(listOf(Part.ToolCall("cap$cur", "phone_observe", "{}")), StopReason.ToolUse, usage)
                    cur <= 12 -> ModelReply(listOf(Part.ToolCall("cap$cur", "phone_tap", """{"ref":"@e1","snapshotId":"big-$reads"}""")), StopReason.ToolUse, usage)
                    else -> ModelReply(listOf(Part.Text("Completed under 100k")), StopReason.EndTurn, usage)
                }
            }
        }
        val events = mutableListOf<RunEvent>()
        val end = AgentLoop(
            model = model,
            tools = toolDispatcher(screen, visionEnabled = true),
            basePrompt = SystemPrompt.identity(),
            flags = SpeedFlags(inputTokenCap = true),
            maxSteps = 20,
            sink = object : RunSink {
                override fun event(e: RunEvent) { events += e }
            },
        ).run("Observe large trees without exceeding 100k")

        assertTrue("Expected Succeeded, got $end", end is TaskState.Succeeded)
        assertTrue("Every request sent must be <= 100,000 tokens (max=${observedActualInputs.maxOrNull()})", observedActualInputs.all { it <= ContextLimits.MAX_INPUT_TOKENS })
        val replies = events.filterIsInstance<RunEvent.ModelReply>()
        val capReplies = replies.filter { it.compactionTrigger == "cap_100k" }
        assertTrue("Expected at least one cap_100k compaction before step 15", capReplies.isNotEmpty())
        for (r in capReplies) {
            val reason = r.compactionReason
            assertNotNull(reason)
            assertFalse("cap_100k must never log overflow reason: $reason", reason!!.contains("provider reported context too long"))
            assertTrue("cap_100k reason must log pre-cap estimate > 100k: $reason", reason.endsWith("exceeded cap=100000"))
            val loggedEst = reason.substringAfter("estimated=").substringBefore(" ").toInt()
            assertTrue("pre-cap estimate in reason ($loggedEst) must be > 100000", loggedEst > ContextLimits.MAX_INPUT_TOKENS)
        }
        // Non-compaction final step must not leak cap_100k trigger/reason
        val lastReply = replies.last()
        if (lastReply.compactionTrigger == null) {
            assertNull(lastReply.compactionReason)
        }
    }

    @Test fun anchoredCurrentTokensPreventsFalseCapAndEnforceCapBlocksUnshrinkableOver100k() = runTest {
        // 1) Anchored currentTokens=45k must NOT fire cap_100k even if unanchored TokenEstimate > 100k.
        val compressor = ContextCompressor(TOOLS, maxInputTokens = ContextLimits.MAX_INPUT_TOKENS)
        val bigThinking = Message(Role.Assistant, listOf(Part.Thinking("z".repeat(420_000)), Part.ToolCall("t1", "phone_tap", "{}")), Author.Mika)
        val toolRes = Message(Role.Tool, listOf(Part.ToolResult("t1", """{"ok":true}""", false)), Author.Mika)
        val h = listOf(task, bigThinking, toolRes)
        assertTrue("unanchored TokenEstimate exceeds 100k", TokenEstimate.of(h) > ContextLimits.MAX_INPUT_TOKENS)
        val res = compressor.compress(h, contextTokens = 1_000_000, currentTokens = 45_000, fixedOverhead = 4_000)
        assertFalse("anchored currentTokens=45k must not falsely trigger cap_100k", res.rewritten)
        assertNull(res.trigger)

        // 2) Unshrinkable >100k request fails with ModelError.ContextTooLong without sending over the wire.
        val fake = FakeModel(answerReply("never-sent"), contextTokens = 1_000_000)
        val caller = ModelCaller(fake, toolDispatcher(FakeScreen()), null, SpeedFlags(inputTokenCap = true), RetryPolicy.NONE, {}, { 0 })
        caller.reset("SYSTEM")
        val hugeUnshrinkableImages = listOf(
            Message(Role.User, listOf(Part.Text("user images")) + List(120) { Part.Image("image/png", "AQID") }, Author.User),
        )
        var thrown: Throwable? = null
        try {
            caller.ask(hugeUnshrinkableImages, emptyList(), step = 1)
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue("Expected ContextTooLong, got $thrown", thrown is ModelError.ContextTooLong)
        assertTrue("Over-100k request must never be sent over wire", fake.requests.isEmpty())
        assertEquals("too_long", caller.lastCallDiagnostics?.capCheckResult)
        assertEquals("cap_100k", caller.lastCallDiagnostics?.compactionTrigger)
        assertTrue(caller.lastCallDiagnostics?.compactionReason?.contains("exceeded cap=100000") == true)
    }

    @Test fun multiRoleLightAndMainAnchorsAreIsolatedWhenLightCompacts() = runTest {
        val main = FakeModel(
            ModelReply(listOf(Part.ToolCall("m1", "phone_observe", "{}")), StopReason.ToolUse, Usage(20_000, 50, 0)),
            ModelReply(listOf(Part.Text("main done")), StopReason.EndTurn, Usage(21_000, 30, 15_000)),
            contextTokens = 1_000_000,
        )
        // Light has a tiny 4k context window so step 2 forces Light compaction (and clearAnchorsFor(ROLE_LIGHT)).
        val light = FakeModel(
            ModelReply(listOf(Part.ToolCall("l1", "phone_tap", """{"target":"a"}""")), StopReason.ToolUse, Usage(2_000, 20, 0)),
            ModelReply(listOf(Part.ToolCall("l2", "phone_tap", """{"target":"b"}""")), StopReason.ToolUse, Usage(2_200, 20, 0)),
            contextTokens = 4_000,
        )
        val router = ModelRouter(main, light)
        val caller = ModelCaller(main, toolDispatcher(FakeScreen()), router, SpeedFlags(lightSteps = true), RetryPolicy.NONE, {}, { 0 })
        caller.reset("SYSTEM")

        val h = mutableListOf<Message>(
            Message(Role.User, listOf(Part.Text("task")), Author.User),
        )
        // Step 1 on Main -> anchor Main at 20,000 + 50 resent.
        val (r1, u1) = caller.askRouted(h, emptyList(), step = 1, using = main)
        val m1 = Message(Role.Assistant, r1.parts, Author.Mika)
        caller.noteReply(m1, u1, r1.usage)
        h += m1
        h += Message(Role.Tool, listOf(Part.ToolResult("m1", """{"ok":true}""", false)), Author.Mika)

        // Add pad in Light's size_trigger zone (2,250..3,000 for 4k window), well under Main's 1M.
        val bigPad = "x".repeat(9_600)
        val lightMsg = Message(Role.User, listOf(Part.Text(bigPad)), Author.Mika)
        h += lightMsg
        val (lr1, lu1) = caller.askRouted(h, emptyList(), step = 2, using = light)
        assertEquals(light, lu1)
        assertEquals("size_trigger", caller.lastCallDiagnostics?.compactionTrigger)

        // Next call on Main MUST still trust Main's anchor (20,000 + 50 + delta ~ 25k), NOT fall back to unanchored estimate!
        // Note: unanchored h only has 4k tokens of text, whereas Main's anchor says 20,000 + 50 + 4,000 * 1.2 = ~24,850!
        caller.askRouted(h, emptyList(), step = 3, using = main)
        val estMainStep3 = caller.lastCallDiagnostics?.estimatedInputTokens ?: 0
        assertTrue(
            "Main anchor must survive Light compaction (expected >= 24_000 anchored, got $estMainStep3)",
            estMainStep3 >= 24_000,
        )
    }
}
