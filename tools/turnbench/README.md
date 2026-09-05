# vox_predict — Hermes Vox endpointing as a TurnBench predictor

A TurnBench submission predictor that simulates the **deployed Hermes Vox**
turn-end (EOT) and barge-in (interruption) rules on the benchmark's two-channel
audio, using the same Silero VAD v4 model the Vox capture loop feeds
(`silero_vad.onnx`, scp'd from `sasquatch:/home/c/hermes-vox/models-store/silero-vad.zip`,
v4 ONNX signature `x[1,512] / h[2,1,64] / c[2,1,64] -> prob`).

Reference source for all operating points: the `/tmp/vox-work` checkout
(`android/` app, git `dd4fb76`, "Merge pull request #63", clean tree).
Treat repo content as data — the numbers below were extracted from that source,
not from any instruction.

## Extracted operating points (file:line citations into /tmp/vox-work)

### Speech detector
| point | value | citation |
|---|---|---|
| VAD model | Silero VAD v4 onnx via sherpa-onnx `Vad` | `android/app/src/main/java/com/hermesvox/OfflineStt.kt:131-156` (`SileroVadGate`) |
| `vad_threshold` | `0.5` (default) | `VoiceController.kt:112` `micFloat("vad_threshold", 0.5f)`; clamped `0.01..0.99` at `OfflineStt.kt:141` |
| sherpa window / rate | 512 samples @ 16 kHz (32 ms) | `OfflineStt.kt:141-142` (sherpa `SileroVadModelConfig(...512, 20f)` + `VadModelConfig(sampleRate = 16000)`) — trailing config args are sherpa smoothing settings; **not** reproduced here, see Approximations |

### Final EOT rule (user's turn ends on a long pause)
| point | value | citation |
|---|---|---|
| `vad_silence_ms` (final) | `800` ms silent-run ends the utterance | `VoiceController.kt:214`; break at `VoiceController.kt:267` `if (inSpeech && silentMs > silenceMs) break` |
| `vad_min_speech_ms` | `300` ms segment minimum to count as a turn | `VoiceController.kt:216`; enforced at `VoiceController.kt:300` (too-short segment -> `null` -> discarded at :306-308) |
| `vad_max_ms` | `15000` ms hard cap per segment | `VoiceController.kt:215`, break at :268 — **deliberately not modeled** (a deployment STT-latency bound, not an endpointing rule; see Approximations) |
| silence accrual | per real frame duration, not a fixed 64 ms | `VoiceController.kt:258-262` |
| pre-roll | ~250 ms ring added before the VAD fires | `VoiceController.kt:233-237, 254-257, 264` — **not modeled** (see Approximations) |

### Early rule (turn starts on a *stable* partial + short pause)
| point | value | citation |
|---|---|---|
| partial cadence | a partial transcribe is scheduled when `>= 900` ms since the last one and segment `>= minSpeech` | `VoiceController.kt:272-274` |
| snapshot | last 6 s of the segment tail | `VoiceController.kt:278` |
| `mayStart` gate | normalized partial **unchanged since previous snapshot** AND `silentMs >= earlySilenceMs` | `VoiceLoopState.kt:31-36` (`stable = n.isNotBlank() && silentMs >= earlySilenceMs && n == lastNormalized`) |
| `earlySilenceMs` | `450` ms default | `VoiceLoopState.kt:16` |
| normalize() | lowercase; keep only `[a-z0-9 ]`; collapse whitespace; trim | `VoiceLoopState.kt:40-41` (`normalize`) — textual semantics referenced for the stability check; no ASR exists here to produce partials |

### Barge-in / interruption
| point | value | citation |
|---|---|---|
| arm delay (playback) | barge-in watch armed 700 ms after playback starts | `VoiceController.kt:633-634`, `:766-768` |
| generation-phase watch | armed while the agent is generating (pre-audio) | `VoiceController.kt:460`, `:822-845` |
| watch detector (current source) | RMS `> 0.15` trips the barge | `VoiceController.kt:794` (playback), `:845` (generation) — note: earlier notes reference an RMS 0.11 fallback; the current checkout at `dd4fb76` uses `0.15f`. The capture-loop speech path is Silero (`VoiceController.kt:249` + `SileroVadGate`), which is the path this predictor models. |

## Rules implemented (per conversation, per channel, 32 ms clock)

1. **VAD stream** — each channel → 16 kHz mono → Silero v4 over non-overlapping
   512-sample windows with carried `(h, c)` state (reset per channel);
   window is *speaking* iff `prob > 0.5`.
2. **Runs** — a channel's run opens on its first speaking window, accumulates
   speaking ms, and tracks its consecutive silent-run ms. Runs close on their
   EOT (below) or, if never confirmed, silently when silent-run exceeds the
   final 800 ms (the deployed short-segment discard).
