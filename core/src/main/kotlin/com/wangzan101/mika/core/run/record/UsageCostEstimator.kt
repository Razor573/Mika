package com.wangzan101.mika.core.run.record

import java.math.BigDecimal
import java.math.RoundingMode

object UsageCostEstimator {
    private val FLASH_UNCACHED_PER_M = BigDecimal("0.15")
    private val FLASH_CACHED_PER_M = BigDecimal("0.015")
    private val FLASH_OUTPUT_PER_M = BigDecimal("0.60")

    private val HYDROGEN_UNCACHED_PER_M = BigDecimal("2.00")
    private val HYDROGEN_CACHED_PER_M = BigDecimal("0.20")
    private val HYDROGEN_OUTPUT_PER_M = BigDecimal("12.00")

    private val FLASH_KEYWORDS = listOf("flash", "mini", "haiku", "lite")
    private val HEAVY_KEYWORDS = listOf("hydrogen", "pro", "opus", "sonnet")

    fun isFlashModel(model: String?, role: String?): Boolean {
        if (!model.isNullOrBlank()) {
            if (FLASH_KEYWORDS.any { model.contains(it, ignoreCase = true) }) return true
            if (HEAVY_KEYWORDS.any { model.contains(it, ignoreCase = true) }) return false
        }
        return role == "light"
    }

    fun usd(
        model: String?,
        role: String?,
        inputTokens: Long,
        cachedTokens: Long,
        outputTokens: Long,
    ): Double {
        val safeInput = inputTokens.coerceAtLeast(0L)
        val safeCached = cachedTokens.coerceIn(0L, safeInput)
        val uncached = safeInput - safeCached
        val safeOutput = outputTokens.coerceAtLeast(0L)

        val flash = isFlashModel(model, role)
        val uncachedRate = if (flash) FLASH_UNCACHED_PER_M else HYDROGEN_UNCACHED_PER_M
        val cachedRate = if (flash) FLASH_CACHED_PER_M else HYDROGEN_CACHED_PER_M
        val outputRate = if (flash) FLASH_OUTPUT_PER_M else HYDROGEN_OUTPUT_PER_M

        val raw = (uncached.toBigDecimal() * uncachedRate +
            safeCached.toBigDecimal() * cachedRate +
            safeOutput.toBigDecimal() * outputRate)
            .movePointLeft(6)
        return raw.setScale(6, RoundingMode.HALF_UP).toDouble()
    }
}
