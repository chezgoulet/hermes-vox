# Streaming STT A/B — measured on a Pixel 7

Decision D5 says streaming STT is chosen by measurement, not by reputation. This
is that measurement: four sherpa-onnx streaming arms, run on the phone through the
runtime the app ships, in real time, in three noise conditions plus accented
conversational speech.

**Bottom line: `nemotron-speech-streaming-en-0.6b` at the 160 ms chunk is both the
accurate arm and the fast one — 1.4 % / 2.6 % / 1.7 % WER (quiet / TV / kitchen)
against the zipformer's 2.0 % / 4.5 % / 2.8 %, 11.9 % on accented speech against
38.8 %, and a word-visible median of 707 ms against 956 ms. It is also 3.1× the
CPU, 3.1× the memory and 3.5× the model download.** The zipformer remains the arm
for devices that cannot afford those resources; the 20M arm is still unusable.

The accent result is the one that changes a decision. At 39 % WER the zipformer
was disqualified for a real user with an accent (this document said so); at 12 %
Nemotron is merely imperfect. Everything else it wins is a bonus on top of that.

---

## 1. What was built

| piece | where |
|---|---|
| On-device harness: loads a streaming recognizer, feeds WAV in wall-clock pace, records every partial, finalisation, decode time, feed lag, RSS, CPU, battery counters | `android/bench/` (separate APK `com.hermesvox.bench`, same pinned `sherpa-onnx-1.13.6.aar`) |
| Corpus builder: three noise conditions + word-level ground truth by forced alignment | `tools/sttbench-stream/build_ab_corpus.py` |
| Runner + scorer: adb push/launch/pull, then WER, latency, stability, RTF, RSS, CPU, battery; `--fetch` downloads, verifies and extracts the arm tarballs | `tools/sttbench-stream/run_ab.py`, `scripts/stt-ab.sh` |
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
would say so. It does, for Nemotron — see §4.4.

## 2. Arms

All four are upstream sherpa-onnx int8 exports — encoder, decoder, joiner,
`tokens.txt` — decoded with `greedy_search` on 4 threads (the app's
`VoxThreads.stt` default: cores/2, floored at 2 and capped at 4, which is 4 on
this phone). Endpoint detection is enabled with a shared 500 ms trailing-silence
rule but only *consulted* after a clip's audio has been fed, so a mid-utterance
pause cannot cut the utterance in half here; it exists to time finalisation.

| arm | artifact | tarball | on-disk int8 encoder | licence |
|---|---|---|---|---|
| `zip20m` | [`sherpa-onnx-streaming-zipformer-en-20M-2023-02-17`](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2) | 127.9 MB | 42.8 MB | Apache-2.0 |
| `zip0626` | [`sherpa-onnx-streaming-zipformer-en-2023-06-26`](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2) | 310.4 MB | 70.1 MB | Apache-2.0 |
| `nemotron160` | [`sherpa-onnx-nemotron-speech-streaming-en-0.6b-160ms-int8-2026-04-25`](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemotron-speech-streaming-en-0.6b-160ms-int8-2026-04-25.tar.bz2) | 463.9 MB | 652.9 MB | NVIDIA Open Model License |
| `nemotron560` | [`sherpa-onnx-nemotron-speech-streaming-en-0.6b-560ms-int8-2026-04-25`](https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemotron-speech-streaming-en-0.6b-560ms-int8-2026-04-25.tar.bz2) | 463.9 MB | 652.9 MB | NVIDIA Open Model License |

sha256 and byte size of the tarballs actually measured (both verified by
`run_ab.py --fetch`, which refuses a mismatch on either):

```
9c559283e8498d3fe95913c79ca1cb454bb26281ac2b102b41306c7d752765d9   127887156  sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2
639e25b578e9e997131402199419c13a941f8e4e198e2da1ce57dbf5cf401282   310414022  sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2
0ae73a41cd51599dc7cac9ac083d9d35de53d762ca45923505fde47a3751814b   463945198  sherpa-onnx-nemotron-speech-streaming-en-0.6b-160ms-int8-2026-04-25.tar.bz2
78e2b79fcf7271553a74402a76b771b09ea40117a39566a79f52235b23db6358   463945051  sherpa-onnx-nemotron-speech-streaming-en-0.6b-560ms-int8-2026-04-25.tar.bz2
```

