package com.wangzan101.mika.settings

import com.wangzan101.mika.core.shell.BuiltinShellStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 银行模式恢复步骤的纯函数测试:按恢复后的真实状态,算出还有哪些步骤要用户亲手做。
 * 暂停/恢复本身要读系统(无障碍列表、无线调试开关、助手状态),不在 JVM 单测里跑,
 * 由 [com.wangzan101.mika.core.shell.BuiltinShellEngineTest] 在 core 单测里覆盖状态机。
 */
class BankingModeTest {
    private fun state(
        accessibility: AccessibilityState = AccessibilityState.On,
        wirelessDebugging: Boolean = true,
        helper: BuiltinShellStatus = BuiltinShellStatus.Running,
        helperPaired: Boolean = true,
    ) = BankingState(
        paused = false,
        accessibility = accessibility,
        accessibilityRunning = accessibility == AccessibilityState.On,
        wirelessDebugging = wirelessDebugging,
        helper = helper,
        helperPaired = helperPaired,
        taskRunning = false,
    )

    @Test
    fun everythingBackOnNeedsNoManualStep() {
        assertTrue(planResumeSteps(state()).isEmpty())
    }

    @Test
    fun accessibilityOffNeedsTheSettingsStep() {
        assertEquals(
            listOf(ResumeStep.OpenAccessibilitySettings),
            planResumeSteps(state(accessibility = AccessibilityState.Off)),
        )
        // 服务在列表里却没在跑也一样:用户得去系统设置里亲手打开。
        assertEquals(
            listOf(ResumeStep.OpenAccessibilitySettings),
            planResumeSteps(state(accessibility = AccessibilityState.EnabledNotRunning)),
        )
    }

    @Test
    fun wirelessOffNeedsTheDeveloperOptionsStep() {
        assertEquals(
            listOf(ResumeStep.OpenWirelessDebuggingPage),
            planResumeSteps(state(wirelessDebugging = false)),
        )
    }

    @Test
    fun lostPairingNeedsRePairing() {
        assertEquals(
            listOf(ResumeStep.RePairHelper),
            planResumeSteps(state(helper = BuiltinShellStatus.NotPaired, helperPaired = false)),
        )
    }

    @Test
    fun pausedHelperWithSavedPairingNeedsNoRePairing() {
        // 银行模式暂停后恢复:助手是 Paused、配对还在,不用重新配对。
        assertTrue(planResumeSteps(state(helper = BuiltinShellStatus.Paused)).isEmpty())
    }

    @Test
    fun allThreeGapsAreListedInOrder() {
        assertEquals(
            listOf(
                ResumeStep.OpenAccessibilitySettings,
                ResumeStep.OpenWirelessDebuggingPage,
                ResumeStep.RePairHelper,
            ),
            planResumeSteps(
                state(
                    accessibility = AccessibilityState.Off,
                    wirelessDebugging = false,
                    helper = BuiltinShellStatus.NotPaired,
                    helperPaired = false,
                ),
            ),
        )
    }
}
