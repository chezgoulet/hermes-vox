#!/usr/bin/env python3
"""Self-test for field-log-turns.py: the definitions the 0.9.1 recount got wrong, pinned."""
import importlib.util
from pathlib import Path

spec = importlib.util.spec_from_file_location("flt", Path(__file__).with_name("field-log-turns.py"))
flt = importlib.util.module_from_spec(spec); spec.loader.exec_module(flt)

LOG = """\
22:00:00.000 [D] turn done: gen=1 resp= len=30 err=
22:02:07.000 [D] event=turn label=turn gen=1 outcome=stream-done fullReply=1800 speechEndToRenderDone=127000 session_turns=1
22:03:00.000 [D] turn done: gen=2 resp= len=1530 err=
22:05:30.000 [D] event=turn label=turn gen=2 outcome=stream-done fullReply=2000 speechEndToRenderDone=152000 session_turns=2
22:06:00.000 [D] turn done: gen=3 resp= len=20 err=
22:06:03.000 [D] event=turn label=turn gen=3 outcome=stream-done fullReply=9000 speechEndToRenderDone=3500 session_turns=3
22:06:04.000 [D] event=turn-dropped reason=stopped gen=4
23:59:59.000 [D] turn done: gen=5 resp= len=40 err=
00:00:40.000 [D] event=turn label=turn gen=5 outcome=stop fullReply=1500 speechEndToRenderDone=- session_turns=4
"""

lines = list(enumerate(LOG.splitlines(), 1))
turns = flt.parse(lines, 15.3)
s = flt.summary(turns, 4000)

# turn-dropped is not a turn (the old \bevent=turn\b matched it).
assert s["turns"] == 4, s
# fullReply is gateway time: gen=3 is a SHORT reply (20 chars) behind a SLOW gateway (9 s),
# and gen=2 is a LONG reply (1,530 chars) behind a FAST one. The two splits must disagree.
assert s["fast_gateway"] == 3 and s["slow_gateway"] == 1, s
assert s["short"] == 3 and s["long"] == 1, s
# Only the short reply at the budget is a stall; the long one's 152 s is its own playback.
assert [t["line"] for t in s["stuck"]] == [2], s["stuck"]
assert len(s["long_render"]) == 1
# Held time crosses midnight in an undated log: 41 s, not -86,359 s.
held = {t["line"]: t["held"] for t in turns}
assert held[9] == 41_000, held
assert sorted(t["line"] for t in s["held"]) == [2, 9], s["held"]
# The estimate is chars / cps.
assert turns[0]["audio_est"] == round(30 / 15.3 * 1000)
print("field-log-turns: 8 checks OK")
