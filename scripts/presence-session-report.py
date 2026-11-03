#!/usr/bin/env python3
"""presence-session-report.py — what the presence layer actually did in a session.

Reads a logcat (one or more files) and reports, per run: how many slots were offered,
how many became lines, why the rest were refused, how far apart the lines landed, and
— when the build logs it — how long a render took.

  python3 scripts/presence-session-report.py logcat.txt
  python3 scripts/presence-session-report.py --json logcat.txt
  python3 scripts/presence-session-report.py --check logcat.txt   # exit 1 if the bar is missed

The bar (docs/PLAN-0.9.2.md, "The instrument and the bar"; issue #217). Every row names what
it measures, the events it counts, the threshold, the value and the sample size; a row with
no samples is "unknown", never a pass. The rows (docs/PRESENCE-TUNING-BAR.md):

  speak-rate   spoken / offered working slots, Conversational runs     >= 2/3
  late         turn renders dropped as late (er-soul-drop reason=late) == 0
  repeats      a spoken line hash (h=) heard twice in one run          == 0
  render-p95   er-soul ms=, nearest-rank p95                           <  3000 ms
  boundary     working renders started < 700 ms after speech end (D18) == 0

The event vocabulary is the app's, not this script's:
  event=er-presence line=N interval=MS density=D kind=lag|soul [h=HASH]
  event=er-presence skip line=N reason=R density=D
  event=er-presence stop reason=R lines=N
  event=er-soul kind=K decision=D chars=N heard=B mood=M [ms=N] [boundaryMs=N]
  event=er-soul-drop reason=late ...
  event=er-render-skip|er-render-drop|er-render-preempt ...
"""
import json
import re
import statistics as st
import sys
from dataclasses import dataclass, field
from pathlib import Path

LINE = re.compile(r"er-presence line=(\d+)(?: interval=(\d+))?(?: density=(\w+))?(?: kind=(\w+))?(?: h=([0-9a-f]+))?")
SKIP = re.compile(r"er-presence skip line=(\d+)(?: reason=(\w+))?(?: density=(\w+))?")
STOP = re.compile(r"er-presence stop reason=(\w+)(?: lines=(\d+))?")
SOUL = re.compile(
    r"er-soul kind=(\w+) decision=(\w+) chars=(\d+) heard=(true|false) mood=(\S+)(?: ms=(\d+))?(?: boundaryMs=(-?\d+))?"
)
SOUL_DROP = re.compile(r"er-soul-drop reason=(\w+)")
# The guard's own verdict on a line it refused: emitted only on the working path, and only
# when a slot was actually offered. `why` decides whether the bounds are wrong (#217).
DETAIL = re.compile(r"er-presence detail line=(\d+) why=([\w-]+)")
RENDER = re.compile(r"event=er-render-(skip|drop|preempt)(?: reason=(\w+))?(?: intent=(\w+))?")

BAR_RATE = 2.0 / 3.0
BAR_P95_MS = 3000
# D18: speech-end to first audio is budgeted at 700 ms p50; an aside rendering inside that
# window competes with the reply for the CPU it is meant to cover.
BAR_BOUNDARY_MS = 700


@dataclass
class Run:
    """One presence run: opened by a slot and closed by a stop event."""
    index: int = 0
    density: str | None = None
    slots: int = 0
    spoken: int = 0
    skips: list[str] = field(default_factory=list)
    kinds: list[str] = field(default_factory=list)
    intervals: list[int] = field(default_factory=list)
    stop_reason: str | None = None
    lines_at_stop: int | None = None
    hashes: list[str] = field(default_factory=list)

    def close(self, reason, lines):
        self.stop_reason = reason
        self.lines_at_stop = lines


@dataclass
class Session:
    runs: list[Run] = field(default_factory=list)
    late: int = 0
    soul: int = 0
    renders: list[int] = field(default_factory=list)
    render_events: dict[str, int] = field(default_factory=dict)
    decisions: dict[str, int] = field(default_factory=dict)
    guard_refusals: dict[str, int] = field(default_factory=dict)
    missing_ms: int = 0
    # boundaryMs of each working render (the D18 window); -1 = no turn yet, skipped.
    working_boundary: list[int] = field(default_factory=list)

    def run(self) -> Run:
        if not self.runs:
            self.runs.append(Run(index=0))
        return self.runs[-1]

    def new_run(self):
        self.runs.append(Run(index=len(self.runs)))