### 2.1 Nemotron and the pinned runtime

The pin is `sherpa-onnx-1.13.6.aar` (sha256 `0012d9a2…1698`, verified before
every build by `scripts/fetch-runtime.sh`). The question was whether a runtime
pinned in August exposes a model released in April. Inspecting the artifact
rather than assuming:

- `classes.jar` contains `com.k2fsa.sherpa.onnx.OnlineNeMoCtcModelConfig` — that
  is NeMo **CTC**, which is not what Nemotron is.
- `libsherpa-onnx-c-api.so` (arm64-v8a) contains the strings
  `online-recognizer-transducer-nemo-impl.h`, `online-transducer-nemo-model.cc`,
  `online-transducer-greedy-search-nemo-decoder.cc` and the model-type token
  `nemo_transducer`. The **NeMo streaming transducer** path is compiled in.
- Nemotron support landed for v1.13.0 (PR k2-fsa/sherpa-onnx#3555, released
  2026-04-28); 1.13.6 is 2026-08-18. The pin is fine and was **not** bumped to get
  this arm.

The Kotlin API takes these models through the ordinary
`OnlineTransducerModelConfig` — no `model_type` string is needed; the runtime
detects the NeMo decoder by its output-node count. The harness needed no code
change at all: only the host-side file glob, because NeMo exports are
`encoder.int8.onnx` while zipformer exports are
`encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx`.

This was then confirmed on the device, before the full pass: a fresh-process
decode of `short_01.wav` returned `"You will be frank with me. I always am"` at
2 986 ms load and RTF 0.64. "1.13.6 exposes the NeMo online path" is a
device-verified fact here, not a symbol-table inference.

### 2.2 Model artifact and licence

