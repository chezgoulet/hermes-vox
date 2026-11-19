#!/usr/bin/env python3
"""soak-report.py — the verdict for a thirty-minute soak, in an exit code.

`scripts/soak.sh` samples the phone once every thirty seconds and writes JSONL. This script reads
that file and answers the four questions `docs/BUDGETS-0.9.1.md` set for a live call:

  1. **Battery** — no worse than 4 % per hour of a live call.
  2. **Thermal** — no throttle-state change during the soak. Cooling is not a failure.
  3. **Memory ceiling** — peak PSS at or under 1.5 GB for the whole process.
  4. **Memory slope** — no growth over the soak: the median of the second half against the first.

Exit codes, the same vocabulary as `scripts/baseline-diff.py`:

  0  SOAK PASS       every row judged and inside its budget
  1  SOAK FAIL       a row was measured and a budget was crossed; the row is named
  2  SOAK USAGE      the file is not a soak, or there is nothing to read
  3  SOAK UNKNOWN    a row could not be judged — no call in the window, a phone on the charger,
                     a missing series, or a window shorter than the soak. **Unknown is not
                     healthy** (D44): a soak that measured nothing is not a passing soak.

Why UNKNOWN is a separate code: the temptation with a soak is to run it for four minutes on a
desk, see no failures, and tick the box. The four-minute window cannot see a thermal climb, and
a phone on the charger cannot see battery drain at all. Both of those are UNKNOWN, not PASS.
"""

from __future__ import annotations

import argparse
import json
import statistics
import sys

BATTERY_PCT_PER_HOUR = 4.0
PSS_CEILING_KB = 1_500_000
SLOPE_LIMIT = 0.15
SLOPE_FLOOR_KB = 100_000
# Drain cannot be read off a few minutes: a single point of battery is reported at coarse
# granularity, so a short window extrapolates to absurdities in both directions. Under this
# window the battery row is UNKNOWN, whatever --min-minutes says.
BATTERY_MIN_MINUTES = 10.0
DEFAULT_MINUTES = 30.0

THERMAL_RANK = {
    "none": 0,
    "light": 1,
    "moderate": 2,
    "severe": 3,
    "critical": 4,
    "emergency": 5,
    "shutdown": 6,
}


def rank(state: str) -> int | None:
    if not state:
        return None
    s = state.strip().lower()
    for prefix in ("thermal_status_", "status_"):
        if s.startswith(prefix):
            s = s[len(prefix):]
    return THERMAL_RANK.get(s)


class BadFile(Exception):
    """The input is not a soak file. Its own exit code, so it can never read as a verdict."""


def load(path: str) -> tuple[dict, dict, list[dict]]:
    header: dict = {}
    call: dict = {}
    samples: list[dict] = []
    with open(path, encoding="utf-8") as fh:
        for n, line in enumerate(fh, start=1):
            line = line.strip()
            if not line:
                continue
            try:
                rec = json.loads(line)
            except json.JSONDecodeError:
                raise BadFile(f"line {n} is not JSON — {line[:60]}")
            if "soak" in rec:
                header = rec["soak"]
            elif "call" in rec:
                call = rec["call"]
            elif "t" in rec:
                samples.append(rec)
    return header, call, samples


