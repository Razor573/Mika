package com.wangzan101.mika.core.model

import com.wangzan101.mika.core.net.ProxyFailure
import com.wangzan101.mika.core.oauth.AntigravityAuth
import com.wangzan101.mika.core.oauth.VertexEndpoints
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * 服务商列出的一个模型。能力字段为 null 表示接口没给,由调用方沿用自己的初值,这里不猜。
 *
 * 有的服务商一个 Key 下的模型走不同的接口协议,列表里给每个模型写明协议和地址(`api` 表)。
 * [endpoint] 是其中 Mika 会说的那一种,调用这个模型时按它选适配器和地址;为 null 时按服务商的接口类型。
 * [unsupportedApis] 是写明了、但 Mika 一种也不会说的协议名:这样的模型调用必然失败,不给选(见 [usable])。
 * [chat] 是接口说没说它是对话模型(`type` 是 chat / embedding / rerank…,或输出模态里有没有文字);没说为 null。
 */
data class ListedModel(
    val id: String,
    val displayName: String = "",
    val toolCalls: Boolean? = null,
    val vision: Boolean? = null,
    val contextTokens: Int? = null,
    val reasoning: Boolean? = null,
    val endpoint: ModelEndpoint? = null,
    val unsupportedApis: List<String> = emptyList(),
    val chat: Boolean? = null,
    /** 服务商明确给出的思考档位;null 表示未知,空列表表示没有可选档位。保留服务商的顺序。 */
    val reasoningEfforts: List<String>? = null,
    /**
     * 服务商写明这个模型接受的思考方式(Anthropic 列表 `capabilities.thinking.types` 里支持的那几种,
     * 如 `adaptive`、`enabled`、`disabled`);null 表示没说。
     */
    val thinkingTypes: List<String>? = null,
    /**
     * 接口写明了这个模型能走哪几种协议(有的聚合服务用 `supported_endpoints` 给出,如 `/chat/completions`、
     * `/messages`);null 表示没说。用不在其中的接口类型调用必然失败,见 [ModelProtocol.ofPath]。
     */
    val protocols: List<ModelProtocol>? = null,
) {
    val usable: Boolean get() = endpoint != null || unsupportedApis.isEmpty()
}

/** 模型列表里 `api` 表认得的接口协议;[wire] 是表里的键名。 */
enum class ModelProtocol(val wire: String) {
    /** OpenAI Chat Completions,由 [OpenAiCompatModel] 调用。 */
    ChatCompletions("chat-completions"),
    /** Anthropic Messages,由 [AnthropicModel] 调用。 */
    Messages("messages"),
    /** Gemini generateContent,由 [GeminiModel] 调用。 */
    GenerateContent("generate-content"),
    /** OpenAI Responses,由 [ResponsesModel] 调用。排在最后:同一个模型也写了前几种的,照旧用前几种。 */
    Responses("responses");

    companion object {
        fun of(wire: String?): ModelProtocol? = entries.firstOrNull { it.wire == wire }

        /**
         * 按端点路径认协议:有的聚合服务用 `supported_endpoints` 给每个模型能走哪些端点(`/chat/completions`、
         * `/messages`、`/responses`、`/v1beta/models/{模型}:generateContent`),只写路径不写协议名。认不出为 null。
         *
         * 只认 `…:generateContent` 这一种 Gemini 生成端点:裸的 `/models`(或 `/v1/models`、`/provider/v1/models`)
         * 是列模型的端点,列得出来不等于调得动,按它认定「能生成」会让不该试的模型被试一次,所以宁可认不出。
         */
        fun ofPath(path: String?): ModelProtocol? {
            val p = path?.trim()?.trimEnd('/')?.lowercase() ?: return null
            return when {
                p.endsWith("/chat/completions") -> ChatCompletions
                p.endsWith("/messages") -> Messages
                p.endsWith("/responses") -> Responses
                p.endsWith(":generatecontent") -> GenerateContent
                else -> null
            }
        }
    }
}