- Upstream model: [`nvidia/nemotron-speech-streaming-en-0.6b`](https://huggingface.co/nvidia/nemotron-speech-streaming-en-0.6b)
  — 600 M FastConformer cache-aware RNNT, English (US), released 2026-03-13.
- ONNX export measured: k2-fsa's `...-160ms-int8-2026-04-25`, tarball sha256
  `0ae73a41…814b`, 463 945 198 bytes. Inside: `encoder.int8.onnx`
  (652 916 849 B, sha256 `71111f61…1209`), `decoder.int8.onnx` (7 257 753 B),
  `joiner.int8.onnx` (1 735 862 B), `tokens.txt` (8 952 B). The 560 ms export
  shares decoder, joiner and tokens byte-for-byte and differs only in the encoder
  (`7d932213…09d8`).
- Chunk size is baked into the encoder, so the four advertised operating points
  (80 / 160 / 560 / 1120 ms) are four separate downloads, not a runtime flag.
  Two were measured (§4); 160 ms won on this phone.
- Licence: **NVIDIA Open Model License Agreement** (`license: other`,
  `license_name: nvidia-open-model-license` on the model card). Commercial use
  and derivative models are permitted; outputs are not NVIDIA's. Two conditions
  matter here: **if we distribute the model — or a derivative, which the ONNX
  export is — we must ship a copy of the agreement and a `NOTICE` file reading
  "Licensed by NVIDIA Corporation under the NVIDIA Open Model License"**, and use
  must be consistent with NVIDIA's Trustworthy AI terms. The licence is
  revocable.
- **We do not redistribute the weights.** The app downloads them at runtime, so
  the obligation lands on the download screen rather than the APK: show the
  licence, the attribution string and the model-card link before a 464 MB
  download, and record acceptance. The upstream tarball itself ships **no LICENSE
  file** — only a 158-byte README pointing at the model card — so anything we
  mirror has to add the agreement and the NOTICE ourselves.

**Supply-chain note, corrected.** The previous revision of this document recorded
`74685102…` as the sha256 of the 2023-06-26 tarball and claimed upstream had
re-published the asset at 179.6 MB, leaving `run_bench.py`'s pin stale. That was
wrong, and the error was in the document, not upstream:

- a fresh download on 2026-10-19 from the pinned URL is **310 414 022 bytes** and
  hashes to **`639e25b5…`** — exactly the pin already in
  `tools/sttbench/run_bench.py`, and the size the GitHub release API reports for
  that asset (`updated_at` 2024-09-11);
- the model directory every 0626 number in this document was measured from was
  extracted from that tarball at 12:57 on the day of the run, and its
  `encoder-…int8.onnx` is byte-identical (`5022b2ec…`) to the one inside it.

So nothing needed re-pinning: `74685102…` was a transcription error in the
earlier write-up. `run_bench.py` now additionally carries the expected byte size
as a second witness, and `run_ab.py --fetch` verifies both for all four streaming
arms. Anyone who tried to reproduce the earlier run and hit a checksum failure
was hitting a bad documented hash, not a changed artifact; with the corrected
hash the reproduction is exact.

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

**One comparability correction.** The runs added for Nemotron select every clip
whose `condition` matches, which means the four accented clips also appear inside
the `quiet`, `tv` and `kitchen` cells — that is why those cells took 164 s rather
than 143 s. The earlier zipformer runs did not include them there. To compare
like with like, **every number in §4 is scored on the same 13 read-speech clips
and the same 4 accent clips for all four arms**; accented clips are removed from
the read-speech cells at scoring time. The scorer was not changed, and the raw
JSON keeps the full cells.

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
  roughly ±0.1 s of systematic error. All arms are scored against the *same*
  onsets, so the between-arm comparison is unaffected; the absolute number is
  "~0.7–0.9 s, ±0.1 s", not "707 ms".

## 4. Results

Pixel 7 (`2A091FDH200AJ1`), Android as shipped, 4 decode threads, 20 ms chunks,
one pass per cell. Every number below comes from
`tools/sttbench-stream/results/raw/*.json`, scored by `run_ab.py --score-only`
(restricted to the common clip set as described in §3).

Latency is **per word**: time from the word's ground-truth onset to the first
partial that contains it (so a word's own duration is included, which is what the
user experiences). "post-word" subtracts the word's end — the model's own delay.
"words seen" is the fraction of reference words that ever appeared in a partial:
a low figure means the latency statistics describe only the easy subset.

### 4.1 Read speech, all four arms

| metric | 0626 quiet | 0626 tv | 0626 kitchen | 20M quiet | 20M tv | 20M kitchen | Nem160 quiet | Nem160 tv | Nem160 kitchen | Nem560 quiet | Nem560 tv | Nem560 kitchen |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| WER | 2.0 % | 4.5 % | 2.8 % | 20.7 % | 20.2 % | 24.1 % | **1.4 %** | **2.6 %** | **1.7 %** | 2.8 %\* | 3.1 %\* | 2.3 % |
| partial latency median | 956 ms | 942 ms | 937 ms | 856 ms | 878 ms | 871 ms | **707 ms** | **798 ms** | **778 ms** | 906 ms | 955 ms | 988 ms |
| p90 | 1328 | 1329 | 1335 | 1231 | 1296 | 1269 | 1043 | 1208 | 1226 | 1284 | 1353 | 1373 |
| p95 | 1451 | 1468 | 1457 | 1379 | 1493 | 1408 | **1170** | 1426 | 1827 | 1406 | 1460 | 1480 |
| post-word median | 670 | 662 | 656 | 564 | 578 | 576 | **390** | 481 | 459 | 601 | 678 | 686 |
| finalisation median | 841 | 725 | 721 | 489 | 604 | 491 | 137 | 150 | 263 | 253 | 307 | 442 |
| revisions / word | 1.29 | 1.26 | 1.26 | 0.86 | 0.91 | 0.75 | 1.21 | 1.20 | 1.17 | 1.00 | 1.00 | 1.05 |
| words seen in partials | 99.0 % | 96.6 % | 97.2 % | 61.0 % | 65.0 % | 52.5 % | **99.4 %** | 97.4 % | 98.3 % | 94.6 % | 94.5 % | 98.8 % |
| RTF | 0.19 | 0.20 | 0.19 | 0.15 | 0.16 | 0.15 | 0.68 | 0.73 | 0.76 | 0.30 | 0.30 | 0.37 |
| RSS peak | 291 MB | 290 MB | 287 MB | 248 MB | 249 MB | 245 MB | 903 MB | 904 MB | 905 MB | 902 MB | 904 MB | 901 MB |
| feed lag mean | 4.2 ms | 5.0 ms | 4.6 ms | 3.0 ms | 2.9 ms | 2.9 ms | 30 ms | 94 ms | 102 ms | 23 ms | 23 ms | 36 ms |

\* Nem560's quiet and tv WER include one clip (`short_02`, 4 errors over 7
reference words) that the endpoint rule truncated mid-sentence — see §4.4.
Excluding it, Nem560 is 1.6 % / 2.0 % / 2.3 %, i.e. level with Nem160.

