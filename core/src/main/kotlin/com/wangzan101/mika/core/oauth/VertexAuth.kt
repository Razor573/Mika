package com.wangzan101.mika.core.oauth

import com.wangzan101.mika.core.model.BearerToken
import com.wangzan101.mika.core.model.HttpSupport
import com.wangzan101.mika.core.model.ModelError
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

data class VertexServiceAccount(
    val projectId: String,
    val clientEmail: String,
    val privateKeyPem: String,
    val tokenUri: String = DEFAULT_TOKEN_URI,
) {
    val displaySummary: String
        get() = if (projectId.isNotBlank()) "$clientEmail ($projectId)" else clientEmail

    fun fingerprintSha256(): String =
        fingerprintSha256("$projectId\n$clientEmail\n$tokenUri\n$privateKeyPem")

    override fun toString(): String =
        "VertexServiceAccount(projectId=$projectId, clientEmail=$clientEmail, tokenUri=$tokenUri)"

    companion object {
        const val DEFAULT_TOKEN_URI = "https://oauth2.googleapis.com/token"
        const val VERTEX_SCOPE = "https://www.googleapis.com/auth/cloud-platform"

        private val json = Json { ignoreUnknownKeys = true }
        private const val PEM_HEADER = "-----BEGIN PRIVATE KEY-----"
        private const val PEM_FOOTER = "-----END PRIVATE KEY-----"

        fun parse(rawJson: String): VertexServiceAccount {
            val trimmed = rawJson.trim()
            if (trimmed.isEmpty()) throw IllegalArgumentException("Service Account JSON is empty.")
            val root = try {
                json.parseToJsonElement(trimmed) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: throw IllegalArgumentException("Invalid Service Account JSON.")

            val type = root.str("type")
            if (type != "service_account") {
                throw IllegalArgumentException("Service Account JSON must have type \"service_account\".")
            }

            val clientEmail = root.str("client_email")?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("Service Account JSON is missing \"client_email\".")

            val privateKeyPem = root.str("private_key")?.takeIf { hasValidPemEnvelope(it) }
                ?: throw IllegalArgumentException("Service Account JSON is missing a valid PKCS#8 PEM \"private_key\".")

            val tokenUriElement = root["token_uri"]
            val tokenUri = when (tokenUriElement) {
                null, JsonNull -> DEFAULT_TOKEN_URI
                is JsonPrimitive -> {
                    val rawUri = tokenUriElement.takeIf { it.isString }?.contentOrNull?.trim()
                        ?: throw IllegalArgumentException("Service Account \"token_uri\" must be a valid HTTPS URL.")
                    VertexEndpoints.requireHttpsUrl(rawUri, "Service Account token_uri").toString()
                }
                else -> throw IllegalArgumentException("Service Account \"token_uri\" must be a valid HTTPS URL.")
            }

            val projectId = root.str("project_id")?.trim().orEmpty()
            return VertexServiceAccount(
                projectId = projectId,
                clientEmail = clientEmail,
                privateKeyPem = privateKeyPem,
                tokenUri = tokenUri,
            )
        }

        fun fingerprintSha256(rawJson: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(rawJson.trim().toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }

        private fun hasValidPemEnvelope(pem: String): Boolean {
            val start = pem.indexOf(PEM_HEADER)
            if (start < 0) return false
            val bodyStart = start + PEM_HEADER.length
            val end = pem.indexOf(PEM_FOOTER, startIndex = bodyStart)
            if (end < 0) return false
            return pem.substring(bodyStart, end).replace("\\n", "").isNotBlank()
        }
    }
}

object VertexEndpoints {
    const val DEFAULT_LOCATION = "global"
    const val GLOBAL_HOST = "https://aiplatform.googleapis.com"

    private val LOCATION_REGEX = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$")
    private val PROJECT_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]*$")
    private val MODEL_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")

    fun normalizeLocation(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return DEFAULT_LOCATION
        val normalized = trimmed.lowercase()
        if (!LOCATION_REGEX.matches(normalized)) {
            throw IllegalArgumentException("Invalid Vertex AI location.")
        }
        return normalized
    }

    fun validateProjectId(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || ".." in trimmed || !PROJECT_REGEX.matches(trimmed)) {
            throw IllegalArgumentException("Invalid Vertex AI project ID.")
        }
        return trimmed
    }

    fun cleanModelId(raw: String): String =
        raw.trim()
            .removePrefix("publishers/google/models/")
            .removePrefix("models/")
            .trim()

    fun validateModelId(raw: String): String {
        val clean = cleanModelId(raw)
        if (clean.isEmpty() || ".." in clean || !MODEL_REGEX.matches(clean)) {
            throw IllegalArgumentException("Invalid Vertex AI model ID.")
        }
        return clean
    }

    fun host(location: String): String {
        val loc = normalizeLocation(location)
        return if (loc == DEFAULT_LOCATION) GLOBAL_HOST else "https://$loc-aiplatform.googleapis.com"
    }

    fun generateContentUrl(
        projectId: String,
        location: String = DEFAULT_LOCATION,
        model: String,
        stream: Boolean = false,
    ): String {
        val proj = validateProjectId(projectId)
        val loc = normalizeLocation(location)
        val cleanModel = validateModelId(model)
        val action = if (stream) "streamGenerateContent?alt=sse" else "generateContent"
        val url = "${host(loc)}/v1/projects/$proj/locations/$loc/publishers/google/models/$cleanModel:$action"
        return requireHttpsUrl(url, "Vertex endpoint URL").toString()
    }

    fun streamGenerateContentUrl(
        projectId: String,
        location: String = DEFAULT_LOCATION,
        model: String,
    ): String = generateContentUrl(projectId, location, model, stream = true)

    fun publisherModelsUrl(
        location: String = DEFAULT_LOCATION,
        pageSize: Int = 100,
        pageToken: String? = null,
    ): String {
        val loc = normalizeLocation(location)
        val base = "${host(loc)}/v1/publishers/google/models"
        return requireHttpsUrl(base, "Vertex catalog URL").newBuilder()
            .addQueryParameter("pageSize", pageSize.coerceIn(1, 1000).toString())
            .apply { if (!pageToken.isNullOrBlank()) addQueryParameter("pageToken", pageToken) }
            .build()
            .toString()
    }

    internal fun requireHttpsUrl(raw: String, label: String = "URL"): HttpUrl {
        val parsed = raw.trim().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("$label must be a valid HTTPS URL.")
        if (!parsed.isHttps || parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.host.isBlank()) {
            throw IllegalArgumentException("$label must use HTTPS without embedded credentials.")
        }
        return parsed
    }

    internal fun requireHttpsOrLoopbackUrl(raw: String, label: String = "URL"): HttpUrl {
        val parsed = raw.trim().toHttpUrlOrNull()
            ?: throw IllegalArgumentException("$label must be a valid URL.")
        val isLoopbackHttp = parsed.scheme == "http" &&
            (parsed.host == "127.0.0.1" || parsed.host == "localhost" || parsed.host == "::1")
        if ((!parsed.isHttps && !isLoopbackHttp) ||
            parsed.username.isNotEmpty() ||
            parsed.password.isNotEmpty() ||
            parsed.host.isBlank()
        ) {
            throw IllegalArgumentException("$label must use HTTPS (or loopback HTTP for tests).")
        }
        return parsed
    }
}

