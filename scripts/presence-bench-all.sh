#!/usr/bin/env bash
# presence-bench-all.sh — the whole D4 measurement sweep, under one phone lock.
#
# Order matters on a shared phone: the CPU rows first (they are the ones the decision needs
# and they always complete), the GPU rows last, because a GPU engine that cannot fit can
# wedge the process and needs a force-stop before the next run.
#
# Cold-start protocol: the harness deletes its per-run compile cache before every cold init,
# each row is a fresh process, and the model is read from INTERNAL storage (a model mmap'd
# from FUSE-backed /sdcard measures the storage layer, not the model).
set -u
cd "$(dirname "$0")/.."

export ADB="${ADB:-${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb}"
OUT="$PWD/presence-bench-results"
mkdir -p "$OUT"
LOG="$OUT/sweep.log"
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
  local model="$1" backend="$2" reps="${3:-3}" shape="${4:-presence}" tag="${5:-}" fresh="${6:-}"
  say "--- $model/$backend/$shape${tag:+ [$tag]}${fresh:+ fresh-engine} x$reps  ($(thermal_now))"
  FRESH="$fresh" bash scripts/presence-bench.sh "$model" "$backend" "$reps" "$shape" >> "$LOG" 2>&1
  local rc=$?
  local n; n=$(wc -l < "$OUT/$model-$backend-$shape${tag:+-$tag}${fresh:+-fresh}.jsonl" 2>/dev/null || echo 0)
  say "--- $model/$backend/$shape${fresh:+ fresh-engine} done rc=$rc rows=$n"
}

# CPU first: the rows the decision rests on.
run smollm cpu 3 presence
run qwen   cpu 3 presence          # the app's shape: one Engine, a new Conversation per render
run qwen   cpu 3 presence "" fresh  # what Qwen actually requires: one Engine per render
run gemma  cpu 3 presence

# The app's real render shape: a ~600-token persona is what the presence layer prefills.
run qwen   cpu 2 persona "" fresh

# GPU last: an engine that does not fit can stall initialization.
run gemma  gpu 3 presence
run smollm gpu 3 presence
run qwen   gpu 2 presence "" fresh
run qwen   gpu 2 presence

say "sweep done ($(thermal_now))"
