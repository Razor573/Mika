package com.wangzan101.mika.settings

import com.wangzan101.mika.core.agent.SpeedFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureFlagsSpeedTest {
    @Test fun freshInstallTurnsOnDefaultsIncludingReplay() {
        val f = FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs()).speedFlags()
        assertTrue(f.inlineScreen && f.batchSteps && f.adaptiveVision && f.replay && f.lightSteps)
        assertFalse(f.tablesAtBirth)
    }

    @Test fun earlierUpgradeOnlyAddsReplayOnce() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        // 上一版升级过、用户又关掉了连续操作:这次只加上回放和大小模型分工,不动别的,不开启 tablesAtBirth
        prefs.edit().putString("speed_flags", "inlineScreen,adaptiveVision").putBoolean("speed_defaults_applied_v2", true).commit()
        val flags = FeatureFlags(prefs)
        assertEquals(SpeedFlags(inlineScreen = true, adaptiveVision = true, replay = true, lightSteps = true, tablesAtBirth = false), flags.speedFlags())
        // 升级之后用户手动关掉回放就保持关闭
        flags.setSpeed(flags.speedFlags().copy(replay = false))
        assertFalse(FeatureFlags(prefs).speedFlags().replay)
    }

    @Test fun migratesV57DevicesBackToTablesAtBirthFalseAndRemovesV5Flag() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        prefs.edit()
            .putString("speed_flags", "inlineScreen,batchSteps,adaptiveVision,replay,lightSteps,tablesAtBirth")
            .putBoolean("speed_defaults_applied_v2", true)
            .putBoolean("speed_defaults_applied_v3", true)
            .putBoolean("speed_defaults_applied_v4", true)
            .putBoolean("speed_defaults_applied_v5", true)
            .commit()
        val flags = FeatureFlags(prefs)
        val migrated = flags.speedFlags()
        assertFalse(migrated.tablesAtBirth)
        assertTrue(migrated.inlineScreen && migrated.batchSteps && migrated.adaptiveVision && migrated.replay && migrated.lightSteps)
        assertFalse(prefs.contains("speed_defaults_applied_v5"))
        // Reloading stays false and does not re-add the v5 key
        assertFalse(FeatureFlags(prefs).speedFlags().tablesAtBirth)
        assertFalse(prefs.contains("speed_defaults_applied_v5"))
    }
}
