# Add official Shizuku support as an alternative shell transport

## Summary

Adds official Shizuku (`dev.rikka.shizuku:api` and `dev.rikka.shizuku:provider` `13.1.5`) as a second shell transport alongside Wireless debugging for Android 12+ devices, so Mika can control the phone on mobile data even when Wi-Fi and Wireless debugging are off.

- **Three universal transport options** under Settings -> Phone control (**Auto**, **Wireless debugging**, **Shizuku**) with no device-specific hacks or model names.
- **Auto mode** prefers Shizuku when installed, running, and permission-granted, and falls back to root / Wireless debugging otherwise (canceling any pending Shizuku bind before fallback).
- **Clear Shizuku status & actions:** Live status row (`Installed`, `Running`, `Permission`), one-tap permission request / open Shizuku row, and Shizuku start instructions explaining why Mika never auto-starts Shizuku itself.
- **Full IMikaShell parity:** All existing helper calls (shell commands, APK install, screen control, devtools sockets, device files, call audio) route transparently through `HelperHub` using `ShizukuShellUserService`.

## Why upstream users need it

Upstream Mika's Wireless debugging helper requires active Wi-Fi and Wireless debugging pairing/IP/port resolution. Whenever a user leaves Wi-Fi on mobile data, Wireless debugging cannot connect. Once Shizuku is running, Mika binds via Shizuku's official binder service and retains UID 2000 shell control anywhere without Wi-Fi.

## Key files

- `core/src/main/kotlin/com/wangzan101/mika/core/device/shell/ShizukuTransport.kt`
- `core/src/main/kotlin/com/wangzan101/mika/core/device/shell/BuiltinShell.kt`
- `core/src/main/kotlin/com/wangzan101/mika/core/device/shell/BuiltinShellEngine.kt`
- `app/src/main/java/com/wangzan101/mika/cap/phone/shell/ShizukuShellUserService.kt`
- `app/src/main/java/com/wangzan101/mika/cap/phone/shell/HelperHub.kt`
- `app/src/main/java/com/wangzan101/mika/cap/phone/setup/ShizukuController.kt`
- `app/src/main/java/com/wangzan101/mika/cap/phone/setup/BuiltinShellManager.kt`
- `app/src/main/java/com/wangzan101/mika/settings/ui/SettingsSubPages.kt` (PhoneOpsScreen composable)
- `app/src/main/java/com/wangzan101/mika/settings/ui/PhoneSetupCopy.kt`
- `app/src/main/AndroidManifest.xml`
- `gradle/libs.versions.toml`, `app/build.gradle.kts`, `licenses/THIRD_PARTY_NOTICES.md`
- `core/src/test/kotlin/com/wangzan101/mika/core/device/shell/BuiltinShellEngineTest.kt`
- `app/src/test/java/com/wangzan101/mika/cap/phone/setup/ShizukuTransportTest.kt`

## How to test on a phone

1. Install and start **Shizuku** on an Android 12+ device.
2. Turn **Wi-Fi OFF** and leave **mobile data ON**.
3. Open Settings -> Phone control, choose **Auto** (or **Shizuku**), tap **Grant Shizuku permission**, and approve Mika in Shizuku's prompt.
4. Confirm the **Shizuku** status shows `Running · Permission granted` and **Phone helper** connects as `Connected via Shizuku; works on Wi-Fi or mobile data`.
5. Run a screen-control task on mobile data.
6. Force-stop Shizuku in the Shizuku app and verify Mika surfaces `Shizuku is not running` with retry guidance (or falls back to Wireless debugging in **Auto** mode when Wi-Fi is back on).

## What it does NOT do

- Does not auto-start Shizuku itself or bypass Shizuku's permission model or Android security boundaries (after a phone reboot, the user must start Shizuku again using USB ADB, root, or Wireless debugging).
- Does not grant root privileges or relax banking/protected-window flags; `ShizukuShellUserService` runs as standard ADB shell UID (`2000`) via official `Shizuku` APIs only.

## Deliberately left out

- Fork-only Banking mode / pause-resume integrations (banking mode and its settings screen were removed cleanly from this branch).
- Fork application ID / branding (`applicationId` remains `com.wangzan101.mika`).