/**
 * 一个模型单独指定的接口:协议、列表里给的地址([baseUrl],是这个协议的根,如 `…/anthropic/v1`),
 * 以及兼容接口把思考内容放在哪个字段([reasoningField],如 `reasoning_content`;没说为 null)。
 */
data class ModelEndpoint(val protocol: ModelProtocol, val baseUrl: String, val reasoningField: String? = null) {
    /**
     * 交给适配器的 Base URL。适配器自己接版本路径:[AnthropicModel] 接 `/v1/messages`、[GeminiModel] 接
     * `/v1beta/models/…`,所以列表地址末尾已带的这一段去掉,免得重复;Chat Completions 只接 `/chat/completions`、
     * Responses 只接 `/responses`,原样用。
     */
    val adapterBaseUrl: String
        get() {
            val url = baseUrl.trim().trimEnd('/')
            return when (protocol) {
                ModelProtocol.ChatCompletions, ModelProtocol.Responses -> url
                ModelProtocol.Messages -> url.removeSuffix("/v1")
                ModelProtocol.GenerateContent -> url.removeSuffix("/v1beta")
            }
        }
}

/** 列模型为什么失败;[text] 是给人看的原因。 */
enum class ListFailure(val text: String) {
    BadUrl("Invalid endpoint URL; it must start with http:// or https://"),
    Auth("Invalid API key or missing permission"),
    Quota("Account quota exhausted"),
    RateLimited("Too many requests; please try again later"),
    Network("Could not reach this address; check your network and endpoint URL"),
    ProxyUnreachable("Could not reach the network proxy, or the proxy did not forward the request; check Settings › Network proxy"),
    ProxyAuth("Network proxy rejected the username or password; check Settings › Network proxy"),
    NotSupported("This service does not provide a model list"),
    Server("Service temporarily unavailable; please try again later"),
    BadResponse("Could not understand the response; check the endpoint URL and API format"),
    ;

    companion object {
        /** 请求没发成:代理那一段的问题照实说,别的算连不上。 */
        fun of(e: Throwable): ListFailure = when ((e as? ModelError.Proxy)?.failure) {
            ProxyFailure.Unreachable -> ProxyUnreachable
            ProxyFailure.AuthFailed -> ProxyAuth
            null -> Network
        }
    }
}

sealed interface ModelListResult {
    data class Ok(val models: List<ListedModel>) : ModelListResult
    data class Failed(val reason: ListFailure, val detail: String = "") : ModelListResult {
        val message: String get() = if (detail.isEmpty()) reason.text else "${reason.text} ($detail)"
    }
}

/**
 * 用各家公开的「列出模型」接口取可用模型:
 * - OpenAI 兼容:`GET {base}/models`(Bearer)。只给 id 的照实返回;带 `context_length`、
 *   `architecture.input_modalities`、`supported_parameters` 等字段的(部分聚合服务会给)才填能力。
 *   也认另一种写法:`capabilities.tools.supported`、`capabilities.reasoning.supported`、`limit.context`、
 *   `modalities.input`(有 `image/…` 即能看图),以及按协议给地址的 `api` 表(见 [ListedModel.endpoint])。
 *   用 ChatGPT 账号列出的是 `models` 表:名字在 `slug`、显示名在 `display_name`,只留 `visibility` 为 `list` 的、保持服务端的次序;
 *   给了 `input_modalities`、`context_window`、`supported_reasoning_levels` 的照填。
 * - Anthropic:`GET {base}/v1/models`,按 `after_id` 翻页;`max_input_tokens` 是上下文,
 *   `capabilities.image_input` / `capabilities.thinking` 是看图和推理。不给工具调用信息。
 *   思考档位按 `capabilities.effort` 和 `capabilities.thinking.types` 逐项照填(见 [anthropicEfforts])。
 * - Gemini:`GET {base}/v1beta/models`,按 `pageToken` 翻页;只留能 `generateContent` 的,
 *   `inputTokenLimit` 是上下文,`thinking` 是推理。不给工具调用和看图信息,也不给思考档位。
 */
