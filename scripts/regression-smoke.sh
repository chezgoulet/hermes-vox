#!/usr/bin/env bash
# regression-smoke — the automatable half of the per-cut regression set.
#
#   scripts/regression-smoke.sh start    install-independent: record the build, cold-start it,
#                                        clear the log, and print the manual set to walk.
#   scripts/regression-smoke.sh finish   pull the log, check the witnesses, and say what is
#                                        proved, what failed, and what it cannot see.
#
# The manual half is in docs/REGRESSION.md § "The regression set". This script never claims a
# path it did not observe: anything it cannot decide prints UNKNOWN with the line to look for.

set -u

PKG=com.hermesvox
STATE_DIR="${TMPDIR:-/tmp}/vox-regression"
LOG="$STATE_DIR/session.log"
MARK="$STATE_DIR/mark"

adb_ok() {
  command -v adb >/dev/null 2>&1 || { echo "adb not on PATH (set ANDROID_HOME)"; exit 1; }
  [ -n "$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')" ] || {
    echo "no device attached (adb devices shows none)"; exit 1; }
}

field() { adb shell dumpsys package "$PKG" 2>/dev/null | grep -m1 "$1" | sed 's/^ *//'; }

start() {
  adb_ok
  mkdir -p "$STATE_DIR"
  local ver code
  ver=$(field versionName)
  code=$(field versionCode)
  echo "== build under test"
  echo "   $ver"
  echo "   $code"
  echo

  echo "== cold start to first frame"
  adb shell am force-stop "$PKG" >/dev/null 2>&1
  sleep 1
  adb shell am start -W -n "$PKG/.MainActivity" 2>/dev/null \
    | grep -E "TotalTime|WaitTime|Status" | sed 's/^/   /'
  echo

  adb logcat -c >/dev/null 2>&1
  date +%s > "$MARK"
  echo "== log cleared. Walk the manual set in docs/REGRESSION.md:"
  cat <<'SET'
   3  a call reaches green (the readiness overlay clears)
   4  a short reply speaks and ends promptly          <- the #538 class
   5  a long reply streams and completes
   6  barge-in cuts the reply and takes the turn
   7  presence on: a line is produced, once, not repeated
   8  switch profile mid-use
   9  refresh profiles from the host
  10  open Settings during a live call: no picture-in-picture
  11  hang up, redial, second call starts clean
  12  export the log: every line carries a date
SET
  echo
  echo "Then: scripts/regression-smoke.sh finish"
}

count() { grep -c "$1" "$LOG" 2>/dev/null || true; }

say() { # label, value, verdict
  printf '   %-46s %-8s %s\n' "$1" "$2" "$3"
}

finish() {
  adb_ok
  [ -f "$MARK" ] || { echo "run 'start' first"; exit 1; }
  mkdir -p "$STATE_DIR"
  adb logcat -d -v time > "$LOG" 2>/dev/null
  local lines; lines=$(wc -l < "$LOG")

  echo "== build"
  field versionName
  echo "== log: $LOG ($lines lines)"
  echo

  echo "== witnesses"
  local drains_played drains_forced drains_timeout startvals pip mood
  drains_played=$(count "event=tts-drain result=played")
  drains_forced=$(count "event=tts-drain result=forced")
  drains_timeout=$(count "event=tts-drain result=timeout")
  startvals=$(grep -o "event=tts-track sr=[0-9]* capacityMs=[0-9]* startFrames=[0-9]*" "$LOG" | sed 's/.*startFrames=//' | sort -un | tr '\n' ' ')
  pip=$(count "event=pip enter ok=true")
  local retire_full retire_short
  retire_full=$(count "event=tts-retire .*full=true")
  retire_short=$(count "event=tts-retire .*full=false")
  mood=$(grep -c "event=\(er-glue\|tts-speak\).*<<[A-Z/]" "$LOG" || true)

  say "replies drained cleanly (played)" "$drains_played" \
      "$([ "$drains_played" -ge 1 ] && echo PASS || echo UNKNOWN)"
  say "replies needing the forced play-out" "$drains_forced" \
      "$([ "$drains_forced" -eq 0 ] && echo PASS || echo "CHECK (not a failure; see docs/REGRESSION.md)")"
  say "replies that timed out draining" "$drains_timeout" \
      "$([ "$drains_timeout" -eq 0 ] && echo PASS || echo FAIL)"
  say "streamed track start thresholds (frames)" "${startvals:-none}" \
      "$([ -n "${startvals:-}" ] && echo "CHECK <= rate/4" || echo UNKNOWN)"
  say "replies retired whole (path 5, full=true)" "$retire_full" \
      "$([ "$retire_full" -ge 1 ] && echo PASS || echo UNKNOWN)"
  say "replies retired short of their chunks" "$retire_short" \
      "$([ "$retire_short" -eq 0 ] && echo PASS || echo "FAIL unless a barge or stop cut it")"
  say "picture-in-picture entries during the smoke" "$pip" \
      "$([ "$pip" -eq 0 ] && echo PASS || echo "FAIL if Settings was opened")"
  say "mood markers reaching the voice" "$mood" \
      "$([ "$mood" -eq 0 ] && echo PASS || echo FAIL)"

  echo
  echo "== turns"
  grep -o "event=turn label=turn gen=[0-9]* outcome=[a-z-]*" "$LOG" \
    | sed 's/.*outcome=//' | sort | uniq -c | sed 's/^/   /' || echo "   none"
  echo
  echo "== the profile ask"
  grep -o "event=profiles-ask .*" "$LOG" | tail -3 | sed 's/^/   /' || echo "   none"
  echo
  echo "== what this run cannot see"
  echo "   - whether a reply was intelligible (Supertonic word loss, #559)"
  echo "   - whether a greeting landed before you spoke (the gate's feel)"
  echo "   - anything about a second handset (#208)"
}

case "${1:-}" in
  start) start ;;
  finish) finish ;;
  *) echo "usage: $0 start|finish"; exit 2 ;;
esac
