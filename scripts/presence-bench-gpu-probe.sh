#!/usr/bin/env bash
# presence-bench-gpu-probe.sh — what a Qwen GPU init does to memory on this phone.
#
# The engine init never returns for Qwen3.5 0.8B on the Pixel 7 (both shapes, two passes).
# This records the memory the attempt consumes while it is stuck, so the report can say what
# it needed rather than "it did not work": process RSS, the device's MemAvailable, and whether
# the low-memory killer took anything, sampled every 5 s for the life of the attempt.
set -u
cd "$(dirname "$0")/.."
export ADB="${ADB:-${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb}"
OUT="$PWD/presence-bench-results/qwen-gpu-init.txt"
PKG=com.hermesvox

: > "$OUT"
echo "# Qwen3.5-0.8B_int8.litertlm, GPU backend, Pixel 7 — memory during engine init" >> "$OUT"
echo "# $(date -u +%FT%TZ)  free before: $($ADB shell cat /proc/meminfo | sed -n 's/MemAvailable: *\([0-9]*\) kB/\1/p') kB available" >> "$OUT"
echo "# t_s  proc_rss_kb  proc_pss_kb  mem_available_kb  mem_free_kb" >> "$OUT"

$ADB shell am force-stop "$PKG" >/dev/null 2>&1
sleep 2

# Start the attempt in the background, then sample while it runs.
( $ADB shell am instrument -w \
    -e bench.models qwen -e bench.backends gpu -e bench.reps 1 \
    -e bench.shape presence -e bench.initTimeoutS 240 \
    -e bench.dir /sdcard/Android/data/$PKG/files/bench \
    -e bench.results /data/user/0/$PKG/files/bench-results/qwen-gpu-probe.jsonl \
    "$PKG.test/androidx.test.runner.AndroidJUnitRunner" > /tmp/qwen-gpu-probe.log 2>&1 ) &

for i in $(seq 0 48); do
  t=$((i * 5))
  rss="$($ADB shell "run-as $PKG cat /proc/self/status 2>/dev/null" 2>/dev/null | head -0)"
  # the test process is the app process; read it by pid
  pid="$($ADB shell pidof $PKG | tr -d '\r')"
  if [ -n "$pid" ]; then
    rss="$($ADB shell "cat /proc/$pid/status 2>/dev/null | sed -n 's/^VmRSS: *\([0-9]*\) kB/\1/p'" | tr -d '\r')"
  fi
  mem="$($ADB shell cat /proc/meminfo | tr -d '\r')"
  avail="$(printf '%s' "$mem" | sed -n 's/MemAvailable: *\([0-9]*\) kB/\1/p')"
  free="$(printf '%s' "$mem" | sed -n 's/MemFree: *\([0-9]*\) kB/\1/p')"
  pss="$($ADB shell dumpsys meminfo $PKG 2>/dev/null | sed -n 's/.*TOTAL PSS: *\([0-9]*\).*/\1/p' | head -1)"
  printf '%s  %s  %s  %s  %s\n' "$t" "${rss:-0}" "${pss:-0}" "${avail:-0}" "${free:-0}" >> "$OUT"
  sleep 5
done

echo "# instrumentation log:" >> "$OUT"
sed -n '1,40p' /tmp/qwen-gpu-probe.log >> "$OUT"
$ADB shell am force-stop "$PKG" >/dev/null 2>&1
echo "wrote $OUT"