### 4.2 Accented conversational speech

| metric | 0626 | 20M | Nem160 | Nem560 |
|---|---|---|---|---|
| WER | 38.8 % (26/67) | 55.2 % (37/67) | **11.9 % (8/67)** | 16.4 % (11/67) |
| partial latency median | 910 ms | 762 ms | 900 ms | 1088 ms |
| p95 | 1449 | 1106 | 1155 | 1439 |
| post-word median | 640 | 507 | 622 | 840 |
| finalisation median | 1037 | 856 | 699 | 927 |
| revisions / word | 1.02 | 0.72 | 0.99 | 0.92 |
| words seen in partials | 65.4 % | 45.7 % | 88.7 % | 84.9 % |
| RTF | 0.21 | 0.17 | 0.74 | 0.34 |
| RSS peak | 296 MB | 240 MB | 898 MB | 897 MB |

Per clip:

| clip | 0626 | 20M | Nem160 | Nem560 |
|---|---|---|---|---|
| accent_01 | 15 % | 40 % | 5 % | 5 % |
| accent_02 | 38 % | 38 % | 6 % | 6 % |
| accent_03 | 65 % | 85 % | 25 % | 40 % |
| accent_04 | 36 % | 55 % | 9 % | 9 % |

### 4.3 Cost and startup

| | 0626 | 20M | Nem160 | Nem560 |
|---|---|---|---|---|
| cold start (model load, fresh process, median of 4) | 3 548 ms | 805 ms | 2 969 ms | 2 940 ms |
| CPU s per audio-hour | 3 719 | 3 179 | **11 572** | 4 821 |
| peak RSS | 291 MB | 248 MB | 903 MB | 902 MB |
| download / on-disk int8 | 310 MB / 70 MB | 128 MB / 43 MB | 464 MB / 663 MB | 464 MB / 663 MB |

Cold start varies per cell (Nem160 2 936–3 032 ms); the table uses the median.
CPU is summed from per-clip `/proc/self/stat` ticks over the same clips, so it is
comparable across arms; it is not a battery number (the phone was on USB power
for every run and `ENERGY_COUNTER` read 0 — see §6).

### 4.4 What the numbers say

1. **Nemotron at 160 ms wins every read-speech condition**, and not by a hair:
   1.4 % vs 2.0 % clean, 2.6 % vs 4.5 % in babble, 1.7 % vs 2.8 % in pink-noise
   clatter. It also wins latency (707 ms vs 956 ms median word, 1170 vs 1451
   p95), revisions (1.21 vs 1.29 per word) and partial coverage (99.4 % vs
   99.0 %). There is no accuracy-for-latency trade here: it is better at both.
2. **The accent gap is the real result.** 38.8 % → 11.9 %, spread across all four
   clips (15→5, 38→6, 65→25, 36→9), not one lucky clip. This was the number that
   argued against shipping streaming to a real user; at 12 % it argues much less.
   Four clips is still four clips — treat it as a direction, not a precise WER.