def judge(samples: list[dict], call: dict, minutes: float) -> tuple[str, list[tuple[str, str, str]]]:
    """Return (verdict, rows) where each row is (name, state, detail)."""
    rows: list[tuple[str, str, str]] = []
    span_min = (samples[-1]["t"] - samples[0]["t"]) / 60.0 if len(samples) > 1 else 0.0

    if span_min < minutes:
        rows.append(("window", "UNKNOWN", f"{span_min:.1f} min of samples; a soak is {minutes:.0f} min"))
    else:
        rows.append(("window", "PASS", f"{span_min:.1f} min, {len(samples)} samples"))

    if not call.get("detected"):
        rows.append(("call", "UNKNOWN", "no live call in the window (nothing to soak)"))
    else:
        rows.append(("call", "PASS", f"call opened {call.get('opened_iso', '?')}"))

    # --- thermal: any rise is a failure; a fall is not.
    ranks = [(s.get("t"), rank(s.get("thermal", ""))) for s in samples]
    ranks = [(t, r) for t, r in ranks if r is not None]
    if not ranks:
        rows.append(("thermal", "UNKNOWN", "no thermal samples"))
    else:
        start_rank = ranks[0][1]
        peak = max(r for _, r in ranks)
        worst = max(ranks, key=lambda p: p[1])
        if peak > start_rank:
            rows.append(("thermal", "FAIL", f"rose from {start_rank} to {peak} at t={worst[0]:.0f}s"))
        else:
            rows.append(("thermal", "PASS", f"stayed at {start_rank} across {len(ranks)} samples"))

    # --- battery: per hour, only if it was discharging the whole time.
    batt = [(s.get("t"), s.get("battery")) for s in samples if isinstance(s.get("battery"), (int, float))]
    charging = any(s.get("charging") for s in samples)
    if len(batt) < 2:
        rows.append(("battery", "UNKNOWN", "fewer than two battery samples"))
    elif span_min < BATTERY_MIN_MINUTES:
        rows.append(("battery", "UNKNOWN", f"{span_min:.1f} min is too short to measure drain"))
    elif charging:
        rows.append(("battery", "UNKNOWN", "the phone was charging — drain is not measurable"))
    else:
        hours = (batt[-1][0] - batt[0][0]) / 3600.0
        if hours <= 0:
            rows.append(("battery", "UNKNOWN", "zero-length window"))
        else:
            drop = batt[0][1] - batt[-1][1]
            per_hour = drop / hours
            state = "FAIL" if per_hour > BATTERY_PCT_PER_HOUR else "PASS"
            rows.append(("battery", state, f"{drop} point(s) in {hours * 60:.0f} min = {per_hour:.1f} %/h (budget {BATTERY_PCT_PER_HOUR:g})"))

    # --- memory ceiling and slope.
    pss = [s.get("pss_kb") for s in samples if isinstance(s.get("pss_kb"), (int, float))]
    if not pss:
        rows.append(("peak PSS", "UNKNOWN", "no meminfo samples"))
        rows.append(("memory slope", "UNKNOWN", "no meminfo samples"))
    else:
        peak = max(pss)
        state = "FAIL" if peak > PSS_CEILING_KB else "PASS"
        rows.append(("peak PSS", state, f"{peak / 1024:.0f} MB (ceiling {PSS_CEILING_KB / 1024:.0f} MB)"))
        if len(pss) < 6:
            rows.append(("memory slope", "UNKNOWN", f"{len(pss)} samples is too few to judge a slope"))
        else:
            half = len(pss) // 2
            first = statistics.median(pss[:half])
            second = statistics.median(pss[half:])
            growth = (second - first) / first if first else 0.0
            delta = second - first
            # A leak has to clear both a percentage and an absolute floor: PSS jitters by tens
            # of MB, and a small base makes any percentage look large.
            leaked = growth > SLOPE_LIMIT or delta > SLOPE_FLOOR_KB
            state = "FAIL" if leaked else "PASS"
            rows.append(("memory slope", state, f"median {first / 1024:.0f} → {second / 1024:.0f} MB "
                                                f"({growth * 100:+.1f}% and {delta / 1024:+.0f} MB; "
                                                f"limit {SLOPE_LIMIT * 100:.0f}% or +{SLOPE_FLOOR_KB / 1024:.0f} MB)"))

    avail = [s.get("avail_kb") for s in samples if isinstance(s.get("avail_kb"), (int, float))]
    if avail:
        rows.append(("lowest MemAvailable", "INFO", f"{min(avail) / 1024:.0f} MB"))

    if any(state == "FAIL" for _, state, _ in rows):
        return "FAIL", rows
    if any(state == "UNKNOWN" for _, state, _ in rows):
        return "UNKNOWN", rows
    return "PASS", rows


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="Judge a soak JSONL against the four budgets.")
    ap.add_argument("path", help="the JSONL written by scripts/soak.sh")
    ap.add_argument("--min-minutes", type=float, default=DEFAULT_MINUTES,
                    help=f"how long a window counts as a soak (default {DEFAULT_MINUTES:g})")
    args = ap.parse_args(argv)

    try:
        header, call, samples = load(args.path)
    except OSError as exc:
        print(f"soak-report: cannot read {args.path}: {exc}", file=sys.stderr)
        return 2
    except BadFile as exc:
        print(f"soak-report: SOAK USAGE — {exc}", file=sys.stderr)
        return 2
    if not samples:
        print("soak-report: SOAK USAGE — no samples in the file", file=sys.stderr)
        return 2

    verdict, rows = judge(samples, call, args.min_minutes)
    label = header.get("label") or args.path
    print(f"soak-report: {label}")
    for name, state, detail in rows:
        print(f"  {name:<20} {state:<8} {detail}")
    print(f"SOAK {verdict} ({sum(1 for _, s, _ in rows if s == 'PASS')} rows judged, "
          f"{sum(1 for _, s, _ in rows if s == 'FAIL')} crossed, "
          f"{sum(1 for _, s, _ in rows if s == 'UNKNOWN')} unknown)")
    return {"PASS": 0, "FAIL": 1, "UNKNOWN": 3}[verdict]


if __name__ == "__main__":
    sys.exit(main())
