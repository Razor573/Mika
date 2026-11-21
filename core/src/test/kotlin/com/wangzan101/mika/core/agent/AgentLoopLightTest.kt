package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.AnthropicModel
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.BearerToken
import com.wangzan101.mika.core.model.CallOptions
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.GeminiModel
import com.wangzan101.mika.core.model.LightPrompt
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.OpenAiCompatModel
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.ResponsesModel
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.model.VertexModel
import com.wangzan101.mika.core.record.RunEvent
import com.wangzan101.mika.core.record.RunMetrics
import com.wangzan101.mika.core.record.RunSink
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.uistate.ActionResult
import com.wangzan101.mika.core.uistate.ScreenSnapshot
import com.wangzan101.mika.core.uistate.UiElement
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

private class Named(override val name: String, private val inner: FakeModel) : ChatModel by inner {
    val requests get() = inner.requests
    val callOptions get() = inner.callOptions
    val systems get() = inner.systems
    val offeredTools get() = inner.offeredTools
    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
        inner.complete(system, messages, tools)
    override suspend fun complete(
        system: String,
        messages: List<Message>,
        tools: List<ToolSpec>,
        options: CallOptions,
    ): ModelReply = inner.complete(system, messages, tools, options)
}

private class Events : RunSink {
    val list = mutableListOf<RunEvent>()
    override fun event(e: RunEvent) { list += e }
}

private var seq = 0
private fun call(name: String, args: String = "{}") = Part.ToolCall("l${++seq}", name, args)
private fun tools(vararg calls: Part.ToolCall) = ModelReply(calls.toList(), StopReason.ToolUse, null)
private fun toolsWithUsage(usage: Usage, vararg calls: Part.ToolCall) = ModelReply(calls.toList(), StopReason.ToolUse, usage)
private fun finish(text: String = "done") = com.wangzan101.mika.core.testing.answerReply(text)

/** 屏上有一个操作按钮;每次读取另有新序号,路由测试不会被无新数据收尾打断。 */
private class SendScreen(private val label: String = "发送", private val inner: FakeScreen = FakeScreen()) : com.wangzan101.mika.core.uistate.ScreenPort by inner {
    private var reads = 0
    override suspend fun snapshot() = ScreenSnapshot("s1", "com.x",
        listOf(
            UiElement("@e1", "button", label, 500, 500, listOf(400, 450, 600, 550), null, true, false, false),
            UiElement("@e2", "text", "读取 ${++reads}", 500, 700, listOf(400, 650, 600, 750), null, false, false, false),
        ), 1080, 2400)
}

/** Safe multi-element screen for tap/scroll/swipe/type/check steps in thesame app. */
private class SafeActionScreen(private val inner: FakeScreen = FakeScreen()) : com.wangzan101.mika.core.uistate.ScreenPort by inner {
    private var tick = 0
    override suspend fun snapshot(): ScreenSnapshot {
        val n = ++tick
        return ScreenSnapshot(
            snapshotId = "s1",
            packageName = "com.notes.app",
            elements = listOf(
                UiElement("@e1", "button", "详情 $n", 300, 300, listOf(200, 250, 400, 350), null, clickable = true, editable = false, scrollable = false),
                UiElement("@e2", "button", "下一项 $n", 300, 500, listOf(200, 450, 400, 550), null, clickable = true, editable = false, scrollable = false),
                UiElement("@e3", "textfield", "笔记内容 $n", 500, 650, listOf(100, 600, 900, 700), null, clickable = true, editable = true, scrollable = false, focused = true),
                UiElement("@e4", "list", "列表 $n", 500, 800, listOf(0, 720, 1000, 950), null, clickable = false, editable = false, scrollable = true),
                UiElement("@e5", "button", "确认支付", 800, 900, listOf(700, 860, 900, 940), null, clickable = true, editable = false, scrollable = false),
            ),
            width = 1080,
            height = 2400,
        )
    }

    override suspend fun peek(): ScreenSnapshot = snapshot()
    override suspend fun tapRef(ref: String, snapshotId: String): ActionResult = ActionResult.Ok(changed = true)
    override suspend fun tapXY(x: Int, y: Int): ActionResult = ActionResult.Ok(changed = true)
    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean): ActionResult = ActionResult.Ok(changed = true)
    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): ActionResult = ActionResult.Ok(changed = true)
    override suspend fun typeText(ref: String?, snapshotId: String?, text: String): ActionResult = ActionResult.Ok(changed = true)
}

@OptIn(ExperimentalCoroutinesApi::class)
class AgentLoopLightTest {
    private val on = SpeedFlags(lightSteps = true)

    private fun loop(main: ChatModel, light: ChatModel, sink: RunSink = RunSink.NONE, screen: com.wangzan101.mika.core.uistate.ScreenPort = FakeScreen()) =
        AgentLoop(main, toolDispatcher(screen), "BASE", sink = sink, flags = on, router = ModelRouter(main, light))

