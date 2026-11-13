# Install (or update) Mika 2 so Android records Google Play as the installer.
#
# Why: some banking apps (HSBC UK is the reported one) query
# PackageManager.getInstallSourceInfo() and block access when an app was
# installed from an "unofficial app store". Mika is distributed as a GitHub
# Actions artifact, so a normal sideload records the installer as null, the
# browser, or the system package installer, and HSBC flags it.
#
# `adb install -i com.android.vending` sets the *installing* package to
# Google Play. Honest limits, do not expect more from it than this:
#  - It only sets the installing package. The *initiating* package stays
#    `com.android.shell` and packageSource stays 1 (a real Play install has
#    initiating `com.android.vending` and packageSource 0). It helps only
#    banks that check the installing package name.
#  - It is NOT permanent on its own. The installer of record is simply the
#    last installer, so any later update via Mika's in-app updater or a
#    browser download replaces it again. Repeat this script for EVERY
#    update. Mika warns about this in the update sheet when it was
#    installed from Play.
#  - The only permanent fix is a Google Play listing (see
#    docs/installer-source-and-banking-apps.md). The app itself cannot set
#    this: PackageInstaller.SessionParams.setInstallerPackageName() only
#    allows a foreign installer to apps holding INSTALL_PACKAGES, a system
#    signature permission Mika does not and cannot hold.
#
# Usage (Windows PowerShell, phone connected over USB or wireless ADB,
# USB debugging on):
#   .\tools\install-mika2.ps1 -ApkPath .\mika2-release.apk
# The APK is the `mika2-release-apk` artifact from the Build APK workflow
# run on GitHub (Razor573/Mika > Actions > Build APK > Artifacts). Install
# the release APK, never the debug one: debug is signed with a different
# key and will not update over the release install.
param(
    [Parameter(Mandatory = $true)]
    [string]$ApkPath,
    [string]$PackageName = "com.wangzan101.mika2",
    [string]$Installer = "com.android.vending"
)

$ErrorActionPreference = "Stop"

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
    Write-Error "adb was not found in PATH. Install Android platform-tools and add it to PATH, then try again."
}
if (-not (Test-Path $ApkPath)) {
    Write-Error "APK not found: $ApkPath"
}

$devices = adb devices
$ready = $devices | Select-String -Pattern "`tdevice$"
if (-not $ready) {
    Write-Error "No authorised ADB device found. Connect the phone, allow USB debugging on it, then run `adb devices` and check it shows 'device'."
}

Write-Host "Installing $ApkPath as $PackageName with installer $Installer ..."
adb install -r -i $Installer $ApkPath

Write-Host ""
Write-Host "Verifying installer record:"
adb shell dumpsys package $PackageName | Select-String -Pattern "installerPackageName|initiatingPackageName|packageSource"
Write-Host ""
Write-Host "Expected: installerPackageName=$Installer. initiatingPackageName will still be com.android.shell - that is normal for an ADB install and cannot be changed from here."
Write-Host "Now open HSBC UK and check it no longer flags Mika 2. If it still does, it is checking more than the installing package, and only a Play Store listing will fix it."
