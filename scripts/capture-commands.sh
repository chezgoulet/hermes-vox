#!/usr/bin/env bash
# #226 — capture the commands screen on a device: both grounds, every group, every state.
#
# Real captures, not mocks: this drives the installed DEBUG build through `adb shell
# input` taps and swipes — the same events a finger delivers — and each PNG is the app's
# own frame buffer. Nothing about the screen is drawn by this script.
#
# The sheet is opened by the debug harness (`--ez sheet true`, inert in a release build;
# MainActivity.handleDebugHarness). Tapping `/` on the main screen opens the same screen.
#
# The four states come from the app's real behaviour, not from a harness flag:
#   running   /reconnect against a gateway that accepts the connection and never answers,
#             so the command really is still going (commands-slow-gateway.py over
#             `adb reverse`, against the app's own 20 s GatewayClient timeout)
#   queued    /clear tapped while that /reconnect is still running — inline commands run
#             one at a time, so the second row says so instead of being dropped
#   failed    the same /reconnect when its timeout expires
#   done      the queued /clear, which then runs and finishes (it touches nothing on the
#             gateway, so it is the harmless command)
#
# Why taps by coordinate and not by view id: `uiautomator dump` returns "null root node"
# while the being is animating, which is always. The sheet is a fixed, full-screen layout,
# so a row's position is a constant of the screen — the table below, in per-mille of the
# display height, measured from the first capture of this screen.
#
# Usage:
#   scripts/capture-commands.sh [outdir] [package] [dark|light|both]
#     outdir   default artifacts/226-commands
#     package  default com.hermesvox.shot — a side-by-side debug install. The phone's
#              installed com.hermesvox is the owner's app; a debug build with its own
#              application id (and its own FileProvider authority) leaves it untouched.
#              See artifacts/275-light-mode/README.md for the same arrangement.
#
# Requires: adb, one booted and UNLOCKED device, `cmd uimode night` support, python3 on
# the host, and the shot build installed + debuggable.
set -euo pipefail
export PATH="${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools:$PATH"

OUT="${1:-artifacts/226-commands}"
PKG="${2:-com.hermesvox.shot}"
WHICH="${3:-both}"
SERIAL="${DEVICE:-}"
A="adb ${SERIAL:+-s $SERIAL}"
PORT="${PORT:-8642}"

