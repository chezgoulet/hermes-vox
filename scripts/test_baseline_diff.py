#!/usr/bin/env python3
"""Self-test for baseline-diff.py: a comparator that can fail, proved by making it fail.

Sprint 1's bar (#209): the diff "exits non-zero when any stated tolerance is crossed. A
self-test feeds it a synthetic regression and requires a failure." This file does that, and
the four things a comparator needs before its failures mean anything:

  1. two rows that are the same pass                     — a tool that always fails proves nothing
  2. a synthetic regression fails                         — and the failing row is named
  3. a row the baseline measured but the candidate lost   — UNKNOWN, non-zero, never "healthy"
  4. an excluded row (fullReply) moving a long way        — does not fail, because it is not
                                                            comparable (gateway time, not audio)

Exit codes are the interface (0 pass / 1 regressed / 2 usage / 3 unknown), so each case runs
the script as a subprocess and asserts the code, not just the function's return value.
"""
import json
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
DIFF = HERE / "baseline-diff.py"

# A plausible gate-off row: whisper-base, 900 ms partials, 800 ms VAD, Presence off.
BASE = {
    "label": "0.9.1.4 gate off, fresh install",
    "turns": 8,
    "calls": 1,
    "cold_start": {"p50": 780, "p95": 900, "n": 3},
    "latency": {
        "stt": {"p50": 300, "p95": 420, "n": 8},
        "firstByte": {"p50": 280, "p95": 350, "n": 8},
        "firstText": {"p50": 640, "p95": 800, "n": 8},
        "firstAudio": {"p50": 1100, "p95": 1400, "n": 8},
        "fullReply": {"p50": 6000, "p95": 9000, "n": 8},
    },
    "presence_render": {"p50": None, "p95": None, "n": 0},
    "peak_rss_mb": 2400,
    "min_avail_mb": 1500,
    "thermal_start": "none",
    "thermal_end": "light",
    "warm": {"Whisper-base": {"loadMs": 1200, "warmMs": 300},
             "SherpaTts": {"warmMs": 640}},
    "calls": 1,
    "dropped_turns": 0,
    "failed_turns": 0,
}


def run(args, base=None, cand=None):
    """Run the diff as a subprocess against two dicts; returns (code, stdout+stderr)."""
    with tempfile.TemporaryDirectory() as d:
        paths = []
        for name, doc in (("base.json", base), ("cand.json", cand)):
            p = Path(d) / name
            p.write_text(json.dumps(doc))
            paths.append(str(p))
        cmd = [sys.executable, str(DIFF)] + [a.replace("BASE", paths[0]).replace("CAND", paths[1])
                                             for a in args]
        r = subprocess.run(cmd, capture_output=True, text=True)
        return r.returncode, r.stdout + r.stderr


def case(name, base, cand, want_code, want_in=""):
    code, out = run(["BASE", "CAND"], base, cand)
    assert code == want_code, f"{name}: exit {code}, wanted {want_code}\n{out}"
    assert want_in in out, f"{name}: {want_in!r} not in output\n{out}"
    return out


# 1. The same row twice is a pass. A comparator that always fails would pass cases 2–3 and
#    prove nothing, which is why this case exists first.
out = case("identical rows pass", BASE, dict(BASE), 0, "BASELINE-DIFF PASS")
assert "REGRESSED" not in out

# A candidate that is *better* is also a pass: the diff looks for regressions, not change.
better = json.loads(json.dumps(BASE))
better["latency"]["firstAudio"]["p50"] = 800
better["peak_rss_mb"] = 1900
case("an improvement passes", BASE, better, 0, "BASELINE-DIFF PASS")

# 2. The synthetic regression. First audio moves from 1100 ms to 1600 ms — 45% slower, well
#    past the +20%-or-80 ms band — and the diff must fail, naming the row.
regressed = json.loads(json.dumps(BASE))
regressed["latency"]["firstAudio"]["p50"] = 1600
out = case("a slower first audio fails", BASE, regressed, 1, "BASELINE-DIFF REGRESSED")
assert "first audio" in out.split("BASELINE-DIFF REGRESSED")[1], out

# Inside the band is not a regression: 1100 → 1150 is under max(220, 80).
inside = json.loads(json.dumps(BASE))
inside["latency"]["firstAudio"]["p50"] = 1150
case("noise inside the band passes", BASE, inside, 0, "BASELINE-DIFF PASS")

# A cold start 200 ms slower is past both halves of its band (+30% = 234 ms … so use more).
cold = json.loads(json.dumps(BASE))
cold["cold_start"]["p50"] = 1100
out = case("a slower cold start fails", BASE, cold, 1, "cold start")
assert "cold start → first frame" in out

