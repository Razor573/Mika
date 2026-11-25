package com.wangzan101.mika.core.agent

import com.wangzan101.mika.core.assembly.toolDispatcher
import com.wangzan101.mika.core.model.AnthropicModel
import com.wangzan101.mika.core.model.AntigravityModel
import com.wangzan101.mika.core.model.Author
import com.wangzan101.mika.core.model.BearerToken
import com.wangzan101.mika.core.model.Capabilities
import com.wangzan101.mika.core.model.ChatModel
import com.wangzan101.mika.core.model.GeminiModel
import com.wangzan101.mika.core.model.LightPrompt
import com.wangzan101.mika.core.model.Message
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelErrors
import com.wangzan101.mika.core.model.ModelReply
import com.wangzan101.mika.core.model.OpenAiCompatModel
import com.wangzan101.mika.core.model.Part
import com.wangzan101.mika.core.model.ResponsesModel
import com.wangzan101.mika.core.model.Role
import com.wangzan101.mika.core.model.StopReason
import com.wangzan101.mika.core.model.ToolSpec
import com.wangzan101.mika.core.model.Usage
import com.wangzan101.mika.core.model.VertexModel
import com.wangzan101.mika.core.run.record.RunEvent
import com.wangzan101.mika.core.testing.answerReply
import com.wangzan101.mika.core.tool.FakeScreen
import com.wangzan101.mika.core.tool.Verb
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ModelCallerTimeoutRetryTest {
    private val caps = Capabilities(toolCalls = true, vision = false, contextTokens = 128_000, reasoning = false)
    private val history = listOf(Message(Role.User, listOf(Part.Text("Check status")), Author.User))
    private val observeSpec = listOf(ToolSpec("phone_observe", "Observe current screen", """{"type":"object","properties":{}}"""))

    private fun openAiOkJson(text: String = "ok"): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"choices":[{"message":{"role":"assistant","content":"$text"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":2}}""")

    private fun openAiToolCallJson(name: String, argsJson: String = "{}"): MockResponse = MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody("""{"choices":[{"message":{"role":"assistant","tool_calls":[{"id":"c1","type":"function","function":{"name":"$name","arguments":${kotlinx.serialization.json.JsonPrimitive(argsJson)}}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":12,"completion_tokens":5}}""")

    private fun httpClient(readMs: Long = 450L, connectMs: Long = 450L, callMs: Long = 0L): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(connectMs, TimeUnit.MILLISECONDS)
            .readTimeout(readMs, TimeUnit.MILLISECONDS)
            .writeTimeout(readMs, TimeUnit.MILLISECONDS)
            .callTimeout(callMs, TimeUnit.MILLISECONDS)
            .build()

    private fun caller(
        model: ChatModel,
        policy: RetryPolicy = RetryPolicy(
            maxRetries = 3,
            baseMs = 20L,
            maxBackoffMs = 40L,
            timeoutRetryConnectMs = 150L,
            timeoutRetryMs = 150L,
            jitter = { 0.0 },
        ),
        router: ModelRouter? = null,
        flags: SpeedFlags = SpeedFlags.OFF,
        fallbacks: List<ChatModel> = emptyList(),
        events: MutableList<RunEvent> = mutableListOf(),
    ): ModelCaller = ModelCaller(
        model = model,
        tools = toolDispatcher(FakeScreen()),
        router = router,
        flags = flags,
        retry = policy,
        emit = { events += it },
        now = { System.currentTimeMillis() },
        fallbacks = fallbacks,
    ).also { it.reset("System prompt") }

    @Test fun repeatedTimeoutFailsFastAfterExactlyTwoAttempts() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.enqueue(openAiOkJson("should never reach 3rd attempt"))

            val events = mutableListOf<RunEvent>()
            val model = OpenAiCompatModel(server.url("/v1").toString().trimEnd('/'), "k", "m", caps, httpClient(readMs = 450L))
            val c = caller(model, events = events)

            var thrown: ModelError? = null
            val elapsedMs = measureTimeMillis {
                try {
                    c.ask(history, emptyList(), step = 1)
                    fail("Expected ModelError.Network timeout")
                } catch (e: ModelError) {
                    thrown = e
                }
            }

            val err = thrown as ModelError.Network
            assertTrue(err.isTimeout)
            assertTrue(ModelErrors.isTimeout(err))
            assertEquals(2, server.requestCount)
            assertTrue("Expected fast failure well under 120s, took ${elapsedMs}ms", elapsedMs < 3_000L)

            val retryEvents = events.filterIsInstance<RunEvent.State>().filter { it.name == "model_retry" }
            assertEquals(1, retryEvents.size)
            assertTrue(retryEvents.single().detail.contains("network_timeout #1"))

            val diag = c.lastCallDiagnostics
            assertNotNull(diag)
            assertEquals(2, diag?.attempt)
            assertEquals(1, diag?.retryCount)
            assertEquals(true, diag?.timedOut)
            assertEquals(1, diag?.timeoutRetries)
            assertEquals(150L, diag?.timeoutMs)
            assertEquals("network_timeout", diag?.retryReason)
        }
    }

    @Test fun timeoutRecoversOnSingleShortTimeoutRetry() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.enqueue(openAiOkJson("recovered"))

            val model = OpenAiCompatModel(server.url("/v1").toString().trimEnd('/'), "k", "m", caps, httpClient(readMs = 450L))
            val c = caller(model)
            val reply = c.ask(history, emptyList(), step = 1)

            assertEquals("recovered", reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text })
            assertEquals(2, server.requestCount)
            val diag = c.lastCallDiagnostics
            assertEquals(2, diag?.attempt)
            assertEquals(1, diag?.retryCount)
            assertEquals(true, diag?.timedOut)
            assertEquals(1, diag?.timeoutRetries)
            assertEquals(150L, diag?.timeoutMs)
            assertEquals("network_timeout", diag?.retryReason)
        }
    }

    @Test fun secondAttemptEnforcesShortTimeoutWhenHeadersExceedShortLimitButNotInitialLimit() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            // Attempt 1 times out at 500ms initial readTimeout.
            server.enqueue(MockResponse().setHeadersDelay(800, TimeUnit.MILLISECONDS).setBody("{}"))
            // Attempt 2 delays 280ms: < 500ms initial readTimeout, but > 140ms short-retry readTimeout!
            server.enqueue(MockResponse().setHeadersDelay(280, TimeUnit.MILLISECONDS).setBody("{}"))

            val policy = RetryPolicy(
                maxRetries = 3,
                baseMs = 20L,
                maxBackoffMs = 40L,
                timeoutRetryConnectMs = 140L,
                timeoutRetryMs = 140L,
                jitter = { 0.0 },
            )
            val model = OpenAiCompatModel(server.url("/v1").toString().trimEnd('/'), "k", "m", caps, httpClient(readMs = 500L))
            val c = caller(model, policy = policy)

            var thrown: ModelError? = null
            val elapsedMs = measureTimeMillis {
                try {
                    c.ask(history, emptyList(), step = 1)
                    fail("Expected short-timeout failure on attempt 2")
                } catch (e: ModelError) {
                    thrown = e
                }
            }
            assertTrue((thrown as ModelError.Network).isTimeout)
            assertEquals(2, server.requestCount)
            assertEquals(140L, thrown?.let { (it as ModelError.Network).timeoutMs })
            assertTrue("Expected short retry timeout on attempt 2, took ${elapsedMs}ms", elapsedMs < 2_500L)
        }
    }

    @Test fun inferenceTimeoutOverrideNeverLengthensSmallerClientTimeout() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

            // Client has 120ms timeout while RetryPolicy default short timeout is 15_000ms.
            val policy = RetryPolicy(maxRetries = 3, baseMs = 10L, maxBackoffMs = 20L, jitter = { 0.0 })
            val model = OpenAiCompatModel(server.url("/v1").toString().trimEnd('/'), "k", "m", caps, httpClient(readMs = 120L, connectMs = 120L))
            val c = caller(model, policy = policy)

            var thrown: ModelError.Network? = null
            val elapsedMs = measureTimeMillis {
                try {
                    c.ask(history, emptyList(), step = 1)
                    fail("Expected fast timeout")
                } catch (e: ModelError.Network) {
                    thrown = e
                }
            }
            assertEquals(2, server.requestCount)
            assertEquals(true, thrown?.isTimeout)
            assertTrue("Smaller 120ms timeout must not be inflated to 15s (took ${elapsedMs}ms)", elapsedMs < 2_000L)
        }
    }

    @Test fun midSseStreamStallFailsFastAndRetriesOnceWithShortTimeout() = runBlocking {
        StallingSseServer(
            listOf(
                StallBehavior.SendPrefixThenStall("""{"choices":[{"delta":{"content":"partial"}}]}"""),
                StallBehavior.SendComplete(
                    """{"choices":[{"delta":{"content":"done"}}]}""",
                    """{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":8,"completion_tokens":3}}""",
                    "[DONE]",
                ),
            )
        ).use { server ->
            val model = OpenAiCompatModel("${server.baseUrl}/v1", "k", "m", caps, httpClient(readMs = 450L))
            val c = caller(model)
            val streamed = mutableListOf<String>()
            val reply = c.ask(history, emptyList(), step = 1, onText = { streamed += it })

            assertEquals("done", reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text })
            assertEquals(2, server.connections)
            assertEquals(true, c.lastCallDiagnostics?.timedOut)
            assertEquals(1, c.lastCallDiagnostics?.timeoutRetries)
            // Partial text from stalled stream was cleared via onText("") before retry.
            assertTrue(streamed.contains(""))
        }
    }

    @Test fun validSlowMultiChunkSseStreamExceedingReadTimeoutTotalDurationSucceeds() = runBlocking {
        StallingSseServer(
            listOf(
                StallBehavior.SendSlowChunks(
                    chunkDelayMs = 110L,
                    events = listOf(
                        """{"choices":[{"delta":{"content":"a"}}]}""",
                        """{"choices":[{"delta":{"content":"b"}}]}""",
                        """{"choices":[{"delta":{"content":"c"}}]}""",
                        """{"choices":[{"delta":{"content":"d"}}]}""",
                        """{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":4}}""",
                        "[DONE]",
                    ),
                ),
            )
        ).use { server ->
            // Each chunk arrives in 110ms (< 260ms readTimeout), while total duration > 550ms (> 260ms readTimeout).
            // callTimeout = 0 guarantees active SSE streams are not killed mid-stream.
            val model = OpenAiCompatModel("${server.baseUrl}/v1", "k", "m", caps, httpClient(readMs = 260L, callMs = 0L))
            val c = caller(model)
            val reply = c.ask(history, emptyList(), step = 1, onText = {})

            assertEquals("abcd", reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text })
            assertEquals(1, server.connections)
            assertNull(c.lastCallDiagnostics?.timedOut)
        }
    }

    @Test fun nonTimeoutTransientErrorsRetainThreeRetries() = runBlocking {
        // 503 x3 + 200 OK -> 4 HTTP requests
        MockWebServer().use { server ->
            server.start()
            repeat(3) { server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable")) }
            server.enqueue(openAiOkJson("ok-503"))

            val model = OpenAiCompatModel(server.url("/v1").toString().trimEnd('/'), "k", "m", caps, httpClient(readMs = 500L))
            val c = caller(model)
            val reply = c.ask(history, emptyList(), step = 1)
            assertEquals("ok-503", reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text })
            assertEquals(4, server.requestCount)
            assertNull(c.lastCallDiagnostics?.timedOut)
        }

        // Non-timeout network disconnect x3 + 200 OK -> 4 HTTP requests
        MockWebServer().use { server ->
            server.start()
            repeat(3) { server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) }
            server.enqueue(openAiOkJson("ok-disconnect"))

            val model = OpenAiCompatModel(server.url("/v1").toString().trimEnd('/'), "k", "m", caps, httpClient(readMs = 500L))
            val c = caller(model)
            val reply = c.ask(history, emptyList(), step = 1)
            assertEquals("ok-disconnect", reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text })
            assertEquals(4, server.requestCount)
            assertNull(c.lastCallDiagnostics?.timedOut)
        }
    }

    @Test fun rateLimited429RetainsThreeRetriesWithVirtualClock() = runTest {
        var calls = 0
        val flaky429 = object : ChatModel {
            override val name = "m-429"
            override val capabilities = caps
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                calls++
                if (calls <= 3) throw ModelError.RateLimited(1_000L)
                return answerReply("ok-429")
            }
        }
        val c = ModelCaller(flaky429, toolDispatcher(FakeScreen()), null, SpeedFlags.OFF, RetryPolicy(maxRetries = 3, jitter = { 0.0 }), {}, { 0L })
            .also { it.reset("") }
        val reply = c.ask(history, emptyList(), step = 1)
        assertEquals("ok-429", reply.parts.filterIsInstance<Part.Text>().single().text)
        assertEquals(4, calls)
    }

    @Test fun mixedTimeoutAndServer503RespectsTimeoutCapAndRetries503() = runBlocking {
        // Case 1: Timeout -> 503 -> Timeout (fails because 1 timeout retry already consumed)
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.enqueue(MockResponse().setResponseCode(503).setBody("err"))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.enqueue(openAiOkJson("unreachable"))

            val model = OpenAiCompatModel(server.url("/v1").toString().trimEnd('/'), "k", "m", caps, httpClient(readMs = 400L))
            val c = caller(model)
            try {
                c.ask(history, emptyList(), step = 1)
                fail("Expected ModelError.Network timeout on 3rd attempt")
            } catch (e: ModelError.Network) {
                assertTrue(e.isTimeout)
            }
            assertEquals(3, server.requestCount)
            assertEquals(true, c.lastCallDiagnostics?.timedOut)
            assertEquals(1, c.lastCallDiagnostics?.timeoutRetries)
        }

        // Case 2: 503 -> Timeout -> short-timeout retry recovers
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(503).setBody("err"))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            server.enqueue(openAiOkJson("recovered-after-503-then-timeout"))

            val model = OpenAiCompatModel(server.url("/v1").toString().trimEnd('/'), "k", "m", caps, httpClient(readMs = 400L))
            val c = caller(model)
            val reply = c.ask(history, emptyList(), step = 1)
            assertEquals("recovered-after-503-then-timeout", reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text })
            assertEquals(3, server.requestCount)
            assertEquals(true, c.lastCallDiagnostics?.timedOut)
            assertEquals(1, c.lastCallDiagnostics?.timeoutRetries)
        }
    }

    @Test fun fallbackChainTakesOverAfterSingleTimeoutRetryExhausted() = runBlocking {
        MockWebServer().use { primaryServer ->
            MockWebServer().use { backupServer ->
                primaryServer.start()
                backupServer.start()
                primaryServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                primaryServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
                backupServer.enqueue(openAiOkJson("from-backup"))

                val primary = OpenAiCompatModel(primaryServer.url("/v1").toString().trimEnd('/'), "k", "primary", caps, httpClient(readMs = 350L))
                val backup = OpenAiCompatModel(backupServer.url("/v1").toString().trimEnd('/'), "k", "backup", caps, httpClient(readMs = 350L))
                val c = caller(primary, fallbacks = listOf(backup))

                val reply = c.ask(history, emptyList(), step = 1)
                assertEquals("from-backup", reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text })
                assertSame(backup, c.lastMainUsed)
                assertEquals(2, primaryServer.requestCount)
                assertEquals(1, backupServer.requestCount)
                assertEquals(true, c.lastCallDiagnostics?.timedOut)
                assertEquals(1, c.lastCallDiagnostics?.timeoutRetries)
            }
        }
    }

    @Test fun lightTimeoutRetryAppliesShortTimeoutAndPreservesR2Contract() = runBlocking {
        MockWebServer().use { lightServer ->
            lightServer.start()
            // Attempt 1 times out at 500ms; Attempt 2 delays 280ms (> 140ms short timeout, < 500ms client timeout) -> falls back to Main.
            lightServer.enqueue(MockResponse().setHeadersDelay(800, TimeUnit.MILLISECONDS).setBody("{}"))
            lightServer.enqueue(MockResponse().setHeadersDelay(280, TimeUnit.MILLISECONDS).setBody("{}"))

            val lightModel = OpenAiCompatModel(
                baseUrl = lightServer.url("/v1").toString().trimEnd('/'),
                apiKey = "k-light",
                model = "gemini-3.8-flash",
                capabilities = caps,
                http = httpClient(readMs = 500L),
                forceToolCall = true,
            )
            val mainModel = object : ChatModel {
                override val name = "main-model"
                override val capabilities = caps
                var calls = 0
                override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                    calls++
                    return answerReply("from-main")
                }
            }
            val events = mutableListOf<RunEvent>()
            val policy = RetryPolicy(
                maxRetries = 3,
                baseMs = 20L,
                maxBackoffMs = 40L,
                timeoutRetryConnectMs = 140L,
                timeoutRetryMs = 140L,
                jitter = { 0.0 },
            )
            val router = ModelRouter(mainModel, lightModel)
            val c = caller(
                model = mainModel,
                policy = policy,
                router = router,
                flags = SpeedFlags(lightSteps = true),
                events = events,
            )
            val track = StepTrack().apply {
                after(Verb.Observe, ok = true, guard = false, changed = null, recovery = false, app = "com.shop.app")
                after(Verb.Observe, ok = true, guard = false, changed = null, recovery = false, app = "com.shop.app")
            }
            val (reply, used) = c.routed(history, observeSpec, track, step = 3, restarted = {})

            assertSame(mainModel, used)
            assertEquals("from-main", reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text })
            assertEquals(2, lightServer.requestCount)
            assertEquals(1, mainModel.calls)

            // R2 verification: Light request body preserves gemini-3.8-flash, LightPrompt, and tool_choice.
            val req1Body = lightServer.takeRequest().body.readUtf8()
            val req2Body = lightServer.takeRequest().body.readUtf8()
            for (body in listOf(req1Body, req2Body)) {
                assertTrue(body.contains(""""model":"gemini-3.8-flash""""))
                assertTrue(body.contains("tool_choice"))
                assertTrue(body.contains(LightPrompt.INSTRUCTION))
            }

            val rejectedLight = events.filterIsInstance<RunEvent.ModelReply>().filter { it.role == "light" && it.accepted == false }
            assertEquals(2, rejectedLight.size)
            assertEquals(true, rejectedLight[0].timedOut)
            assertEquals(0, rejectedLight[0].timeoutRetries)
            assertEquals(500L, rejectedLight[0].timeoutMs)
            assertEquals("network_timeout", rejectedLight[0].errorCode)
            assertEquals(true, rejectedLight[1].timedOut)
            assertEquals(1, rejectedLight[1].timeoutRetries)
            assertEquals(140L, rejectedLight[1].timeoutMs)
            assertEquals("network_timeout", rejectedLight[1].errorCode)
        }
    }

    @Test fun lightTimeoutRecoversOnShortTimeoutRetryWithoutCallingMain() = runBlocking {
        MockWebServer().use { lightServer ->
            lightServer.start()
            lightServer.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            lightServer.enqueue(openAiToolCallJson("phone_observe"))

            val lightModel = OpenAiCompatModel(
                baseUrl = lightServer.url("/v1").toString().trimEnd('/'),
                apiKey = "k-light",
                model = "gemini-3.8-flash",
                capabilities = caps,
                http = httpClient(readMs = 400L),
                forceToolCall = true,
            )
            val mainModel = object : ChatModel {
                override val name = "main-model"
                override val capabilities = caps
                var calls = 0
                override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                    calls++
                    return answerReply("should-not-run")
                }
            }
            val router = ModelRouter(mainModel, lightModel)
            val c = caller(mainModel, router = router, flags = SpeedFlags(lightSteps = true))
            val track = StepTrack().apply {
                after(Verb.Observe, ok = true, guard = false, changed = null, recovery = false, app = "com.shop.app")
                after(Verb.Observe, ok = true, guard = false, changed = null, recovery = false, app = "com.shop.app")
            }
            val (reply, used) = c.routed(history, observeSpec, track, step = 3, restarted = {})

            assertSame(lightModel, used)
            assertEquals("phone_observe", reply.parts.filterIsInstance<Part.ToolCall>().single().name)
            assertEquals(2, lightServer.requestCount)
            assertEquals(0, mainModel.calls)
            assertEquals(true, c.lastCallDiagnostics?.timedOut)
            assertEquals(1, c.lastCallDiagnostics?.timeoutRetries)
            assertEquals(150L, c.lastCallDiagnostics?.timeoutMs)
            assertEquals("network_timeout", c.lastCallDiagnostics?.retryReason)
        }
    }

    @Test fun allSixLlmAdaptersApplyShortTimeoutRetryThroughHttpSupport() = runBlocking {
        val policy = RetryPolicy(
            maxRetries = 3,
            baseMs = 10L,
            maxBackoffMs = 20L,
            timeoutRetryConnectMs = 140L,
            timeoutRetryMs = 140L,
            jitter = { 0.0 },
        )
        val client = httpClient(readMs = 450L)
        val adapters = listOf<Pair<String, (MockWebServer) -> ChatModel>>(
            "OpenAiCompat" to { s -> OpenAiCompatModel(s.url("/v1").toString().trimEnd('/'), "k", "m", caps, client) },
            "Anthropic" to { s -> AnthropicModel(s.url("").toString().trimEnd('/'), "k", "m", caps, client) },
            "Gemini" to { s -> GeminiModel(s.url("").toString().trimEnd('/'), "k", "m", caps, client) },
            "Responses" to { s -> ResponsesModel(s.url("/v1").toString().trimEnd('/'), BearerToken.fixed("k"), "m", caps, client) },
            "Vertex" to { s -> VertexModel("proj-1", "global", BearerToken.fixed("tok"), "gemini-4", caps, client, baseUrl = s.url("").toString().trimEnd('/')) },
            "Antigravity" to { s -> AntigravityModel(BearerToken.fixed("tok"), "proj-1", "gemini-4", caps, client, baseUrl = s.url("").toString().trimEnd('/')) },
        )
        for ((name, factory) in adapters) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse().setHeadersDelay(700, TimeUnit.MILLISECONDS).setBody("{}"))
                server.enqueue(MockResponse().setHeadersDelay(260, TimeUnit.MILLISECONDS).setBody("{}"))
                val c = caller(factory(server), policy = policy)
                try {
                    c.ask(history, emptyList(), step = 1)
                    fail("$name should have timed out on both attempts")
                } catch (e: ModelError.Network) {
                    assertTrue("$name expected timeout", e.isTimeout)
                    assertEquals("$name expected short timeoutMs on 2nd attempt", 140L, e.timeoutMs)
                }
                assertEquals("$name expected exactly 2 attempts", 2, server.requestCount)
            }
        }
    }

    private sealed interface StallBehavior {
        class SendPrefixThenStall(val event: String) : StallBehavior
        class SendComplete(vararg val events: String) : StallBehavior
        class SendSlowChunks(val chunkDelayMs: Long, val events: List<String>) : StallBehavior
    }

    private class StallingSseServer(behaviors: List<StallBehavior>) : Closeable {
        private val listener = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        private val executor = Executors.newCachedThreadPool { r -> Thread(r, "stalling-sse").apply { isDaemon = true } }
        private val queue = ArrayDeque(behaviors)
        private val sockets = mutableListOf<Socket>()
        @Volatile var connections = 0
            private set

        val baseUrl = "http://${listener.inetAddress.hostAddress}:${listener.localPort}"

        init {
            executor.submit {
                while (!listener.isClosed) {
                    val socket = try {
                        listener.accept()
                    } catch (_: Exception) {
                        break
                    }
                    synchronized(sockets) { sockets += socket }
                    connections++
                    val behavior = synchronized(queue) { queue.removeFirstOrNull() } ?: StallBehavior.SendComplete("[DONE]")
                    executor.submit { handle(socket, behavior) }
                }
            }
        }

        private fun handle(socket: Socket, behavior: StallBehavior) {
            socket.use { s ->
                s.soTimeout = 5_000
                readRequest(s.getInputStream())
                val out = s.getOutputStream()
                out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                when (behavior) {
                    is StallBehavior.SendPrefixThenStall -> {
                        out.write("data: ${behavior.event}\n\n".toByteArray())
                        out.flush()
                        Thread.sleep(2_000L)
                    }
                    is StallBehavior.SendComplete -> {
                        for (ev in behavior.events) {
                            out.write("data: $ev\n\n".toByteArray())
                        }
                        out.flush()
                    }
                    is StallBehavior.SendSlowChunks -> {
                        for (ev in behavior.events) {
                            Thread.sleep(behavior.chunkDelayMs)
                            out.write("data: $ev\n\n".toByteArray())
                            out.flush()
                        }
                    }
                }
            }
        }

        private fun readRequest(input: InputStream) {
            readLine(input)
            var len = 0
            while (true) {
                val line = readLine(input)
                if (line.isEmpty()) break
                val parts = line.split(':', limit = 2)
                if (parts.size == 2 && parts[0].equals("Content-Length", ignoreCase = true)) {
                    len = parts[1].trim().toInt()
                }
            }
            repeat(len) { if (input.read() == -1) throw EOFException() }
        }

        private fun readLine(input: InputStream): String {
            val bytes = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b == -1) throw EOFException()
                if (b == '\n'.code) return String(bytes.toByteArray(), Charsets.ISO_8859_1).removeSuffix("\r")
                bytes.write(b)
            }
        }

        override fun close() {
            runCatching { listener.close() }
            synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
            executor.shutdownNow()
        }
    }
}
