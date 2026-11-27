package com.wangzan101.mika.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.wangzan101.mika.core.device.shell.BuiltinShellStatus
import com.wangzan101.mika.task.PauseState
import com.wangzan101.mika.task.ResumeStep
import com.wangzan101.mika.task.planResumeSteps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「暂停 Mika」:持久标记是唯一的真相来源,写盘成功才算数;恢复步骤按真实状态算。
 * 暂停/恢复本身要读系统(无障碍列表、无线调试开关),不在 JVM 单测里跑;助手的投影由 core 的
 * BuiltinShellEnginePauseTest 覆盖。
 */
class MikaPauseTest {
    @Test
    fun setWritesToDiskBeforeTheValueChanges() {
        val disk = mutableListOf<Boolean>()
        val pause = MikaPause(false) { disk += it; true }
        assertTrue(pause.set(true))
        assertTrue(pause.paused())
        assertTrue(pause.state.value)
        assertEquals(listOf(true), disk)
        // 同一个值不再写盘。
        assertTrue(pause.set(true))
        assertEquals(listOf(true), disk)
    }

    @Test
    fun failedWriteLeavesTheFlagUnchanged() {
        val pause = MikaPause(false) { false }
        assertFalse(pause.set(true))
        assertFalse(pause.paused())
        val paused = MikaPause(true) { false }
        assertFalse(paused.set(false))
        assertTrue(paused.paused())
    }

    @Test
    fun restartReadsThePersistedFlag() {
        var disk = false
        MikaPause(disk) { disk = it; true }.set(true)
        assertTrue(MikaPause(disk) { disk = it; true }.paused())
    }

    private fun state(
        accessibility: AccessibilityState = AccessibilityState.On,
        wirelessDebugging: Boolean = true,
        helper: BuiltinShellStatus = BuiltinShellStatus.Running,
        helperPaired: Boolean = true,
    ) = PauseState(
        paused = false,
        accessibility = accessibility,
        wirelessDebugging = wirelessDebugging,
        helper = helper,
        helperPaired = helperPaired,
    )

    @Test
    fun everythingBackOnNeedsNoManualStep() {
        assertTrue(planResumeSteps(state()).isEmpty())
    }

    @Test
    fun accessibilityOffNeedsTheSettingsStep() {
        assertEquals(listOf(ResumeStep.OpenAccessibilitySettings), planResumeSteps(state(accessibility = AccessibilityState.Off)))
        // 服务在列表里却没在跑也一样:用户得去系统设置里亲手打开。
        assertEquals(
            listOf(ResumeStep.OpenAccessibilitySettings),
            planResumeSteps(state(accessibility = AccessibilityState.EnabledNotRunning)),
        )
    }

    @Test
    fun wirelessOffNeedsTheDeveloperOptionsStepOnlyForAPairedHelper() {
        assertEquals(listOf(ResumeStep.OpenWirelessDebuggingPage), planResumeSteps(state(wirelessDebugging = false)))
        // 经 Shizuku 或 root 起助手、从没配对过无线调试:不要求打开它。
        assertTrue(planResumeSteps(state(wirelessDebugging = false, helperPaired = false)).isEmpty())
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
        // 暂停后恢复:助手还是 Paused、配对还在,不用重新配对。
        assertTrue(planResumeSteps(state(helper = BuiltinShellStatus.Paused)).isEmpty())
    }

    @Test
    fun legacyPausePrefsMigratesToMikaPauseAndClearsOldPrefs() {
        val prefsMap = mutableMapOf<String, InMemoryPrefs>()
        val legacy = InMemoryPrefs().apply { data["paused"] = true }
        val current = InMemoryPrefs()
        prefsMap["banking_mode"] = legacy
        prefsMap["mika_pause"] = current

        val ctx = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                prefsMap.getOrPut(name) { InMemoryPrefs() }

            override fun deleteSharedPreferences(name: String): Boolean = prefsMap.remove(name) != null
        }

        val pause = MikaPause.load(ctx)
        assertTrue(pause.paused())
        assertEquals(true, current.data["paused"])
        assertFalse(legacy.contains("paused"))
        assertFalse(legacy.getBoolean("paused", false))
    }

    private class InMemoryPrefs : SharedPreferences {
        val data = mutableMapOf<String, Any?>()
        override fun getAll(): MutableMap<String, *> = data.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = data[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = data[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = data[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private var cleared = false
            private val pending = mutableMapOf<String, Any?>()
            override fun putString(key: String?, value: String?) = apply { if (key != null) pending[key] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { if (key != null) pending[key] = values }
            override fun putInt(key: String?, value: Int) = apply { if (key != null) pending[key] = value }
            override fun putLong(key: String?, value: Long) = apply { if (key != null) pending[key] = value }
            override fun putFloat(key: String?, value: Float) = apply { if (key != null) pending[key] = value }
            override fun putBoolean(key: String?, value: Boolean) = apply { if (key != null) pending[key] = value }
            override fun remove(key: String?) = apply { if (key != null) pending[key] = null }
            override fun clear() = apply { cleared = true }
            override fun commit(): Boolean {
                if (cleared) data.clear()
                pending.forEach { (k, v) -> if (v == null) data.remove(k) else data[k] = v }
                return true
            }
            override fun apply() { commit() }
        }
    }
}
