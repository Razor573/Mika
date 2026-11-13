package com.wangzan101.mika.core.model

import com.wangzan101.mika.core.oauth.AntigravityAuth
import com.wangzan101.mika.core.oauth.VertexEndpoints
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.UUID

class AntigravityModel(
    private val token: BearerToken,
    private val projectId: () -> String,
    private val model: String,
    override val capabilities: Capabilities,
    private val http: OkHttpClient,
    private val headers: Map<String, String> = emptyMap(),
    private val maxOutputTokens: Int? = null,
    private val reasoningEffort: String? = null,
    baseUrl: String? = null,
) : StreamingChatModel {
    constructor(
        token: BearerToken,
        projectId: String,
        model: String,
        capabilities: Capabilities,
        http: OkHttpClient,
        headers: Map<String, String> = emptyMap(),
        maxOutputTokens: Int? = null,
        reasoningEffort: String? = null,
        baseUrl: String? = null,
    ) : this(
        token = token,
        projectId = { projectId },
        model = model,
        capabilities = capabilities,
        http = http,
        headers = headers,
        maxOutputTokens = maxOutputTokens,
        reasoningEffort = reasoningEffort,
        baseUrl = baseUrl,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val cleanModel = model.trim().replace(MODELS_PREFIX_REGEX, "").trim()
    private val streamEndpoint: String

    override val name: String get() = cleanModel

    init {
        val base = if (baseUrl == null) {
            AntigravityAuth.DAILY_ENDPOINT
        } else {
            VertexEndpoints.requireHttpsOrLoopbackUrl(baseUrl, "Antigravity baseUrl").toString().trimEnd('/')
        }
        streamEndpoint = "$base/v1internal:streamGenerateContent?alt=sse"
    }

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
        completeStreaming(system, messages, tools) {}

    override suspend fun completeStreaming(
        system: String,
        messages: List<Message>,
        tools: List<ToolSpec>,
        onText: (String) -> Unit,
    ): ModelReply {
        val wireModel = resolveWireModel(model, reasoningEffort)
        val innerPayload = GeminiCodec.buildPayload(
            system = system,
            messages = messages,
            tools = tools,
            vision = capabilities.vision,
            maxOutputTokens = maxOutputTokens,
            reasoningEffort = normalizeThinkingEffort(wireModel, reasoningEffort),
        )
        val resolvedProject = projectId().trim()
        var fresh = false
        while (true) {
            val accessToken = token.get(fresh)?.takeIf { it.isNotBlank() } ?: throw ModelError.Auth()
            val stream = GeminiCodec.GeminiStream(onText)
            val (status, retryAfter, text) = HttpSupport.stream(
                http,
                request(accessToken, resolvedProject, wireModel, innerPayload),
            ) { rawLine ->
                acceptEvent(rawLine, stream, wireModel)
            }
            if (status in 200..299) {
                if (text.isNotEmpty()) {
                    val unwrapped = unwrapResponseJson(text, wireModel)
                    return GeminiCodec.finishOrBuffered(unwrapped, stream, onText)
                }
                return stream.finish()
            }
            if (status == 401 && token.refreshable && !fresh) {
                fresh = true
                continue
            }
            throw mapError(status, retryAfter, text, wireModel)
        }
    }

    private suspend fun request(
        accessToken: String,
        resolvedProject: String,
        wireModel: String,
        innerPayload: JsonObject,
    ): Request {
        val outerEnvelope = buildJsonObject {
            put("project", resolvedProject)
            put("model", wireModel)
            put("userAgent", OUTER_USER_AGENT)
            put("requestType", REQUEST_TYPE)
            put("requestId", "agent-${UUID.randomUUID()}")
            put("request", innerPayload)
        }
        return AntigravityAuth.applyHeaders(
            Request.Builder()
                .url(streamEndpoint)
                .withHeaders(headers)
                .removeHeader("x-goog-api-key"),
            accessToken,
        )
            .header("Accept", "text/event-stream")
            .postInference(InferenceProtocol.Gemini, payload = innerPayload, wirePayload = outerEnvelope)
            .build()
    }

    private fun acceptEvent(rawLine: String, stream: GeminiCodec.GeminiStream, wireModel: String): Boolean {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed == "[DONE]") return true
        val parsed = try {
            json.parseToJsonElement(trimmed) as? JsonObject
        } catch (e: Exception) {
            throw ModelError.BadResponse("流式响应不是合法 JSON", e)
        } ?: throw ModelError.BadResponse("流式响应不是合法 JSON")
        if (parsed["response"] == null && parsed["candidates"] == null && parsed["error"] is JsonObject) {
            val err = parsed["error"] as JsonObject
            val code = (err["code"] as? JsonPrimitive)?.intOrNull ?: 500
            throw mapError(code, null, trimmed, wireModel)
        }
        val innerJson = (parsed["response"] as? JsonObject)?.toString() ?: trimmed
        return stream.accept(innerJson)
    }

    private fun unwrapResponseJson(text: String, wireModel: String): String {
        val parsed = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return text
        if (parsed["response"] == null && parsed["candidates"] == null && parsed["error"] is JsonObject) {
            val err = parsed["error"] as JsonObject
            val code = (err["code"] as? JsonPrimitive)?.intOrNull ?: 500
            throw mapError(code, null, text, wireModel)
        }
        return (parsed["response"] as? JsonObject)?.toString() ?: text
    }

    private fun mapError(status: Int, retryAfter: String?, body: String, wireModel: String): ModelError {
        val normalizedBody = runCatching {
            val el = json.parseToJsonElement(body)
            if (el is JsonArray) (el.firstOrNull() as? JsonObject)?.toString() else null
        }.getOrNull() ?: body
        return ModelHttp.geminiError(status, retryAfter, normalizedBody, cleanModel.ifEmpty { wireModel })
    }

    companion object {
        private const val OUTER_USER_AGENT = "antigravity"
        private const val REQUEST_TYPE = "agent"
        private val MODELS_PREFIX_REGEX = Regex("""^models/""", RegexOption.IGNORE_CASE)
        private val VARIANT_SUFFIX_REGEX = Regex("""-(?:low|medium|high|xhigh|tiered|agent)$""", RegexOption.IGNORE_CASE)

        fun resolveWireModel(modelId: String, reasoningEffort: String? = null): String {
            val id = modelId.trim().replace(MODELS_PREFIX_REGEX, "").trim()
            if (id.isEmpty()) return ""
            if (VARIANT_SUFFIX_REGEX.containsMatchIn(id)) return id
            val lower = id.lowercase()
            if (lower == "gemini-4-argon" || lower == "gemini-4-hydrogen") {
                val level = when (reasoningEffort?.trim()?.lowercase()) {
                    "minimal", "low" -> "low"
                    "high" -> "high"
                    "xhigh" -> "xhigh"
                    else -> "medium"
                }
                return "$lower-$level"
            }
            if (lower == "gemini-3.8-flash" || lower == "gemini-3.7-flash" || lower == "gemini-3.6-flash") {
                return "$lower-tiered"
            }
            if (lower.startsWith("gemini-3.1-pro") || lower.startsWith("gemini-pro")) {
                return "gemini-pro-agent"
            }
            return id
        }

        internal fun normalizeThinkingEffort(wireModel: String, reasoningEffort: String?): String? {
            val isGemini4 = wireModel.startsWith("gemini-4", ignoreCase = true)
            val raw = reasoningEffort?.trim()?.lowercase()
            if (raw.isNullOrEmpty() || raw == "none") {
                return if (isGemini4) "medium" else raw?.takeIf { it == "none" }
            }
            return when (raw) {
                "minimal", "low" -> "low"
                "medium" -> "medium"
                "high", "xhigh" -> "high"
                else -> if (isGemini4) "medium" else raw
            }
        }
    }
}
