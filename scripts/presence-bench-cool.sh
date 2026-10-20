#!/usr/bin/env bash
# presence-bench-cool.sh — the second pass: a settled phone, and a decode rate measured over
# enough tokens to mean something.
#
# The first sweep ran on a phone another agent had been hammering (BIG junction at 97 C), and
# a 32-token cap renders "Hello!" in two chunks, which is not a decode rate. This pass idles
# first, records the thermal state on arrival, and asks for a paragraph (maxOut 128) so the
# decode figure is measured over dozens of chunks.
set -u
cd "$(dirname "$0")/.."
export ADB="${ADB:-${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb}"
OUT="$PWD/presence-bench-results"
LOG="$OUT/sweep-cool.log"
: > "$LOG"
say() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }

thermal_now() {
  local dump; dump="$($ADB shell dumpsys thermalservice 2>/dev/null)"
  local st big
  st="$(printf '%s' "$dump" | sed -n 's/^Thermal Status: *\([0-9]*\).*/\1/p' | head -1)"
  big="$(printf '%s' "$dump" | sed -n 's/.*Temperature{mValue=\([0-9.]*\).*mName=BIG.*/\1/p' | head -1)"
  echo "status=${st:-?} BIG=${big:-?}C"
}

run() {
  local model="$1" backend="$2" reps="${3:-3}" shape="${4:-presence}" tag="${5:-}" fresh="${6:-}" maxout="${7:-}"
  say "--- $model/$backend/$shape${tag:+ [$tag]}${fresh:+ fresh}${maxout:+ out=$maxout} x$reps ($(thermal_now))"
  MAXOUT="$maxout" FRESH="$fresh" bash scripts/presence-bench.sh "$model" "$backend" "$reps" "$shape" "$tag" >> "$LOG" 2>&1
  say "--- done rc=$? ($(thermal_now))"
}

say "device: $($ADB shell getprop ro.product.model) / Android $($ADB shell getprop ro.build.version.release) (API $($ADB shell getprop ro.build.version.sdk)) / $($ADB shell getprop ro.product.cpu.abi)"

# The phone was at 97 C at the end of the first sweep. Let it settle before the control rows.
say "idling 360 s for a settled handset"
sleep 360

run gemma gpu 3 presence cool
run gemma cpu 3 presence cool
run qwen  cpu 3 presence cool

# Decode rate over a paragraph rather than over "Hello!".
run gemma gpu 2 long long "" "" 128
run qwen  cpu 2 long long "" "" 128
run smollm cpu 2 long long "" "" 128
say "second pass done ($(thermal_now))"
