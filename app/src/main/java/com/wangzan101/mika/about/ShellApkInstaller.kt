package com.wangzan101.mika.about

import com.wangzan101.mika.core.shell.PermissionCommands

/**
 * Install Mika updates, or repair its installer record, through the phone
 * helper - the built-in shell helper that runs as shell (uid 2000), the
 * same identity `adb shell` runs as.
 *
 * Why this exists: the app itself cannot install with Google Play as the
 * installer. [android.content.pm.PackageInstaller.SessionParams.setInstallerPackageName]
 * only lets an app set an installer other than its own package if it holds
 * `INSTALL_PACKAGES`, a system-signature permission Mika cannot obtain, and
 * calling it with `com.android.vending` from inside Mika throws a
 * `SecurityException`. The shell helper has no such problem: `pm install`
 * is the command `adb install` runs under the hood, and `pm install -i`
 * sets the installing package exactly as `adb install -i` does. The
 * resulting record is therefore identical to the ADB script's
 * (`tools/install-mika2.ps1`): installing package `com.android.vending`,
 * initiating package `com.android.shell`, packageSource 1 - which is what
 * banking apps such as HSBC UK check. See
 * `docs/installer-source-and-banking-apps.md` for the limits.
 *
 * Two operations, both explicit and user-initiated from the update sheet:
 * - Install an update: `pm install -r -i com.android.vending <apk>`.
 *   It runs detached (`&`, output to a log file) because the helper's
 *   `runCommand` times a command out after 10 seconds and a 180 MB APK
 *   can take longer to install; the app is killed by its own update
 *   anyway, so there is no result to wait for in-process. Success is
 *   read from the log file afterwards ([installSucceeded]).
 * - Repair the installer record after a normal in-app update:
 *   `pm set-installer <package> com.android.vending`, run synchronously
 *   through [PermissionCommands.checked] so the exit code is visible.
 *
 * Nothing here claims Mika was installed by Google Play when it was not
 * installed by the helper with `-i` either - the record it writes is the
 * record of the installer that actually performed the install, the shell
 * helper, with Play named as the installing package, exactly as the ADB
 * script does. No spoofing inside the app's own PackageInstaller session.
 */
object ShellApkInstaller {
    /** Name of the log file the detached `pm install` writes into. */
    const val INSTALL_LOG_NAME = "shell-install.log"

    /** Marker the detached install prints once the background job started. */
    const val STARTED_MARKER = "install-started"

    /**
     * `pm install` for [apkPath], detached so the helper's 10-second
     * command timeout cannot kill it mid-install. Output goes to
     * [logPath]; the shell prints [STARTED_MARKER] once the job is away.
     */
    fun installCommand(apkPath: String, logPath: String): String =
        "pm install -r -i ${InstallSource.PLAY_STORE} ${PermissionCommands.quote(apkPath)}" +
            " > ${PermissionCommands.quote(logPath)} 2>&1 & echo $STARTED_MARKER"

    /** `pm set-installer` for [packageName]: instant, run synchronously. */
    fun setInstallerCommand(packageName: String): String =
        "pm set-installer $packageName ${InstallSource.PLAY_STORE}"

    /** True when the detached install was launched (not when it finished). */
    fun installStarted(output: String): Boolean = STARTED_MARKER in output

    /**
     * True when the detached `pm install` log says it succeeded. `pm`
     * prints `Success` on its own line on success and
     * `Failure [INSTALL_FAILED_...]` on failure.
     */
    fun installSucceeded(logText: String?): Boolean {
        if (logText == null) return false
        val lines = logText.lineSequence().map { it.trim() }.toList()
        return "Success" in lines && lines.none { it.startsWith("Failure") }
    }

    /** True when a [PermissionCommands.checked] `pm set-installer` exited 0. */
    fun setInstallerSucceeded(output: String): Boolean =
        PermissionCommands.exitCode(output) == 0
}