class ModelCatalog(private val http: OkHttpClient) {
    // 各方法的 headers:每个请求都带的头(见 [ClientHeaders]);Key 的头由这里自己加
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun openAiCompat(baseUrl: String, apiKey: String?, headers: Map<String, String> = emptyMap()): ModelListResult = run {
        val url = endpoint(baseUrl, "/models") ?: return@run badUrl()
        val req = Request.Builder().url(url).get().withHeaders(headers)
            .apply { if (!apiKey.isNullOrEmpty()) header("Authorization", "Bearer $apiKey") }.build()
        val root = fetch(req).getOrElse { return@run it.asFailure() }
        val data = (root["data"] ?: root["models"]) as? JsonArray ?: return@run ModelListResult.Failed(ListFailure.BadResponse)
        ModelListResult.Ok(data.mapNotNull { (it as? JsonObject)?.let(::openAiModel) }.distinctBy { it.id })
    }

    suspend fun anthropic(baseUrl: String, apiKey: String, headers: Map<String, String> = emptyMap()): ModelListResult = run {
        val base = endpoint(baseUrl, "/v1/models") ?: return@run badUrl()
        val all = mutableListOf<ListedModel>()
        var after: String? = null
        repeat(MAX_PAGES) {
            val url = base.newBuilder().addQueryParameter("limit", "1000")
                .apply { if (after != null) addQueryParameter("after_id", after) }.build()
            val req = Request.Builder().url(url).get().withHeaders(headers)
                .header("x-api-key", apiKey).header("anthropic-version", "2023-06-01").build()
            val root = fetch(req).getOrElse { return@run it.asFailure() }
            val data = root["data"] as? JsonArray ?: return@run ModelListResult.Failed(ListFailure.BadResponse)
            all += data.mapNotNull { (it as? JsonObject)?.let(::anthropicModel) }
            after = root.str("last_id")
            if (root.bool("has_more") != true || after == null) return@run ModelListResult.Ok(all.distinctBy { it.id })
        }
        ModelListResult.Ok(all.distinctBy { it.id })
    }

    suspend fun gemini(baseUrl: String, apiKey: String, headers: Map<String, String> = emptyMap()): ModelListResult = run {
        val base = endpoint(baseUrl, "/v1beta/models") ?: return@run badUrl()
        val all = mutableListOf<ListedModel>()
        var token: String? = null
        repeat(MAX_PAGES) {
            val url = base.newBuilder().addQueryParameter("pageSize", "1000")
                .apply { if (token != null) addQueryParameter("pageToken", token) }.build()
            val req = Request.Builder().url(url).get().withHeaders(headers).header("x-goog-api-key", apiKey).build()
            val root = fetch(req).getOrElse { return@run it.asFailure() }
            val data = root["models"] as? JsonArray ?: JsonArray(emptyList())
            all += data.mapNotNull { (it as? JsonObject)?.let(::geminiModel) }
            token = root.str("nextPageToken")?.takeIf { it.isNotEmpty() }
            if (token == null) return@run ModelListResult.Ok(all.distinctBy { it.id })
        }
        ModelListResult.Ok(all.distinctBy { it.id })
    }

