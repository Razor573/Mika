# Banking mode (v49, 0.1.44)

Pause Mika so a bank app opens, then resume it. No uninstall, no Play
listing, no re-signing, no data loss.

## What the user verified on his phone (14 Nov 2026)

HSBC UK opens only when BOTH of these are off:

- the accessibility service (MikaAccessibilityService)
- wireless debugging (the shell helper's transport)

Either one on, HSBC blocks Mika 2 as a sideloaded app. Banking mode turns
both off and stops the helper. Resume turns them back on.

## Where the switch lives

- **Settings > Banking mode** — the full screen, and a row on the Settings
  home page (right under "Phone operations") showing Paused / Active.
- **Home screen banner** — while paused, the status banner says
  "Mika paused — Accessibility off, helper disconnected. HSBC should now
  open." Tapping it opens Settings.
- The Settings status list still shows "Accessibility off" as a separate
  problem while paused; that entry opens Accessibility settings, which is
  where the user goes to resume anyway.

## What Pause does

1. Remembers `paused = true` (SharedPreferences `banking_mode`, survives
   restarts). A restart re-applies the pause before the recovery watchers
   start, so Mika does not come back on by itself.
2. Stops any running task first (and says so).
3. Stops the phone helper: `BuiltinShellControl.pause()` sets
   `BuiltinShellStatus.Paused`. The watchdog does not restart it, the
   accessibility auto-repair does not re-enable the service while paused
   (`AccessibilityRepair` refuses when the helper is Paused), and no
   pairing-code notification is shown.
4. Turns the accessibility service off with `AccessibilityService.disableSelf()`
   (Android 13+). On older Android, or if the service is listed but not
   running, the screen tells the user to turn it off in Accessibility
   settings instead of claiming it worked.
5. Turns wireless debugging off by writing the system setting (only when
   Mika holds the write-secure-settings permission). If the write is not
   allowed, the screen tells the user to turn it off in Developer options.

Pause does NOT unpair, does NOT delete the pairing key
(`noBackupFilesDir/adb/adbkey.bin`), does NOT delete the port cache, does
NOT uninstall anything, does NOT delete data. `BuiltinShellControl.unpair()`
is never called on this path.

## What Resume does

1. Clears the persisted `paused` flag.
2. Turns wireless debugging back on (same system-setting write, when
   allowed).
3. Reconnects the helper with the **saved pairing** — `BuiltinShellControl.resume()`
   relaunches from the stored identity and port cache. No new pairing code.
   Only if the pairing is actually gone (helper status NotPaired) does the
   screen offer the re-pair flow.
4. Lists the steps the app cannot do itself, each as a button that opens the
   right system page:
   - **Turn Mika back on in Accessibility settings** — Android forbids an
     app from enabling its own accessibility service, so the user flicks it
     on there. This step is always listed unless the service is already on.
   - **Turn wireless debugging back on in Developer options** — only if
     Mika could not write the switch itself.
   - **Pair the phone helper again** — only if the pairing is gone.
5. Shows the live state afterwards (accessibility on/off, wireless
   debugging on/off, helper running/paused/not running) — read fresh from
   the system, never assumed. If the helper is paired but not connected yet,
   a "Reconnect helper now" row retries it.

## Edge cases

- Pausing while a task is running stops the task first.
- Accessibility already off: pause still disconnects the helper, and vice versa.
- Helper not paired: pause still works; resume shows the re-pair step.
- Pause before the engine starts (e.g. right after a restart) keeps the
  helper stopped until resume.
- `BuiltinShellStatus.Paused` is a new status. `paired` stays true while
  paused (the pairing is intact), so the phone-setup screens and the
  recovery policy treat it as "paired, not running" rather than "never set up".

## Tests

- `core/.../BuiltinShellEngineTest`: pause stops the helper and the
  watchdog without forgetting; resume reconnects with the saved pairing;
  pause before start keeps the helper stopped; resume without pairing does
  not launch.
- `core/.../AccessibilityRepairTest`: repair never fires while the helper
  is Paused, even when Mika holds the write-secure-settings permission.
- `app/.../BankingModeTest`: the resume-step planner (`planResumeSteps`)
  lists exactly the manual steps the live state still needs.