def parse(text: str) -> Session:
    s = Session()
    for raw in text.splitlines():
        if "er-presence" not in raw and "er-soul" not in raw and "er-render" not in raw:
            continue
        if m := STOP.search(raw):
            s.run().close(m.group(1), int(m.group(2)) if m.group(2) else None)
            s.new_run()
            continue
        if m := LINE.search(raw):
            r = s.run()
            n = int(m.group(1))
            r.slots = max(r.slots, n)
            r.spoken += 1
            if m.group(2):
                r.intervals.append(int(m.group(2)))
            if m.group(3):
                r.density = m.group(3)
            if m.group(4):
                r.kinds.append(m.group(4))
            if m.group(5):
                r.hashes.append(m.group(5))
            continue
        if m := SKIP.search(raw):
            r = s.run()
            r.slots = max(r.slots, int(m.group(1)))
            r.skips.append(m.group(2) or "unknown")
            if m.group(3):
                r.density = m.group(3)
            continue
        if m := SOUL_DROP.search(raw):
            if m.group(1) == "late":
                s.late += 1
            continue
        if m := SOUL.search(raw):
            s.soul += 1
            s.decisions[m.group(2)] = s.decisions.get(m.group(2), 0) + 1
            if m.group(6):
                s.renders.append(int(m.group(6)))
            else:
                s.missing_ms += 1
            if m.group(1) == "working" and m.group(7) is not None and int(m.group(7)) >= 0:
                s.working_boundary.append(int(m.group(7)))
            continue
        if m := DETAIL.search(raw):
            # `why=-` is the guard saying it would have spoken the line; the skip was for a
            # different reason (late, glue-playing, undeliverable). Counting "-" as a refusal
            # reason would pollute the one number this report exists to produce.
            if m.group(2) != "-":
                s.guard_refusals[m.group(2)] = s.guard_refusals.get(m.group(2), 0) + 1
            continue
        if m := RENDER.search(raw):
            k = m.group(1) + (":" + m.group(2) if m.group(2) else "")
            s.render_events[k] = s.render_events.get(k, 0) + 1
    # a log that never stopped still has a live run; keep it only if it saw a slot
    if s.runs and s.runs[-1].slots == 0 and s.runs[-1].stop_reason is None:
        s.runs.pop()
    return s


def _counts(values):
    out = {}
    for v in values:
        out[v] = out.get(v, 0) + 1
    return out


def pct(vals, q):
    """p50 is the median; every other quantile is nearest-rank.

    Nearest-rank for the tail on purpose: with a handful of samples an interpolated p95
    sits below the worst run, and a latency bar that understates the tail is worse than
    no bar. p95 of three samples is the slowest of the three.
    """
    if not vals:
        return None
    if q == 0.5:
        return st.median(vals)
    vals = sorted(vals)
    import math
    return vals[max(0, math.ceil(q * len(vals)) - 1)]


def summarise(s: Session) -> dict:
    runs = [r for r in s.runs if r.slots > 0]
    slots = sum(r.slots for r in runs)
    spoken = sum(r.spoken for r in runs)
    skips: dict[str, int] = {}
    for r in runs:
        for k in r.skips:
            skips[k] = skips.get(k, 0) + 1
    intervals = [i for r in runs for i in r.intervals]
    conv = [r for r in runs if (r.density or "conversational") == "conversational"]
    conv_slots = sum(r.slots for r in conv)
    conv_spoken = sum(r.spoken for r in conv)
    rate = (conv_spoken / conv_slots) if conv_slots else None
    p95 = pct(s.renders, 0.95)
    hashed_runs = [r for r in runs if r.hashes]
    repeats = sum(len(r.hashes) - len(set(r.hashes)) for r in hashed_runs)
    inside = sum(1 for b in s.working_boundary if b < BAR_BOUNDARY_MS)
    bar = [
        _row("speak-rate", "spoken / offered working slots, Conversational runs",
             "er-presence line / skip", ">= 2/3", rate, conv_slots,
             None if rate is None else rate >= BAR_RATE),
        _row("late", "turn renders that arrived after the reply and were dropped",
             "er-soul-drop reason=late", "== 0", s.late, s.soul, s.late == 0),
        _row("repeats", "spoken lines heard twice in one run (by line hash)",
             "er-presence line … h=", "== 0", repeats if hashed_runs else None,
             sum(len(r.hashes) for r in hashed_runs), None if not hashed_runs else repeats == 0),
        _row("render-p95", "render latency, nearest-rank p95",
             "er-soul … ms=", f"< {BAR_P95_MS} ms", p95, len(s.renders),
             None if p95 is None else p95 < BAR_P95_MS),
        _row("boundary", f"working renders started < {BAR_BOUNDARY_MS} ms after the caller stopped (D18)",
             "er-soul kind=working … boundaryMs=", "== 0", inside if s.working_boundary else None,
             len(s.working_boundary), None if not s.working_boundary else inside == 0),
    ]
    checks = {b["name"]: b["verdict"] for b in bar}
    # Back-compatible keys for --json readers of the first version.
    checks["rate"] = checks["speak-rate"]
    checks["p95"] = checks["render-p95"]
    return {
        "runs": len(runs),
        "slots": slots,
        "spoken": spoken,
        "rate": rate,
        "skips": skips,
        "stops": _counts(r.stop_reason for r in runs if r.stop_reason),
        "median_interval_ms": st.median(intervals) if intervals else None,
        "kinds": {k: sum(1 for r in runs for x in r.kinds if x == k) for k in {x for r in runs for x in r.kinds}},
        "soul_renders": s.soul,
        "late_drops": s.late,
        "guard_refusals": s.guard_refusals,
        "render_ms_p50": pct(s.renders, 0.5),
        "render_ms_p95": p95,
        "render_samples": len(s.renders),
        "render_samples_missing_ms": s.missing_ms,
        "render_events": s.render_events,
        "decisions": s.decisions,
        "conversational": {"slots": conv_slots, "spoken": conv_spoken},
        "repeats": repeats if hashed_runs else None,
        "working_boundary_ms": s.working_boundary,
        "bar": bar,
        "checks": checks,
        "passes_bar": all(b["verdict"] for b in bar if b["verdict"] is not None)
            and any(b["verdict"] is not None for b in bar),
    }


