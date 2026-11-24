package com.wangzan101.mika.core.run.record

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneOffset

@Serializable
data class ModelPriceEntry(
    val provider: String,
    val model: String,
    val tier: String = "standard",
    val region: String = "global",
    val inputPerM: Double,
    val outputPerM: Double,
    val cachedInputPerM: Double,
    val effectiveFrom: String? = null,
    val effectiveThrough: String? = null,
) {
    fun isEffectiveOn(date: LocalDate): Boolean {
        val from = effectiveFrom?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val through = effectiveThrough?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (from != null && date.isBefore(from)) return false
        if (through != null && date.isAfter(through)) return false
        return true
    }
}

@Serializable
data class PricingSnapshot(
    val fetchedAt: String,
    val source: String,
    val entries: List<ModelPriceEntry>,
)

@Serializable
data class ResolvedModelPrice(
    val provider: String,
    val model: String,
    val tier: String,
    val inputPerM: Double,
    val outputPerM: Double,
    val cachedInputPerM: Double,
    val source: String,
    val fetchedAt: String,
    val fallback: Boolean,
    val effectiveFrom: String? = null,
    val effectiveThrough: String? = null,
)

object BundledPricingFallback {
    const val AS_OF_DATE = "2026-11-24"

    val ENTRIES: List<ModelPriceEntry> = listOf(
        // Vertex AI official rates (global region)
        ModelPriceEntry("vertex", "gemini-4-hydrogen", "standard", "global", 1.50, 7.50, 0.075),
        ModelPriceEntry("vertex", "gemini-4-hydrogen", "priority", "global", 2.70, 13.50, 0.135),
        ModelPriceEntry("vertex", "gemini-4-hydrogen", "batch", "global", 0.75, 3.75, 0.0375),
        ModelPriceEntry("vertex", "gemini-4-hydrogen", "flex", "global", 0.75, 3.75, 0.0375),
        ModelPriceEntry("vertex", "gemini-3.8-flash", "standard", "global", 0.75, 3.75, 0.075, effectiveThrough = "2026-12-31"),
        ModelPriceEntry("vertex", "gemini-3.8-flash", "standard", "global", 1.50, 7.50, 0.15, effectiveFrom = "2027-01-01"),
        ModelPriceEntry("vertex", "gemini-3.8-flash", "priority", "global", 1.35, 6.75, 0.135, effectiveThrough = "2026-12-31"),
        ModelPriceEntry("vertex", "gemini-3.8-flash", "priority", "global", 2.70, 13.50, 0.27, effectiveFrom = "2027-01-01"),
        ModelPriceEntry("vertex", "gemini-3.8-flash", "batch", "global", 0.375, 1.875, 0.0375, effectiveThrough = "2026-12-31"),
        ModelPriceEntry("vertex", "gemini-3.8-flash", "batch", "global", 0.75, 3.75, 0.075, effectiveFrom = "2027-01-01"),
        ModelPriceEntry("vertex", "gemini-3.8-flash", "flex", "global", 0.375, 1.875, 0.0375, effectiveThrough = "2026-12-31"),
        ModelPriceEntry("vertex", "gemini-3.8-flash", "flex", "global", 0.75, 3.75, 0.075, effectiveFrom = "2027-01-01"),
        ModelPriceEntry("vertex", "gemini-4-neon", "standard", "global", 3.00, 15.00, 0.15),
        ModelPriceEntry("vertex", "gemini-3.1-pro-preview", "standard", "global", 2.00, 12.00, 0.20),
        ModelPriceEntry("vertex", "gemini-2.5-flash", "standard", "global", 0.30, 2.50, 0.03),
        ModelPriceEntry("vertex", "gemini-2.5-pro", "standard", "global", 1.25, 10.00, 0.125),

        // Gemini Developer API official rates
        ModelPriceEntry("gemini", "gemini-4-hydrogen", "standard", "global", 1.50, 7.50, 0.075),
        ModelPriceEntry("gemini", "gemini-4-hydrogen", "batch", "global", 0.75, 3.75, 0.0375),
        ModelPriceEntry("gemini", "gemini-3.8-flash", "standard", "global", 0.75, 3.75, 0.075, effectiveThrough = "2026-12-31"),
        ModelPriceEntry("gemini", "gemini-3.8-flash", "standard", "global", 1.50, 7.50, 0.15, effectiveFrom = "2027-01-01"),
        ModelPriceEntry("gemini", "gemini-3.8-flash", "batch", "global", 0.375, 1.875, 0.0375, effectiveThrough = "2026-12-31"),
        ModelPriceEntry("gemini", "gemini-3.8-flash", "batch", "global", 0.75, 3.75, 0.075, effectiveFrom = "2027-01-01"),
        ModelPriceEntry("gemini", "gemini-4-neon", "standard", "global", 3.00, 15.00, 0.15),
        ModelPriceEntry("gemini", "gemini-3.1-pro-preview", "standard", "global", 2.00, 12.00, 0.20),
        ModelPriceEntry("gemini", "gemini-2.5-flash", "standard", "global", 0.30, 2.50, 0.03),
        ModelPriceEntry("gemini", "gemini-2.5-pro", "standard", "global", 1.25, 10.00, 0.125),
    )

