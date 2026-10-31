# Presence bench — Qwen 3.5 0.8B and the lighter candidates on a Pixel 7

D4 (`docs/DECISIONS.md`) makes `Qwen3.5-0.8B_int8.litertlm` the default presence model, on the
runtime we already ship, and commits to measuring load time and resident memory on the Pixel
before calling it done. This is that measurement, plus SmolLM2 360M and the current Gemma 4 E2B,
against the budget the presence layer needs:

- **first token < 300 ms**
- **a full short line < 700 ms**
- **resident memory that leaves room for the speech models**

## The answer

**Qwen 3.5 0.8B cannot be the presence default on the Pixel 7.** Not because the runtime refuses
it — it loads, and it answers — but because:

1. **Its GPU engine does not fit.** Engine init never completed: the process' PSS climbed to
   **6.86 GB** while the phone had **4.30 GB** available, `MemAvailable` fell to **35 MB**, the
   kernel reclaimed, and `initialize()` had still not returned when the 180 s watchdog fired. Two
   independent passes, both prompt shapes, same result. The model card predicts this: on Android
   the GPU path runs with fp32 activations (a Mali device "does not fit in the memory the phone
   had free"), and the only Android GPU it verifies is an Adreno part.
2. **On the CPU it is 5x slower than the model it would replace.** 1.42 s to first token and
   ~2–4 s per short line at 6–12 tok/s, against Gemma 4 E2B on the GPU at **0.29 s** to first
   token and **0.79 s** for a line.
3. **The app's conversation shape corrupts its output.** Qwen3.5 carries GatedDeltaNet recurrent
   state that LiteRT-LM does not reset for a new `Conversation` on an Engine that already ran
   one — the card says to build a *new Engine per conversation*. `GemmaExpress` does the
   opposite (one Engine, conversations rotated by `SoulBudget`). The third of three presence
   renders returned the system prompt verbatim instead of a line, in **both** passes; on the
   128-token shape the second render returned the user directive verbatim. Same prompt, same
   engine, correct output one turn earlier.
4. **It saves memory only on disk.** 965 MB of file against Gemma's 2.6 GB, but **2.28 GB** of
   resident footprint against Gemma's **2.41 GB** — the activations, the float GatedDeltaNet
   state and the KV cache dominate, not the weights.

Gemma 4 E2B on the GPU is faster than all three candidates on both budgets, and it is the only
configuration measured that meets the 300 ms first-token budget. D4 should keep it as the
default and ship Qwen as a selectable model, not the default.

## Method

### The harness

`android/app/src/androidTest/java/com/hermesvox/PresenceBenchTest.kt` — an instrumented test, so
it runs the **same pinned LiteRT-LM the app ships (0.16.1)** in the app's own process on the
phone. It is not packaged into a release APK and `scripts/gate.sh` never runs it (a gate host has
no phone).

Per row (one model × one backend):

1. Copies the `.litertlm` into the app's **internal** storage (timed separately, not counted as
   load). The app loads models from `filesDir`; a model mmap'd from FUSE-backed `/sdcard`
   page-faults through the FUSE daemon for every weight page, which measures the storage layer.
2. Deletes the compile cache, then times `Engine(EngineConfig(...))` + `initialize()`
   (**cold load**). Repeats on the populated cache (**warm load** — the second app launch).
3. Creates a `Conversation` with a fixed short system line and one user line, greedy sampling
   (`topK=1, topP=1.0, temperature=0`), `maxOutputToken = 32`.
4. Records wall-clock **time to first token** (send → first `onMessage` callback), **total line
   time** (send → `onDone`), the callback-chunk count as the token count, `getTokenCount()`, and
   **peak RSS** (`/proc/self/status` `VmHWM`, one process per row, so the figure belongs to that
   row alone).
5. Reads the thermal state and battery temperature from the host around every row.

Prompt shapes: **presence** (`"You are the voice of an agent on a phone call. Reply in one short
spoken sentence. Never say you are an AI or a model."` + one directive line) and **persona** (a
~600-token persona, because that is what `GemmaExpress` really prefills via `VoxSoul.soulPrompt`).
A **long** shape caps output at 128 tokens so a decode rate is measured over 20–32 chunks rather
than over a two-token "Hello!".

```sh
ADB=$ANDROID_HOME/platform-tools/adb MODEL_SRC=~/vox-models \
  bash scripts/presence-bench.sh qwen cpu 3 presence      # one row
bash scripts/presence-bench-all.sh                        # CPU rows, then GPU rows
bash scripts/presence-bench-cool.sh                       # settled handset + 128-token decode
bash scripts/presence-bench-gpu-probe.sh                  # memory during an init that never returns
python3 scripts/presence-bench-report.py presence-bench-results
```

The drivers take `/home/c/phone.lock` for the whole install-and-measure window: the phone is
shared with other agents, and the first attempt at this bench died because another agent's
install landed under the instrumented process mid-run.

