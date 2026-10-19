# Streaming STT A/B — measured on a Pixel 7

Decision D5 says streaming STT is chosen by measurement, not by reputation. This
is that measurement: two sherpa-onnx streaming arms, run on the phone through the
runtime the app ships, in real time, in three noise conditions.

**Bottom line: `sherpa-onnx-streaming-zipformer-en-2023-06-26` (int8) wins and is
the only one of the two that is usable.** The 20M arm is 4.4× faster to load and
~75 ms faster per word, but it scores 20–24 % WER against 2–5 %, and on two of
thirteen clean clips it returns nothing at all. Accuracy dominates: a fast
transcript you have to repeat is slower than a slow transcript you don't.

Both arms are still slow enough that the partial-text UX needs a design decision:
median word-visible latency is ~0.95 s (p95 ~1.45 s) even for the winner.

---

## 1. What was built

| piece | where |
|---|---|
| On-device harness: loads a streaming recognizer, feeds WAV in wall-clock pace, records every partial, finalisation, decode time, feed lag, RSS, CPU, battery counters | `android/bench/` (separate APK `com.hermesvox.bench`, same pinned `sherpa-onnx-1.13.6.aar`) |
| Corpus builder: three noise conditions + word-level ground truth by forced alignment | `tools/sttbench-stream/build_ab_corpus.py` |
| Runner + scorer: adb push/launch/pull, then WER, latency, stability, RTF, RSS, battery | `tools/sttbench-stream/run_ab.py`, `scripts/stt-ab.sh` |
| Raw device results (one JSON per arm × condition, every partial with its timestamp) | `tools/sttbench-stream/results/raw/` — committed, so every table below can be recomputed with `run_ab.py --score-only` |

The bench is a **separate package** on purpose. The Pixel is shared with another
agent; a measurement must never replace, restart or re-sign the copy of Hermes
Vox someone else is using. It also keeps the harness out of the shipping APK —
`android/bench` is not referenced by `:app`.

The harness flags a run as `interrupted` if its activity loses the foreground
(another app brought to front, screen off, task switch) and the host refuses to
score it. That check exists because it fired: an early `tv` run of this study was
invalidated by a concurrent agent launching their own probe app, and the numbers
below are only from runs that passed.

Feed pacing is real time (20 ms chunks), not "decode as fast as possible": a
partial's latency only means something if audio arrives at microphone rate. Each
chunk records its schedule lag; if the phone had fallen behind, the lag column
would say so (measured mean 2.8–5.0 ms, i.e. it kept up).

## 2. Arms

Both are the upstream sherpa-onnx int8 transducer exports — encoder, decoder,
joiner, `tokens.txt` — with `greedy_search`, 4 threads (the app's
`VoxThreads.stt` default: cores/2, floored at 2 and capped at 4, which is 4 on
this phone). Endpoint detection is enabled with a shared 500 ms trailing-silence
rule but only *consulted* after a clip's audio has been fed, so a mid-utterance
pause cannot cut the utterance in half here; it exists to time finalisation.

| arm | artifact | download | on-disk int8 encoder | licence |
|---|---|---|---|---|
| `zip20m` | [`sherpa-onnx-streaming-zipformer-en-20M-2023-02-17`](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2) | 127.9 MB | 42.8 MB | Apache-2.0 |
| `zip0626` | [`sherpa-onnx-streaming-zipformer-en-2023-06-26`](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2) | 179.6 MB | 70.1 MB | Apache-2.0 |

sha256 of the tarballs actually used:

```
9c559283e8498d3fe95913c79ca1cb454bb26281ac2b102b41306c7d752765d9  sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2
74685102caaebb495ab0e9788e8bc29b02a2ec91b0eb5fc08238dcf04ab18c0d  sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2
```

**Supply-chain note.** The second hash does *not* match the pin already in
`tools/sttbench/run_bench.py` (`639e25b5…`), and the tarball this study used is
179.6 MB where the old pin implied a much smaller file. Upstream re-published the
`asr-models` asset for that model name; `run_bench.py --fetch` would now fail its
own checksum. Whichever artifact we bless has to be pinned to what we measured.

