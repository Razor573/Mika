#!/usr/bin/env bash
# Install (or update) Mika 2 so Android records Google Play as the installer.
# Same purpose and limits as install-mika2.ps1 in this folder - read the
# header there (and docs/installer-source-and-banking-apps.md) before use:
# `adb install -i com.android.vending` only sets the *installing* package,
# the initiating package stays com.android.shell, and the record is replaced
# again by any later update via the in-app updater or a browser download,
# so repeat this script for EVERY update. A Google Play listing is the only
# permanent fix; the app itself cannot set the Play record.
#
# Usage (macOS / Linux, phone connected, USB debugging on):
#   ./tools/install-mika2.sh path/to/mika2-release.apk
# The APK is the `mika2-release-apk` artifact from the Build APK workflow
# run on GitHub (Razor573/Mika > Actions > Build APK > Artifacts). Install
# the release APK, never the debug one.
set -euo pipefail

APK_PATH="${1:-}"
PACKAGE_NAME="${PACKAGE_NAME:-com.wangzan101.mika2}"
INSTALLER="${INSTALLER:-com.android.vending}"

if [ -z "$APK_PATH" ]; then
  echo "usage: $0 path/to/mika2-release.apk" >&2
  exit 1
fi
if ! command -v adb >/dev/null 2>&1; then
  echo "adb was not found in PATH. Install Android platform-tools first." >&2
  exit 1
fi
if [ ! -f "$APK_PATH" ]; then
  echo "APK not found: $APK_PATH" >&2
  exit 1
fi
if ! adb devices | grep -q $'\tdevice$'; then
  echo "No authorised ADB device found. Connect the phone, allow USB debugging on it, then run 'adb devices' and check it shows 'device'." >&2
  exit 1
fi

echo "Installing $APK_PATH as $PACKAGE_NAME with installer $INSTALLER ..."
adb install -r -i "$INSTALLER" "$APK_PATH"

echo
echo "Verifying installer record:"
adb shell dumpsys package "$PACKAGE_NAME" | grep -E "installerPackageName|initiatingPackageName|packageSource" || true
echo
echo "Expected: installerPackageName=$INSTALLER. initiatingPackageName will still be com.android.shell - that is normal for an ADB install and cannot be changed from here."
echo "Now open HSBC UK and check it no longer flags Mika 2. If it still does, it is checking more than the installing package, and only a Play Store listing will fix it."
