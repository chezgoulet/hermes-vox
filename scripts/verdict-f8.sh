#!/usr/bin/env bash
# F8 — the ten-utterance verdict script.
#
# The purpose is to stop "this build feels better" from being an impression. Ten fixed
# utterances, spoken in the same order, on the same device, against the same gateway, with
# the numbers recorded per utterance. Run it once per candidate build and compare rows, not
# feelings.
#
# What it records without a human judgement, straight from the app's own latency ledger
# (`event=lat ... metric=speech-to-audio p50=… p95=… n=…`) and logcat: the running
# speech-to-audio median and sample count after each turn, and peak PSS at the end.
# What it cannot measure: whether the words were right. That column is yours — which is
# why the script waits for you instead of guessing.
#
# Usage:
#   scripts/verdict-f8.sh <build-label>        # e.g. verdict-f8.sh 0.9.1-nightly-f8dca26
#   ADB=/path/to/adb SERIAL=<serial> scripts/verdict-f8.sh <label>
#
# Output: docs/verdicts/<label>.md and docs/verdicts/<label>.json
set -u

ADB="${ADB:-adb}"
SERIAL="${SERIAL:-2A091FDH200AJ1}"
PKG="${PKG:-com.hermesvox}"
LABEL="${1:-}"

if [ -z "$LABEL" ]; then
  echo "usage: $0 <build-label>" >&2
  exit 2
fi

D="$ADB -s $SERIAL"
OUT_DIR="docs/verdicts"
mkdir -p "$OUT_DIR"
MD="$OUT_DIR/$LABEL.md"
JSON="$OUT_DIR/$LABEL.json"
RAW="$(mktemp -t verdict-f8-XXXXXX.log)"

UTTERANCES=(
  "What's on my calendar today?"
  "Decline the eleven o'clock and tell them I'm travelling."
  "Draft an email to Sam about the dock delivery, keep it short."
  "Change 'Friday' to 'Tuesday' in that draft."
  "Send it, then block an hour for the drive."
  "What are the trail conditions at the lodge right now?"
  "How many unread messages do I have?"
  "Cancel that — stop, I changed my mind."
  "Read me the last three lines of what you just wrote."
  "Do the thing that failed a minute ago, but slower."
)
WHAT_IT_TESTS=(
  "a plain read, one tool"
  "an instruction with a time and a reason (approval is likely)"
  "composition with a length constraint"
  "an edit to something already spoken"
  "two actions in one breath, in order"
  "a live lookup whose answer is a number and a condition"
  "a countable answer — the grounded-number path"
  "interruption mid-reply"
  "reading content back, verbatim"
  "recovery after a failure"
)

# The last latency block the app printed, as `p50 p95 n` for speech-to-audio.
s2a() {
  grep -a "metric=speech-to-audio" "$RAW" | tail -1 |
    sed -n 's/.*p50=\([0-9.]*\).*p95=\([0-9.]*\).*n=\([0-9]*\).*/\1|\2|\3/p'
}

echo "F8 verdict run — $LABEL"
echo "device $SERIAL, package $PKG  (logcat → $RAW)"
$D logcat -c >/dev/null 2>&1
$D logcat -v time > "$RAW" 2>&1 &
LOGCAT_PID=$!
trap 'kill "$LOGCAT_PID" 2>/dev/null' EXIT

rows=""
for i in "${!UTTERANCES[@]}"; do
  n=$((i + 1))
  echo
  echo "── $n/10 — ${WHAT_IT_TESTS[$i]}"
  echo "   say: \"${UTTERANCES[$i]}\""
  printf "   Enter when the reply has finished, or 'x' to mark a break: "
  read -r line
  note=""
  if [ "$line" = "x" ]; then
    printf "   what broke: "
    read -r note
  fi
  sleep 1
  lat=$(s2a)
  p50=$(echo "$lat" | cut -d'|' -f1); p95=$(echo "$lat" | cut -d'|' -f2); cnt=$(echo "$lat" | cut -d'|' -f3)
  rows="$rows$n|${UTTERANCES[$i]}|${WHAT_IT_TESTS[$i]}|${p50:-—}|${p95:-—}|${cnt:-—}|${note:-}|"$'\n'
done

pss=$($D shell dumpsys meminfo "$PKG" 2>/dev/null | awk '/TOTAL PSS/{print $3; exit}')
version=$($D shell dumpsys package "$PKG" | grep -m1 versionName | tr -d '\r' | cut -d= -f2)

{
  echo "# F8 verdict — $LABEL"
  echo
  echo "Build: \`$version\` · device $SERIAL · PSS at end: ${pss:-—} KB"
  echo "Run at: $(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  echo
  echo "Ten fixed utterances, same order every time. \`p50\` and \`p95\` are the app's own"
  echo "speech-to-audio ledger at that point in the run — a running figure, not a per-turn"
  echo "stopwatch, which is why \`n\` is recorded beside it."
  echo
  echo "| # | utterance | what it tests | p50 | p95 | n | words right? | break |"
  echo "|---|---|---|---|---|---|---|---|"
  echo "$rows" | while IFS='|' read -r a b c d e f g; do
    [ -n "$a" ] || continue
    echo "| $a | $b | $c | $d | $e | $f | ☐ | ${g:-—} |"
  done
  echo
  echo "## The comparison that matters"
  echo
  echo "Put the previous build's p50 column beside this one. A build is better when the median is"
  echo "lower and the ☐ column has no new crosses — not when it feels smoother."
} > "$MD"

{
  echo "{\"label\":\"$LABEL\",\"build\":\"$version\",\"device\":\"$SERIAL\",\"pss_kb\":${pss:-0},\"rows\":["
  first=1
  echo "$rows" | while IFS='|' read -r a b c d e f g; do
    [ -n "$a" ] || continue
    [ $first -eq 0 ] && printf ","
    first=0
    printf '{"n":%s,"utterance":"%s","tests":"%s","p50":"%s","p95":"%s","samples":"%s","break":"%s"}' \
      "$a" "$b" "$c" "$d" "$e" "$f" "$g"
  done
  echo "]}"
} > "$JSON"

echo
echo "wrote $MD"
echo "wrote $JSON"