mkdir -p "$OUT"
command -v adb >/dev/null || { echo "capture: adb not found"; exit 2; }
[ "$($A shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] || { echo "capture: no booted device"; exit 2; }
$A shell pm list packages "$PKG" 2>/dev/null | grep -q "package:$PKG$" \
  || { echo "capture: $PKG is not installed"; exit 2; }
# The device must be past the lock screen: a screencap of the keyguard is a black frame
# with a PIN pad on it, and every shot below would be that instead of the app.
$A shell dumpsys window 2>/dev/null | grep -q 'isKeyguardShowing=true' \
  && { echo "capture: unlock the device first (the shots are taken through the app)"; exit 2; }
# A display that sleeps mid-pass yields black frames; the whole run is a few minutes.
$A shell settings put system screen_off_timeout 1800000 >/dev/null 2>&1 || true
$A shell svc power stayon true >/dev/null 2>&1 || true
# run-as only works once the app has run at least once (its data dir must exist).
$A shell run-as "$PKG" true >/dev/null 2>&1 \
  || $A shell am start -n "$PKG/com.hermesvox.MainActivity" >/dev/null 2>&1 || true

# ---- geometry: rows are constants of the screen, in per-mille of its height --------------
SIZE="$($A shell wm size | tr -d '\r' | sed -n 's/.*: \([0-9]*x[0-9]*\).*/\1/p')"
W="${SIZE%x*}"; H="${SIZE#*x}"
[ -n "$W" ] || { echo "capture: could not read the screen size"; exit 2; }
at() { echo "$(( W * $1 / 1000 )) $(( H * $2 / 1000 ))"; }
ROW_CLEAR=270; ROW_COMPRESS=380; ROW_RECONNECT=537; ROW_NEW=685; ROW_MODELS=795
tap_row() { $A shell input tap $(at 500 "$1"); sleep 1; }
swipe_up() { $A shell input swipe $(at 500 650) $(at 500 250) 300; sleep 1; }

# ---- the gateway that accepts and never answers -----------------------------------------
SLOW="$(cd "$(dirname "$0")" && pwd)/commands-slow-gateway.py"
slow_pid=""
stop_slow() { [ -n "$slow_pid" ] && kill "$slow_pid" 2>/dev/null || true; slow_pid=""; }
start_slow() {
  stop_slow
  PORT="$PORT" python3 "$SLOW" >/dev/null 2>&1 &
  slow_pid=$!
  sleep 0.5
}
trap 'stop_slow' EXIT
$A reverse "tcp:$PORT" "tcp:$PORT" >/dev/null

shot() { $A exec-out screencap -p > "$OUT/$1.png"; echo "  -> $OUT/$1.png"; }

seed() { # $1 = theme, $2 = gateway url
  cat > /tmp/hv-cmd-prefs.xml <<EOF
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
  <string name="url">$2</string>
  <string name="model">hermes-agent</string>
  <string name="key">shot-key</string>
  <string name="theme">$1</string>
  <boolean name="onboarding_skipped" value="true" />
  <boolean name="coach_marks_seen" value="true" />
</map>
EOF
  $A shell am force-stop "$PKG"
  $A push /tmp/hv-cmd-prefs.xml /data/local/tmp/hv.xml >/dev/null
  $A shell run-as "$PKG" mkdir -p shared_prefs
  $A shell run-as "$PKG" cp /data/local/tmp/hv.xml shared_prefs/hv.xml
}

# $1 = dark|light — the app has its own theme setting; the system is set to match so the
# status bar and the app agree, and the app's ground is what the shot shows.
ground() {
  if [ "$1" = "light" ]; then $A shell cmd uimode night no >/dev/null 2>&1 || true
  else $A shell cmd uimode night yes >/dev/null 2>&1 || true; fi
}
open_sheet() {
  # A sleeping display is a black frame: wake it first.
  $A shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  $A shell wm dismiss-keyguard >/dev/null 2>&1 || true
  # Close a sheet left open by the previous pass — show() is a no-op while one is up, so
  # the next call would otherwise leave the list where the last swipe put it.
  $A shell input keyevent KEYCODE_BACK >/dev/null 2>&1 || true
  sleep 1
  $A shell am start -n "$PKG/com.hermesvox.MainActivity" --ez sheet true >/dev/null
  # A cold start on a slow device takes seconds; the sheet is posted after the harness.
  sleep "${SETTLE:-8}"
}

run_ground() { # $1 = dark|light
  local theme="$1"
  echo "=== $theme ==="
  ground "$theme"
  start_slow
  # https, not http: the app refuses cleartext (res/xml/network_security_config.xml), so
  # an http:// endpoint fails instantly and there would be nothing to watch. The handshake
  # is what hangs, because the gateway accepts and then says nothing.
  seed "$theme" "https://127.0.0.1:$PORT"
  open_sheet
  shot "$theme-sheet"                 # header, search, CONVERSATION and CONNECTION
  swipe_up; shot "$theme-sheet-2"     # SESSION and DIAGNOSTICS
  swipe_up; shot "$theme-sheet-3"     # the last rows, whole

  open_sheet                          # back to the top for the states
  tap_row "$ROW_RECONNECT"
  shot "$theme-running"               # the row says "running…" while it really runs
  tap_row "$ROW_CLEAR"
  shot "$theme-queued"                # /reconnect running, /clear queued behind it
  sleep 2
  shot "$theme-still-working"         # past Operations.LONG_MS: "still working…", no number
  sleep 20                            # the gateway's own 20 s timeout expires
  shot "$theme-failed"                # the row says "failed"
  sleep 2
  shot "$theme-done"                  # the queued /clear ran afterwards and finished
}

case "$WHICH" in
  both) run_ground dark; run_ground light ;;
  dark|light) run_ground "$WHICH" ;;
  *) echo "capture: third argument must be dark, light or both"; exit 2 ;;
esac

stop_slow
echo "capture: done -> $OUT"