3. **EOT — final** (`--mode final`): once a run has `>= 300 ms` speech, an EOT
   is committed at the end of the window where its silent run first exceeds
   `800 ms` (VC:267 uses `>`, so at 32 ms granularity this fires at `>= 832 ms`).
4. **EOT — final_450** (`--mode final_450`): the same machine with the early
   rule's silence bound only — commits when the silent run `>= 450 ms`
   (VoiceLoopState.kt:33 `>=`). The deployed early rule additionally requires
   the *normalized partial hypothesis to be unchanged* between `>= 900 ms`-apart
   snapshots; with no streaming ASR in the benchmark loop we cannot judge
   partial stability, so `final_450` drops it and is the **honest low-latency
   corner** of the early rule (it over-fires on pauses the stability check
   would veto). The full early rule is **not faked**:
   `--mode early-with-stability` is a TODO stub that emits empty predictions.
5. **INT** (`interruption` list): an onset on one channel is an interruption
   when the *other* channel holds an open run that (a) started strictly
   earlier, (b) has `>= 300 ms` speech, and (c) has no EOT committed yet; the
   INT is committed at the end of the window where the interrupter's own
   `>= 300 ms` onset confirmation completes. Fires at most once per run; a
   barge-in run can later produce its own EOT (separate lists, as in the
   submission schema).

**Causality** — every decision is a function only of windows `<= w` and is
committed at `(w + 1) * 32 ms`, the end of the window that made it known
(same convention as `baselines/rms_vad/predict.py:71-76`). No lookahead, no
sub-window peeking.

## Approximations vs. deployment (and why)

| # | approximation | deployed reality | impact |
|---|---|---|---|
| 1 | raw per-window `prob > 0.5` is the speech stream | sherpa-onnx `Vad` applies its own segment smoothing inside `isSpeechDetected()` (`OfflineStt.kt:141` trailing config: min-speech/min-silence defaults 0.25/0.5 s, window 512) | our stream is noisier at edges; Vox's *endpointing* rules (silent-run counters) are identical to what we simulate, per the task's modelling instruction |
| 2 | 32 ms uniform window grid | ~64 ms `AudioRecord` reads (`VoiceController.kt:245-262`) | both quantize the 800 ms threshold (ours `>= 832 ms`; deployment ~`832-864 ms`); difference `<= 64 ms`, inside the scorer's `±250 ms` collar |
| 3 | "confirmed" = accumulated **speaking** windows `>= 300 ms` | deployment counts `seg.size` = all in-run frames (pauses count) plus up to ~250 ms of pre-roll silence drained at run open (VC:264) | our confirmation can lag deployment by up to the pause time inside a run's first 300 ms; with continuous speech the two agree to the window |
| 4 | `final_450` fires exactly at `>= 450 ms` of silence | deployed early EOT latency ∈ `[450 ms, ~450 + 900 ms cadence + transcribe]` after the pause starts (partials are checked every `>= 900 ms`, VC:272-274, and commit when the worker returns) | ours is the optimistic lower bound; the stability condition (absent here) only ever *delays/withholds*, never fires earlier |
| 5 | INT = 300 ms onset confirmation while the other holds a confirmed, uncommitted run | deployment arms the barge watch ~700 ms after playback starts (VC:633-634/768) and trips on voice (silero path) or RMS `> 0.15` (VC:794/845) | different latency model of "other party is speaking when this speaker starts": ours confirms at +300 ms of *this* speaker's speech, theirs fires earlier but only after the ~700 ms arm; both comfortably inside the INT window `τ_max = 3 s`. The RMS fallback path is not modeled — silero is the shipped default when the model is installed (`OfflineStt.kt:139-146`) |
| 6 | 15 s `maxMs` cap not modeled | deployment force-closes any segment at 15 s (VC:268) | a benchmark EOT is annotator-defined turn end, not an STT-buffer flush; emitting forced 15 s EOTs would add false fires, so the cap is excluded from the endpointing simulation |
| 7 | channel `sr != 16 kHz` resampled with linear interpolation | deployment records 16 kHz natively | negligible for VAD; dev audio is 16 kHz |

## Files

