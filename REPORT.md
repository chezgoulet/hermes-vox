# REPORT — streaming STT A/B (D5), Pixel 7

Branch `feat/stt`. Gate: `scripts/gate.sh` → **GATE-GREEN** (parity over 10
fixtures, with `:bench` in the build). Nothing pushed.

## Outcome

Two sherpa-onnx streaming arms built, measured on the Pixel 7 in three noise
conditions plus accented speech, with a harness that records every partial's
arrival time. **`sherpa-onnx-streaming-zipformer-en-2023-06-26` (int8) is the
recommendation; the 20M arm is unusable.**

| | 0626 quiet | 0626 tv | 0626 kitchen | 0626 accent | 20M quiet | 20M tv | 20M kitchen | 20M accent |
|---|---|---|---|---|---|---|---|---|
| WER | **2 %** | **5 %** | **3 %** | 39 % | 21 % | 20 % | 24 % | 55 % |
| word latency median | 955 ms | 942 ms | 937 ms | 910 ms | 856 ms | 878 ms | 871 ms | 762 ms |
| word latency p95 | 1451 | 1468 | 1457 | 1449 | 1379 | 1493 | 1408 | 1106 |
| finalisation median | 841 | 725 | 721 | 1037 | 489 | 604 | 491 | 856 |
| revisions / word | 1.29 | 1.26 | 1.26 | 1.02 | 0.86 | 0.91 | 0.75 | 0.72 |
| words seen in partials | 99 % | 97 % | 97 % | 65 % | 61 % | 65 % | 53 % | 46 % |
| RTF | 0.19 | 0.20 | 0.19 | 0.21 | 0.15 | 0.16 | 0.15 | 0.17 |
| RSS peak | 291 MB | 290 MB | 287 MB | 296 MB | 248 MB | 249 MB | 245 MB | 240 MB |

Repeat: quiet re-run for both arms gave identical transcripts on all 15 clips,
median latency ±8 ms, RTF identical to three decimals.
| cold start | 3548 ms | | | | 805 ms | | | |
| CPU s / audio-hour | 4046 | | | | 3381 | | | |

Paired on words both arms surfaced: 20M is ~75 ms faster (856 vs 965 median,
quiet) — real, small, and swamped by 19 points of WER. The full analysis,
method and caveats are in `docs/STT-AB.md`.

## What I built

- `android/bench/` — a **separate** APK (`com.hermesvox.bench`) that loads the
  same pinned sherpa-onnx AAR, feeds WAV in real time (20 ms chunks), and records
  every partial (audio time + wall time), finalisation, decode ms, feed lag, RSS,
  CPU ticks and battery counters to JSON. It marks a run `interrupted` if the
  activity loses the foreground, and the host refuses to score those. Separate
  package so a measurement never replaces the Hermes Vox install on the shared
  Pixel. Not referenced by `:app`; `settings.gradle` gains `include ':bench'`.
- `tools/sttbench-stream/` — corpus builder (three conditions + word-onset ground
  truth by forced alignment, ±60 ms cross-check), adb runner/scorer (WER, latency
  percentiles, paired latency, stability, RTF, RSS, CPU), onset-stability check.
- `scripts/stt-ab.sh` — build → install under `phone.lock` → run all cells →
  restore Hermes Vox to the foreground.
- `docs/STT-AB.md` — numbers, method, recommendation, gaps.

## Things that went wrong (and are fixed / documented)

1. **Endpoint rule3 was misconfigured** (trailing-silence threshold with 0 minimum
   utterance length), which is not what sherpa means by rule3; it fired on the
   first silent chunk. Fixed to sherpa's default shape.
2. **Results were written next to the config**, so the host polled the wrong
   directory and the first run looked hung. Now always `<files>/results`.
3. **Shared-phone contention is real**: another agent's benchmarks took the
   foreground twice and invalidated runs (correctly flagged), and their CPU load
   inflated one run's wall time. The runner now waits for the phone to go quiet,
   retries on interruption, and reports feed lag so a throttled run is visible.
4. **Upstream artifact drift**: the `2023-06-26` tarball's sha256
   (`74685102…`) no longer matches the pin in `tools/sttbench/run_bench.py`
   (`639e25b5…`); upstream re-published the asset. `run_bench.py --fetch` would
   now fail. The doc records the hash actually measured.

## Not measured (honest gaps)

- **Nemotron 0.6B and Vosk arms were not run.** Vosk is a second runtime, Nemotron
  a 631 MB NVIDIA-licensed download; the brief allows doing the two sherpa arms
  properly instead, which is what happened. The recommendation is "best of the two
  sherpa arms", not "best of four".
- **Battery per audio-hour**: the phone was USB-powered, `ENERGY_COUNTER` read 0
  and the charge counter rose. CPU-seconds per audio-hour is reported instead.
- **TV/kitchen are synthetic proxies** (babble @10 dB, pink+clatter @8 dB), not
  field recordings.
- **Word onsets carry ~±100 ms** systematic error (two-checkpoint agreement: 60 ms
  median); between-arm comparisons are unaffected, absolute latency is not
  precise to the millisecond.
- **One device, 4 threads, 20 ms chunks, one endpoint rule (500 ms)**; one pass per
  cell, so run-to-run variance is only partly characterised.
- **Recognizer in isolation**: file → recognizer, not the app's mic path with
  Silero VAD gating and TTS echo. Real app numbers will be worse.
- Accent result rests on 4 clips.

## Commits

```
ad7c8b0 bench: on-device streaming-STT A/B harness
2d2a482 sttbench-stream: corpus conditions, word-onset ground truth and A/B scoring
98f06d6 bench: write results under files/results, fix the endpoint rule3 default
bece647 docs: STT-AB results on the Pixel 7 with the evidence committed
9826a3e sttbench-stream: commit the run configs and the scored summary
```
(plus a final docs touch-up commit)