    suspend fun vertex(
        token: BearerToken,
        location: String = VertexEndpoints.DEFAULT_LOCATION,
        projectId: String? = null,
        headers: Map<String, String> = emptyMap(),
        baseUrl: String? = null,
    ): ModelListResult = run {
        val cleanLocation = try {
            VertexEndpoints.normalizeLocation(location)
        } catch (_: IllegalArgumentException) {
            return@run badUrl()
        }
        val cleanProject = try {
            projectId?.trim()?.takeIf { it.isNotEmpty() }?.let(VertexEndpoints::validateProjectId)
        } catch (_: IllegalArgumentException) {
            return@run badUrl()
        }
        val overrideBase = if (baseUrl != null) {
            try {
                VertexEndpoints.requireHttpsOrLoopbackUrl(baseUrl, "Vertex baseUrl").toString().trimEnd('/')
            } catch (_: IllegalArgumentException) {
                return@run badUrl()
            }
        } else {
            null
        }

        var fresh = false
        var bearer = try {
            token.get(false)?.takeIf { it.isNotBlank() } ?: return@run ModelListResult.Failed(ListFailure.Auth)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelError) {
            return@run failureFor(e)
        }

        val byId = linkedMapOf<String, ListedModel>().apply {
            CURATED_VERTEX_MODELS.forEach { put(it.id, it) }
        }
        var pageToken: String? = null
        repeat(MAX_PAGES) {
            val url = if (overrideBase == null) {
                VertexEndpoints.publisherModelsUrl(cleanLocation, pageSize = 100, pageToken = pageToken)
            } else {
                "$overrideBase/v1/publishers/google/models".toHttpUrlOrNull()!!.newBuilder()
                    .addQueryParameter("pageSize", "100")
                    .apply { if (pageToken != null) addQueryParameter("pageToken", pageToken) }
                    .build()
                    .toString()
            }
            val (root, updatedAuth) = fetchVertexPage(url, token, bearer, fresh, cleanProject, headers).getOrElse { err ->
                val failed = err.asFailure()
                if (failed.reason == ListFailure.NotSupported) {
                    return@run ModelListResult.Ok(byId.values.toList())
                }
                return@run failed
            }
            bearer = updatedAuth.first
            fresh = updatedAuth.second

            val data = (root["publisherModels"] ?: root["models"]) as? JsonArray ?: JsonArray(emptyList())
            data.mapNotNull(::vertexModel).forEach { model ->
                val existing = byId[model.id]
                byId[model.id] = if (existing == null) {
                    model
                } else {
                    existing.copy(
                        displayName = model.displayName.ifBlank { existing.displayName },
                        contextTokens = model.contextTokens ?: existing.contextTokens,
                        reasoning = model.reasoning ?: existing.reasoning,
                    )
                }
            }
            pageToken = root.str("nextPageToken")?.takeIf { it.isNotEmpty() }
            if (pageToken == null) return@run ModelListResult.Ok(byId.values.toList())
        }
        ModelListResult.Ok(byId.values.toList())
    }

    suspend fun antigravity(
        token: BearerToken,
        projectId: String,
        headers: Map<String, String> = emptyMap(),
        baseUrl: String? = null,
    ): ModelListResult = antigravity(token, { projectId }, headers, baseUrl)

    suspend fun antigravity(
        token: BearerToken,
        projectId: () -> String = { "" },
        headers: Map<String, String> = emptyMap(),
        baseUrl: String? = null,
    ): ModelListResult = run {
        val endpointBase = if (baseUrl != null) {
            try {
                VertexEndpoints.requireHttpsOrLoopbackUrl(baseUrl, "Antigravity baseUrl").toString().trimEnd('/')
            } catch (_: IllegalArgumentException) {
                return@run badUrl()
            }
        } else {
            AntigravityAuth.DAILY_ENDPOINT
        }

        var bearer = try {
            token.get(false)?.takeIf { it.isNotBlank() } ?: return@run ModelListResult.Failed(ListFailure.Auth)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelError) {
            return@run failureFor(e)
        }

        val byId = linkedMapOf<String, ListedModel>().apply {
            CURATED_ANTIGRAVITY_MODELS.forEach { put(it.id, it) }
        }
        val cleanProject = projectId().trim()
        val bodyJson = buildJsonObject {
            if (cleanProject.isNotEmpty()) put("project", cleanProject)
        }.toString()
        val url = "$endpointBase/v1internal:fetchAvailableModels"

        val root = fetchAntigravityModels(url, bodyJson, token, bearer, headers).getOrElse { err ->
            val failed = err.asFailure()
            if (failed.reason == ListFailure.NotSupported) {
                return@run ModelListResult.Ok(byId.values.toList())
            }
            return@run failed
        }

        AntigravityAuth.parseAvailableModels(root).forEach { model ->
            val existing = byId[model.id]
            byId[model.id] = if (existing == null) {
                model
            } else {
                existing.copy(
                    displayName = model.displayName.ifBlank { existing.displayName },
                    contextTokens = model.contextTokens ?: existing.contextTokens,
                    reasoning = model.reasoning ?: existing.reasoning,
                )
            }
        }
        ModelListResult.Ok(byId.values.toList())
    }

