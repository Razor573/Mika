package com.wangzan101.mika.core.oauth

import com.wangzan101.mika.core.model.HttpSupport
import com.wangzan101.mika.core.model.ListedModel
import com.wangzan101.mika.core.model.ModelError
import com.wangzan101.mika.core.model.ModelHttp
import com.wangzan101.mika.core.model.withHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

data class AntigravityAttempt(
    val state: String,
    val verifier: String,
    val redirectUri: String,
) {
    val challenge: String get() = Pkce.challenge(verifier)

    override fun toString(): String = "AntigravityAttempt(redirectUri=$redirectUri)"
}

class AntigravityAuth(
    private val http: OkHttpClient,
    authorizeUrl: String = AUTHORIZE_URL,
    tokenUrl: String = TOKEN_URL,
    userinfoUrl: String = USERINFO_URL,
    prodEndpoint: String = PROD_ENDPOINT,
    dailyEndpoint: String = DAILY_ENDPOINT,
    private val clientId: String = CLIENT_ID,
    private val clientSecret: String = CLIENT_SECRET,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val authorizeUrl = VertexEndpoints.requireHttpsOrLoopbackUrl(authorizeUrl, "Antigravity authorizeUrl").toString()
    private val tokenUrl = VertexEndpoints.requireHttpsOrLoopbackUrl(tokenUrl, "Antigravity tokenUrl").toString()
    private val userinfoUrl = VertexEndpoints.requireHttpsOrLoopbackUrl(userinfoUrl, "Antigravity userinfoUrl").toString()
    private val prodEndpoint = VertexEndpoints.requireHttpsOrLoopbackUrl(prodEndpoint, "Antigravity prodEndpoint").toString().trimEnd('/')
    private val dailyEndpoint = VertexEndpoints.requireHttpsOrLoopbackUrl(dailyEndpoint, "Antigravity dailyEndpoint").toString().trimEnd('/')

    fun newAttempt(redirectUri: String): AntigravityAttempt =
        AntigravityAttempt(Pkce.state(), Pkce.verifier(), redirectUri)

    fun authorizeUrl(attempt: AntigravityAttempt): String = authorizeUrl.toHttpUrl().newBuilder()
        .addQueryParameter("client_id", clientId)
        .addQueryParameter("response_type", "code")
        .addQueryParameter("redirect_uri", attempt.redirectUri)
        .addQueryParameter("scope", SCOPES)
        .addQueryParameter("code_challenge", attempt.challenge)
        .addQueryParameter("code_challenge_method", "S256")
        .addQueryParameter("state", attempt.state)
        .addQueryParameter("access_type", "offline")
        .addQueryParameter("prompt", "consent")
        .build()
        .toString()

    fun accepts(params: Map<String, String>, attempt: AntigravityAttempt): Boolean {
        val state = params["state"]
        if (state != null) return state == attempt.state
        return params["code"].isNullOrEmpty() && !params["error"].isNullOrEmpty()
    }

    fun readCallback(params: Map<String, String>, attempt: AntigravityAttempt): String {
        val state = params["state"]
        if (state != null && state != attempt.state) {
            throw AccountSignInException(AccountSignInFailure.Rejected, "state")
        }
        val err = params["error"]?.takeIf { it.isNotBlank() }
        if (err != null) {
            val reason = if (err == "access_denied" || err == "consent_required") {
                AccountSignInFailure.Denied
            } else {
                AccountSignInFailure.Rejected
            }
            throw AccountSignInException(reason, err.take(40))
        }
        val code = params["code"]?.takeIf { it.isNotEmpty() }
            ?: throw AccountSignInException(AccountSignInFailure.Rejected, "no_code")
        if (state != attempt.state) {
            throw AccountSignInException(AccountSignInFailure.Rejected, "state")
        }
        return code
    }

    fun openCallback(port: Int = CALLBACK_PORT, strictPort: Boolean = false): LoopbackCallback =
        try {
            LoopbackCallback.open(port = port, path = CALLBACK_PATH)
        } catch (e: PortBusyException) {
            if (strictPort || port == 0) throw e
            LoopbackCallback.open(port = 0, path = CALLBACK_PATH)
        }

    suspend fun exchange(code: String, attempt: AntigravityAttempt): OauthTokens {
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("client_secret", clientSecret)
            .add("code", code)
            .add("code_verifier", attempt.verifier)
            .add("grant_type", "authorization_code")
            .add("redirect_uri", attempt.redirectUri)
            .build()
        val req = Request.Builder()
            .url(tokenUrl)
            .header("Accept", "application/json")
            .post(form)
            .build()
        val raw = try {
            HttpSupport.execute(http, req)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelError) {
            throw AccountSignInException(AccountSignInFailure.Network, "token_exchange", e)
        }
        if (raw.status in 500..599 || raw.status == 429) {
            throw AccountSignInException(AccountSignInFailure.Network, "http_${raw.status}")
        }
        val root = jsonObject(raw.body)
        val err = root?.str("error")
        if (raw.status != 200 || err != null || root == null) {
            val errCode = err ?: "http_${raw.status}"
            val reason = if (errCode == "invalid_grant") AccountSignInFailure.Expired else AccountSignInFailure.Rejected
            throw AccountSignInException(reason, errCode)
        }
        val access = root.str("access_token")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw AccountSignInException(AccountSignInFailure.Rejected, "no_access_token")
        return OauthTokens(
            accessToken = access,
            refreshToken = root.str("refresh_token")?.trim()?.takeIf { it.isNotEmpty() },
            idToken = root.str("id_token")?.trim()?.takeIf { it.isNotEmpty() },
            expiresInSec = (root["expires_in"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: DEFAULT_EXPIRES_SEC,
            scopes = root.str("scope")?.split(' ')?.filter { it.isNotEmpty() }.orEmpty(),
            apiBase = dailyEndpoint,
        )
    }

    suspend fun refresh(session: AccountSession): OauthTokenResult {
        if (session.refreshToken.isEmpty()) return OauthTokenResult.Rejected("no_refresh_token", terminal = true)
        val targetUrl = if (tokenUrl != TOKEN_URL) tokenUrl else session.tokenUrl.ifBlank { tokenUrl }
        val url = try {
            VertexEndpoints.requireHttpsOrLoopbackUrl(targetUrl, "Antigravity tokenUrl")
        } catch (_: IllegalArgumentException) {
            return OauthTokenResult.Rejected("bad_url", terminal = true)
        }
        val form = FormBody.Builder()
            .add("client_id", session.clientId.ifBlank { clientId })
            .add("client_secret", clientSecret)
            .add("grant_type", "refresh_token")
            .add("refresh_token", session.refreshToken)
            .build()
        val raw = try {
            HttpSupport.execute(http, Request.Builder().url(url).header("Accept", "application/json").post(form).build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelError) {
            return OauthTokenResult.Failed(e)
        }
        if (raw.status in 500..599) return OauthTokenResult.Failed(ModelError.Server(raw.status))
        if (raw.status == 429) return OauthTokenResult.Failed(ModelError.RateLimited(HttpSupport.retryAfterMs(raw.retryAfter, raw.body)))
        val root = jsonObject(raw.body)
        val err = root?.str("error")
        if (raw.status != 200 || err != null || root == null) {
            val code = err ?: "http_${raw.status}"
            return OauthTokenResult.Rejected(code, terminal = code in TERMINAL_REFRESH || raw.status == 401)
        }
        val access = root.str("access_token")?.trim()?.takeIf { it.isNotEmpty() }
            ?: return OauthTokenResult.Rejected("no_access_token", terminal = false)
        return OauthTokenResult.Ok(
            OauthTokens(
                accessToken = access,
                refreshToken = root.str("refresh_token")?.trim()?.takeIf { it.isNotEmpty() },
                idToken = root.str("id_token")?.trim()?.takeIf { it.isNotEmpty() },
                expiresInSec = (root["expires_in"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: DEFAULT_EXPIRES_SEC,
                scopes = root.str("scope")?.split(' ')?.filter { it.isNotEmpty() }.orEmpty(),
                apiBase = dailyEndpoint,
            ),
        )
    }

    suspend fun fetchUserEmail(accessToken: String, idToken: String? = null): String =
        fetchUserIdentity(accessToken, idToken).second

    private suspend fun fetchUserIdentity(accessToken: String, idToken: String?): Pair<String, String> {
        val (claimSub, claimEmail) = idClaims(idToken)
        if (claimEmail.isNotEmpty()) return claimSub to claimEmail
        val raw = try {
            HttpSupport.execute(
                http,
                Request.Builder()
                    .url(userinfoUrl)
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer $accessToken")
                    .get()
                    .build(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val root = raw?.takeIf { it.status == 200 }?.let { jsonObject(it.body) } ?: return claimSub to ""
        val sub = (root.str("sub") ?: root.str("id")).orEmpty().ifBlank { claimSub }
        val email = root.str("email")?.trim().orEmpty()
        return sub to email
    }

    suspend fun discoverProject(
        accessToken: String,
        maxAttempts: Int = DEFAULT_POLL_ATTEMPTS,
        pollDelayMs: Long = DEFAULT_POLL_DELAY_MS,
        sleep: suspend (Long) -> Unit = ::oauthSleep,
    ): String {
        val metadata = metadataJson()
        val loadBody = buildJsonObject { put("metadata", metadata) }.toString()
        val loadJson = postSetupJson("$prodEndpoint/v1internal:loadCodeAssist", accessToken, loadBody, "loadCodeAssist")
        extractProjectId(loadJson["cloudaicompanionProject"])?.let { return it }

        val allowedTiers = (loadJson["allowedTiers"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
        val defaultTier = allowedTiers.firstOrNull { it.bool("isDefault") == true && !it.str("id").isNullOrBlank() }
            ?: allowedTiers.firstOrNull { !it.str("id").isNullOrBlank() }
        val tierId = defaultTier?.str("id")?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_TIER_ID

        val onboardBody = buildJsonObject {
            put("tierId", tierId)
            put("metadata", metadata)
        }.toString()
        for (attempt in 0 until maxAttempts) {
            if (attempt > 0 && pollDelayMs > 0) {
                sleep(pollDelayMs)
            }
            val onboardJson = postSetupJson("$prodEndpoint/v1internal:onboardUser", accessToken, onboardBody, "onboardUser")
            val project = extractProjectId((onboardJson["response"] as? JsonObject)?.get("cloudaicompanionProject"))
                ?: extractProjectId(onboardJson["cloudaicompanionProject"])
            if (project != null) return project
            if (onboardJson.bool("done") == true) break
        }
        throw AccountSignInException(AccountSignInFailure.NoSubscription, SETUP_NO_PROJECT)
    }

    suspend fun resolveProjectWithFallback(
        accessToken: String,
        maxAttempts: Int = DEFAULT_POLL_ATTEMPTS,
        pollDelayMs: Long = DEFAULT_POLL_DELAY_MS,
        sleep: suspend (Long) -> Unit = ::oauthSleep,
    ): String {
        try {
            return discoverProject(accessToken, maxAttempts, pollDelayMs, sleep)
        } catch (e: AccountSignInException) {
            if (e.code != SETUP_NO_PROJECT) throw e
            val probeRoot = try {
                fetchAvailableModelsJson(accessToken, DEFAULT_PROJECT_ID)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                throw e
            }
            if (!hasNonEmptyModels(probeRoot)) throw e
            return DEFAULT_PROJECT_ID
        }
    }

    suspend fun fetchAvailableModels(
        accessToken: String,
        projectId: String = "",
        headers: Map<String, String> = emptyMap(),
    ): List<ListedModel> = parseAvailableModels(fetchAvailableModelsJson(accessToken, projectId, headers))

    private suspend fun fetchAvailableModelsJson(
        accessToken: String,
        projectId: String = "",
        headers: Map<String, String> = emptyMap(),
    ): JsonObject {
        val bodyJson = buildJsonObject {
            val clean = projectId.trim()
            if (clean.isNotEmpty()) put("project", clean)
        }.toString()
        val req = applyHeaders(
            Request.Builder()
                .url("$dailyEndpoint/v1internal:fetchAvailableModels")
                .withHeaders(headers)
                .removeHeader("x-goog-api-key")
                .header("Accept", "application/json"),
            accessToken,
        ).post(bodyJson.toRequestBody(JSON_TYPE)).build()
        val raw = HttpSupport.execute(http, req)
        if (raw.status !in 200..299) {
            throw ModelHttp.geminiError(raw.status, raw.retryAfter, raw.body, "")
        }
        return jsonObject(raw.body) ?: throw ModelError.BadResponse("Antigravity fetchAvailableModels returned invalid JSON")
    }

    fun tokens(
        load: () -> AccountSession?,
        save: (before: AccountSession, after: AccountSession) -> Unit,
        now: () -> Long = this.now,
        refreshAheadMs: Long = DEFAULT_REFRESH_AHEAD_MS,
    ): AccountTokens = AccountTokens(
        account = ACCOUNT_NAME,
        refresh = ::refresh,
        load = load,
        save = save,
        now = now,
        refreshAheadMs = refreshAheadMs,
    )

    suspend fun signIn(
        openBrowser: (String) -> Unit,
        page: (AccountSignInException?) -> String = ::defaultCallbackPage,
        port: Int = CALLBACK_PORT,
        strictPort: Boolean = false,
        timeoutMs: Long = 5 * 60_000L,
        finishTimeoutMs: Long = 30_000L,
        maxPollAttempts: Int = DEFAULT_POLL_ATTEMPTS,
        pollDelayMs: Long = DEFAULT_POLL_DELAY_MS,
        sleep: suspend (Long) -> Unit = ::oauthSleep,
    ): AccountSession {
        var outcome: Result<AccountSession>? = null
        openCallback(port = port, strictPort = strictPort).use { cb ->
            val attempt = newAttempt(cb.redirectUri(CALLBACK_HOST))
            openBrowser(authorizeUrl(attempt))
            cb.await(timeoutMs, { accepts(it, attempt) }) { params ->
                val result = finishSignIn(finishTimeoutMs) {
                    val code = readCallback(params, attempt)
                    val tokens = exchange(code, attempt)
                    val (sub, email) = fetchUserIdentity(tokens.accessToken, tokens.idToken)
                    val projectId = resolveProjectWithFallback(tokens.accessToken, maxPollAttempts, pollDelayMs, sleep)
                    AccountSession(
                        provider = PROVIDER,
                        accountId = sub.ifBlank { email.ifBlank { PROVIDER } },
                        label = email,
                        accessToken = tokens.accessToken,
                        refreshToken = tokens.refreshToken.orEmpty(),
                        expiresAtMs = now() + tokens.expiresInSec * 1000,
                        scopes = tokens.scopes,
                        apiBase = dailyEndpoint,
                        clientId = clientId,
                        issuer = ISSUER,
                        tokenUrl = tokenUrl,
                        projectId = projectId,
                    )
                }
                outcome = result
                page(result.exceptionOrNull() as AccountSignInException?)
            } ?: throw AccountSignInException(AccountSignInFailure.TimedOut)
        }
        return outcome!!.getOrThrow()
    }

    private suspend fun finishSignIn(timeoutMs: Long, block: suspend () -> AccountSession): Result<AccountSession> = try {
        Result.success(withTimeout(timeoutMs) { block() })
    } catch (e: TimeoutCancellationException) {
        Result.failure(AccountSignInException(AccountSignInFailure.Network, "timeout", e))
    } catch (e: CancellationException) {
        throw e
    } catch (e: AccountSignInException) {
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(AccountSignInException(AccountSignInFailure.Network, cause = e))
    }

    private suspend fun postSetupJson(url: String, accessToken: String, bodyJson: String, op: String): JsonObject {
        val req = applyHeaders(
            Request.Builder()
                .url(url)
                .header("Accept", "application/json"),
            accessToken,
        ).post(bodyJson.toRequestBody(JSON_TYPE)).build()
        val raw = try {
            HttpSupport.execute(http, req)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelError) {
            throw AccountSignInException(AccountSignInFailure.Network, op, e)
        }
        if (raw.status in 500..599 || raw.status == 429) {
            throw AccountSignInException(AccountSignInFailure.Network, "http_${raw.status}")
        }
        if (raw.status !in 200..299) {
            throw AccountSignInException(AccountSignInFailure.Rejected, "http_${raw.status}")
        }
        return jsonObject(raw.body) ?: throw AccountSignInException(AccountSignInFailure.Rejected, "bad_json_$op")
    }

    private fun jsonObject(body: String): JsonObject? =
        runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    private fun metadataJson(): JsonObject = buildJsonObject {
        put("ideType", "ANTIGRAVITY")
        put("platform", "PLATFORM_UNSPECIFIED")
        put("pluginType", "GEMINI")
    }

    private fun hasNonEmptyModels(root: JsonObject): Boolean = when (val models = root["models"]) {
        is JsonArray -> models.isNotEmpty()
        is JsonObject -> models.isNotEmpty()
        else -> false
    }

    companion object {
        const val PROVIDER = "antigravity"
        const val ACCOUNT_NAME = "Antigravity"
        const val ISSUER = "https://accounts.google.com"
        const val AUTHORIZE_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        const val USERINFO_URL = "https://www.googleapis.com/oauth2/v2/userinfo"
        const val PROD_ENDPOINT = "https://cloudcode-pa.googleapis.com"
        const val DAILY_ENDPOINT = "https://daily-cloudcode-pa.googleapis.com"
        const val API_BASE = DAILY_ENDPOINT
        const val DEFAULT_PROJECT_ID = "rising-fact-p41fc"
        const val CALLBACK_PORT = 51121
        const val CALLBACK_PATH = "/oauth-callback"
        const val CALLBACK_HOST = "localhost"

        const val USER_AGENT = "antigravity/hub/2.31.2 windows/amd64"
        const val GOOG_API_CLIENT = "google-cloud-sdk vscode_cloudshelleditor/0.1"
        const val CLIENT_METADATA = """{"ideType":"ANTIGRAVITY","platform":"PLATFORM_UNSPECIFIED","pluginType":"GEMINI"}"""

        val SCOPES_LIST = listOf(
            "openid",
            "https://www.googleapis.com/auth/cloud-platform",
            "https://www.googleapis.com/auth/userinfo.email",
            "https://www.googleapis.com/auth/userinfo.profile",
            "https://www.googleapis.com/auth/cclog",
            "https://www.googleapis.com/auth/experimentsandconfigs",
        )
        val SCOPES = SCOPES_LIST.joinToString(" ")

        val CLIENT_ID = fromCharCodes(
            49, 48, 55, 49, 48, 48, 54, 48, 54, 48, 53, 57, 49, 45, 116, 109, 104, 115, 115, 105, 110, 50, 104, 50, 49,
            108, 99, 114, 101, 50, 51, 53, 118, 116, 111, 108, 111, 106, 104, 52, 103, 52, 48, 51, 101, 112, 46, 97, 112,
            112, 115, 46, 103, 111, 111, 103, 108, 101, 117, 115, 101, 114, 99, 111, 110, 116, 101, 110, 116, 46, 99, 111, 109,
        )
        val CLIENT_SECRET = fromCharCodes(
            71, 79, 67, 83, 80, 88, 45, 75, 53, 56, 70, 87, 82, 52, 56, 54, 76, 100, 76, 74, 49, 109, 76, 66, 56, 115, 88,
            67, 52, 122, 54, 113, 68, 65, 102,
        )

        const val DEFAULT_POLL_ATTEMPTS = 5
        const val DEFAULT_POLL_DELAY_MS = 1_500L
        const val DEFAULT_REFRESH_AHEAD_MS = 5 * 60_000L
        private const val DEFAULT_EXPIRES_SEC = 3_600L
        private const val DEFAULT_TIER_ID = "free-tier"
        private const val SETUP_NO_PROJECT = "setup_no_project"
        private val JSON_TYPE = "application/json".toMediaType()
        private val MODELS_PREFIX_REGEX = Regex("""^models/""", RegexOption.IGNORE_CASE)
        private val VARIANT_SUFFIX_REGEX = Regex("""-(?:low|medium|high|xhigh|tiered|agent)$""", RegexOption.IGNORE_CASE)
        private val DISPLAY_VARIANT_SUFFIX_REGEX = Regex("""\s*\((?:low|medium|high|xhigh|tiered|agent)\)$""", RegexOption.IGNORE_CASE)
        private val EXCLUDED_MODEL_TOKENS = listOf(
            "claude",
            "gpt",
            "carbon",
            "image",
            "imagen",
            "veo",
            "embedding",
            "aqa",
            "tts",
            "audio",
            "live",
            "robotics",
            "computer-use",
        )

        private fun fromCharCodes(vararg codes: Int): String = buildString(codes.size) {
            for (c in codes) append(c.toChar())
        }

        internal fun applyHeaders(builder: Request.Builder, accessToken: String? = null): Request.Builder = builder.apply {
            if (!accessToken.isNullOrEmpty()) header("Authorization", "Bearer $accessToken")
            header("User-Agent", USER_AGENT)
            header("X-Goog-Api-Client", GOOG_API_CLIENT)
            header("Client-Metadata", CLIENT_METADATA)
        }

        fun collapseModelId(raw: String): String {
            val stripped = raw.trim().replace(MODELS_PREFIX_REGEX, "").trim()
            if (stripped.isEmpty()) return ""
            return stripped.replace(VARIANT_SUFFIX_REGEX, "")
        }

        fun isSupportedGeminiModel(id: String): Boolean {
            val clean = collapseModelId(id).lowercase()
            if (clean.isEmpty() || !clean.startsWith("gemini")) return false
            return EXCLUDED_MODEL_TOKENS.none { it in clean }
        }

        fun idClaims(idToken: String?): Pair<String, String> {
            val payload = idToken?.split('.')?.getOrNull(1) ?: return "" to ""
            val bytes = runCatching {
                Base64.getUrlDecoder().decode(payload.trimEnd('='))
            }.getOrNull() ?: return "" to ""
            val obj = runCatching {
                Json { ignoreUnknownKeys = true }.parseToJsonElement(String(bytes, Charsets.UTF_8)) as? JsonObject
            }.getOrNull() ?: return "" to ""
            val sub = obj.str("sub").orEmpty()
            val email = obj.str("email")?.trim().orEmpty()
            return sub to email
        }

        internal fun extractProjectId(el: JsonElement?): String? = when (el) {
            is JsonPrimitive -> el.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            is JsonObject -> el.str("id")?.trim()?.takeIf { it.isNotEmpty() }
            else -> null
        }

        internal fun parseAvailableModels(root: JsonObject): List<ListedModel> {
            val modelsEl = root["models"] ?: return emptyList()
            val entries: List<Pair<String, JsonObject?>> = when (modelsEl) {
                is JsonObject -> modelsEl.entries.map { (k, v) -> k to (v as? JsonObject) }
                is JsonArray -> modelsEl.mapNotNull { el ->
                    when (el) {
                        is JsonPrimitive -> el.takeIf { it.isString }?.contentOrNull?.let { it to null }
                        is JsonObject -> {
                            val id = el.str("name") ?: el.str("model") ?: el.str("id")
                            id?.let { it to el }
                        }
                        else -> null
                    }
                }
                else -> emptyList()
            }
            val byId = linkedMapOf<String, ListedModel>()
            for ((rawId, obj) in entries) {
                if (obj != null) {
                    val methods = (obj["supportedGenerationMethods"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    if (methods != null && "generateContent" !in methods) continue
                }
                if (!isSupportedGeminiModel(rawId)) continue
                val collapsed = collapseModelId(rawId).lowercase()
                if (collapsed.isEmpty()) continue
                val display = obj?.str("displayName")?.let(::cleanModelDisplayName).orEmpty()
                val contextTokens = obj?.let {
                    it.int("inputTokenLimit") ?: it.int("maxInputTokens") ?: it.int("contextLength") ?: it.int("maxTokens")
                }
                val thinking = obj?.let { it.bool("thinking") ?: it.bool("supportsThinking") }
                val existing = byId[collapsed]
                byId[collapsed] = if (existing == null) {
                    ListedModel(
                        id = collapsed,
                        displayName = display,
                        contextTokens = contextTokens,
                        reasoning = thinking,
                    )
                } else {
                    existing.copy(
                        displayName = existing.displayName.ifBlank { display },
                        contextTokens = existing.contextTokens ?: contextTokens,
                        reasoning = existing.reasoning ?: thinking,
                    )
                }
            }
            return byId.values.toList()
        }

        private fun cleanModelDisplayName(raw: String): String =
            raw.trim().replace(DISPLAY_VARIANT_SUFFIX_REGEX, "").trim()

        private fun defaultCallbackPage(error: AccountSignInException?): String {
            val ok = error == null
            val title = if (ok) "Signed in to Antigravity" else "Antigravity sign-in failed"
            val subtitle = if (ok) {
                "You can close this tab and return to Mika."
            } else {
                val detail = listOfNotNull(error?.reason?.name, error?.code).joinToString(": ")
                    .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                if (detail.isBlank()) "Please return to Mika and try again." else "$detail. You can close this tab."
            }
            val accent = if (ok) "#10b981" else "#ef4444"
            return "<!doctype html><html><head><meta charset=\"utf-8\"><title>$title</title>" +
                "<style>body{margin:0;height:100vh;display:flex;align-items:center;justify-content:center;background:#0b0d12;color:#f3f4f6;font-family:sans-serif}" +
                ".card{padding:28px 32px;border-radius:14px;background:#131722;border:1px solid #262c3a;max-width:420px;text-align:center}" +
                "h1{margin:0 0 8px;font-size:18px;color:$accent}p{margin:0;font-size:13px;color:#9ca3af;line-height:1.5}</style></head>" +
                "<body><div class=\"card\"><h1>$title</h1><p>$subtitle</p></div></body></html>"
        }
    }
}

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull
private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }
