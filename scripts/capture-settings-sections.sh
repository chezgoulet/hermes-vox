#!/usr/bin/env bash
# #224 — capture the four Settings sections, collapsed and expanded, light and dark.
#
# Real device captures, not mocks: this opens the installed DEBUG build's SettingsActivity
# at a named section (the `section` extra the screen already reads), taps the section's one
# disclosure with a fresh accessibility dump on every step, and screenshots the phone's
# own screen.
#
# Usage:
#   scripts/capture-settings-sections.sh [outdir] [package]
#     outdir   default artifacts/224-settings-split
#     package  default com.hermesvox.shot (a side-by-side debug install; see below)
#
# The shot build (local, never committed) differs from the tree in three ways only:
#   - applicationId com.hermesvox.shot and fileprovider authority
#     com.hermesvox.shot.fileprovider, so it can sit beside the release-signed nightly
#     (a debug APK cannot `install -r` over a release signature);
#   - SettingsActivity exported="true", so `am start ... --es section <id>` can open a
#     section directly instead of tapping through Main → Settings → section.
# Everything on screen is this tree's layout and code.
#
# Requires: adb, one booted device, the shot build installed and debuggable.
set -euo pipefail
export PATH="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools:$PATH"

OUT="${1:-artifacts/224-settings-split}"
PKG="${2:-com.hermesvox.shot}"
SETTLE="${SETTLE:-2}"

mkdir -p "$OUT"
command -v adb >/dev/null || { echo "capture: adb not found"; exit 2; }
[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] || { echo "capture: no booted device"; exit 2; }
# A freshly installed app has no data directory yet, and run-as needs one: open it once.
adb shell run-as "$PKG" true >/dev/null 2>&1 \
  || { adb shell am start -n "$PKG/com.hermesvox.SettingsActivity" >/dev/null; sleep 3; }
adb shell run-as "$PKG" true >/dev/null 2>&1 \
  || { echo "capture: $PKG is not installed/debuggable"; exit 2; }

seed() {
  cat > /tmp/hv-shot-prefs.xml <<'EOF'
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
  <string name="theme">system</string>
  <boolean name="onboarding_skipped" value="true" />
  <boolean name="coach_marks_seen" value="true" />
</map>
EOF
  adb push /tmp/hv-shot-prefs.xml /data/local/tmp/hv-shot-prefs.xml >/dev/null
  adb shell run-as "$PKG" mkdir -p shared_prefs
  adb shell run-as "$PKG" cp /data/local/tmp/hv-shot-prefs.xml shared_prefs/hv.xml
}