    private suspend fun fetchAntigravityModels(
        url: String,
        bodyJson: String,
        token: BearerToken,
        initialBearer: String,
        headers: Map<String, String>,
    ): Result<JsonObject> {
        var bearer = initialBearer
        var fresh = false
        while (true) {
            val req = AntigravityAuth.applyHeaders(
                Request.Builder()
                    .url(url)
                    .withHeaders(headers)
                    .removeHeader("x-goog-api-key"),
                bearer,
            )
                .header("Accept", "application/json")
                .post(bodyJson.toRequestBody(JSON_TYPE))
                .build()
            val raw = try {
                HttpSupport.execute(http, req)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return Result.failure(Failure(ModelListResult.Failed(ListFailure.of(e))))
            }
            if (raw.status == 401 && token.refreshable && !fresh) {
                fresh = true
                bearer = try {
                    token.get(true)?.takeIf { it.isNotBlank() }
                        ?: return Result.failure(Failure(ModelListResult.Failed(ListFailure.Auth)))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ModelError) {
                    return Result.failure(Failure(failureFor(e)))
                }
                continue
            }
            if (raw.status !in 200..299) return Result.failure(Failure(failureFor(raw.status, raw.body)))
            val root = try { json.parseToJsonElement(raw.body).jsonObject } catch (_: Exception) { null }
                ?: return Result.failure(Failure(ModelListResult.Failed(ListFailure.BadResponse)))
            return Result.success(root)
        }
    }

