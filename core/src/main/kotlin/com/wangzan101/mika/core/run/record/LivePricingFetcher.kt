package com.wangzan101.mika.core.run.record

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Fetches official per-model pricing directly from Google's Cloud Billing Catalog API and
 * official Google pricing pages (Gemini Developer API + Vertex AI), never using Google Search grounding.
 */
object LivePricingFetcher {
    const val CLOUD_BILLING_SERVICES_URL = "https://cloudbilling.googleapis.com/v1/services"
    const val GEMINI_PRICING_URL = "https://ai.google.dev/gemini-api/docs/pricing"
    const val VERTEX_PRICING_URL = "https://cloud.google.com/vertex-ai/generative-ai/pricing"

    private val json = Json { ignoreUnknownKeys = true }

    private val MONTH_MAP = mapOf(
        "january" to Month.JANUARY, "jan" to Month.JANUARY,
        "february" to Month.FEBRUARY, "feb" to Month.FEBRUARY,
        "march" to Month.MARCH, "mar" to Month.MARCH,
        "april" to Month.APRIL, "apr" to Month.APRIL,
        "may" to Month.MAY,
        "june" to Month.JUNE, "jun" to Month.JUNE,
        "july" to Month.JULY, "jul" to Month.JULY,
        "august" to Month.AUGUST, "aug" to Month.AUGUST,
        "september" to Month.SEPTEMBER, "sep" to Month.SEPTEMBER, "sept" to Month.SEPTEMBER,
        "october" to Month.OCTOBER, "oct" to Month.OCTOBER,
        "november" to Month.NOVEMBER, "nov" to Month.NOVEMBER,
        "december" to Month.DECEMBER, "dec" to Month.DECEMBER,
    )

    private val THROUGH_DATE_RE = Regex("""through\s+([A-Za-z]+)\s+(\d{1,2}),\s*(\d{4})""", RegexOption.IGNORE_CASE)
    private val STARTING_DATE_RE = Regex("""(?:starting|from)\s+([A-Za-z]+)\s+(\d{1,2}),\s*(\d{4})""", RegexOption.IGNORE_CASE)
    private val DOLLAR_RE = Regex("""\$(\d+(?:\.\d+)?)""")

    private val HTML_TAG_RE = Regex("""<[^>]+>""")
    private val WHITESPACE_RE = Regex("""\s+""")

    private val GEMINI_MODEL_SECTION_RE = Regex("""\{%\s*id=(?:&quot;|")([a-z0-9._-]+)(?:&quot;|")\s*%\}""")
    private val GEMINI_TAB_RE = Regex("""\{%\s*tab\s+title=(?:&quot;|")([^"&]+)(?:&quot;|")\s*%\}(.*?)\{%\s*/tab\s*%\}""", RegexOption.DOT_MATCHES_ALL)

    private val VERTEX_TAB_BUTTON_RE = Regex("""<button[^>]*\bid="(tab-[^"]+)"[^>]*\btrack-metadata-eventdetail="([^"]+)"[^>]*>""")
    private val VERTEX_TABPANEL_RE = Regex("""<div[^>]*\brole="tabpanel"[^>]*\baria-labelledby="(tab-[^"]+)"[^>]*>""")
    private val VERTEX_TABLE_RE = Regex("""<table\b[^>]*>(.*?)</table>""", RegexOption.DOT_MATCHES_ALL)
    private val VERTEX_TR_RE = Regex("""<tr\b[^>]*>(.*?)</tr>""", RegexOption.DOT_MATCHES_ALL)
    private val VERTEX_CELL_RE = Regex("""<t[hd]\b[^>]*>(.*?)</t[hd]>""", RegexOption.DOT_MATCHES_ALL)

    private fun defaultHttp(base: OkHttpClient): OkHttpClient =
        base.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build()

