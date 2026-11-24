# Export and import all app data as a plain ZIP backup

## Summary

Adds a full-app backup export and import flow under Settings -> Data -> Backup & data (`BackupScreen.kt`, `BackupEngine.kt`, `BackupPrefs.kt`).

- **Export** decrypts Keystore-protected app secrets (provider API keys, decision model key, OAuth/web search/web fetch keys, encrypted call history, workflow/checklist secrets) and writes all app preferences and data files into a single plain ZIP archive containing a versioned `manifest.json` (`version = 1`), saved through Android's system document picker.
- **Import** validates the entire ZIP structure, paths, and manifest before touching existing data, stages files safely, and re-encrypts every protected entry under the current installation's fresh Android Keystore keys. When local data already exists, the UI asks for explicit confirmation (`Replace all`) and instructs the user to close and reopen Mika so all stores reload from disk.

## Why upstream users need it

Because Mika encrypts stored API keys and private app files with hardware/Keystore keys tied to the current app installation, moving to a new phone or reinstalling the app currently loses configured providers, keys, workflows, chats, and personal data. This gives users a portable, offline migration and backup path with zero cloud dependency.

## Key files

- `app/src/main/java/com/wangzan101/mika/backup/BackupEngine.kt`
- `app/src/main/java/com/wangzan101/mika/backup/BackupPrefs.kt`
- `app/src/main/java/com/wangzan101/mika/backup/ui/BackupScreen.kt`
- `app/src/main/java/com/wangzan101/mika/settings/ui/SettingsScreen.kt`
- `app/src/main/res/values/strings_backup.xml`
- `app/src/test/java/com/wangzan101/mika/backup/BackupEngineTest.kt`
- `docs/backup-export-import.md`

## How to test on a phone

1. Open Settings -> Data -> Backup & data and tap **Export backup** to save a backup ZIP file via the system picker.
2. Open the saved ZIP and verify it contains `manifest.json` along with decrypted JSON/XML data (remember to treat the plain ZIP carefully since it contains unencrypted keys).
3. Uninstall and reinstall Mika (or install on a second device so new Keystore keys are generated).
4. Open Settings -> Data -> Backup & data -> **Import backup**, select the ZIP, confirm replacement if prompted, then close and reopen Mika.
5. Verify configured providers/keys, chat history, personas, memories, workflows, checklists, web search/fetch keys, and call history work normally under the new installation.

## What it does NOT do

- Does not password-encrypt or password-protect the exported ZIP archive (it is a plain ZIP containing decrypted keys so it can be restored across Keystore rotations).
- Does not back up or transfer Android ADB pairing keys (`adbkey`), system Accessibility permission, the bundled sandbox rootfs/workspace, or large file-library binary attachments.
- Does not automatically restart or hot-reload in-memory singletons; users must close and reopen Mika after import.

## Deliberately left out

- Fork version bump, Play internal testing docs, fork branding, and all unrelated fork providers or settings.
