#!/usr/bin/env python3
"""baseline-report.py — the per-release performance baseline from a call log (#209).

Reads one or more exported logs (Settings -> Share full log, or logcat) and prints the
baseline table the issue asks for, from the app's own event lines:

  event=turn ... stt=MS firstByte=MS firstText=MS firstAudio=MS fullReply=MS outcome=O
  event=er-soul ... ms=MS                      (a Presence render)
  event=res rssMb=… availMb=… thermal=…         (ResourceProbe, every 10 s of a call)
  event=res-summary peakRssMb=… minAvailMb=… thermalStart=… thermalEnd=… ms=…
  <leg> loaded: … loadMs=… warmMs=…             (a model warmed)
  event=turn-dropped / event=stream-turn-failed (a dropped turn)

  python3 scripts/baseline-report.py log.txt [log2.txt …]
  python3 scripts/baseline-report.py --json log.txt

A row the log cannot fill says "not in log" — the point is drift between cuts, so an
absent number is reported as absent, never estimated.
"""
import json
import re
import sys
from pathlib import Path

KV = re.compile(r"(\w+)=([^\s]+)")
# event=turn outcomes that are failures (the skill's "cut outcomes" minus the user's own cuts:
# a barge-in, a hush or a hang-up is the caller deciding, not the build failing).
FAILED = {"reply-error", "stream-lost", "empty-reply"}


def pct(xs, p):
    if not xs:
        return None
    s = sorted(xs)
    return s[int((len(s) - 1) * p)]


def num(v):
    try:
        return int(v)
    except (TypeError, ValueError):
        return None


def parse(lines):
    turns = {k: [] for k in ("stt", "firstByte", "firstText", "firstAudio", "fullReply")}
    renders, rss, avail, thermal, warm, summaries = [], [], [], [], {}, []
    dropped = failed = total_turns = 0
    for line in lines:
        if "event=turn " in line:
            kv = dict(KV.findall(line))
            total_turns += 1
            for k in turns:
                n = num(kv.get(k))
                if n is not None:
                    turns[k].append(n)
            if kv.get("outcome") in FAILED:
                failed += 1
        elif "event=turn-dropped" in line or "event=stream-turn-failed" in line:
            dropped += 1
        elif "event=er-soul " in line:
            n = num(dict(KV.findall(line)).get("ms"))
            if n is not None:
                renders.append(n)
        elif "event=res-summary" in line:
            summaries.append(dict(KV.findall(line)))
        elif "event=res " in line:
            kv = dict(KV.findall(line))
            if num(kv.get("rssMb")) is not None and num(kv["rssMb"]) >= 0:
                rss.append(num(kv["rssMb"]))
            if num(kv.get("availMb")) is not None and num(kv["availMb"]) >= 0:
                avail.append(num(kv["availMb"]))
            if kv.get("thermal"):
                thermal.append(kv["thermal"])
        elif " loaded: " in line:
            leg = line.split(" loaded: ")[0].split()[-1]
            kv = dict(KV.findall(line))
            warm[leg] = {k: num(kv[k]) for k in ("loadMs", "warmMs") if k in kv}
    return {
        "turns": total_turns,
        "latency": {k: {"p50": pct(v, 0.5), "p95": pct(v, 0.95), "n": len(v)} for k, v in turns.items()},
        "presence_render": {"p50": pct(renders, 0.5), "p95": pct(renders, 0.95), "n": len(renders)},
        "peak_rss_mb": max(rss) if rss else None,
        "min_avail_mb": min(avail) if avail else None,
        "thermal_start": thermal[0] if thermal else None,
        "thermal_end": thermal[-1] if thermal else None,
        "calls": len(summaries),
        "warm": warm,
        "dropped_turns": dropped,
        "failed_turns": failed,
    }


def table(r):
    def f(v, unit=" ms"):
        return "not in log" if v is None else f"{v}{unit}"
    rows = ["| Measure | Value |", "|---|---|"]
    for leg, w in sorted(r["warm"].items()):
        rows.append(f"| warm: {leg} | " + (", ".join(f"{k} {v} ms" for k, v in w.items()) or "logged, no time") + " |")
    if not r["warm"]:
        rows.append("| model warm | not in log |")
    for k, label in (("stt", "speech → text"), ("firstByte", "first byte"), ("firstText", "first text"),
                     ("firstAudio", "first audio"), ("fullReply", "full reply")):
        l = r["latency"][k]
        rows.append(f"| {label} p50 / p95 (n={l['n']}) | {f(l['p50'])} / {f(l['p95'])} |")
    p = r["presence_render"]
    rows.append(f"| presence render p50 / p95 (n={p['n']}) | {f(p['p50'])} / {f(p['p95'])} |")
    rows.append(f"| peak RSS | {f(r['peak_rss_mb'], ' MB')} |")
    rows.append(f"| lowest MemAvailable | {f(r['min_avail_mb'], ' MB')} |")
    rows.append(f"| thermal start → end | {r['thermal_start'] or 'not in log'} → {r['thermal_end'] or 'not in log'} |")
    rows.append(f"| turns / dropped / failed | {r['turns']} / {r['dropped_turns']} / {r['failed_turns']} |")
    return "\n".join(rows)


def main(argv):
    as_json = "--json" in argv
    paths = [a for a in argv if not a.startswith("--")]
    if not paths:
        print(__doc__.strip().splitlines()[0]); return 2
    lines = []
    for p in paths:
        lines += Path(p).read_text(errors="replace").splitlines()
    r = parse(lines)
    print(json.dumps(r, indent=2) if as_json else table(r))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
