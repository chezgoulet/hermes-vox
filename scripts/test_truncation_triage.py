#!/usr/bin/env python3
"""Self-test for truncation-triage.py: each cause, from the lines that decide it."""
import importlib.util
from pathlib import Path

spec = importlib.util.spec_from_file_location("tt", Path(__file__).with_name("truncation-triage.py"))
tt = importlib.util.module_from_spec(spec); spec.loader.exec_module(tt)

def v(log): return [x[0] for x in tt.triage(log.strip().splitlines())]

# The user cut in: not a truncation.
assert v("""
D supertonic chunk 44100 smp @44100Hz (20 ch)
D event=tts-stop reason=barge-in msSinceBarge=40 cancelToSilenceMs=3
D event=reveal-freeze reason=barge-in chars=10 of=60
D event=reply-end gen=4 reason=cancelled cause=barge-in
""") == ["user-cut"]

# The order #240 writes a cut in: reply-end first, then the stop, then the freeze; then the
# next reply starts. Two replies, the first a user cut, the second heard whole.
assert v("""
D supertonic chunk 44100 smp @44100Hz (20 ch)
D event=reply-end gen=4 reason=cancelled cause=hush
D event=tts-stop reason=hush msSinceBarge=0 cancelToSilenceMs=2
D event=reveal-freeze reason=hush chars=9 of=40
D supertonic chunk 88200 smp @44100Hz (30 ch)
D event=reveal-freeze reason=done chars=30 of=30
D event=tts-retire gen=5 chunks=1 ms=2100
""") == ["user-cut", "heard-whole"]

# A worker break nobody asked for: the retirement bug.
assert v("""
D supertonic chunk 44100 smp @44100Hz (20 ch)
D event=tts-stop gen=5 worker-break chunkIdx=2
D event=reveal-freeze reason=stop chars=20 of=60
D event=tts-retire gen=5 chunks=1 ms=900
""") == ["retirement"]

# Frozen short although every chunk was synthesized: the audio was cut.
assert v("""
D supertonic chunk 44100 smp @44100Hz (20 ch)
D supertonic chunk 44100 smp @44100Hz (20 ch)
D event=reveal-freeze reason=stop chars=22 of=40
D event=tts-retire gen=6 chunks=2 ms=2100
""") == ["retirement"]

# Heard whole.
assert v("""
D supertonic chunk 88200 smp @44100Hz (30 ch)
D event=reveal-freeze reason=done chars=30 of=30
D event=tts-retire gen=7 chunks=1 ms=2100
""") == ["heard-whole"]

# A chunk far too short for its text: the synthesizer.
assert v("""
D supertonic chunk 4410 smp @44100Hz (60 ch)
D event=tts-retire gen=8 chunks=1 ms=300
""") == ["synthesis"]

# Not enough to say.
assert v("""
D event=tts-stop gen=9 reason=stop-unknown
""")[0] in ("not-enough-log", "user-cut")
print("truncation-triage self-test: OK")