    fun fetch(
        http: OkHttpClient = OkHttpClient(),
        billingBearerToken: String? = null,
        billingApiKey: String? = null,
        billingProjectId: String? = null,
        nowIso: String = Instant.now().toString(),
        cloudBillingBaseUrl: String = CLOUD_BILLING_SERVICES_URL,
        geminiPricingUrl: String = GEMINI_PRICING_URL,
        vertexPricingUrl: String = VERTEX_PRICING_URL,
    ): PricingSnapshot? {
        val client = defaultHttp(http)
        val entries = ArrayList<ModelPriceEntry>()
        val sources = ArrayList<String>()

        if (!billingBearerToken.isNullOrBlank() || !billingApiKey.isNullOrBlank()) {
            val apiEntries = runCatching {
                fetchFromCloudBillingApi(
                    http = client,
                    bearerToken = billingBearerToken,
                    apiKey = billingApiKey,
                    projectId = billingProjectId,
                    servicesUrl = cloudBillingBaseUrl,
                )
            }.getOrNull().orEmpty()
            if (apiEntries.isNotEmpty()) {
                entries += apiEntries
                sources += "cloud_billing_api"
            }
        }

        val vertexHtml = runCatching { httpGetText(client, vertexPricingUrl) }.getOrNull()
        if (!vertexHtml.isNullOrBlank()) {
            val parsed = parseVertexPricingPage(vertexHtml)
            if (parsed.isNotEmpty()) {
                mergeMissing(entries, parsed)
                sources += "vertex_pricing_page"
            }
        }

        val geminiHtml = runCatching { httpGetText(client, geminiPricingUrl) }.getOrNull()
        if (!geminiHtml.isNullOrBlank()) {
            val parsed = parseGeminiPricingPage(geminiHtml)
            if (parsed.isNotEmpty()) {
                mergeMissing(entries, parsed)
                sources += "gemini_pricing_page"
            }
        }

        if (entries.isEmpty()) return null
        return PricingSnapshot(
            fetchedAt = nowIso,
            source = sources.joinToString("+"),
            entries = entries,
        )
    }

    private fun mergeMissing(target: MutableList<ModelPriceEntry>, extra: List<ModelPriceEntry>) {
        val seen = HashSet<String>(target.size * 2)
        target.forEach { seen += keyOf(it) }
        for (e in extra) {
            if (seen.add(keyOf(e))) target += e
        }
    }

    private fun keyOf(e: ModelPriceEntry): String =
        "${e.provider}|${e.model}|${e.tier}|${e.region}|${e.effectiveFrom.orEmpty()}|${e.effectiveThrough.orEmpty()}"

