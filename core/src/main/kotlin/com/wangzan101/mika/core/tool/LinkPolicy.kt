package com.wangzan101.mika.core.tool


/**
 * open_link 能打开什么。分发器执行前查一次,外壳服务启动前再查一次。
 * 设置页只认安卓原版公开的 `android.settings.*` 动作;链接拒绝文件与脚本类协议,
 * 网页链接必须指定 App(否则会落到浏览器里)。
 */
object LinkPolicy {
    const val APP_DETAILS = "android.settings.APPLICATION_DETAILS_SETTINGS"

    val SETTINGS_ACTIONS: Set<String> = setOf(
        "android.settings.SETTINGS", "android.settings.WIFI_SETTINGS", "android.settings.BLUETOOTH_SETTINGS",
        "android.settings.DISPLAY_SETTINGS", "android.settings.SOUND_SETTINGS", "android.settings.BATTERY_SAVER_SETTINGS",
        APP_DETAILS, "android.settings.APPLICATION_SETTINGS", "android.settings.DATE_SETTINGS",
        "android.settings.LOCALE_SETTINGS", "android.settings.DEVICE_INFO_SETTINGS", "android.settings.LOCATION_SOURCE_SETTINGS",
        "android.settings.INTERNAL_STORAGE_SETTINGS", "android.settings.ACCESSIBILITY_SETTINGS",
    )

    private val BLOCKED_SCHEMES = setOf("intent", "file", "content", "javascript", "data", "about", "blob")
    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
    /** 安卓包名的形状;分发器检查 package / app 参数也用它。 */
    val PACKAGE = Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$")
    private const val MAX_URI = 2000

    /** null = 允许;否则是给模型看的错误说明。 */
    fun check(action: String?, uri: String?, pkg: String?): String? {
        if (action == null && uri == null) return "Give uri, or action for an Android settings page."
        if (pkg != null && !PACKAGE.matches(pkg)) return "package must be an Android package name."
        if (uri != null && (uri.length > MAX_URI || uri.any { it.isWhitespace() || it.isISOControl() })) {
            return "uri must be at most $MAX_URI characters without spaces."
        }
        if (action != null) {
            if (action !in SETTINGS_ACTIONS) return "action must be one of the Android settings actions: ${SETTINGS_ACTIONS.joinToString()}."
            if (pkg != null) return "package is not used with action."
            if (action == APP_DETAILS) {
                val target = uri?.takeIf { it.startsWith("package:") }?.removePrefix("package:")
                if (target == null || !PACKAGE.matches(target)) return "$APP_DETAILS needs uri package:<package name>, e.g. package:com.example.app."
            } else if (uri != null) return "Only $APP_DETAILS takes a uri; open other settings pages with action alone."
            return null
        }
        val scheme = SCHEME.find(uri!!)?.groupValues?.get(1)?.lowercase() ?: return "uri must start with a scheme, e.g. https:// or app://."
        if (scheme in BLOCKED_SCHEMES) return "$scheme: links are not supported by phone_open_link."
        if (scheme == "package") return "package: uris are only for action $APP_DETAILS."
        if ((scheme == "http" || scheme == "https") && pkg == null) {
            return "A web link needs package (the app that should open it); otherwise it would open in a browser."
        }
        return null
    }

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

    internal fun isValidMailtoTargets(targets: String): Boolean {
        val addresses = targets.split(',')
        if (addresses.isEmpty()) return false
        return addresses.all { addr ->
            val at = addr.indexOf('@')
            at > 0 && at < addr.lastIndex && addr.indexOf('@', at + 1) < 0
        }
    }

    private fun schemeOf(uri: String?): String? =
        uri?.trim()?.let { SCHEME.find(it)?.groupValues?.get(1)?.lowercase() }

    private fun hasWhitespaceOrControl(s: String): Boolean =
        s.any { it.isWhitespace() || it.isISOControl() }

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
}
