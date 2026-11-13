package com.wangzan101.mika.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellApkInstallerTest {
    @Test
    fun installCommandUsesPmInstallWithPlayInstaller() {
        val command = ShellApkInstaller.installCommand(
            "/sdcard/Android/data/com.wangzan101.mika2/files/Download/updates/update-1.apk",
            "/sdcard/Android/data/com.wangzan101.mika2/files/Download/updates/shell-install.log",
        )
        assertTrue(command.startsWith("pm install -r -i com.android.vending "))
        assertTrue(command.contains("update-1.apk"))
        assertTrue(command.contains("shell-install.log"))
        // Detached: the helper's 10-second command timeout must not kill it.
        assertTrue(command.endsWith("& echo install-started"))
    }

    @Test
    fun installCommandQuotesPathsWithSpaces() {
        val command = ShellApkInstaller.installCommand("/sdcard/my dir/update 1.apk", "/sdcard/my dir/log.txt")
        assertTrue(command.contains("'/sdcard/my dir/update 1.apk'"))
        assertTrue(command.contains("'/sdcard/my dir/log.txt'"))
    }

    @Test
    fun setInstallerCommandNamesPlay() {
        assertEquals(
            "pm set-installer com.wangzan101.mika2 com.android.vending",
            ShellApkInstaller.setInstallerCommand("com.wangzan101.mika2"),
        )
    }

    @Test
    fun installStartedNeedsMarker() {
        assertTrue(ShellApkInstaller.installStarted("install-started\n"))
        assertFalse(ShellApkInstaller.installStarted(""))
        assertFalse(ShellApkInstaller.installStarted("Success\n"))
    }

    @Test
    fun installSucceededNeedsSuccessLineWithoutFailure() {
        assertTrue(ShellApkInstaller.installSucceeded("Success\n"))
        assertFalse(ShellApkInstaller.installSucceeded("Failure [INSTALL_FAILED_VERSION_DOWNGRADE]\n"))
        assertFalse(ShellApkInstaller.installSucceeded("Success\nFailure [INSTALL_FAILED_INVALID_APK]\n"))
        assertFalse(ShellApkInstaller.installSucceeded(null))
        assertFalse(ShellApkInstaller.installSucceeded(""))
    }

    @Test
    fun setInstallerSucceededReadsExitCode() {
        assertTrue(ShellApkInstaller.setInstallerSucceeded("some output\n__mika_exit=0\n"))
        assertFalse(ShellApkInstaller.setInstallerSucceeded("Error: something\n__mika_exit=1\n"))
        assertFalse(ShellApkInstaller.setInstallerSucceeded(""))
    }
}