The `20M` arm was also run with its fp32 encoder on the host: byte-for-byte the
same (bad) hypotheses, so what follows is a property of that checkpoint, not of
int8 quantization.

## 3. Corpus and conditions

The speech is the existing `tools/sttbench/corpus` (LibriSpeech test-clean CC BY
4.0, EdAcc CC BY-SA 4.0 — same clips, same references, same normalization as the
offline bench): 13 utterances 2.2–31.7 s plus 4 accented utterances, and 2
non-speech clips as hallucination probes. Each speech clip is rendered in three
conditions:

| condition | what it is | honest label |
|---|---|---|
| `quiet` | the clip unchanged | real |
| `tv` | clip + 3-talker babble from three other corpus speakers at 10 dB SNR | **proxy** for a TV in the room, not a recording of a TV |
| `kitchen` | clip + pink noise at 8 dB SNR + 120 ms broadband clatter bursts every ~1.3 s | **proxy** for appliance/dish noise, synthesised |

`accent` is the four EdAcc clips, clean, kept as their own condition because a
small English model is most likely to fall over there and averaging them into
"quiet" would hide it.

Total: 17 speech clips × 3 conditions + 4 accent + 2 silence = 57 clips per arm,
497.5 s of audio per full pass.

### Ground truth word onsets

Partial latency is meaningless without word timings, and the corpus has none, so
they are produced by forced alignment off-device: faster-whisper `small.en`
(word timestamps), aligned to the reference transcript by edit-distance DP.
397/419 reference words (94.7 %) matched; the rest are interpolated between
matched neighbours and marked `how:"interp"` in the manifest.

Two corrections and one check:

- faster-whisper pins the first word of a segment to 0.0; word 0's onset is taken
  from the energy onset instead (exact, and it is the 240–540 ms of leading
  silence that would otherwise be charged to the model).
- Onsets are forced monotonic after that.
- **Onset uncertainty**: the same alignment run with an independently sized
  checkpoint (`base.en`) agrees with `small.en` to a median of **60 ms**
  (p90 140 ms, 77 % within 100 ms) over 127 words. So absolute latency carries
  roughly ±0.1 s of systematic error. Both arms are scored against the *same*
  onsets, so the between-arm comparison is unaffected; the absolute number is
  "~0.9 s, ±0.1 s", not "912 ms".

## 4. Results

Pixel 7 (`2A091FDH200AJ1`), Android as shipped, 4 decode threads, 20 ms chunks,
one pass per cell. Every number below comes from
`tools/sttbench-stream/results/raw/*.json`, scored by `run_ab.py --score-only`.

Latency is **per word**: time from the word's ground-truth onset to the first
partial that contains it (so a word's own duration is included, which is what the
user experiences). "post-word" subtracts the word's end — the model's own delay.
"words seen" is the fraction of reference words that ever appeared in a partial:
a low figure means the latency statistics describe only the easy subset.

| metric | 0626 quiet | 0626 tv | 0626 kitchen | 0626 accent | 20M quiet | 20M tv | 20M kitchen | 20M accent |
|---|---|---|---|---|---|---|---|---|
| WER | **2.0 %** | **5.0 %** | **3.0 %** | **39 %** | 21.0 % | 20.0 % | 24.0 % | 55 % |
| partial latency median | 955 ms | 942 ms | 937 ms | 910 ms | 856 ms | 878 ms | 871 ms | 762 ms |
| p90 | 1328 | 1329 | 1335 | 1284 | 1231 | 1296 | 1269 | 986 |
| p95 | 1451 | 1468 | 1457 | 1449 | 1379 | 1493 | 1408 | 1106 |
| post-word median | 670 | 662 | 656 | 640 | 564 | 578 | 576 | 507 |
| finalisation median | 841 | 725 | 721 | 1037 | 489 | 604 | 491 | 856 |
| finalisation p95 | 1125 | 952 | 1081 | 1180 | 1015 | 889 | 842 | 1125 |
| revisions / word | 1.29 | 1.26 | 1.26 | 1.02 | 0.86 | 0.91 | 0.75 | 0.72 |
| words seen in partials | **99 %** | 97 % | 97 % | 65 % | 61 % | 65 % | 53 % | 46 % |
| RTF | 0.19 | 0.20 | 0.19 | 0.21 | 0.15 | 0.16 | 0.15 | 0.17 |
| RSS peak | 291 MB | 290 MB | 287 MB | 296 MB | 248 MB | 249 MB | 245 MB | 240 MB |
| feed lag mean | 4.2 ms | 5.0 ms | 4.6 ms | 4.5 ms | 3.0 ms | 2.9 ms | 2.9 ms | 3.0 ms |

