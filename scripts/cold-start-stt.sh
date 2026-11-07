#!/usr/bin/env bash
# cold-start-stt.sh — does the listening eye appear before speech recognition can hear?
#
# The report: "Cold startup, wait for the listening eye, begin speaking — the first attempt
# produces nothing and the second works." The hypothesis under test is specific and can be
# wrong: the eye says *listening* before the recogniser is warm, so the first utterance's
# audio is discarded instead of queued. This script measures the moments that decide it, one
# cold start per row, and prints what the numbers mean — including "the hypothesis is wrong".
#
# Per run (N runs, default 5):
#   am force-stop  ->  am start into a call  ->  wait for the eye
# and the four moments the report names, all read from the app's own logcat lines:
#
#   process start   /proc/<pid>/stat starttime + btime (10 ms resolution), not "when am start
#                   returned" — the launch is asynchronous and that would flatter the result
#   eye shown       `event=cold-stt stage=eye`   (the state the eye is drawn from)
#   recogniser ready `event=cold-stt stage=ready` (the STT leg answered after its warm decode)
#   first partial   `event=cold-stt stage=first-partial` (a hypothesis the recogniser produced)
#
# plus the two capture-side moments that separate "the model was cold" from "the microphone
# was not delivering audio": `stage=mic-recording` and `stage=first-audio` (with the peak
# sample of the first read — an all-zero peak is a deaf mic, not a quiet room).
#
# A run LOSES ITS FIRST UTTERANCE when either is true at the moment the eye appeared:
#   * the recogniser had not reported ready          (the reported hypothesis)
#   * the microphone had not delivered a sample      (the same symptom, a different cause)
# The summary counts both, and says which one fired, so a fix aimed at the wrong one is
# visible instead of plausible.
#
# Usage:
#   scripts/cold-start-stt.sh [N]            # measure; N defaults to 5
#   scripts/cold-start-stt.sh --speak [N]    # also prompt a human to speak at the eye, and
#                                            # time the first partial (the honest utterance)
#   scripts/cold-start-stt.sh --assert [N]   # measurement + a nonzero exit if ANY run showed
#                                            # the eye before the recogniser was ready or
#                                            # before the mic delivered audio. This is the pin
#                                            # a fix must keep green.
#
# A release/nightly install has no `event=cold-stt` markers (they are app-side, added with
# this script), so the older lines are used when the new ones are absent: `event=cue
# cue=listening` is the eye (it fires on the same state edge), `<model> loaded:` is ready,
# and `mic: source=` is the recorder being built. Those fallbacks cannot see the first read
# or the first partial; the script says which set it used in every row.
#
# Measuring a build from THIS tree needs an install the script can force-stop. The project's
# release install is signed with a key this host does not hold, so a debug APK cannot replace
# it (DEVICE_VERIFY.md); install a bench copy BESIDE it, as scripts/presence-cost.sh does, and
# point PKG at it:
#   (cd android && ./gradlew assembleDebug -Phermesvox.benchSuffix=coldstt)
#   adb install -r android/app/build/outputs/apk/debug/hermes-vox-*.apk
#   adb shell pm grant com.hermesvox.coldstt android.permission.RECORD_AUDIO
#   # its filesDir needs models/whisper-base/{encoder,decoder}.int8.onnx + tokens.txt and
#   # models/silero-vad/silero_vad.onnx (adb push + run-as copy), plus `url`/`key` prefs
#   # (`adb shell run-as com.hermesvox.coldstt`, shared_prefs/hv.xml) so the app does not route
#   # to onboarding. The gateway does not have to be reachable: partials and markers are local.
#   PKG=com.hermesvox.coldstt scripts/cold-start-stt.sh 8
#
# The phone must be able to start a call. With no gateway configured the app opens
# onboarding instead and there is no eye at all — the script says so rather than timing
# nothing. Env: ADB, SERIAL, PKG (com.hermesvox), TAG (HermesVox), EYE_TIMEOUT (90),
# PARTIAL_TIMEOUT (8), CALL_INTENT (android.intent.action.ASSIST), CALL_COMPONENT (resolved
# from the phone, so a bench build with its own package id works).
set -uo pipefail

ADB="${ADB:-${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb}"
SERIAL="${SERIAL:-}"
PKG="${PKG:-com.hermesvox}"
TAG="${TAG:-HermesVox}"
EYE_TIMEOUT="${EYE_TIMEOUT:-90}"
PARTIAL_TIMEOUT="${PARTIAL_TIMEOUT:-8}"
[ -n "$SERIAL" ] && ADB="$ADB -s $SERIAL"