    val SNAPSHOT = PricingSnapshot(
        fetchedAt = AS_OF_DATE,
        source = "bundled_fallback",
        entries = ENTRIES,
    )
}

internal object PricingResolver {
    fun normalizeModelId(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return ""
        val tail = trimmed.substringAfterLast('/')
        return tail.lowercase()
    }

    fun normalizeProvider(provider: String?, model: String?): String {
        val p = provider?.trim()?.lowercase().orEmpty()
        return when {
            p == "vertex" || p.contains("vertex") -> "vertex"
            p == "gemini" || p.contains("gemini") || p.contains("google") -> "gemini"
            p.isNotEmpty() -> p
            normalizeModelId(model).contains("gemini") -> "gemini"
            else -> "unknown"
        }
    }

    fun resolve(
        catalog: PricingSnapshot?,
        provider: String?,
        model: String?,
        role: String?,
        tier: String = "standard",
        region: String = "global",
        asOf: LocalDate = LocalDate.now(ZoneOffset.UTC),
    ): ResolvedModelPrice {
        val normProvider = normalizeProvider(provider, model)
        val normModel = normalizeModelId(model)
        val normTier = tier.trim().lowercase().ifEmpty { "standard" }
        val normRegion = region.trim().lowercase().ifEmpty { "global" }

        if (catalog != null && catalog.entries.isNotEmpty()) {
            matchEntry(catalog.entries, normProvider, normModel, normTier, normRegion, asOf)?.let { entry ->
                return ResolvedModelPrice(
                    provider = entry.provider,
                    model = entry.model,
                    tier = entry.tier,
                    inputPerM = entry.inputPerM,
                    outputPerM = entry.outputPerM,
                    cachedInputPerM = entry.cachedInputPerM,
                    source = catalog.source,
                    fetchedAt = catalog.fetchedAt,
                    fallback = false,
                    effectiveFrom = entry.effectiveFrom,
                    effectiveThrough = entry.effectiveThrough,
                )
            }
        }

        // Bundled fallback: always flagged with fallback = true and bundled AS_OF_DATE.
        matchEntry(BundledPricingFallback.ENTRIES, normProvider, normModel, normTier, normRegion, asOf)?.let { entry ->
            return ResolvedModelPrice(
                provider = entry.provider,
                model = entry.model,
                tier = entry.tier,
                inputPerM = entry.inputPerM,
                outputPerM = entry.outputPerM,
                cachedInputPerM = entry.cachedInputPerM,
                source = "bundled_fallback",
                fetchedAt = BundledPricingFallback.AS_OF_DATE,
                fallback = true,
                effectiveFrom = entry.effectiveFrom,
                effectiveThrough = entry.effectiveThrough,
            )
        }

        val flashFallback = UsageCostEstimator.isFlashModel(model, role)
        return ResolvedModelPrice(
            provider = normProvider,
            model = normModel.ifEmpty { if (flashFallback) "flash_generic" else "heavy_generic" },
            tier = normTier,
            inputPerM = if (flashFallback) 0.75 else 1.50,
            outputPerM = if (flashFallback) 3.75 else 7.50,
            cachedInputPerM = 0.075,
            source = "bundled_role_fallback",
            fetchedAt = BundledPricingFallback.AS_OF_DATE,
            fallback = true,
        )
    }

    private fun matchEntry(
        entries: List<ModelPriceEntry>,
        provider: String,
        model: String,
        tier: String,
        region: String,
        asOf: LocalDate,
    ): ModelPriceEntry? {
        if (model.isEmpty()) return null
        val modelCandidates = buildList {
            add(model)
            listOf("-latest", "-001", "-002", "-preview", "-exp").forEach { suffix ->
                if (model.endsWith(suffix)) add(model.removeSuffix(suffix))
            }
        }.distinct()

        val providerCandidates = buildList {
            add(provider)
            if (provider == "gemini") add("vertex")
            if (provider == "vertex") add("gemini")
        }.distinct()

        for (p in providerCandidates) {
            val byProvider = entries.filter { it.provider == p && it.isEffectiveOn(asOf) }
            if (byProvider.isEmpty()) continue
            for (m in modelCandidates) {
                val exactOrPrefix = byProvider.filter { it.model == m }
                    .ifEmpty { byProvider.filter { m.startsWith(it.model) || it.model.startsWith(m) } }
                if (exactOrPrefix.isEmpty()) continue
                val byTier = exactOrPrefix.filter { it.tier == tier }
                    .ifEmpty { exactOrPrefix.filter { it.tier == "standard" } }
                    .ifEmpty { exactOrPrefix }
                val byRegion = byTier.filter { it.region == region }
                    .ifEmpty { byTier.filter { it.region == "global" } }
                    .ifEmpty { byTier }
                // Prefer entries with an explicit effective window matching asOf over undated defaults.
                return byRegion.maxWithOrNull(
                    compareBy<ModelPriceEntry>(
                        { it.effectiveThrough != null || it.effectiveFrom != null },
                        { it.effectiveFrom ?: "" },
                    ),
                )
            }
        }
        return null
    }
}
