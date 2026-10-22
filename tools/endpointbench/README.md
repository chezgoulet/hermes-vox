# endpointbench — when does a turn end? (#229)

Replays the sttbench corpus (22 clips with speech; `../sttbench/corpus`) frame by frame against
an endpointer, offline, on every build (`EndpointBenchTest`). Measured per endpointer: **false
endpoints** (a commit before the speaker is done — worse than a slow one, the issue says) and
the **median wait after the last word** before a correct commit.

`make_traces.py` builds the fixture (`android/app/src/test/resources/endpointbench/traces.json`):
100 ms frames, Silero VAD segments (the app's VAD; `min_silence_duration` 0.1 s so the trace,
not the VAD, holds the pauses) and the partial text of a streaming recogniser —
`sherpa-onnx-streaming-zipformer-en-2023-06-26` (Apache-2.0), fed as the app feeds it. 3 s of
trailing silence per clip.

```
python3 -I tools/endpointbench/make_traces.py --zipformer <dir> --silero <silero_vad.onnx>
```

## Result (2026-10-22)

The issue's cheaper arm — no new weights; a judgement over the pause, the words and the partial's
stability (`SemanticEndpoint`) — against today's fixed 800 ms pause:

| endpointer | false endpoints | median after last word |
|---|---|---|
| 800 ms fixed (today) | 6 | 900 ms |
| PATIENT (stable 400 ms) | 4 | 1000 ms |
| BALANCED (stable 300 ms) | 6 | 800 ms |
| EAGER (stable 200 ms) | 11 | 500 ms |

**What it says.** The arm is a dial, not a win: at equal accuracy it is 100 ms faster, and every
setting that commits much sooner also commits wrongly much more often. The feature that carries
it is the partial's stability; the dangling-word rule moves it little. D18's p50 ≤ 700 ms is not
reached at today's accuracy.

**What it cannot say.** The corpus is read speech (LibriSpeech, EdAcc), whose pauses are longer
and more regular than conversation's; two clips carry an inserted 1.4 s thinking pause. The
decision between this arm and a small dedicated model (Phoenix-VAD-style) needs **conversational
recordings** and the device's own STT cadence.

**Not wired into the app.** Stability needs a fresh partial about every 100 ms; the capture
loop's partial worker runs every 900 ms (#235), so wired in today the endpointer would behave as
a different, worse one than the one measured. It goes in behind `SemanticEndpoint.KEY` when the
live stream's partials arrive at the bench's cadence.
