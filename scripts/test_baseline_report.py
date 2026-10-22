#!/usr/bin/env python3
"""Self-test for baseline-report.py: a synthetic log in, the baseline numbers out."""
import importlib.util
from pathlib import Path

spec = importlib.util.spec_from_file_location("br", Path(__file__).with_name("baseline-report.py"))
br = importlib.util.module_from_spec(spec); spec.loader.exec_module(br)

LOG = """\
D Nemotron-nemotron-stream loaded: threads=4 loadMs=2100 warmMs=380
D SherpaTts loaded: supertonic speakers=10 threads=4 warmMs=640
D event=res rssMb=1800 availMb=3100 totalMb=7680 low=false thermal=none
D event=turn label=rt gen=1 outcome=stream-done stt=420 firstByte=300 firstText=900 firstAudio=1300 fullReply=4000 session_turns=1
D event=er-soul kind=lag decision=spoken chars=20 heard=false mood=- ms=2300
D event=res rssMb=2600 availMb=900 totalMb=7680 low=true thermal=light
D event=turn label=rt gen=2 outcome=reply-error stt=380 firstByte=280 firstText=800 firstAudio=1100 fullReply=3500 session_turns=2
D event=turn-dropped reason=stopped gen=3
D event=res-summary peakRssMb=2600 minAvailMb=900 thermalStart=none thermalEnd=light samples=2 ms=60000
"""

r = br.parse(LOG.splitlines())
assert r["turns"] == 2, r
assert r["latency"]["firstAudio"] == {"p50": 1100, "p95": 1100, "n": 2}, r["latency"]
assert r["presence_render"]["n"] == 1 and r["presence_render"]["p50"] == 2300
assert r["peak_rss_mb"] == 2600 and r["min_avail_mb"] == 900
assert (r["thermal_start"], r["thermal_end"]) == ("none", "light")
assert r["warm"]["Nemotron-nemotron-stream"] == {"loadMs": 2100, "warmMs": 380}, r["warm"]
assert r["dropped_turns"] == 1 and r["calls"] == 1 and r["failed_turns"] == 1, r
t = br.table(r)
assert "| peak RSS | 2600 MB |" in t and "not in log" not in t.split("first byte")[0].replace("warm", ""), t
empty = br.table(br.parse([]))
assert "| peak RSS | not in log |" in empty
print("baseline-report self-test: OK")
