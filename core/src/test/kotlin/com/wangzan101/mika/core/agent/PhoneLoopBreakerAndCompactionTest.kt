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
        // Cycle 3: tries phone_scroll (blocked #1), tries phone_observe (blocked #2 -> guard stops loop cleanly!)
        val replies = listOf(
            ModelReply(listOf(call("phone_observe")), StopReason.ToolUse, Usage(500, 20, 0)),
            ModelReply(listOf(call("phone_extract", """{"mode":"list"}""")), StopReason.ToolUse, Usage(700, 20, 400)),
            ModelReply(listOf(call("phone_scroll", """{"direction":"down"}""")), StopReason.ToolUse, Usage(900, 20, 600)),
            ModelReply(listOf(call("phone_observe")), StopReason.ToolUse, Usage(1100, 20, 800)),
            ModelReply(listOf(call("phone_extract", """{"mode":"list"}""")), StopReason.ToolUse, Usage(1300, 20, 1000)),
            ModelReply(listOf(call("phone_scroll", """{"direction":"down"}""")), StopReason.ToolUse, Usage(1500, 20, 1200)),
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
        assertTrue("Must stop well before 52 steps (was ${modelReplies.size})", modelReplies.size <= 7)

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
}
