#!/usr/bin/env bash
# presence-bench-all.sh — the whole D4 measurement sweep, under one phone lock.
#
# Runs one instrumented measurement per (model, backend). Order matters on a shared phone:
# the CPU rows first (they are the ones the decision needs and they always complete), the
# GPU rows last, because a GPU engine that cannot fit can wedge the process and needs a
# force-stop before the next run.
#
# Cold-start protocol: the harness deletes its per-run compile cache before every cold init,
# and each row is a fresh process. Thermal state is recorded before and after each row.
set -u
cd "$(dirname "$0")/.."

export ADB="${ADB:-${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb}"
OUT="$PWD/presence-bench-results"
mkdir -p "$OUT"
LOG="$OUT/sweep.log"
: > "$LOG"

say() { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }

say "sweep start; thermal: $($ADB shell dumpsys thermalservice 2>/dev/null | sed -n 's/.*Thermal Status: *\([a-z]*\).*/\1/p' | head -1)"

run() {
  local model="$1" backend="$2" reps="${3:-3}" shape="${4:-presence}"
  say "--- $model/$backend/$shape x$reps"
  bash scripts/presence-bench.sh "$model" "$backend" "$reps" "$shape" >> "$LOG" 2>&1
  local rc=$?
  local n; n=$(wc -l < "$OUT/$model-$backend-$shape.jsonl" 2>/dev/null || echo 0)
  say "--- $model/$backend/$shape done rc=$rc rows=$n"
}

# CPU first: the rows the decision rests on.
run smollm cpu 3 presence
run qwen   cpu 3 presence
run gemma  cpu 3 presence

# The long-persona shape on the two candidates: what the app's real render prefills.
run qwen   cpu 2 persona
run gemma  gpu 2 persona

# GPU last: an engine that does not fit can stall initialization.
run smollm gpu 3 presence
run gemma  gpu 3 presence
run qwen   gpu 2 presence

say "sweep done"
say "final thermal: $($ADB shell dumpsys thermalservice 2>/dev/null | sed -n 's/.*Thermal Status: *\([a-z]*\).*/\1/p' | head -1)"
