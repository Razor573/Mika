package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.run.record.RunRecords
import com.wangzan101.mika.core.run.record.RunSink
import com.wangzan101.mika.core.run.record.RunSummary
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiElement
import com.wangzan101.mika.core.screen.uistate.UiTreeNode
import com.wangzan101.mika.core.screen.uistate.UiWindowTree
import com.wangzan101.mika.core.tool.FakeScreen
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneLoopBreakerAndCompactionTest {
    private val seq = AtomicInteger(0)
    private fun call(tool: String, args: String = "{}") = Part.ToolCall("p${seq.incrementAndGet()}", tool, args)

    private class RecordingSink : RunSink {
        val events = mutableListOf<RunEvent>()
        override fun event(e: RunEvent) {
            events += e
        }
    }

    @Test
    fun noProgressLoopLogsHashesAndBlocksThirdRepeatThenStopsIfIgnored() = runTest {
        var snapCount = 0
        val staticScreen = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot(): ScreenSnapshot {
                snapCount++
                val node = UiTreeNode(
                    "RecyclerView",
                    listOf(0, 0, 1080, 2000),
                    scrollable = true,
                    children = listOf(
                        UiTreeNode("TextView", listOf(10, 100, 900, 200), text = "Laptop Alpha \$999"),
                        UiTreeNode("TextView", listOf(10, 220, 900, 320), text = "Laptop Beta \$1199"),
                    ),
                )
                val win = UiWindowTree(1, "application", 1, "Shop", "com.shop.app", true, true, listOf(node))
                val el = UiElement("@e1", "button", "Laptop Alpha", 200, 150, listOf(10, 100, 900, 200), null, true, false, false)
                return ScreenSnapshot("s$snapCount", "com.shop.app", listOf(el), 1080, 2400, windows = listOf(win))
            }
            override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = ActionResult.Ok(changed = false)
        }

        // Cycle 1: observe -> extract
        // Cycle 2: scroll -> observe -> extract (same screenHash & extractedHash -> repeat #2)
        // Cycle 3: tries phone_scroll (blocked #1 -> forced to try tap/swipe first), tries phone_tap (no change), tries phone_observe (blocked #2 -> guard stops loop cleanly!)
        val replies = listOf(
            ModelReply(listOf(call("phone_observe")), StopReason.ToolUse, Usage(500, 20, 0)),
            ModelReply(listOf(call("phone_extract", """{"mode":"list"}""")), StopReason.ToolUse, Usage(700, 20, 400)),
            ModelReply(listOf(call("phone_scroll", """{"direction":"down"}""")), StopReason.ToolUse, Usage(900, 20, 600)),
            ModelReply(listOf(call("phone_observe")), StopReason.ToolUse, Usage(1100, 20, 800)),
            ModelReply(listOf(call("phone_extract", """{"mode":"list"}""")), StopReason.ToolUse, Usage(1300, 20, 1000)),
            ModelReply(listOf(call("phone_scroll", """{"direction":"down"}""")), StopReason.ToolUse, Usage(1500, 20, 1200)),
            ModelReply(listOf(call("phone_tap", """{"ref":"@e1","snapshotId":"s2"}""")), StopReason.ToolUse, Usage(1600, 20, 1300)),
            ModelReply(listOf(call("phone_observe")), StopReason.ToolUse, Usage(1700, 20, 1400)),
            ModelReply(listOf(Part.Text("done")), StopReason.EndTurn, Usage(1900, 20, 1600)),
        )
        val model = FakeModel(*replies.toTypedArray(), contextTokens = 200_000)
        val sink = RecordingSink()
        val end = AgentLoop(
            model = model,
            tools = toolDispatcher(staticScreen, visionEnabled = false),
            basePrompt = "BASE",
            maxSteps = 52,
            sink = sink,
        ).run("Find laptops")

        assertTrue("Expected guard stop instead of 52-step loop, got $end", end is TaskState.Failed)
        val modelReplies = sink.events.filterIsInstance<RunEvent.ModelReply>()
        assertTrue("Must stop well before 52 steps (was ${modelReplies.size})", modelReplies.size <= 8)

        // Screen hash and extracted hash logged on steps
        val extractReplies = modelReplies.filter { it.tool == "phone_extract" }
        assertEquals(2, extractReplies.size)
        assertNotNull(extractReplies[0].screenHash)
        assertNotNull(extractReplies[0].extractedHash)
        assertEquals(extractReplies[0].screenHash, extractReplies[1].screenHash)
        assertEquals(extractReplies[0].extractedHash, extractReplies[1].extractedHash)
        assertEquals(false, extractReplies[1].screenChanged)
        assertEquals(false, extractReplies[1].extractedItemsNew)

        val toolResults = sink.events.filterIsInstance<RunEvent.ToolResult>()
        assertTrue(toolResults.any { it.resultJson.contains("no_progress_cycle_blocked") })
    }

    @Test
    fun historyReadBlockedWhenRepeatedConsecutivelyWithoutScreenChange() = runTest {
        var screenVersion = 1
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot(): ScreenSnapshot {
                val node = UiTreeNode("TextView", listOf(0, 0, 100, 50), text = "Screen-$screenVersion")
                val win = UiWindowTree(1, "application", 1, "App", "com.x", true, true, listOf(node))
                val el = UiElement("@e1", "button", "Item-$screenVersion", 50, 25, listOf(0, 0, 100, 50), null, true, false, false)
                return ScreenSnapshot("snap-$screenVersion", "com.x", listOf(el), 1080, 2400, windows = listOf(win))
            }
            override suspend fun tapRef(ref: String, snapshotId: String): ActionResult {
                screenVersion++
                return ActionResult.Ok(changed = true)
            }
        }
        val fakeRecords = object : RunRecords {
            override suspend fun summary(runId: String) = RunSummary(runId, "prev", startedAtMs = 1000L)
            override suspend fun events(runId: String) = emptyList<RunEvent>()
        }
        val spec = RunSpec(
            id = RunId("20261124-210002-hist01"),
            goal = "Check history then tap",
            threadId = "t1",
            priorTurns = listOf(Turn("prev goal", "prev answer", "succeeded", runId = "20261124-200000-prev01")),
            provenance = RunProvenance(RunOrigin.Ui),
        )

        val model = FakeModel(
            ModelReply(listOf(call("phone_observe")), StopReason.ToolUse, Usage(500, 20, 0)),
            ModelReply(listOf(call("history_read", """{"reason":"check prior laptop prices"}""")), StopReason.ToolUse, Usage(600, 20, 300)),
            ModelReply(listOf(call("history_read", """{"reason":"check again"}""")), StopReason.ToolUse, Usage(700, 20, 400)),
            ModelReply(listOf(call("phone_tap", """{"ref":"@e1","snapshotId":"snap-1"}""")), StopReason.ToolUse, Usage(800, 20, 500)),
            ModelReply(listOf(call("phone_observe")), StopReason.ToolUse, Usage(900, 20, 600)),
            ModelReply(listOf(call("history_read", """{"reason":"check after screen changed"}""")), StopReason.ToolUse, Usage(1000, 20, 700)),
            ModelReply(listOf(Part.Text("done")), StopReason.EndTurn, Usage(1100, 20, 800)),
        )
        val sink = RecordingSink()
        val end = AgentLoop(
            model = model,
            tools = toolDispatcher(screen, runRecords = fakeRecords, runSpec = spec),
            basePrompt = "BASE",
            sink = sink,
        ).run(spec, com.wangzan101.mika.core.run.RunInbox())

        assertTrue("$end", end is TaskState.Succeeded)
        val histResults = sink.events.filterIsInstance<RunEvent.ToolResult>().filter { it.tool == "history_read" }
        assertEquals(3, histResults.size)
        assertFalse(histResults[0].resultJson.contains("history_read_consecutive_limit"))
        assertTrue(histResults[1].resultJson.contains("history_read_consecutive_limit"))
        assertFalse(histResults[2].resultJson.contains("history_read_consecutive_limit"))

        val histReplies = sink.events.filterIsInstance<RunEvent.ModelReply>().filter { it.tool == "history_read" }
        assertEquals("check prior laptop prices", histReplies[0].historyReadReason)
    }

    @Test
    fun fiftyStepPhoneTaskWithLargeTreesAndLightFailuresDoesNotChurnCompactions() = runTest {
        var reads = 0
        val largeTreeChildren = (1..25).map { i ->
            UiTreeNode("TextView", listOf(0, i * 60, 1000, i * 60 + 50), text = "Laptop item $i with specs and price \$${900 + i * 50}")
        }
        val screen = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot(): ScreenSnapshot {
                reads++
                val win = UiWindowTree(1, "application", 1, "Shop-$reads", "com.shop.app", true, true, largeTreeChildren)
                val els = (1..10).map { i ->
                    UiElement("@e$i", "button", "Item-$reads-$i", 100, i * 60, listOf(0, i * 60, 500, i * 60 + 50), null, true, false, false)
                }
                return ScreenSnapshot("snap-$reads", "com.shop.app", els, 1080, 2400, windows = listOf(win))
            }
            override suspend fun screenshotPng() = byteArrayOf(1, 2, 3, 4)
            override suspend fun tapRef(ref: String, snapshotId: String) = ActionResult.Ok(changed = true)
            override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = ActionResult.Ok(changed = true)
        }

        val stepCounter = AtomicInteger(0)
        val main = object : ChatModel {
            override val name = "gemini-4-hydrogen"
            override val provider = "vertex"
            override val capabilities = Capabilities(vision = true, contextTokens = 1_000_000, toolCalls = true, reasoning = false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                val cur = stepCounter.incrementAndGet()
                val est = TokenEstimate.of(messages) + TokenEstimate.of(system) +
                    tools.sumOf { TokenEstimate.of(it.name + it.description + it.parametersJsonSchema) }
                val usage = Usage(est, 40, (est * 0.85).toInt())
                if (cur > 48) return ModelReply(listOf(Part.Text("All 48 phone steps done")), StopReason.EndTurn, usage)
                val toolCall = when (cur % 3) {
                    1 -> call("phone_observe")
                    2 -> call("phone_extract", """{"mode":"list"}""")
                    else -> call("phone_tap", """{"ref":"@e1","snapshotId":"snap-$reads"}""")
                }
                return ModelReply(listOf(toolCall), StopReason.ToolUse, usage)
            }
        }
        val failingLight = object : ChatModel {
            override val name = "gemini-3.8-flash"
            override val provider = "gemini"
            override val capabilities = Capabilities(vision = true, contextTokens = 1_000_000, toolCalls = true, reasoning = false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                throw ModelError.Server(500, "transient").withDiagnostics(500, "INTERNAL", """{"error":"internal"}""")
            }
        }

        val sink = RecordingSink()
        val end = AgentLoop(
            model = main,
            tools = toolDispatcher(screen, visionEnabled = true),
            basePrompt = SystemPrompt.identity(),
            maxSteps = 55,
            flags = SpeedFlags(lightSteps = true),
            router = ModelRouter(main, failingLight),
            sink = sink,
        ).run("Browse 48 phone steps")

        assertTrue("$end", end is TaskState.Succeeded)
        val compressions = sink.events.filterIsInstance<RunEvent.Compressed>()
        assertTrue("Expected <= 5 compactions (not 23), got ${compressions.size}: ${compressions.map { it.foldedSteps }}", compressions.size <= 5)
        val mainFolded = compressions.filter { it.role == "main" || it.role == null }.map { it.foldedSteps }
        assertEquals("Must have zero duplicate foldedSteps: $mainFolded", mainFolded.distinct(), mainFolded)
        val maxIn = sink.events.filterIsInstance<RunEvent.ModelReply>().maxOf { it.inputTokens ?: 0 }
        assertTrue("Max input must stay <= 100k, was $maxIn", maxIn <= ContextLimits.MAX_INPUT_TOKENS)
    }

    @Test
    fun toolSequenceBreakerStopsRepeatingChangingHashScrollsAndOffScreenSystemCommands() = runTest {
        var reads = 0
        val changingScreen = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot(): ScreenSnapshot {
                reads++
                val node = UiTreeNode("TextView", listOf(0, 0, 900, 200), text = "Laptop row $reads")
                val win = UiWindowTree(1, "application", 1, "Shop", "com.shop.app", true, true, listOf(node))
                val el = UiElement("@e1", "button", "Item $reads", 200, 150, listOf(0, 0, 900, 200), null, true, false, false)
                return ScreenSnapshot("s$reads", "com.shop.app", listOf(el), 1080, 2400, windows = listOf(win))
            }
            override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = ActionResult.Ok(changed = true)
        }
        // Even with changing screenHash on every phone_scroll, 3 scrolls without opening an item or extracting new items must block the 4th and stop on 5th.
        val scrollReplies = Array(10) { i ->
            ModelReply(listOf(call("phone_scroll", """{"direction":"down"}""")), StopReason.ToolUse, Usage(500 + i * 50, 20, 400))
        }
        val sinkScroll = RecordingSink()
        val endScroll = AgentLoop(
            model = FakeModel(*scrollReplies, contextTokens = 200_000),
            tools = toolDispatcher(changingScreen, visionEnabled = false),
            basePrompt = "BASE",
            maxSteps = 40,
            sink = sinkScroll,
        ).run("Browse laptops")

        assertTrue("Expected tool-sequence guard stop, got $endScroll", endScroll is TaskState.Failed)
        val scrollModelReplies = sinkScroll.events.filterIsInstance<RunEvent.ModelReply>()
        assertTrue("Must stop early (~5 steps, not 40), got ${scrollModelReplies.size}", scrollModelReplies.size <= 6)
        assertTrue(scrollModelReplies.any { it.breakerTrigger?.startsWith("tool_sequence_3x") == true })
        assertTrue(scrollModelReplies.any { !it.toolSequence.isNullOrEmpty() })

        val formatted = com.wangzan101.mika.core.run.record.UsageLogFormatter.formatEvents(sinkScroll.events, "run-seq")
        assertTrue(formatted.contains("\"toolSequence\""))
        assertTrue(formatted.contains("\"breakerTrigger\""))
    }

    @Test
    fun allToolsLoopBreakerCatchesTaskMemoryAndViewPastScreensLoops() = runTest {
        var reads = 0
        val changingScreen = object : ScreenPort by FakeScreen() {
            override suspend fun snapshot(): ScreenSnapshot {
                reads++
                val node = UiTreeNode("TextView", listOf(0, 0, 900, 200), text = "Row $reads")
                val win = UiWindowTree(1, "application", 1, "Shop", "com.shop.app", true, true, listOf(node))
                val el = UiElement("@e1", "button", "Item $reads", 200, 150, listOf(0, 0, 900, 200), null, true, false, false)
                return ScreenSnapshot("s$reads", "com.shop.app", listOf(el), 1080, 2400, windows = listOf(win))
            }
        }
        val taskMemoryStore = com.wangzan101.mika.core.learn.memory.TaskMemoryStore(
            object : com.wangzan101.mika.core.learn.memory.TaskMemoryPersistence {
                override fun load() = emptyList<com.wangzan101.mika.core.learn.memory.TaskMemory>()
                override fun save(items: List<com.wangzan101.mika.core.learn.memory.TaskMemory>) {}
            },
            clock = { 0L },
            ids = { "x" },
        )
        // Repeating [phone_observe, view_past_screens] x 3 even with changing screen hash must block on 4th repeat
        val replies = listOf(
            ModelReply(listOf(call("phone_observe")), StopReason.ToolUse, Usage(400, 20, 0)),
            ModelReply(listOf(call("view_past_screens", """{"ids":[1]}""")), StopReason.ToolUse, Usage(500, 20, 200)),
            ModelReply(listOf(call("phone_observe")), StopReason.ToolUse, Usage(600, 20, 300)),
            ModelReply(listOf(call("view_past_screens", """{"ids":[1]}""")), StopReason.ToolUse, Usage(700, 20, 400)),
            ModelReply(listOf(call("phone_observe")), StopReason.ToolUse, Usage(800, 20, 500)),
            ModelReply(listOf(call("view_past_screens", """{"ids":[1]}""")), StopReason.ToolUse, Usage(900, 20, 600)),
            ModelReply(listOf(call("phone_observe")), StopReason.ToolUse, Usage(1000, 20, 700)),
            ModelReply(listOf(call("phone_observe")), StopReason.ToolUse, Usage(1100, 20, 800)),
        )
        val sink = RecordingSink()
        val end = AgentLoop(
            model = FakeModel(*replies.toTypedArray(), contextTokens = 200_000),
            tools = toolDispatcher(changingScreen, visionEnabled = false, taskMemory = taskMemoryStore),
            basePrompt = "BASE",
            maxSteps = 20,
            sink = sink,
        ).run("Inspect screen")

        assertTrue("Expected tool_sequence_loop stop, got $end", end is TaskState.Failed && end.kind == FailKind.Guard)
        val toolResults = sink.events.filterIsInstance<RunEvent.ToolResult>()
        assertTrue(toolResults.any { it.resultJson.contains("tool_sequence_loop_blocked") })

        // Repeating task_memory_search x 3 is also caught and logged
        val memReplies = Array(6) { i ->
            ModelReply(listOf(call("task_memory_search", """{"query":"laptop $i"}""")), StopReason.ToolUse, Usage(400 + i * 50, 20, 200))
        }
        val sinkMem = RecordingSink()
        val endMem = AgentLoop(
            model = FakeModel(*memReplies, contextTokens = 200_000),
            tools = toolDispatcher(changingScreen, visionEnabled = false, taskMemory = taskMemoryStore),
            basePrompt = "BASE",
            maxSteps = 20,
            sink = sinkMem,
        ).run("Search memory")
        assertTrue("Expected guard stop for task_memory_search loop, got $endMem", endMem is TaskState.Failed && endMem.kind == FailKind.Guard)
        val memRepliesLogged = sinkMem.events.filterIsInstance<RunEvent.ModelReply>()
        assertTrue(memRepliesLogged.any { it.breakerTrigger?.startsWith("tool_sequence_3x") == true })
    }

    @Test
    fun stuckScreenRuleForcesTapBeforeGiveUpAndAllowsCleanEndingOrContinuesOnChange() = runTest {
        var screenVersion = 1
        var tapShouldChange = false
        val screen = object : ScreenPort by FakeScreen() {
            private fun buildSnap(ver: Int): ScreenSnapshot {
                val node = UiTreeNode("TextView", listOf(0, 0, 900, 200), text = "StaticScreen-$ver")
                val win = UiWindowTree(1, "application", 1, "App", "com.shop.app", true, true, listOf(node))
                val el = UiElement("@e1", "button", "NextItem-$ver", 200, 150, listOf(0, 0, 900, 200), null, true, false, false)
                return ScreenSnapshot("snap-$ver", "com.shop.app", listOf(el), 1080, 2400, windows = listOf(win))
            }
            override suspend fun snapshot() = buildSnap(screenVersion)
            override suspend fun peek() = buildSnap(screenVersion)
            override suspend fun tapRef(ref: String, snapshotId: String): ActionResult {
                if (tapShouldChange) screenVersion++
                return ActionResult.Ok(changed = tapShouldChange)
            }
            override suspend fun tapXY(x: Int, y: Int): ActionResult {
                if (tapShouldChange) screenVersion++
                return ActionResult.Ok(changed = tapShouldChange)
            }
        }
        val taskMemoryStore = com.wangzan101.mika.core.learn.memory.TaskMemoryStore(
            object : com.wangzan101.mika.core.learn.memory.TaskMemoryPersistence {
                override fun load() = emptyList<com.wangzan101.mika.core.learn.memory.TaskMemory>()
                override fun save(items: List<com.wangzan101.mika.core.learn.memory.TaskMemory>) {}
            },
            clock = { 0L },
            ids = { "x" },
        )

        // Case A: 3 consecutive unchanged screens -> end_not_done & phone_observe blocked -> tap tried (no change) -> end_not_done allowed
        val sinkA = RecordingSink()
        val modelA = FakeModel(
            ModelReply(listOf(call("phone_observe")), StopReason.ToolUse, Usage(400, 20, 0)),
            ModelReply(listOf(call("phone_list_elements")), StopReason.ToolUse, Usage(500, 20, 200)),
            ModelReply(listOf(call("view_past_screens", """{"ids":[1]}""")), StopReason.ToolUse, Usage(600, 20, 300)),
            ModelReply(listOf(call("task_memory_search", """{"query":"item"}""")), StopReason.ToolUse, Usage(700, 20, 400)),
            ModelReply(listOf(call("end_not_done", """{"message":"stuck before tap"}""")), StopReason.ToolUse, Usage(800, 20, 500)),
            ModelReply(listOf(call("phone_observe")), StopReason.ToolUse, Usage(900, 20, 600)),
            ModelReply(listOf(call("phone_tap", """{"x":200,"y":150}""")), StopReason.ToolUse, Usage(1000, 20, 700)),
            ModelReply(listOf(call("end_not_done", """{"message":"screen still unchanged after tapping @e1"}""")), StopReason.ToolUse, Usage(1100, 20, 800)),
        )
        val endA = AgentLoop(
            model = modelA,
            tools = toolDispatcher(screen, visionEnabled = false, inlineScreen = true, taskMemory = taskMemoryStore),
            basePrompt = "BASE",
            flags = SpeedFlags(inlineScreen = true),
            maxSteps = 20,
            sink = sinkA,
        ).run("Open item")
        assertTrue("Expected GaveUp after tap attempt, got $endA", endA is TaskState.Failed && endA.kind == FailKind.GaveUp)
        assertEquals("screen still unchanged after tapping @e1", (endA as TaskState.Failed).reason)
        val resultsA = sinkA.events.filterIsInstance<RunEvent.ToolResult>()
        assertTrue(resultsA.any { it.tool == "end_not_done" && it.resultJson.contains("stuck_screen_giveup_blocked") })
        assertTrue(resultsA.any { it.tool == "phone_observe" && it.resultJson.contains("stuck_screen_blocked") })

        // Case B: tap changes the screen -> resets stuck counter and continues to success
        tapShouldChange = true
        val sinkB = RecordingSink()
        val modelB = FakeModel(
            ModelReply(listOf(call("phone_observe")), StopReason.ToolUse, Usage(400, 20, 0)),
            ModelReply(listOf(call("phone_list_elements")), StopReason.ToolUse, Usage(500, 20, 200)),
            ModelReply(listOf(call("view_past_screens", """{"ids":[1]}""")), StopReason.ToolUse, Usage(600, 20, 300)),
            ModelReply(listOf(call("task_memory_search", """{"query":"item"}""")), StopReason.ToolUse, Usage(700, 20, 400)),
            ModelReply(listOf(call("phone_tap", """{"x":200,"y":150}""")), StopReason.ToolUse, Usage(800, 20, 500)),
            ModelReply(listOf(call("phone_observe")), StopReason.ToolUse, Usage(900, 20, 600)),
            ModelReply(listOf(Part.Text("Opened item successfully")), StopReason.EndTurn, Usage(1000, 20, 700)),
        )
        val endB = AgentLoop(
            model = modelB,
            tools = toolDispatcher(screen, visionEnabled = false, inlineScreen = true, taskMemory = taskMemoryStore),
            basePrompt = "BASE",
            flags = SpeedFlags(inlineScreen = true),
            maxSteps = 20,
            sink = sinkB,
        ).run("Open item")
        assertTrue("Expected Succeeded after tap changed screen, got $endB", endB is TaskState.Succeeded)
        assertEquals(2, screenVersion)
        val resultsB = sinkB.events.filterIsInstance<RunEvent.ToolResult>()
        assertTrue(resultsB.none { it.resultJson.contains("stuck_screen_blocked") })
    }

    @Test
    fun stuckScreenStopsIgnoredGiveUpScrollMemoryAndAlternatingLoopsUnderUnlimitedSteps() = runTest {
        val staticScreen = object : ScreenPort by FakeScreen() {
            private val snap = ScreenSnapshot(
                "snap-static",
                "com.shop.app",
                listOf(UiElement("@e1", "button", "Item", 200, 150, listOf(0, 0, 900, 200), null, true, false, false)),
                1080,
                2400,
                windows = listOf(UiWindowTree(1, "application", 1, "App", "com.shop.app", true, true, listOf(UiTreeNode("TextView", listOf(0, 0, 900, 200), text = "Static")))),
            )
            override suspend fun snapshot() = snap
            override suspend fun peek() = snap
            override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = ActionResult.Ok(changed = false)
        }
        val taskMemoryStore = com.wangzan101.mika.core.learn.memory.TaskMemoryStore(
            object : com.wangzan101.mika.core.learn.memory.TaskMemoryPersistence {
                override fun load() = emptyList<com.wangzan101.mika.core.learn.memory.TaskMemory>()
                override fun save(items: List<com.wangzan101.mika.core.learn.memory.TaskMemory>) {}
            },
            clock = { 0L },
            ids = { "x" },
        )

        fun stuckSetup(): List<ModelReply> = listOf(
            ModelReply(listOf(call("phone_observe")), StopReason.ToolUse, Usage(400, 20, 0)),
            ModelReply(listOf(call("phone_list_elements")), StopReason.ToolUse, Usage(500, 20, 200)),
            ModelReply(listOf(call("view_past_screens", """{"ids":[1]}""")), StopReason.ToolUse, Usage(600, 20, 300)),
            ModelReply(listOf(call("task_memory_search", """{"query":"item"}""")), StopReason.ToolUse, Usage(700, 20, 400)),
        )

        // 1. Repeated end_not_done under UNLIMITED_STEPS: warns first (2x), then terminates cleanly via Guard Stop
        val giveUpReplies = stuckSetup() + List(10) { i ->
            ModelReply(listOf(call("end_not_done", """{"message":"give up $i"}""")), StopReason.ToolUse, Usage(800 + i * 50, 20, 500))
        }
        val sinkGiveUp = RecordingSink()
        val endGiveUp = AgentLoop(
            model = FakeModel(*giveUpReplies.toTypedArray(), contextTokens = 200_000),
            tools = toolDispatcher(staticScreen, visionEnabled = false, inlineScreen = true, taskMemory = taskMemoryStore),
            basePrompt = "BASE",
            flags = SpeedFlags(inlineScreen = true),
            maxSteps = AgentLoop.UNLIMITED_STEPS,
            sink = sinkGiveUp,
        ).run("Try item")
        assertTrue("Expected Guard stop for ignored end_not_done, got $endGiveUp", endGiveUp is TaskState.Failed && endGiveUp.kind == FailKind.Guard)
        val giveUpWarnResults = sinkGiveUp.events.filterIsInstance<RunEvent.ToolResult>().filter { it.resultJson.contains("stuck_screen_giveup_blocked") }
        assertEquals(2, giveUpWarnResults.size)
        val giveUpLog = com.wangzan101.mika.core.run.record.UsageLogFormatter.formatEvents(sinkGiveUp.events, "run-giveup-stop")
        assertTrue(giveUpLog.contains("\"screenHash\""))
        assertTrue(giveUpLog.contains("\"screenChanged\""))
        assertTrue(giveUpLog.contains("\"toolSequence\""))
        assertTrue(giveUpLog.contains("\"breakerTrigger\":\"stuck_screen_giveup_stop\""))

        // 2. Repeated phone_scroll / task_memory_search under UNLIMITED_STEPS: warns first (2x), then stops via Guard
        for (toolName in listOf("phone_scroll", "task_memory_search")) {
            val args = if (toolName == "phone_scroll") """{"direction":"down"}""" else """{"query":"item"}"""
            val repeatReplies = stuckSetup() + List(10) { i ->
                ModelReply(listOf(call(toolName, args)), StopReason.ToolUse, Usage(800 + i * 50, 20, 500))
            }
            val sinkRepeat = RecordingSink()
            val endRepeat = AgentLoop(
                model = FakeModel(*repeatReplies.toTypedArray(), contextTokens = 200_000),
                tools = toolDispatcher(staticScreen, visionEnabled = false, inlineScreen = true, taskMemory = taskMemoryStore),
                basePrompt = "BASE",
                flags = SpeedFlags(inlineScreen = true),
                maxSteps = AgentLoop.UNLIMITED_STEPS,
                sink = sinkRepeat,
            ).run("Try item")
            assertTrue("Expected Guard stop for $toolName, got $endRepeat", endRepeat is TaskState.Failed && endRepeat.kind == FailKind.Guard)
            val warnCount = sinkRepeat.events.filterIsInstance<RunEvent.ToolResult>().count { it.resultJson.contains("stuck_screen_blocked") }
            assertEquals("Expected 2 Warn nudges before Stop for $toolName", 2, warnCount)
            val repeatLog = com.wangzan101.mika.core.run.record.UsageLogFormatter.formatEvents(sinkRepeat.events, "run-$toolName-stop")
            assertTrue(repeatLog.contains("\"breakerTrigger\":\"stuck_screen_3x_stop\""))
        }

        // 3. Alternating end_not_done + phone_scroll + phone_observe under UNLIMITED_STEPS: 3x Warn, 4th -> Stop
        val altReplies = stuckSetup() + listOf(
            ModelReply(listOf(call("end_not_done", """{"message":"give up"}""")), StopReason.ToolUse, Usage(800, 20, 500)),
            ModelReply(listOf(call("phone_scroll", """{"direction":"down"}""")), StopReason.ToolUse, Usage(900, 20, 600)),
            ModelReply(listOf(call("phone_observe")), StopReason.ToolUse, Usage(1000, 20, 700)),
            ModelReply(listOf(call("end_not_done", """{"message":"give up again"}""")), StopReason.ToolUse, Usage(1100, 20, 800)),
            ModelReply(listOf(call("phone_observe")), StopReason.ToolUse, Usage(1200, 20, 900)),
        )
        val sinkAlt = RecordingSink()
        val endAlt = AgentLoop(
            model = FakeModel(*altReplies.toTypedArray(), contextTokens = 200_000),
            tools = toolDispatcher(staticScreen, visionEnabled = false, inlineScreen = true, taskMemory = taskMemoryStore),
            basePrompt = "BASE",
            flags = SpeedFlags(inlineScreen = true),
            maxSteps = AgentLoop.UNLIMITED_STEPS,
            sink = sinkAlt,
        ).run("Try item")
        assertTrue("Expected Guard stop for alternating warnings, got $endAlt", endAlt is TaskState.Failed && endAlt.kind == FailKind.Guard)
        val altModelReplies = sinkAlt.events.filterIsInstance<RunEvent.ModelReply>()
        assertEquals(8, altModelReplies.size)
        val altLog = com.wangzan101.mika.core.run.record.UsageLogFormatter.formatEvents(sinkAlt.events, "run-alt-stop")
        assertTrue(altLog.contains("\"screenHash\""))
        assertTrue(altLog.contains("\"screenChanged\""))
        assertTrue(altLog.contains("\"toolSequence\""))
        assertTrue(altLog.contains("\"breakerTrigger\":\"stuck_screen_giveup_stop\""))
    }

    @Test
    fun failedTapDoesNotUnlockGiveUpOnStuckScreenUntilSucceedingTap() = runTest {
        var tapAttempt = 0
        val screen = object : ScreenPort by FakeScreen() {
            private val snap = ScreenSnapshot(
                "snap-fixed",
                "com.shop.app",
                listOf(UiElement("@e1", "button", "Target", 200, 150, listOf(0, 0, 900, 200), null, true, false, false)),
                1080,
                2400,
                windows = listOf(UiWindowTree(1, "application", 1, "App", "com.shop.app", true, true, listOf(UiTreeNode("TextView", listOf(0, 0, 900, 200), text = "Static")))),
            )
            override suspend fun snapshot() = snap
            override suspend fun peek() = snap
            override suspend fun tapXY(x: Int, y: Int): ActionResult {
                tapAttempt++
                return if (tapAttempt == 1) ActionResult.Failed("tap_injection_failed")
                else ActionResult.Ok(changed = false)
            }
        }
        val taskMemoryStore = com.wangzan101.mika.core.learn.memory.TaskMemoryStore(
            object : com.wangzan101.mika.core.learn.memory.TaskMemoryPersistence {
                override fun load() = emptyList<com.wangzan101.mika.core.learn.memory.TaskMemory>()
                override fun save(items: List<com.wangzan101.mika.core.learn.memory.TaskMemory>) {}
            },
            clock = { 0L },
            ids = { "x" },
        )
        val replies = listOf(
            ModelReply(listOf(call("phone_observe")), StopReason.ToolUse, Usage(400, 20, 0)),
            ModelReply(listOf(call("phone_list_elements")), StopReason.ToolUse, Usage(500, 20, 200)),
            ModelReply(listOf(call("view_past_screens", """{"ids":[1]}""")), StopReason.ToolUse, Usage(600, 20, 300)),
            ModelReply(listOf(call("task_memory_search", """{"query":"item"}""")), StopReason.ToolUse, Usage(700, 20, 400)),
            ModelReply(listOf(call("phone_tap", """{"x":200,"y":150}""")), StopReason.ToolUse, Usage(800, 20, 500)),
            ModelReply(listOf(call("end_not_done", """{"message":"give up after failed tap"}""")), StopReason.ToolUse, Usage(900, 20, 600)),
            ModelReply(listOf(call("phone_tap", """{"x":200,"y":150}""")), StopReason.ToolUse, Usage(1000, 20, 700)),
            ModelReply(listOf(call("end_not_done", """{"message":"give up after real tap"}""")), StopReason.ToolUse, Usage(1100, 20, 800)),
        )
        val sink = RecordingSink()
        val end = AgentLoop(
            model = FakeModel(*replies.toTypedArray(), contextTokens = 200_000),
            tools = toolDispatcher(screen, visionEnabled = false, inlineScreen = true, taskMemory = taskMemoryStore),
            basePrompt = "BASE",
            flags = SpeedFlags(inlineScreen = true),
            maxSteps = AgentLoop.UNLIMITED_STEPS,
            sink = sink,
        ).run("Open item")

        assertTrue("Expected GaveUp after succeeding tap, got $end", end is TaskState.Failed && end.kind == FailKind.GaveUp)
        assertEquals("give up after real tap", (end as TaskState.Failed).reason)
        val endResults = sink.events.filterIsInstance<RunEvent.ToolResult>().filter { it.tool == "end_not_done" }
        assertEquals(2, endResults.size)
        assertTrue(endResults[0].resultJson.contains("stuck_screen_giveup_blocked"))
        assertFalse(endResults[1].resultJson.contains("stuck_screen_giveup_blocked"))

        // 3x [phone_back(ok=true), phone_tap(ok=false)] repeating sequence must STILL NOT unlock end_not_done
        tapAttempt = 0
        val seqFailReplies = listOf(
            ModelReply(listOf(call("phone_observe")), StopReason.ToolUse, Usage(400, 20, 0)),
            ModelReply(listOf(call("phone_list_elements")), StopReason.ToolUse, Usage(500, 20, 200)),
            ModelReply(listOf(call("view_past_screens", """{"ids":[1]}""")), StopReason.ToolUse, Usage(600, 20, 300)),
            ModelReply(listOf(call("task_memory_search", """{"query":"item"}""")), StopReason.ToolUse, Usage(700, 20, 400)),
            ModelReply(listOf(call("phone_back")), StopReason.ToolUse, Usage(750, 20, 420)),
            ModelReply(listOf(call("phone_tap", """{"x":200,"y":150}""")), StopReason.ToolUse, Usage(800, 20, 450)),
            ModelReply(listOf(call("phone_back")), StopReason.ToolUse, Usage(850, 20, 470)),
            ModelReply(listOf(call("phone_tap", """{"x":200,"y":150}""")), StopReason.ToolUse, Usage(900, 20, 500)),
            ModelReply(listOf(call("phone_back")), StopReason.ToolUse, Usage(950, 20, 520)),
            ModelReply(listOf(call("phone_tap", """{"x":200,"y":150}""")), StopReason.ToolUse, Usage(1000, 20, 550)),
            ModelReply(listOf(call("end_not_done", """{"message":"give up after 3x failed tap seq"}""")), StopReason.ToolUse, Usage(1050, 20, 600)),
            ModelReply(listOf(call("end_not_done", """{"message":"give up again"}""")), StopReason.ToolUse, Usage(1100, 20, 650)),
            ModelReply(listOf(call("end_not_done", """{"message":"give up third time"}""")), StopReason.ToolUse, Usage(1150, 20, 700)),
        )
        val alwaysFailingTapScreen = object : ScreenPort by FakeScreen() {
            private val snap = ScreenSnapshot(
                "snap-fixed-seq",
                "com.shop.app",
                listOf(UiElement("@e1", "button", "Target", 200, 150, listOf(0, 0, 900, 200), null, true, false, false)),
                1080,
                2400,
                windows = listOf(UiWindowTree(1, "application", 1, "App", "com.shop.app", true, true, listOf(UiTreeNode("TextView", listOf(0, 0, 900, 200), text = "Static")))),
            )
            override suspend fun snapshot() = snap
            override suspend fun peek() = snap
            override suspend fun back() = ActionResult.Ok(changed = false)
            override suspend fun tapXY(x: Int, y: Int) = ActionResult.Failed("tap_injection_failed")
        }
        val sinkSeqFail = RecordingSink()
        val endSeqFail = AgentLoop(
            model = FakeModel(*seqFailReplies.toTypedArray(), contextTokens = 200_000),
            tools = toolDispatcher(alwaysFailingTapScreen, visionEnabled = false, inlineScreen = true, taskMemory = taskMemoryStore),
            basePrompt = "BASE",
            flags = SpeedFlags(inlineScreen = true),
            maxSteps = AgentLoop.UNLIMITED_STEPS,
            sink = sinkSeqFail,
        ).run("Open item")
        assertTrue("Expected Guard stop instead of GaveUp when all taps failed, got $endSeqFail", endSeqFail is TaskState.Failed && endSeqFail.kind == FailKind.Guard)
        val seqFailEndResults = sinkSeqFail.events.filterIsInstance<RunEvent.ToolResult>().filter { it.tool == "end_not_done" }
        assertEquals(2, seqFailEndResults.size)
        assertTrue(seqFailEndResults.all { it.resultJson.contains("stuck_screen_giveup_blocked") })
    }

    @Test
    fun batchGuardsStopPopulatesTelemetryOnModelReply() = runTest {
        val staticScreen = object : ScreenPort by FakeScreen() {
            private val snap = ScreenSnapshot(
                "snap-batch",
                "com.shop.app",
                listOf(UiElement("@e1", "button", "Item", 200, 150, listOf(0, 0, 900, 200), null, true, false, false)),
                1080,
                2400,
                windows = listOf(UiWindowTree(1, "application", 1, "App", "com.shop.app", true, true, listOf(UiTreeNode("TextView", listOf(0, 0, 900, 200), text = "Static")))),
            )
            override suspend fun snapshot() = snap
            override suspend fun peek() = snap
            override suspend fun tapXY(x: Int, y: Int) = ActionResult.Ok(changed = false)
            override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = ActionResult.Ok(changed = false)
        }
        val batchStepsJson = (1..12).joinToString(",") { """{"tool":"phone_tap","args":{"x":200,"y":150}}""" }
        val sink = RecordingSink()
        val end = AgentLoop(
            model = FakeModel(
                ModelReply(listOf(call("phone_observe")), StopReason.ToolUse, Usage(400, 20, 0)),
                ModelReply(listOf(call("do_steps", """{"steps":[$batchStepsJson]}""")), StopReason.ToolUse, Usage(600, 20, 300)),
                contextTokens = 200_000,
            ),
            tools = toolDispatcher(staticScreen, visionEnabled = false, inlineScreen = true),
            basePrompt = "BASE",
            flags = SpeedFlags(inlineScreen = true, batchSteps = true),
            maxSteps = AgentLoop.UNLIMITED_STEPS,
            sink = sink,
        ).run("Batch tap")
        assertTrue("Expected Guard stop from batch no_progress, got $end", end is TaskState.Failed && end.kind == FailKind.Guard)
        val batchReply = sink.events.filterIsInstance<RunEvent.ModelReply>().last()
        assertEquals("do_steps", batchReply.tool)
        assertNotNull(batchReply.screenHash)
        assertEquals(false, batchReply.screenChanged)
        assertTrue(batchReply.toolSequence?.contains("do_steps") == true)
        assertEquals("no_progress", batchReply.breakerTrigger)

        // Even after an earlier stuck_screen_3x warning, do_steps hitting no_progress must emit "no_progress" (not stale "stuck_screen_3x")
        val taskMemoryStore = com.wangzan101.mika.core.learn.memory.TaskMemoryStore(
            object : com.wangzan101.mika.core.learn.memory.TaskMemoryPersistence {
                override fun load() = emptyList<com.wangzan101.mika.core.learn.memory.TaskMemory>()
                override fun save(items: List<com.wangzan101.mika.core.learn.memory.TaskMemory>) {}
            },
            clock = { 0L },
            ids = { "x" },
        )
        val sinkAfterStuck = RecordingSink()
        val endAfterStuck = AgentLoop(
            model = FakeModel(
                ModelReply(listOf(call("phone_observe")), StopReason.ToolUse, Usage(400, 20, 0)),
                ModelReply(listOf(call("view_past_screens", """{"ids":[1]}""")), StopReason.ToolUse, Usage(450, 20, 100)),
                ModelReply(listOf(call("task_memory_search", """{"query":"item"}""")), StopReason.ToolUse, Usage(500, 20, 150)),
                ModelReply(listOf(call("task_memory_search", """{"query":"item2"}""")), StopReason.ToolUse, Usage(550, 20, 200)),
                ModelReply(listOf(call("phone_scroll", """{"direction":"down"}""")), StopReason.ToolUse, Usage(600, 20, 250)),
                ModelReply(listOf(call("do_steps", """{"steps":[$batchStepsJson]}""")), StopReason.ToolUse, Usage(700, 20, 300)),
                contextTokens = 200_000,
            ),
            tools = toolDispatcher(staticScreen, visionEnabled = false, inlineScreen = true, taskMemory = taskMemoryStore),
            basePrompt = "BASE",
            flags = SpeedFlags(inlineScreen = true, batchSteps = true),
            maxSteps = AgentLoop.UNLIMITED_STEPS,
            sink = sinkAfterStuck,
        ).run("Batch tap after stuck")
        assertTrue("Expected Guard stop from batch no_progress, got $endAfterStuck", endAfterStuck is TaskState.Failed && endAfterStuck.kind == FailKind.Guard)
        val repliesAfterStuck = sinkAfterStuck.events.filterIsInstance<RunEvent.ModelReply>()
        assertEquals("stuck_screen_3x", repliesAfterStuck[4].breakerTrigger)
        val batchReplyAfterStuck = repliesAfterStuck.last()
        assertEquals("do_steps", batchReplyAfterStuck.tool)
        assertNotNull(batchReplyAfterStuck.screenHash)
        assertEquals(false, batchReplyAfterStuck.screenChanged)
        assertTrue(batchReplyAfterStuck.toolSequence?.contains("do_steps") == true)
        assertEquals("no_progress", batchReplyAfterStuck.breakerTrigger)
    }

    @Test
    fun alternatingFiveAllowedToolsOnStuckScreenStopsUnderUnlimitedSteps() = runTest {
        val staticScreen = object : ScreenPort by FakeScreen() {
            private val snap = ScreenSnapshot(
                "snap-5alt",
                "com.shop.app",
                listOf(UiElement("@e1", "button", "Item", 200, 150, listOf(0, 0, 900, 200), null, true, false, false)),
                1080,
                2400,
                windows = listOf(UiWindowTree(1, "application", 1, "App", "com.shop.app", true, true, listOf(UiTreeNode("TextView", listOf(0, 0, 900, 200), text = "Static")))),
            )
            override suspend fun snapshot() = snap
            override suspend fun peek() = snap
            override suspend fun back() = ActionResult.Ok(changed = false)
            override suspend fun pressKey(keyCode: Int) = ActionResult.Ok(changed = false)
            override suspend fun clearText(ref: String?, snapshotId: String?) = ActionResult.Ok(changed = false)
            override suspend fun openApp(packageName: String, cold: Boolean) = ActionResult.Ok(changed = false)
            override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = ActionResult.Ok(changed = false)
        }
        val taskMemoryStore = com.wangzan101.mika.core.learn.memory.TaskMemoryStore(
            object : com.wangzan101.mika.core.learn.memory.TaskMemoryPersistence {
                override fun load() = emptyList<com.wangzan101.mika.core.learn.memory.TaskMemory>()
                override fun save(items: List<com.wangzan101.mika.core.learn.memory.TaskMemory>) {}
            },
            clock = { 0L },
            ids = { "x" },
        )
        val cycle = listOf(
            call("phone_back"),
            call("phone_press_key", """{"keyCode":4}"""),
            call("phone_clear_text", """{"ref":"@e1","snapshotId":"snap-5alt"}"""),
            call("phone_launch_app", """{"packageName":"com.shop.app"}"""),
            call("phone_type_text", """{"ref":"@e1","snapshotId":"snap-5alt","text":"abc"}"""),
        )
        val replies = listOf(
            ModelReply(listOf(call("phone_observe")), StopReason.ToolUse, Usage(400, 20, 0)),
            ModelReply(listOf(call("phone_list_elements")), StopReason.ToolUse, Usage(450, 20, 100)),
            ModelReply(listOf(call("view_past_screens", """{"ids":[1]}""")), StopReason.ToolUse, Usage(500, 20, 150)),
            ModelReply(listOf(call("task_memory_search", """{"query":"item"}""")), StopReason.ToolUse, Usage(550, 20, 200)),
        ) + List(20) { idx ->
            ModelReply(listOf(cycle[idx % cycle.size]), StopReason.ToolUse, Usage(600 + idx * 50, 20, 250))
        }
        val sink = RecordingSink()
        val end = AgentLoop(
            model = FakeModel(*replies.toTypedArray(), contextTokens = 200_000),
            tools = toolDispatcher(staticScreen, visionEnabled = false, inlineScreen = true, taskMemory = taskMemoryStore),
            basePrompt = "BASE",
            flags = SpeedFlags(inlineScreen = true),
            maxSteps = AgentLoop.UNLIMITED_STEPS,
            sink = sink,
        ).run("Cycle 5 allowed tools")
        assertTrue("Expected Guard stop for 5 alternating allowed tools, got $end", end is TaskState.Failed && end.kind == FailKind.Guard)
        assertTrue("Expected stuck_screen reason, got ${(end as TaskState.Failed).reason}", end.reason.contains("stuck_screen"))
        val modelReplies = sink.events.filterIsInstance<RunEvent.ModelReply>()
        assertEquals("stuck_screen_3x_stop", modelReplies.last().breakerTrigger)
        assertTrue("Must reach stuck_screen max unchanged and stop boundedly (was ${modelReplies.size} replies)", modelReplies.size in 10..16)
    }

    @Test
    fun crossScreenFailedTapSequenceDoesNotUnlockGiveUpOnNewScreen() = runTest {
        var ver = 1
        var tapCount = 0
        var backCount = 0
        val screen = object : ScreenPort by FakeScreen() {
            private fun buildSnap(v: Int) = ScreenSnapshot(
                "snap-cross-$v",
                "com.shop.app",
                listOf(UiElement("@e1", "button", "Target-$v", 200, 150, listOf(0, 0, 900, 200), null, true, false, false)),
                1080,
                2400,
                windows = listOf(UiWindowTree(1, "application", 1, "App", "com.shop.app", true, true, listOf(UiTreeNode("TextView", listOf(0, 0, 900, 200), text = "Screen-$v")))),
            )
            override suspend fun snapshot() = buildSnap(ver)
            override suspend fun peek() = buildSnap(ver)
            override suspend fun back(): ActionResult {
                backCount++
                return if (backCount == 2) {
                    ver = 2
                    ActionResult.Ok(changed = true)
                } else {
                    ActionResult.Ok(changed = false)
                }
            }
            override suspend fun tapXY(x: Int, y: Int): ActionResult {
                tapCount++
                return if (tapCount == 1) ActionResult.Ok(changed = false)
                else ActionResult.Failed("tap_injection_failed")
            }
        }
        val replies = listOf(
            // On h1: observe -> back(ok=true) -> tap(ok=true, changed=false)
            ModelReply(listOf(call("phone_observe")), StopReason.ToolUse, Usage(400, 20, 0)),
            ModelReply(listOf(call("phone_back")), StopReason.ToolUse, Usage(450, 20, 100)),
            ModelReply(listOf(call("phone_tap", """{"x":200,"y":150}""")), StopReason.ToolUse, Usage(500, 20, 150)),
            // Transition h1 -> h2 via phone_back
            ModelReply(listOf(call("phone_back")), StopReason.ToolUse, Usage(550, 20, 200)),
            // On h2: tap(ok=false) -> back(ok=true) -> tap(ok=false)
            ModelReply(listOf(call("phone_tap", """{"x":200,"y":150}""")), StopReason.ToolUse, Usage(600, 20, 250)),
            ModelReply(listOf(call("phone_back")), StopReason.ToolUse, Usage(650, 20, 300)),
            ModelReply(listOf(call("phone_tap", """{"x":200,"y":150}""")), StopReason.ToolUse, Usage(700, 20, 350)),
            // Give-up attempts on h2 MUST remain blocked because zero taps succeeded on h2
            ModelReply(listOf(call("end_not_done", """{"message":"give up on h2"}""")), StopReason.ToolUse, Usage(750, 20, 400)),
            ModelReply(listOf(call("end_not_done", """{"message":"give up on h2 again"}""")), StopReason.ToolUse, Usage(800, 20, 450)),
            ModelReply(listOf(call("end_not_done", """{"message":"give up on h2 third"}""")), StopReason.ToolUse, Usage(850, 20, 500)),
        )
        val sink = RecordingSink()
        val end = AgentLoop(
            model = FakeModel(*replies.toTypedArray(), contextTokens = 200_000),
            tools = toolDispatcher(screen, visionEnabled = false, inlineScreen = true),
            basePrompt = "BASE",
            flags = SpeedFlags(inlineScreen = true),
            maxSteps = AgentLoop.UNLIMITED_STEPS,
            sink = sink,
        ).run("Open item on h2")

        assertTrue("Expected Guard stop instead of GaveUp when h2 taps failed, got $end", end is TaskState.Failed && end.kind == FailKind.Guard)
        val endResults = sink.events.filterIsInstance<RunEvent.ToolResult>().filter { it.tool == "end_not_done" }
        assertEquals(2, endResults.size)
        assertTrue(endResults.all { it.resultJson.contains("stuck_screen_giveup_blocked") })
    }

    @Test
    fun batchSubstepScreenChangeFollowedByGuardStopUsesUpdatedScreenHash() = runTest {
        var ver = 1
        var tapCount = 0
        val screen = object : ScreenPort by FakeScreen() {
            private fun buildSnap(v: Int) = ScreenSnapshot(
                "snap-batch-change-$v",
                "com.shop.app",
                listOf(UiElement("@e1", "button", "Item-$v", 200, 150, listOf(0, 0, 900, 200), null, true, false, false)),
                1080,
                2400,
                windows = listOf(UiWindowTree(1, "application", 1, "App", "com.shop.app", true, true, listOf(UiTreeNode("TextView", listOf(0, 0, 900, 200), text = "BatchScreen-$v")))),
            )
            override suspend fun snapshot() = buildSnap(ver)
            override suspend fun peek() = buildSnap(ver)
            override suspend fun tapXY(x: Int, y: Int): ActionResult {
                tapCount++
                return if (tapCount == 1) {
                    ver = 2
                    ActionResult.Ok(changed = true)
                } else {
                    ActionResult.Ok(changed = false)
                }
            }
        }
        val batchStepsJson = (1..14).joinToString(",") { """{"tool":"phone_tap","args":{"x":200,"y":150}}""" }
        val sink = RecordingSink()
        val end = AgentLoop(
            model = FakeModel(
                ModelReply(listOf(call("phone_observe")), StopReason.ToolUse, Usage(400, 20, 0)),
                ModelReply(listOf(call("do_steps", """{"steps":[$batchStepsJson]}""")), StopReason.ToolUse, Usage(600, 20, 300)),
                contextTokens = 200_000,
            ),
            tools = toolDispatcher(screen, visionEnabled = false, inlineScreen = true),
            basePrompt = "BASE",
            flags = SpeedFlags(inlineScreen = true, batchSteps = true),
            maxSteps = AgentLoop.UNLIMITED_STEPS,
            sink = sink,
        ).run("Batch change then stop")

        assertTrue("Expected Guard stop from batch no_progress, got $end", end is TaskState.Failed && end.kind == FailKind.Guard)
        val modelReplies = sink.events.filterIsInstance<RunEvent.ModelReply>()
        assertEquals(2, modelReplies.size)
        val h1Hash = modelReplies[0].screenHash
        val batchReply = modelReplies[1]
        assertNotNull(h1Hash)
        assertNotNull(batchReply.screenHash)
        assertTrue("Expected batch screenHash (${batchReply.screenHash}) to reflect updated h2 instead of stale h1 ($h1Hash)", batchReply.screenHash != h1Hash)
        assertEquals("no_progress", batchReply.breakerTrigger)
    }
}
