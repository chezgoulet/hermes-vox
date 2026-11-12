#!/usr/bin/env python3
"""baseline-diff.py — the comparator behind "no less stable than the last release" (#209).

Two `baseline-report.py --json` outputs in, one verdict out. The baseline is the previous
release's row, taken on the same device with the same settings; the candidate is the build
about to be cut, taken the same way. "No less stable" stops being a sentence and becomes an
exit code:

  0  BASELINE-DIFF PASS       — no stated tolerance was crossed
  1  BASELINE-DIFF REGRESSED  — at least one stated tolerance was crossed
  2  BASELINE-DIFF USAGE      — bad arguments, or a file that is not a baseline report
  3  BASELINE-DIFF UNKNOWN    — the baseline measured a row and the candidate did not
                                report it: unknown, which is never "healthy" (D44)

A non-zero code is the point. So is a *zero* code on two identical rows: a comparator that
always fails proves nothing, and the self-test (`scripts/test_baseline_diff.py`, in the gate)
requires both directions.

## The tolerances, and why each one is what it is

Every tolerance is `max(relative × baseline, absolute floor)` added to the baseline. The
relative half absorbs a device that is proportionally slower this run; the absolute floor
stops a tiny baseline (120 ms of STT) from turning 15 ms of noise into a red. Above that
band the candidate has to be *worse*, not merely different.

| Row | Tolerance | Why that number |
|---|---|---|
| cold start → first frame | +30% or +150 ms | dominated by process start and ART warm-up, which vary with what else the phone was doing; a fifth of a second is the smallest visible step on a cold open |
| model warm: loadMs | +40% or +500 ms | a cold load reads the model from disk; disk contention on one handset is the noise, half a second is the smallest change a user feels |
| model warm: warmMs | +50% or +300 ms | the cache path is short and should be stable; half again its time is a real change, not jitter |
| speech → text p50 | +20% or +60 ms | the recogniser is the one leg where a config change (whisper-base vs a larger model) moves the number in whole hundreds of ms |
| first byte p50 | +20% or +60 ms | gateway leg; noise on a tailnet is tens of ms, a config change is not |
| first text p50 | +25% or +80 ms | parser + first token; tolerates a gateway that streams the first token slightly later |
| first audio p50 | +20% or +80 ms | **the perceived number** — the one a user calls "slow". Tightest relative band of the latency rows |
| presence render p50 | +25% or +120 ms | a render is an on-device model call; 120 ms is about one short word of speech |
| peak RSS | +250 MB | a resident model is hundreds of MB; a leak shows as a trend over cuts, a regression as a step of this size |
| lowest MemAvailable | −200 MB | headroom, the thing that decides whether a second model can co-reside. Falling by this much is a budget change |
| thermal start / end | rank only | thermal is a state, not a number: `none < light < moderate < severe < critical`. A candidate that ends hotter than the baseline ended is a regression at any duration |
| failed turns | any increase | a failed turn (`reply-error`, `stream-lost`, `empty-reply`) is never noise; one more is one more |
| dropped turns | any increase — **warn** | a drop can be the caller hanging up (`reason=stopped`), which is not the build's fault. Reported, does not fail the diff |

**Not compared, stated rather than dropped:** `fullReply`. It is `uptimeMillis() - t0` at the
gateway's `done` (`VoiceController.kt` → `LatencyStats.pushFullReply`) — gateway time, not
audio, and it grows with the length of the reply. Two runs of different replies cannot be
differenced on it; the 0.9.1 post-mortem's first recount compared it as audio and got the
denominator wrong (`docs/REVIEW-0.9.1.md`). It stays in the report as context.

## Which rows are required

The baseline defines the required set. A row the baseline measured is required of the
candidate: if the candidate's log does not contain it, the diff says UNKNOWN and exits 3.
A row the baseline never measured cannot block — it is printed as "no baseline" with the
candidate's number beside it, and the release document records it as the first measurement.

That rule is why a row can go *missing* on a new build and still be caught: the failure mode
this instrument exists to prevent is a number that quietly stops being reported.

## Usage

  python3 scripts/baseline-diff.py base.json candidate.json
  python3 scripts/baseline-diff.py --base base.json --cand cand.json
  python3 scripts/baseline-diff.py --only cold,firstAudio base.json cand.json
  python3 scripts/baseline-diff.py --quiet base.json cand.json      # the verdict line only

`--only` takes comma-separated row keys (as printed in the table's first column, see `--keys`).
"""
import json
import sys

OK, REGRESSED, USAGE, UNKNOWN = 0, 1, 2, 3

THERMAL_RANK = {"none": 0, "light": 1, "moderate": 2, "severe": 3, "critical": 4, "shutdown": 5}


def _pct(report, name, p="p50"):
    d = (report.get("latency") or {}).get(name) or {}
    return d.get(p), int(d.get("n") or 0)


def _cold(report, p="p50"):
    d = report.get("cold_start") or {}
    return d.get(p), int(d.get("n") or 0)


def _scalar(key):
    def get(report):
        return report.get(key), None
    return get