    @Test fun cleanStepsGoToLightAndAreRecorded() = runTest {
        // 第 1 步主模型;第 2 步前台 App 只见过一次,主模型;第 3 步起两步同 App、无错 → 轻量
        val main = Named("main", FakeModel(tools(call("phone_observe")), tools(call("phone_observe")), finish()))
        val light = Named("light", FakeModel(tools(call("phone_observe")), finish(), finish()))
        val sink = Events()
        val r = loop(main, light, sink).run("t")
        assertTrue(r is TaskState.Succeeded)
        // 第 4 步轻量模型不带工具回复,重试一次提醒后仍不带工具,改问主模型
        assertEquals(3, light.requests.size)
        assertEquals(3, main.requests.size)
        val replies = sink.list.filterIsInstance<RunEvent.ModelReply>()
        assertEquals(listOf("main", "main", "light", "light_rejected", "main"), replies.map { it.role })
        assertEquals(listOf("main", "main", "light", "light", "main"), replies.map { it.model })
    }

    // 不带调用的回复先用一行提醒在轻量模型上重试一次;仍不带工具才改问主模型
    @Test fun lightAnswerIsAskedAgainOfMain() = runTest {
        val main = Named("main", FakeModel(tools(call("phone_observe")), tools(call("phone_observe")), finish()))
        val light = Named(
            "light",
            FakeModel(
                com.wangzan101.mika.core.testing.answerReply("LIGHT_ANSWER"),
                com.wangzan101.mika.core.testing.answerReply("LIGHT_ANSWER_RETRY"),
            ),
        )
        val sink = Events()
        val r = loop(main, light, sink).run("t")
        assertTrue(r is TaskState.Succeeded)
        assertEquals(2, light.requests.size)
        assertEquals(3, main.requests.size)
        assertTrue(sink.list.any { it is RunEvent.State && it.name == "light_retry" && it.detail == "no_tool" })
        assertTrue(sink.list.any { it is RunEvent.State && it.name == "light_to_main" && it.detail == "no_tool" })
        val rejected = sink.list.filterIsInstance<RunEvent.ModelReply>().single { it.role == "light_rejected" }
        assertEquals("no_tool", rejected.reason)
        assertEquals("no_tool_after_retry", rejected.validatorReason)
        assertEquals("None", rejected.lightTool)
        assertEquals(true, rejected.lightRetried)
        assertEquals(listOf("main", "main", "light_rejected", "main"), sink.list.filterIsInstance<RunEvent.ModelReply>().map { it.role })
        // 改派后主模型看到的历史里没有那条没执行的回复,也没有轻量重试提醒
        val asked = main.requests.last()
        assertTrue(asked.flatMap { it.parts }.filterIsInstance<Part.Text>().none { "LIGHT_ANSWER" in it.text })
        assertEquals(asked.size, light.requests.first().size)
        assertEquals(asked.size + 1, light.requests.last().size)
    }

    @Test fun lightNoToolAnswerRecoversOnSingleLineRetryWithoutCallingMain() = runTest {
        val main = Named("main", FakeModel(tools(call("phone_observe")), tools(call("phone_observe")), finish("全部完成")))
        val light = Named(
            "light",
            FakeModel(
                // Step 3 attempt 1: no-tool prose -> triggers 1 retry with RETRY_REMINDER
                com.wangzan101.mika.core.testing.answerReply("I think I should tap the button now"),
                // Step 3 attempt 2 (retry): returns a valid tool call!
                toolsWithUsage(Usage(300, 28, 200), call("phone_tap", """{"ref":"@e1","snapshotId":"s1"}""")),
                // Step 4: finish -> retry -> escalate to main for final answer
                finish(),
                finish(),
            ),
        )
        val sink = Events()
        val r = loop(main, light, sink, SafeActionScreen()).run("点击详情")
        assertTrue(r is TaskState.Succeeded)
        // Step 3 succeeded on Light after retry!
        assertTrue(sink.list.any { it is RunEvent.State && it.name == "light_retry" && it.detail == "no_tool" })
        val step3Reply = sink.list.filterIsInstance<RunEvent.ModelReply>().first { it.step == 3 }
        assertEquals("light", step3Reply.role)
        assertEquals("phone_tap", step3Reply.tool)
        // Verify the retry request carried the one-line reminder
        val retryMessages = light.requests[1]
        val reminderText = retryMessages.last().parts.filterIsInstance<Part.Text>().first().text
        assertEquals(LightPrompt.RETRY_REMINDER, reminderText)
    }

