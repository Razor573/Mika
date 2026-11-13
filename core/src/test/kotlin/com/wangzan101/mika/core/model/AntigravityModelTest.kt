package com.wangzan101.mika.core.model

import com.wangzan101.mika.core.oauth.AntigravityAuth
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class AntigravityModelTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val user = listOf(Message(Role.User, listOf(Part.Text("hi")), Author.User))
    private val tapTool = ToolSpec(
        name = "phone_tap",
        description = "Tap element",
        parametersJsonSchema = """{"type":"object","properties":{"ref":{"type":"string"},"mode":{"const":"tap"}},"required":["ref"],"additionalProperties":false,"${'$'}schema":"http://json-schema.org/draft-07/schema#"}""",
    )

    @Before
    fun setUp() {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl(): String = server.url("/").toString().trimEnd('/')

    private fun model(
        modelId: String = "gemini-4-hydrogen",
        token: BearerToken = BearerToken.fixed("ag-tok-1"),
        projectId: () -> String = { "proj-1" },
        vision: Boolean = true,
        headers: Map<String, String> = emptyMap(),
        maxOutputTokens: Int? = null,
        reasoningEffort: String? = null,
        base: String? = baseUrl(),
    ): AntigravityModel = AntigravityModel(
        token = token,
        projectId = projectId,
        model = modelId,
        capabilities = Capabilities(toolCalls = true, vision = vision, contextTokens = 1_000_000, reasoning = true),
        http = http,
        headers = headers,
        maxOutputTokens = maxOutputTokens,
        reasoningEffort = reasoningEffort,
        baseUrl = base,
    )

    private fun sse(vararg payloads: String): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody(payloads.joinToString("") { "data: $it\n\n" })

    private fun okJson(body: String): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private fun sentEnvelope(): JsonObject =
        Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    @Test
    fun resolveWireModelCoversVariantsAndNeverAppendsTieredToGemini4() {
        val efforts = listOf(
            null to "medium",
            "" to "medium",
            "none" to "medium",
            "minimal" to "low",
            "low" to "low",
            "medium" to "medium",
            "high" to "high",
            "xhigh" to "xhigh",
            "HIGH" to "high",
        )
        for (base in listOf("gemini-4-hydrogen", "gemini-4-argon", "models/gemini-4-hydrogen", "models/gemini-4-argon")) {
            val bare = base.removePrefix("models/")
            for ((effort, suffix) in efforts) {
                val resolved = AntigravityModel.resolveWireModel(base, effort)
                assertEquals("$bare-$suffix", resolved)
                assertFalse(resolved.contains("tiered"))
            }
        }

        for (alreadySuffixed in listOf(
            "gemini-4-hydrogen-low",
            "gemini-4-argon-xhigh",
            "gemini-3.8-flash-tiered",
            "gemini-pro-agent",
            "GEMINI-4-ARGON-HIGH",
        )) {
            assertEquals(alreadySuffixed, AntigravityModel.resolveWireModel(alreadySuffixed, "minimal"))
        }
        assertEquals("gemini-4-hydrogen-medium", AntigravityModel.resolveWireModel("models/gemini-4-hydrogen-medium", "high"))

        assertEquals("gemini-3.8-flash-tiered", AntigravityModel.resolveWireModel("gemini-3.8-flash", "high"))
        assertEquals("gemini-3.7-flash-tiered", AntigravityModel.resolveWireModel("gemini-3.7-flash", null))
        assertEquals("gemini-3.6-flash-tiered", AntigravityModel.resolveWireModel("models/gemini-3.6-flash", null))

        assertEquals("gemini-pro-agent", AntigravityModel.resolveWireModel("gemini-3.1-pro", "high"))
        assertEquals("gemini-pro-agent", AntigravityModel.resolveWireModel("gemini-3.1-pro-preview", null))
        assertEquals("gemini-pro-agent", AntigravityModel.resolveWireModel("gemini-pro", null))

        assertEquals("gemini-2.5-flash", AntigravityModel.resolveWireModel("models/gemini-2.5-flash", "high"))
    }

    @Test
    fun rejectsNonLoopbackHttpBaseUrl() {
        for (bad in listOf("http://daily-cloudcode-pa.googleapis.com", "ftp://daily-cloudcode-pa.googleapis.com")) {
            try {
                model(base = bad)
                fail("Expected IllegalArgumentException for $bad")
            } catch (_: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun sendsExactEndpointHeadersAndOuterEnvelope() = runBlocking<Unit> {
        server.enqueue(
            sse(
                """{"response":{"candidates":[{"content":{"role":"model","parts":[{"text":"hello"}]},"finishReason":"STOP"}]}}""",
                "[DONE]",
            ),
        )
        var currentProject = "proj-dynamic-42"
        val m = model(
            modelId = "gemini-4-hydrogen",
            projectId = { currentProject },
            headers = mapOf(
                "User-Agent" to "should-be-overridden",
                "Authorization" to "Bearer evil",
                "x-goog-api-key" to "secret-key",
                "x-test-session" to "sess-99",
            ),
            reasoningEffort = "xhigh",
            maxOutputTokens = 2048,
        )
        m.complete("Be helpful", user, listOf(tapTool))

        val req = server.takeRequest()
        assertEquals("/v1internal:streamGenerateContent?alt=sse", req.path)
        assertEquals("Bearer ag-tok-1", req.getHeader("Authorization"))
        assertEquals(AntigravityAuth.USER_AGENT, req.getHeader("User-Agent"))
        assertFalse(req.getHeader("User-Agent")!!.contains("cl="))
        assertEquals(AntigravityAuth.GOOG_API_CLIENT, req.getHeader("X-Goog-Api-Client"))
        assertEquals(AntigravityAuth.CLIENT_METADATA, req.getHeader("Client-Metadata"))
        assertEquals("text/event-stream", req.getHeader("Accept"))
        assertEquals("sess-99", req.getHeader("x-test-session"))
        assertNull(req.getHeader("x-goog-api-key"))

        val envelope = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("proj-dynamic-42", envelope["project"]!!.jsonPrimitive.content)
        assertEquals("gemini-4-hydrogen-xhigh", envelope["model"]!!.jsonPrimitive.content)
        assertEquals("antigravity", envelope["userAgent"]!!.jsonPrimitive.content)
        assertEquals("agent", envelope["requestType"]!!.jsonPrimitive.content)
        assertTrue(envelope["requestId"]!!.jsonPrimitive.content.startsWith("agent-"))

        val inner = envelope["request"]!!.jsonObject
        val genCfg = inner["generationConfig"]!!.jsonObject
        assertEquals(2048, genCfg["maxOutputTokens"]!!.jsonPrimitive.content.toInt())
        val thinkingCfg = genCfg["thinkingConfig"]!!.jsonObject
        assertTrue(thinkingCfg["includeThoughts"]!!.jsonPrimitive.boolean)
        assertEquals("HIGH", thinkingCfg["thinkingLevel"]!!.jsonPrimitive.content)

        val decl = inner["tools"]!!.jsonArray[0].jsonObject["functionDeclarations"]!!.jsonArray[0].jsonObject
        val params = decl["parameters"]!!.jsonObject
        assertFalse(params.containsKey("additionalProperties"))
        assertFalse(params.containsKey("${'$'}schema"))

        // Subsequent turn reads updated projectId from lambda
        currentProject = "proj-dynamic-99"
        server.enqueue(
            sse(
                """{"response":{"candidates":[{"content":{"role":"model","parts":[{"text":"second"}]},"finishReason":"STOP"}]}}""",
                "[DONE]",
            ),
        )
        m.complete("sys", user, emptyList())
        assertEquals("proj-dynamic-99", sentEnvelope()["project"]!!.jsonPrimitive.content)
    }

    @Test
    fun mapsThinkingLevelsForGemini4Models() = runBlocking<Unit> {
        val cases = listOf(
            null to "MEDIUM",
            "none" to "MEDIUM",
            "minimal" to "LOW",
            "low" to "LOW",
            "medium" to "MEDIUM",
            "high" to "HIGH",
            "xhigh" to "HIGH",
        )
        for ((effort, expectedLevel) in cases) {
            server.enqueue(
                sse(
                    """{"response":{"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}]}}""",
                    "[DONE]",
                ),
            )
            model(modelId = "gemini-4-argon", reasoningEffort = effort).complete("sys", user, emptyList())
            val env = sentEnvelope()
            val thinkingCfg = env["request"]!!.jsonObject["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject
            assertEquals("effort=$effort", expectedLevel, thinkingCfg["thinkingLevel"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun streamsTextThinkingToolCallsUsageAndBufferedJsonFallback() = runBlocking<Unit> {
        server.enqueue(
            sse(
                """{"response":{"candidates":[{"content":{"role":"model","parts":[{"thought":true,"text":"plan "},{"text":"Hello "}]}}]}}""",
                """{"response":{"candidates":[{"content":{"role":"model","parts":[{"text":"world"},{"functionCall":{"name":"phone_tap","args":{"ref":"b1"}},"thoughtSignature":"sig-abc"}]},"finishReason":"STOP"}]}}""",
                """{"response":{"usageMetadata":{"promptTokenCount":25,"candidatesTokenCount":9,"cachedContentTokenCount":5,"thoughtsTokenCount":3}}}""",
                "[DONE]",
            ),
        )
        val updates = mutableListOf<String>()
        val reply = model().completeStreaming("sys", user, listOf(tapTool), updates::add)

        assertEquals(listOf("Hello ", "Hello world"), updates)
        assertEquals(StopReason.ToolUse, reply.stopReason)
        assertEquals(Usage(25, 12, 5, reasoningTokens = 3), reply.usage)
        assertEquals(Part.Thinking("plan "), reply.parts[0])
        assertEquals(Part.Text("Hello world"), reply.parts[1])
        val call = reply.parts[2] as Part.ToolCall
        assertEquals("phone_tap", call.name)
        assertEquals("""{"ref":"b1"}""", call.argsJson)
        assertEquals("sig-abc", call.opaque)

        // Buffered application/json response fallback
        server.enqueue(
            okJson(
                """{"response":{"candidates":[{"content":{"role":"model","parts":[{"text":"buffered"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":4,"candidatesTokenCount":2}}}""",
            ),
        )
        val bufferedUpdates = mutableListOf<String>()
        val bufferedReply = model().completeStreaming("sys", user, emptyList(), bufferedUpdates::add)
        assertEquals(listOf("buffered"), bufferedUpdates)
        assertEquals(listOf(Part.Text("buffered")), bufferedReply.parts)
        assertEquals(Usage(4, 2, 0), bufferedReply.usage)
    }

    @Test
    fun encodesToolResultsSignaturesVisionAndInterruptedHistory() = runBlocking<Unit> {
        server.enqueue(
            sse(
                """{"response":{"candidates":[{"content":{"role":"model","parts":[{"text":"done"}]},"finishReason":"STOP"}]}}""",
                "[DONE]",
            ),
        )
        val history = InterruptedHistory.messages + listOf(
            Message(
                Role.Assistant,
                listOf(
                    Part.ToolCall("c1", "phone_tap", """{"ref":"a1"}""", opaque = null),
                    Part.ToolCall("c2", "phone_tap", """{"ref":"a2"}""", opaque = "sig-kept"),
                ),
                Author.Mika,
            ),
            Message(
                Role.Tool,
                listOf(Part.ToolResult("c1", "ok", isError = true)),
                Author.Mika,
            ),
            Message(
                Role.User,
                listOf(Part.Text("look"), Part.Image("QUJD", mime = "image/jpeg")),
                Author.User,
            ),
        )

        model(vision = true).complete("sys", history, listOf(tapTool))
        val contents = sentEnvelope()["request"]!!.jsonObject["contents"]!!.jsonArray
        val serialized = contents.toString()
        assertTrue(serialized.contains(GeminiCodec.SKIP_SIGNATURE))
        assertTrue(serialized.contains("sig-kept"))
        assertTrue(serialized.contains("functionResponse"))
        assertTrue(serialized.contains("inlineData"))
        assertTrue(serialized.contains("image/jpeg"))

        // Vision off omits image inlineData
        server.enqueue(
            sse(
                """{"response":{"candidates":[{"content":{"role":"model","parts":[{"text":"ok"}]},"finishReason":"STOP"}]}}""",
                "[DONE]",
            ),
        )
        model(vision = false).complete(
            "sys",
            listOf(Message(Role.User, listOf(Part.Image("QUJD")), Author.User)),
            emptyList(),
        )
        val noVision = sentEnvelope()["request"]!!.jsonObject["contents"]!!.toString()
        assertFalse(noVision.contains("inlineData"))
        assertTrue(noVision.contains("screenshot omitted"))
    }

    @Test
    fun mapsFinishReasonsAndErrorsIncludingStreamErrors() = runBlocking<Unit> {
        server.enqueue(
            sse(
                """{"response":{"candidates":[{"content":{"role":"model","parts":[{"text":"cut"}]},"finishReason":"MAX_TOKENS"}]}}""",
                "[DONE]",
            ),
        )
        assertEquals(StopReason.MaxTokens, model().complete("sys", user, emptyList()).stopReason)

        server.enqueue(
            sse(
                """{"response":{"candidates":[{"content":{"role":"model","parts":[]},"finishReason":"SAFETY"}]}}""",
                "[DONE]",
            ),
        )
        try {
            model().complete("sys", user, emptyList())
            fail("Expected ContentRefused on SAFETY")
        } catch (_: ModelError.ContentRefused) {
            // expected
        }

        suspend fun errFor(resp: MockResponse): ModelError {
            server.enqueue(resp)
            return try {
                model().complete("sys", user, emptyList())
                fail("Expected ModelError")
                throw AssertionError()
            } catch (e: ModelError) {
                e
            }
        }

        assertTrue(errFor(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"not found"}}""")) is ModelError.ModelNotFound)
        assertTrue(errFor(MockResponse().setResponseCode(429).setHeader("retry-after", "3")) is ModelError.RateLimited)
        assertTrue(
            errFor(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"Quota exceeded, limit: 0"}}""")) is ModelError.ModelNotAvailable,
        )
        assertTrue(errFor(MockResponse().setResponseCode(503)) is ModelError.Server)
        assertTrue(errFor(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"exceeds the maximum number of tokens"}}""")) is ModelError.ContextTooLong)
        assertTrue(errFor(sse("""{"error":{"code":429,"message":"slow down"}}""")) is ModelError.RateLimited)
    }

    @Test
    fun retries401OnceWhenTokenIsRefreshable() = runBlocking<Unit> {
        var calls = 0
        val refreshFlags = mutableListOf<Boolean>()
        val refreshableToken = object : BearerToken {
            override val refreshable: Boolean = true
            override suspend fun get(fresh: Boolean): String {
                calls++
                refreshFlags += fresh
                return if (fresh) "tok-fresh" else "tok-stale"
            }
        }
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"expired"}}"""))
        server.enqueue(
            sse(
                """{"response":{"candidates":[{"content":{"role":"model","parts":[{"text":"recovered"}]},"finishReason":"STOP"}]}}""",
                "[DONE]",
            ),
        )
        val reply = model(token = refreshableToken).complete("sys", user, emptyList())
        assertEquals(listOf(Part.Text("recovered")), reply.parts)
        assertEquals(listOf(false, true), refreshFlags)
        assertEquals(2, calls)
        assertEquals("Bearer tok-stale", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer tok-fresh", server.takeRequest().getHeader("Authorization"))

        // Non-refreshable token does not retry
        val beforeNonRefreshable = server.requestCount
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad token"}}"""))
        try {
            model(token = BearerToken.fixed("tok-fixed")).complete("sys", user, emptyList())
            fail("Expected ModelError.Auth")
        } catch (_: ModelError.Auth) {
            // expected
        }
        assertEquals(beforeNonRefreshable + 1, server.requestCount)
        server.takeRequest()
    }

    @Test
    fun catalogAntigravityCuratesCollapsesFiltersAndRetries401() = runBlocking<Unit> {
        assertEquals(
            listOf("gemini-4-hydrogen", "gemini-4-argon", "gemini-3.8-flash"),
            ModelCatalog.CURATED_ANTIGRAVITY_MODELS.map { it.id },
        )

        val catalog = ModelCatalog(http)
        val refreshFlags = mutableListOf<Boolean>()
        val refreshable = object : BearerToken {
            override val refreshable: Boolean = true
            override suspend fun get(fresh: Boolean): String {
                refreshFlags += fresh
                return if (fresh) "cat-fresh" else "cat-stale"
            }
        }

        // 401 -> refresh once -> 200 live list with variants and excluded entries
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"expired"}}"""))
        server.enqueue(
            okJson(
                """
                {
                  "models": {
                    "gemini-4-hydrogen-low": {"displayName": "Gemini 4 Hydrogen (low)", "inputTokenLimit": 1000000, "thinking": true},
                    "gemini-4-hydrogen-high": {"displayName": "Gemini 4 Hydrogen (high)", "inputTokenLimit": 1000000, "thinking": true},
                    "models/gemini-4-argon-xhigh": {"displayName": "Gemini 4 Argon (xhigh)", "inputTokenLimit": 1000000, "thinking": true},
                    "gemini-3.8-flash-tiered": {"displayName": "Gemini 3.8 Flash", "inputTokenLimit": 1000000},
                    "gemini-4-carbon": {"displayName": "Gemini 4 Carbon"},
                    "claude-sonnet-4-6": {"displayName": "Claude Sonnet 4.6"},
                    "gpt-oss-120b": {"displayName": "GPT OSS 120B"},
                    "imagen-3.0-generate": {"displayName": "Imagen 3"},
                    "veo-2.0": {"displayName": "Veo 2"},
                    "gemini-embedding-001": {"displayName": "Embedding"},
                    "aqa": {"displayName": "AQA"},
                    "gemini-2.5-flash-tts": {"displayName": "TTS"},
                    "gemini-2.5-flash-audio": {"displayName": "Audio"},
                    "gemini-live-2.5-flash": {"displayName": "Live"},
                    "gemini-robotics-er": {"displayName": "Robotics"},
                    "gemini-computer-use": {"displayName": "Computer Use"}
                  }
                }
                """.trimIndent(),
            ),
        )

        val okResult = catalog.antigravity(refreshable, projectId = "proj-cat-1", baseUrl = baseUrl())
        assertTrue(okResult is ModelListResult.Ok)
        val models = (okResult as ModelListResult.Ok).models
        val ids = models.map { it.id }
        assertEquals(ids.distinct(), ids)
        assertTrue(ids.containsAll(listOf("gemini-4-hydrogen", "gemini-4-argon", "gemini-3.8-flash")))
        for (excluded in listOf("carbon", "claude", "gpt", "imagen", "veo", "embedding", "aqa", "tts", "audio", "live", "robotics", "computer-use", "-low", "-high", "-xhigh", "-tiered")) {
            assertFalse("Unexpected $excluded in $ids", ids.any { excluded in it })
        }
        assertEquals(listOf(false, true), refreshFlags)
        val firstCatReq = server.takeRequest()
        assertEquals("/v1internal:fetchAvailableModels", firstCatReq.path)
        assertEquals("""{"project":"proj-cat-1"}""", firstCatReq.body.readUtf8())
        assertEquals("Bearer cat-stale", firstCatReq.getHeader("Authorization"))
        assertEquals(AntigravityAuth.USER_AGENT, firstCatReq.getHeader("User-Agent"))
        assertEquals("Bearer cat-fresh", server.takeRequest().getHeader("Authorization"))

        // 404 NotSupported falls back cleanly to curated list
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"message":"not found"}}"""))
        val notSupported = catalog.antigravity(BearerToken.fixed("tok"), projectId = "proj-cat-1", baseUrl = baseUrl())
        assertEquals(ModelListResult.Ok(ModelCatalog.CURATED_ANTIGRAVITY_MODELS), notSupported)
        server.takeRequest()

        // 403 returns Auth failure without curated fallback
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"message":"forbidden"}}"""))
        val forbidden = catalog.antigravity(BearerToken.fixed("tok"), projectId = "proj-cat-1", baseUrl = baseUrl())
        assertTrue(forbidden is ModelListResult.Failed && forbidden.reason == ListFailure.Auth)
        server.takeRequest()

        // Non-loopback http baseUrl returns BadUrl
        val badUrl = catalog.antigravity(BearerToken.fixed("tok"), projectId = "proj-cat-1", baseUrl = "http://daily-cloudcode-pa.googleapis.com")
        assertTrue(badUrl is ModelListResult.Failed && badUrl.reason == ListFailure.BadUrl)
    }
}
