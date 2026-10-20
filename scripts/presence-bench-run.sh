#!/usr/bin/env bash
# presence-bench-run.sh — one lock window: install the harness, then run the sweep.
#
# The device is shared. Holding /home/c/phone.lock across the install AND the measurements
# is what keeps another agent's install from landing mid-run (which is how the first sweep
# attempt died: the app was replaced under the instrumented process).
set -u
cd "$(dirname "$0")/.."
export ADB="${ADB:-${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb}"
APK=android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk

echo "[$(date +%H:%M:%S)] installing $APK"
"$ADB" install -r -t "$APK" || { echo "install failed"; exit 3; }

exec bash scripts/presence-bench-all.sh