### Conditions

| | |
|---|---|
| Device | Pixel 7 (`panther`), Tensor G2, 8 GB (`MemTotal` 7,647,536 kB), Android 17 / API 37, arm64-v8a |
| Runtime | `com.google.ai.edge.litertlm:litertlm-android:0.16.1` (the app's pin; the Qwen bundle requires ≥ 0.15) |
| Build | debug APK, app process, model in internal storage, one process per row |
| Thermal | `Thermal Status: 0` (none) in every row; battery 25–26 °C. The `BIG` junction reading in `dumpsys thermalservice` sits at 97 °C in both the hot and the settled pass and never moves — it is a cached value, not a live signal, and is not used as evidence here |
| Passes | pass 1 ran immediately after another agent's STT A/B (handset busy); pass 2 after a 6-minute idle. **Pass 2 is what the tables report**: pass 1 was 2–3x slower on the same rows (Gemma GPU cold init 107 s vs 40 s, first token 1.76 s vs 1.29 s), which is other work on the phone, not the model |
| Models | Qwen3.5-0.8B_int8 `964,566,992 B` sha256 `25447e25…`; SmolLM2_360M_instruct `373,719,040 B`; gemma-4-E2B-it `2,588,147,712 B` sha256 `18193810…` (matches `ModelCatalog`) |

## Results

### Presence shape, 32-token cap, greedy — steady state (render 2–3)

| Model | Backend | Cold load | Warm load | First token | Full line (8 tok) | Decode | Peak RSS |
|---|---|---|---|---|---|---|---|
| **Gemma 4 E2B** (2.6 GB) | **GPU** | 39.8 s | 3.2 s | **0.29 s** | **0.79 s** | 14.3–15.0 tok/s | **2.41 GB** |
| Gemma 4 E2B | CPU | 5.1 s | 0.46 s | 1.46 s | 2.30 s | 8.4 tok/s | 2.16 GB |
| Qwen 3.5 0.8B (965 MB) | CPU | 40.3 s | 34.7 s | 1.43 s | 1.59 s¹ | 6.1 tok/s | 2.28 GB |
| Qwen 3.5 0.8B | GPU | **never** (180 s)² | — | — | — | — | aborted at 6.86 GB PSS² |
| SmolLM2 360M (374 MB) | CPU | 1.9 s | 0.38 s | 7.0 s | 11.7 s | 6.6 tok/s | 1.36 GB |
| SmolLM2 360M | GPU | 12.4 s | 1.5 s | 1.45 s | 1.92 s | 12.8 tok/s | 1.51 GB |

¹ Qwen's "line" here is two tokens — `"Hello!"` — not eight like Gemma's, so it is not a like-for-like
number; see the 128-token row below. ² `presence-bench-gpu-probe.sh`: PSS 0 → 6.86 GB over 170 s while
`MemAvailable` fell 4.30 GB → 35 MB, then freed; no exception, `initialize()` simply never returned.

### Long shape, 128-token cap — decode measured over a paragraph

| Model | Backend | First token | Tokens | Decode | Line time |
|---|---|---|---|---|---|
| Gemma 4 E2B | GPU | 0.29 s | 20 | **16.5 tok/s** | 1.44 s |
| Qwen 3.5 0.8B | CPU | 1.71 s | 26–30 | 11.9 tok/s | 4.15 s |
| SmolLM2 360M | CPU | 7.0 s | 32 | 6.6 tok/s | 11.7 s |

### The persona shape

The app prefills a ~600-token persona per render. Measured on Qwen CPU with one Engine per render
(the shape Qwen requires), a 1,033-token prompt and a seven-token answer: **first token 21.5 s**,
line 22.1 s (that row ran in the busy first pass, so treat it as an upper bound). The prefill, not
the decode, is the cost — which is why the warm-conversation design exists at all, and why moving
to Qwen would put that prefill back on every turn.

### What the numbers say about the budget

An aside is 8–15 tokens. At the measured rates:

| Model/backend | First token | 12-token line | Budget (300 / 700 ms) |
|---|---|---|---|
| Gemma GPU | 0.29 s | ≈ 1.1 s | first token **met**; line met only for ≤ 6 tokens |
| Qwen CPU | 1.43 s | ≈ 2.4 s | missed by 5x / 3x |
| SmolLM2 GPU | 1.45 s | ≈ 2.4 s | missed |
| Gemma CPU | 1.46 s | ≈ 2.9 s | missed |
| SmolLM2 CPU | 7.0 s | ≈ 8.8 s | missed by 20x |

**The budget is a GPU budget.** Nothing on the CPU comes within 4x of 300 ms, and the 700 ms line
is reached only by Gemma on the GPU with a line of six tokens or fewer. That is a statement about
the Tensor G2, not about the model choice: swapping the model file does not move it.

## Findings beyond the numbers

1. **"One Engine per conversation" is a Qwen requirement, not a style choice.** Reproduced in both
   passes: the third render on a reused Engine emitted the system prompt instead of a line; the
   long shape emitted the user directive verbatim on rep 2. `GemmaExpress`'s design (one Engine,
   `SoulBudget` rotating conversations) is exactly the pattern the model card says is broken for
   this family. Supporting Qwen means either an Engine per render — measured at 35–94 s of init
   per render, with first token 5.7–10.7 s — or a runtime that resets recurrent state.