    @Test fun lightSimpleTapStepProducesToolCallUnder200TokensWithForcedToolOptionsAndNoProse() = runTest {
        val main = Named("main", FakeModel(tools(call("phone_observe")), tools(call("phone_observe")), finish()))
        val light = Named(
            "light",
            FakeModel(
                toolsWithUsage(Usage(400, 24, 300), call("phone_tap", """{"ref":"@e1","snapshotId":"s1"}""")),
                finish(),
                finish(),
            ),
        )
        val sink = Events()
        val r = loop(main, light, sink, SafeActionScreen()).run("点一下详情")
        assertTrue(r is TaskState.Succeeded)
        assertEquals(CallOptions.LIGHT_TOOL_ONLY, light.callOptions.first())
        assertEquals(true, light.callOptions.first().forceToolCall)
        assertEquals(200, light.callOptions.first().maxOutputTokens)
        assertTrue(light.systems.first().contains("no prose, no explanation, no summary, no thinking out loud", ignoreCase = true))
        val step3 = sink.list.filterIsInstance<RunEvent.ModelReply>().first { it.step == 3 }
        assertEquals("light", step3.role)
        assertEquals("phone_tap", step3.tool)
        assertEquals("", step3.text)
        assertTrue((step3.outputTokens ?: 0) < 200)
    }

    @Test fun lightCheckAndActionStepsAcceptDescribeObserveHistoryReadAndPermissiveVariants() = runTest {
        val main = Named(
            "main",
            FakeModel(
                tools(call("phone_observe")),
                tools(call("phone_observe")),
                finish("检查完毕"),
            ),
        )
        // Step 3: phone_scroll; Step 4: phone_observe; Step 5: phone_tap on @e2 (even if Main might have picked @e1, and with outputTokens = 260 > 200)
        val light = Named(
            "light",
            FakeModel(
                toolsWithUsage(Usage(450, 22, 320), call("phone_scroll", """{"direction":"down"}""")),
                toolsWithUsage(Usage(470, 18, 350), call("phone_observe")),
                toolsWithUsage(Usage(500, 260, 380), call("phone_tap", """{"ref":"@e2","snapshotId":"s1"}""")),
                finish(),
                finish(),
            ),
        )
        val sink = Events()
        val r = loop(main, light, sink, SafeActionScreen()).run("滚动并查看下一项")
        assertTrue(r is TaskState.Succeeded)
        val lightReplies = sink.list.filterIsInstance<RunEvent.ModelReply>().filter { it.role == "light" }
        assertEquals(listOf("phone_scroll", "phone_observe", "phone_tap"), lightReplies.map { it.tool })
    }

    @Test fun lightRejectsMissingTargetAndInvalidToolWithTelemetryFields() = runTest {
        val main = Named(
            "main",
            FakeModel(
                tools(call("phone_observe")),
                tools(call("phone_observe")),
                tools(call("phone_observe")),
                tools(call("phone_observe")),
                tools(call("phone_observe")),
                finish("done"),
            ),
        )
        val light = Named(
            "light",
            FakeModel(
                // Step 3: nonexistent element @e99 -> rejected with missing_target
                toolsWithUsage(Usage(400, 31, 200), call("phone_tap", """{"ref":"@e99","snapshotId":"s1"}""")),
                // Step 6 (after 2 trouble steps on Main): unoffered/ privileged tool ask_user -> rejected with unsafe_tool
                toolsWithUsage(Usage(500, 29, 250), call("ask_user", """{"question":"?"}""")),
            ),
        )
        val sink = Events()
        val r = loop(main, light, sink, SafeActionScreen()).run("测试拒绝")
        assertTrue(r is TaskState.Succeeded)
        val rejected = sink.list.filterIsInstance<RunEvent.ModelReply>().filter { it.role == "light_rejected" }
        assertEquals(2, rejected.size)
        assertEquals("missing_target", rejected[0].reason)
        assertEquals("target_not_found:@e99", rejected[0].validatorReason)
        assertEquals("phone_tap", rejected[0].lightTool)
        assertEquals(31, rejected[0].lightOutputTokens)
        assertEquals(false, rejected[0].lightRetried)

        assertEquals("unsafe_tool", rejected[1].reason)
        assertEquals("main_only_tool:ask_user", rejected[1].validatorReason)
        assertEquals("ask_user", rejected[1].lightTool)
        assertEquals(29, rejected[1].lightOutputTokens)
        assertEquals(false, rejected[1].lightRetried)
    }

