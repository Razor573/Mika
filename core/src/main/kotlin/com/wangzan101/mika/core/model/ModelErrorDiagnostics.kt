package com.wangzan101.mika.core.model

import com.wangzan101.mika.core.run.record.Redactor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

object ModelErrorDiagnostics {
    const val MAX_BODY_CHARS = 300

    private val json = Json { ignoreUnknownKeys = true }

    private val BEARER = Regex("""\bBearer[ \t]+[^\s"]+""", RegexOption.IGNORE_CASE)
    private val GOOGLE_API_KEY = Regex("""AIza[0-9A-Za-z_-]{20,}""")
    private val SK_KEY = Regex("""sk-[0-9A-Za-z_-]{16,}""")
    private val OAUTH_YA29 = Regex("""ya29\.[0-9A-Za-z._-]+""")
    private val URL_KEY = Regex("""([?&](?:api_)?key=)[^&\s"'<>]+""", RegexOption.IGNORE_CASE)
    private val JSON_SECRET = Regex(
        """("(?:api_key|access_token|refresh_token|authorization|secret)"\s*:\s*)"(?:[^"\\]|\\.)*"""",
        RegexOption.IGNORE_CASE,
    )
    private val NEWLINES = Regex("""[\r\n]+""")

    data class Extracted(
        val httpStatus: Int?,
        val errorCode: String?,
        val errorBody: String?,
    )

    fun scrubAndClip(raw: String): String {
        var out = JSON_SECRET.replace(raw) { m -> """${m.groupValues[1]}"${Redactor.MASK}"""" }
        out = URL_KEY.replace(out) { m -> "${m.groupValues[1]}${Redactor.MASK}" }
        out = BEARER.replace(out, "Bearer ${Redactor.MASK}")
        out = GOOGLE_API_KEY.replace(out, Redactor.MASK)
        out = SK_KEY.replace(out, Redactor.MASK)
        out = OAUTH_YA29.replace(out, Redactor.MASK)
        out = Redactor.text(out)
        out = NEWLINES.replace(out, " ").trim()
        return out.take(MAX_BODY_CHARS)
    }

    fun extractJsonCode(body: String): String? {
        val parsed = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return null
        val root = (parsed as? JsonObject) ?: (parsed as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val err = root["error"] as? JsonObject
        return err?.str("code")
            ?: err?.str("status")
            ?: err?.str("type")
            ?: err?.prim("code")
            ?: err?.prim("status")
            ?: root.str("code")
            ?: root.str("status")
            ?: root.str("type")
            ?: root.prim("code")
            ?: root.prim("status")
    }

    fun extract(e: Throwable): Extracted {
        val target = (e as? ModelError.ChainFailed)?.primary ?: e
        val modelErr = target as? ModelError
        val httpStatus = (e as? ModelError)?.httpStatus
            ?: modelErr?.httpStatus
            ?: (target as? ModelError.Server)?.status
        val errorCode = (e as? ModelError)?.errorCode
            ?: modelErr?.errorCode
            ?: mappedErrorCode(target)
        val errorBody = (e as? ModelError)?.errorBody
            ?: modelErr?.errorBody
            ?: target.message?.takeIf { it.isNotBlank() }?.let(::scrubAndClip)
            ?: e.message?.takeIf { it.isNotBlank() }?.let(::scrubAndClip)
        return Extracted(httpStatus = httpStatus, errorCode = errorCode, errorBody = errorBody)
    }

    fun shortReason(e: Throwable): String {
        val target = (e as? ModelError.ChainFailed)?.primary ?: e
        val status = (e as? ModelError)?.httpStatus
            ?: (target as? ModelError)?.httpStatus
            ?: (target as? ModelError.Server)?.status
        if (status != null) return "http_$status"
        return target::class.simpleName ?: e::class.simpleName ?: "UnknownError"
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.prim(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun mappedErrorCode(e: Throwable): String? = when (e) {
        is ModelError.Auth -> "auth"
        is ModelError.IllegalHeader -> "illegal_header"
        is ModelError.QuotaExceeded -> "quota_exceeded"
        is ModelError.TaskBudgetExceeded -> "task_budget_exceeded"
        is ModelError.DailyBudgetExceeded -> "daily_budget_exceeded"
        is ModelError.ProactiveBudgetExceeded -> "proactive_budget_exceeded"
        is ModelError.RateLimited -> "rate_limited"
        is ModelError.ContextTooLong -> "context_too_long"
        is ModelError.ModelNotFound -> "model_not_found"
        is ModelError.ModelNotAvailable -> "model_not_available"
        is ModelError.ToolsUnsupported -> "tools_unsupported"
        is ModelError.Network -> "network"
        is ModelError.Proxy -> "proxy"
        is ModelError.StreamCut -> "stream_cut"
        is ModelError.Server -> "server_error"
        is ModelError.ContentRefused -> "content_refused"
        is ModelError.ReasoningEffortRejected -> "reasoning_effort_rejected"
        is ModelError.ServiceTierRejected -> "service_tier_rejected"
        is ModelError.ChatGptLimitReached -> "chatgpt_limit_reached"
        is ModelError.ChatGptNotEligible -> "chatgpt_not_eligible"
        is ModelError.SignInExpired -> "sign_in_expired"
        is ModelError.AccountPlanNotAllowed -> "account_plan_not_allowed"
        is ModelError.BadResponse -> "bad_response"
        is ModelError.GroupUnavailable -> "group_unavailable"
        is ModelError.GroupExhausted -> "group_exhausted"
        is ModelError.ChainFailed -> "chain_failed"
        else -> e::class.simpleName
    }
}
