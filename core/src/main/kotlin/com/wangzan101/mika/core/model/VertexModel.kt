package com.wangzan101.mika.core.model

import com.wangzan101.mika.core.oauth.VertexEndpoints
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

class VertexModel(
    projectId: String,
    location: String = VertexEndpoints.DEFAULT_LOCATION,
    private val token: BearerToken,
    model: String,
    override val capabilities: Capabilities,
    private val http: OkHttpClient,
    private val headers: Map<String, String> = emptyMap(),
    private val maxOutputTokens: Int? = null,
    private val reasoningEffort: String? = null,
    baseUrl: String? = null,
) : StreamingChatModel {
    private val cleanProjectId = VertexEndpoints.validateProjectId(projectId)
    private val cleanLocation = VertexEndpoints.normalizeLocation(location)
    private val cleanModel = VertexEndpoints.validateModelId(model)

    override val name: String get() = cleanModel

    private val endpoint: String
    private val streamEndpoint: String

    init {
        if (baseUrl == null) {
            endpoint = VertexEndpoints.generateContentUrl(cleanProjectId, cleanLocation, cleanModel, stream = false)
            streamEndpoint = VertexEndpoints.generateContentUrl(cleanProjectId, cleanLocation, cleanModel, stream = true)
        } else {
            val base = VertexEndpoints.requireHttpsOrLoopbackUrl(baseUrl, "Vertex baseUrl")
                .toString().trimEnd('/')
            val path = "/v1/projects/$cleanProjectId/locations/$cleanLocation/publishers/google/models/$cleanModel"
            endpoint = "$base$path:generateContent"
            streamEndpoint = "$base$path:streamGenerateContent?alt=sse"
        }
    }

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
        val payload = GeminiCodec.buildPayload(
            system = system,
            messages = messages,
            tools = tools,
            vision = capabilities.vision,
            maxOutputTokens = maxOutputTokens,
            reasoningEffort = reasoningEffort,
        )
        var fresh = false
        while (true) {
            val accessToken = token.get(fresh)?.takeIf { it.isNotBlank() } ?: throw ModelError.Auth()
            val (status, retryAfter, text) = HttpSupport.execute(http, request(accessToken, payload, stream = false))
            if (status in 200..299) return GeminiCodec.parseReply(text)
            if (status == 401 && token.refreshable && !fresh) {
                fresh = true
                continue
            }
            throw mapError(status, retryAfter, text)
        }
    }

    override suspend fun completeStreaming(
        system: String,
        messages: List<Message>,
        tools: List<ToolSpec>,
        onText: (String) -> Unit,
    ): ModelReply {
        val payload = GeminiCodec.buildPayload(
            system = system,
            messages = messages,
            tools = tools,
            vision = capabilities.vision,
            maxOutputTokens = maxOutputTokens,
            reasoningEffort = reasoningEffort,
        )
        var fresh = false
        while (true) {
            val accessToken = token.get(fresh)?.takeIf { it.isNotBlank() } ?: throw ModelError.Auth()
            val stream = GeminiCodec.GeminiStream(onText)
            val (status, retryAfter, text) = HttpSupport.stream(http, request(accessToken, payload, stream = true), stream::accept)
            if (status in 200..299) {
                return GeminiCodec.finishOrBuffered(text, stream, onText)
            }
            if (status == 401 && token.refreshable && !fresh) {
                fresh = true
                continue
            }
            throw mapError(status, retryAfter, text)
        }
    }

    private suspend fun request(accessToken: String, payload: JsonObject, stream: Boolean): Request =
        Request.Builder()
            .url(if (stream) streamEndpoint else endpoint)
            .withHeaders(headers)
            .removeHeader("x-goog-api-key")
            .header("Authorization", "Bearer $accessToken")
            .header("x-goog-user-project", cleanProjectId)
            .apply { if (stream) header("Accept", "text/event-stream") }
            .postInference(InferenceProtocol.Gemini, payload)
            .build()

    private fun mapError(status: Int, retryAfter: String?, body: String): ModelError =
        ModelHttp.geminiError(status, retryAfter, body, cleanModel)
}
