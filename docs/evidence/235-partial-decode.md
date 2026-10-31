# #235 — decoded audio-seconds per partial utterance

**Outcome of this lane: 1 — the fix is already in `testing`, and this lane adds the
measurement the issue asks for.** The live-stream partial path landed in PR #300
(`40dff4f`, merged `7bd2909`, 2026-10-21) before the device-free sprint was filed; the
capture loop already calls `LiveStt.feedLive()` with only the audio it has not fed. This
lane changes no production code. It adds the deterministic acceptance harness
(`PartialDecodeLinearTest`) and records the numbers below, which were missing.

Measured on `fix/235-streaming-partials` from `origin/testing` @ `df78f4e`, JDK 17, no
device, no model, no wall clock.

## What the harness measures

`android/app/src/test/java/com/hermesvox/PartialDecodeLinearTest.kt` drives the real
`StreamingTranscriber` / `LiveUtterance` code over a fixed synthetic source. A fake
`StreamDecoder` counts every sample it is handed; the real recogniser decodes exactly what
it is given, so sample count is the model-independent cost unit for "decoded
audio-seconds".

* **live** — one stream per utterance, `feed()` gets only the delta since the previous
  tick, `finishLive()` gets the remainder: the #235 shape.
* **legacy** — every tick calls `transcribeClip()` on the whole buffer so far: the shape
  before #235, used as the control.
* Both paths add a 500 ms endpoint tail at the end; tails are counted separately and
  excluded from the ratio, so **ratio = utterance audio decoded ÷ utterance length**.
  Ratio 1.00 means every sample was decoded once.
* Ticks use the app's own cadences: `ContinuousCapture.LEGACY_PARTIAL_MS` (900 ms) and
  `ContinuousCapture.PARTIAL_MS` (100 ms, continuous capture / #228).

Command:

```
cd android
JAVA_HOME=$HOME/jdk-17 ./gradlew --no-daemon testDebugUnitTest \
  --tests 'com.hermesvox.PartialDecodeLinearTest'
```

## Numbers

Two-minute turn is the issue's acceptance; 15/30/60 s are there to show the curve.

| utterance | live decoded | live ratio | legacy decoded | legacy ratio |
|---:|---:|---:|---:|---:|
| 15 s | 15.0 s | **1.00** | 122.4 s | 8.16 |
| 30 s | 30.0 s | **1.00** | 504.9 s | 16.83 |
| 60 s | 60.0 s | **1.00** | 1 989.9 s | 33.17 |
| 120 s | 120.0 s | **1.00** | 8 019.9 s | 66.83 |

At the continuous-capture cadence the live path is unchanged (15/30/60/120 s all decode
15.0/30.0/60.0/120.0 s, ratio 1.00); the legacy worker would be ten times worse still.

Analytic check: with a 0.9 s cadence the legacy worker decodes
`0.9 × m(m+1)/2` seconds for `m = L/0.9` ticks, so its ratio is `≈ L/(2 × 0.9)` — 66.7 at
120 s, matching the measured 66.83. Doubling the utterance doubles the ratio: quadratic.
The live ratio is 1.00 at every length: linear.

## Assertions (the same rule on both paths)

* Live: ratio within **1 %** of 1.00 at 15, 30, 60 and 120 s, and the spread across those
  lengths ≤ 1 % — the per-second cost does not move with length. The two-minute turn is
  asserted to decode exactly 120.0 s of audio, which is the instrumented acceptance.
* Legacy: the same ratio check (≤ 1.01) must **fail** at every length, and each doubling
  must roughly double the cost (factor in 1.8–2.2). This is the harness's own control:
  a harness that could not distinguish the two shapes fails the suite.
* Test run: 4 tests, 0 skipped, 0 failures (`PartialDecodeLinearTest.xml`,
  2026-10-31).

The suite also fails on any duplicated or dropped feed: `StreamingTranscriberTest` already
pins sample order and chunking, and `LiveUtterance.lastFinishedFed` is asserted equal to
the utterance length.

## Production instrument

The shipped path logs the same fact on device without a harness:

* `event=stt-live seg=… fedMs=… restMs=… finishMs=…` — `fedMs` is the utterance audio
  decoded for that segment, tail excluded. `fedMs` equal to the segment's length is the
  linear claim on a real call.
* `event=stt-rtf seg=… audioMs=… feedDecodeMs=… rtf=… feeds=… cadenceMs=…` — the
  recogniser's own time against the audio it decoded.
* `event=stt-live-fallback …` — the one linear-but-slower path left: if the partial worker
  cannot hand over within 2 s, the turn is decoded whole once, never per tick.

## What this does not prove

Real-model wall-clock RTF, memory, and accuracy on a phone. Those need hardware (#179,
#208, #209) and are unchanged by this note. What is proven here is the acceptance in #235
as written — decoded audio-seconds per utterance are linear in the utterance's length — and
that the old snapshot worker is measurably quadratic under the same harness.

## Why no loop rewrite

The shipped fix does not restructure `listen()`: it keeps the one partial block and swaps
the snapshot for a delta feed plus a live finish. The VAD thresholds, endpointing, reply
lifecycle, barge path and presence guard are untouched. #228 (continuous capture) is also
already merged (`feat/continuous-capture`, PR #319), so the 100 ms cadence the issue pairs
with #235 is measured above rather than deferred.
