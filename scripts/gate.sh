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

# Two gates at once on one host are not two independent gates. The worktrees are
# separate but ~/.gradle is not, and a shared test-results directory corrupted by
# contention reads as a failing test ("Could not write XML test results",
# output.bin.idx missing) — a failure that sends the reader hunting a bug that
# does not exist. So gate runs serialise per host, on a path outside every
# checkout, for the whole run (gradle, fetch-runtime.sh and the parity harness all
# contend, not just gradle).
GATE_LOCK="${HERMES_VOX_GATE_LOCK:-/tmp/hermes-vox-gate.lock}"
# The overrides exist so the lock's behaviour can be exercised without waiting an
# hour or touching the host-wide path; a normal run uses the defaults.
GATE_LOCK_TIMEOUT="${HERMES_VOX_GATE_LOCK_TIMEOUT:-3600}"

# Best effort: name the holder so a timeout says who is blocking. The gate writes
# its pid into the lock file; if that is unreadable or stale, ask the platform.
# Empty when nobody can say — the message degrades, it does not invent a holder.
gate_lock_holder() {
  local pid=""
  pid="$(sed -n 's/^pid=\([0-9][0-9]*\).*/\1/p' "$GATE_LOCK" 2>/dev/null | head -n1)"
  if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
    printf 'PID %s' "$pid"
    return 0
  fi
  if command -v fuser >/dev/null 2>&1; then
    pid="$(fuser "$GATE_LOCK" 2>/dev/null | tr -s ' \t' ' ' | sed 's/^ //; s/ $//')"
    if [ -n "$pid" ]; then printf 'PID %s' "$pid"; fi
  fi
  return 0
}

gate_acquire_lock() {
  if ! command -v flock >/dev/null 2>&1; then
    # macOS has no flock(1). Say so — a different behaviour stated beats one hidden.
    echo "gate: flock not available on this platform — running UNLOCKED (two gates on this host may corrupt each other's test results)"
    return 0
  fi
  # fd 9 is held for the life of the script. The lock goes with the process, however
  # it dies (exit, error, SIGKILL): a dead gate cannot leave a stale lock behind.
  exec 9>>"$GATE_LOCK" || gate_die "cannot open the gate lock $GATE_LOCK"
  if ! flock -n 9; then
    echo "gate: another gate is running on this host — waiting"
    if ! flock -w "$GATE_LOCK_TIMEOUT" 9; then
      holder="$(gate_lock_holder)"
      gate_die "could not acquire $GATE_LOCK within ${GATE_LOCK_TIMEOUT}s${holder:+; still held by $holder}"
    fi
    echo "gate: lock acquired — continuing"
  fi
  # Diagnostics only: the pid lets a waiter's timeout message name the holder.
  printf 'pid=%s started=%s\n' "$$" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >"$GATE_LOCK" 2>/dev/null || true
  trap 'exec 9>&-' EXIT
  return 0
}

gate_acquire_lock

# Everything below runs in a subshell whose lock descriptor is closed, so the
# children the gate spawns (gradle, python, the parity harness) cannot inherit —
# and therefore cannot keep holding — the lock. This process alone holds it, and
# the kernel drops it when this process dies, including SIGKILL. No stale lock,
# no lock a later gate has to wait on because an orphan build still has it.
(
exec 9>&-

echo "== [0/6] fetch pinned runtime deps =="
bash scripts/fetch-runtime.sh

echo "== [1/6] presence report self-tests =="
python3 scripts/test_presence_session_report.py 2>&1 | tail -3
# #417: the report pipeline's parser, grouping, counts and malformed handling — including the
# daily script's "post only when the numbers changed" cadence, against the checked-in fixture.
python3 scripts/test_presence_reports.py 2>&1 | tail -3
# #256: the invention classifier's own tests. The measurement gates nothing; its code is kept honest.
python3 scripts/test_invention_report.py 2>&1 | tail -3

# #221: the app skill (skills/hermes-vox-app/SKILL.md) is the app's self-description,
# and a skill that disagrees with the code is wrong. The checker verifies every
# claim in it (the file exists and the claim's token occurs in it exactly once), so
# code that moves out from under a sentence fails the gate instead of rotting quietly.
echo "== [2/6] app skill claims =="
python3 scripts/check-skill-claims.py

# The design documents make specific claims about the code. Until now nothing checked
# them, which is how DECISIONS.md came to say "Presence remains 0.9.2" while the
# milestone said otherwise. docs/DOC-TRUTH.yml names each claim, the document that
# makes it and the pattern that proves it, so either side drifting fails here.
echo "== [2b/6] design documents against the code =="
python3 scripts/check-doc-truth.py

echo "== [3/6] gradle assembleDebug + unit tests =="
(cd android && ./gradlew --no-daemon -q clean assembleDebug testDebugUnitTest)
ls -la android/app/build/outputs/apk/debug/*.apk

# The mTLS fixtures are test-only keys. "They are under src/test/resources" is a claim
# about the source layout; this checks the ARTIFACT, so a packaging change that swept the
# test tree into the APK would fail here instead of shipping a private key.
apk="$(ls android/app/build/outputs/apk/debug/*.apk | head -n1)"
key_material="$(unzip -l "$apk" | grep -Ei '\.(p12|pfx|jks|bks|keystore|pem)$' || true)"
[ -z "$key_material" ] || gate_die "the APK contains key material:
$key_material"

echo "== [4/6] stream parity & mTLS verification harnesses =="
# StreamParityTest replays testdata/stream/*.sse against the committed goldens.
# A gate that passes because the harness silently stopped running is not a gate.
# MtlsEntityTest is the one that goes through Entity.open to a real SSLServerSocket with
# needClientAuth: the alias lookup, the socket factory, the origin guard and the peer
# certificate — not a hand-built context that skips all of them.
for suite in \
  "TEST-com.hermesvox.core.StreamParityTest.xml" \
  "TEST-com.hermesvox.core.MtlsConfigTest.xml" \
  "TEST-com.hermesvox.core.MtlsKeyManagerTest.xml" \
  "TEST-com.hermesvox.core.MtlsHandshakeTest.xml" \
  "TEST-com.hermesvox.core.MtlsEntityTest.xml"; do
  xml="android/app/build/test-results/testDebugUnitTest/$suite"
  [ -f "$xml" ] || gate_die "Test suite $suite did not run ($xml missing)."
  grep -q 'tests="[1-9][0-9]*" skipped="0" failures="0" errors="0"' "$xml" \
    || gate_die "Test suite $suite did not pass cleanly: $(grep -o 'tests="[^>]*' "$xml" | head -n1)"
  echo "gate: $suite OK ($(grep -o 'tests="[0-9]*"' "$xml" | head -n1))"
done
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
)