    private fun openAiModel(o: JsonObject): ListedModel? {
        // 用 ChatGPT 账号列模型时(`models` 表)名字在 `slug`、显示名在 `display_name`,只留 `visibility` 为 list 的
        o.str("visibility")?.let { if (it != "list") return null }
        val id = (o.str("id") ?: o.str("slug"))?.takeIf { it.isNotBlank() } ?: return null
        val arch = o["architecture"] as? JsonObject
        val inputs = (arch?.get("input_modalities") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val modality = arch?.str("modality")?.substringBefore("->")
        val outputs = ((arch?.get("output_modalities") ?: o.obj("modalities")?.get("output")) as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: arch?.str("modality")?.takeIf { "->" in it }?.substringAfter("->")?.split('+')
        val params = (o["supported_parameters"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        val caps = o["capabilities"] as? JsonObject
        val modalIn = ((o.obj("modalities")?.get("input") ?: o["input_modalities"]) as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        // DeepSeek 的列表写在 `effort.supported_levels`(另有 `default_level`,这里不用)
        val reasoningLevels = (o["supported_reasoning_levels"] ?: caps?.obj("reasoning")?.get("effort")
            ?: o.obj("effort")?.get("supported_levels")) as? JsonArray
        val efforts = reasoningLevels?.mapNotNull { level ->
            (((level as? JsonObject)?.get("effort") ?: level) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
                ?.trim()?.takeIf { it.isNotEmpty() }
        }?.distinct()
        val api = o.obj("api")
        // 有的聚合服务不给 `api` 表,改用 `supported_endpoints` 写这个模型能走哪几个端点
        val protocols = (o["supported_endpoints"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(ModelProtocol::ofPath) }
            ?.distinct()
            ?.takeIf { it.isNotEmpty() }
        // 一个模型可能写了几种协议:按 Chat Completions、Messages、generateContent、Responses 的次序取第一种会说的
        val endpoint = api?.let { a ->
            ModelProtocol.entries.firstNotNullOfOrNull { p ->
                val e = a.obj(p.wire) ?: return@firstNotNullOfOrNull null
                val url = e.str("base_url")?.takeIf { it.isNotBlank() } ?: return@firstNotNullOfOrNull null
                ModelEndpoint(p, url, e.obj("compatibility")?.str("reasoning_field"))
            }
        }
        return ListedModel(
            id = id,
            displayName = (o.str("name") ?: o.str("display_name")).orEmpty(),
            toolCalls = params?.let { "tools" in it } ?: caps?.obj("tools")?.bool("supported")
                ?: caps?.bool("function_calling") ?: caps?.bool("tool_calls"),
            vision = inputs?.let { "image" in it } ?: modalIn?.let { m -> m.any { it == "image" || it.startsWith("image/") } }
                ?: modality?.let { "image" in it } ?: o.bool("supports_image_in") ?: caps?.bool("vision"),
            contextTokens = o.int("context_length") ?: o.int("context_window") ?: o.int("max_context_length")
                ?: o.obj("limit")?.int("context"),
            reasoning = params?.let { "reasoning" in it || "include_reasoning" in it } ?: caps?.obj("reasoning")?.bool("supported")
                ?: o.bool("supports_reasoning") ?: reasoningLevels?.let { it.isNotEmpty() },
            endpoint = endpoint,
            reasoningEfforts = efforts,
            unsupportedApis = if (endpoint == null) api?.keys?.toList().orEmpty() else emptyList(),
            protocols = protocols,
            chat = when (o.str("type")?.lowercase()) {
                in CHAT_TYPES -> true
                in NON_CHAT_TYPES -> false
                // 只出图、出声音的不是对话模型;出文字的不另记
                else -> outputs?.takeIf { "text" !in it }?.let { false }
            },
        )
    }

    private fun anthropicModel(o: JsonObject): ListedModel? {
        val id = o.str("id")?.takeIf { it.isNotBlank() } ?: return null
        val caps = o["capabilities"] as? JsonObject
        val thinking = caps?.obj("thinking")
        val types = thinking?.obj("types")?.supportedKeys()
        return ListedModel(
            id = id,
            displayName = o.str("display_name").orEmpty(),
            vision = (caps?.get("image_input") as? JsonObject)?.bool("supported"),
            contextTokens = o.int("max_input_tokens"),
            reasoning = thinking?.bool("supported"),
            reasoningEfforts = types?.let { anthropicEfforts(caps?.obj("effort"), it) },
            thinkingTypes = types,
        )
    }

    /**
     * Anthropic 模型可选的思考档位:只在它接受自适应思考(`adaptive`,档位放 `output_config.effort`)时给,
     * 档位是 `capabilities.effort` 里标了支持的那几级;也接受关掉思考(`disabled`)的,前面加一档 `none`。
     * 只接受按预算思考(`enabled` + `budget_tokens`)或不分档位的,没有可选档位。
     */
    private fun anthropicEfforts(effort: JsonObject?, types: List<String>): List<String> {
        val levels = effort?.takeIf { it.bool("supported") == true }?.supportedKeys().orEmpty()
            .sortedBy { EFFORT_ORDER.indexOf(it).takeIf { i -> i >= 0 } ?: EFFORT_ORDER.size }
        if ("adaptive" !in types || levels.isEmpty()) return emptyList()
        return listOfNotNull("none".takeIf { "disabled" in types }) + levels
    }

    /** `{"a":{"supported":true},"b":{"supported":false},"supported":true}` 里标了支持的子项:`[a]`。 */
    private fun JsonObject.supportedKeys(): List<String> = keys.filter { (this[it] as? JsonObject)?.bool("supported") == true }

    private fun geminiModel(o: JsonObject): ListedModel? {
        val id = o.str("name")?.removePrefix("models/")?.takeIf { it.isNotBlank() } ?: return null
        val methods = (o["supportedGenerationMethods"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        if (methods != null && "generateContent" !in methods) return null
        return ListedModel(
            id = id,
            displayName = o.str("displayName").orEmpty(),
            contextTokens = o.int("inputTokenLimit"),
            reasoning = o.bool("thinking"),
        )
    }

    private fun vertexModel(el: JsonElement): ListedModel? {
        val rawId = when (el) {
            is JsonPrimitive -> el.takeIf { it.isString }?.contentOrNull
            is JsonObject -> {
                val methods = (el["supportedGenerationMethods"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                if (methods != null && "generateContent" !in methods) return null
                el.str("name") ?: el.str("model") ?: el.str("id")
            }
            else -> null
        } ?: return null
        val id = VertexEndpoints.cleanModelId(rawId)
        if (id.isEmpty()) return null
        val lower = id.lowercase()
        if ("gemini" !in lower) return null
        if (VERTEX_EXCLUDED_TOKENS.any { it in lower }) return null
        val obj = el as? JsonObject
        return ListedModel(
            id = id,
            displayName = obj?.str("displayName").orEmpty(),
            contextTokens = obj?.int("inputTokenLimit"),
            reasoning = obj?.bool("thinking"),
        )
    }

    private suspend fun fetchVertexPage(
        url: String,
        token: BearerToken,
        initialBearer: String,
        initialFresh: Boolean,
        projectId: String?,
        headers: Map<String, String>,
    ): Result<Pair<JsonObject, Pair<String, Boolean>>> {
        var bearer = initialBearer
        var fresh = initialFresh
        while (true) {
            val req = Request.Builder()
                .url(url)
                .get()
                .withHeaders(headers)
                .removeHeader("x-goog-api-key")
                .header("Authorization", "Bearer $bearer")
                .apply { if (projectId != null) header("x-goog-user-project", projectId) }
                .build()
            val raw = try {
                HttpSupport.execute(http, req)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return Result.failure(Failure(ModelListResult.Failed(ListFailure.of(e))))
            }
            if (raw.status == 401 && token.refreshable && !fresh) {
                fresh = true
                bearer = try {
                    token.get(true)?.takeIf { it.isNotBlank() }
                        ?: return Result.failure(Failure(ModelListResult.Failed(ListFailure.Auth)))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ModelError) {
                    return Result.failure(Failure(failureFor(e)))
                }
                continue
            }
            if (raw.status !in 200..299) return Result.failure(Failure(failureFor(raw.status, raw.body)))
            val root = try { json.parseToJsonElement(raw.body).jsonObject } catch (_: Exception) { null }
                ?: return Result.failure(Failure(ModelListResult.Failed(ListFailure.BadResponse)))
            return Result.success(root to (bearer to fresh))
        }
    }

    private fun failureFor(e: ModelError): ModelListResult.Failed = when (e) {
        is ModelError.Auth, is ModelError.SignInExpired -> ModelListResult.Failed(ListFailure.Auth)
        is ModelError.QuotaExceeded -> ModelListResult.Failed(ListFailure.Quota)
        is ModelError.RateLimited -> ModelListResult.Failed(ListFailure.RateLimited)
        is ModelError.Server -> ModelListResult.Failed(ListFailure.Server, "HTTP ${e.status}")
        is ModelError.BadResponse -> ModelListResult.Failed(ListFailure.BadResponse)
        is ModelError.Proxy -> ModelListResult.Failed(ListFailure.of(e))
        else -> ModelListResult.Failed(ListFailure.Network)
    }

    private class Failure(val result: ModelListResult.Failed) : Exception(result.message)

    private fun Throwable.asFailure(): ModelListResult.Failed =
        (this as? Failure)?.result ?: ModelListResult.Failed(ListFailure.Network)

    private suspend fun fetch(req: Request): Result<JsonObject> {
        val raw = try {
            HttpSupport.execute(http, req)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(Failure(ModelListResult.Failed(ListFailure.of(e))))
        }
        if (raw.status !in 200..299) return Result.failure(Failure(failureFor(raw.status, raw.body)))
        val root = try { json.parseToJsonElement(raw.body).jsonObject } catch (e: Exception) { null }
            ?: return Result.failure(Failure(ModelListResult.Failed(ListFailure.BadResponse)))
        return Result.success(root)
    }

    private fun failureFor(status: Int, body: String): ModelListResult.Failed = when {
        status == 401 || status == 403 -> ModelListResult.Failed(ListFailure.Auth)
        // Gemini 对无效 Key 回 400 INVALID_ARGUMENT,原因写在内容里
        status == 400 && (body.contains("API_KEY_INVALID") || body.contains("API key not valid")) -> ModelListResult.Failed(ListFailure.Auth)
        status == 402 || body.contains("insufficient_quota") -> ModelListResult.Failed(ListFailure.Quota)
        status == 429 -> ModelListResult.Failed(ListFailure.RateLimited)
        status == 404 || status == 405 || status == 501 -> ModelListResult.Failed(ListFailure.NotSupported)
        status in 500..599 -> ModelListResult.Failed(ListFailure.Server, "HTTP $status")
        else -> ModelListResult.Failed(ListFailure.BadResponse, "HTTP $status")
    }

    private fun badUrl() = ModelListResult.Failed(ListFailure.BadUrl)

    private fun endpoint(baseUrl: String, path: String) = (baseUrl.trim().trimEnd('/') + path).toHttpUrlOrNull()

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }

    companion object {
        /** 翻页上限:防止服务端一直给下一页。 */
        private const val MAX_PAGES = 10
        /** Anthropic 的 `capabilities.effort` 按字母排;界面按由浅到深排,不认得的排最后。 */
        private val EFFORT_ORDER = listOf("low", "medium", "high", "xhigh", "max")
        /** 列表里 `type` 一项的写法:是对话模型的,和不是的(向量、重排、画图、语音、审核)。 */
        private val CHAT_TYPES = setOf("chat", "llm", "language", "text", "vlm", "code")
        private val NON_CHAT_TYPES = setOf(
            "embedding", "embeddings", "rerank", "reranker", "image", "images", "video", "audio", "tts", "stt", "asr",
            "speech", "transcription", "moderation",
        )
        private val VERTEX_EXCLUDED_TOKENS = listOf("embedding", "imagen", "veo", "tts", "live", "audio", "transcribe")
        private val CURATED_VERTEX_MODELS = listOf(
            ListedModel(id = "gemini-4-hydrogen", displayName = "Gemini 4 Hydrogen"),
            ListedModel(id = "gemini-4-argon", displayName = "Gemini 4 Argon"),
            ListedModel(id = "gemini-4-carbon", displayName = "Gemini 4 Carbon"),
            ListedModel(id = "gemini-3.8-flash", displayName = "Gemini 3.8 Flash"),
        )
        private val JSON_TYPE = "application/json".toMediaType()
        val CURATED_ANTIGRAVITY_MODELS = listOf(
            ListedModel(id = "gemini-4-hydrogen", displayName = "Gemini 4 Hydrogen"),
            ListedModel(id = "gemini-4-argon", displayName = "Gemini 4 Argon"),
            ListedModel(id = "gemini-3.8-flash", displayName = "Gemini 3.8 Flash"),
        )
    }
}