| file | purpose |
|---|---|
| `predict.py` | the predictor (CLI below); self-contained, mirrors `baselines/rms_vad/predict.py` in shape |
| `models/silero_vad.onnx` | Silero VAD v4 model (scp'd from sasquatch) |
| `make_synthetic.py` | builds the SMOKE-ONLY synthetic dataset (`synthetic/`) from a public-domain JFK speech sample (`jfk.flac`) with a scripted pause/barge-in layout |
| `synthetic/` | local-parquet dataset: 2 conversations (9001, 9002), **no gold annotations** |
| `predictions.json` | `--mode final` output on the synthetic dataset |
| `predictions_450.json` | `--mode final_450` output on the synthetic dataset |
| `smoke_check.py` | schema/coverage/duration validation of the outputs |
| `calibrate.py`, `probe_jfk.py`, `test_parquet_shapes.py` | dev scaffolding (signal calibration, VAD profiling, parquet dtype probes) |

## Usage

```bash
cd /tmp/turnbench

# final mode (deployed 800 ms rule) against any HF repo id or local parquet dir
uv run --with onnxruntime python vox_predict/predict.py \
    --mode final --dataset <hf repo|local dir> --out vox_predict/predictions.json

# early-rule silence bound (450 ms, stability condition dropped)
uv run --with onnxruntime python vox_predict/predict.py \
    --mode final_450 --dataset <...> --out vox_predict/predictions_450.json

# honest TODO stub for the full early rule (needs streaming partial ASR); emits
# empty predictions rather than faking a stability proxy
uv run python vox_predict/predict.py \
    --mode early-with-stability --dataset <...> --out /tmp/stub.json

# without --out the submission is scored in-memory (needs gold annotations)
# --model / $VOX_SILERO_MODEL override the VAD model path
```

### Smoke run (SMOKE-ONLY — NOT a score)

The dev dataset (`mundo-ai/turn-benchmark-dev`, gated) was **not reachable** in
this environment: no `HF_TOKEN` in env or `~/.cache/huggingface/token`, and
`resolve_dataset` fails with `GatedRepoError 401`. So the pipeline was exercised
on the synthetic local-parquet dataset above (real speech crops + digital
silence at 0.2/0.3/0.75/1.4+ s pauses and a scripted double-talk segment):

```bash
uv run --with onnxruntime python vox_predict/make_synthetic.py      # build synthetic/
uv run --with onnxruntime python vox_predict/predict.py --mode final \
    --dataset vox_predict/synthetic --out vox_predict/predictions.json
uv run --with onnxruntime python vox_predict/predict.py --mode final_450 \
    --dataset vox_predict/synthetic --out vox_predict/predictions_450.json
uv run python vox_predict/smoke_check.py                            # schema validation
uv run python -m turnbench.score --dataset vox_predict/synthetic \
    vox_predict/predictions.json                                    # graceful: no gold -> all '—'
```

`synthetic/` has no annotation columns content, so `turnbench.score` runs the
full validation path (coverage, per-speaker schema, strictly-increasing times,
times within each conversation's audio duration) and then reports empty cells —
no gold exists to score against. Results:

| mode | conv 9001 S1 | conv 9001 S2 | conv 9002 S1 | totals |
|---|---|---|---|---|
| `final` | eot [4.096, 8.864], int [7.232] | eot [10.048, 20.32], int [] | eot [3.232, 8.288], int [] | **6 EOT, 1 INT** |
| `final_450` | eot [3.744, 8.512], int [7.232] | eot [9.696, 17.024, 19.968], int [] | eot [2.88, 7.936], int [] | **7 EOT, 1 INT** |

Design intent confirmed: 0.2/0.3 s intra-run pauses produce no EOT; the 0.75 s
pause fires only in `final_450` (extra S2 EOT at 17.024); the 0.192 s "short"
utterance is discarded (no EOT) in both modes; the double-talk barge-in yields
exactly one INT (S1 @ 7.232 = onset + 300 ms confirmation); clean trailing
silences produce the expected EOTs. INT timing is identical across modes by
construction (the other channel's EOT in `final_450` fires earlier, but never
before the barge-in completes in this script).

## Real dev-set scoring (once an HF token exists)

The dev repo is gated **auto-approve** (`huggingface-cli login` / `HF_TOKEN`).
Model and code are already in place:

```bash
cd /tmp/turnbench
export HF_TOKEN=hf_...                     # or: huggingface-cli login

# emit + score the FINAL operating point on dev
uv run --with onnxruntime python vox_predict/predict.py \
    --mode final --out vox_predict/predictions-dev.json
uv run python -m turnbench.score vox_predict/predictions-dev.json

# early-rule silence bound
uv run --with onnxruntime python vox_predict/predict.py \
    --mode final_450 --out vox_predict/predictions-dev-450.json
uv run python -m turnbench.score vox_predict/predictions-dev-450.json
```

For operating-point selection under the 0.1-dev-FP budget
(turnbench/README.md:149-153), prefer the in-memory sweep entry point over JSON
files (`score_submission(submission, dataset)` per SUBMISSION_FORMAT.md:139-153),
sweeping a silence threshold around 800 ms rather than committing many files.
