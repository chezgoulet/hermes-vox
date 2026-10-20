#!/usr/bin/env python3
"""presence-session-report.py — what the presence layer actually did in a session.

Reads a logcat (one or more files) and reports, per run: how many slots were offered,
how many became lines, why the rest were refused, how far apart the lines landed, and
— when the build logs it — how long a render took.

  python3 scripts/presence-session-report.py logcat.txt
  python3 scripts/presence-session-report.py --json logcat.txt
  python3 scripts/presence-session-report.py --check logcat.txt   # exit 1 if the bar is missed

The bar (docs/PLAN-0.9.1.md §3, issue #217), applied to Conversational runs only:
at least two thirds of offered slots speak, nothing lands after the reply, and render
p95 stays under three seconds. Repeats need the line text, which only exists when
Settings -> Log transcripts is on; the report says "unknown" rather than guessing.

The event vocabulary is the app's, not this script's:
  event=er-presence line=N interval=MS density=D kind=lag|soul
  event=er-presence skip line=N reason=R density=D
  event=er-presence stop reason=R lines=N
  event=er-soul kind=K decision=D chars=N heard=B mood=M [ms=N]
  event=er-soul-drop reason=late ...
  event=er-render-skip|er-render-drop|er-render-preempt ...
"""
import json
import re
import statistics as st
import sys
from dataclasses import dataclass, field
from pathlib import Path

LINE = re.compile(r"er-presence line=(\d+)(?: interval=(\d+))?(?: density=(\w+))?(?: kind=(\w+))?")
SKIP = re.compile(r"er-presence skip line=(\d+)(?: reason=(\w+))?(?: density=(\w+))?")
STOP = re.compile(r"er-presence stop reason=(\w+)(?: lines=(\d+))?")
SOUL = re.compile(
    r"er-soul kind=(\w+) decision=(\w+) chars=(\d+) heard=(true|false) mood=(\S+)(?: ms=(\d+))?"
)
SOUL_DROP = re.compile(r"er-soul-drop reason=(\w+)")
RENDER = re.compile(r"event=er-render-(skip|drop|preempt)(?: reason=(\w+))?(?: intent=(\w+))?")

BAR_RATE = 2.0 / 3.0
BAR_P95_MS = 3000


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
    missing_ms: int = 0

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
    checks = {
        "rate": None if rate is None else rate >= BAR_RATE,
        "late": s.late == 0,
        "p95": None if p95 is None else p95 < BAR_P95_MS,
    }
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
        "render_ms_p50": pct(s.renders, 0.5),
        "render_ms_p95": p95,
        "render_samples": len(s.renders),
        "render_samples_missing_ms": s.missing_ms,
        "render_events": s.render_events,
        "decisions": s.decisions,
        "conversational": {"slots": conv_slots, "spoken": conv_spoken},
        "checks": checks,
        "passes_bar": all(v for v in checks.values() if v is not None) and any(v is not None for v in checks.values()),
    }


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
    if d["render_samples"]:
        out.append(f"  render latency       p50 {fmt(d['render_ms_p50'], ' ms')}   p95 {fmt(d['render_ms_p95'], ' ms')}   n={d['render_samples']}")
    else:
        out.append("  render latency       not logged by this build (needs event=er-soul … ms=)")
    if d["render_events"]:
        out.append("  render events        " + ", ".join(f"{k} {v}" for k, v in sorted(d["render_events"].items())))
    out.append("")
    c = d["checks"]
    out.append("  bar (Conversational)")
    out.append(f"    two thirds of slots speak   {'PASS' if c['rate'] else 'FAIL' if c['rate'] is False else 'unknown (no slots)'}")
    out.append(f"    nothing lands after reply   {'PASS' if c['late'] else 'FAIL'}")
    out.append(f"    render p95 < 3 s            {'PASS' if c['p95'] else 'FAIL' if c['p95'] is False else 'unknown (no ms= in this build)'}")
    out.append(f"    nothing repeats             unknown (needs Settings -> Log transcripts on)")
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
