package com.wangzan101.mika.core.cap.phone

import com.wangzan101.mika.core.access.AppAccessDenied
import com.wangzan101.mika.core.access.AppAccessState
import com.wangzan101.mika.core.prompt.Feedback
import com.wangzan101.mika.core.tool.LinkPolicy
import com.wangzan101.mika.core.uistate.SystemAction
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Deterministic route decision for [VerifiedIntentRouter.classify].
 */
sealed interface IntentRouteDecision {
    /** Safe to launch directly without a reference-note entry or interactive prompt. */
    data object DirectAllowed : IntentRouteDecision

    /** Requires explicit user confirmation before launching (calls/messages). */
    data class ConfirmRequired(val scheme: String, val uri: String) : IntentRouteDecision

    /** Rejected by link policy, app access control, or missing reference-note entry. */
    data class Denied(val code: String, val message: String) : IntentRouteDecision
}

/**
 * Deterministic validation, classification, and intent-URI builders for `https:`, `geo:`, `mailto:`,
 * calendar [SystemAction.AddCalendarEvent], `tel:`, `sms:`/`smsto:`/`mms:`/`sip:`, and [LinkPolicy.SETTINGS_ACTIONS].
 */
object VerifiedIntentRouter {
    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
    private val DIRECT_SAFE_SCHEMES = setOf("geo", "mailto")
    private val CONFIRMATION_SCHEMES = setOf("tel", "sms", "smsto", "mms", "sip")
    private const val MAX_URI = 2000

    /** Returns `true` when [action] is one of the allowed Android settings actions. */
    fun isAllowedSettingsAction(action: String?): Boolean =
        action != null && action.trim() in LinkPolicy.SETTINGS_ACTIONS

    /** Returns `true` when [uri] uses a direct-safe scheme (`geo:` or `mailto:`). */
    fun isDirectSafeScheme(uri: String?): Boolean = schemeOf(uri) in DIRECT_SAFE_SCHEMES

    /**
     * Returns `true` for well-formed `geo:` (non-blank query or valid coordinates) and
     * `mailto:` (non-blank recipient or query, no whitespace or control characters).
     */
    fun isDirectSafeIntent(uri: String?): Boolean {
        val raw = uri?.trim() ?: return false
        if (raw.isEmpty() || raw.length > MAX_URI || hasWhitespaceOrControl(raw)) return false
        return when (schemeOf(raw)) {
            "geo" -> isValidGeoBody(raw.substringAfter(':'))
            "mailto" -> isValidMailtoBody(raw.substringAfter(':'))
            else -> false
        }
    }

    /**
     * Returns `true` for call and messaging schemes (`tel:`, `sms:`, `smsto:`, `mms:`, `sip:`)
     * that require explicit user confirmation before launching.
     */
    fun requiresConfirmation(uri: String?): Boolean {
        val raw = uri?.trim() ?: return false
        if (raw.isEmpty() || hasWhitespaceOrControl(raw)) return false
        return schemeOf(raw) in CONFIRMATION_SCHEMES
    }

    /** Returns `true` when [uri] is a valid `https:` URL with a non-blank host and no control chars. */
    fun isValidHttpsUri(uri: String?): Boolean {
        val raw = uri?.trim() ?: return false
        if (raw.isEmpty() || raw.length > MAX_URI || hasWhitespaceOrControl(raw)) return false
        if (schemeOf(raw) != "https") return false
        val parsed = runCatching { URI(raw) }.getOrNull() ?: return false
        return !parsed.host.isNullOrBlank()
    }

    /** Validates a [SystemAction.AddCalendarEvent]; returns `null` when valid, or a descriptive error. */
    fun validateCalendarEvent(event: SystemAction.AddCalendarEvent): String? {
        val title = event.title.trim()
        if (title.isEmpty()) return "Calendar event title must not be blank."
        if (event.title.any { it.isISOControl() }) return "Calendar event title must not contain control characters."
        if (event.location?.any { it.isISOControl() } == true) {
            return "Calendar event location must not contain control characters."
        }
        if (event.endMs != null && event.endMs < event.beginMs) {
            return "Calendar event end time must not precede begin time."
        }
        return null
    }

