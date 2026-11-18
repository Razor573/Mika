package com.wangzan101.mika.core.usage

import com.wangzan101.mika.core.model.Usage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ModelPricing(
    val inputPerM: Double,
    val outputPerM: Double,
    val cachedInputPerM: Double? = null,
    val cacheReadMultiplier: Double? = null,
) {
    fun effectiveCachedInputPerM(): Double =
        cachedInputPerM ?: cacheReadMultiplier?.let { inputPerM * it } ?: inputPerM
}

@Serializable
data class ModelRateOverride(
    val inputPerMUsd: Double,
    val outputPerMUsd: Double,
    val cachedInputPerMUsd: Double? = null,
)

@Serializable
data class UsdCost(
    val usd: Double? = null,
    val exact: Boolean = false,
    val partial: Boolean = false,
)

data class CallCostEstimate(
    val usd: Double,
    val exact: Boolean,
    val isFree: Boolean = false,
)

/** Per-task USD spend cap presets and valid bounds; null means unlimited. */
object UsdBudgetLimits {
    const val MIN = 0.01
    const val MAX = 1000.0
    val PRESETS = listOf(0.05, 0.15, 0.50, 1.00)
    val UNLIMITED: Double? = null

    fun valid(value: Double): Boolean = value.isFinite() && value in MIN..MAX
}

@Serializable
private data class PricingCatalog(val models: Map<String, ModelPricing> = emptyMap())

/**
 * Estimates per-call USD spend across providers.
 *
 * Resolution order:
 * 1. Exact provider-reported [Usage.costUsd] (e.g. OpenRouter `usage.cost`).
 * 2. `:free` model ID suffix -> `$0.00` USD (`isFree = true`).
 * 3. User custom per-1M override ([ModelRateOverride]).
 * 4. Bundled `pricing/model_pricing.json` table (with prefix/date normalization).
 * 5. Otherwise `null` (never fabricates `$0.00` for unpriced/local models).
 */
class ModelCostEstimator(private val bundledRates: Map<String, ModelPricing>) {
    fun isFreeModel(model: String): Boolean = model.trim().endsWith(":free", ignoreCase = true)

    fun bundledRateFor(model: String): ModelPricing? {
        for (candidate in candidateKeys(model)) {
            bundledRates[candidate]?.let { return it }
        }
        return null
    }

    fun estimateCall(
        model: String,
        usage: Usage?,
        override: ModelRateOverride? = null,
    ): CallCostEstimate? {
        if (usage == null) return null
        val reported = usage.costUsd
        if (reported != null && reported.isFinite() && reported >= 0.0) {
            return CallCostEstimate(
                usd = reported,
                exact = true,
                isFree = isFreeModel(model) && reported == 0.0,
            )
        }
        if (usage.inputTokens < 0 || usage.outputTokens < 0 || usage.cachedInputTokens !in 0..usage.inputTokens) {
            return null
        }
        if (isFreeModel(model)) {
            return CallCostEstimate(usd = 0.0, exact = true, isFree = true)
        }
        if (override != null) {
            if (!override.inputPerMUsd.isFinite() || override.inputPerMUsd < 0.0 ||
                !override.outputPerMUsd.isFinite() || override.outputPerMUsd < 0.0 ||
                (override.cachedInputPerMUsd != null && (!override.cachedInputPerMUsd.isFinite() || override.cachedInputPerMUsd < 0.0))
            ) {
                return null
            }
            val usd = computeUsd(
                usage = usage,
                inputPerM = override.inputPerMUsd,
                outputPerM = override.outputPerMUsd,
                cachedInputPerM = override.cachedInputPerMUsd ?: override.inputPerMUsd,
            )
            return CallCostEstimate(usd = usd, exact = false, isFree = false)
        }
        val rate = bundledRateFor(model) ?: return null
        val usd = computeUsd(
            usage = usage,
            inputPerM = rate.inputPerM,
            outputPerM = rate.outputPerM,
            cachedInputPerM = rate.effectiveCachedInputPerM(),
        )
        return CallCostEstimate(usd = usd, exact = false, isFree = false)
    }

