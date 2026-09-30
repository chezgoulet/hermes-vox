# sttbench — on-device STT regression bench

"Hermes must always receive a true transcription of what the user said." This
bench is how that claim is measured instead of asserted: a small, license-clean
corpus (`corpus/`, 25 clips, 5.7 MB) and a host-side runner that decodes it with
the **same model files the app downloads** (the sha256 pins in `ModelCatalog.kt`)
through the **same runtime version** the app ships (sherpa-onnx 1.13.6).

It reports, per model:

- **WER** vs the reference transcript, overall and per category (short / long /
  verylong / pause / noisy / accent). Normalization: lowercase, punctuation
  stripped, digits spelled out (years as "eighteen fifty five"), hyphens split.
- **RTF** — decode seconds / audio seconds, on this host's CPU, 4 threads (the
  app's default: half the cores, max 4).
- **Hallucinations** on the five non-speech clips: `raw` = any text at all,
  `kept` = text that survives the app's bracketed-tag strip
  (`TranscriptValidator.BRACKETS`) and would reach the rest of the validator.

Whisper is decoded exactly as the app does: fp32 encoder/decoder, greedy search,
`tail_paddings=1000`, and the `SttWindows` rule (25 s windows cut at the quietest
20 ms frame in the last 4 s) for audio past one window.

Not part of PR CI — the models are hundreds of MB. Run it by hand, or from the
Actions tab (`sttbench` workflow, manual dispatch).

## Run

```bash
python3 -m venv tools/sttbench/.venv            # or: uv venv tools/sttbench/.venv
tools/sttbench/.venv/bin/pip install -r tools/sttbench/requirements.txt

# download + sha256-verify the models (into tools/sttbench/.models, gitignored)
tools/sttbench/.venv/bin/python tools/sttbench/run_bench.py --model all --fetch -v

# one model, per-clip output
tools/sttbench/.venv/bin/python tools/sttbench/run_bench.py --model whisper-base -v

# what the pre-fix early start committed (only the last 6 s of each clip)
tools/sttbench/.venv/bin/python tools/sttbench/run_bench.py --model whisper-base --old-early-start
```

Models: `whisper-tiny`, `whisper-base` (the app default), `whisper-small`,
`parakeet-v2`, `parakeet-v3` (NeMo Parakeet-TDT 0.6B int8), `zipformer-stream`
(streaming zipformer, 2023-06-26, int8). `--model all` runs the comparison set.
Other flags: `--tail-paddings N` (Whisper), `--window-s S` (0 = no windowing),
`--threads N`, `--json out.json` (per-clip hypotheses).

`build_corpus.py` regenerates `corpus/` from its sources (LibriSpeech and EdAcc
rows via the Hugging Face datasets-server API; noise from a fixed seed). See
`corpus/README.md` for licences and provenance.

## Baseline (2026-09-29, x86-64 host, 12 cores, 4 decode threads)

| model | WER | RTF | halluc raw/kept | short | long | verylong | pause | noisy | accent |
|---|---|---|---|---|---|---|---|---|---|
| whisper-base.en (app default) | **5.6%** | 0.19 | 5/0 | 2.1% | 4.3% | 0.0% | 0.0% | 32.0% | 14.9% |
| whisper-small.en | 3.6% | 0.54 | 5/1 | 2.1% | 2.1% | 0.0% | 0.0% | 4.0% | 16.4% |
| parakeet-tdt-0.6b-v2 int8 | **3.4%** | **0.08** | **0/0** | 0.0% | 1.4% | 0.0% | 0.0% | 4.0% | 17.9% |
| parakeet-tdt-0.6b-v3 int8 | 3.4% | 0.08 | 0/0 | 0.0% | 1.4% | 0.0% | 0.0% | 4.0% | 17.9% |
| streaming zipformer en 2023-06-26 int8 | 8.1% | 0.10 | 0/0 | 0.0% | 2.9% | 1.1% | 0.0% | 20.0% | 38.8% |

whisper-base.en decoder settings and the early-start rule, same corpus:

| variant | WER | notes |
|---|---|---|
| tail_paddings 1000 (now explicit; the old `0` meant 1000) | **5.6%** | |
| tail_paddings 300 | 11.0% | `short_04` loops: "This was this was …" ×10 |
| tail_paddings 50 (upstream's English suggestion) | 12.4% | loops + a dropped accent clip ("But") |
| no windowing (one decode call) | 7.2% | `verylong_01` (31.7 s) loses its tail: 8.0% vs 0.0% |
| **pre-fix early start** (last 6 s only) | **50.9%** | long 60%, verylong 81%, pause 64% — the truncation bug |

The whisper hallucinations are all bracketed tags ("(buzzing)", "(clicking)",
"[BLANK_AUDIO]") that the validator strips; whisper-small also produced a bare
"you" on mains hum, which the validator's low-speech phrase rule refuses.