    private fun httpGetText(
        http: OkHttpClient,
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): String {
        val builder = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", "Mozilla/5.0 (compatible; Mika/1.0)")
        headers.forEach { (k, v) -> builder.header(k, v) }
        http.newCall(builder.build()).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} from $url")
            return body
        }
    }

    internal fun fetchFromCloudBillingApi(
        http: OkHttpClient,
        bearerToken: String? = null,
        apiKey: String? = null,
        projectId: String? = null,
        servicesUrl: String = CLOUD_BILLING_SERVICES_URL,
    ): List<ModelPriceEntry> {
        val headers = LinkedHashMap<String, String>()
        if (!bearerToken.isNullOrBlank()) headers["Authorization"] = "Bearer $bearerToken"
        if (!projectId.isNullOrBlank()) headers["x-goog-user-project"] = projectId
        val sep = if ('?' in servicesUrl) "&" else "?"
        val listUrl = buildString {
            append(servicesUrl)
            append(sep).append("pageSize=50")
            if (!apiKey.isNullOrBlank()) append("&key=").append(apiKey)
        }
        val servicesJson = httpGetText(http, listUrl, headers)
        val services = ((json.parseToJsonElement(servicesJson) as? JsonObject)?.get("services") as? JsonArray).orEmpty()
        val serviceIds = services.mapNotNull { it as? JsonObject }.filter { svc ->
            val name = (svc["displayName"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            name.contains("Vertex", ignoreCase = true) || name.contains("Gemini", ignoreCase = true)
        }.mapNotNull { svc ->
            (svc["serviceId"] as? JsonPrimitive)?.contentOrNull
                ?: (svc["name"] as? JsonPrimitive)?.contentOrNull?.substringAfterLast('/')
        }
        if (serviceIds.isEmpty()) return emptyList()

        val collected = ArrayList<ModelPriceEntry>()
        for (id in serviceIds.take(3)) {
            val baseRoot = servicesUrl.substringBeforeLast("/services")
            val skuUrl = buildString {
                append(baseRoot).append("/services/").append(id).append("/skus?pageSize=200")
                if (!apiKey.isNullOrBlank()) append("&key=").append(apiKey)
            }
            val skuJson = runCatching { httpGetText(http, skuUrl, headers) }.getOrNull() ?: continue
            collected += parseCloudBillingSkusJson(skuJson)
        }
        return collected
    }

    internal fun parseCloudBillingSkusJson(rawJson: String): List<ModelPriceEntry> {
        val root = runCatching { json.parseToJsonElement(rawJson) as? JsonObject }.getOrNull() ?: return emptyList()
        val skus = (root["skus"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        data class Acc(var input: Double? = null, var output: Double? = null, var cached: Double? = null)
        val byKey = LinkedHashMap<Triple<String, String, String>, Acc>()

        for (sku in skus) {
            val desc = (sku["description"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val modelId = extractModelIdFromSkuDescription(desc) ?: continue
            val pricePerM = extractUsdPerMillionFromSku(sku) ?: continue
            val lower = desc.lowercase(Locale.US)
            val tier = when {
                "batch" in lower || "flex" in lower -> "batch"
                "priority" in lower -> "priority"
                else -> "standard"
            }
            val region = if ("non-global" in lower || "regional" in lower || "europe" in lower || "us-" in lower) "regional" else "global"
            val key = Triple(modelId, tier, region)
            val acc = byKey.getOrPut(key) { Acc() }
            when {
                "cache" in lower && "storage" !in lower && "write" !in lower -> acc.cached = pricePerM
                "output" in lower -> acc.output = pricePerM
                "input" in lower && "cache" !in lower -> acc.input = pricePerM
            }
        }

        val out = ArrayList<ModelPriceEntry>()
        for ((key, acc) in byKey) {
            val inp = acc.input ?: continue
            val outp = acc.output ?: continue
            val cached = acc.cached ?: (inp * 0.1)
            out += ModelPriceEntry(
                provider = "vertex",
                model = key.first,
                tier = key.second,
                region = key.third,
                inputPerM = inp,
                outputPerM = outp,
                cachedInputPerM = cached,
            )
        }
        return out
    }

    private fun extractModelIdFromSkuDescription(desc: String): String? {
        val m = Regex("""gemini[\s-]+\d+(?:\.\d+)?[\s-]+(?:flash(?:[\s-]+lite)?|pro(?:[\s-]+preview)?|hydrogen|neon|carbon|argon)(?:[\s-]+preview)?""", RegexOption.IGNORE_CASE).find(desc)
            ?: return null
        return m.value.trim().lowercase(Locale.US).replace(WHITESPACE_RE, "-")
    }

    private fun extractUsdPerMillionFromSku(sku: JsonObject): Double? {
        val info = (sku["pricingInfo"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val expr = info["pricingExpression"] as? JsonObject ?: return null
        val usageUnit = (expr["usageUnit"] as? JsonPrimitive)?.contentOrNull.orEmpty().lowercase(Locale.US)
        val rates = (expr["tieredRates"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val nonZeroRate = rates.firstOrNull { rate ->
            val money = rate["unitPrice"] as? JsonObject ?: return@firstOrNull false
            val units = (money["units"] as? JsonPrimitive)?.longOrNull ?: 0L
            val nanos = (money["nanos"] as? JsonPrimitive)?.longOrNull ?: 0L
            units > 0L || nanos > 0L
        } ?: return null
        val unitPrice = nonZeroRate["unitPrice"] as? JsonObject ?: return null
        val units = ((unitPrice["units"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()) ?: 0.0
        val nanos = ((unitPrice["nanos"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()) ?: 0.0
        val raw = units + (nanos / 1_000_000_000.0)
        return when {
            "1000" in usageUnit || "k" in usageUnit -> raw * 1_000.0
            "1000000" in usageUnit || "m" in usageUnit -> raw
            else -> raw
        }
    }

    internal fun parseGeminiPricingPage(rawHtml: String): List<ModelPriceEntry> {
        val decoded = decodeBasicEntities(rawHtml)
        val matches = GEMINI_MODEL_SECTION_RE.findAll(decoded).toList()
        if (matches.isEmpty()) return emptyList()
        val entries = ArrayList<ModelPriceEntry>()
        for (i in matches.indices) {
            val modelId = matches[i].groupValues[1].trim().lowercase(Locale.US)
            if (!modelId.startsWith("gemini-")) continue
            val start = matches[i].range.last + 1
            val end = if (i + 1 < matches.size) matches[i + 1].range.first else minOf(decoded.length, start + 18_000)
            val section = decoded.substring(start, end)
            for (tabMatch in GEMINI_TAB_RE.findAll(section)) {
                val tier = normalizeTier(tabMatch.groupValues[1]) ?: continue
                val body = tabMatch.groupValues[2]
                val rows = body.split("---")
                var inputRates = emptyList<RateWindow>()
                var outputRates = emptyList<RateWindow>()
                var cacheRates = emptyList<RateWindow>()
                for (row in rows) {
                    val rowLower = row.lowercase(Locale.US)
                    val cols = row.split(Regex("""^\s*\*\s+""", setOf(RegexOption.MULTILINE))).drop(1)
                    if (cols.isEmpty()) continue
                    val paidCol = cols.last()
                    when {
                        rowLower.contains("input price") && !rowLower.contains("caching") ->
                            inputRates = extractRateWindows(paidCol)
                        rowLower.contains("output price") ->
                            outputRates = extractRateWindows(paidCol)
                        rowLower.contains("context caching price") ->
                            cacheRates = extractRateWindows(
                                paidCol.lines().filterNot { it.contains("storage price", ignoreCase = true) }.joinToString("\n"),
                            )
                    }
                }
                entries += combineRateWindows("gemini", modelId, tier, "global", inputRates, outputRates, cacheRates)
            }
        }
        return entries
    }

    internal fun parseVertexPricingPage(rawHtml: String): List<ModelPriceEntry> {
        val tabLabels = HashMap<String, String>()
        for (m in VERTEX_TAB_BUTTON_RE.findAll(rawHtml)) {
            tabLabels[m.groupValues[1]] = m.groupValues[2]
        }
        val panelOffsets = VERTEX_TABPANEL_RE.findAll(rawHtml).map { m ->
            m.range.first to tabLabels[m.groupValues[1]]
        }.toList()

        fun panelTiersAt(offset: Int): List<String> {
            val nearest = panelOffsets.lastOrNull { it.first <= offset }?.second ?: return listOf("standard")
            return normalizeVertexTiers(nearest)
        }

        val entries = ArrayList<ModelPriceEntry>()
        for (tableMatch in VERTEX_TABLE_RE.findAll(rawHtml)) {
            val tableHtml = tableMatch.groupValues[1]
            val rows = VERTEX_TR_RE.findAll(tableHtml).map { tr ->
                VERTEX_CELL_RE.findAll(tr.groupValues[1]).map { td ->
                    cleanCell(td.groupValues[1])
                }.toList()
            }.filter { it.isNotEmpty() }.toList()
            if (rows.size < 2) continue

            val header = rows.first().joinToString(" | ").lowercase(Locale.US)
            if (!header.contains("model") || !header.contains("region") || !header.contains("price")) continue

            val tiers = panelTiersAt(tableMatch.range.first)
            val cacheColIdx = rows.first().indexOfFirst {
                val l = it.lowercase(Locale.US)
                l.contains("cache read") || l.contains("cached input")
            }
            val priceColIdx = rows.first().indexOfFirst {
                val l = it.lowercase(Locale.US)
                l.contains("price") && !l.contains("cache") && !l.contains("> 200k")
            }.takeIf { it >= 0 } ?: 3

            data class GroupRowAcc(
                var globalInput: Double? = null,
                var globalCached: Double? = null,
                var globalOutput: Double? = null,
                var nonGlobalInput: Double? = null,
                var nonGlobalCached: Double? = null,
                var nonGlobalOutput: Double? = null,
            )

            val grouped = LinkedHashMap<Triple<String, String?, String?>, GroupRowAcc>()
            var currentModelId: String? = null
            var currentFrom: String? = null
            var currentThrough: String? = null
            var currentType = ""

            for (row in rows.drop(1)) {
                val colModel = row.getOrNull(0).orEmpty()
                if (colModel.isNotBlank()) {
                    val (parsedModel, parsedFrom, parsedThrough) = parseVertexModelLabel(colModel)
                    currentModelId = parsedModel
                    currentFrom = parsedFrom
                    currentThrough = parsedThrough
                }
                val modelId = currentModelId ?: continue
                val colType = row.getOrNull(1).orEmpty()
                if (colType.isNotBlank()) currentType = colType.lowercase(Locale.US)

                val colRegion = row.getOrNull(2).orEmpty().trim().lowercase(Locale.US)
                val isGlobal = colRegion == "global" || colRegion.isEmpty()
                val isNonGlobal = colRegion == "non-global"
                if (!isGlobal && !isNonGlobal) continue

                val mainPrice = row.getOrNull(priceColIdx)?.let(::firstDollar)
                val cachedPrice = if (cacheColIdx >= 0) row.getOrNull(cacheColIdx)?.let(::firstDollar) else null

                val acc = grouped.getOrPut(Triple(modelId, currentFrom, currentThrough)) { GroupRowAcc() }
                when {
                    currentType.startsWith("input (") || currentType == "input" -> {
                        if (isGlobal) {
                            acc.globalInput = mainPrice
                            if (cachedPrice != null) acc.globalCached = cachedPrice
                        } else {
                            acc.nonGlobalInput = mainPrice
                            if (cachedPrice != null) acc.nonGlobalCached = cachedPrice
                        }
                    }
                    currentType.contains("text output") || currentType.startsWith("output") -> {
                        if (isGlobal) acc.globalOutput = mainPrice
                        else acc.nonGlobalOutput = mainPrice
                    }
                }
            }

            for ((key, acc) in grouped) {
                val (modelId, effFrom, effThrough) = key
                for (tier in tiers) {
                    if (acc.globalInput != null && acc.globalOutput != null) {
                        entries += ModelPriceEntry(
                            provider = "vertex",
                            model = modelId,
                            tier = tier,
                            region = "global",
                            inputPerM = acc.globalInput!!,
                            outputPerM = acc.globalOutput!!,
                            cachedInputPerM = acc.globalCached ?: (acc.globalInput!! * 0.1),
                            effectiveFrom = effFrom,
                            effectiveThrough = effThrough,
                        )
                    }
                    if (acc.nonGlobalInput != null && acc.nonGlobalOutput != null) {
                        entries += ModelPriceEntry(
                            provider = "vertex",
                            model = modelId,
                            tier = tier,
                            region = "non-global",
                            inputPerM = acc.nonGlobalInput!!,
                            outputPerM = acc.nonGlobalOutput!!,
                            cachedInputPerM = acc.nonGlobalCached ?: (acc.nonGlobalInput!! * 0.1),
                            effectiveFrom = effFrom,
                            effectiveThrough = effThrough,
                        )
                    }
                }
            }
        }
        return entries
    }

    private data class RateWindow(val rate: Double, val from: String? = null, val through: String? = null)

    private fun combineRateWindows(
        provider: String,
        modelId: String,
        tier: String,
        region: String,
        inputs: List<RateWindow>,
        outputs: List<RateWindow>,
        cached: List<RateWindow>,
    ): List<ModelPriceEntry> {
        if (inputs.isEmpty() || outputs.isEmpty()) return emptyList()
        val windows = inputs.map { it.from to it.through }.distinct()
        return windows.mapNotNull { (from, through) ->
            val inp = inputs.firstOrNull { it.from == from && it.through == through }?.rate ?: inputs.first().rate
            val outp = outputs.firstOrNull { it.from == from && it.through == through }?.rate ?: outputs.first().rate
            val cach = cached.firstOrNull { it.from == from && it.through == through }?.rate
                ?: cached.firstOrNull()?.rate
                ?: (inp * 0.1)
            ModelPriceEntry(
                provider = provider,
                model = modelId,
                tier = tier,
                region = region,
                inputPerM = inp,
                outputPerM = outp,
                cachedInputPerM = cach,
                effectiveFrom = from,
                effectiveThrough = through,
            )
        }
    }

    private fun extractRateWindows(cellText: String): List<RateWindow> {
        val out = ArrayList<RateWindow>()
        for (rawLine in cellText.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.contains("storage price", ignoreCase = true)) continue
            val price = firstDollar(line) ?: continue
            val through = THROUGH_DATE_RE.find(line)?.let(::parseDateMatch)
            val from = STARTING_DATE_RE.find(line)?.let(::parseDateMatch)
            out += RateWindow(price, from = from, through = through)
        }
        return out
    }

    private fun parseVertexModelLabel(rawLabel: String): Triple<String, String?, String?> {
        val through = THROUGH_DATE_RE.find(rawLabel)?.let(::parseDateMatch)
        val from = STARTING_DATE_RE.find(rawLabel)?.let(::parseDateMatch)
        var cleaned = THROUGH_DATE_RE.replace(rawLabel, "")
        cleaned = STARTING_DATE_RE.replace(cleaned, "")
        cleaned = cleaned.replace("*", "").trim()
        val slug = cleaned.lowercase(Locale.US).replace(WHITESPACE_RE, "-")
        return Triple(slug, from, through)
    }

    private fun parseDateMatch(match: MatchResult): String? {
        val month = MONTH_MAP[match.groupValues[1].lowercase(Locale.US)] ?: return null
        val day = match.groupValues[2].toIntOrNull() ?: return null
        val year = match.groupValues[3].toIntOrNull() ?: return null
        return runCatching { LocalDate.of(year, month, day).format(DateTimeFormatter.ISO_LOCAL_DATE) }.getOrNull()
    }

    private fun firstDollar(text: String): Double? =
        DOLLAR_RE.find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull()

    private fun normalizeTier(title: String): String? {
        val lower = title.trim().lowercase(Locale.US)
        return when {
            "standard" in lower -> "standard"
            "priority" in lower -> "priority"
            "batch" in lower -> "batch"
            "flex" in lower -> "flex"
            else -> null
        }
    }

    private fun normalizeVertexTiers(raw: String): List<String> {
        val lower = raw.trim().lowercase(Locale.US)
        return when {
            "flex" in lower && "batch" in lower -> listOf("flex", "batch")
            "priority" in lower -> listOf("priority")
            "batch" in lower -> listOf("batch")
            "flex" in lower -> listOf("flex")
            else -> listOf("standard")
        }
    }

    private fun cleanCell(html: String): String =
        decodeBasicEntities(HTML_TAG_RE.replace(html, " ")).replace(WHITESPACE_RE, " ").trim()

    private fun decodeBasicEntities(s: String): String = s
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .replace("&nbsp;", " ")
}
