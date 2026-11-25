package com.wangzan101.mika.core.run.record

import com.wangzan101.mika.core.model.ModelErrorDiagnostics
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.math.BigDecimal
import java.math.RoundingMode

@Serializable
data class RoleUsageTotals(
    val calls: Int,
    val acceptedCalls: Int,
    val rejectedCalls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long,
    val costUsd: Double,
)

@Serializable
data class ModelUsageTotals(
    val calls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long,
    val costUsd: Double,
)

@Serializable
data class SubAgentUsageSummary(
    val childId: String,
    val parentStep: Int,
    val goal: String,
    val goalHash: String,
    val steps: Int,
    val calls: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedTokens: Long,
    val costUsd: Double,
    val mainCalls: Int,
    val lightCalls: Int,
    val lightAcceptedCalls: Int,
    val lightRejectedCalls: Int,
    val durationMs: Long,
    val outcome: String,
    val logFile: String,
    val unreportedCalls: Int = 0,
    val unreportedReasons: Map<String, Int> = emptyMap(),
    val mainInputTokens: Long = 0,
    val mainOutputTokens: Long = 0,
    val mainCachedTokens: Long = 0,
    val mainCostUsd: Double = 0.0,
    val lightInputTokens: Long = 0,
    val lightOutputTokens: Long = 0,
    val lightCachedTokens: Long = 0,
    val lightCostUsd: Double = 0.0,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class UsageRunSummary(
    @EncodeDefault val type: String = "run_summary",
    val runId: String,
    val versionCode: Int? = null,
    val versionName: String? = null,
    val mainModel: String? = null,
    val mainProvider: String? = null,
    val lightModel: String? = null,
    val lightProvider: String? = null,
    val steps: Int,
    val outcome: String,
    val wallTimeMs: Long,
    val totalsByRole: Map<String, RoleUsageTotals>,
    val totalsByModel: Map<String, ModelUsageTotals>,
    val totalsByTool: Map<String, Int>,
    val subAgents: List<SubAgentUsageSummary>,
    val parentCalls: Int,
    val subAgentCalls: Int,
    val sideCalls: Int,
    val totalCalls: Int,
    val unreportedCalls: Int,
    val unreportedReasons: Map<String, Int>,
    val totalInputTokens: Long,
    val totalOutputTokens: Long,
    val totalCachedTokens: Long,
    val totalCostUsd: Double,
    val maxInputTokens: Int,
    val cacheRate: Double,
    val compressedEvents: Int,
    val foldedSteps: List<Int>,
    val lightAcceptedCalls: Int,
    val lightRejectedCalls: Int,
    val lightAcceptedRate: Double,
    val lightRejectedRate: Double,
    val retryWaitTotalMs: Long,
    val priceSource: String? = null,
    val priceFetchedAt: String? = null,
    val priceFallback: Boolean? = null,
)

object UsageLogFormatter {
    private val USAGE_FIELDS: Map<String, Set<String>> = mapOf(
        "task_started" to setOf(
            "model", "origin", "stepLimit", "tokenBudget",
            "versionCode", "versionName", "mainProvider", "lightModel", "lightProvider",
            "parentRunId", "childId", "parentStep",
        ),
        "prompt_version" to setOf("hash"),
        "model_reply" to setOf(
            "step", "model", "role", "group", "tool", "inputTokens", "cachedTokens",
            "outputTokens", "repliedAtMs", "latencyMs", "retryWaitMs",
            "reason", "decisionScore", "validatorReason", "lightTool", "lightOutputTokens", "lightRetried",
            "provider", "accepted", "attempt", "retryCount", "httpStatus", "errorCode", "errorBody", "errorField",
            "usageReported", "hasImages", "vision", "sameApp", "lastChanged", "forcedMain",
            "cacheMissReason", "systemPromptHash", "promptVersion", "toolListHash",
            "promptTransformEffect", "compactionTrigger", "compactionReason", "foldedSteps", "capCheckResult",
            "estimatedInputTokens", "costUsd",
            "priceInputPerM", "priceOutputPerM", "priceCachedPerM", "priceSource", "priceFetchedAt", "priceTier", "priceFallback",
            "screenHash", "extractedHash",
            "screenChanged", "extractedItemsNew", "historyReadReason",
            "toolSequence", "breakerTrigger",
            "escalatedFromLight", "lightRejectionReason",
        ),
        "compressed" to setOf("foldedSteps", "role", "trigger", "reason"),
        "model_calls" to setOf(
            "calls", "httpCalls", "unreported", "httpUnreported",
            "inputTokens", "cachedTokens", "outputTokens", "deepSeekCost",
            "unreportedReasons", "sideCalls", "sideInputTokens", "sideOutputTokens",
            "sideCachedTokens", "byPurpose",
        ),
        "sub_agent_summary" to setOf(
            "childId", "parentStep", "goal", "goalHash", "steps", "calls",
            "inputTokens", "outputTokens", "cachedTokens", "costUsd",
            "mainCalls", "lightCalls", "lightAcceptedCalls", "lightRejectedCalls",
            "mainInputTokens", "mainOutputTokens", "mainCachedTokens", "mainCostUsd",
            "lightInputTokens", "lightOutputTokens", "lightCachedTokens", "lightCostUsd",
            "durationMs", "outcome", "logFile", "unreportedCalls", "unreportedReasons",
        ),
        "task_ended" to setOf("outcome", "kind"),
    )

    private val OBJECT_FIELDS = setOf("unreportedReasons", "byPurpose", "deepSeekCost")
    private val ARRAY_FIELDS = setOf("toolSequence")

    fun formatLines(
        rawLines: Sequence<String>,
        runId: String = "",
        versionCode: Int? = null,
        versionName: String? = null,
    ): String {
        val acc = SummaryAccumulator(runId, versionCode, versionName)
        val out = StringBuilder()
        for ((index, rawLine) in rawLines.withIndex()) {
            if (rawLine.isBlank()) continue
            val obj = try {
                RunEvent.json.parseToJsonElement(rawLine) as? JsonObject
            } catch (_: Exception) {
                null
            }
            val type = (obj?.get("type") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            if (obj == null || type == null) {
                // Keep incomplete/damaged lines visible as gaps while preserving remaining events.
                out.appendLine("""{"type":"unreadable_event","line":${index + 1}}""")
                continue
            }
            (obj["atMs"] as? JsonPrimitive)?.longOrNull?.let(acc::observeAtMs)
            decodeForSummary(type, obj)?.let(acc::add)
            val filtered = filterEventObject(type, obj) ?: continue
            out.appendLine(filtered.toString())
        }
        val summary = acc.build()
        out.appendLine(RunEvent.json.encodeToString(UsageRunSummary.serializer(), summary))
        return out.toString()
    }

    fun formatEvents(
        events: List<RunEvent>,
        runId: String = "",
        versionCode: Int? = null,
        versionName: String? = null,
    ): String {
        val acc = SummaryAccumulator(runId, versionCode, versionName)
        val out = StringBuilder()
        for (event in events) {
            acc.add(event)
            val obj = RunEvent.json.encodeToJsonElement(RunEvent.serializer(), event) as? JsonObject ?: continue
            val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: continue
            val filtered = filterEventObject(type, obj) ?: continue
            out.appendLine(filtered.toString())
        }
        val summary = acc.build()
        out.appendLine(RunEvent.json.encodeToString(UsageRunSummary.serializer(), summary))
        return out.toString()
    }

    fun buildSummary(
        events: List<RunEvent>,
        runId: String = "",
        versionCode: Int? = null,
        versionName: String? = null,
    ): UsageRunSummary {
        val acc = SummaryAccumulator(runId, versionCode, versionName)
        for (event in events) {
            acc.add(event)
        }
        return acc.build()
    }

    private fun filterEventObject(type: String, event: JsonObject): JsonObject? {
        val allowed = USAGE_FIELDS[type] ?: return null
        val filtered = LinkedHashMap<String, JsonElement>(event.size)
        for ((key, value) in event) {
            if (key != "type" && key != "atMs" && key !in allowed) continue
            val sanitized = sanitizeValue(key, value) ?: continue
            filtered[key] = sanitized
        }
        if (type == "model_reply" && (filtered["role"] as? JsonPrimitive)?.contentOrNull == "light") {
            filtered.putIfAbsent("retryWaitMs", JsonPrimitive(0))
            if (filtered.containsKey("attempt")) filtered.putIfAbsent("retryCount", JsonPrimitive(0))
        }
        return JsonObject(filtered)
    }

    private fun sanitizeValue(key: String, value: JsonElement): JsonElement? = when (value) {
        is JsonNull -> JsonNull
        is JsonPrimitive -> {
            if (!value.isString) value
            else if (key == "errorBody") JsonPrimitive(ModelErrorDiagnostics.scrubAndClip(value.content))
            else JsonPrimitive(Redactor.text(value.content))
        }
        is JsonObject -> if (key in OBJECT_FIELDS) sanitizeObject(value) else null
        is JsonArray -> if (key in ARRAY_FIELDS) sanitizeArray(value) else null
    }

    private fun sanitizeArray(arr: JsonArray): JsonArray {
        val out = ArrayList<JsonElement>(arr.size)
        for (v in arr) {
            when (v) {
                is JsonPrimitive -> out += if (v.isString) JsonPrimitive(Redactor.text(v.content)) else v
                else -> Unit
            }
        }
        return JsonArray(out)
    }

    private fun sanitizeObject(obj: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(obj.size)
        for ((k, v) in obj) {
            when (v) {
                is JsonNull -> out[k] = JsonNull
                is JsonPrimitive -> out[k] = if (v.isString) JsonPrimitive(Redactor.text(v.content)) else v
                else -> Unit
            }
        }
        return JsonObject(out)
    }

    private fun decodeForSummary(type: String, obj: JsonObject): RunEvent? = runCatching {
        when (type) {
            "task_started" -> {
                val safe = obj.patched(
                    overrides = mapOf("goal" to JsonPrimitive("")),
                    defaults = mapOf("atMs" to JsonPrimitive(0L), "model" to JsonPrimitive("")),
                )
                RunEvent.json.decodeFromJsonElement(RunEvent.TaskStarted.serializer(), safe)
            }
            "prompt_version" -> {
                val safe = obj.patched(
                    defaults = mapOf("atMs" to JsonPrimitive(0L), "hash" to JsonPrimitive("")),
                )
                RunEvent.json.decodeFromJsonElement(RunEvent.PromptVersion.serializer(), safe)
            }
            "model_reply" -> {
                val safe = obj.patched(
                    overrides = mapOf("text" to JsonPrimitive(""), "argsJson" to JsonNull),
                    defaults = mapOf(
                        "atMs" to JsonPrimitive(0L),
                        "step" to JsonPrimitive(0),
                        "tool" to JsonNull,
                        "inputTokens" to JsonNull,
                        "outputTokens" to JsonNull,
                        "cachedTokens" to JsonNull,
                    ),
                )
                RunEvent.json.decodeFromJsonElement(RunEvent.ModelReply.serializer(), safe)
            }
            "compressed" -> {
                val safe = obj.patched(
                    defaults = mapOf("atMs" to JsonPrimitive(0L), "foldedSteps" to JsonPrimitive(0)),
                )
                RunEvent.json.decodeFromJsonElement(RunEvent.Compressed.serializer(), safe)
            }
            "model_calls" -> {
                val safe = obj.patched(
                    defaults = mapOf(
                        "atMs" to JsonPrimitive(0L),
                        "calls" to JsonPrimitive(0),
                        "unreported" to JsonPrimitive(0),
                        "inputTokens" to JsonPrimitive(0L),
                        "outputTokens" to JsonPrimitive(0L),
                        "cachedTokens" to JsonPrimitive(0L),
                    ),
                )
                RunEvent.json.decodeFromJsonElement(RunEvent.ModelCalls.serializer(), safe)
            }
            "sub_agent_summary" -> {
                val safe = obj.patched(
                    defaults = mapOf(
                        "atMs" to JsonPrimitive(0L),
                        "childId" to JsonPrimitive(""),
                        "parentStep" to JsonPrimitive(0),
                        "goal" to JsonPrimitive(""),
                        "goalHash" to JsonPrimitive(""),
                        "steps" to JsonPrimitive(0),
                        "calls" to JsonPrimitive(0),
                        "inputTokens" to JsonPrimitive(0L),
                        "outputTokens" to JsonPrimitive(0L),
                        "cachedTokens" to JsonPrimitive(0L),
                        "costUsd" to JsonPrimitive(0.0),
                        "mainCalls" to JsonPrimitive(0),
                        "lightCalls" to JsonPrimitive(0),
                        "lightAcceptedCalls" to JsonPrimitive(0),
                        "lightRejectedCalls" to JsonPrimitive(0),
                        "mainInputTokens" to JsonPrimitive(0L),
                        "mainOutputTokens" to JsonPrimitive(0L),
                        "mainCachedTokens" to JsonPrimitive(0L),
                        "mainCostUsd" to JsonPrimitive(0.0),
                        "lightInputTokens" to JsonPrimitive(0L),
                        "lightOutputTokens" to JsonPrimitive(0L),
                        "lightCachedTokens" to JsonPrimitive(0L),
                        "lightCostUsd" to JsonPrimitive(0.0),
                        "durationMs" to JsonPrimitive(0L),
                        "outcome" to JsonPrimitive(RunMetrics.UNFINISHED),
                        "logFile" to JsonPrimitive(""),
                    ),
                )
                RunEvent.json.decodeFromJsonElement(RunEvent.SubAgentSummary.serializer(), safe)
            }
            "task_ended" -> {
                val safe = obj.patched(
                    overrides = mapOf("answerOrReason" to JsonPrimitive("")),
                    defaults = mapOf("atMs" to JsonPrimitive(0L), "outcome" to JsonPrimitive(RunMetrics.UNFINISHED)),
                )
                RunEvent.json.decodeFromJsonElement(RunEvent.TaskEnded.serializer(), safe)
            }
            else -> null
        }
    }.getOrNull()

    private fun JsonObject.patched(
        overrides: Map<String, JsonElement> = emptyMap(),
        defaults: Map<String, JsonElement> = emptyMap(),
    ): JsonObject {
        val copy = LinkedHashMap<String, JsonElement>(size + defaults.size)
        copy.putAll(this)
        for ((k, v) in defaults) {
            if (k !in copy) copy[k] = v
        }
        for ((k, v) in overrides) {
            copy[k] = v
        }
        return JsonObject(copy)
    }

    private class SummaryAccumulator(
        private val runId: String,
        private val versionCodeOverride: Int?,
        private val versionNameOverride: String?,
    ) {
        private var started: RunEvent.TaskStarted? = null
        private var ended: RunEvent.TaskEnded? = null
        private var firstAtMs: Long? = null
        private var lastAtMs: Long? = null
        private val replies = ArrayList<RunEvent.ModelReply>()
        private val compressed = ArrayList<RunEvent.Compressed>()
        private var modelCalls: RunEvent.ModelCalls? = null
        private val subAgents = ArrayList<RunEvent.SubAgentSummary>()

        fun observeAtMs(atMs: Long) {
            if (firstAtMs == null || atMs < firstAtMs!!) firstAtMs = atMs
            if (lastAtMs == null || atMs > lastAtMs!!) lastAtMs = atMs
        }

        fun add(e: RunEvent) {
            observeAtMs(e.atMs)
            when (e) {
                is RunEvent.TaskStarted -> if (started == null) started = e
                is RunEvent.ModelReply -> replies += e
                is RunEvent.Compressed -> compressed += e
                is RunEvent.ModelCalls -> modelCalls = e
                is RunEvent.SubAgentSummary -> subAgents += e
                is RunEvent.TaskEnded -> if (ended == null) ended = e
                else -> Unit
            }
        }

        fun build(): UsageRunSummary {
            val start = started
            val end = ended
            val versionCode = versionCodeOverride ?: start?.versionCode
            val versionName = versionNameOverride ?: start?.versionName
            val mainModel = start?.model?.takeIf { it.isNotBlank() }
                ?: replies.firstOrNull { it.role == null || it.role == "main" }?.model?.takeIf { it.isNotBlank() }
            val mainProvider = start?.mainProvider?.takeIf { it.isNotBlank() }
                ?: replies.firstOrNull { it.role == null || it.role == "main" }?.provider?.takeIf { it.isNotBlank() }
            val lightModel = start?.lightModel?.takeIf { it.isNotBlank() }
                ?: replies.firstOrNull { it.role == "light" || it.role == "light_rejected" }?.model?.takeIf { it.isNotBlank() }
            val lightProvider = start?.lightProvider?.takeIf { it.isNotBlank() }
                ?: replies.firstOrNull { it.role == "light" || it.role == "light_rejected" }?.provider?.takeIf { it.isNotBlank() }

            val startMs = start?.atMs ?: firstAtMs
            val endMs = end?.atMs ?: lastAtMs
            val wallTimeMs = if (startMs != null && endMs != null) (endMs - startMs).coerceAtLeast(0L) else 0L

            var steps = 0
            var maxInputTokens = 0
            var retryWaitTotalMs = 0L
            var parentInput = 0L
            var parentOutput = 0L
            var parentCached = 0L
            var parentCost = 0.0
            var lightAccepted = 0
            var lightRejected = 0
            var parentMainCalls = 0
            var parentLightCalls = 0

            val roleAcc = LinkedHashMap<String, RoleAcc>()
            val modelAcc = LinkedHashMap<String, ModelAcc>()
            val toolAcc = LinkedHashMap<String, Int>()

            for (r in replies) {
                val role = resolvedRole(r)
                val isRejected = r.role == "light_rejected" || r.accepted == false
                val isAccepted = !isRejected
                val modelName = r.model?.takeIf { it.isNotBlank() }
                    ?: if (role == "light") (lightModel ?: mainModel ?: "unknown") else (mainModel ?: "unknown")
                val providerName = r.provider?.takeIf { it.isNotBlank() }
                    ?: if (role == "light") (lightProvider ?: mainProvider) else mainProvider
                val inTok = (r.inputTokens ?: 0).coerceAtLeast(0)
                val outTok = (r.outputTokens ?: 0).coerceAtLeast(0)
                val cacheTok = (r.cachedTokens ?: 0).coerceAtLeast(0)
                val cost = replyCostUsd(r, modelName, providerName, role)

                if (r.acted) {
                    steps = maxOf(steps, r.step)
                }
                if (inTok > maxInputTokens) maxInputTokens = inTok
                retryWaitTotalMs += r.retryWaitMs.coerceAtLeast(0L)

                parentInput += inTok.toLong()
                parentOutput += outTok.toLong()
                parentCached += cacheTok.toLong()
                parentCost += cost

                if (role == "light") {
                    parentLightCalls++
                    if (isAccepted) lightAccepted++ else lightRejected++
                } else if (role == "main") {
                    parentMainCalls++
                }

                val ra = roleAcc.getOrPut(role) { RoleAcc() }
                ra.calls++
                if (isAccepted) ra.acceptedCalls++ else ra.rejectedCalls++
                ra.inputTokens += inTok.toLong()
                ra.outputTokens += outTok.toLong()
                ra.cachedTokens += cacheTok.toLong()
                ra.costUsd += cost

                val ma = modelAcc.getOrPut(modelName) { ModelAcc() }
                ma.calls++
                ma.inputTokens += inTok.toLong()
                ma.outputTokens += outTok.toLong()
                ma.cachedTokens += cacheTok.toLong()
                ma.costUsd += cost

                r.tool?.takeIf { it.isNotBlank() }?.let { t ->
                    toolAcc[t] = (toolAcc[t] ?: 0) + 1
                }
            }

            val mc = modelCalls
            val sideCalls = (mc?.sideCalls ?: 0).coerceAtLeast(0)
            val sideInput = (mc?.sideInputTokens ?: 0L).coerceAtLeast(0L)
            val sideOutput = (mc?.sideOutputTokens ?: 0L).coerceAtLeast(0L)
            val sideCached = (mc?.sideCachedTokens ?: 0L).coerceAtLeast(0L)
            val sideCost = estimateSideCostUsd(
                sideInput = sideInput,
                sideCached = sideCached,
                sideOutput = sideOutput,
                mainModel = mainModel,
                mainProvider = mainProvider,
                lightModel = lightModel,
                lightProvider = lightProvider,
                byPurpose = mc?.byPurpose,
                parentMainCalls = parentMainCalls,
                parentLightCalls = parentLightCalls,
            )

            val subAgentSummaries = subAgents.map { s ->
                SubAgentUsageSummary(
                    childId = s.childId,
                    parentStep = s.parentStep,
                    goal = Redactor.text(s.goal),
                    goalHash = s.goalHash,
                    steps = s.steps,
                    calls = s.calls,
                    inputTokens = s.inputTokens,
                    outputTokens = s.outputTokens,
                    cachedTokens = s.cachedTokens,
                    costUsd = roundUsd(s.costUsd),
                    mainCalls = s.mainCalls,
                    lightCalls = s.lightCalls,
                    lightAcceptedCalls = s.lightAcceptedCalls,
                    lightRejectedCalls = s.lightRejectedCalls,
                    durationMs = s.durationMs,
                    outcome = s.outcome,
                    logFile = Redactor.text(s.logFile),
                    unreportedCalls = s.unreportedCalls,
                    unreportedReasons = s.unreportedReasons,
                    mainInputTokens = s.mainInputTokens,
                    mainOutputTokens = s.mainOutputTokens,
                    mainCachedTokens = s.mainCachedTokens,
                    mainCostUsd = roundUsd(s.mainCostUsd),
                    lightInputTokens = s.lightInputTokens,
                    lightOutputTokens = s.lightOutputTokens,
                    lightCachedTokens = s.lightCachedTokens,
                    lightCostUsd = roundUsd(s.lightCostUsd),
                )
            }

            val subCalls = subAgents.sumOf { it.calls.coerceAtLeast(0) }
            val subInput = subAgents.sumOf { it.inputTokens.coerceAtLeast(0L) }
            val subOutput = subAgents.sumOf { it.outputTokens.coerceAtLeast(0L) }
            val subCached = subAgents.sumOf { it.cachedTokens.coerceAtLeast(0L) }
            val subCost = subAgents.sumOf { finiteNonNegative(it.costUsd) }

            val unreportedReasonCounts = LinkedHashMap<String, Int>()
            fun addUnreported(reason: String, count: Int) {
                if (count <= 0) return
                unreportedReasonCounts[reason] = (unreportedReasonCounts[reason] ?: 0) + count
            }

            val parentUnreported: Int = if (mc != null) {
                val u = mc.unreported.coerceAtLeast(0)
                val reasons = mc.unreportedReasons
                if (reasons != null) {
                    reasons.forEach { (k, v) -> addUnreported(k, v) }
                    val accounted = reasons.values.sum()
                    if (u > accounted) addUnreported("unknown", u - accounted)
                } else if (u > 0) {
                    var accounted = 0
                    for (r in replies) {
                        if (accounted >= u) break
                        if (isMissingUsage(r)) {
                            addUnreported(replyUnreportedReason(r), 1)
                            accounted++
                        }
                    }
                    if (u > accounted) addUnreported("unknown", u - accounted)
                }
                u
            } else {
                var missing = 0
                for (r in replies) {
                    if (isMissingUsage(r)) {
                        addUnreported(replyUnreportedReason(r), 1)
                        missing++
                    }
                }
                missing
            }

            var subUnreported = 0
            for (s in subAgents) {
                val u = s.unreportedCalls.coerceAtLeast(0)
                subUnreported += u
                s.unreportedReasons.forEach { (k, v) -> addUnreported(k, v) }
                val accounted = s.unreportedReasons.values.sum()
                if (u > accounted) addUnreported("unknown", u - accounted)
            }

            val parentCalls = replies.size
            val totalCalls = parentCalls + sideCalls + subCalls
            val totalInput = parentInput + sideInput + subInput
            val totalOutput = parentOutput + sideOutput + subOutput
            val totalCached = parentCached + sideCached + subCached
            val totalCost = roundUsd(parentCost + sideCost + subCost)
            val cacheRate = if (totalInput > 0L) {
                (totalCached.toDouble() / totalInput.toDouble()).takeIf { it.isFinite() } ?: 0.0
            } else 0.0

            val lightTotal = lightAccepted + lightRejected
            val lightAcceptedRate = if (lightTotal > 0) lightAccepted.toDouble() / lightTotal.toDouble() else 0.0
            val lightRejectedRate = if (lightTotal > 0) lightRejected.toDouble() / lightTotal.toDouble() else 0.0

            return UsageRunSummary(
                runId = runId,
                versionCode = versionCode,
                versionName = versionName,
                mainModel = mainModel,
                mainProvider = mainProvider,
                lightModel = lightModel,
                lightProvider = lightProvider,
                steps = steps,
                outcome = end?.outcome ?: RunMetrics.UNFINISHED,
                wallTimeMs = wallTimeMs,
                totalsByRole = roleAcc.mapValues { (_, v) ->
                    RoleUsageTotals(
                        calls = v.calls,
                        acceptedCalls = v.acceptedCalls,
                        rejectedCalls = v.rejectedCalls,
                        inputTokens = v.inputTokens,
                        outputTokens = v.outputTokens,
                        cachedTokens = v.cachedTokens,
                        costUsd = roundUsd(v.costUsd),
                    )
                },
                totalsByModel = modelAcc.mapValues { (_, v) ->
                    ModelUsageTotals(
                        calls = v.calls,
                        inputTokens = v.inputTokens,
                        outputTokens = v.outputTokens,
                        cachedTokens = v.cachedTokens,
                        costUsd = roundUsd(v.costUsd),
                    )
                },
                totalsByTool = toolAcc,
                subAgents = subAgentSummaries,
                parentCalls = parentCalls,
                subAgentCalls = subCalls,
                sideCalls = sideCalls,
                totalCalls = totalCalls,
                unreportedCalls = parentUnreported + subUnreported,
                unreportedReasons = unreportedReasonCounts,
                totalInputTokens = totalInput,
                totalOutputTokens = totalOutput,
                totalCachedTokens = totalCached,
                totalCostUsd = totalCost,
                maxInputTokens = maxInputTokens,
                cacheRate = cacheRate,
                compressedEvents = compressed.size,
                foldedSteps = compressed.map { it.foldedSteps },
                lightAcceptedCalls = lightAccepted,
                lightRejectedCalls = lightRejected,
                lightAcceptedRate = lightAcceptedRate,
                lightRejectedRate = lightRejectedRate,
                retryWaitTotalMs = retryWaitTotalMs,
                priceSource = replies.firstNotNullOfOrNull { it.priceSource },
                priceFetchedAt = replies.firstNotNullOfOrNull { it.priceFetchedAt },
                priceFallback = replies.mapNotNull { it.priceFallback }.takeIf { it.isNotEmpty() }?.any { it },
            )
        }
    }

    private class RoleAcc(
        var calls: Int = 0,
        var acceptedCalls: Int = 0,
        var rejectedCalls: Int = 0,
        var inputTokens: Long = 0L,
        var outputTokens: Long = 0L,
        var cachedTokens: Long = 0L,
        var costUsd: Double = 0.0,
    )

    private class ModelAcc(
        var calls: Int = 0,
        var inputTokens: Long = 0L,
        var outputTokens: Long = 0L,
        var cachedTokens: Long = 0L,
        var costUsd: Double = 0.0,
    )

    private fun resolvedRole(e: RunEvent.ModelReply): String = when (e.role) {
        "light_rejected", "light" -> "light"
        null, "" -> "main"
        else -> e.role
    }

    private fun isMissingUsage(e: RunEvent.ModelReply): Boolean =
        e.usageReported == false || (e.inputTokens == null && e.outputTokens == null)

    private fun replyUnreportedReason(e: RunEvent.ModelReply): String =
        e.httpStatus?.let { "http_$it" }
            ?: e.errorCode?.takeIf { it.isNotBlank() }
            ?: "usage_omitted_by_provider"

    private fun replyCostUsd(e: RunEvent.ModelReply, model: String?, provider: String?, role: String): Double {
        e.costUsd?.takeIf { it.isFinite() && it >= 0.0 }?.let { return roundUsd(it) }
        val inTok = e.inputTokens
        val outTok = e.outputTokens
        if (inTok == null && outTok == null) return 0.0
        return UsageCostEstimator.usd(
            model = model,
            role = role,
            inputTokens = (inTok ?: 0).toLong(),
            cachedTokens = (e.cachedTokens ?: 0).toLong(),
            outputTokens = (outTok ?: 0).toLong(),
            provider = provider,
        )
    }

    private fun estimateSideCostUsd(
        sideInput: Long,
        sideCached: Long,
        sideOutput: Long,
        mainModel: String?,
        mainProvider: String?,
        lightModel: String?,
        lightProvider: String?,
        byPurpose: Map<String, Int>?,
        parentMainCalls: Int,
        parentLightCalls: Int,
    ): Double {
        if (sideInput <= 0L && sideOutput <= 0L) return 0.0
        if (byPurpose != null) {
            val extraMain = (byPurpose["main"] ?: 0) - parentMainCalls
            val extraLight = (byPurpose["light"] ?: 0) - parentLightCalls
            val extraVision = byPurpose["vision"] ?: 0
            val extraOther = (byPurpose["other"] ?: 0) + (byPurpose["proactive"] ?: 0)
            if (extraMain > 0 && extraLight <= 0 && extraVision <= 0 && extraOther <= 0) {
                return UsageCostEstimator.usd(mainModel, "main", sideInput, sideCached, sideOutput, provider = mainProvider)
            }
            if (extraLight > 0 && extraMain <= 0) {
                return UsageCostEstimator.usd(lightModel ?: mainModel, "light", sideInput, sideCached, sideOutput, provider = lightProvider ?: mainProvider)
            }
        }
        val fallbackModel = lightModel ?: mainModel
        val fallbackProvider = if (lightModel != null) (lightProvider ?: mainProvider) else mainProvider
        val fallbackRole = if (lightModel != null) "light" else "main"
        return UsageCostEstimator.usd(fallbackModel, fallbackRole, sideInput, sideCached, sideOutput, provider = fallbackProvider)
    }

    private fun finiteNonNegative(value: Double): Double =
        if (value.isFinite() && value >= 0.0) value else 0.0

    private fun roundUsd(value: Double): Double {
        if (!value.isFinite() || value <= 0.0) return 0.0
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).toDouble()
    }
}