3. **It costs ~3× the machine.** RTF 0.68–0.76 against 0.19–0.20, 11.6 k vs
   3.7 k CPU-seconds per audio-hour, 903 MB vs 291 MB RSS, 663 MB of weights on
   disk. On a Pixel 7 it sustains real time — just — and the proof is the feed
   lag: 30 ms mean in quiet, 94–102 ms in babble and clatter, where the feeder
   could not always hold the microphone's pace. Those lags are inside the latency
   numbers above, so Nemotron's latency is if anything *understated* by ~30 ms
   relative to the zipformer's. A slower phone would not sustain this arm.
4. **The 560 ms export trades the wrong way here.** Half the CPU (0.30 vs 0.68
   RTF; 4.8 k vs 11.6 k CPU s/audio-hour) but ~200 ms slower per word (paired,
   same words) and a new failure mode in this harness: with 560 ms of audio still
   undecoded when the endpoint flag goes true, a turn can be cut before the last
   words are emitted. That cost `short_02` four of its seven words — the only
   clip in 57 where early endpointing damaged the transcript, and harmless at
   160 ms because at most 160 ms is ever pending.
5. **The 20M arm is unchanged and still unusable** — 20–24 % WER, an empty or
   single-letter hypothesis on several clean clips, 61 % word coverage.
6. **Finalisation is not comparable across chunk sizes** and should not be read as
   a win. Nemotron's 137–263 ms medians mean its endpoint flag was *already true*
   when the harness began feeding trailing silence (`tail_ms = 20` on 14 of 19
   quiet clips): the model had decided the turn was over before the clip ended.
   0626's 721–841 ms means its flag was not yet set and had to accrue trailing
   silence. Those are different internal decisions, so the column measures
   "how soon the model is willing to end the turn", not decoder speed. What it
   does establish is that Nemotron ends a turn sooner — and that the 560 ms
   export can end it too early.
7. **Revisions stay high for the accurate arms** (1.17–1.29 edits per word) — the
   UI must still be designed for text that changes. Nemotron is marginally
   steadier than 0626, not qualitatively different.
8. **Cold start is no longer the zipformer's advantage to lose.** 2.97 s vs
   3.55 s: Nemotron loads *faster* than the 2023 zipformer despite 9× the weights.

### 4.5 Paired latency (the fair comparison)

Restricting all four arms to the words every arm surfaced, in the same clips:

| condition | words | 0626 | 20M | Nem160 | Nem560 | Nem160 − 0626 |
|---|---|---|---|---|---|---|
| quiet | 273 | 967 ms | 856 ms | **699 ms** | 905 ms | −304 ms |
| tv | 276 | 946 ms | 879 ms | **771 ms** | 944 ms | −175 ms |
| kitchen | 264 | 941 ms | 869 ms | **743 ms** | 969 ms | −169 ms |
| accent | 29 | 905 ms | 765 ms | **678 ms** | 976 ms | −146 ms |

The 160 ms Nemotron is the fastest arm on every condition once the comparison is
like-for-like, by 0.15–0.3 s. The zipformer 20M is fast because it drops words;
Nemotron drops almost none (99.4 % coverage).

### 4.6 Repeatability

`quiet` was re-run for both zipformer arms on the same clips: final transcripts
**identical on all 15 clips for both arms**, per-word latency moved by −8 ms
(0626) and +1 ms (20M) at the median, −1 / +18 ms at p95, RTF identical to three
decimals. **Nemotron was not repeated** — its 1.4 % vs 2.0 % margin rests on a
single pass over 348 words, so read it as "equal or better", not as a certified
0.6-point win. The accent gap (27 points) is far outside any plausible
run-to-run variation.

## 5. Recommendation

**Adopt `sherpa-onnx-nemotron-speech-streaming-en-0.6b` (int8, 160 ms chunk) as
the streaming STT arm, with `sherpa-onnx-streaming-zipformer-en-2023-06-26`
(int8) as the fallback for devices that cannot afford it.** Ship either behind a
setting ("stream partials while you speak") and keep the existing offline Whisper
base.en path as the default final transcript until the streaming UX is settled.