    /** Evaluates [AppAccessState.denial] for [packageName] and returns the denial if blocked/unavailable. */
    fun checkAppAccess(packageName: String?, access: AppAccessState): AppAccessDenied? =
        access.denial(packageName)

    /** Builds a validated, URL-encoded `geo:` search URI for [query]. */
    fun buildGeoUri(query: String): String {
        val trimmed = query.trim()
        require(trimmed.isNotEmpty()) { "geo query must not be blank." }
        require(trimmed.none { it.isISOControl() }) { "geo query must not contain control characters." }
        val uri = "geo:0,0?q=${encodeQueryComponent(trimmed)}"
        require(LinkPolicy.check(null, uri, null) == null && isDirectSafeIntent(uri)) {
            "geo URI exceeds policy limits."
        }
        return uri
    }

    /** Builds a validated `geo:` coordinate URI with an optional URL-encoded [label]. */
    fun buildGeoUri(latitude: Double, longitude: Double, label: String? = null): String {
        require(latitude.isFinite() && latitude in -90.0..90.0) { "latitude must be between -90 and 90." }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "longitude must be between -180 and 180." }
        val cleanLabel = label?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanLabel != null) {
            require(cleanLabel.none { it.isISOControl() }) { "geo label must not contain control characters." }
        }
        val uri = if (cleanLabel != null) {
            "geo:$latitude,$longitude?q=${encodeQueryComponent(cleanLabel)}"
        } else {
            "geo:$latitude,$longitude"
        }
        require(LinkPolicy.check(null, uri, null) == null && isDirectSafeIntent(uri)) {
            "geo URI exceeds policy limits."
        }
        return uri
    }

    /** Builds a validated, URL-encoded `mailto:` URI with optional [subject] and [body]. */
    fun buildMailtoUri(to: String, subject: String? = null, body: String? = null): String {
        val recipient = to.trim()
        require(recipient.isNotEmpty()) { "mailto recipient must not be blank." }
        require(!hasWhitespaceOrControl(recipient)) {
            "mailto recipient must not contain whitespace or control characters."
        }
        require(isValidMailtoTargets(recipient)) { "mailto recipient must be a valid email address." }
        val params = mutableListOf<String>()
        subject?.trim()?.takeIf { it.isNotEmpty() }?.let { cleanSubject ->
            require(cleanSubject.none { it.isISOControl() }) {
                "mailto subject must not contain control characters."
            }
            params += "subject=${encodeQueryComponent(cleanSubject)}"
        }
        body?.takeIf { it.isNotBlank() }?.let { cleanBody ->
            require(cleanBody.none { it.isISOControl() && it != '\n' && it != '\r' && it != '\t' }) {
                "mailto body must not contain control characters."
            }
            params += "body=${encodeQueryComponent(cleanBody)}"
        }
        val uri = buildString {
            append("mailto:")
            append(recipient)
            if (params.isNotEmpty()) {
                append('?')
                append(params.joinToString("&"))
            }
        }
        require(LinkPolicy.check(null, uri, null) == null && isDirectSafeIntent(uri)) {
            "mailto URI exceeds policy limits."
        }
        return uri
    }

    /** Validates an `https:` URI paired with an explicit target [packageName]. */
    fun buildHttpsUri(rawUrl: String, packageName: String): Pair<String, String> {
        val cleanUrl = rawUrl.trim()
        val cleanPkg = packageName.trim()
        require(isValidHttpsUri(cleanUrl)) { "URL must be a valid https: URI." }
        require(LinkPolicy.PACKAGE.matches(cleanPkg)) { "packageName must be a valid Android package name." }
        return cleanUrl to cleanPkg
    }

    /**
     * Classifies a `phone_open_link` invocation against [LinkPolicy], [AppAccessState],
     * direct-safe schemes, confirmation-gated communication schemes, and Knowledge listing.
     */
    fun classify(
        action: String?,
        uri: String?,
        packageName: String?,
        listedInKnowledge: Boolean,
        access: AppAccessState,
    ): IntentRouteDecision {
        val cleanAction = action?.trim()
        val cleanUri = uri?.trim()
        val cleanPkg = packageName?.trim()
        LinkPolicy.check(cleanAction, cleanUri, cleanPkg)?.let {
            return IntentRouteDecision.Denied("invalid_link", it)
        }
        if (!access.available) {
            val denial = checkAppAccess(cleanPkg, access)!!
            return IntentRouteDecision.Denied(denial.code, denial.message ?: denial.code)
        }
        if (cleanPkg != null) {
            checkAppAccess(cleanPkg, access)?.let { denial ->
                return IntentRouteDecision.Denied(denial.code, denial.message ?: denial.code)
            }
        }
        if (cleanAction != null) {
            return IntentRouteDecision.DirectAllowed
        }
        val targetUri = cleanUri!!
        val scheme = schemeOf(targetUri)
        if (isDirectSafeIntent(targetUri)) {
            return IntentRouteDecision.DirectAllowed
        }
        if (scheme in DIRECT_SAFE_SCHEMES) {
            return IntentRouteDecision.Denied("invalid_link", "Malformed $scheme: URI.")
        }
        if (requiresConfirmation(targetUri)) {
            val target = targetUri.substringAfter(':').substringBefore('?')
            if (target.isBlank()) {
                return IntentRouteDecision.Denied("invalid_link", "Malformed $scheme: URI.")
            }
            return IntentRouteDecision.ConfirmRequired(scheme!!, targetUri)
        }
        if (listedInKnowledge) {
            return IntentRouteDecision.DirectAllowed
        }
        return IntentRouteDecision.Denied("invalid_link", Feedback["link_not_listed"])
    }

    private fun schemeOf(uri: String?): String? =
        uri?.trim()?.let { SCHEME.find(it)?.groupValues?.get(1)?.lowercase() }

    private fun hasWhitespaceOrControl(s: String): Boolean =
        s.any { it.isWhitespace() || it.isISOControl() }

    private fun encodeQueryComponent(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    private fun isValidGeoBody(body: String): Boolean {
        if (body.isEmpty()) return false
        val qIdx = body.indexOf('?')
        val coordsPart = (if (qIdx >= 0) body.substring(0, qIdx) else body).substringBefore(';')
        val queryPart = if (qIdx >= 0) body.substring(qIdx + 1) else null
        if (queryPart != null && !hasValidGeoQuery(queryPart)) return false
        if (coordsPart.isEmpty()) {
            return queryPart != null && hasNonBlankParam(queryPart, "q")
        }
        val parts = coordsPart.split(',')
        if (parts.size !in 2..3) return false
        val lat = parts[0].toDoubleOrNull() ?: return false
        val lon = parts[1].toDoubleOrNull() ?: return false
        if (!lat.isFinite() || lat !in -90.0..90.0) return false
        if (!lon.isFinite() || lon !in -180.0..180.0) return false
        if (parts.size == 3 && parts[2].toDoubleOrNull()?.isFinite() != true) return false
        return true
    }

    private fun hasValidGeoQuery(query: String): Boolean {
        if (query.isEmpty()) return false
        val pairs = query.split('&')
        for (pair in pairs) {
            if (pair.isEmpty()) return false
            val eq = pair.indexOf('=')
            val key = if (eq >= 0) pair.substring(0, eq) else pair
            val value = if (eq >= 0) pair.substring(eq + 1) else ""
            if (key == "q" && value.isBlank()) return false
        }
        return true
    }

    private fun hasNonBlankParam(query: String, name: String): Boolean =
        query.split('&').any { pair ->
            val eq = pair.indexOf('=')
            eq > 0 && pair.substring(0, eq) == name && pair.substring(eq + 1).isNotBlank()
        }

    private fun isValidMailtoBody(body: String): Boolean {
        if (body.isEmpty()) return false
        val qIdx = body.indexOf('?')
        val targetPart = if (qIdx >= 0) body.substring(0, qIdx) else body
        val queryPart = if (qIdx >= 0) body.substring(qIdx + 1) else null
        if (targetPart.isNotEmpty() && !isValidMailtoTargets(targetPart)) return false
        if (queryPart != null && queryPart.split('&').none { it.isNotBlank() && it != "=" }) return false
        return targetPart.isNotEmpty() || !queryPart.isNullOrBlank()
    }

    private fun isValidMailtoTargets(targets: String): Boolean {
        val addresses = targets.split(',')
        if (addresses.isEmpty()) return false
        return addresses.all { addr ->
            val at = addr.indexOf('@')
            at > 0 && at < addr.lastIndex && addr.indexOf('@', at + 1) < 0
        }
    }
}