data class VertexTokenResponse(
    val accessToken: String,
    val expiresInSec: Long,
) {
    override fun toString(): String = "VertexTokenResponse(expiresInSec=$expiresInSec)"
}

object VertexAuth {
    private const val JWT_LIFETIME_SEC = 3600L
    private const val JWT_GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer"
    private val json = Json { ignoreUnknownKeys = true }
    private val urlEncoder = Base64.getUrlEncoder().withoutPadding()
    private val whitespace = Regex("\\s+")

    fun createJwtAssertion(
        serviceAccount: VertexServiceAccount,
        nowEpochSec: Long = System.currentTimeMillis() / 1000L,
    ): String {
        val header = buildJsonObject {
            put("alg", "RS256")
            put("typ", "JWT")
        }.toString()
        val claims = buildJsonObject {
            put("iss", serviceAccount.clientEmail)
            put("scope", VertexServiceAccount.VERTEX_SCOPE)
            put("aud", serviceAccount.tokenUri)
            put("iat", nowEpochSec)
            put("exp", nowEpochSec + JWT_LIFETIME_SEC)
        }.toString()
        val signingInput = "${b64Url(header.toByteArray(Charsets.UTF_8))}.${b64Url(claims.toByteArray(Charsets.UTF_8))}"
        val privateKey = decodePrivateKey(serviceAccount.privateKeyPem)
        val sig = try {
            Signature.getInstance("SHA256withRSA").apply {
                initSign(privateKey)
                update(signingInput.toByteArray(Charsets.UTF_8))
            }.sign()
        } catch (_: Exception) {
            throw IllegalArgumentException("Failed to sign Vertex JWT assertion with Service Account key.")
        }
        return "$signingInput.${b64Url(sig)}"
    }

    internal fun decodePrivateKey(privateKeyPem: String): PrivateKey {
        val body = privateKeyPem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\n", "")
            .replace(whitespace, "")
        if (body.isEmpty()) {
            throw IllegalArgumentException("Invalid RSA private key in Service Account credentials.")
        }
        return try {
            val der = Base64.getDecoder().decode(body)
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
        } catch (_: Exception) {
            throw IllegalArgumentException("Invalid RSA private key in Service Account credentials.")
        }
    }

