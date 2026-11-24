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
}
