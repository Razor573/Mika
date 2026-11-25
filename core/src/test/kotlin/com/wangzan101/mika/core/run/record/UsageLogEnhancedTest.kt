package com.wangzan101.mika.core.run.record

import com.wangzan101.mika.core.agent.AgentLoop
import com.wangzan101.mika.core.agent.ModelRouter
import com.wangzan101.mika.core.agent.SpeedFlags
import com.wangzan101.mika.core.agent.TaskState
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
import com.wangzan101.mika.core.model.usage.UsagePurpose
import com.wangzan101.mika.core.model.usage.UsageSink
import com.wangzan101.mika.core.model.usage.UsageSource
import com.wangzan101.mika.core.model.usage.metered
import com.wangzan101.mika.core.model.usage.RunUsageTally
import com.wangzan101.mika.core.run.ChildAgentRunner
import com.wangzan101.mika.core.run.MemoryChildRunStore
import com.wangzan101.mika.core.run.RunId
import com.wangzan101.mika.core.run.RunOrigin
import com.wangzan101.mika.core.run.RunProvenance
import com.wangzan101.mika.core.run.RunSpec
import com.wangzan101.mika.core.screen.uistate.ActionResult
import com.wangzan101.mika.core.screen.uistate.ScreenPort
import com.wangzan101.mika.core.screen.uistate.ScreenSnapshot
import com.wangzan101.mika.core.screen.uistate.UiElement
import com.wangzan101.mika.core.screen.uistate.UiTreeNode
import com.wangzan101.mika.core.screen.uistate.UiWindowTree
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.cap.web.FakeWebPort
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsageLogEnhancedTest {
    private val seq = AtomicInteger(0)
    private fun call(tool: String, args: String = "{}") = Part.ToolCall("u${seq.incrementAndGet()}", tool, args)

    private class RecordingEvents : RunSink {
        val list = mutableListOf<RunEvent>()
        override fun event(e: RunEvent) {
            synchronized(list) { list += e }
        }
    }

    private class ScriptedModel(
        override val name: String,
        override val provider: String,
        private val script: (Int) -> Any,
        vision: Boolean = true,
    ) : ChatModel {
        override val capabilities = Capabilities(vision = vision, contextTokens = 200_000, toolCalls = true, reasoning = false)
        private var count = 0
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
            val n = ++count
            return when (val r = script(n)) {
                is ModelReply -> r
                is Throwable -> throw r
                else -> error("unexpected $r")
            }
        }
    }

    @Test
    fun lightErrorsEmitRealModelReplyRowsWithRedactedHttpDiagnosticsAndMainMetadata() = runTest {
        val rawBodyWithSecret = """{"error":{"status":"INVALID_ARGUMENT","message":"Invalid token AIzaSyFakeSecretKey1234567890123456 and Bearer sk-secret-token-abcdef1234567890"}}"""
        val lightError = ModelError.Server(400, "Bad request").withDiagnostics(400, "INVALID_ARGUMENT", rawBodyWithSecret)

        val mainModel = ScriptedModel(
            name = "gemini-4-hydrogen",
            provider = "vertex",
            script = { step ->
                when (step) {
                    1 -> ModelReply(listOf(call("phone_observe")), StopReason.ToolUse, Usage(2000, 80, 0))
                    2 -> ModelReply(listOf(call("phone_observe")), StopReason.ToolUse, Usage(2500, 90, 1800))
                    3 -> ModelReply(listOf(call("phone_tap", """{"x":10,"y":20}""")), StopReason.ToolUse, Usage(3000, 75, 2400))
                    else -> ModelReply(listOf(Part.Text("SECRET_USER_ANSWER_DO_NOT_LOG")), StopReason.EndTurn, Usage(3200, 60, 2600))
                }
            },
        )
        val lightModel = ScriptedModel(
            name = "gemini-3.8-flash",
            provider = "gemini",
            script = { throw lightError },
        )

        val sink = RecordingEvents()
        val tally = RunUsageTally()
        val meteredMain = mainModel.metered(UsageSource("p-main", "vertex", mainModel.name, UsagePurpose.Main), UsageSink { _, _ -> })
        val meteredLight = lightModel.metered(UsageSource("p-light", "gemini", lightModel.name, UsagePurpose.Light), UsageSink { _, _ -> })

        val outcome = withContext(tally) {
            AgentLoop(
                model = meteredMain,
                tools = toolDispatcher(FakeScreen(), visionEnabled = true),
                basePrompt = "BASE_SECRET_PROMPT_DO_NOT_LOG",
                flags = SpeedFlags(lightSteps = true),
                router = ModelRouter(meteredMain, meteredLight),
                sink = sink,
                promptTransform = { "$it\nTRANSFORMED" },
                versionCode = 61,
                versionName = "0.1.56",
            ).run("SECRET_GOAL_DO_NOT_LOG")
        }
        assertTrue("$outcome", outcome is TaskState.Succeeded)
        sink.event(tally.event(System.currentTimeMillis()))

        val formatted = UsageLogFormatter.formatEvents(sink.list, runId = "20261124-210000-test01", versionCode = 61, versionName = "0.1.56")
        val lines = formatted.trimEnd().lines()
        val rows = lines.map { Json.parseToJsonElement(it).jsonObject }

        // Privacy checks
        assertFalse(formatted.contains("AIzaSyFakeSecretKey"))
        assertFalse(formatted.contains("sk-secret-token"))
        assertFalse(formatted.contains("SECRET_USER_ANSWER_DO_NOT_LOG"))
        assertFalse(formatted.contains("BASE_SECRET_PROMPT_DO_NOT_LOG"))
        assertFalse(formatted.contains("SECRET_GOAL_DO_NOT_LOG"))

        // Light per-attempt error rows (step 3 tried twice -> both role="light", accepted=false)
        val lightRows = rows.filter { it["type"]?.jsonPrimitive?.content == "model_reply" && it["role"]?.jsonPrimitive?.content == "light" }
        assertEquals(2, lightRows.size)
        for ((idx, row) in lightRows.withIndex()) {
            assertEquals(false, row["accepted"]?.jsonPrimitive?.boolean)
            assertEquals(idx + 1, row["attempt"]?.jsonPrimitive?.int)
            assertEquals(idx, row["retryCount"]?.jsonPrimitive?.int)
            assertEquals(400, row["httpStatus"]?.jsonPrimitive?.int)
            assertEquals("INVALID_ARGUMENT", row["errorCode"]?.jsonPrimitive?.content)
            assertNotNull(row["errorBody"]?.jsonPrimitive?.content)
            assertEquals("gemini-3.8-flash", row["model"]?.jsonPrimitive?.content)
            assertEquals("gemini", row["provider"]?.jsonPrimitive?.content)
            assertEquals(0, row["inputTokens"]?.jsonPrimitive?.int)
            assertEquals(0, row["outputTokens"]?.jsonPrimitive?.int)
            assertEquals(0, row["cachedTokens"]?.jsonPrimitive?.int)
            assertEquals(false, row["usageReported"]?.jsonPrimitive?.boolean)
            assertEquals(3, row["step"]?.jsonPrimitive?.int)
            assertNotNull(row["hasImages"])
            assertNotNull(row["vision"])
            assertNotNull(row["sameApp"])
            assertEquals(false, row["forcedMain"]?.jsonPrimitive?.boolean)
            assertNotNull(row["latencyMs"])
            assertNotNull(row["retryWaitMs"])
        }
        println("SAMPLE_LIGHT_ERROR_ROW=${lines[rows.indexOf(lightRows.first())]}")

        // Main per-call fields
        val mainRows = rows.filter { it["type"]?.jsonPrimitive?.content == "model_reply" && it["role"]?.jsonPrimitive?.content == "main" }
        assertEquals(4, mainRows.size)
        val firstMain = mainRows.first()
        assertEquals("vertex", firstMain["provider"]?.jsonPrimitive?.content)
        assertEquals("cold_start", firstMain["cacheMissReason"]?.jsonPrimitive?.content)
        assertNotNull(firstMain["systemPromptHash"]?.jsonPrimitive?.content)
        assertNotNull(firstMain["promptVersion"]?.jsonPrimitive?.content)
        assertNotNull(firstMain["toolListHash"]?.jsonPrimitive?.content)
        assertEquals("modified", firstMain["promptTransformEffect"]?.jsonPrimitive?.content)
        assertEquals("ok", firstMain["capCheckResult"]?.jsonPrimitive?.content)
        assertTrue((firstMain["estimatedInputTokens"]?.jsonPrimitive?.int ?: 0) > 0)
        assertTrue((firstMain["costUsd"]?.jsonPrimitive?.double ?: 0.0) > 0.0)
        assertNotNull(firstMain["priceInputPerM"])
        assertNotNull(firstMain["priceOutputPerM"])
        assertNotNull(firstMain["priceCachedPerM"])
        assertNotNull(firstMain["priceSource"])
        assertNotNull(firstMain["priceFetchedAt"])
        assertEquals("standard", firstMain["priceTier"]?.jsonPrimitive?.content)
        assertNotNull(firstMain["priceFallback"])

        // Summary row reconciles unreported reasons
        val summary = rows.last()
        assertEquals("run_summary", summary["type"]?.jsonPrimitive?.content)
        assertEquals(61, summary["versionCode"]?.jsonPrimitive?.int)
        assertEquals("0.1.56", summary["versionName"]?.jsonPrimitive?.content)
        assertEquals(2, summary["unreportedCalls"]?.jsonPrimitive?.int)
        assertTrue((summary["unreportedReasons"]?.jsonObject?.isNotEmpty()) == true)
    }

    @Test
    fun subAgentChildLogsAndSummaryTotalsReconcileWithParent() = runTest {
        val childLogs = linkedMapOf<String, String>()
        val parentSink = RecordingEvents()
        val childStore = MemoryChildRunStore()

        val fakeWeb = FakeWebPort()

        val childRunner = ChildAgentRunner(
            create = { spec, _ ->
                val childMain = ScriptedModel(
                    name = "gemini-4-hydrogen",
                    provider = "vertex",
                    script = { step ->
                        if (step == 1) ModelReply(listOf(call("web_open", """{"url":"https://example.com"}""")), StopReason.ToolUse, Usage(1000, 40, 500))
                        else ModelReply(listOf(Part.Text("child finding for ${spec.goal}")), StopReason.EndTurn, Usage(1200, 30, 800))
                    },
                ).metered(UsageSource("p-main", "vertex", "gemini-4-hydrogen", UsagePurpose.Main), UsageSink { _, _ -> })
                AgentLoop(
                    model = childMain,
                    tools = toolDispatcher(FakeScreen(), web = fakeWeb, runSpec = spec),
                    basePrompt = "CHILD_PROMPT",
                    maxSteps = 5,
                    versionCode = 61,
                    versionName = "0.1.56",
                    mainProvider = "vertex",
                )
            },
            store = childStore,
            parentSink = parentSink,
            writeChildLog = { _, childId, content -> childLogs[childId] = content },
            versionCode = 61,
            versionName = "0.1.56",
        )

        val parentMain = ScriptedModel(
            name = "gemini-4-hydrogen",
            provider = "vertex",
            script = { step ->
                when (step) {
                    1 -> ModelReply(
                        listOf(call("spawn_agent", """{"tasks":["Check laptop A","Check laptop B"]}""")),
                        StopReason.ToolUse,
                        Usage(2000, 60, 1000),
                    )
                    else -> ModelReply(listOf(Part.Text("Combined parent answer")), StopReason.EndTurn, Usage(2600, 50, 1800))
                }
            },
        ).metered(UsageSource("p-main", "vertex", "gemini-4-hydrogen", UsagePurpose.Main), UsageSink { _, _ -> })

        val parentSpec = RunSpec(
            id = RunId("20261124-210001-parent"),
            goal = "Compare two laptops",
            threadId = "t1",
            provenance = RunProvenance(RunOrigin.Ui),
        )
        val parentTally = RunUsageTally()
        val parentOutcome = withContext(parentTally) {
            AgentLoop(
                model = parentMain,
                tools = toolDispatcher(FakeScreen(), web = fakeWeb, childHost = childRunner, runSpec = parentSpec),
                basePrompt = "PARENT_PROMPT",
                sink = parentSink,
                versionCode = 61,
                versionName = "0.1.56",
                mainProvider = "vertex",
            ).run(parentSpec, com.wangzan101.mika.core.run.RunInbox())
        }
        assertTrue("$parentOutcome", parentOutcome is TaskState.Succeeded)
        parentSink.event(parentTally.event(System.currentTimeMillis()))

        assertEquals(2, childLogs.size)
        for ((childId, content) in childLogs) {
            assertTrue("child log must contain run_summary", content.contains(""""type":"run_summary""""))
            assertTrue("child log must mention childId", content.contains(childId))
        }

        val parentFormatted = UsageLogFormatter.formatEvents(parentSink.list, runId = parentSpec.id.value, versionCode = 61, versionName = "0.1.56")
        val parentRows = parentFormatted.trimEnd().lines().map { Json.parseToJsonElement(it).jsonObject }
        val subAgentRows = parentRows.filter { it["type"]?.jsonPrimitive?.content == "sub_agent_summary" }
        assertEquals(2, subAgentRows.size)
        for (sub in subAgentRows) {
            assertEquals(1, sub["parentStep"]?.jsonPrimitive?.int)
            assertEquals("succeeded", sub["outcome"]?.jsonPrimitive?.content)
            val cid = sub["childId"]?.jsonPrimitive?.content!!
            assertEquals("mika-usage-child-$cid.jsonl", sub["logFile"]?.jsonPrimitive?.content)
            assertTrue(childLogs.containsKey(cid))
            assertEquals(2200L, sub["inputTokens"]?.jsonPrimitive?.long)
            assertEquals(70L, sub["outputTokens"]?.jsonPrimitive?.long)
            assertEquals(1300L, sub["cachedTokens"]?.jsonPrimitive?.long)
        }
        val sampleSubLine = parentFormatted.trimEnd().lines().first { it.contains(""""type":"sub_agent_summary"""") }
        println("SAMPLE_SUB_AGENT_SUMMARY_ROW=$sampleSubLine")

        val summary = parentRows.last()
        assertEquals("run_summary", summary["type"]?.jsonPrimitive?.content)
        assertEquals(0, summary["unreportedCalls"]?.jsonPrimitive?.int)
        assertEquals(2, summary["parentCalls"]?.jsonPrimitive?.int)
        assertEquals(4, summary["subAgentCalls"]?.jsonPrimitive?.int)
        assertEquals(6, summary["totalCalls"]?.jsonPrimitive?.int)
        // Parent: (2000+2600)=4600 in, 110 out, 2800 cached; 2 children: 2*(2200 in, 70 out, 1300 cached)
        assertEquals(4600L + 4400L, summary["totalInputTokens"]?.jsonPrimitive?.long)
        assertEquals(110L + 140L, summary["totalOutputTokens"]?.jsonPrimitive?.long)
        assertEquals(2800L + 2600L, summary["totalCachedTokens"]?.jsonPrimitive?.long)
        assertEquals(2, summary["subAgents"]?.jsonArray?.size)
    }

    @Test
    fun usageLogRecordsScreenHashScreenChangedToolSequenceAndBreakerTriggerOnNormalAndBlockedSteps() = runTest {
        var ver = 1
        var tapChanges = false
        val screen = object : ScreenPort by FakeScreen() {
            private fun snap(v: Int): ScreenSnapshot {
                val node = UiTreeNode("TextView", listOf(0, 0, 900, 200), text = "Screen-$v")
                val win = UiWindowTree(1, "application", 1, "App", "com.shop.app", true, true, listOf(node))
                val el = UiElement("@e1", "button", "Item-$v", 200, 150, listOf(0, 0, 900, 200), null, true, false, false)
                return ScreenSnapshot("s-$v", "com.shop.app", listOf(el), 1080, 2400, windows = listOf(win))
            }
            override suspend fun snapshot() = snap(ver)
            override suspend fun peek() = snap(ver)
            override suspend fun tapRef(ref: String, snapshotId: String): ActionResult {
                if (tapChanges) ver++
                return ActionResult.Ok(changed = tapChanges)
            }
            override suspend fun tapXY(x: Int, y: Int): ActionResult {
                if (tapChanges) ver++
                return ActionResult.Ok(changed = tapChanges)
            }
        }
        val model = ScriptedModel(
            name = "gemini-4-hydrogen",
            provider = "vertex",
            script = { step ->
                when (step) {
                    1 -> ModelReply(listOf(call("phone_observe")), StopReason.ToolUse, Usage(1000, 30, 0))
                    2 -> ModelReply(listOf(call("phone_list_elements")), StopReason.ToolUse, Usage(1200, 30, 800))
                    3 -> ModelReply(listOf(call("view_past_screens", """{"ids":[1]}""")), StopReason.ToolUse, Usage(1400, 30, 900))
                    4 -> ModelReply(listOf(call("task_memory_search", """{"query":"item"}""")), StopReason.ToolUse, Usage(1600, 30, 1000))
                    5 -> ModelReply(listOf(call("phone_observe")), StopReason.ToolUse, Usage(1800, 30, 1100))
                    6 -> {
                        tapChanges = true
                        ModelReply(listOf(call("phone_tap", """{"x":200,"y":150}""")), StopReason.ToolUse, Usage(2000, 30, 1200))
                    }
                    else -> ModelReply(listOf(Part.Text("done")), StopReason.EndTurn, Usage(2200, 30, 1400))
                }
            },
        )
        val sink = RecordingEvents()
        val tally = RunUsageTally()
        val metered = model.metered(UsageSource("p-main", "vertex", model.name, UsagePurpose.Main), UsageSink { _, _ -> })
        val taskMemoryStore = com.wangzan101.mika.core.learn.memory.TaskMemoryStore(
            object : com.wangzan101.mika.core.learn.memory.TaskMemoryPersistence {
                override fun load() = emptyList<com.wangzan101.mika.core.learn.memory.TaskMemory>()
                override fun save(items: List<com.wangzan101.mika.core.learn.memory.TaskMemory>) {}
            },
            clock = { 0L },
            ids = { "x" },
        )
        val end = withContext(tally) {
            AgentLoop(
                model = metered,
                tools = toolDispatcher(screen, visionEnabled = false, inlineScreen = true, taskMemory = taskMemoryStore),
                basePrompt = "BASE",
                flags = SpeedFlags(inlineScreen = true),
                sink = sink,
            ).run("Check telemetry")
        }
        assertTrue("$end", end is TaskState.Succeeded)
        sink.event(tally.event(System.currentTimeMillis()))

        val formatted = UsageLogFormatter.formatEvents(sink.list, runId = "20261124-210003-r3", versionCode = 62, versionName = "0.1.57")
        val rows = formatted.trimEnd().lines().map { Json.parseToJsonElement(it).jsonObject }
        val replyRows = rows.filter { it["type"]?.jsonPrimitive?.content == "model_reply" }
        assertEquals(7, replyRows.size)

        val step1Hash = replyRows[0]["screenHash"]?.jsonPrimitive?.content
        assertNotNull(step1Hash)
        assertEquals(true, replyRows[0]["screenChanged"]?.jsonPrimitive?.boolean)

        for (idx in 1..3) {
            assertEquals(step1Hash, replyRows[idx]["screenHash"]?.jsonPrimitive?.content)
            assertEquals(false, replyRows[idx]["screenChanged"]?.jsonPrimitive?.boolean)
            assertTrue((replyRows[idx]["toolSequence"]?.jsonArray?.size ?: 0) > 0)
        }

        // Step 5 was blocked by stuck_screen_3x: MUST still log screenHash, screenChanged = false, toolSequence, and breakerTrigger
        val blockedRow = replyRows[4]
        assertEquals(step1Hash, blockedRow["screenHash"]?.jsonPrimitive?.content)
        assertEquals(false, blockedRow["screenChanged"]?.jsonPrimitive?.boolean)
        assertEquals("stuck_screen_3x", blockedRow["breakerTrigger"]?.jsonPrimitive?.content)
        val blockedSeq = blockedRow["toolSequence"]?.jsonArray?.map { it.jsonPrimitive.content }
        assertTrue("Expected phone_observe in blocked toolSequence: $blockedSeq", blockedSeq?.contains("phone_observe") == true)

        // Step 6 tap changed the screen
        val tapRow = replyRows[5]
        val tapHash = tapRow["screenHash"]?.jsonPrimitive?.content
        assertNotNull(tapHash)
        assertTrue(tapHash != step1Hash)
        assertEquals(true, tapRow["screenChanged"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun usageLogRecordsBreakerStopTelemetryWhenBlockedGiveUpAndSubsequentStopOccur() = runTest {
        val screen = object : ScreenPort by FakeScreen() {
            private val snap = ScreenSnapshot(
                "s-fixed",
                "com.shop.app",
                listOf(UiElement("@e1", "button", "SameItem", 200, 150, listOf(0, 0, 900, 200), null, true, false, false)),
                1080,
                2400,
                windows = listOf(UiWindowTree(1, "application", 1, "App", "com.shop.app", true, true, listOf(UiTreeNode("TextView", listOf(0, 0, 900, 200), text = "Fixed")))),
            )
            override suspend fun snapshot() = snap
            override suspend fun peek() = snap
            override suspend fun tapRef(ref: String, snapshotId: String) = ActionResult.Ok(changed = false)
            override suspend fun tapXY(x: Int, y: Int) = ActionResult.Ok(changed = false)
        }
        val model = ScriptedModel(
            name = "gemini-4-hydrogen",
            provider = "vertex",
            script = { step ->
                when (step) {
                    1 -> ModelReply(listOf(call("phone_observe")), StopReason.ToolUse, Usage(1000, 20, 0))
                    2 -> ModelReply(listOf(call("phone_list_elements")), StopReason.ToolUse, Usage(1100, 20, 600))
                    3 -> ModelReply(listOf(call("view_past_screens", """{"ids":[1]}""")), StopReason.ToolUse, Usage(1200, 20, 700))
                    4 -> ModelReply(listOf(call("task_memory_search", """{"query":"item"}""")), StopReason.ToolUse, Usage(1300, 20, 800))
                    5 -> ModelReply(listOf(call("end_not_done", """{"message":"give up early"}""")), StopReason.ToolUse, Usage(1400, 20, 900))
                    6 -> ModelReply(listOf(call("phone_tap", """{"x":200,"y":150}""")), StopReason.ToolUse, Usage(1500, 20, 1000))
                    7 -> ModelReply(listOf(call("phone_observe")), StopReason.ToolUse, Usage(1600, 20, 1100))
                    else -> ModelReply(listOf(call("phone_observe")), StopReason.ToolUse, Usage(1700, 20, 1200))
                }
            },
        )
        val sink = RecordingEvents()
        val tally = RunUsageTally()
        val metered = model.metered(UsageSource("p-main", "vertex", model.name, UsagePurpose.Main), UsageSink { _, _ -> })
        val taskMemoryStore = com.wangzan101.mika.core.learn.memory.TaskMemoryStore(
            object : com.wangzan101.mika.core.learn.memory.TaskMemoryPersistence {
                override fun load() = emptyList<com.wangzan101.mika.core.learn.memory.TaskMemory>()
                override fun save(items: List<com.wangzan101.mika.core.learn.memory.TaskMemory>) {}
            },
            clock = { 0L },
            ids = { "x" },
        )
        val end = withContext(tally) {
            AgentLoop(
                model = metered,
                tools = toolDispatcher(screen, visionEnabled = false, inlineScreen = true, taskMemory = taskMemoryStore),
                basePrompt = "BASE",
                flags = SpeedFlags(inlineScreen = true),
                sink = sink,
            ).run("Verify give-up block and stop telemetry")
        }
        assertTrue("$end", end is TaskState.Failed && end.kind == com.wangzan101.mika.core.agent.FailKind.Guard)
        sink.event(tally.event(System.currentTimeMillis()))

        val formatted = UsageLogFormatter.formatEvents(sink.list, runId = "20261124-210004-r3stop", versionCode = 62, versionName = "0.1.57")
        val rows = formatted.trimEnd().lines().map { Json.parseToJsonElement(it).jsonObject }
        val replyRows = rows.filter { it["type"]?.jsonPrimitive?.content == "model_reply" }
        assertEquals(8, replyRows.size)

        val step1Hash = replyRows[0]["screenHash"]?.jsonPrimitive?.content
        assertNotNull(step1Hash)

        // Step 5: blocked end_not_done
        val giveUpBlockedRow = replyRows[4]
        assertEquals(step1Hash, giveUpBlockedRow["screenHash"]?.jsonPrimitive?.content)
        assertEquals(false, giveUpBlockedRow["screenChanged"]?.jsonPrimitive?.boolean)
        assertEquals("stuck_screen_giveup_blocked", giveUpBlockedRow["breakerTrigger"]?.jsonPrimitive?.content)

        // Step 8: guard stop row
        val stopRow = replyRows[7]
        assertEquals(step1Hash, stopRow["screenHash"]?.jsonPrimitive?.content)
        assertEquals(false, stopRow["screenChanged"]?.jsonPrimitive?.boolean)
        assertEquals("stuck_screen_3x_stop", stopRow["breakerTrigger"]?.jsonPrimitive?.content)
        assertTrue((stopRow["toolSequence"]?.jsonArray?.size ?: 0) > 0)

        val endedRow = rows.first { it["type"]?.jsonPrimitive?.content == "task_ended" }
        assertEquals("failed", endedRow["outcome"]?.jsonPrimitive?.content)
        assertEquals("guard", endedRow["kind"]?.jsonPrimitive?.content)
    }

    @Test
    fun usageLogPreservesSingleStepVerdictStopBreakerTriggerAfterRecordStepOutcome() = runTest {
        val screen = object : ScreenPort by FakeScreen() {
            private val snap = ScreenSnapshot(
                "s-fail",
                "com.shop.app",
                listOf(UiElement("@e1", "button", "Item", 200, 150, listOf(0, 0, 900, 200), null, true, false, false)),
                1080,
                2400,
                windows = listOf(UiWindowTree(1, "application", 1, "App", "com.shop.app", true, true, listOf(UiTreeNode("TextView", listOf(0, 0, 900, 200), text = "Static")))),
            )
            override suspend fun snapshot() = snap
            override suspend fun peek() = snap
            override suspend fun tapXY(x: Int, y: Int) = ActionResult.Failed("err")
            override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = ActionResult.Failed("err")
            override suspend fun back() = ActionResult.Failed("err")
            override suspend fun pressKey(keyCode: Int) = ActionResult.Failed("err")
            override suspend fun openApp(packageName: String, cold: Boolean) = ActionResult.Failed("err")
        }
        val failingCycle = listOf(
            call("phone_tap", """{"x":200,"y":150}"""),
            call("phone_swipe", """{"startX":200,"startY":800,"endX":200,"endY":300}"""),
            call("phone_back"),
            call("phone_press_key", """{"keyCode":4}"""),
            call("phone_launch_app", """{"packageName":"com.shop.app"}"""),
        )
        val model = ScriptedModel(
            name = "gemini-4-hydrogen",
            provider = "vertex",
            script = { step ->
                if (step == 1) ModelReply(listOf(call("phone_observe")), StopReason.ToolUse, Usage(1000, 20, 0))
                else ModelReply(listOf(failingCycle[(step - 2) % failingCycle.size]), StopReason.ToolUse, Usage(1000 + step * 50, 20, 500))
            },
        )
        val sink = RecordingEvents()
        val tally = RunUsageTally()
        val metered = model.metered(UsageSource("p-main", "vertex", model.name, UsagePurpose.Main), UsageSink { _, _ -> })
        val end = withContext(tally) {
            AgentLoop(
                model = metered,
                tools = toolDispatcher(screen, visionEnabled = false, inlineScreen = true),
                basePrompt = "BASE",
                flags = SpeedFlags(inlineScreen = true),
                sink = sink,
            ).run("Verify single-step verdict Stop breakerTrigger")
        }
        assertTrue("$end", end is TaskState.Failed && end.kind == com.wangzan101.mika.core.agent.FailKind.Guard)
        assertTrue(end.toString().contains("too_many_failures"))
        sink.event(tally.event(System.currentTimeMillis()))

        val formatted = UsageLogFormatter.formatEvents(sink.list, runId = "20261124-210005-r3verdict", versionCode = 62, versionName = "0.1.57")
        val rows = formatted.trimEnd().lines().map { Json.parseToJsonElement(it).jsonObject }
        val replyRows = rows.filter { it["type"]?.jsonPrimitive?.content == "model_reply" }
        val lastReply = replyRows.last()
        assertEquals("too_many_failures", lastReply["breakerTrigger"]?.jsonPrimitive?.content)
        assertNotNull(lastReply["screenHash"]?.jsonPrimitive?.content)
        assertEquals(false, lastReply["screenChanged"]?.jsonPrimitive?.boolean)
    }
}
