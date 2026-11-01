#!/usr/bin/env bash
# #225 — the live preview costs nothing after you leave it.
#
# "Cheap enough not to make Settings stutter" and "no dangling renderer" are measurements,
# not adjectives. This script takes them in the app's own process, walking the screens the
# way a user walks them (no force-stop between states, so every row describes the SAME
# process — otherwise the numbers are about cold-start warmup, not about the preview):
#
#   settings-home      Settings, home list — no being on screen, no render thread
#   preview-running    Settings -> Being, the live preview drawing
#   after-back         back to the Settings home list (preview released)
#   after-onpause      Settings finished: onPause ran, the process is in the background
#
# Each row is TOTAL PSS from `dumpsys meminfo`, sampled three times after a settle; the
# frame counter `dumpsys gfxinfo` reports (a renderer that stopped draws no frames) and the
# power service's own wake-lock list (a preview that "costs nothing" must not hold the CPU).
#
# Usage: scripts/measure-225-preview-pss.sh [package] [ground: dark|light]
set -euo pipefail
export PATH="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools:$PATH"

PKG="${1:-com.hermesvox.shot}"
GROUND="${2:-dark}"
SETTLE="${SETTLE:-10}"

command -v adb >/dev/null || { echo "measure: adb not found"; exit 2; }
[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] || { echo "measure: no booted device"; exit 2; }
# A freshly installed app has no data directory yet, and run-as needs one: open it once.
adb shell run-as "$PKG" true >/dev/null 2>&1 \
  || { adb shell am start -n "$PKG/com.hermesvox.MainActivity" >/dev/null; sleep 3; }
adb shell run-as "$PKG" true >/dev/null 2>&1 || { echo "measure: $PKG is not installed/debuggable"; exit 2; }

seed_defaults() {
  cat > /tmp/hv-225-pss.xml <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
  <string name="theme">$GROUND</string>
  <boolean name="onboarding_skipped" value="true" />
  <boolean name="coach_marks_seen" value="true" />
  <boolean name="presence_enabled" value="false" />
  <string name="visual_category">lumen</string>
  <boolean name="visual_cycle_all" value="false" />
  <float name="visual_density" value="1.0" />
  <float name="visual_speed" value="1.0" />
  <float name="visual_trail" value="0.0" />
  <float name="visual_hue" value="0.0" />
  <float name="visual_energy" value="1.0" />
  <float name="visual_glow" value="1.0" />
</map>
EOF
  adb push /tmp/hv-225-pss.xml /data/local/tmp/hv-225-pss.xml >/dev/null
  adb shell run-as "$PKG" mkdir -p shared_prefs
  adb shell run-as "$PKG" cp /data/local/tmp/hv-225-pss.xml shared_prefs/hv.xml
}

pss() { adb shell dumpsys meminfo "$PKG" 2>/dev/null | awk '/TOTAL PSS/ {print $3; exit}'; }
frames() { adb shell dumpsys gfxinfo "$PKG" 2>/dev/null | awk '/Total frames rendered/ {print $4; exit}'; }
uid() { adb shell pm list packages -U "$PKG" 2>/dev/null | sed -n 's/.*uid:\([0-9]*\).*/\1/p' | head -1; }
# A preview that "costs nothing when not in use" must not hold the CPU awake either: no
# wake lock naming the app or its uid, in the power service's own list.
wakelocks() {
  local pat="$PKG"
  [ -n "${APP_UID:-}" ] && pat="$pat|uid=$APP_UID"
  adb shell dumpsys power 2>/dev/null | sed -n '/Wake Locks:/,/^$/p' | grep -cE "$pat" || true
}
median() { printf '%s\n' "$@" | sort -n | sed -n '2p'; }
wait_pid() {
  local i
  for i in $(seq 1 15); do
    [ -n "$(adb shell pidof "$PKG" 2>/dev/null | tr -d '\r')" ] && return 0
    sleep 2
  done
  echo "measure: the process never came up" >&2
  return 1
}

sample() { # label -> "label kB frames wakes"
  local label="$1" vals=() f p w
  sleep "$SETTLE"
  for _ in 1 2 3; do vals+=("$(pss)"); sleep 2; done
  p=$(median "${vals[@]}")
  f=$(frames)
  w=$(wakelocks)
  printf '| %s | %s | %s | %s |\n' "$label" "${p:-?}" "${f:-?}" "${w:-?}"
  LAST_PSS="$p"; LAST_FRAMES="$f"; LAST_WAKES="$w"
}

adb shell cmd uimode night "$([ "$GROUND" = dark ] && echo yes || echo no)" >/dev/null 2>&1 || true
seed_defaults
adb shell am force-stop "$PKG"

APP_UID="$(uid)"
echo
echo "device: $(adb shell getprop ro.product.model | tr -d '\r') · $(adb shell getprop ro.build.fingerprint | tr -d '\r')"
echo "package: $PKG · uid: ${APP_UID:-unknown} · ground: $GROUND · settle: ${SETTLE}s, median of 3"
echo
echo '| state | TOTAL PSS (kB) | frames rendered (cumulative) | wake locks held |'
echo '|---|---|---|---|'

# 1. Settings home list: the process is up, the Being section was never opened.
adb shell am start -n "$PKG/com.hermesvox.MainActivity" --es settings home >/dev/null
wait_pid
sample "settings-home"
home_pss="${LAST_PSS:-0}"

# 2. The Being section, in the SAME process: the preview is drawing.
adb shell am start -n "$PKG/com.hermesvox.MainActivity" \
  --es settings visuals --es being_advanced true --es preview_state thinking >/dev/null
wait_pid
sample "preview-running"
run_pss="${LAST_PSS:-0}"; run_frames="${LAST_FRAMES:-0}"

# 3. Back to the Settings home list: the section is hidden, so the preview is released.
adb shell input keyevent KEYCODE_BACK >/dev/null
sample "after-back"
back_pss="${LAST_PSS:-0}"; back_frames="${LAST_FRAMES:-0}"

# 4. Settings finished: onPause ran and the process is in the background.
adb shell input keyevent KEYCODE_BACK >/dev/null
sample "after-onpause"
pause_pss="${LAST_PSS:-0}"; pause_frames="${LAST_FRAMES:-0}"; pause_wakes="${LAST_WAKES:-0}"

# The renderer stopped: no frames between two reads 5 s apart.
sleep 5
still="$(frames)"
echo
echo "frames drawn in the 5 s after onPause: $(( ${still:-0} - ${pause_frames:-0} ))"
echo "PSS while previewing: $run_pss kB · after leaving: $pause_pss kB · home band: $home_pss kB · back band: $back_pss kB"
echo "wake locks after onPause: $pause_wakes (0 = the app holds none)"
echo "delta preview-vs-home: $(( run_pss - home_pss )) kB · delta after-vs-home: $(( pause_pss - home_pss )) kB"
