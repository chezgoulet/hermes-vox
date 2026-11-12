#!/usr/bin/env python3
"""Count turn outcomes in an exported Hermes Vox field log.

The point of this script is the denominator. Every number quoted in a release
document about a field log has to be re-derivable from the log plus this file,
or it is a memory of a number. 0.9.1's post-mortem quoted three different
counts for the same defect from the same log; this exists so the next one
cannot.

    scripts/field-log-turns.py LOG [--since-line N] [--short-ms 4000]

Definitions used, printed with the result:

  a reply's length     event=turn fullReply=<ms of audio>
  the drain's duration event=turn speechEndToRenderDone=<ms>, or -1/absent
                       when the turn ended before the drain reported
  short                fullReply < --short-ms (default 4000: the 0.9.1
                       streaming track's capacity, which is what a short
                       reply could not fill)

A merged log of two builds cannot be counted as one log. `--since-line N`
counts from a line number you can justify; the tool prints which rule it
used and never guesses a build boundary on its own.
"""

import argparse
import re
import sys
from collections import Counter

TURN = re.compile(r"^(\d\d:\d\d:\d\d\.\d+).*\bevent=turn\b")
KV = re.compile(r"(\w+)=(-?\d+)")
OUTCOME = re.compile(r"outcome=(\S+)")


def buckets(ms_values, short_ms):
    """Durations grouped the way the review talks about them."""
    edges = [
        ("under 15 s", 0, 15_000),
        ("15-60 s", 15_000, 60_000),
        ("60-120 s", 60_000, 120_000),
        ("120 s or more", 120_000, None),
    ]
    out = []
    for label, lo, hi in edges:
        n = sum(1 for v in ms_values if v >= lo and (hi is None or v < hi))
        out.append((label, n))
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("log", nargs="?", default="-")
    ap.add_argument("--since-line", type=int, default=1,
                    help="1-indexed line to start counting from")
    ap.add_argument("--short-ms", type=int, default=4000)
    a = ap.parse_args()

    src = sys.stdin if a.log == "-" else open(a.log, errors="replace")
    lines = src.read().splitlines()
    counted = lines[a.since_line - 1:]

    turns = []
    for line in counted:
        m = TURN.match(line)
        if not m:
            continue
        kv = {k: int(v) for k, v in KV.findall(line)}
        turns.append({
            "at": m.group(1),
            "full": kv.get("fullReply", -1),
            "render": kv.get("speechEndToRenderDone", -1),
            "outcome": (OUTCOME.search(line).group(1)
                        if OUTCOME.search(line) else "?"),
        })

    if not turns:
        print("no event=turn lines in the range counted")
        return 1

    short = [t for t in turns if 0 <= t["full"] < a.short_ms]
    long_ = [t for t in turns if t["full"] >= a.short_ms]
    unmeasured = [t for t in turns if t["full"] < 0]

    print(f"log                 {a.log}")
    print(f"counted from line   {a.since_line} of {len(lines)}")
    print(f"turns               {len(turns)}")
    print(f"span                {turns[0]['at']} -> {turns[-1]['at']}")
    print(f"short (<{a.short_ms} ms audio)  {len(short)}")
    print(f"long  (>={a.short_ms} ms audio) {len(long_)}")
    if unmeasured:
        print(f"audio length absent {len(unmeasured)}")

    for label, group in (("short", short), ("long", long_)):
        measured = [t for t in group if t["render"] >= 0]
        missing = len(group) - len(measured)
        print(f"\n{label} replies, drain duration")
        for name, n in buckets([t["render"] for t in measured], a.short_ms):
            print(f"  {name:14} {n}")
        if missing:
            print(f"  {'not measured':14} {missing}  (no speechEndToRenderDone)")
        stuck = [t for t in measured if t["render"] >= 120_000]
        if stuck:
            vals = sorted(t["render"] for t in stuck)
            print(f"  stuck range    {vals[0]}-{vals[-1]} ms "
                  f"({len(stuck)} turns)")

    outcomes = Counter(t["outcome"] for t in turns)
    print("\noutcomes            " + "  ".join(
        f"{k}={v}" for k, v in outcomes.most_common()))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
