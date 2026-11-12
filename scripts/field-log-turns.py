#!/usr/bin/env python3
"""Count turn outcomes in an exported Hermes Vox field log.

The point of this script is the denominator. Every number quoted in a release
document about a field log has to be re-derivable from the log plus this file,
or it is a memory of a number. 0.9.1's post-mortem quoted three different
counts for the same defect from the same log; this exists so the next one
cannot.

    scripts/field-log-turns.py LOG [--since-line N | --since-field NAME]
                                   [--short-ms 4000] [--cps 15.3]

What the log can and cannot say (read before quoting a number):

  gateway time     event=turn fullReply=<ms>. This is NOT audio. It is the
                   elapsed time from turn launch to the gateway's `done`
                   (VoiceController: LatencyStats.pushFullReply(uptime - t0)
                   on the done event). "fast gateway" = fullReply < --short-ms.
  reply length     no log line records the audio length of a streamed reply.
                   The proxy is the reply's characters (`turn done: gen=N …
                   len=L`, the last one for that gen before its event=turn)
                   divided by --cps. 15.3 chars/s is the median of the 205
                   one-shot `generated N samples @ R Hz (text C chars)` lines
                   in the 0.9.1 reporter's log (range 9.3-24.5), so treat the
                   estimate as +-50 %. "short reply" = estimated audio < --short-ms.
  render done      event=turn speechEndToRenderDone=<ms>: speech end to the last
                   sample played. It grows with the reply's length, so a long
                   reply that plays for two minutes is not a stall. A stall is
                   a SHORT reply with a render near the 120 s turn budget.
  held             time from `turn done` (all text in) to the turn's event=turn
                   (the gate releasing). A short reply held > 15 s waited on
                   something other than its own audio.

Only `event=turn ` lines are turns; `event=turn-dropped` is not counted.

A merged log of two builds cannot be counted as one log. `--since-line N`
counts from a line you can justify; `--since-field NAME` counts from the first
line carrying `NAME=` and prints that line, which is a justified boundary only
when the field was introduced in a known build (speechEndToRenderDone first
exists in LatencyStats at v0.9.1, absent at v0.9.0.3 — so the boundary it
finds is "0.9.1 or later", not "0.9.1"). The tool never guesses a boundary.
"""

import argparse
import re
import sys
from collections import Counter

STAMP = re.compile(r"^(\d\d):(\d\d):(\d\d)\.(\d+)")
TURN = re.compile(r"\bevent=turn\s")
DONE = re.compile(r"turn done: gen=(\d+)\b.*?\blen=(\d+)")
KV = re.compile(r"(\w+)=(-?\d+)\b")
GEN = re.compile(r"\bgen=(\d+)\b")
OUTCOME = re.compile(r"outcome=(\S+)")

STUCK_MS = 120_000   # the 0.9.1 turn budget the stalled drains ran into
HELD_MS = 15_000


def stamp_ms(line):
    m = STAMP.match(line)
    if not m:
        return None
    h, mi, s, frac = m.groups()
    return ((int(h) * 60 + int(mi)) * 60 + int(s)) * 1000 + int(frac[:3].ljust(3, "0"))


def parse(lines, cps):
    """One dict per event=turn line, with the reply length and hold time where the log has them."""
    turns, done = [], {}
    day, prev = 0, None
    for no, line in lines:
        t = stamp_ms(line)
        if t is not None:
            if prev is not None and t + 3_600_000 < prev:   # an undated log rolls over midnight
                day += 1
            prev = t
            t += day * 86_400_000
        d = DONE.search(line)
        if d:
            done[d.group(1)] = (t, int(d.group(2)))
            continue
        if not TURN.search(line):
            continue
        kv = {k: int(v) for k, v in KV.findall(line)}
        g = GEN.search(line)
        gen = g.group(1) if g else None
        at, chars = done.pop(gen, (None, None)) if gen else (None, None)
        o = OUTCOME.search(line)
        turns.append({
            "line": no,
            "gateway": kv.get("fullReply", -1),
            "render": kv.get("speechEndToRenderDone", -1),
            "chars": chars,
            "audio_est": round(chars / cps * 1000) if chars is not None else None,
            "held": (t - at) if (t is not None and at is not None) else None,
            "outcome": o.group(1) if o else "?",
        })
    return turns


