package com.wangzan101.mika.about

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallSourceTest {
    @Test fun playStoreIsRecognised() {
        assertTrue(InstallSource.isPlayInstaller("com.android.vending"))
        assertTrue(InstallSource.isPlayInstaller(InstallSource.PLAY_STORE))
    }

    @Test fun otherInstallersAreNotPlay() {
        assertFalse(InstallSource.isPlayInstaller(null))
        assertFalse(InstallSource.isPlayInstaller("com.google.android.packageinstaller"))
        assertFalse(InstallSource.isPlayInstaller("com.android.packageinstaller"))
        assertFalse(InstallSource.isPlayInstaller("com.wangzan101.mika2"))
        assertFalse(InstallSource.isPlayInstaller("org.mozilla.firefox"))
    }
}
