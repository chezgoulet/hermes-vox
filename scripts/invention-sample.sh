#!/usr/bin/env bash
# invention-sample.sh — #256: sample the presence model on a phone, then classify what it invents.
#
# Observation only: it gates nothing and changes no bound. The sampler is an instrumented test
# (android/app/src/androidTest/.../InventionSampleTest.kt) so it renders with the app's own
# persona, directives and runtime, in the app's process. This script runs it, pulls the JSONL,
# and hands it to scripts/invention-report.py. Output shape: docs/INVENTION-MEASUREMENT.md.
#
#   scripts/invention-sample.sh                 # 20 samples per (mode, context), CPU
#   SAMPLES=50 BACKEND=gpu scripts/invention-sample.sh
#   MODES=narrate scripts/invention-sample.sh
#
# Needs: a phone on adb with the debug app installed and its presence model downloaded (or the
# .litertlm pushed to the bench dir), and the androidTest APK installed
# (cd android && ./gradlew installDebug installDebugAndroidTest).
set -euo pipefail

SAMPLES="${SAMPLES:-20}"
BACKEND="${BACKEND:-cpu}"
MODES="${MODES:-greet,answer,narrate}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ADB:-${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb}"
PKG=com.hermesvox
TEST_PKG=com.hermesvox.test
OUT="${OUT:-$ROOT/invention-results}"
STAMP="$(date +%Y%m%d-%H%M%S)"
REL="files/invention/$STAMP.jsonl"

"$ADB" get-state >/dev/null 2>&1 || { echo "invention-sample: no device on adb" >&2; exit 2; }
mkdir -p "$OUT"
echo "== sampling: modes=$MODES samples=$SAMPLES backend=$BACKEND"
# Generous host timeout: three modes x up to six contexts x SAMPLES renders of ~2-3 s each.
timeout $(( 600 + SAMPLES * 13 * 6 )) "$ADB" shell am instrument -w \
  -e class com.hermesvox.InventionSampleTest \
  -e inv.samples "$SAMPLES" -e inv.modes "$MODES" -e inv.backend "$BACKEND" \
  -e inv.results "/data/user/0/$PKG/$REL" \
  "$TEST_PKG/androidx.test.runner.AndroidJUnitRunner" | tail -5
"$ADB" exec-out run-as "$PKG" cat "$REL" > "$OUT/$STAMP.jsonl"
"$ADB" shell run-as "$PKG" rm -f "$REL" || true
echo "== $(wc -l < "$OUT/$STAMP.jsonl") records: $OUT/$STAMP.jsonl"
python3 "$ROOT/scripts/invention-report.py" "$OUT/$STAMP.jsonl"