    private fun computeUsd(
        usage: Usage,
        inputPerM: Double,
        outputPerM: Double,
        cachedInputPerM: Double,
    ): Double {
        val cached = usage.cachedInputTokens.toLong()
        val nonCached = (usage.inputTokens - usage.cachedInputTokens).coerceAtLeast(0).toLong()
        val output = usage.outputTokens.toLong()
        return (nonCached * inputPerM + cached * cachedInputPerM + output * outputPerM) / TOKENS_PER_MILLION
    }

    companion object {
        private const val RESOURCE_PATH = "/pricing/model_pricing.json"
        private const val TOKENS_PER_MILLION = 1_000_000.0
        private val json = Json { ignoreUnknownKeys = true }
        private val VENDOR_PREFIXES = listOf("google/", "openai/", "anthropic/", "deepseek/")
        private val PUBLISHERS_MODELS = Regex("""^(?:.*/)?publishers/[^/]+/models/""")
        private val DATE_OR_BUILD_SUFFIX = Regex("""-(?:\d{4}-\d{2}-\d{2}|\d{8}|\d{4}|\d{3})$""")

        val DEFAULT: ModelCostEstimator by lazy { loadBundled() }

        fun isFreeModel(model: String): Boolean = DEFAULT.isFreeModel(model)

        fun loadBundled(): ModelCostEstimator {
            val loaded = runCatching {
                ModelCostEstimator::class.java.getResourceAsStream(RESOURCE_PATH)
                    ?.bufferedReader(Charsets.UTF_8)
                    ?.use { it.readText() }
                    ?.let { json.decodeFromString(PricingCatalog.serializer(), it).models }
            }.getOrNull()?.takeIf { it.isNotEmpty() }
            return ModelCostEstimator(loaded ?: FALLBACK_RATES)
        }

        internal fun normalizeModelId(raw: String): String {
            var s = raw.trim().lowercase()
            if (s.isEmpty()) return ""
            s = PUBLISHERS_MODELS.replace(s, "")
            while (true) {
                val next = when {
                    s.startsWith("models/") -> s.removePrefix("models/")
                    else -> VENDOR_PREFIXES.firstNotNullOfOrNull { prefix ->
                        if (s.startsWith(prefix)) s.removePrefix(prefix) else null
                    }
                }
                if (next == null || next == s) break
                s = next
            }
            if (!s.endsWith(":free")) {
                val colon = s.indexOf(':')
                if (colon > 0) s = s.substring(0, colon)
            }
            return s
        }

        private fun candidateKeys(raw: String): List<String> {
            val base = normalizeModelId(raw)
            if (base.isEmpty()) return emptyList()
            val out = LinkedHashSet<String>()
            fun addWithVariant(id: String) {
                out += id
                if (id.endsWith("-5-5")) out += id.removeSuffix("-5-5") + "-5.5"
                if (id.endsWith("-4.5")) out += id.removeSuffix("-4.5") + "-4-5"
            }
            addWithVariant(base)
            var stripped = base
            repeat(2) {
                val next = DATE_OR_BUILD_SUFFIX.replace(stripped, "")
                if (next != stripped && next.isNotEmpty()) {
                    addWithVariant(next)
                    stripped = next
                }
            }
            if (stripped.endsWith("-latest")) {
                addWithVariant(stripped.removeSuffix("-latest"))
            }
            return out.toList()
        }

        private val FALLBACK_RATES: Map<String, ModelPricing> = mapOf(
            "gemini-3.1-flash-lite-preview" to ModelPricing(0.075, 0.30, cachedInputPerM = 0.01875),
            "gemini-3.8-flash-lite" to ModelPricing(0.25, 1.00, cachedInputPerM = 0.0625),
            "gemini-3.8-flash" to ModelPricing(0.75, 3.75, cachedInputPerM = 0.1875),
            "gemini-4-flash" to ModelPricing(0.75, 3.75, cachedInputPerM = 0.1875),
            "gemini-4-argon" to ModelPricing(2.00, 10.00, cachedInputPerM = 0.50),
            "gemini-4-carbon" to ModelPricing(2.00, 10.00, cachedInputPerM = 0.50),
            "gemini-4-hydrogen" to ModelPricing(4.00, 20.00, cachedInputPerM = 1.00),
            "gemini-3.1-pro-preview" to ModelPricing(2.00, 12.00, cachedInputPerM = 0.50),
            "gemini-3.8-pro" to ModelPricing(2.50, 15.00, cachedInputPerM = 0.625),
            "gemini-4-pro" to ModelPricing(3.00, 15.00, cachedInputPerM = 0.75),
            "gemini-2.5-flash" to ModelPricing(0.30, 2.50, cachedInputPerM = 0.075),
            "gemini-2.5-flash-lite" to ModelPricing(0.10, 0.40, cachedInputPerM = 0.025),
            "gemini-2.5-pro" to ModelPricing(1.25, 10.00, cachedInputPerM = 0.3125),
            "gemini-decision-1" to ModelPricing(0.10, 0.40, cachedInputPerM = 0.025),
            "gemini-2.0-flash" to ModelPricing(0.10, 0.40, cachedInputPerM = 0.025),
            "gemini-1.5-flash" to ModelPricing(0.075, 0.30, cachedInputPerM = 0.01875),
            "gemini-1.5-pro" to ModelPricing(1.25, 5.00, cachedInputPerM = 0.3125),
            "gpt-6-nano" to ModelPricing(0.05, 0.40, cachedInputPerM = 0.005),
            "gpt-6-mini" to ModelPricing(0.25, 2.00, cachedInputPerM = 0.025),
            "gpt-6" to ModelPricing(1.50, 10.00, cachedInputPerM = 0.15),
            "gpt-6.1-terra" to ModelPricing(1.00, 5.00, cachedInputPerM = 0.10),
            "gpt-5-nano" to ModelPricing(0.05, 0.40, cachedInputPerM = 0.005),
            "gpt-5-mini" to ModelPricing(0.25, 2.00, cachedInputPerM = 0.025),
            "gpt-5" to ModelPricing(1.25, 10.00, cachedInputPerM = 0.125),
            "gpt-4o" to ModelPricing(2.50, 10.00, cachedInputPerM = 1.25),
            "gpt-4o-mini" to ModelPricing(0.15, 0.60, cachedInputPerM = 0.075),
            "o3" to ModelPricing(2.00, 8.00, cachedInputPerM = 0.50),
            "o4-mini" to ModelPricing(1.10, 4.40, cachedInputPerM = 0.275),
            "claude-haiku-5.5" to ModelPricing(0.10, 0.50, cacheReadMultiplier = 0.05),
            "claude-sonnet-5.5" to ModelPricing(2.00, 10.00, cacheReadMultiplier = 0.05),
            "claude-opus-5.5" to ModelPricing(4.00, 20.00, cacheReadMultiplier = 0.05),
            "claude-sonnet-4-5" to ModelPricing(3.00, 15.00, cacheReadMultiplier = 0.10),
            "claude-haiku-4-5" to ModelPricing(1.00, 5.00, cacheReadMultiplier = 0.10),
            "claude-opus-4-5" to ModelPricing(5.00, 25.00, cacheReadMultiplier = 0.10),
            "deepseek-chat" to ModelPricing(0.27, 1.10, cachedInputPerM = 0.07),
            "deepseek-reasoner" to ModelPricing(0.55, 2.19, cachedInputPerM = 0.14),
            "deepseek-v4-flash" to ModelPricing(0.25, 0.42, cachedInputPerM = 0.03),
        )
    }
}

/** Thread-safe per-run accumulator of estimated/exact USD cost across calls. */
class RunUsdCost(
    private val estimator: ModelCostEstimator = ModelCostEstimator.DEFAULT,
    private val rateOverrides: (providerId: String, model: String) -> ModelRateOverride? = { _, _ -> null },
) {
    private var totalUsd = 0.0
    private var hasPricedCall = false
    private var allExact = true
    private var partial = false

    @Synchronized
    fun add(source: UsageSource, usage: Usage?, httpAttempts: Int) {
        if (httpAttempts <= 0 && usage == null) return
        if (httpAttempts > 1) partial = true
        if (usage == null) {
            partial = true
            return
        }
        val override = rateOverrides(source.providerId, source.model)
        val estimate = estimator.estimateCall(source.model, usage, override)
        if (estimate == null) {
            partial = true
            return
        }
        hasPricedCall = true
        if (!estimate.exact) allExact = false
        totalUsd += estimate.usd
    }

    @Synchronized
    fun snapshot(): UsdCost? {
        if (!hasPricedCall) return null
        return UsdCost(usd = totalUsd, exact = allExact, partial = partial)
    }

    @Synchronized
    fun currentUsd(): Double = totalUsd
}
