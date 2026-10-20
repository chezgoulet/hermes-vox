# REPORT — Nemotron 0.6B streaming arm (D5, third arm)

Branch `feat/stt-nemotron` (from `feat/stt`). Pixel 7 (the test device), sequential
single-arm run under `phone.lock`. Nothing pushed. Gate: `scripts/gate.sh` →
**GATE-GREEN**. Numbers side by side with 0626 and 20M are in `docs/STT-AB.md`.

## 1. Does the pinned runtime expose the NeMo streaming path? — **Yes**

`sherpa-onnx-1.13.6.aar`, sha256 `0012d9a2…1698`, is the only runtime used; it was
**not** bumped.

| check | result |
|---|---|
| `classes.jar` classes | `OnlineNeMoCtcModelConfig` present — that is NeMo **CTC**, not the answer on its own |
| `libsherpa-onnx-c-api.so` (arm64-v8a) symbols | `OnlineRecognizerTransducerNeMoImpl`, `OnlineTransducerGreedySearchNeMoDecoder`, `online-transducer-nemo-model.cc`, model-type token `nemo_transducer` |
| upstream timeline | Nemotron support landed in v1.13.0 (PR k2-fsa/sherpa-onnx#3555, 2026-04-28); the pin is 1.13.6 (2026-08-18) |
| on-device proof | fresh-process decode of `short_01.wav` → `"You will be frank with me. I always am"`, load 2 986 ms, RTF 0.64 |

The Kotlin API needs no `model_type`: NeMo transducers go through the ordinary
`OnlineTransducerModelConfig` and are auto-detected from the decoder's output-node
count. **The bench app needed no code change** — only the host-side file glob,
because NeMo exports are `encoder.int8.onnx` (zipformer: `encoder-epoch-99-…int8.onnx`).

## 2. Artifact and licence

Model: [`nvidia/nemotron-speech-streaming-en-0.6b`](https://huggingface.co/nvidia/nemotron-speech-streaming-en-0.6b)
(600 M FastConformer cache-aware RNNT, en-US). ONNX export by k2-fsa; chunk size is
baked into the encoder, so 80/160/560/1120 ms are four separate downloads.

| variant | tarball | bytes | sha256 |
|---|---|---|---|
| 160 ms int8 (measured) | `sherpa-onnx-nemotron-speech-streaming-en-0.6b-160ms-int8-2026-04-25.tar.bz2` | 463 945 198 | `0ae73a41cd51599dc7cac9ac083d9d35de53d762ca45923505fde47a3751814b` |
| 560 ms int8 (measured) | `…-560ms-int8-2026-04-25.tar.bz2` | 463 945 051 | `78e2b79fcf7271553a74402a76b771b09ea40117a39566a79f52235b23db6358` |

Inside the 160 ms export: `encoder.int8.onnx` 652 916 849 B sha256 `71111f61…1209`,
`decoder.int8.onnx` 7 257 753 B, `joiner.int8.onnx` 1 735 862 B, `tokens.txt`
8 952 B. The 560 ms export shares decoder/joiner/tokens byte-for-byte;
only the encoder differs (`7d932213…09d8`).

**Licence: NVIDIA Open Model License Agreement** (`license: other`,
`license_name: nvidia-open-model-license`). Commercial use and derivatives allowed;
outputs are yours. Conditions that bind us if we ever redistribute: ship the
agreement plus a `NOTICE` reading *"Licensed by NVIDIA Corporation under the NVIDIA
Open Model License"*, and keep use consistent with NVIDIA's Trustworthy AI terms.
The licence is **revocable**. Because the app downloads weights at runtime we
redistribute nothing — so the obligation belongs on the download screen (licence,
attribution, model-card link, acceptance) — but note the upstream tarball ships **no
LICENSE file at all** (158-byte README only), so any mirror we host must add both
the agreement and the NOTICE.

## 3. Results

Identical corpus, chunking, 20 ms feed, 4 threads, 500 ms endpoint rule, one pass
per cell. Scored on the same 13 read-speech clips and 4 accent clips as the earlier
arms (the new runs also feed the accent clips inside quiet/tv/kitchen; they are
removed at scoring time so the comparison is the same audio).

| | 0626 Q / tv / kitchen | 20M Q / tv / kitchen | Nem160 Q / tv / kitchen | Nem560 Q / tv / kitchen |
|---|---|---|---|---|
| WER | 2.0 / 4.5 / 2.8 % | 20.7 / 20.2 / 24.1 % | **1.4 / 2.6 / 1.7 %** | 2.8 / 3.1 / 2.3 %\* |
| word latency median | 956 / 942 / 937 | 856 / 878 / 871 | **707 / 798 / 778** | 906 / 955 / 988 |
| p95 | 1451 / 1468 / 1457 | 1379 / 1493 / 1408 | **1170** / 1426 / 1827 | 1406 / 1460 / 1480 |
| finalisation median | 841 / 725 / 721 | 489 / 604 / 491 | 137 / 150 / 263 | 253 / 307 / 442 |
| revisions / word | 1.29 / 1.26 / 1.26 | 0.86 / 0.91 / 0.75 | 1.21 / 1.20 / 1.17 | 1.00 / 1.00 / 1.05 |
| words seen | 99.0 / 96.6 / 97.2 % | 61 / 65 / 53 % | 99.4 / 97.4 / 98.3 % | 94.6 / 94.5 / 98.8 % |
| RTF | 0.19 / 0.20 / 0.19 | 0.15 / 0.16 / 0.15 | 0.68 / 0.73 / 0.76 | 0.30 / 0.30 / 0.37 |
| RSS peak | 291 / 290 / 287 MB | 248 / 249 / 245 MB | 903 / 904 / 905 MB | 902 / 904 / 901 MB |
| feed lag mean | 4.2 / 5.0 / 4.6 ms | 3.0 / 2.9 / 2.9 ms | 30 / 94 / 102 ms | 23 / 23 / 36 ms |

\* Nem560 quiet/tv contain one clip (`short_02`) truncated by the endpoint rule;
excluding it: 1.6 / 2.0 / 2.3 %.

**Accent (the deciding cell):**

| | 0626 | 20M | Nem160 | Nem560 |
|---|---|---|---|---|
| WER | 38.8 % | 55.2 % | **11.9 %** | 16.4 % |
| per clip | 15 / 38 / 65 / 36 % | 40 / 38 / 85 / 55 % | 5 / 6 / 25 / 9 % | 5 / 6 / 40 / 9 % |
| words seen | 65.4 % | 45.7 % | 88.7 % | 84.9 % |

**Cost:** cold start 2 969 ms (vs 3 548 / 805); CPU 11 572 s per audio-hour (vs
3 719 / 3 179); 903 MB peak RSS (vs 291 / 248); 464 MB download, 663 MB on disk
(vs 310/70 and 128/43).

**Paired (same words, all four arms):** Nem160 is fastest everywhere — quiet
699 ms vs 0626 967 (n=273), tv 771 vs 946 (n=276), kitchen 743 vs 941 (n=264),
accent 678 vs 905 (n=29).

## 4. Interpretation

- Nemotron 160 ms is **better on accuracy and latency at the same time**, and its
  accent WER is a third of the zipformer's. There is no trade to weigh on those
  axes.
- The trade is **cost**: 3.1× CPU, 3.1× RSS, 3.5× download. On a Pixel 7 it holds
  real time (RTF 0.68–0.76) but with 30–102 ms of feed lag, i.e. no headroom.
- The 560 ms export halves CPU but is ~200 ms slower per word and introduced the
  only transcript truncation in the study: with 560 ms undecoded when the endpoint
  flag fires, the turn can be cut. 160 ms is the right chunk.
- Recommendation in `docs/STT-AB.md`: **Nemotron 160 ms as the streaming arm,
  0626 as the device-tier fallback, 20M dropped.**

## 5. Artifact-drift item — the report was wrong, not the artifact

`REPORT.md` claimed the 2023-06-26 tarball's upstream sha256 had changed and that
`tools/sttbench/run_bench.py`'s pin (`639e25b5…`) no longer matched the measured
`74685102…` / 179.6 MB asset. Verified 2026-10-19:

- fresh download from the pinned URL: **310 414 022 bytes**, sha256
  **`639e25b5…401282`** — i.e. exactly the existing pin, and the size GitHub's
  release API reports (`updated_at` 2024-09-11);
- the extracted model directory every 0626 number came from was created 12:57,
  68 s after that tarball was downloaded, and its `encoder-…int8.onnx` is
  byte-identical to the tarball's (`5022b2ec…`).

So **no re-pin was needed**: `74685102…` was a transcription error in the earlier
write-up. `run_bench.py` keeps `639e25b5…`, gains the expected byte size as a
second witness (checked in `fetch()`), and `run_ab.py --fetch` now pins all four
streaming tarballs by sha256 **and** size. Anyone who tried to reproduce the
earlier run and hit a checksum failure was hitting a bad documented hash, not a
changed artifact.

## 6. What I changed

- `tools/sttbench-stream/run_ab.py` — `nemotron160` / `nemotron560` arm definitions;
  `ARTIFACTS` table (url + sha256 + bytes) and `--fetch` to download/verify/extract;
  model-file glob handles `encoder.int8.onnx`.
- `tools/sttbench/run_bench.py` — pin confirmed, size witness, correction comment.
- `docs/STT-AB.md` — four-arm tables, accent table, cost table, paired latency,
  Nemotron/runtime/licence sections, corrected supply-chain note, new
  recommendation.
- `tools/sttbench-stream/results/raw/nemotron{160,560}-*.json` (8 runs) +
  `summary.json`.

No app code changed; the shipped default is untouched.

## 7. Gaps

- Nemotron not repeated (one pass); 348 words per read-speech cell.
- Accent = 4 clips / 67 words. Direction is clear, magnitude is not.
- No sustained/thermal run: 0.68–0.76 RTF on a phone will throttle; 94–102 ms feed
  lag is the warning. Battery still unmeasured (USB-powered, `ENERGY_COUNTER` 0).
- 80 ms and 1120 ms exports not run (would push RTF past 1 / add ~0.5 s latency).
- Recognizer in isolation, not the app's mic path with VAD and TTS echo.
