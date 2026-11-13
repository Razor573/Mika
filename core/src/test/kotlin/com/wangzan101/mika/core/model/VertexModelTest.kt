package com.wangzan101.mika.core.model

import com.wangzan101.mika.core.oauth.VertexEndpoints
import com.wangzan101.mika.core.usage.ModelHttpAttempts
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class VertexModelTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun model(
        projectId: String = "proj-1",
        location: String = "global",
        modelId: String = "gemini-4-argon",
        token: BearerToken = BearerToken.fixed("vt-1"),
        vision: Boolean = false,
        reasoningEffort: String? = null,
        headers: Map<String, String> = mapOf("User-Agent" to "MikaTest/1.0"),
        baseUrl: String? = server.url("/").toString().trimEnd('/'),
    ) = VertexModel(
        projectId = projectId,
        location = location,
        token = token,
        model = modelId,
        capabilities = Capabilities(toolCalls = true, vision = vision, contextTokens = 1_000_000, reasoning = true),
        http = OkHttpClient(),
        headers = headers,
        reasoningEffort = reasoningEffort,
        baseUrl = baseUrl,
    )

    private val user = listOf(Message(Role.User, listOf(Part.Text("hi")), Author.Mika))
    private fun ok(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun sse(vararg chunks: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(chunks.joinToString("") { "data: $it\n\n" })

    private fun cand(parts: String, finish: String = "STOP") =
        """{"candidates":[{"content":{"role":"model","parts":$parts},"finishReason":"$finish"}]}"""

    private fun JsonObject.arr(k: String) = this[k]!!.jsonArray
    private fun JsonObject.obj(k: String) = this[k]!!.jsonObject
    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content

    @Test
    fun parsesFunctionCallsAndUsesBearerWithoutApiKeyHeader() = runTest {
        server.enqueue(ok(cand("""[{"text":"ok"},{"functionCall":{"name":"phone_tap","args":{"ref":"a1"}}},{"functionCall":{"name":"phone_back","args":{}}}]""")))
        val r = model(headers = mapOf("User-Agent" to "MikaTest/1.0", "x-goog-api-key" to "should-be-stripped")).complete("s", user, emptyList())
        assertEquals(
            listOf(
                Part.Text("ok"),
                Part.ToolCall("gemini-0-phone_tap", "phone_tap", """{"ref":"a1"}"""),
                Part.ToolCall("gemini-1-phone_back", "phone_back", "{}"),
            ),
            r.parts,
        )
        assertEquals(StopReason.ToolUse, r.stopReason)

        val req = server.takeRequest()
        assertEquals("/v1/projects/proj-1/locations/global/publishers/google/models/gemini-4-argon:generateContent", req.path)
        assertEquals("Bearer vt-1", req.getHeader("Authorization"))
        assertEquals("proj-1", req.getHeader("x-goog-user-project"))
        assertEquals("MikaTest/1.0", req.getHeader("User-Agent"))
        assertNull(req.getHeader("x-goog-api-key"))
    }

    @Test
    fun streamingEmitsCumulativeDeltasAndHandlesTrailingUsageChunk() = runTest {
        server.enqueue(
            sse(
                """{"candidates":[{"content":{"parts":[{"text":"think","thought":true}]}}]}""",
                """{"candidates":[{"content":{"parts":[{"text":"Hel"}]}}]}""",
                """{"candidates":[{"content":{"parts":[{"text":"lo"},{"functionCall":{"name":"phone_tap","args":{"ref":"b2"}},"thoughtSignature":"SIG1"}]}}]}""",
                """{"candidates":[{"finishReason":"STOP"}]}""",
                """{"usageMetadata":{"promptTokenCount":50,"candidatesTokenCount":10,"thoughtsTokenCount":4,"cachedContentTokenCount":12}}""",
            ),
        )
        val emitted = mutableListOf<String>()
        val reply = model(location = "us-central1", modelId = "gemini-4-hydrogen").completeStreaming("s", user, emptyList()) {
            emitted += it
        }
        assertEquals(listOf("Hel", "Hello"), emitted)
        assertEquals(
            listOf(
                Part.Thinking("think"),
                Part.Text("Hello"),
                Part.ToolCall("gemini-0-phone_tap", "phone_tap", """{"ref":"b2"}""", "SIG1"),
            ),
            reply.parts,
        )
        assertEquals(StopReason.ToolUse, reply.stopReason)
        assertEquals(Usage(50, 14, 12, reasoningTokens = 4), reply.usage)

        val req = server.takeRequest()
        assertEquals(
            "/v1/projects/proj-1/locations/us-central1/publishers/google/models/gemini-4-hydrogen:streamGenerateContent?alt=sse",
            req.path,
        )
        assertEquals("text/event-stream", req.getHeader("Accept"))
        assertEquals("Bearer vt-1", req.getHeader("Authorization"))
        assertEquals("proj-1", req.getHeader("x-goog-user-project"))
        assertNull(req.getHeader("x-goog-api-key"))
    }

    @Test
    fun completeStreamingSupportsBufferedJsonFallback() = runTest {
        server.enqueue(ok("""{"candidates":[{"content":{"parts":[{"text":"buffered"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":3}}"""))
        val emitted = mutableListOf<String>()
        val reply = model().completeStreaming("s", user, emptyList()) { emitted += it }
        assertEquals(listOf("buffered"), emitted)
        assertEquals(listOf(Part.Text("buffered")), reply.parts)
        assertEquals(StopReason.EndTurn, reply.stopReason)
        assertEquals(Usage(10, 3, 0), reply.usage)
    }

    @Test
    fun retriesOnceOn401InBothCompleteAndCompleteStreaming() = runTest {
        val freshFlags = mutableListOf<Boolean>()
        val refreshableToken = object : BearerToken {
            override val refreshable: Boolean get() = true
            override suspend fun get(fresh: Boolean): String {
                freshFlags += fresh
                return if (fresh) "fresh-tok" else "stale-tok"
            }
        }

        // complete() 401 -> refresh -> 200
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":401,"status":"UNAUTHENTICATED","message":"expired"}}"""))
        server.enqueue(ok(cand("""[{"text":"recovered"}]""")))
        val completeReply = model(token = refreshableToken).complete("s", user, emptyList())
        assertEquals(listOf(Part.Text("recovered")), completeReply.parts)
        assertEquals(listOf(false, true), freshFlags)
        assertEquals("Bearer stale-tok", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer fresh-tok", server.takeRequest().getHeader("Authorization"))

        freshFlags.clear()

        // completeStreaming() 401 -> refresh -> 200 SSE
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":401,"status":"UNAUTHENTICATED","message":"expired"}}"""))
        server.enqueue(sse("""{"candidates":[{"content":{"parts":[{"text":"stream-ok"}]},"finishReason":"STOP"}]}"""))
        val emitted = mutableListOf<String>()
        val streamReply = model(token = refreshableToken).completeStreaming("s", user, emptyList()) { emitted += it }
        assertEquals(listOf("stream-ok"), emitted)
        assertEquals(listOf(Part.Text("stream-ok")), streamReply.parts)
        assertEquals(listOf(false, true), freshFlags)
        assertEquals("Bearer stale-tok", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer fresh-tok", server.takeRequest().getHeader("Authorization"))

        // Non-refreshable fixed token does not retry on 401
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":401,"status":"UNAUTHENTICATED","message":"bad"}}"""))
        try {
            model(token = BearerToken.fixed("bad-fixed")).complete("s", user, emptyList())
            fail("Expected ModelError.Auth")
        } catch (_: ModelError.Auth) {
        }
        assertEquals("Bearer bad-fixed", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun thinkingToolsSanitizationVisionAndThoughtSignaturesMatchGeminiCodec() = runTest {
        server.enqueue(ok(cand("""[{"text":"ok"}]""")))
        val schema = """{"${'$'}schema":"x","type":"object","additionalProperties":false,"properties":{"additionalProperties":{"type":"string","additionalProperties":false}}}"""
        val foreignHistory = listOf(
            Message(Role.User, listOf(Part.Text("go"), Part.Image("IMG")), Author.Mika),
            Message(Role.Assistant, listOf(Part.ToolCall("c1", "phone_tap", """{"ref":"r1"}"""), Part.ToolCall("c2", "phone_back", "{}")), Author.Mika),
            Message(Role.Tool, listOf(Part.ToolResult("c1", "done", isError = true)), Author.Mika),
        )
        model(vision = true, reasoningEffort = "high").complete("SYS", foreignHistory, listOf(ToolSpec("t", "desc", schema)))
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

        assertEquals("SYS", body.obj("systemInstruction").arr("parts")[0].jsonObject.str("text"))
        val cfg = body.obj("generationConfig").obj("thinkingConfig")
        assertEquals("HIGH", cfg.str("thinkingLevel"))
        assertEquals("true", cfg["includeThoughts"]!!.jsonPrimitive.content)

        val params = body.arr("tools")[0].jsonObject.arr("functionDeclarations")[0].jsonObject.obj("parameters")
        assertFalse(params.containsKey("\$schema"))
        assertFalse(params.containsKey("additionalProperties"))
        assertTrue(params.obj("properties").containsKey("additionalProperties"))
        assertFalse(params.obj("properties").obj("additionalProperties").containsKey("additionalProperties"))

        val contents = body.arr("contents")
        assertEquals(listOf("user", "model", "user"), contents.map { it.jsonObject.str("role") })
        val userInline = contents[0].jsonObject.arr("parts")[1].jsonObject.obj("inlineData")
        assertEquals("image/png", userInline.str("mimeType"))
        val modelParts = contents[1].jsonObject.arr("parts")
        assertEquals(SKIP_SIGNATURE, modelParts[0].jsonObject.str("thoughtSignature"))
        assertFalse(modelParts[1].jsonObject.containsKey("thoughtSignature"))
        val fr = contents[2].jsonObject.arr("parts")[0].jsonObject.obj("functionResponse")
        assertEquals("phone_tap", fr.str("name"))
        assertEquals("true", fr.obj("response").str("is_error"))
    }

    @Test
    fun verifiesHttpsUrlsAndRejectsNonLoopbackHttp() {
        assertEquals(
            "https://aiplatform.googleapis.com/v1/projects/p1/locations/global/publishers/google/models/gemini-4-argon:generateContent",
            VertexEndpoints.generateContentUrl("p1", "", "gemini-4-argon", stream = false),
        )
        assertEquals(
            "https://europe-west1-aiplatform.googleapis.com/v1/projects/p1/locations/europe-west1/publishers/google/models/gemini-4-carbon:streamGenerateContent?alt=sse",
            VertexEndpoints.streamGenerateContentUrl("p1", "europe-west1", "gemini-4-carbon"),
        )
        try {
            model(baseUrl = "http://insecure.example.com")
            fail("Expected non-loopback http:// baseUrl to be rejected")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun vertexCatalogMergesLiveModelsAndPreservesCuratedDefaults() = runTest {
        val catalog = ModelCatalog(OkHttpClient())
        val base = server.url("/").toString().trimEnd('/')

        // 1. Live publisher list with curated + extra + excluded models (also triggers 401 refresh retry)
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"code":401,"message":"expired"}}"""))
        server.enqueue(
            ok(
                """{
                    "publisherModels": [
                        {"name": "publishers/google/models/gemini-4-hydrogen", "displayName": "Gemini 4 Hydrogen Live", "inputTokenLimit": 1000000, "thinking": true},
                        {"name": "publishers/google/models/gemini-2.5-pro", "displayName": "Gemini 2.5 Pro", "inputTokenLimit": 2000000, "thinking": true},
                        {"name": "publishers/google/models/imagen-3.0-generate-001"},
                        {"name": "publishers/google/models/gemini-embedding-001"}
                    ]
                }""",
            ),
        )

        val freshCalls = mutableListOf<Boolean>()
        val token = object : BearerToken {
            override val refreshable: Boolean get() = true
            override suspend fun get(fresh: Boolean): String {
                freshCalls += fresh
                return if (fresh) "tok-fresh" else "tok-stale"
            }
        }
        val okRes = catalog.vertex(token = token, location = "us-central1", projectId = "proj-1", baseUrl = base)
        val listed = (okRes as ModelListResult.Ok).models
        val ids = listed.map { it.id }
        assertEquals(
            listOf("gemini-4-hydrogen", "gemini-4-argon", "gemini-4-carbon", "gemini-3.8-flash", "gemini-2.5-pro"),
            ids,
        )
        assertEquals("Gemini 4 Hydrogen Live", listed.first { it.id == "gemini-4-hydrogen" }.displayName)
        assertEquals(1_000_000, listed.first { it.id == "gemini-4-hydrogen" }.contextTokens)
        assertEquals(true, listed.first { it.id == "gemini-4-hydrogen" }.reasoning)
        assertEquals(listOf(false, true), freshCalls)

        val req1 = server.takeRequest()
        assertEquals("/v1/publishers/google/models?pageSize=100", req1.path)
        assertEquals("Bearer tok-stale", req1.getHeader("Authorization"))
        assertEquals("proj-1", req1.getHeader("x-goog-user-project"))
        assertNull(req1.getHeader("x-goog-api-key"))
        assertEquals("Bearer tok-fresh", server.takeRequest().getHeader("Authorization"))

        // 2. Empty live response and 404 still keep all 4 plain-ID curated defaults
        server.enqueue(ok("""{"publisherModels":[]}"""))
        val emptyOk = catalog.vertex(token = BearerToken.fixed("vt"), baseUrl = base) as ModelListResult.Ok
        assertEquals(listOf("gemini-4-hydrogen", "gemini-4-argon", "gemini-4-carbon", "gemini-3.8-flash"), emptyOk.models.map { it.id })
        server.takeRequest()

        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"code":404,"message":"not supported"}}"""))
        val fallbackOk = catalog.vertex(token = BearerToken.fixed("vt"), baseUrl = base) as ModelListResult.Ok
        assertEquals(listOf("gemini-4-hydrogen", "gemini-4-argon", "gemini-4-carbon", "gemini-3.8-flash"), fallbackOk.models.map { it.id })
        server.takeRequest()

        // 3. 403 maps cleanly to ListFailure.Auth
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"code":403,"message":"forbidden"}}"""))
        val authFail = catalog.vertex(token = BearerToken.fixed("vt"), baseUrl = base) as ModelListResult.Failed
        assertEquals(ListFailure.Auth, authFail.reason)
        server.takeRequest()
    }

    @Test
    fun inferenceProtocolGeminiUnwrapsDirectAndWrappedUsageMetadata() {
        val direct = Json.parseToJsonElement(
            """{"usageMetadata":{"promptTokenCount":120,"candidatesTokenCount":30,"thoughtsTokenCount":10,"cachedContentTokenCount":40}}""",
        ).jsonObject
        val wrapped = Json.parseToJsonElement(
            """{"response":{"usageMetadata":{"promptTokenCount":120,"candidatesTokenCount":30,"thoughtsTokenCount":10,"cachedContentTokenCount":40}}}""",
        ).jsonObject
        assertEquals(ReportedTokens(120, 40, 40), InferenceProtocol.Gemini.usageIn(direct))
        assertEquals(ReportedTokens(120, 40, 40), InferenceProtocol.Gemini.usageIn(wrapped))
    }

    @Test
    fun mapsErrorsAndFinishReasons() = runTest {
        suspend fun errorOf(status: Int, body: String, vararg headers: Pair<String, String>): ModelError {
            val r = MockResponse().setResponseCode(status).setBody(body)
            headers.forEach { r.setHeader(it.first, it.second) }
            server.enqueue(r)
            try {
                model().complete("s", user, emptyList())
            } catch (e: ModelError) {
                server.takeRequest()
                return e
            }
            fail("expected ModelError")
            throw IllegalStateException()
        }

        fun err(code: Int, status: String, msg: String) =
            """{"error":{"code":$code,"status":"$status","message":"$msg"}}"""

        assertTrue(errorOf(400, err(400, "INVALID_ARGUMENT", "The input token count (2000000) exceeds the maximum number of tokens allowed (1048576).")) is ModelError.ContextTooLong)
        assertTrue(errorOf(403, err(403, "PERMISSION_DENIED", "no")) is ModelError.Auth)
        val rl = errorOf(429, err(429, "RESOURCE_EXHAUSTED", "slow down"), "Retry-After" to "3")
        assertEquals(3000L, (rl as ModelError.RateLimited).retryAfterMs)
        assertTrue(errorOf(404, err(404, "NOT_FOUND", "models/x not found")) is ModelError.ModelNotFound)
        assertEquals(503, (errorOf(503, err(503, "UNAVAILABLE", "busy")) as ModelError.Server).status)

        server.enqueue(ok(cand("""[{"text":"x"}]""", "MAX_TOKENS")))
        assertEquals(StopReason.MaxTokens, model().complete("s", user, emptyList()).stopReason)
        server.takeRequest()

        server.enqueue(ok("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))
        try {
            model().complete("s", user, emptyList())
            fail("expected ContentRefused")
        } catch (_: ModelError.ContentRefused) {
        }
        server.takeRequest()
    }

    @Test
    fun postInferenceTagsLogicalPayloadAndPostsWirePayload() = runTest {
        val logical = buildJsonObject { put("model", "gemini-4-argon") }
        val wire = buildJsonObject { put("request", logical) }
        val built = withContext(ModelHttpAttempts()) {
            Request.Builder()
                .url(server.url("/v1/test"))
                .postInference(InferenceProtocol.Gemini, logical, wirePayload = wire)
                .build()
        }
        val tag = built.tag(InferenceRequest::class.java)!!
        assertEquals(InferenceProtocol.Gemini, tag.protocol)
        assertEquals(logical, tag.payload)
        val sink = okio.Buffer()
        built.body!!.writeTo(sink)
        assertEquals(wire.toString(), sink.readUtf8())
    }
}
