#!/usr/bin/env bash
# Streaming-STT A/B — one command (docs/STT-AB.md).
#
#   1. fetch the pinned runtime and build the bench APK (:bench). It is a
#      SEPARATE package (com.hermesvox.bench), so the copy of Hermes Vox on the
#      shared phone is never replaced or restarted by a measurement;
#   2. under the project's phone lock, install it, push the corpus + each arm's
#      model directory, run every arm x condition in real time, and print the
#      summary table.
#
# The lock is held for the WHOLE install + measure window, as the shared-Pixel
# protocol requires. Builds take the build lock separately.
#
#   scripts/stt-ab.sh                              # build + all arms, all conditions
#   scripts/stt-ab.sh --arms zip20m --conditions quiet
#   scripts/stt-ab.sh --device-only --score-only   # rescan results, no device run
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-$HOME/jdk-17}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$HOME/.local/bin:$PATH"
export SERIAL="${SERIAL:-2A091FDH200AJ1}"
PHONE_LOCK="${PHONE_LOCK:-$HOME/phone.lock}"
BUILD_LOCK="${BUILD_LOCK:-$HOME/build.lock}"

DEVICE_ONLY=0
FORWARD=()
for a in "$@"; do
  if [ "$a" = "--device-only" ]; then DEVICE_ONLY=1; else FORWARD+=("$a"); fi
done

if [ "$DEVICE_ONLY" = 0 ]; then
  echo "== [1/2] fetch runtime + build :bench =="
  bash scripts/fetch-runtime.sh
  flock "$BUILD_LOCK" -c "cd '$ROOT/android' && ./gradlew --no-daemon -q :bench:assembleDebug"
fi

echo "== [2/2] device phase — phone lock held for install + measure =="
mkdir -p tools/sttbench-stream/results
flock "$PHONE_LOCK" bash -c '
  set -o pipefail
  adb -s "$SERIAL" install -r -t android/bench/build/outputs/apk/debug/bench-debug.apk || exit 3
  python3 tools/sttbench-stream/run_ab.py --prepare "$@" 2>&1 | tee tools/sttbench-stream/results/last-run.log
  rc=$?
  # leave the shared phone as we found it: Hermes Vox back in the foreground
  adb -s "$SERIAL" shell am start -n com.hermesvox/.MainActivity >/dev/null 2>&1 || true
  exit $rc
' _ "${FORWARD[@]}"
