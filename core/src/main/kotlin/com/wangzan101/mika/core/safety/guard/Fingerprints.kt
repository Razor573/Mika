package com.wangzan101.mika.core.safety.guard

import com.wangzan101.mika.core.tool.Verb
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.MessageDigest

/** 读屏、看图、诊断屏幕一类:守卫按工具计重复,不按参数。 */
internal val DIAGNOSTIC_VERBS = setOf(Verb.Observe, Verb.ListElements, Verb.Screenshot, Verb.Describe, Verb.Diagnose, Verb.ViewPastScreens)
/** 只读不动的看页面一类:页面没变时反复用它们读,由 [OperationGuards.read] 管。 */
internal val PAGE_READ_VERBS = DIAGNOSTIC_VERBS + setOf(Verb.ReadPage, Verb.EvaluateJs, Verb.SiteCommand)

/** 工具结果的指纹(不含每次都换的读屏编号)。 */
internal fun resultFingerprint(content: JsonObject): Long {
    val text = JsonObject(content - "snapshotId" - "guard" - "advice").toString()
    var h = -0x340d631b7bdddcdbL
    for (b in text.toByteArray(Charsets.UTF_8)) h = (h xor (b.toLong() and 0xff)) * 0x100000001b3L
    return h
}

/** 画面指纹:元素列表文本的 64 位 FNV-1a。纯文本模型没有截图,也能判断「画面没变」。 */
internal fun elementsHash(elements: JsonArray?): Long? {
    elements ?: return null
    // 不含编号:稳定编号开着时同一屏里重复的同名元素每次读都换新编号,不该被当成换了一屏
    val text = JsonArray(elements.map { e -> (e as? JsonObject)?.let { JsonObject(it - "ref") } ?: e }).toString()
    var h = -0x340d631b7bdddcdbL
    for (b in text.toByteArray(Charsets.UTF_8)) {
        h = (h xor (b.toLong() and 0xff)) * 0x100000001b3L
    }
    return h
}

private const val HEX_CHARS = "0123456789abcdef"

/** SHA-256 小写十六进制前 12 位。 */
fun sha256Hex12(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    val sb = StringBuilder(12)
    for (i in 0 until 6) {
        val b = digest[i].toInt() and 0xff
        sb.append(HEX_CHARS[b ushr 4]).append(HEX_CHARS[b and 0x0f])
    }
    return sb.toString()
}

private fun stripVolatile(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> {
        val keys = element.keys.filter { it != "ref" && it != "snapshotId" }.sorted()
        buildJsonObject {
            for (k in keys) put(k, stripVolatile(element.getValue(k)))
        }
    }
    is JsonArray -> JsonArray(element.map(::stripVolatile))
    else -> element
}

private fun JsonObject.strField(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** 归一化屏幕内容(剥离易变的 ref 与 snapshotId),返回前 12 位 SHA-256;完全没有屏幕信息时返回 null。 */
fun screenContentHash(elements: JsonArray?, content: JsonObject): String? {
    val screenObj = content["screen"] as? JsonObject
    val el = elements ?: (content["elements"] as? JsonArray) ?: (screenObj?.get("elements") as? JsonArray)
    val currentApp = content.strField("currentApp") ?: screenObj?.strField("currentApp")
    val title = content.strField("title") ?: screenObj?.strField("title")
    val windows = (content["windows"] as? JsonArray) ?: (screenObj?.get("windows") as? JsonArray)
    val tree = (content["tree"] as? JsonArray) ?: (screenObj?.get("tree") as? JsonArray)
    if (el == null && currentApp == null && title == null && windows == null && tree == null) return null
    val normalized = buildJsonObject {
        currentApp?.let { put("currentApp", it) }
        el?.let { put("elements", stripVolatile(it)) }
        title?.let { put("title", it) }
        tree?.let { put("tree", stripVolatile(it)) }
        windows?.let { put("windows", stripVolatile(it)) }
    }
    return sha256Hex12(normalized.toString())
}

/** 结构化提取结果的整表与逐项指纹。 */
data class ExtractedHashResult(val hash: String, val itemHashes: List<String>)

/** 对 items/matches/results 列表去易变字段并分别计算逐项与整表 SHA-256 前 12 位;不含数组时返回 null。 */
fun extractedItemsHash(content: JsonObject): ExtractedHashResult? {
    val arr = (content["items"] as? JsonArray)
        ?: (content["matches"] as? JsonArray)
        ?: (content["results"] as? JsonArray)
        ?: return null
    if (arr.isEmpty()) return ExtractedHashResult(sha256Hex12("[]"), emptyList())
    val normalized = arr.map(::stripVolatile)
    val itemHashes = normalized.map { sha256Hex12(it.toString()) }
    val hash = sha256Hex12(JsonArray(normalized).toString())
    return ExtractedHashResult(hash, itemHashes)
}
