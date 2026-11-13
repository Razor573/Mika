# Installer source and banking apps (HSBC UK)

## The problem

HSBC UK blocks its app from opening when it finds apps on the phone that
were "downloaded from unofficial app stores". It names the flagged apps
on screen. Mika and Mika 2 were flagged for the user on 13 Nov 2026.

This is a real, documented HSBC check, not a Mika bug: in Dec 2025 /
Jan 2026 HSBC UK showed the same warning for Bitwarden installed from
F-Droid, telling the user to uninstall it and reinstall it from Google
Play or the Galaxy Store. The bank queries Android's
`PackageManager.getInstallSourceInfo()` (API 30+, older
`getInstallerPackageName()`) and treats anything whose installer of
record is not a recognised store as unofficial.

Mika is distributed as a GitHub Actions artifact, not from a store, so a
normal sideload records the installer as null, the browser that
downloaded the APK, or the system package installer - none of which is
Google Play - and HSBC flags it.

Uncertainty, stated honestly: HSBC's exact check is closed-source. What
is proven is that it flags apps by install source. Whether it also, or
instead, flags Mika for its accessibility service, overlay, or shell
helper is **not** proven - the screen the user saw names the install
source, and the public Bitwarden cases are install-source cases. If the
steps below do not unblock HSBC, accessibility may be part of its check
too, and no installer change will fix that.

## What Android actually records

`android.content.pm.InstallSourceInfo` has three installer fields:

| Field | For a real Play install | For `adb install -i com.android.vending` |
|---|---|---|
| `installingPackageName` | `com.android.vending` | `com.android.vending` |
| `initiatingPackageName` | `com.android.vending` | `com.android.shell` |
| `packageSource` | 0 (store) | 1 (not a store) |

So `adb install -i` only sets the *installing* package. There is no
`adb`/`pm` flag that sets the initiating package - it is written by the
system from whatever created the install session. A bank that checks
only the installing package will accept it; a bank that checks the
initiating package or `packageSource` will not.

The installer of record is also simply **the last installer** (AOSP
app-ownership docs). An update replaces it with whoever performed that
update. Android 14's update-ownership prompt only applies where the
original installer claimed ownership, which Play does for its core apps,
not generally - so do not rely on it to protect the record.

## Why the app cannot fix this itself

Mika's in-app updater (`ApkUpdater.installIntent`) downloads the APK
and hands it to the system package installer with an `ACTION_VIEW`
intent. The system package installer then becomes the installer of
record. The app cannot instead install itself with Play as the
installer:

- `PackageInstaller.SessionParams.setInstallerPackageName()` only
  allows setting an installer other than the caller's own package to
  apps holding `Manifest.permission.INSTALL_PACKAGES`. That is a
  system-signature permission; a normal app cannot obtain it. Mika
  holds only `REQUEST_INSTALL_PACKAGES`. Calling it with
  `com.android.vending` from inside Mika throws a SecurityException.
- Claiming to be Play when the app was not installed by Play would be
  spoofing the very check banks rely on. This repo does not do that.

So there is **no in-app fix**. What the app does instead, since v45,
is warn: if the installed copy came from Google Play, the update sheet
shows a note that installing the update in the app will change the
installer and banking apps may flag Mika again, and points at the
install script below. The current installer is also already shown in
Diagnostics ("copy diagnostics").

## The fix that works (with limits): install via ADB

Repeat for **every** install and **every** update. Any update via the
in-app updater or a browser download replaces the installer record
again, and HSBC will flag Mika again.

Windows (PowerShell, phone connected, USB debugging on):

```powershell
.\scripts\install-mika2.ps1 -ApkPath .\mika2-release.apk
```

macOS / Linux:

```bash
./scripts/install-mika2.sh path/to/mika2-release.apk
```

The APK is the `mika2-release-apk` artifact from the Build APK workflow
run on GitHub (Razor573/Mika > Actions > Build APK > Artifacts).
Install the release APK, never the debug one: the debug APK is signed
with a different key and will not update over the release install.

The script checks `adb` is present, a device is connected, installs
with `adb install -r -i com.android.vending`, then prints the recorded
`installerPackageName` so you can verify it. Then open HSBC UK and
check.

## The only permanent fix: a Google Play listing

If Mika 2 were installed from Google Play, Play itself would be the
installer - installing and initiating package, packageSource 0 - for
the install and for every update, with nothing to repeat. That is the
only route that fully mimics a Play install, because it *is* one.

What it would take (not done here, no attempt made to publish):

- A Google Play developer account ($25 one-off) in the publisher's name.
- The app submitted for Play review, signed with the same release key
  as the current installs (otherwise existing installs cannot update
  over it - they would need a reinstall).
- Play's accessibility declaration: Mika is an accessibility-service
  app, so Play requires a declaration explaining why the service is
  needed and that it is core to the app. This is the part most likely
  to be challenged in review, since Play restricts accessibility use
  to apps where it is core functionality.
- Ongoing Play policy compliance (privacy policy, data-safety form,
  target SDK, etc.) for every release.

Until then, the ADB script above is the working route, repeated per
update.
