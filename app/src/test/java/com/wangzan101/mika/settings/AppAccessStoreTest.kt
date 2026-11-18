package com.wangzan101.mika.settings

import com.wangzan101.mika.core.access.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppAccessStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun file() = java.io.File(temp.root, "access.json")

    @Test fun newAppsAndClassificationNeverChangePermission() {
        val store = AppAccessStore(file())
        assertNull(store.current().denial("new.bank"))
        store.classify(mapOf("new.bank" to AppClassification(AppCategory.Finance)))
        assertNull(store.current().denial("new.bank"))
        store.setBlocked("new.bank", true)
        store.classify(mapOf("new.bank" to AppClassification(AppCategory.Other)))
        assertEquals("app_blocked", store.current().denial("new.bank")?.code)
        assertEquals(store.current().blocked, AppAccessStore(file()).current().blocked)
    }

    @Test fun sharingDoesNotUnblockAndReblockingClearsGrant() {
        val store = AppAccessStore(file())
        store.setBlocked("app", true)
        store.setShareAllowed("app", true)
        assertTrue(store.current().canShare("app"))
        assertNotNull(store.current().denial("app"))
        assertTrue(AppAccessStore(file()).current().canShare("app"))
        store.setShareAllowed("app", false)
        assertFalse(store.current().canShare("app"))
        store.setBlocked("app", false)
        assertNull(store.current().denial("app"))
        store.setBlocked("app", true)
        assertFalse(store.current().canShare("app"))
    }

    @Test fun manualClassificationSurvivesAutomaticUpdates() {
        val store = AppAccessStore(file())
        store.classify(mapOf("app" to AppClassification(AppCategory.Game, manual = true)))
        store.classify(mapOf("app" to AppClassification(AppCategory.Finance)))
        assertEquals(AppCategory.Game, store.current().classifications["app"]?.category)
    }

    @Test fun presetsDoNotApplyToFuturePackages() {
        val store = AppAccessStore(file())
        store.blockPackages(setOf("old.bank"))
        assertNull(store.current().denial("new.bank"))
        store.allowAll()
        assertTrue(store.current().blocked.isEmpty())
    }

    @Test fun corruptFileDoesNotAllowEveryApp() {
        file().writeText("{bad")
        val store = AppAccessStore(file())
        assertEquals("app_access_unavailable", store.current().denial("app")?.code)
        store.reset()
        assertNull(store.current().denial("app"))
    }

    @Test fun livePolicyReferenceSeesChangesAndUnknownTargetIsSeparate() {
        val store = AppAccessStore(file())
        val policy: AppAccessPolicy = store
        assertNull(policy.current().denial("app"))
        store.setBlocked("app", true)
        assertEquals("app_blocked", policy.current().denial("app")?.code)
        assertEquals("app_access_unverified", policy.current().denial(null)?.code)
        assertEquals(setOf("app"), policy.current().blocked)
    }

    @Test fun hardLockedPreSeededAndSurvivesAllowAllAndSetBlockedFalse() {
        val f = file()
        val store = AppAccessStore(f)
        assertEquals(DEFAULT_HARD_LOCKED, store.current().hardLocked)
        val authPkg = "com.google.android.apps.authenticator2"
        assertEquals("app_blocked", store.current().denial(authPkg)?.code)

        store.setBlocked("com.example.other", true)
        store.allowAll()
        assertTrue(store.current().blocked.isEmpty())
        assertEquals(DEFAULT_HARD_LOCKED, store.current().hardLocked)
        assertEquals("app_blocked", store.current().denial(authPkg)?.code)

        store.setBlocked(authPkg, false)
        assertTrue(authPkg in store.current().hardLocked)
        assertEquals("app_blocked", store.current().denial(authPkg)?.code)
    }

    @Test fun setHardLockedOptOutPersistsAcrossReloadAndResetRestoresDefaults() {
        val f = file()
        val store = AppAccessStore(f)
        val authPkg = "com.google.android.apps.authenticator2"
        store.setHardLocked(authPkg, false)
        assertFalse(authPkg in store.current().hardLocked)
        assertNull(store.current().denial(authPkg))

        val reloaded = AppAccessStore(f)
        assertFalse(authPkg in reloaded.current().hardLocked)
        assertNull(reloaded.current().denial(authPkg))

        reloaded.setHardLocked("com.custom.bank", true)
        assertTrue("com.custom.bank" in reloaded.current().hardLocked)
        assertEquals("app_blocked", reloaded.current().denial("com.custom.bank")?.code)

        reloaded.reset()
        assertEquals(DEFAULT_HARD_LOCKED, reloaded.current().hardLocked)
        assertEquals("app_blocked", reloaded.current().denial(authPkg)?.code)
    }

    @Test fun legacyFileWithoutHardLockedKeySeedsDefaults() {
        val f = file()
        f.writeText("""{"version":1,"blocked":["com.old.bank"],"shareAllowed":[],"classifications":{}}""")
        val store = AppAccessStore(f)
        assertEquals(setOf("com.old.bank"), store.current().blocked)
        assertEquals(DEFAULT_HARD_LOCKED, store.current().hardLocked)
    }
}