def _row(name, measures, source, threshold, value, n, verdict):
    """One bar row: what it measures, from which events, against what, and on how much."""
    return {"name": name, "measures": measures, "source": source, "threshold": threshold,
            "value": value, "n": n, "verdict": verdict}


def fmt(v, unit="", nd=0):
    if v is None:
        return "—"
    return f"{v:.{nd}f}{unit}" if isinstance(v, float) else f"{v}{unit}"


def report(s: Session) -> str:
    d = summarise(s)
    out = []
    out.append("presence — session report")
    out.append("")
    out.append(f"  runs with slots      {d['runs']}")
    out.append(f"  slots offered        {d['slots']}")
    out.append(f"  lines spoken         {d['spoken']}  ({fmt(d['rate']*100 if d['rate'] is not None else None, '%', 0)} of Conversational slots)")
    if d["skips"]:
        out.append("  refused              " + ", ".join(f"{k} {v}" for k, v in sorted(d["skips"].items())))
    if d["kinds"]:
        out.append("  kinds                " + ", ".join(f"{k} {v}" for k, v in sorted(d["kinds"].items())))
    out.append(f"  median interval      {fmt(d['median_interval_ms'], ' ms')}")
    out.append("")
    out.append(f"  soul renders         {d['soul_renders']}   decisions " +
               ", ".join(f"{k} {v}" for k, v in sorted(d["decisions"].items())))
    out.append(f"  late drops           {d['late_drops']}   (a line that arrived after the reply)")
    if d["guard_refusals"]:
        out.append("  guard refusals       " + ", ".join(f"{k} {v}" for k, v in sorted(d["guard_refusals"].items())))
    else:
        out.append("  guard refusals       none logged (the guard only runs when a working slot is offered)")
    if d["render_samples"]:
        out.append(f"  render latency       p50 {fmt(d['render_ms_p50'], ' ms')}   p95 {fmt(d['render_ms_p95'], ' ms')}   n={d['render_samples']}")
    else:
        out.append("  render latency       not logged by this build (needs event=er-soul … ms=)")
    if d["render_events"]:
        out.append("  render events        " + ", ".join(f"{k} {v}" for k, v in sorted(d["render_events"].items())))
    out.append("")
    out.append("  bar — each row: what it measures, the events, threshold, value (n)")
    for b in d["bar"]:
        verdict = "PASS" if b["verdict"] else "FAIL" if b["verdict"] is False else "unknown"
        v = b["value"]
        shown = "—" if v is None else (f"{v*100:.0f}%" if b["name"] == "speak-rate" else f"{v}")
        out.append(f"    {verdict:<8}{b['name']:<12}{shown:>8} {b['threshold']:<10} n={b['n']:<4} "
                   f"{b['measures']}  [{b['source']}]")
    return "\n".join(out)


def main(argv):
    args = [a for a in argv[1:] if not a.startswith("-")]
    as_json = "--json" in argv
    check = "--check" in argv
    if not args:
        print(__doc__)
        return 2
    text = "\n".join(Path(p).read_text(errors="replace") for p in args)
    s = parse(text)
    d = summarise(s)
    if as_json:
        print(json.dumps(d, indent=2))
    else:
        print(report(s))
    if check:
        return 0 if d["passes_bar"] else 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
