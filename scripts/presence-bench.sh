#!/usr/bin/env bash
# presence-bench.sh — run one presence-model measurement on a connected phone.
#
# The harness is an instrumented test (android/app/src/androidTest/.../PresenceBenchTest.kt)
# so it runs the same pinned LiteRT-LM the app ships, on the device, in the app's own process.
# This script is the host driver: it installs nothing it does not need to, pushes the model,
# records the thermal/Ambient conditions around the run, and pulls the JSONL results.
#
#   scripts/presence-bench.sh qwen cpu 3 presence
#   scripts/presence-bench.sh gemma gpu 1 presence cool
#
# Requirements: the debug app is installed on the phone (its process is the measurement
# process), the androidTest APK is built, and the .litertlm files are in $MODEL_SRC.
set -euo pipefail

MODEL_ID="${1:?model id: qwen | smollm | gemma}"
BACKEND="${2:-cpu}"
REPS="${3:-3}"
SHAPE="${4:-presence}"
# Free-form tag for the result file name (a pass name, "cool", "busy"...). Without it two
# passes of the same row silently overwrite each other's numbers.
EXTRA="${5:-}"
# KV budget to request; empty = the model's own default. The app itself asks for 8192.
MAXTOK="${MAXTOK:-}"
# CPU thread count; empty = the runtime default. The app never sets one, so the default row
# is the honest one and a threads row is a diagnostic.
THREADS="${THREADS:-}"
# One Engine per render (what Qwen3.5 requires; the app uses one Engine + many Conversations).
FRESH="${FRESH:-}"
# Output-token cap; empty = the harness default (32, a presence line). A long shape uses 128.
MAXOUT="${MAXOUT:-}"

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
TAG="${MODEL_ID}-${BACKEND}-${SHAPE}${THREADS:+-t$THREADS}${FRESH:+-fresh}${MAXOUT:+-o$MAXOUT}${EXTRA:+-$EXTRA}"

thermal() {
  # Thermal status (0 none .. 6 shutdown), battery temperature, and the hottest CPU/GPU zone.
  # /sys/class/thermal needs root on this build, so the zones come from dumpsys instead.
  local dump status batt big
  dump="$($ADB shell dumpsys thermalservice 2>/dev/null)"
  status="$(printf '%s' "$dump" | sed -n 's/^Thermal Status: *\([0-9]*\).*/\1/p' | head -1)"
  batt="$($ADB shell dumpsys battery 2>/dev/null | sed -n 's/.*temperature: \([0-9]*\).*/\1/p' | head -1)"
  big="$(printf '%s' "$dump" | sed -n 's/.*Temperature{mValue=\([0-9.]*\).*mName=BIG.*/\1/p' | head -1)"
  printf '{"t":%s,"thermal_status":%s,"battery_c_x10":%s,"big_c":%s}' \
    "$(date +%s)" "${status:-null}" "${batt:-0}" "${big:-null}"
}

echo "== $TAG: thermal before"
BEFORE="$(thermal)"; echo "$BEFORE"

# The model, if it is not already on the phone at the right size. A push killed by a host
# timeout leaves a TRUNCATED file behind, and a truncated .litertlm fails deep inside the
# runtime — so compare sizes rather than existence.
WANT=$(stat -c%s "$MODEL_SRC/$FILE")
HAVE=$($ADB shell "stat -c %s $BENCH_DIR/$FILE 2>/dev/null" 2>/dev/null | tr -d "\r")
if [ "$HAVE" != "$WANT" ]; then
  # The model dir must be APP-owned: /sdcard/Android/data/<pkg> created by the shell cannot be
  # written by the app, and the harness needs to create its per-run compile-cache dirs inside it.
  # A no-op instrumentation run creates it as the app.
  if ! $ADB shell "[ -d $BENCH_DIR ]" 2>/dev/null; then
    echo "== creating the app-owned model dir"
    $ADB shell am instrument -w -e bench.models none \
      "$TEST_PKG/androidx.test.runner.AndroidJUnitRunner" >/dev/null 2>&1 || true
  fi
  echo "== pushing $FILE ($WANT bytes; phone had ${HAVE:-none})"
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
  ${MAXTOK:+-e bench.maxTokens "$MAXTOK"} \
  ${THREADS:+-e bench.threads "$THREADS"} \
  ${FRESH:+-e bench.freshEngine true} \
  ${MAXOUT:+-e bench.maxOut "$MAXOUT"} \
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