    @Test fun lightIsNeverRoutedOnUpfrontMainSteps() = runTest {
        // When history has an upfront Main marker (e.g. final summary / user interjection / privileged tool follow-up),
        // Light receives 0 requests.
        val main = Named(
            "main",
            FakeModel(
                tools(call("phone_observe")),
                tools(call("phone_observe")),
                tools(call("end_not_done", """{"message":"需要主模型决策"}""")),
            ),
        )
        val light = Named("light", FakeModel(tools(call("phone_observe"))))
        val dispatcher = toolDispatcher(SafeActionScreen())
        val router = ModelRouter(main, light)
        val sink = Events()
        val caller = ModelCaller(main, dispatcher, router, on, RetryPolicy.NONE, sink::event, { 1000L })
        val track = StepTrack()
        track.after(com.wangzan101.mika.core.tool.Verb.Observe, ok = true, guard = false, changed = null, recovery = false, app = "com.notes.app")
        track.after(com.wangzan101.mika.core.tool.Verb.Observe, ok = true, guard = false, changed = null, recovery = false, app = "com.notes.app")

        val historyWithSummary = listOf(
            Message(Role.User, listOf(Part.Text("请执行并给出 final summary")), Author.User),
            Message(Role.Assistant, listOf(call("ask_user", """{"question":"确认?"}""")), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("l1", """{"ok":true}""", isError = false)), Author.Mika),
        )
        val (_, usedModel) = caller.routed(historyWithSummary, dispatcher.offeredNow(), track, 3, {})
        assertEquals("main", usedModel.name)
        assertEquals(0, light.requests.size)
    }

    @Test fun providerForcedToolCallingPayloadsAcrossGeminiVertexOpenAiResponsesAndClaude() = runTest {
        val spec = listOf(ToolSpec("phone_tap", "Tap an element", """{"type":"object","properties":{"ref":{"type":"string"}}}"""))
        val userMsg = listOf(Message(Role.User, listOf(Part.Text("tap")), Author.User))
        val http = OkHttpClient()

        // 1. GeminiModel
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"phone_tap","args":{"ref":"@e1"}}}]},"finishReason":"STOP"}]}"""),
            )
            val gemini = GeminiModel(
                baseUrl = server.url("/").toString().trimEnd('/'),
                apiKey = "gk",
                model = "gemini-2.5-flash",
                capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 1_000_000, reasoning = false),
                http = http,
            )
            gemini.complete("BASE", userMsg, spec, CallOptions.LIGHT_TOOL_ONLY)
            val bodyStr = server.takeRequest().body.readUtf8()
            val body = Json.parseToJsonElement(bodyStr).jsonObject
            assertEquals("ANY", body["tool_config"]!!.jsonObject["function_calling_config"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
            assertEquals(200, body["generationConfig"]!!.jsonObject["maxOutputTokens"]!!.jsonPrimitive.content.toInt())
            assertFalse(bodyStr.contains("tool_choice"))
        }

        // 2. VertexModel
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"phone_tap","args":{"ref":"@e1"}}}]},"finishReason":"STOP"}]}"""),
            )
            val vertex = VertexModel(
                projectId = "proj-1",
                location = "global",
                token = BearerToken.fixed("vt"),
                model = "gemini-2.5-flash",
                capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 1_000_000, reasoning = false),
                http = http,
                baseUrl = server.url("/").toString().trimEnd('/'),
            )
            vertex.complete("BASE", userMsg, spec, CallOptions.LIGHT_TOOL_ONLY)
            val bodyStr = server.takeRequest().body.readUtf8()
            val body = Json.parseToJsonElement(bodyStr).jsonObject
            assertEquals("ANY", body["tool_config"]!!.jsonObject["function_calling_config"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
            assertEquals(200, body["generationConfig"]!!.jsonObject["maxOutputTokens"]!!.jsonPrimitive.content.toInt())
            assertFalse(bodyStr.contains("tool_choice"))
        }

        // 3. OpenAiCompatModel (plus fallback when server rejects tool_choice with 400)
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json")
                    .setBody("""{"error":{"message":"tool_choice is unsupported by this model"}}"""),
            )
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"choices":[{"finish_reason":"tool_calls","message":{"content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"phone_tap","arguments":"{\"ref\":\"@e1\"}"}}]}}]}"""),
            )
            val openAi = OpenAiCompatModel(
                baseUrl = server.url("/v1").toString().trimEnd('/'),
                apiKey = "sk-test",
                model = "gpt-4o-mini",
                capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 128_000, reasoning = false),
                http = http,
            )
            val reply = openAi.complete("BASE", userMsg, spec, CallOptions.LIGHT_TOOL_ONLY)
            assertEquals("phone_tap", reply.parts.filterIsInstance<Part.ToolCall>().single().name)
            val firstReqStr = server.takeRequest().body.readUtf8()
            val firstReq = Json.parseToJsonElement(firstReqStr).jsonObject
            assertEquals("required", firstReq["tool_choice"]!!.jsonPrimitive.content)
            assertEquals(200, firstReq["max_tokens"]!!.jsonPrimitive.content.toInt())
            assertFalse(firstReqStr.contains("tool_config"))
            assertFalse(firstReqStr.contains("function_calling_config"))

            val fallbackReq = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertFalse(fallbackReq.containsKey("tool_choice"))
        }

        // 4. ResponsesModel
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody(
                        "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"phone_tap\",\"arguments\":\"{\\\"ref\\\":\\\"@e1\\\"}\"}}\n\n" +
                            "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"usage\":{\"input_tokens\":50,\"output_tokens\":15}}}\n\n",
                    ),
            )
            val responses = ResponsesModel(
                baseUrl = server.url("/v1").toString().trimEnd('/'),
                token = BearerToken.fixed("sk-resp"),
                model = "gpt-4.1-mini",
                capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 128_000, reasoning = false),
                http = http,
            )
            responses.complete("BASE", userMsg, spec, CallOptions.LIGHT_TOOL_ONLY)
            val bodyStr = server.takeRequest().body.readUtf8()
            val body = Json.parseToJsonElement(bodyStr).jsonObject
            assertEquals("required", body["tool_choice"]!!.jsonPrimitive.content)
            assertEquals(200, body["max_output_tokens"]!!.jsonPrimitive.content.toInt())
            assertFalse(bodyStr.contains("tool_config"))
            assertFalse(bodyStr.contains("function_calling_config"))
        }

        // 5. AnthropicModel
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"content":[{"type":"tool_use","id":"t1","name":"phone_tap","input":{"ref":"@e1"}}],"stop_reason":"tool_use"}"""),
            )
            val claude = AnthropicModel(
                baseUrl = server.url("/").toString().trimEnd('/'),
                apiKey = "sk-ant",
                model = "claude-haiku-4-5",
                capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 200_000, reasoning = true),
                http = http,
                reasoningEffort = "medium",
            )
            claude.complete("BASE", userMsg, spec, CallOptions.LIGHT_TOOL_ONLY)
            val bodyStr = server.takeRequest().body.readUtf8()
            val body = Json.parseToJsonElement(bodyStr).jsonObject
            assertEquals("any", body["tool_choice"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals(200, body["max_tokens"]!!.jsonPrimitive.content.toInt())
            assertFalse(body.containsKey("thinking"))
            assertFalse(bodyStr.contains("tool_config"))
            assertFalse(bodyStr.contains("function_calling_config"))
        }
    }

    @Test fun fortyTwoStepTaskWithLightForcedToolCallingProducesUsageReceipt() = runTest {
        // 42-step end-to-end task:
        // Step 1-2: Main (initial observe in app)
        // Steps 3..41: Light executes tap/scroll/swipe/type/observe steps (<200 output tokens each, 0 prose),
        //   including 1 no-tool response that recovers on the 1-line retry (Step 15),
        //   and 1 rejection on an irreversible payment button (@e5 "确认支付") at Step 28 that escalates to Main (followed by 2 trouble steps on Main at Steps 29-30),
        //   then Light resumes for Steps 31..41,
        // Step 42: Main produces the final summary answer.
        val mainReplies = mutableListOf<ModelReply>()
        val lightReplies = mutableListOf<ModelReply>()

        // Step 1 & Step 2 on Main
        mainReplies += toolsWithUsage(Usage(1850, 95, 1200), call("phone_observe"))
        mainReplies += toolsWithUsage(Usage(2100, 88, 1600), call("phone_tap", """{"ref":"@e1","snapshotId":"s1"}"""))

        for (step in 3..41) {
            when (step) {
                15 -> {
                    // Attempt 1 returns no tool call -> retried once with 1-line reminder -> Attempt 2 returns valid tool call!
                    lightReplies += ModelReply(listOf(Part.Text("checking next item")), StopReason.EndTurn, Usage(2400, 12, 2100))
                    lightReplies += toolsWithUsage(Usage(2420, 26, 2100), call("phone_observe"))
                }
                28 -> {
                    // Light attempts to tap irreversible button @e5 ("确认支付") -> rejected by LightCallValidator -> Main handles step 28
                    lightReplies += toolsWithUsage(Usage(2900, 32, 2500), call("phone_tap", """{"ref":"@e5","snapshotId":"s1"}"""))
                    mainReplies += toolsWithUsage(Usage(2900, 110, 2400), call("phone_observe"))
                }
                29, 30 -> {
                    // 2 trouble steps after rejection run on Main
                    mainReplies += toolsWithUsage(Usage(2950 + step * 10, 105, 2500), call("phone_tap", """{"ref":"@e2","snapshotId":"s1"}"""))
                }
                else -> {
                    val toolCall = when (step % 5) {
                        0 -> call("phone_tap", """{"ref":"@e1","snapshotId":"s1"}""")
                        1 -> call("phone_scroll", """{"direction":"down"}""")
                        2 -> call("phone_tap", """{"ref":"@e2","snapshotId":"s1"}""")
                        3 -> call("phone_swipe", """{"startX":500,"startY":800,"endX":500,"endY":300}""")
                        else -> call("phone_type_text", """{"ref":"@e3","snapshotId":"s1","text":"item-$step"}""")
                    }
                    lightReplies += toolsWithUsage(Usage(2100 + step * 25, 22 + (step % 19), 1800 + step * 20), toolCall)
                }
            }
        }

        // Step 42: Light is asked or routed to Main for final summary. Let's have step 42 finish on Main after Light no-tool or upfront Main:
        // To avoid an extra rejection on step 42, let's have step 41 switch app or trigger final summary on Main, OR let step 41 be phone_launch_app so step 42 is on Main!
        lightReplies[lightReplies.lastIndex] = toolsWithUsage(Usage(3120, 29, 2800), call("phone_launch_app", """{"package":"com.notes.app"}"""))
        mainReplies += ModelReply(listOf(Part.Text("已完成全部 42 步笔记整理与核对。")), StopReason.EndTurn, Usage(3400, 180, 2900))

        val main = Named("gemini-3.1-pro", FakeModel(*mainReplies.toTypedArray()))
        val light = Named("gemini-2.5-flash-lite", FakeModel(*lightReplies.toTypedArray()))
        val sink = Events()
        val state = loop(main, light, sink, SafeActionScreen()).run("整理 42 条笔记项目并汇总")
        assertTrue("Expected Succeeded but got $state", state is TaskState.Succeeded)

        val metrics = RunMetrics.of(sink.list, "20261119-42steps-light")!!
        assertEquals(42, metrics.steps)
        assertEquals(37, metrics.lightCalls)
        assertEquals(1, metrics.lightRejectedCalls)
        assertEquals(1, metrics.lightRetriedCalls)
        assertTrue("Expected rejection rate < 5%, got ${metrics.lightRejectionRate}", metrics.lightRejectionRate < 0.05)

        val usageFields = mapOf(
            "task_started" to setOf("model"),
            "model_reply" to setOf(
                "step", "model", "role", "tool", "inputTokens", "cachedTokens",
                "outputTokens", "repliedAtMs", "latencyMs", "retryWaitMs",
                "reason", "decisionScore", "validatorReason", "lightTool", "lightOutputTokens", "lightRetried",
            ),
            "compressed" to setOf("foldedSteps"),
            "model_calls" to setOf("calls", "httpCalls", "unreported", "httpUnreported", "inputTokens", "cachedTokens", "outputTokens"),
            "task_ended" to setOf("outcome", "kind"),
        )
        val usageJsonl = buildString {
            for (e in sink.list) {
                val obj = RunEvent.json.parseToJsonElement(RunEvent.encode(e)).jsonObject
                val type = (obj["type"] as? JsonPrimitive)?.content ?: continue
                val allowed = usageFields[type] ?: continue
                val filtered = obj.filter { (k, v) -> v is JsonPrimitive && (k == "type" || k == "atMs" || k in allowed) }
                appendLine(JsonObject(filtered).toString())
            }
        }
        val artifactFile = File("""C:\Users\User\.gemini\antigravity\brain\632442b9-ffe2-4991-a500-599bec4eeb3d\mika-usage-42-steps.jsonl""")
        artifactFile.parentFile?.mkdirs()
        artifactFile.writeText(usageJsonl)
        assertTrue(artifactFile.isFile && artifactFile.length() > 0)
    }

    @Test fun lightCoordinateAndLocatedTapsGoToMain() = runTest {
        for (args in listOf("""{"x":500,"y":500}""", """{"ref":"@v1"}""", """{"x":900,"y":900}""")) {
            val main = Named("main", FakeModel(tools(call("phone_observe")), tools(call("phone_observe")), tools(call("end_not_done", """{"message":"不点"}"""))))
            val light = Named("light", FakeModel(tools(call("phone_tap", args))))
            val sink = Events()
            loop(main, light, sink, SendScreen()).run("t")
            assertTrue(args, sink.list.any { it is RunEvent.State && it.name == "light_to_main" && it.detail == "phone_tap" })
            assertTrue(args, sink.list.filterIsInstance<RunEvent.ToolResult>().none { it.tool == "phone_tap" })
        }
    }

    @Test fun confirmationBlockedStepForcesMain() = runTest {
        // 第 2 步主模型点「提交订单」被确认关卡拦下:之后两步主模型,第 5 步才轮到轻量模型
        val main = Named("main", FakeModel(tools(call("phone_observe")), tools(call("phone_tap", """{"ref":"@e1","snapshotId":"s1"}""")),
            tools(call("phone_observe")), tools(call("phone_observe")), finish()))
        val light = Named("light", FakeModel(tools(call("phone_observe")), finish(), finish()))
        val sink = Events()
        loop(main, light, sink, SendScreen("提交订单")).run("t")
        assertEquals(listOf("main", "main", "main", "main", "light", "light_rejected", "main"),
            sink.list.filterIsInstance<RunEvent.ModelReply>().map { it.role })
        assertTrue((sink.list.filterIsInstance<RunEvent.ToolResult>().first { it.tool == "phone_tap" }).resultJson.contains("confirmation_required"))
    }

    @Test fun permanentLightErrorsTurnRoutingOffIncludingTheDailyBudget() = runTest {
        for (error in listOf(
            com.wangzan101.mika.core.model.ModelError.Auth(),
            com.wangzan101.mika.core.model.ModelError.DailyBudgetExceeded("轻量服务商", 10_000, 10_000),
        )) {
            val main = Named("main", FakeModel(*Array(6) { tools(call("phone_observe")) }, finish()))
            val light = Named("light", FakeModel(error))
            val sink = Events()
            val r = loop(main, light, sink, SendScreen()).run("t")
            assertTrue("$error: $r", r is TaskState.Succeeded)
            assertEquals(1, light.requests.size)
            assertEquals(1, sink.list.filterIsInstance<RunEvent.ModelReply>().count { it.role == "light_rejected" })
        }
    }

    @Test fun taskBudgetExhaustionDoesNotFallBackFromLightToMain() = runTest {
        val main = Named("main", FakeModel(tools(call("phone_observe")), tools(call("phone_observe")), finish()))
        val light = Named("light", FakeModel(com.wangzan101.mika.core.model.ModelError.TaskBudgetExceeded(5, 5)))
        val end = loop(main, light).run("t")
        assertTrue("$end", end is TaskState.Failed && end.kind == FailKind.TokenLimit)
        assertEquals(1, light.requests.size)
        assertEquals("不能用主模型继续消耗同一任务预算", 2, main.requests.size)
    }

    @Test fun otherModelsThinkingAndOpaqueAreNotSentAcross() = runTest {
        val mine = Part.ToolCall("m1", "phone_observe", "{}", opaque = "sigM")
        val theirs = Part.ToolCall("x1", "phone_observe", "{}", opaque = "sigL")
        val main = Named("main", FakeModel(ModelReply(listOf(Part.Thinking("tm"), mine), StopReason.ToolUse, null),
            tools(call("phone_observe")), tools(call("phone_observe")), tools(call("phone_observe")), finish()))
        val light = Named("light", FakeModel(ModelReply(listOf(Part.Thinking("tl"), theirs), StopReason.ToolUse, null), finish(), finish()))
        loop(main, light).run("t")
        // 第 3 步轻量模型;第 4 步它要交结果,改问主模型:轻量模型那条去掉思考和私有数据,主模型自己的原样
        val toMain = main.requests[2].filter { it.role == com.wangzan101.mika.core.model.Role.Assistant }.flatMap { it.parts }
        assertTrue(mine in toMain && Part.Thinking("tm") in toMain)
        assertTrue(theirs.copy(opaque = null) in toMain && Part.Thinking("tl") !in toMain)
        val toLight = light.requests.first().filter { it.role == com.wangzan101.mika.core.model.Role.Assistant }.flatMap { it.parts }
        assertTrue(mine.copy(opaque = null) in toLight && toLight.none { it is Part.Thinking })
    }

    @Test fun lightIrreversibleTapIsAskedAgainOfMain() = runTest {
        val tapSend = """{"ref":"@e1","snapshotId":"s1"}"""
        val main = Named("main", FakeModel(tools(call("phone_observe")), tools(call("phone_observe")), tools(call("end_not_done", """{"message":"不发"}"""))))
        val light = Named("light", FakeModel(tools(call("phone_tap", tapSend))))
        val sink = Events()
        loop(main, light, sink, SendScreen()).run("t")
        assertEquals(1, light.requests.size)
        assertTrue(sink.list.any { it is RunEvent.State && it.name == "light_to_main" && it.detail == "phone_tap" })
        assertTrue(sink.list.filterIsInstance<RunEvent.ToolResult>().none { it.tool == "phone_tap" })
    }

    @Test fun screenshotsNeverGoToTextOnlyLight() = runTest {
        val main = Named("main", FakeModel(tools(call("phone_observe")), tools(call("phone_observe")), tools(call("phone_observe")), finish()))
        val light = Named("light", FakeModel())
        // 主模型看图:observe 附截图,轻量模型不看图就不接
        val seeing = object : ChatModel by main {
            override val capabilities = main.capabilities.copy(vision = true)
        }
        AgentLoop(seeing, toolDispatcher(FakeScreen(), visionEnabled = true), "BASE", flags = on,
            router = ModelRouter(seeing, light)).run("t")
        assertEquals(0, light.requests.size)
    }

    @Test fun lightErrorFallsBackToMain() = runTest {
        val main = Named("main", FakeModel(tools(call("phone_observe")), tools(call("phone_observe")), finish()))
        val light = Named("light", FakeModel(com.wangzan101.mika.core.model.ModelError.Network()))
        val r = loop(main, light).run("t")
        assertTrue(r is TaskState.Succeeded)
        // 轻量模型出错不重试,这一步直接改问主模型
        assertEquals(1, light.requests.size)
        assertEquals(3, main.requests.size)
    }

    @Test fun light503FallsBackImmediately() = runTest {
        val main = Named("main", FakeModel(tools(call("phone_observe")), tools(call("phone_observe")), finish()))
        val light = Named("light", FakeModel(com.wangzan101.mika.core.model.ModelError.Server(503)))
        val r = loop(main, light).run("t")
        assertTrue(r is TaskState.Succeeded)
        // 503 没有重发给轻量模型,这一步由主模型接
        assertEquals(1, light.requests.size)
        assertEquals(3, main.requests.size)
    }

    @Test fun lightOneRetryRunsAtZeroWaitWhenMainHasPreviouslySeen429() = runTest {
        val main = Named("main-ok", FakeModel(ModelError.RateLimited(5_000L), finish()))
        val light = Named(
            "light-ok",
            FakeModel(
                com.wangzan101.mika.core.testing.answerReply("Let me inspect the screen first."),
                tools(call("phone_observe")),
            ),
        )
        val dispatcher = toolDispatcher(SafeActionScreen())
        val router = ModelRouter(main, light)
        val sink = Events()
        val caller = ModelCaller(main, dispatcher, router, on, RetryPolicy(jitter = { 1.0 }), sink::event, { currentTime })
        caller.reset("BASE")

        // First Main call hits 429 and recovers after 5s; no global cooldown carries into later Light calls.
        caller.ask(listOf(Message(Role.User, listOf(Part.Text("Warm up")), Author.User)), emptyList(), step = 1)
        assertEquals(5_000L, currentTime)

        val track = StepTrack()
        track.after(com.wangzan101.mika.core.tool.Verb.Observe, ok = true, guard = false, changed = null, recovery = false, app = "com.notes.app")
        track.after(com.wangzan101.mika.core.tool.Verb.Observe, ok = true, guard = false, changed = null, recovery = false, app = "com.notes.app")

        val specs = listOf(ToolSpec("phone_observe", "Read screen", "{}"))
        val history = listOf(Message(Role.User, listOf(Part.Text("Continue checking")), Author.User))
        val (reply, usedModel) = caller.routed(history, specs, track, 3, {})

        assertEquals("light-ok", usedModel.name)
        assertEquals(2, light.requests.size)
        assertEquals(2, main.requests.size)
        assertEquals(5_000L, currentTime)
        assertEquals("phone_observe", reply.parts.filterIsInstance<Part.ToolCall>().single().name)
        assertTrue(sink.list.any { it is RunEvent.State && it.name == "light_retry" && it.detail == "no_tool" })
    }

    @Test fun light429FallsBackImmediatelyWithoutConsumingTaskWaitBudget() = runTest {
        val main = Named("main", FakeModel(tools(call("phone_observe")), tools(call("phone_observe")), finish()))
        val light = Named("light", FakeModel(ModelError.RateLimited(300_000)))
        val r = loop(main, light).run("t")
        assertTrue(r is TaskState.Succeeded)
        assertEquals(0L, currentTime)
        assertEquals(1, light.requests.size)
        assertEquals(3, main.requests.size)
    }

    @Test fun singleMain429RetriesAtMostThreeTimesPerRequestWithNoGlobalCooldownCarryover() = runTest {
        val main = Named(
            "main-429-cap",
            FakeModel(
                ModelError.RateLimited(null),
                ModelError.RateLimited(null),
                ModelError.RateLimited(null),
                ModelError.RateLimited(null),
                finish("second-request-ok"),
            ),
        )
        val dispatcher = toolDispatcher(SafeActionScreen())
        val sink = Events()
        val caller = ModelCaller(main, dispatcher, null, SpeedFlags.OFF, RetryPolicy(jitter = { 1.0 }), sink::event, { currentTime })
        caller.reset("BASE")

        val req = listOf(Message(Role.User, listOf(Part.Text("Step 1")), Author.User))
        var thrown: Throwable? = null
        try {
            caller.ask(req, emptyList(), step = 1)
        } catch (e: ModelError.RateLimited) {
            thrown = e
        }
        assertTrue(thrown is ModelError.RateLimited)
        assertEquals(4, main.requests.size)
        assertEquals(7_000L, currentTime)

        // Subsequent request starts immediately without any global cooldown wait.
        val second = caller.ask(req, emptyList(), step = 2)
        assertEquals(5, main.requests.size)
        assertEquals(7_000L, currentTime)
        assertEquals("second-request-ok", second.parts.filterIsInstance<Part.Text>().single().text)
    }
}