Cold start (model load in a fresh process, median of 4 runs): **3548 ms** for
`zip0626`, **805 ms** for `zip20m`. CPU: 4046 s vs 3381 s per audio-hour.

### Repeatability

`quiet` was re-run for both arms on the same clips. Final transcripts were
**identical on all 15 clips for both arms**; per-word latency moved by −8 ms
(0626) and +1 ms (20M) at the median, and −1 / +18 ms at p95. RTF was identical to
three decimals. The measurement is stable enough that the 20M-vs-0626 gap is not
noise.

### Paired latency (the fair comparison)

The 20M arm's lower median is partly an artefact of what it drops: the words it
never surfaces are disproportionately the ones with the longest wait. Restricting
both arms to words *both* surfaced, in the same clips:

| condition | words | 0626 median / p95 | 20M median / p95 | median diff |
|---|---|---|---|---|
| quiet | 278 | 965 / 1479 ms | 856 / 1380 ms | −76 ms (20M faster) |
| tv | 282 | 943 / 1508 ms | 877 / 1496 ms | −75 ms |
| kitchen | 267 | 944 / 1484 ms | 871 / 1409 ms | −73 ms |

So the speed difference is real, small, and irrelevant next to the WER gap.

### What the numbers say

1. **`zip0626` is the only viable arm.** 2 % WER on clean read speech, 5 % on
   babble, 3 % on pink-noise clatter, no hallucinations on the two non-speech
   clips, and it surfaces 97–99 % of words in partials. On the same clips the
   offline Whisper base.en default scores 5.6 % (and 32 % on noise) on the host
   bench, so this is not a "streaming costs accuracy" trade so much as a better
   model.
2. **Accented conversational speech is the winner's weak spot.** 39 % WER on the
   four EdAcc clips (host check with the same model and all-at-once decoding:
   37 %, so it is the model, not the phone). Those clips are disfluent,
   conversational and non-native; the model was trained on read speech. This is
   the one result that argues against making streaming the default for a real
   user rather than for a demo — and it needs a larger accent corpus before it is
   a number anyone should plan around.
3. **The 20M arm fails in a specific, reproducible way**: the opening 1–1.5 s of
   every utterance is missing (roughly its 1.28 s left-context window), two of the
   thirteen clean clips return an empty hypothesis and a third returns a single
   letter. Same on the host, same with the fp32 encoder, so it is the checkpoint's
   behaviour rather than the phone or the harness — while its own bundled
   `test_wavs/0.wav` decodes correctly, so the artifact itself is intact. Either
   way it cannot ship: "YOU WILL BE FRANK WITH ME" → `""`.
4. **Finalisation is dominated by the endpoint rule, not the model.** With a
   shared 500 ms trailing-silence rule, the median is 721–841 ms (0626) and
   489–604 ms (20M). The model-dependent tail is small; the win, if we want one,
   is in endpointing, not in the recognizer.
