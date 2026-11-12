#!/usr/bin/env python3
"""Self-test for baseline-report.py: a synthetic log in, the baseline numbers out."""
import importlib.util
import io
import json
import tempfile
from contextlib import redirect_stdout
from pathlib import Path

spec = importlib.util.spec_from_file_location("br", Path(__file__).with_name("baseline-report.py"))
br = importlib.util.module_from_spec(spec); spec.loader.exec_module(br)

LOG = """\
D Nemotron-nemotron-stream loaded: threads=4 loadMs=2100 warmMs=380
D SherpaTts loaded: supertonic speakers=10 threads=4 warmMs=640
D event=lat label=cold metric=first-frame ms=812 createMs=1440
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
# The cold row is ColdFrame's own line: a ledger row that also starts `event=lat ` must not
# be read as a cold start, and a log with no cold line reports n=0 rather than a number.
assert r["cold_start"] == {"p50": 812, "p95": 812, "n": 1}, r["cold_start"]
t = br.table(r)
assert "| cold start → first frame p50 / p95 (n=1) | 812 ms / 812 ms |" in t, t
assert "| peak RSS | 2600 MB |" in t and "not in log" not in t.split("first byte")[0].replace("warm", ""), t
empty_r = br.parse(["D event=lat label=speech-to-audio metric=p50 ms=900 n=4"])
assert empty_r["cold_start"] == {"p50": None, "p95": None, "n": 0}, empty_r["cold_start"]
empty = br.table(br.parse([]))
assert "| peak RSS | not in log |" in empty
assert "| cold start → first frame p50 / p95 (n=0) | not in log / not in log |" in empty

# --label is provenance: the row says which build and configuration produced it, and falls
# back to the file's name rather than inventing one.
with tempfile.TemporaryDirectory() as d:
    p = Path(d) / "0.9.1.4-gateoff.log"
    p.write_text(LOG)
    out = io.StringIO()
    with redirect_stdout(out):
        assert br.main(["--json", "--label", "0.9.1.4 gate off, fresh install", str(p)]) == 0
    got = json.loads(out.getvalue())
    assert got["label"] == "0.9.1.4 gate off, fresh install", got["label"]
    assert got["cold_start"]["p50"] == 812
    out = io.StringIO()
    with redirect_stdout(out):
        assert br.main(["--json", str(p)]) == 0
    assert json.loads(out.getvalue())["label"] == "0.9.1.4-gateoff.log"
    # No file is a usage error — never an empty table that reads as healthy.
    with redirect_stdout(io.StringIO()):
        assert br.main(["--json", "--label", "x"]) == 2

print("baseline-report self-test: OK")