    suspend fun exchangeJwt(
        http: OkHttpClient,
        tokenUrl: String,
        assertion: String,
    ): VertexTokenResponse {
        val form = FormBody.Builder()
            .add("grant_type", JWT_GRANT_TYPE)
            .add("assertion", assertion)
            .build()
        val req = Request.Builder()
            .url(tokenUrl)
            .header("Accept", "application/json")
            .post(form)
            .build()
        val raw = HttpSupport.execute(http, req)
        return when {
            raw.status == 400 || raw.status == 401 || raw.status == 403 -> throw ModelError.Auth()
            raw.status == 429 -> throw ModelError.RateLimited(HttpSupport.retryAfterMs(raw.retryAfter, raw.body))
            raw.status in 500..599 -> throw ModelError.Server(raw.status)
            raw.status !in 200..299 -> throw ModelError.BadResponse("Vertex OAuth returned HTTP ${raw.status}")
            else -> parseTokenResponse(raw.body)
        }
    }

    private fun parseTokenResponse(body: String): VertexTokenResponse {
        val obj = try {
            json.parseToJsonElement(body) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: throw ModelError.BadResponse("Vertex OAuth returned invalid JSON")

        val accessToken = obj.str("access_token")?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw ModelError.BadResponse("Vertex OAuth response missing access_token")
        val expiresIn = (obj["expires_in"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0L } ?: JWT_LIFETIME_SEC
        return VertexTokenResponse(accessToken = accessToken, expiresInSec = expiresIn)
    }

    private fun b64Url(bytes: ByteArray): String = urlEncoder.encodeToString(bytes)
}

class VertexTokenProvider private constructor(
    private val http: OkHttpClient,
    private val loadAccount: AccountLoader,
    private val nowMs: () -> Long,
    private val tokenUrlOverride: String?,
) : BearerToken {
    constructor(
        http: OkHttpClient,
        loadServiceAccountJson: () -> String?,
        nowMs: () -> Long = System::currentTimeMillis,
        tokenUrlOverride: String? = null,
    ) : this(
        http = http,
        loadAccount = AccountLoader {
            val raw = loadServiceAccountJson()?.trim()?.takeIf { it.isNotEmpty() }
            raw?.let { LoadedAccount(VertexServiceAccount.fingerprintSha256(it), VertexServiceAccount.parse(it)) }
        },
        nowMs = nowMs,
        tokenUrlOverride = tokenUrlOverride,
    )

    constructor(
        http: OkHttpClient,
        serviceAccount: VertexServiceAccount,
        nowMs: () -> Long = System::currentTimeMillis,
        tokenUrlOverride: String? = null,
    ) : this(
        http = http,
        loadAccount = AccountLoader { LoadedAccount(serviceAccount.fingerprintSha256(), serviceAccount) },
        nowMs = nowMs,
        tokenUrlOverride = tokenUrlOverride,
    )

    private fun interface AccountLoader {
        fun load(): LoadedAccount?
    }

    private data class LoadedAccount(val fingerprint: String, val account: VertexServiceAccount)

    private class CachedToken(
        val saFingerprint: String,
        val accessToken: String,
        val expiresAtMs: Long,
    ) {
        override fun toString(): String = "CachedToken(expiresAtMs=$expiresAtMs)"
    }

    private val validatedTokenUrlOverride: String? = tokenUrlOverride?.let {
        VertexEndpoints.requireHttpsOrLoopbackUrl(it, "Vertex tokenUrlOverride").toString()
    }
    private val lock = Mutex()
    private var cached: CachedToken? = null

    override val refreshable: Boolean get() = true

    override suspend fun get(fresh: Boolean): String = lock.withLock {
        if (fresh) cached = null
        val loaded = try {
            loadAccount.load()
        } catch (_: IllegalArgumentException) {
            cached = null
            throw ModelError.Auth()
        } ?: run {
            cached = null
            throw ModelError.Auth()
        }

        val now = nowMs()
        val existing = cached
        if (!fresh &&
            existing != null &&
            existing.saFingerprint == loaded.fingerprint &&
            now < existing.expiresAtMs - REFRESH_AHEAD_MS
        ) {
            return@withLock existing.accessToken
        }

        val tokenUrl = validatedTokenUrlOverride ?: loaded.account.tokenUri
        val assertion = try {
            VertexAuth.createJwtAssertion(loaded.account, nowEpochSec = now / 1000L)
        } catch (_: IllegalArgumentException) {
            cached = null
            throw ModelError.Auth()
        }
        val exchanged = VertexAuth.exchangeJwt(http, tokenUrl, assertion)
        cached = CachedToken(
            saFingerprint = loaded.fingerprint,
            accessToken = exchanged.accessToken,
            expiresAtMs = now + exchanged.expiresInSec * 1000L,
        )
        exchanged.accessToken
    }

    private companion object {
        const val REFRESH_AHEAD_MS = 60_000L
    }
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