class Row:
    """One comparable measure.

    kind: `up` (higher is worse), `down` (lower is worse), `count` (any increase is worse),
    `thermal` (worse rank is worse).
    """

    def __init__(self, key, label, get, kind="up", rel=0.0, abs_=0, unit=" ms", why="", warn=False):
        self.key, self.label, self.get, self.kind = key, label, get, kind
        self.rel, self.abs, self.unit, self.why, self.warn = rel, abs_, unit, why, warn


# The order is the order #209 lists them in, so the table reads like the issue.
ROWS = [
    Row("cold", "cold start → first frame (p50)", _cold, rel=0.30, abs_=150),
    Row("stt", "speech → text (p50)", lambda r: _pct(r, "stt"), rel=0.20, abs_=60),
    Row("firstByte", "first byte (p50)", lambda r: _pct(r, "firstByte"), rel=0.20, abs_=60),
    Row("firstText", "first text (p50)", lambda r: _pct(r, "firstText"), rel=0.25, abs_=80),
    Row("firstAudio", "first audio (p50)", lambda r: _pct(r, "firstAudio"), rel=0.20, abs_=80),
    Row("presence", "presence render (p50)", lambda r: (
        (r.get("presence_render") or {}).get("p50"),
        int((r.get("presence_render") or {}).get("n") or 0)), rel=0.25, abs_=120),
    Row("peakRss", "peak RSS", _scalar("peak_rss_mb"), rel=0.0, abs_=250, unit=" MB"),
    Row("minAvail", "lowest MemAvailable", _scalar("min_avail_mb"), kind="down",
        rel=0.0, abs_=200, unit=" MB"),
    Row("thermalStart", "thermal at start", _scalar("thermal_start"), kind="thermal", unit=""),
    Row("thermalEnd", "thermal at end", _scalar("thermal_end"), kind="thermal", unit=""),
    Row("failed", "failed turns", _scalar("failed_turns"), kind="count", unit=""),
    Row("dropped", "dropped turns", _scalar("dropped_turns"), kind="count", unit="", warn=True),
]

EXCLUDED = [
    ("fullReply", "gateway time (`uptimeMillis() - t0` at the gateway's `done`), not audio; "
                  "it grows with reply length, so two runs differ on reply size, not on the build"),
]


def warm_rows(base):
    """One row per warmed model leg, taken from the baseline's own list of legs."""
    rows = []
    for leg in sorted((base.get("warm") or {})):
        for metric, rel, abs_ in (("loadMs", 0.40, 500), ("warmMs", 0.50, 300)):
            def get(r, leg=leg, metric=metric):
                w = (r.get("warm") or {}).get(leg) or {}
                return w.get(metric), None
            rows.append(Row(f"warm.{leg}.{metric}", f"warm {leg} {metric}", get,
                            rel=rel, abs_=abs_))
    return rows


def allowance(row, base):
    """The band a candidate may sit in above [base] before it counts as worse."""
    return max(abs(row.rel) * base, row.abs)


def judge(row, base, cand):
    """(verdict, allowed, note). verdict ∈ pass, regressed, unknown, no-baseline, n/a."""
    if row.kind == "thermal":
        if base is None and cand is None:
            return "n/a", None, "the phone never said"
        if base is None:
            return "no-baseline", None, f"candidate says {cand}"
        b, c = THERMAL_RANK.get(str(base)), THERMAL_RANK.get(str(cand))
        if b is None or c is None:
            return "unknown", None, f"unrecognised thermal state base={base} cand={cand}"
        return ("pass" if c <= b else "regressed"), b, "worse rank is worse"
    if base is None and cand is None:
        # Neither run exercised the row (Presence off on a gate-off baseline, say). Not a
        # pass — nothing was compared — and not a failure either.
        return "n/a", None, "neither run measured it"
    if base is None:
        return "no-baseline", None, "first measurement"
    if cand is None:
        return "unknown", None, "the baseline measured it; the candidate did not report it"
    if row.kind == "count":
        return ("regressed" if cand > base else "pass"), 0, ("any increase is worse"
                                                            if cand > base else "")
    allowed = allowance(row, base)
    if row.kind == "down":
        # Lower is worse: the candidate may fall by [allowed] and no further.
        return ("regressed" if cand < base - allowed else "pass"), allowed, ""
    return ("regressed" if cand > base + allowed else "pass"), allowed, ""


def fmt(v, unit):
    if v is None:
        return "—"
    if isinstance(v, float):
        return f"{v:.0f}{unit}"
    return f"{v}{unit}"


def band(row, allowed, base):
    if row.kind == "count":
        return "0"
    if row.kind == "thermal":
        return "rank"
    sign = "−" if row.kind == "down" else "+"
    parts = []
    if row.rel:
        parts.append(f"{int(row.rel * 100)}%")
    if row.abs:
        parts.append(f"{row.abs}{row.unit}")
    return f"{sign}{allowed:.0f}{row.unit} " + (f"({' or '.join(parts)})" if parts else "")