def summary(turns, short_ms):
    """The counts the report prints, as data, so the self-test can pin them."""
    fast = [t for t in turns if 0 <= t["gateway"] < short_ms]
    short = [t for t in turns if t["audio_est"] is not None and t["audio_est"] < short_ms]
    long_ = [t for t in turns if t["audio_est"] is not None and t["audio_est"] >= short_ms]
    stuck = [t for t in short if t["render"] >= STUCK_MS]
    held = [t for t in short if t["held"] is not None and t["held"] > HELD_MS]
    long_render = [t for t in long_ if t["render"] >= STUCK_MS]
    return {
        "turns": len(turns),
        "fast_gateway": len(fast),
        "slow_gateway": len([t for t in turns if t["gateway"] >= short_ms]),
        "no_gateway_time": len([t for t in turns if t["gateway"] < 0]),
        "short": len(short),
        "long": len(long_),
        "no_length": len([t for t in turns if t["audio_est"] is None]),
        "stuck": stuck,
        "held": held,
        "long_render": long_render,
    }


def rng(vals):
    vals = sorted(vals)
    return f"{vals[0]}-{vals[-1]}" if vals else "-"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("log", nargs="?", default="-")
    ap.add_argument("--since-line", type=int, default=None,
                    help="1-indexed line to start counting from")
    ap.add_argument("--since-field", default=None,
                    help="start at the first line carrying NAME= (prints the line)")
    ap.add_argument("--short-ms", type=int, default=4000,
                    help="4000: the 0.9.1 streaming track's capacity, which a short reply could not fill")
    ap.add_argument("--cps", type=float, default=15.3,
                    help="characters per second of speech for the audio estimate")
    a = ap.parse_args()

    src = sys.stdin if a.log == "-" else open(a.log, errors="replace")
    lines = src.read().splitlines()
    start = 1
    if a.since_field:
        needle = a.since_field + "="
        hit = next((i for i, l in enumerate(lines, 1) if needle in l), None)
        if hit is None:
            print(f"no line carries {needle}")
            return 1
        start = hit
        print(f"boundary            first {needle} at line {hit}: {lines[hit - 1][:80]}")
    elif a.since_line:
        start = a.since_line

    turns = parse(list(enumerate(lines, 1))[start - 1:], a.cps)
    if not turns:
        print("no event=turn lines in the range counted")
        return 1
    s = summary(turns, a.short_ms)

    print(f"log                 {a.log}")
    print(f"counted from line   {start} of {len(lines)}")
    print(f"turns               {s['turns']}  (event=turn only; turn-dropped excluded)")
    print(f"gateway time        fast (<{a.short_ms} ms) {s['fast_gateway']} · "
          f"slow {s['slow_gateway']} · absent {s['no_gateway_time']}   "
          f"(fullReply: turn launch -> gateway done; NOT audio)")
    print(f"reply audio (est.)  short (<{a.short_ms} ms) {s['short']} · long {s['long']} · "
          f"no length {s['no_length']}   (chars / {a.cps} chars/s, +-50 %)")
    st = s["stuck"]
    print(f"\nshort replies at the {STUCK_MS // 1000} s budget   {len(st)}")
    if st:
        print(f"  render done       {rng([t['render'] for t in st])} ms")
        print(f"  reply length      {rng([t['chars'] for t in st])} chars")
        print(f"  outcomes          {dict(Counter(t['outcome'] for t in st))}")
        print(f"  log lines         {' '.join(str(t['line']) for t in st)}")
    hd = s["held"]
    print(f"short replies held >{HELD_MS // 1000} s after text done   {len(hd)}"
          f"   {dict(Counter(t['outcome'] for t in hd))}")
    lr = s["long_render"]
    print(f"long replies with render >= {STUCK_MS // 1000} s   {len(lr)}"
          + (f"   (lengths {rng([t['chars'] for t in lr])} chars: playback time, not a stall)" if lr else ""))
    print("\nNot measurable from a pre-0.9.1.3 log: how long a drain took. "
          "That needs event=tts-drain (0.9.1.3+).")
    outcomes = Counter(t["outcome"] for t in turns)
    print("\noutcomes            " + "  ".join(f"{k}={v}" for k, v in outcomes.most_common()))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
