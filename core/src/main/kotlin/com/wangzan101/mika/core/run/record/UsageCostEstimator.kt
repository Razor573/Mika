package com.wangzan101.mika.core.run.record

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneOffset

data class CostEstimate(
    val costUsd: Double,
    val price: ResolvedModelPrice,
)

object UsageCostEstimator {
    private val FLASH_KEYWORDS = listOf("flash", "mini", "haiku", "lite")
    private val HEAVY_KEYWORDS = listOf("hydrogen", "pro", "opus", "sonnet")

    @Volatile
    private var liveCatalog: PricingSnapshot? = null

    fun installCatalog(catalog: PricingSnapshot?) {
        liveCatalog = catalog
    }

    fun currentCatalog(): PricingSnapshot? = liveCatalog

    fun isFlashModel(model: String?, role: String?): Boolean {
        if (!model.isNullOrBlank()) {
            if (FLASH_KEYWORDS.any { model.contains(it, ignoreCase = true) }) return true
            if (HEAVY_KEYWORDS.any { model.contains(it, ignoreCase = true) }) return false
        }
        return role == "light"
    }

    fun resolvePrice(
        model: String?,
        provider: String? = null,
        role: String? = null,
        tier: String = "standard",
        region: String = "global",
        asOf: LocalDate = LocalDate.now(ZoneOffset.UTC),
    ): ResolvedModelPrice = PricingResolver.resolve(
        catalog = liveCatalog,
        provider = provider,
        model = model,
        role = role,
        tier = tier,
        region = region,
        asOf = asOf,
    )

    fun estimate(
        model: String?,
        role: String?,
        inputTokens: Long,
        cachedTokens: Long,
        outputTokens: Long,
        provider: String? = null,
        tier: String = "standard",
        region: String = "global",
        asOf: LocalDate = LocalDate.now(ZoneOffset.UTC),
    ): CostEstimate {
        val safeInput = inputTokens.coerceAtLeast(0L)
        val safeCached = cachedTokens.coerceIn(0L, safeInput)
        val uncached = safeInput - safeCached
        val safeOutput = outputTokens.coerceAtLeast(0L)

        val resolved = resolvePrice(model, provider, role, tier, region, asOf)
        val raw = (uncached.toBigDecimal() * BigDecimal.valueOf(resolved.inputPerM) +
            safeCached.toBigDecimal() * BigDecimal.valueOf(resolved.cachedInputPerM) +
            safeOutput.toBigDecimal() * BigDecimal.valueOf(resolved.outputPerM))
            .movePointLeft(6)
        val usd = raw.setScale(6, RoundingMode.HALF_UP).toDouble()
        return CostEstimate(usd, resolved)
    }

    fun usd(
        model: String?,
        role: String?,
        inputTokens: Long,
        cachedTokens: Long,
        outputTokens: Long,
        provider: String? = null,
        tier: String = "standard",
        region: String = "global",
        asOf: LocalDate = LocalDate.now(ZoneOffset.UTC),
    ): Double = estimate(model, role, inputTokens, cachedTokens, outputTokens, provider, tier, region, asOf).costUsd
}