The case for Nemotron is accuracy at equal-or-better latency, and specifically
the accent result: the zipformer's 39 % WER on non-native conversational speech
was the reason not to put streaming in front of a real user, and 12 % removes
most of that objection. The case against it is cost: 3× the CPU and memory, a
663 MB footprint, and a feed that only just keeps up on a Pixel 7. That is a
device-tier decision, not a model-quality one, which is why both arms stay in the
tree.

Do **not** adopt the 20M arm. Do **not** choose the 560 ms Nemotron export on this
phone: it halves CPU but costs ~200 ms of word latency and can truncate the end
of a turn.

Before shipping, decide two things this study cannot:

- **Endpointing.** The 500 ms trailing-silence rule is what makes Nemotron's
  turn-end feel fast; it is also what cuts a turn at a 600 ms pause. If the app
  resets the stream on endpoint, the rule must be tuned against the chunk size,
  and the 560 ms export needs a flush of pending audio before reset.
- **Download UX and licence.** 464 MB fetched at runtime, with the NVIDIA Open
  Model License shown and accepted and the attribution string in the notices
  screen; a 663 MB on-disk footprint needs a storage check and an uninstall path.
  Also note the licence is revocable — pinning a version we have verified is not
  the same as being entitled to that version forever.

## 6. What was not measured (and would change the answer)

- **Vosk was not run.** It is the remaining arm in D5 and needs a second runtime
  (Maven AAR), not a config swap. The conclusion is "best of four sherpa arms",
  not "best of five".
- **Sustained thermal behaviour is not measured.** Every cell is ≤ 3.3 minutes of
  audio in a fresh process. Nemotron runs at 0.68–0.76 RTF on 4 threads; over a
  20-minute dictation the Pixel will heat and throttle, and the 94–102 ms feed lag
  in the noisy cells is the early warning. A 20-minute unplugged run per arm is
  the missing experiment, and it would also produce the battery number.
- **Battery per audio-hour is not measured.** The phone was USB-powered for the
  whole study (`dumpsys battery`: USB powered, charge counter rising) and
  `BATTERY_PROPERTY_ENERGY_COUNTER` reported 0. CPU-seconds per audio-hour is
  reported instead: it ranks the arms, but it is not a battery number.
- **"TV" and "kitchen" are synthetic proxies** (babble at 10 dB, pink+clatter at
  8 dB). Reproducible and comparable, not field recordings. A real kitchen is
  impulsive in ways pink noise is not.
- **Onsets carry ~±100 ms of systematic error** (§3). Between-arm comparison is
  unaffected; absolute latency claims are not precise to the millisecond.
- **One pass per cell.** The only repeat evidence is the earlier zipformer quiet
  re-run (§4.6); Nemotron has not been repeated.
- **Accented speech is 4 clips (67 words).** The direction is unmistakable, the
  number is not. A larger accented corpus is the highest-value follow-up.
- **One device, 4 threads, 20 ms chunks, one endpoint rule (500 ms).**
- **No barge-in / VAD interaction.** This measures the recognizer in isolation
  (file → recognizer), not the app's mic path with Silero VAD gating and TTS
  echo. App-level numbers will be worse.
- **The 80 ms and 1120 ms Nemotron exports were not run.** NVIDIA's published
  curve puts 80 ms ~0.3–1.4 points behind 160 ms and 1120 ms ~0.1 ahead of
  560 ms; on this phone 80 ms would push RTF past 1 and 1120 ms would add roughly
  half a second of word latency, so neither is a candidate here.

## 7. Reproducing

```bash
# download + verify (sha256 and size) + extract the arm tarballs
python3 tools/sttbench-stream/run_ab.py --fetch --arms nemotron160 --models /home/c/stt-ab-models

# one arm, all four conditions, build included; installs under the phone lock
scripts/stt-ab.sh --arms nemotron160 --conditions quiet,tv,kitchen,accent

# rescore every committed raw result without touching the device
python3 tools/sttbench-stream/run_ab.py --score-only
```

`scripts/stt-ab.sh` builds `:bench`, installs it under the phone lock, pushes the
corpus and model directories, runs every cell, prints the table and leaves Hermes
Vox back in the foreground. Device runs are real time: one arm × four conditions
is ~11 minutes, four arms ~45.