# A fresh hierarchy every time: a stale dump is a mis-tap.
dump() {
  rm -f /tmp/hv-u.xml
  local i=0
  while [ "$i" -lt 8 ]; do
    if adb shell uiautomator dump /sdcard/hv-u.xml >/dev/null 2>&1; then
      adb shell cat /sdcard/hv-u.xml > /tmp/hv-u.xml 2>/dev/null || true
      if [ -s /tmp/hv-u.xml ]; then
        # An overloaded emulator raises "System UI isn't responding"; tap Wait and
        # re-dump, so the shot is the app and not the dialog.
        if grep -q "isn&#39;t responding\|isn't responding" /tmp/hv-u.xml; then
          python3 - > /tmp/hv-wait.txt <<'PY'
import re
try:
    xml = open('/tmp/hv-u.xml').read()
except Exception:
    raise SystemExit
m = re.search(r'text="Wait"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
if m:
    a, b, c, d = map(int, m.groups())
    print(f"{(a + c) // 2} {(b + d) // 2}")
PY
          if [ -s /tmp/hv-wait.txt ]; then
            adb shell input tap $(cat /tmp/hv-wait.txt)
            sleep 3
            continue
          fi
        fi
        return 0
      fi
    fi
    sleep 1
    i=$((i + 1))
  done
  echo "capture: could not dump the view hierarchy" >&2
  return 1
}

# Resource id -> "left top right bottom", empty when it is not on screen.
bounds() {
  python3 - "$1" <<'PY'
import re, sys
k = sys.argv[1]
try:
    xml = open('/tmp/hv-u.xml').read()
except Exception:
    print(); raise SystemExit
m = re.search(rf'resource-id="[^"]*:id/{re.escape(k)}"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
print(" ".join(m.groups()) if m else "")
PY
}

# Tap a row, scrolling down until it is on screen.
tap_id() {
  local id="$1" tries=0 b l t x y
  while [ "$tries" -lt 8 ]; do
    dump
    b=$(bounds "$id")
    if [ -n "$b" ]; then
      set -- $b; l=$1; t=$2; x=$(((l + $3) / 2)); y=$(((t + $4) / 2))
      adb shell input tap "$x" "$y"
      sleep "$SETTLE"
      return 0
    fi
    adb shell input swipe 1000 1800 1000 1100 300
    sleep 1
    tries=$((tries + 1))
  done
  echo "capture: could not find $id" >&2
  return 1
}

# Bring a row near the top of the viewport so the panel it opens fills the screen.
# Swipes run down the card's right edge (x=1000): a swipe over a SeekBar would turn
# the slider instead of scrolling the page.
align_top() {
  local id="$1" tries=0 b t
  while [ "$tries" -lt 14 ]; do
    dump
    b=$(bounds "$id")
    if [ -n "$b" ]; then
      t=$(echo "$b" | cut -d' ' -f2)
      if [ "$t" -ge 0 ] && [ "$t" -le 800 ]; then
        return 0
      fi
      if [ "$t" -gt 800 ]; then
        adb shell input swipe 1000 1700 1000 1400 400
      else
        adb shell input swipe 1000 800 1000 1100 400
      fi
    else
      adb shell input swipe 1000 1700 1000 1300 400
    fi
    sleep 1
    tries=$((tries + 1))
  done
  echo "capture: could not align $id" >&2
  return 1
}

# A slow emulator can raise "<app> isn't responding" while the GL preview runs. The
# next dump would dismiss it, but the shot comes first: clear it, then shoot at once.
clear_anr() {
  local b
  dump || true
  b=$(python3 - <<'PY'
import re
try:
    xml = open('/tmp/hv-u.xml').read()
except Exception:
    raise SystemExit
m = re.search(r'text="Wait"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml)
if m:
    a, b, c, d = map(int, m.groups())
    print(f"{(a + c) // 2} {(b + d) // 2}")
PY
)
  if [ -n "$b" ]; then
    adb shell input tap $b
    sleep 2
  fi
}

shot() {
  clear_anr
  adb exec-out screencap -p > "$OUT/$1.png"
  echo "  -> $OUT/$1.png"
}

open_section() {
  local section="$1" tries=0
  while [ "$tries" -lt 3 ]; do
    adb shell am force-stop "$PKG"
    adb shell am start -n "$PKG/com.hermesvox.SettingsActivity" --es section "$section" >/dev/null
    sleep 3
    if adb shell dumpsys activity activities 2>/dev/null | grep -q 'topResumedActivity=.*com.hermesvox.SettingsActivity'; then
      return 0
    fi
    tries=$((tries + 1))
  done
  echo "capture: could not open section $section" >&2
  return 1
}

# Expand a section's one disclosure, then bring its first advanced row to the top so
# the panel — not the everyday rows above it — is what the shot proves.
expanded() {
  local disclosure="$1" first_advanced="$2" name="$3" mode="$4"
  tap_id "$disclosure"
  align_top "$first_advanced"
  shot "$name-$mode"
}

capture_mode() {
  local mode="$1"
  local night=no
  [ "$mode" = "dark" ] && night=yes
  adb shell cmd uimode night "$night" >/dev/null 2>&1 || true
  sleep 2

  # Hearing: everyday controls, then the timing panel behind one disclosure.
  open_section speech
  shot "hearing-collapsed-$mode"
  expanded row_mic_advanced row_mic_vad hearing-expanded "$mode"

  # Voice: no advanced rows, so the section is the whole surface.
  open_section tts
  shot "voice-$mode"

  # Presence: the switch, then how Presence behaves behind one disclosure.
  open_section presence
  shot "presence-collapsed-$mode"
  expanded row_presence_advanced row_er_semantic_barge presence-expanded "$mode"

  # Being: the live preview and everyday controls, then the look's fine panel.
  open_section visuals
  shot "being-collapsed-$mode"
  expanded row_being_advanced row_visual_energy being-expanded "$mode"
}

echo "capture: $(adb shell getprop ro.product.model | tr -d '\r') / $PKG"
seed
adb shell cmd statusbar collapse >/dev/null 2>&1 || true
capture_mode light
capture_mode dark
echo "capture: done — $(ls "$OUT"/*.png 2>/dev/null | wc -l) PNGs in $OUT"