say()  { printf '%s\n' "$*"; }
die()  { printf 'cold-start-stt: %s\n' "$*" >&2; exit 2; }
sh_cmd() { $ADB shell "$@" 2>/dev/null | tr -d '\r'; }

# --- arguments -------------------------------------------------------------------------
ASSERT=0; SPEAK=0; RUNS=""
for a in "$@"; do
  case "$a" in
    --assert) ASSERT=1 ;;
    --speak)  SPEAK=1 ;;
    ''|*[!0-9]*) die "unknown argument: $a (usage: $0 [--speak] [--assert] [N])" ;;
    *) RUNS="$a" ;;
  esac
done
RUNS="${RUNS:-5}"

[ -x "${ADB%% *}" ] || die "no adb at ${ADB%% *} (set ADB)"
$ADB get-state >/dev/null 2>&1 || die "no device (adb get-state failed)"

# The call entry point: the assistant route cold-starts the app straight into a call, which
# is the reported sequence (the tap on Call is the only step it skips). The component is
# resolved from the phone rather than assumed: a bench build installed with
# -Phermesvox.benchSuffix=… has package id com.hermesvox.<suffix> while its Activity class is
# still com.hermesvox.AssistActivity, so "$PKG/.AssistActivity" would name a class that does
# not exist and every run would fail with "Activity class does not exist".
CALL_INTENT="${CALL_INTENT:-android.intent.action.ASSIST}"
CALL_COMPONENT="${CALL_COMPONENT:-}"
if [ -z "$CALL_COMPONENT" ]; then
  CALL_COMPONENT="$(sh_cmd cmd package query-activities --brief -a "$CALL_INTENT" -c android.intent.category.DEFAULT     | tr -d '\r' | awk -v p="$PKG/" 'index($1, p) == 1 { print $1; exit }')"
  [ -n "$CALL_COMPONENT" ] || die "$PKG does not handle $CALL_INTENT (installed? exported?)"
fi

# --- one run ---------------------------------------------------------------------------
# Epoch seconds with milliseconds from `date`, so every offset is on the host's clock and
# the /proc start time is converted onto it.
now_ms() { date +%s%3N; }

# The process' real start, in epoch ms, from the kernel: btime + starttime/CLK_TCK.
proc_start_ms() {
  local pid="$1" btime ticks hz
  btime="$(sh_cmd "awk '/^btime/{print \$2}' /proc/stat")"
  ticks="$(sh_cmd "awk '{print \$22}' /proc/$pid/stat")"
  hz="$(sh_cmd getconf CLK_TCK 2>/dev/null)"
  case "$hz" in ''|*[!0-9]*) hz=100 ;; esac   # Android's USER_HZ; only used when getconf is absent
  case "$btime$ticks" in ''|*[!0-9]*) echo "" ; return ;; esac
  echo $(( btime * 1000 + ticks * 1000 / hz ))
}

# Every cold-stt marker from the current log buffer, oldest first: "epoch_ms key rest".
marks() {
  $ADB logcat -d -v epoch -s "$TAG" 2>/dev/null | tr -d '\r' |
  awk '
    /event=cold-stt/ {
      ts = $1;                       # epoch seconds with 3 decimals
      line = $0
      key = ""
      if (line ~ /stage=ready/)        key = "ready"
      else if (line ~ /stage=eye/)     key = "eye"
      else if (line ~ /stage=mic-recording/) key = "mic"
      else if (line ~ /stage=first-audio/)   key = "audio"
      else if (line ~ /stage=read-fail/)     key = "readfail"
      else if (line ~ /stage=first-speech/)  key = "speech"
      else if (line ~ /stage=first-partial/) key = "partial"
      else if (line ~ /stage=level/)         key = "level"
      if (key != "") printf "%d %s %s\n", ts * 1000, key, line
    }
  '
}

# The release install's older lines, for an app without the cold-stt markers.
legacy_marks() {
  $ADB logcat -d -v epoch -s "$TAG" 2>/dev/null | tr -d '\r' |
  awk '
    {
      ts = $1; line = $0; key = ""
      if (line ~ /event=cue cue=listening/)          key = "eye"
      else if (line ~ /(Whisper|Parakeet|Nemotron).* loaded: /) key = "ready"
      else if (line ~ /^.*mic: source=/)             key = "mic"
      else if (line ~ /realtime: speech=/)           key = "partial"
      if (key != "") printf "%d %s %s\n", ts * 1000, key, line
    }
  '
}

