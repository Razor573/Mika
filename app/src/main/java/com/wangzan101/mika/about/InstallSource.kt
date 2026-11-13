package com.wangzan101.mika.about

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Which app installed this copy of Mika, as recorded by Android.
 *
 * Some banking apps (HSBC UK is the reported one) query
 * [PackageManager.getInstallSourceInfo] and refuse to open when the app was
 * installed from an "unofficial app store", i.e. when the installer of
 * record is not Google Play (`com.android.vending`) or the Galaxy Store.
 * Mika is distributed as a GitHub Actions artifact, so a normal sideload
 * records the installer as null / the browser / the system package
 * installer, and those banks flag it.
 *
 * What this file does NOT do, on purpose: it never claims the installer is
 * Google Play when it is not. Android's
 * [android.content.pm.PackageInstaller.SessionParams.setInstallerPackageName]
 * only lets an app set an installer other than its own package name if it
 * holds `Manifest.permission.INSTALL_PACKAGES`, a system-signature
 * permission a normal app cannot obtain; Mika holds only
 * `REQUEST_INSTALL_PACKAGES`. Calling it with `com.android.vending` from
 * inside Mika would throw a `SecurityException`. The installer of record is
 * also simply "the last installer" (AOSP app-ownership docs), so even a
 * correct Play record is replaced by whichever installer performs the next
 * update - an in-app update hands the APK to the system package installer
 * via `ACTION_VIEW` ([ApkUpdater.installIntent]), which then becomes the
 * installer of record.
 *
 * The legitimate routes are:
 * - `adb install -r -i com.android.vending <apk>` (see
 * `tools/install-mika2.ps1` / `tools/install-mika2.sh`) sets the
 * installing package to Play. It only sets the *installing* package: the
 * *initiating* package stays `com.android.shell` and `packageSource`
 * stays 1 (a real Play install has initiating `com.android.vending` and
 * packageSource 0), so it helps only banks that check the installing
 * package. It must also be repeated for every update - any update via the
 * in-app updater or a browser download replaces the record again.
 * - Since v46, the in-app updater itself can do the same thing when the
 * phone helper is paired: [ShellApkInstaller] runs `pm install -r -i
 * com.android.vending` as the shell helper (uid 2000, the same identity
 * `adb shell` runs as), so the record it writes is identical to the ADB
 * script's. It can also repair the record afterwards with
 * `pm set-installer` without reinstalling. The app still cannot do this
 * through its own PackageInstaller session - only the shell helper can.
 * - A Google Play listing is the only permanent fix: then Play itself is
 * the installer, including for updates. See
 * `docs/installer-source-and-banking-apps.md`.
 */
object InstallSource {
    /** Google Play Store. */
    const val PLAY_STORE = "com.android.vending"

    /** The package Android records as having installed this app, or null if unknown. */
    fun installingPackageName(context: Context): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getInstallerPackageName(context.packageName)
        }
    }.getOrNull()

    /** True when this copy was installed from Google Play. */
    fun isPlayInstalled(context: Context): Boolean = installingPackageName(context) == PLAY_STORE

    /** Pure check, kept separate so it can be unit-tested without a Context. */
    fun isPlayInstaller(installerPackageName: String?): Boolean = installerPackageName == PLAY_STORE
}
