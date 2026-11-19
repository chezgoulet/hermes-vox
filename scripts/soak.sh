#!/usr/bin/env bash
# soak.sh — sample a live call for thirty minutes, then judge it.
#
# `docs/BUDGETS-0.9.1.md` sets four budgets for a live call on the 8 GB class: battery no worse
# than 4 %/h, no thermal-state change, peak PSS at or under 1.5 GB, and no memory growth over
# the window. F5 is the gate that measures them; until now F5 existed only as a line in a
# checklist with no instrument behind it, which is how 0.9.1 shipped with a green build gate and
# two device defects.
#
# What this script does: one sample every thirty seconds — PSS, MemAvailable, thermal status,
# battery level and whether the phone is charging — written as JSONL. Then it runs
# `scripts/soak-report.py`, which owns the verdict and the exit code.
#
# What it does NOT do: start the call. A call needs a person to tap it and, for most of F5's
# value, to say something. The script detects whether a call happened from the app's own
# `event=alpha-gate` line and reports *no live call in the window* rather than measuring an idle
# phone and calling it a soak.
#
# Usage:
#   scripts/soak.sh <label>                  # 30 minutes, the F5 window
#   scripts/soak.sh <label> 5                # five minutes (a smoke; the report will say UNKNOWN)
#   ADB=/path/to/adb SERIAL=<serial> scripts/soak.sh <label>
#
# Output: docs/soaks/<label>.jsonl, and the report's verdict on stdout. Exit code is the
# report's: 0 pass, 1 fail, 2 usage, 3 unknown.
set -u

ADB="${ADB:-adb}"
SERIAL="${SERIAL:-2A091FDH200AJ1}"
PKG="${PKG:-com.hermesvox}"
LABEL="${1:-}"
MINUTES="${2:-30}"
INTERVAL="${INTERVAL:-30}"

if [ -z "$LABEL" ]; then
  echo "usage: $0 <label> [minutes]   (default 30 minutes, one sample every ${INTERVAL}s)" >&2
  exit 2
fi

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT_DIR="$ROOT/docs/soaks"
mkdir -p "$OUT_DIR"
OUT="$OUT_DIR/$LABEL.jsonl"
D="$ADB -s $SERIAL"

if ! $D get-state >/dev/null 2>&1; then
  echo "soak: no device $SERIAL — connect it, or set SERIAL/ADB" >&2
  exit 2
fi

# The build on the phone, read from the phone. A soak whose label cannot be tied to a build is a
# number with no owner.
VERSION="$($D shell dumpsys package "$PKG" 2>/dev/null | sed -n 's/.*versionName=\([^ ]*\).*/\1/p' | head -n1)"
CODE="$($D shell dumpsys package "$PKG" 2>/dev/null | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -n1)"
MODEL="$($D shell getprop ro.product.model 2>/dev/null | tr -d '\r')"

SAMPLES=$(( MINUTES * 60 / INTERVAL ))
echo "soak: $LABEL on $MODEL, $PKG $VERSION (versionCode $CODE), $MINUTES min, $SAMPLES samples"
echo "soak: open a call now and talk to it. Sampling every ${INTERVAL}s into $OUT"

{
  printf '{"soak":{"label":"%s","minutes":%s,"serial":"%s","model":"%s","version":"%s","versionCode":"%s","started":"%s","interval_s":%s}}\n' \
    "$LABEL" "$MINUTES" "$SERIAL" "$MODEL" "$VERSION" "$CODE" "$(date -Iseconds)" "$INTERVAL"
} > "$OUT"

call_seen=0
for i in $(seq 0 "$SAMPLES"); do
  t=$(( i * INTERVAL ))

  pss="$($D shell dumpsys meminfo "$PKG" 2>/dev/null | awk '/TOTAL PSS/ {print $3; exit}')"
  avail="$($D shell cat /proc/meminfo 2>/dev/null | awk '/MemAvailable/ {print $2; exit}')"
  therm_num="$($D shell dumpsys thermalservice 2>/dev/null | awk '/Thermal Status/ {print $3; exit}')"
  batt="$($D shell dumpsys battery 2>/dev/null | awk '/ level:/ {print $2; exit}')"
  plugged="$($D shell dumpsys battery 2>/dev/null | grep -cE '^(AC|USB|Wireless) powered: true')"

  case "${therm_num:-}" in
    0) therm="none" ;;
    1) therm="light" ;;
    2) therm="moderate" ;;
    3) therm="severe" ;;
    4) therm="critical" ;;
    5) therm="emergency" ;;
    6) therm="shutdown" ;;
    *) therm="" ;;
  esac

  charging=false
  [ "${plugged:-0}" -gt 0 ] && charging=true

  printf '{"t":%s,"pss_kb":%s,"avail_kb":%s,"thermal":"%s","battery":%s,"charging":%s}\n' \
    "$t" "${pss:-null}" "${avail:-null}" "$therm" "${batt:-null}" "$charging" >> "$OUT"

  # The call marker: the app writes event=alpha-gate at the start of every call, so this is the
  # app's own statement that a call opened, not an inference from a process existing.
  if [ "$call_seen" -eq 0 ] && $D logcat -d -s VoxLog:* 2>/dev/null | grep -q "event=alpha-gate"; then
    call_seen=1
    printf '{"call":{"detected":true,"opened_iso":"%s"}}\n' "$(date -Iseconds)" >> "$OUT"
  fi

  left=$(( SAMPLES - i ))
  printf '  [%s/%s] %ss  pss=%s kB  avail=%s kB  thermal=%s  batt=%s%s\n' \
    "$i" "$SAMPLES" "$t" "${pss:-?}" "${avail:-?}" "${therm:-?}" "${batt:-?}" \
    "$([ "$charging" = true ] && echo ' (charging)')"

  [ "$left" -gt 0 ] && sleep "$INTERVAL"
done

if [ "$call_seen" -eq 0 ]; then
  printf '{"call":{"detected":false,"opened_iso":null}}\n' >> "$OUT"
  echo "soak: no call was detected in this window — the report will say UNKNOWN, not PASS" >&2
fi

python3 "$ROOT/scripts/soak-report.py" "$OUT" --min-minutes "$MINUTES"