# Memory headroom: MemAvailable falling 400 MB is past the −200 MB budget.
tight = json.loads(json.dumps(BASE))
tight["min_avail_mb"] = 1100
case("lost memory headroom fails", BASE, tight, 1, "lowest MemAvailable")

# Thermal is a rank: ending at moderate where the baseline ended at light is a regression,
# and so is starting hotter; ending cooler than the baseline is not.
hot = json.loads(json.dumps(BASE))
hot["thermal_end"] = "moderate"
case("a hotter end state fails", BASE, hot, 1, "thermal at end")
cool = json.loads(json.dumps(BASE)); cool["thermal_end"] = "none"   # started no hotter, ended cooler
case("a cooler run passes", BASE, cool, 0, "BASELINE-DIFF PASS")

# A count is not noise: one failed turn where the baseline had none fails.
failed = json.loads(json.dumps(BASE))
failed["failed_turns"] = 1
case("a failed turn fails", BASE, failed, 1, "failed turns")

# A dropped turn is a warning, not a failure: a drop can be the caller hanging up.
dropped = json.loads(json.dumps(BASE))
dropped["dropped_turns"] = 2
out = case("a dropped turn warns but passes", BASE, dropped, 0, "BASELINE-DIFF PASS")
assert "| warn |" in out, out

# A conversational row with fewer than five turns a side is context, not a verdict: two
# replies are one model's answer, not a distribution. It is printed, and the diff passes
# because the rows the build owns are clean.
thin_run = json.loads(json.dumps(BASE))
for k in ("stt", "firstByte", "firstText", "firstAudio"):
    thin_run["latency"][k] = {"p50": BASE["latency"][k]["p50"] * 3, "p95": None, "n": 2}
out = case("a two-turn run leaves the latency rows unjudged", BASE, thin_run, 0,
           "BASELINE-DIFF PASS")
assert "printed as context and not judged" in out, out
assert "| context |" in out

# ...but the same rows at eight turns a side ARE judged, and a 3x slower first audio fails.
assert "| **REGRESSED** |" in run(["BASE", "CAND"], BASE, regressed)[1]

# A row the candidate lost is UNKNOWN even when it is a conversational row: 'not reported' is
# never allowed to become 'context', or a row could silently stop being measured.
for k in ("firstAudio",):
    lost = json.loads(json.dumps(BASE))
    lost["latency"][k] = {"p50": None, "p95": None, "n": 0}
    case(f"a lost conversational row is UNKNOWN ({k})", BASE, lost, 3, "BASELINE-DIFF UNKNOWN")

# 3. Unknown is not healthy. The baseline measured first audio; the candidate's log has no
#    turn rows at all (a truncated export, or the row stopped being logged). Exit 3, named.
silent = json.loads(json.dumps(BASE))
silent["latency"]["firstAudio"] = {"p50": None, "p95": None, "n": 0}
out = case("a row the baseline measured but the candidate lost is UNKNOWN", BASE, silent, 3,
           "BASELINE-DIFF UNKNOWN")
assert "firstAudio" in out

# 4. The excluded row. fullReply is gateway time; if the candidate's is enormous the diff
#    still passes, and says why it was not compared.
slow_reply = json.loads(json.dumps(BASE))
slow_reply["latency"]["fullReply"]["p50"] = 190000
out = case("fullReply is not compared", BASE, slow_reply, 0, "BASELINE-DIFF PASS")
assert "not compared: fullReply" in out

# A row the baseline never measured cannot block — it is a first measurement, printed.
deeper = json.loads(json.dumps(BASE))
deeper["presence_render"] = {"p50": 2100, "p95": 2400, "n": 5}
out = case("a row with no baseline is a first measurement", BASE, deeper, 0, "BASELINE-DIFF PASS")
assert "first measurement" in out

# A row neither run exercised (Presence off on a gate-off baseline) is n/a, not a pass by
# default: the table says so instead of printing a green tick that was never measured.
out = run(["BASE", "CAND"], BASE, dict(BASE))[1]
presence_row = [l for l in out.splitlines() if l.startswith("| presence render")][0]
assert presence_row.endswith("| n/a | neither run measured it |"), presence_row

# 5. Usage. Two arbitrary JSON files must fail loudly, not compare two Nones and pass.
code, out = run(["BASE", "CAND"], {"hello": "world"}, BASE)
assert code == 2 and "not a baseline report" in out, (code, out)
code, out = run(["BASE"], BASE, BASE)
assert code == 2 and "usage" in out, (code, out)

# A field-log row with an absent optional section (no warm legs at all) still compares: the
# baseline's own rows define the set, so there is nothing to require.
thin = {k: v for k, v in BASE.items() if k != "warm"}
case("a baseline with no warm legs still compares", thin, dict(thin), 0, "BASELINE-DIFF PASS")

print("baseline-diff self-test: OK")
