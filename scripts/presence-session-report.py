#!/usr/bin/env python3
"""presence-session-report.py — what the presence layer actually did in a session.

Reads a logcat (one or more files) and reports, per run: how many slots were offered,
how many became lines, why the rest were refused, how far apart the lines landed, and
— when the build logs it — how long a render took.

  python3 scripts/presence-session-report.py logcat.txt
  python3 scripts/presence-session-report.py --json logcat.txt
  python3 scripts/presence-session-report.py --bar logcat.txt     # the verdict: exit 0 pass, 1 fail, 2 no data
  python3 scripts/presence-session-report.py --check logcat.txt   # exit 1 if the bar is missed

The bar (docs/PLAN-0.9.2.md, "The instrument and the bar"; issue #217). Every row names what
it measures, the events it counts, the threshold, the value and the sample size. A row with
no samples says "no data" and is never a pass. The rows (docs/PRESENCE-TUNING-BAR.md):

  speak-rate   spoken / offered working slots, Conversational runs     >= 2/3
  late         lines that arrived after the reply's first text         == 0
  repeats      a spoken line hash (h=) heard twice in one session      == 0
  render-p95   er-soul ms=, nearest-rank p95                           <  3000 ms
  boundary     working renders started < 700 ms after speech end (D18) == 0

A session is one conversation. `er-presence stop reason=call-end` closes one (a call can also
be one log file); every other stop closes a turn within it. That boundary is what repeats are
counted against: the same line in two turns of one call repeats, the same line in the next
call does not.

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
STOP = re.compile(r"er-presence stop reason=([\w-]+)(?: lines=(\d+))?")
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
    session: int = 0
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
    # The conversation now being read. A call-end stop or a second log file starts a new one.
    session: int = 0

    def run(self) -> Run:
        if not self.runs:
            self.runs.append(Run(index=0, session=self.session))
        return self.runs[-1]

    def new_run(self, reason: str | None = None):
        # `stop reason=call-end` is the app's own end-of-conversation signal; every other stop
        # (reply, cap, barge, hush) ends a turn inside the same conversation.
        if reason == "call-end":
            self.session += 1
        self.runs.append(Run(index=len(self.runs), session=self.session))

    def merge(self, other: "Session") -> None:
        """Fold a second log in as its own conversation(s)."""
        base = self.session + (1 if (self.runs or other.runs) else 0)
        for r in other.runs:
            r.session += base
        self.runs.extend(other.runs)
        self.session = max(self.session, base + other.session)
        self.late += other.late
        self.soul += other.soul
        self.renders.extend(other.renders)
        self.missing_ms += other.missing_ms
        self.working_boundary.extend(other.working_boundary)
        for mine, theirs in ((self.render_events, other.render_events),
                             (self.decisions, other.decisions),
                             (self.guard_refusals, other.guard_refusals)):
            for key, n in theirs.items():
                mine[key] = mine.get(key, 0) + n


def parse(text: str) -> Session:
    s = Session()
    for raw in text.splitlines():
        if "er-presence" not in raw and "er-soul" not in raw and "er-render" not in raw:
            continue
        if m := STOP.search(raw):
            s.run().close(m.group(1), int(m.group(2)) if m.group(2) else None)
            s.new_run(m.group(1))
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
    # Repeats belong to a conversation, not a turn: the same line twice in one call is a
    # repeat, the same line in the next call is not. The app closes a call with
    # `er-presence stop reason=call-end`; a second log file is a second conversation.
    hashes: dict[int, list[str]] = {}
    for r in runs:
        hashes.setdefault(r.session, []).extend(r.hashes)
    hashed_lines = sum(len(v) for v in hashes.values())
    repeats = sum(len(v) - len(set(v)) for v in hashes.values())
    # The after-the-reply failure has two lanes: a turn render dropped as late, and a working
    # slot skipped as late. Both are a line that arrived once the mind had spoken first.
    late_working = skips.get("late", 0)
    late_total = s.late + late_working
    late_n = s.soul + slots
    inside = sum(1 for b in s.working_boundary if b < BAR_BOUNDARY_MS)
    bar = [
        _row("speak-rate", "spoken / offered working slots, Conversational runs",
             "er-presence line / skip", ">= 2/3", rate, conv_slots,
             None if rate is None else rate >= BAR_RATE),
        _row("late", "lines that arrived after the reply's first text (turn drops + working skips)",
             "er-soul-drop reason=late / er-presence skip reason=late", "== 0",
             late_total if late_n else None, late_n, None if not late_n else late_total == 0),
        _row("repeats", "a spoken line heard twice in one session (by line hash)",
             "er-presence line … h=", "== 0", repeats if hashed_lines else None,
             hashed_lines, None if not hashed_lines else repeats == 0),
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
        "sessions": len({r.session for r in runs}),
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
        "late_working": late_working,
        "late_total": late_total,
        "guard_refusals": s.guard_refusals,
        "render_ms_p50": pct(s.renders, 0.5),
        "render_ms_p95": p95,
        "render_samples": len(s.renders),
        "render_samples_missing_ms": s.missing_ms,
        "render_events": s.render_events,
        "decisions": s.decisions,
        "conversational": {"slots": conv_slots, "spoken": conv_spoken},
        "repeats": repeats if hashed_lines else None,
        "hashed_lines": hashed_lines,
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


def _bar_lines(d: dict) -> list[str]:
    """The bar, one row per criterion: the verdict, the measured number, the bar, and n.

    A row with no samples is `no data`, never a pass: an absence is not a success.
    """
    out = ["  bar — pass, fail or no data per criterion, with the measured number and n"]
    for b in d["bar"]:
        verdict = "pass" if b["verdict"] else "fail" if b["verdict"] is False else "no data"
        v = b["value"]
        if v is None:
            shown = "—"
        elif b["name"] == "speak-rate":
            shown = f"{v * 100:.0f}%"
        elif b["name"] == "render-p95":
            shown = f"{v} ms"
        else:
            shown = f"{v}"
        out.append(f"    {verdict:<8}{b['name']:<11}{shown:>7}  bar {b['threshold']:<9} "
                   f"n={b['n']:<4} {b['measures']}  [{b['source']}]")
    measured = sum(1 for b in d["bar"] if b["verdict"] is not None)
    failed = sum(1 for b in d["bar"] if b["verdict"] is False)
    overall = "no data" if measured == 0 else ("pass" if d["passes_bar"] else "fail")
    out.append(f"  verdict: {overall} — "
               f"{measured} of {len(d['bar'])} criteria measured, {failed} failed, "
               f"{len(d['bar']) - measured} with no data")
    return out


def report(s: Session) -> str:
    d = summarise(s)
    out = []
    out.append("presence — session report")
    out.append("")
    out.append(f"  sessions             {d['sessions']}")
    out.append(f"  runs with slots      {d['runs']}")
    out.append(f"  slots offered        {d['slots']}")
    out.append(f"  lines spoken         {d['spoken']}  ({fmt(d['rate']*100 if d['rate'] is not None else None, '%', 0)} of Conversational slots)")
    if d["skips"]:
        out.append("  skips by reason      " + ", ".join(f"{k} {v}" for k, v in sorted(d["skips"].items())))
    else:
        out.append("  skips by reason      none")
    if d["repeats"] is not None:
        out.append(f"  repeats              {d['repeats']}  (the same line twice in one session, "
                   f"over {d['hashed_lines']} hashed line(s))")
    else:
        out.append("  repeats              no data  (the log carries no h= line hashes)")
    if d["kinds"]:
        out.append("  kinds                " + ", ".join(f"{k} {v}" for k, v in sorted(d["kinds"].items())))
    out.append(f"  median interval      {fmt(d['median_interval_ms'], ' ms')}")
    out.append("")
    out.append(f"  soul renders         {d['soul_renders']}   decisions " +
               ", ".join(f"{k} {v}" for k, v in sorted(d["decisions"].items())))
    out.append(f"  late                 {d['late_total']}   (lines that arrived after the reply's first "
               f"text: {d['late_drops']} turn drop(s), {d['late_working']} working skip(s))")
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
    out.extend(_bar_lines(d))
    return "\n".join(out)


def main(argv):
    args = [a for a in argv[1:] if not a.startswith("-")]
    as_json = "--json" in argv
    check = "--check" in argv
    bar = "--bar" in argv
    if not args:
        print(__doc__)
        return 2
    s = parse(Path(args[0]).read_text(errors="replace"))
    for path in args[1:]:
        # Each file is its own conversation; concatenating them would let a line in one call
        # be counted as a repeat of the same line in the next.
        s.merge(parse(Path(path).read_text(errors="replace")))
    d = summarise(s)
    if as_json:
        print(json.dumps({"bar": d["bar"], "passes_bar": d["passes_bar"]} if bar else d, indent=2))
    elif bar:
        print("\n".join(_bar_lines(d)))
    else:
        print(report(s))
    if check or bar:
        if not any(b["verdict"] is not None for b in d["bar"]):
            # Nothing was measured: not a pass, not a fail. Exit 2 so a caller cannot
            # mistake an empty run for a run that met the bar.
            return 2
        return 0 if d["passes_bar"] else 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