# First timestamp for a key, in epoch ms ("" when absent).
pick() { awk -v k="$2" '$2==k {print $1; exit}' <<<"$1"; }

# Seconds between two epoch-ms values, "+x.xx", or "n/a".
gap() {
  if [ -z "$1" ] || [ -z "$2" ]; then echo "n/a"; return; fi
  awk -v a="$2" -v b="$1" 'BEGIN{ d=(a-b)/1000; printf "%+.2f", d }'
}

run_one() {
  local i="$1" t_proc pid deadline log m_eye m_ready m_mic m_audio m_partial m_speech m_readfail
  local mode="cold-stt"

  $ADB shell am force-stop "$PKG" >/dev/null 2>&1
  $ADB logcat -c >/dev/null 2>&1
  sleep 1                                    # let the force-stop settle: a kill is not a cold cache

  $ADB shell am start -a "$CALL_INTENT" -n "$CALL_COMPONENT" >/dev/null 2>&1 ||
    die "could not start $CALL_COMPONENT (installed?)"

  # The process, then its kernel start time.
  deadline=$(( $(date +%s) + 20 )); pid=""
  while [ "$(date +%s)" -lt "$deadline" ]; do
    pid="$(sh_cmd pidof "$PKG")"; pid="${pid%% *}"
    [ -n "$pid" ] && break
    sleep 0.1
  done
  [ -n "$pid" ] || die "run $i: $PKG never started"
  t_proc="$(proc_start_ms "$pid")"
  [ -n "$t_proc" ] || t_proc="$(now_ms)"

  # Wait for the eye; then give the utterance a fair window to reach a partial.
  deadline=$(( $(date +%s) + EYE_TIMEOUT ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    log="$(marks)"
    [ -n "$(pick "$log" eye)" ] && break
    sleep 0.1
  done
  if [ -z "$(pick "$log" eye)" ]; then
    log="$(legacy_marks)"
    mode="legacy"
    [ -n "$(pick "$log" eye)" ] || { say "run $i: no listening eye within ${EYE_TIMEOUT}s (gateway not configured? onboarding?)"; return 1; }
  fi

  if [ "$SPEAK" = "1" ] && [ -t 0 ]; then
    say "run $i: the eye is up — say 'the quick brown fox jumps over the lazy dog' now"
  fi
  deadline=$(( $(date +%s) + PARTIAL_TIMEOUT ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    log="$(marks)"
    [ "$mode" = "legacy" ] && log="$(legacy_marks)"
    [ -n "$(pick "$log" partial)" ] && break
    sleep 0.1
  done
  log="$(marks)"; [ "$mode" = "legacy" ] && log="$(legacy_marks)"
  sleep 0.2   # let a marker that landed with the last poll be flushed
  log="$( [ "$mode" = "legacy" ] && legacy_marks || marks )"

  m_eye="$(pick "$log" eye)"; m_ready="$(pick "$log" ready)"; m_mic="$(pick "$log" mic)"
  m_audio="$(pick "$log" audio)"; m_partial="$(pick "$log" partial)"
  m_speech="$(pick "$log" speech)"; m_readfail="$(pick "$log" readfail)"
  # The mic's own level for the first ~4 s (peak PCM of each 500 ms sample): a ramp that
  # starts at 0 is a deaf capture window, not a quiet room.
  m_levels="$(awk '$2=="level" { t=""; p=""; for (i=1;i<=NF;i++) { if ($i ~ /^t=/) t=substr($i,3); if ($i ~ /^peak=/) p=substr($i,6) } printf "%s:%s ", t, p }' <<<"$log")"

  # What the run says about the hypothesis, in the order it can hurt.
  local lost="no" why=""
  if [ -n "$m_ready" ] && [ -n "$m_eye" ] && [ "$m_eye" -lt "$m_ready" ]; then
    lost="yes"; why="eye-before-ready"
  fi
  if [ -n "$m_audio" ] && [ -n "$m_eye" ] && [ "$m_eye" -lt "$m_audio" ]; then
    lost="yes"; why="${why:+$why,}eye-before-audio"
  elif [ -z "$m_audio" ] && [ "$mode" != "legacy" ]; then
    lost="yes"; why="${why:+$why,}no-audio"
  fi
  [ -n "$m_readfail" ] && [ -z "$m_audio" ] && why="${why:+$why,}deaf-reads"

  printf 'run %-2s [%s] proc=%s eye=%-7s ready=%-7s mic=%-7s audio=%-7s partial=%-7s | eye-ready=%-7s eye-audio=%-7s lost-first-utterance=%s%s\n' \
    "$i" "$mode" "+0.00" "$(gap "$t_proc" "$m_eye")" "$(gap "$t_proc" "$m_ready")" \
    "$(gap "$t_proc" "$m_mic")" "$(gap "$t_proc" "$m_audio")" "$(gap "$t_proc" "$m_partial")" \
    "$(gap "$m_ready" "$m_eye")" "$(gap "$m_audio" "$m_eye")" "${lost:-no}" "${why:+ ($why)}"
  [ -n "$m_levels" ] && printf '        mic levels (ms:peak PCM) %s\n' "$m_levels"

  # Rows are collected for the summary, not re-derived from the printed text.
  ROWS+=("$lost|$(gap "$m_ready" "$m_eye")|$(gap "$m_audio" "$m_eye")|$why|$mode")
  return 0
}

ROWS=()
started=0; failed=0
say "cold-start-stt: $RUNS cold starts of $PKG on $($ADB shell getprop ro.product.model | tr -d '\r') / Android $($ADB shell getprop ro.build.version.release | tr -d '\r')"
say "  timestamps are relative to /proc/<pid> start; a marker's own key names the moment"
say ""
for (( i=1; i<=RUNS; i++ )); do
  started=$((started+1))
  run_one "$i" || failed=$((failed+1))
done

# --- summary ---------------------------------------------------------------------------
say ""
lost=0; eye_before_ready=0; eye_before_audio=0; no_audio=0; scored=0
ER_GAPS=()
for row in "${ROWS[@]:-}"; do
  [ -z "$row" ] && continue
  scored=$((scored+1))
  IFS='|' read -r l er ea why mode <<<"$row"
  case "$l" in yes) lost=$((lost+1)) ;; esac
  case "$why" in *eye-before-ready*) eye_before_ready=$((eye_before_ready+1)); ER_GAPS+=("$er") ;; esac
  case "$why" in *eye-before-audio*) eye_before_audio=$((eye_before_audio+1)) ;; esac
  case "$why" in *no-audio*|*deaf-reads*) no_audio=$((no_audio+1)) ;; esac