5. **Latency is the product problem, not WER.** ~0.95 s median to first visible
   word, ~1.45 s p95, is too slow for the "words appear as you speak" feel the
   chat UI wants. Both arms are chunk-16/chunk-32 streaming zipformers with
   right-context lookahead; a chunk-8 or left-context-only export would trade a
   little WER for several hundred ms. That is a follow-up measurement, not a
   guess.
6. **Revisions are high** (1.26–1.29 edits per word across successive partials
   for the winner). That is the cost of the low-latency lookahead: the UI will
   show text that changes. Design for it (fade/underline committed-only text)
   rather than pretending partials are final.
7. **Cold start 3.5 s** for the winner is a real first-turn cost, and it is paid
   again after every process death. Preloading at app start (or keeping the
   recognizer alive while the mic screen is open) is worth it.

## 5. Recommendation

**Adopt `sherpa-onnx-streaming-zipformer-en-2023-06-26` (int8) as the streaming
STT arm, pinned to the measured tarball hash.** Ship it behind a setting
("stream partials while you speak"), keep the existing offline Whisper base.en
path as the default final transcript until the latency story is fixed, and run a
second A/B on lower-latency chunk configurations (chunk-8 / left-64 exports)
before switching the default.

Do **not** adopt the 20M arm. It is smaller and faster to load, and that is the
whole of its case; its output is not usable English on this corpus.

Before shipping anything, decide the endpoint rule as a product parameter. The
500 ms rule this study shared is what produces the measured 0.72–0.84 s
finalisation median, and it is the cheapest second to win.

## 6. What was not measured (and would change the answer)

- **Nemotron 0.6B and Vosk were not run.** Both are the other two arms in D5:
  Nemotron needs a 631 MB download and a NVIDIA licence decision, Vosk is a second
  runtime (Maven AAR) — a separate integration, not a config swap. The two sherpa
  arms were done properly instead, as the brief allows. The conclusion above is
  therefore "best of the two sherpa arms", not "best of four".
- **Battery per audio-hour is not measured.** The phone was USB-powered for the
  whole study (`dumpsys battery`: USB powered, charge counter *rising*) and
  `BATTERY_PROPERTY_ENERGY_COUNTER` reported 0. What is reported instead is total
  CPU seconds per audio-hour (0626 4046 s, 20M 3381 s), which ranks the two arms but is not
  a battery number. A real measurement needs an unplugged, screen-off, sustained
  run of 20+ minutes per arm.
- **"TV" and "kitchen" are synthetic proxies** (babble at 10 dB, pink+clatter at
  8 dB). They are reproducible and comparable, not field recordings. A real
  kitchen is impulsive and non-stationary in ways pink noise is not.
- **Onsets carry ~±100 ms of systematic error** (see §3). Between-arm comparison
  is unaffected; absolute latency claims are not precise to the millisecond.
- **One pass per cell**, plus a repeat of `quiet` for both arms (§4, repeatability).
  The repeat is the only evidence on run-to-run variance; p95 rests on ~270–1000
  word observations depending on cell.
- **Only one device, one thread count (4), one chunk size (20 ms), one endpoint
  rule (500 ms).** The Pixel 7 is the target; a slower phone would change RTF and
  possibly the feed-lag picture.
- **No barge-in / VAD interaction.** This measures the recognizer in isolation
  (file → recognizer), not the app's mic path with Silero VAD gating and TTS
  echo. The app-level numbers will be worse than these, not better.
- **Accented speech is measured on 4 clips only** — enough to show direction, not
  enough for a WER claim.

## 7. Reproducing

```bash
# once: extract the two arm tarballs somewhere, e.g. /home/c/stt-ab-models
python3 tools/sttbench-stream/build_ab_corpus.py --align   # needs faster-whisper
scripts/stt-ab.sh --arms zip20m,zip0626 --conditions quiet,tv,kitchen,accent
```

`scripts/stt-ab.sh` builds `:bench`, installs it under the phone lock, pushes the
corpus and model directories, runs every cell, prints the table and leaves Hermes
Vox back in the foreground. Device runs are real time: a full pass is ~25 minutes
for two arms.
