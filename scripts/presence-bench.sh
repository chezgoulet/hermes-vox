#!/usr/bin/env bash
# presence-bench.sh — run one presence-model measurement on a connected phone.
#
# The harness is an instrumented test (android/app/src/androidTest/.../PresenceBenchTest.kt)
# so it runs the same pinned LiteRT-LM the app ships, on the device, in the app's own process.
# This script is the host driver: it installs nothing it does not need to, pushes the model,
# records the thermal/Ambient conditions around the run, and pulls the JSONL results.
#
#   scripts/presence-bench.sh qwen cpu 3
#   scripts/presence-bench.sh gemma gpu 1
#
# Requirements: the debug app is installed on the phone (its process is the measurement
# process), the androidTest APK is built, and the .litertlm files are in $MODEL_SRC.
set -euo pipefail

MODEL_ID="${1:?model id: qwen | smollm | gemma}"
BACKEND="${2:-cpu}"
REPS="${3:-3}"
SHAPE="${4:-presence}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ADB="${ADB:-${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb}"
MODEL_SRC="${MODEL_SRC:-$HOME/vox-models}"
PKG=com.hermesvox
TEST_PKG=com.hermesvox.test
BENCH_DIR=/sdcard/Android/data/com.hermesvox/files/bench
OUT="${OUT:-$ROOT/presence-bench-results}"

case "$MODEL_ID" in
  qwen)   FILE=Qwen3.5-0.8B_int8.litertlm ;;
  smollm) FILE=SmolLM2_360M_instruct.litertlm ;;
  gemma)  FILE=gemma-4-E2B-it.litertlm ;;
  *) echo "unknown model id: $MODEL_ID" >&2; exit 2 ;;
esac

mkdir -p "$OUT"
TAG="${MODEL_ID}-${BACKEND}-${SHAPE}"

thermal() {
  # Thermal status (0 none .. 6 shutdown) + battery temperature + the hottest zone.
  local status batt hottest
  status="$($ADB shell dumpsys thermalservice 2>/dev/null | sed -n 's/.*Thermal Status: \([a-z]*\).*/\1/p' | head -1)"
  batt="$($ADB shell dumpsys battery 2>/dev/null | sed -n 's/.*temperature: \([0-9]*\).*/\1/p' | head -1)"
  hottest="$($ADB shell 'cat /sys/class/thermal/thermal_zone*/temp 2>/dev/null | sort -n | tail -1' | tr -d '\r')"
  printf '{"t":%s,"thermal_status":"%s","battery_c_x10":%s,"hottest_zone_mc":%s}' \
    "$(date +%s)" "${status:-unknown}" "${batt:-0}" "${hottest:-0}"
}

echo "== $TAG: thermal before"
BEFORE="$(thermal)"; echo "$BEFORE"

# The model, if it is not already on the phone.
if ! $ADB shell "[ -f $BENCH_DIR/$FILE ]" 2>/dev/null; then
  # The model dir must be APP-owned: /sdcard/Android/data/<pkg> created by the shell cannot be
  # written by the app, and the harness needs to create its per-run compile-cache dirs inside it.
  # A no-op instrumentation run creates it as the app.
  if ! $ADB shell "[ -d $BENCH_DIR ]" 2>/dev/null; then
    echo "== creating the app-owned model dir"
    $ADB shell am instrument -w -e bench.models none \
      "$TEST_PKG/androidx.test.runner.AndroidJUnitRunner" >/dev/null 2>&1 || true
  fi
  echo "== pushing $FILE ($(stat -c%s "$MODEL_SRC/$FILE") bytes)"
  $ADB push "$MODEL_SRC/$FILE" "$BENCH_DIR/" >/dev/null
fi

# One fresh process per run, so VmHWM is that model+backend's peak and nothing else's.
# Results go to a per-run internal file, so a killed run can never be mis-attributed to the
# next one.
RESULTS_REL="files/bench-results/$TAG.jsonl"
RESULTS_ABS="/data/user/0/$PKG/$RESULTS_REL"
echo "== instrumenting $TAG reps=$REPS"
set +e
timeout 420 $ADB shell am instrument -w \
  -e bench.models "$MODEL_ID" \
  -e bench.backends "$BACKEND" \
  -e bench.reps "$REPS" \
  -e bench.shape "$SHAPE" \
  -e bench.dir "$BENCH_DIR" \
  -e bench.results "$RESULTS_ABS" \
  "$TEST_PKG/androidx.test.runner.AndroidJUnitRunner" 2>&1 | tail -25
rc=${PIPESTATUS[0]}
# rc 124 = the host timeout fired. A native init that never returns keeps the app process
# wedged, so the NEXT instrumentation would run against it; clear it before moving on.
if [ "$rc" = "124" ]; then
  echo "== timeout — force-stopping $PKG so the next run gets a clean process"
  $ADB shell am force-stop "$PKG" || true
fi
set -e

AFTER="$(thermal)"; echo "== thermal after: $AFTER"

# Pull the results the test appended (internal storage, survives a killed run).
$ADB exec-out run-as "$PKG" cat "$RESULTS_REL" > "$OUT/$TAG.jsonl" 2>/dev/null || true
$ADB shell run-as "$PKG" rm -f "$RESULTS_REL" 2>/dev/null || true

printf '{"run":"%s","instrument_rc":%s,"before":%s,"after":%s}\n' \
  "$TAG" "$rc" "$BEFORE" "$AFTER" >> "$OUT/conditions.jsonl"

echo "== results: $OUT/$TAG.jsonl ($(wc -l < "$OUT/$TAG.jsonl" 2>/dev/null || echo 0) lines)"
exit "$rc"