done

say "summary: $scored run(s) measured, $failed did not reach the eye"
say "  lost the first utterance: $lost/$scored"
say "    eye before the recogniser was ready: $eye_before_ready/$scored   (the reported hypothesis)"
say "    eye before the mic delivered audio:  $eye_before_audio/$scored   (a microphone race, not a model one)"
say "    no audio at all / failed reads:      $no_audio/$scored"

# The gap the report asks for: "eye shown" against "recogniser ready" on the runs that lost it.
if [ "$eye_before_ready" -gt 0 ]; then
  say "  eye-ready gap on the runs that lost the first utterance (eye BEFORE ready, hence negative):"
  for g in "${ER_GAPS[@]}"; do say "    $g s"; done
else
  say "  eye-ready gap: no run showed the eye before the recogniser"
fi

# The verdict, stated so it can be wrong.
if [ "$eye_before_ready" -gt 0 ]; then
  say "verdict: the hypothesis HELD — the eye said listening before the recogniser was warm."
elif [ "$eye_before_audio" -gt 0 ] || [ "$no_audio" -gt 0 ]; then
  say "verdict: the hypothesis was WRONG on this device — the recogniser was warm before the eye,"
  say "         but the eye still said listening before (or without) the microphone delivering audio."
else
  say "verdict: the hypothesis was NOT reproduced in $scored run(s) — no run showed the eye before"
  say "         either the recogniser or the microphone was ready. A first-utterance loss here"
  say "         has a cause this bench does not measure (say so in the report rather than guess)."
fi

if [ "$ASSERT" = "1" ]; then
  if [ "$eye_before_ready" -gt 0 ] || [ "$eye_before_audio" -gt 0 ] || [ "$no_audio" -gt 0 ]; then
    say "assert: FAILED — a fresh start showed listening before the recogniser/mic could hear"
    exit 1
  fi
  [ "$scored" -gt 0 ] || { say "assert: FAILED — no run reached the eye, nothing was proven"; exit 1; }
  say "assert: PASSED — every run showed the eye only after the recogniser and the mic were ready"
fi
