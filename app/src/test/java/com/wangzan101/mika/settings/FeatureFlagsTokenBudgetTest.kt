package com.wangzan101.mika.settings

import com.wangzan101.mika.core.usage.TokenBudgetLimits
import com.wangzan101.mika.core.usage.UsdBudgetLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeatureFlagsTokenBudgetTest {
    private fun flags() = FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs())

    @Test fun defaultsToUnlimitedAndCanBeCleared() {
        val f = flags()
        assertNull(f.taskTokenBudget())
        f.setTaskTokenBudget(500_000)
        assertEquals(500_000L, f.taskTokenBudget())
        f.setTaskTokenBudget(null)
        assertNull(f.taskTokenBudget())
    }

    @Test fun acceptsBothBoundsAndRejectsValuesOutsideThem() {
        val f = flags()
        f.setTaskTokenBudget(TokenBudgetLimits.MIN)
        assertEquals(TokenBudgetLimits.MIN, f.taskTokenBudget())
        f.setTaskTokenBudget(TokenBudgetLimits.MAX)
        assertEquals(TokenBudgetLimits.MAX, f.taskTokenBudget())
        for (invalid in listOf(-1L, 0L, TokenBudgetLimits.MIN - 1, TokenBudgetLimits.MAX + 1)) {
            f.setTaskTokenBudget(invalid)
            assertNull("非法值按不限制处理: $invalid", f.taskTokenBudget())
        }
    }

    @Test fun proactiveDailyBudgetDefaultsTo200kAndNeverBecomesUnlimited() {
        val f = flags()
        assertEquals(200_000L, f.proactiveDailyTokenBudget())
        f.setProactiveDailyTokenBudget(50_000)
        assertEquals(50_000L, f.proactiveDailyTokenBudget())
        f.setProactiveDailyTokenBudget(null)
        assertEquals(200_000L, f.proactiveDailyTokenBudget())
        f.setProactiveDailyTokenBudget(TokenBudgetLimits.MIN)
        assertEquals(TokenBudgetLimits.MIN, f.proactiveDailyTokenBudget())
        for (invalid in listOf(-1L, 0L, TokenBudgetLimits.MIN - 1, TokenBudgetLimits.MAX + 1)) {
            f.setProactiveDailyTokenBudget(invalid)
            assertEquals("非法值按默认值处理: $invalid", 200_000L, f.proactiveDailyTokenBudget())
        }
    }

    @Test fun usdBudgetDefaultsToUnlimitedAndValidatesBounds() {
        val f = flags()
        assertNull(f.taskUsdBudget())
        f.setTaskUsdBudget(0.15)
        assertEquals(0.15, f.taskUsdBudget()!!, 1e-9)
        f.setTaskUsdBudget(UsdBudgetLimits.MIN)
        assertEquals(UsdBudgetLimits.MIN, f.taskUsdBudget()!!, 1e-9)
        f.setTaskUsdBudget(UsdBudgetLimits.MAX)
        assertEquals(UsdBudgetLimits.MAX, f.taskUsdBudget()!!, 1e-9)
        for (invalid in listOf(0.0, -1.0, UsdBudgetLimits.MIN - 0.001, UsdBudgetLimits.MAX + 1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            f.setTaskUsdBudget(invalid)
            assertNull("Invalid USD budget clears to unlimited: $invalid", f.taskUsdBudget())
        }
    }
}
