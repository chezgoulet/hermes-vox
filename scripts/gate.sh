#!/usr/bin/env bash
# Hermes Vox — full build/verify gate (handoff §10).
# One command: fetch the pinned runtime -> Gradle assembleDebug + unit tests ->
# confirm the stream parity harness ran. The voice core is Kotlin (#126); there is
# no Go, gomobile or NDK step.
#
# Portable: runs from any checkout. Toolchain locations come from the
# environment (JAVA_HOME, ANDROID_HOME); the defaults below are the maintainer's
# build host and only apply when a variable is unset.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

export JAVA_HOME="${JAVA_HOME:-$HOME/jdk-17}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"

# A gate that silently substitutes a different toolchain is not a gate: the
# release build is pinned to JDK 17, so a missing default must stop the run.
gate_die() { echo "gate: $*" >&2; exit 2; }
[ -x "$JAVA_HOME/bin/java" ] || gate_die "JAVA_HOME=$JAVA_HOME has no bin/java. Set JAVA_HOME to a JDK 17."
java_major="$("$JAVA_HOME/bin/java" -version 2>&1 | sed -nE 's/.*version "([0-9]+).*/\1/p' | head -n1)"
[ "$java_major" = "17" ] || gate_die "JAVA_HOME=$JAVA_HOME is Java ${java_major:-unknown}; this gate is pinned to 17."
[ -d "$ANDROID_HOME" ] || gate_die "ANDROID_HOME=$ANDROID_HOME does not exist."
echo "gate: JAVA_HOME=$JAVA_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

echo "== [0/6] fetch pinned runtime deps =="
bash scripts/fetch-runtime.sh

echo "== [1/6] presence report self-test =="
python3 scripts/test_presence_session_report.py 2>&1 | tail -3

# #221: the app skill (skills/hermes-vox-app/SKILL.md) is the app's self-description,
# and a skill that disagrees with the code is wrong. The checker verifies every
# file:line claim in it (file exists, line exists, the claim's token is on it), so a
# refactor that moves the code it describes fails the gate instead of rotting quietly.
echo "== [2/6] app skill claims =="
python3 scripts/check-skill-claims.py

echo "== [3/6] gradle assembleDebug + unit tests =="
(cd android && ./gradlew --no-daemon -q clean assembleDebug testDebugUnitTest)
ls -la android/app/build/outputs/apk/debug/*.apk

echo "== [4/6] stream parity harness ran =="
# StreamParityTest replays testdata/stream/*.sse against the committed goldens.
# A gate that passes because the harness silently stopped running is not a gate.
parity=android/app/build/test-results/testDebugUnitTest/TEST-com.hermesvox.core.StreamParityTest.xml
[ -f "$parity" ] || gate_die "StreamParityTest did not run ($parity missing)."
grep -q 'tests="[1-9][0-9]*" skipped="0" failures="0" errors="0"' "$parity" \
  || gate_die "StreamParityTest did not pass cleanly: $(grep -o 'tests="[^>]*' "$parity" | head -n1)"
echo "gate: parity OK over $(ls testdata/stream/*.sse | wc -l) fixtures"

echo "== [5/6] live gateway suite ran =="
# #167: the six live cases run against the deterministic fake gateway on every
# build, plus the case that proves the harness can fail. None may be skipped.
live=android/app/build/test-results/testDebugUnitTest/TEST-com.hermesvox.core.LiveGatewayTest.xml
[ -f "$live" ] || gate_die "LiveGatewayTest did not run ($live missing)."
grep -q 'tests="[1-9][0-9]*" skipped="0" failures="0" errors="0"' "$live" \
  || gate_die "LiveGatewayTest did not pass cleanly: $(grep -o 'tests="[^>]*' "$live" | head -n1)"
echo "gate: live suite OK ($(grep -o 'tests="[0-9]*"' "$live" | head -n1) — six live cases + the harness self-check)"

echo "GATE-GREEN"