def main(argv):
    args = list(argv[1:])
    if "-h" in args or "--help" in args:
        print((__doc__ or "").strip())
        return OK
    base_path = cand_path = None
    only, quiet = None, "--quiet" in args
    rest, it = [], iter(args)
    for a in it:
        if a in ("--base", "--cand", "--only"):
            v = next(it, "")
            if a == "--base": base_path = v
            elif a == "--cand": cand_path = v
            else: only = [s.strip() for s in v.split(",") if s.strip()]
        elif a.startswith("--base="): base_path = a.split("=", 1)[1]
        elif a.startswith("--cand="): cand_path = a.split("=", 1)[1]
        elif a == "--quiet": continue
        elif a.startswith("--"): continue
        else: rest.append(a)
    if base_path is None and rest: base_path = rest.pop(0)
    if cand_path is None and rest: cand_path = rest.pop(0)
    if not base_path or not cand_path:
        print((__doc__ or "").strip().splitlines()[0])
        print("usage: baseline-diff.py [--base] base.json [--cand] cand.json", file=sys.stderr)
        return USAGE
    reports = []
    for p in (base_path, cand_path):
        try:
            with open(p) as f:
                r = json.load(f)
        except OSError as e:
            print(f"baseline-diff: cannot read {p}: {e}", file=sys.stderr); return USAGE
        except ValueError as e:
            print(f"baseline-diff: {p} is not JSON ({e}) — use `baseline-report.py --json`",
                  file=sys.stderr); return USAGE
        # A baseline report is recognised by its own shape. Handing this tool two arbitrary
        # JSON files must fail loudly rather than compare two Nones and call it a pass.
        if not isinstance(r, dict) or "latency" not in r or "turns" not in r:
            print(f"baseline-diff: {p} is not a baseline report (no `latency`/`turns`); "
                  f"run `scripts/baseline-report.py --json <log>`", file=sys.stderr)
            return USAGE
        reports.append(r)
    base, cand = reports
    rows = ROWS + warm_rows(base)
    if only:
        rows = [r for r in rows if r.key in only]
        if not rows:
            print(f"baseline-diff: --only matched no row; keys are: "
                  f"{', '.join(r.key for r in ROWS + warm_rows(base))}", file=sys.stderr)
            return USAGE

    out, worst, compared, weak, unknown_rows, bad = [], OK, 0, [], [], []
    for row in rows:
        b, bn = row.get(base)
        c, cn = row.get(cand)
        verdict, allowed, note = judge(row, b, c)
        if verdict in ("pass", "regressed"):
            compared += 1
            if bn is not None and cn is not None and min(bn, cn) < 3:
                weak.append(f"{row.key} (n={bn}/{cn})")
        elif verdict == "unknown":
            unknown_rows.append(row.key)
        # A warned row (a dropped turn) is printed and counted, but does not by itself turn
        # the diff red: a drop can be the caller hanging up, and that is not the build's fault.
        if verdict == "regressed":
            if row.warn:
                pass
            else:
                bad.append(row.label)
                worst = REGRESSED
        unit = row.unit
        n = f" n={bn}/{cn}" if bn is not None and cn is not None else ""
        verdict_cell = verdict
        if verdict == "regressed" and row.warn:
            verdict_cell = "warn"
        elif verdict == "regressed":
            verdict_cell = "**REGRESSED**"
        out.append(f"| {row.label}{n} | {fmt(b, unit)} | {fmt(c, unit)} | "
                   f"{band(row, allowed, b) if allowed is not None else '—'} | {verdict_cell} "
                   f"| {note} |")

    if unknown_rows:
        worst = UNKNOWN if worst != REGRESSED else worst
    base_label = base.get("label") or base_path
    cand_label = cand.get("label") or cand_path
    if not quiet:
        print(f"baseline-diff: base «{base_label}» vs candidate «{cand_label}»")
        print(f"  turns: base {base.get('turns')} · candidate {cand.get('turns')}; "
              f"calls: base {base.get('calls')} · candidate {cand.get('calls')}")
        print()
        print("| Row | Base | Candidate | Allowed drift | Verdict | Note |")
        print("|---|---|---|---|---|---|")
        print("\n".join(out))
        print()
        for key, why in EXCLUDED:
            print(f"not compared: {key} — {why}")
        if weak:
            print(f"weak evidence (fewer than 3 samples on a side): {', '.join(weak)}")
        if unknown_rows:
            print(f"unknown (in the baseline, absent from the candidate): {', '.join(unknown_rows)}")

    if worst == REGRESSED:
        print(f"BASELINE-DIFF REGRESSED ({len(bad)} row{'s' if len(bad) != 1 else ''}: "
              f"{', '.join(bad)})")
    elif worst == UNKNOWN:
        print(f"BASELINE-DIFF UNKNOWN ({len(unknown_rows)} row"
              f"{'s' if len(unknown_rows) != 1 else ''} the candidate did not report: "
              f"{', '.join(unknown_rows)})")
    else:
        print(f"BASELINE-DIFF PASS ({compared} rows compared)")
    return worst


if __name__ == "__main__":
    sys.exit(main(sys.argv))