2. **It is not a runtime-version problem.** 0.16.1 (the app's pin) loads the file on the CPU; the
   bundle's floor is 0.15 and the newest published Android artifact is 0.18.0. The GPU failure is
   memory (fp32 activations), not API. An fp16-activation build of the bundle, or a device with
   ~7 GB free, would change the answer; neither exists today.
3. **Warm load is the number users feel.** A second app launch is 3.2 s for Gemma on the GPU
   (from the compile cache), 0.46 s for Gemma on the CPU, **34.7 s for Qwen on the CPU** — Qwen's
   init does not cache well, so the presence layer would be unavailable for half a minute on
   every launch.
4. **SmolLM2 360M is not a latency option, it is a download-size option.** 374 MB and 1.36 GB
   RSS, but 7 s to first token on the CPU and 1.9 s per line on the GPU. Its CPU number is
   reproducible across storage types and passes, so it is the runtime's CPU path for this model,
   not a measurement artefact.
5. **`Conversation.getBenchmarkInfo()` returns nothing on these conversations** in 0.16.1, so
   every figure here is wall-clock from the callback stream, not the runtime's self-report. The
   chunk count tracks tokens (8 chunks for a 7-token line, 20 for a 20-word line), which is what
   the decode rate is computed over.

## Recommendation

**Do not make Qwen 3.5 0.8B the default presence model in 0.9.** Concretely:

1. **Default stays Gemma 4 E2B, GPU-first** (the app's current `Backend.GPU()` preference). It is
   the only configuration that meets the first-token budget, its 2.41 GB peak leaves ~4 GB for
   the speech models and the rest of the app, and its warm load is 3.2 s.
2. **Qwen 0.8B ships as a selectable model, not the default**, with its limits stated in the
   picker: CPU-only on Mali, ~1.4 s to first token, ~2.4 s per line, 35 s warm load. It stays
   worth having — it is the cheap in the "one runtime, many models" sense and it is Apache-2.0 —
   but it is a downgrade on this phone.
3. **The budget needs restating before it is used as a gate.** "First token < 300 ms, line < 700
   ms" is only reachable on the GPU, and only for very short lines. Either the instant lane is
   GPU-only with a stated fallback (a filler from `ErFillers`, or a shorter line), or the budget
   gets a CPU row that says what CPU-class presence is allowed to cost. Measuring against a
   single number that one backend cannot reach is how a gate becomes a decoration.
4. **First-run UX, if download size is the real motive for D4:** Qwen's 965 MB buys 1.6 GB of
   download and **no** resident-memory saving (2.28 GB vs 2.41 GB) and no latency. The model that
   actually trades size for speed and still works on this phone's GPU is SmolLM2 360M (374 MB,
   1.51 GB RSS, 1.9 s per line) — it misses the budget too, but it is honestly the "lighter
   option" rather than a heavier one wearing a smaller file.
5. **Fix the catalog number.** D4 says 920 MB; the artifact is 964,566,992 B (920 MiB, but 965 MB
   decimal), and `ModelCatalog` renders decimal MB. Whatever ships must carry the real size.

If Qwen is still wanted as the default, the prerequisite is not a bench: it is a GPU build of the
bundle with fp16 activations, or a device class with ~7 GB free, plus removing the app's
conversation reuse. That is a 1.0 question, not a 0.9 one.

## Reproducing

```sh
export JAVA_HOME=~/jdk-17 ANDROID_HOME=~/Android/Sdk
flock ~/build.lock -c 'cd android && ./gradlew --no-daemon :app:assembleDebugAndroidTest'
ADB=$ANDROID_HOME/platform-tools/adb MODEL_SRC=~/vox-models bash scripts/presence-bench-run.sh
```

`scripts/presence-bench-run.sh` installs the test APK and runs the sweep under one phone lock;
`presence-bench-results/*.jsonl` holds every row this document is built from, and
`scripts/presence-bench-report.py` regenerates the tables.

Not measured here, and needed before 1.0: the presence layer running *alongside* the speech
models in a real call (STT + TTS resident, GPU shared), and battery per audio-hour. The RSS
figures above are the model in isolation; 2.4 GB of a 7.6 GB phone is comfortable, but the
headroom claim should be proven with the pipeline up.
