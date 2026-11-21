package com.wangzan101.mika.core.model

import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Gemini generateContent 原生接口。
 * - 角色只有 user / model;相邻同角色合并。历史里的 System 消息并入 systemInstruction。
 * - Gemini 不给调用编号:合成 "gemini-<序号>-<名称>"(响应带 id 则用之);
 *   回传 functionResponse 时按 callId 到历史里的 ToolCall 找函数名。
 * - thought 部分解析为 Thinking;选了思考强度时用 `generationConfig.thinkingConfig.thinkingLevel` 控制深度。
 */
class GeminiModel(
    baseUrl: String = "https://generativelanguage.googleapis.com",
    private val apiKey: String,
    private val model: String,
    override val capabilities: Capabilities,
    private val http: OkHttpClient,
    /** 每个请求都带的头(见 [ClientHeaders]);Key 的头由适配器自己加。 */
    private val headers: Map<String, String> = emptyMap(),
    /** 回复最多多少 token(`generationConfig.maxOutputTokens`);null 不限,按服务端默认。 */
    private val maxOutputTokens: Int? = null,
    /** 用户选的思考档位;null 不发,`none` 尽量关掉思考,其余作为 `thinkingConfig.thinkingLevel` 发出。 */
    private val reasoningEffort: String? = null,
    private val googleSearch: Boolean = false,
    private val forceToolCall: Boolean = false,
    private val keySupplier: (() -> String?)? = null,
    /** 密钥被上游拒绝(429/401/403)时带上 HTTP 状态码回调;接线处按状态决定冷却(429)还是标耗尽(401/403)。 */
    private val onKeyRejected: ((key: String, retryAfterMs: Long?, status: Int) -> Unit)? = null,
) : StreamingChatModel {
    override val name: String get() = model
    private val endpoint = baseUrl.trimEnd('/') + "/v1beta/models/$model:generateContent"
    private val streamEndpoint = baseUrl.trimEnd('/') + "/v1beta/models/$model:streamGenerateContent?alt=sse"

    private fun resolveKey(): String = keySupplier?.invoke()?.takeIf { it.isNotBlank() } ?: apiKey

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
        complete(system, messages, tools, CallOptions.DEFAULT)

    override suspend fun complete(
        system: String,
        messages: List<Message>,
        tools: List<ToolSpec>,
        options: CallOptions,
    ): ModelReply {
        val (status, retryAfter, text) = requestWithKeyRotation(::resolveKey, onKeyRejected) { key ->
            HttpSupport.execute(http, request(system, messages, tools, stream = false, options = options, key = key.orEmpty()))
        }
        if (status !in 200..299) throw mapError(status, retryAfter, text)
        return GeminiCodec.parseReply(text)
    }

    override suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
    ): ModelReply = completeStreaming(system, messages, tools, CallOptions.DEFAULT, onText)

    override suspend fun completeStreaming(
        system: String,
        messages: List<Message>,
        tools: List<ToolSpec>,
        options: CallOptions,
        onText: (String) -> Unit,
    ): ModelReply {
        var stream = GeminiCodec.GeminiStream(onText)
        val (status, retryAfter, text) = requestWithKeyRotation(::resolveKey, onKeyRejected) { key ->
            stream = GeminiCodec.GeminiStream(onText)
            HttpSupport.stream(http, request(system, messages, tools, stream = true, options = options, key = key.orEmpty()), stream::accept)
        }
        if (status !in 200..299) throw mapError(status, retryAfter, text)
        return GeminiCodec.finishOrBuffered(text, stream, onText)
    }

    private suspend fun request(
        system: String,
        messages: List<Message>,
        tools: List<ToolSpec>,
        stream: Boolean,
        options: CallOptions = CallOptions.DEFAULT,
        key: String = resolveKey(),
    ): Request {
        val force = forceToolCall || options.forceToolCall
        val effectiveForce = force && tools.isNotEmpty()
        val effectiveSystem = if (force) LightPrompt.ensure(system) else system
        val effectiveMaxOut = options.maxOutputTokens ?: maxOutputTokens
        val payload = GeminiCodec.buildPayload(
            system = effectiveSystem,
            messages = messages,
            tools = tools,
            vision = capabilities.vision,
            maxOutputTokens = effectiveMaxOut,
            reasoningEffort = reasoningEffort,
            googleSearch = googleSearch && tools.isEmpty(),
            forceToolCall = effectiveForce,
        )
        return Request.Builder().url(if (stream) streamEndpoint else endpoint)
            .withHeaders(headers)
            .headerOrReject("x-goog-api-key", key)
            .postInference(InferenceProtocol.Gemini, payload)
            .build()
    }

    private fun mapError(status: Int, retryAfter: String?, body: String): ModelError = ModelHttp.geminiError(status, retryAfter, body, model)
}
