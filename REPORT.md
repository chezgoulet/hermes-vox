# Presence bench — report

Task: decide whether `Qwen3.5-0.8B_int8.litertlm` (920 MB per D4, actually 964,566,992 B) can be
the default presence model on the Pixel 7 alongside the speech models, against a budget of
first token < 300 ms, full short line < 700 ms and sane resident memory. Measure it, measure
SmolLM2 360M and the current Gemma 4 E2B, and recommend.

## Outcome

**No — Qwen 3.5 0.8B should not be the default in 0.9. Gemma 4 E2B stays.** Evidence:

| Model | Backend | Cold load | Warm load | First token | Line (8 tok) | Decode | Peak RSS |
|---|---|---|---|---|---|---|---|
| Gemma 4 E2B (2.6 GB) | **GPU** | 39.8 s | 3.2 s | **0.29 s** | **0.79 s** | 15.0 tok/s | 2.41 GB |
| Gemma 4 E2B | CPU | 5.1 s | 0.46 s | 1.46 s | 2.30 s | 8.4 tok/s | 2.16 GB |
| Qwen 3.5 0.8B (965 MB) | CPU | 40.3 s | 34.7 s | 1.43 s | 1.59 s (2-token line) | 6.1 tok/s | 2.28 GB |
| Qwen 3.5 0.8B | GPU | **never returned** | — | — | — | — | 6.86 GB PSS before the kernel reclaimed |
| SmolLM2 360M (374 MB) | CPU | 1.9 s | 0.38 s | 7.0 s | 11.7 s | 6.6 tok/s | 1.36 GB |
| SmolLM2 360M | GPU | 12.4 s | 1.5 s | 1.45 s | 1.92 s | 12.8 tok/s | 1.51 GB |

Four reasons, in order of weight:

1. **Qwen's GPU engine does not fit.** `initialize()` never returned: PSS climbed to 6.86 GB
   while `MemAvailable` fell from 4.30 GB to 35 MB, then the kernel reclaimed. Reproduced in both
   passes and both prompt shapes. The model card predicts it (Android GPU path uses fp32
   activations; only an Adreno device is verified). Two passes × two shapes, no exception, no
   completion.
2. **On the CPU it is slower than the model it would replace** — 1.43 s to first token against
   Gemma's 0.29 s on the GPU, ~2.4 s for a line against 0.79 s.
3. **It corrupts the app's conversation shape.** Qwen3.5's GatedDeltaNet state is not reset for a
   new `Conversation` on a reused Engine (the card says: one Engine per conversation).
   `GemmaExpress` reuses one Engine and rotates conversations via `SoulBudget`. The third of
   three renders returned the system prompt verbatim instead of a line, in both passes; the
   second render of the long shape returned the user directive verbatim. Qwen's own required
   shape (Engine per render) costs 35–94 s of init per render.
4. **The 965 MB file buys no memory.** 2.28 GB resident vs Gemma's 2.41 GB — activations, float
   GatedDeltaNet projections and KV dominate. The saving is download and disk only.

It is not a runtime-version problem: 0.16.1 loads the file on the CPU (bundle floor is 0.15,
latest Android artifact 0.18.0). The blocker is memory and the recurrent-state contract.

## Recommendation

- **Default stays Gemma 4 E2B, GPU-first.** The only configuration that meets the first-token
  budget; 2.41 GB peak leaves room for the speech stack.
- **Qwen ships selectable, not default**, with its limits in the picker (CPU-only on Mali,
  ~1.4 s TTFT, ~2.4 s per line, ~35 s warm load). Worth keeping as an Apache-2.0 alternative,
  not as a speed win.
- **Restate the budget**: 300/700 ms is a GPU budget and, at 15 tok/s, a ≤6-token-line budget.
  Either the instant lane is GPU-only with a stated fallback, or the budget gets a CPU row.
- **If download size is the real motive**: SmolLM2 360M (374 MB) is the honest light option —
  1.51 GB RSS, 1.9 s per line on the GPU. It misses the budget too, but it is smaller in every
  dimension rather than only on disk.
- **Fix the catalog number**: 964,566,992 B decimal, not 920 MB.

## Files

- `android/app/src/androidTest/java/com/hermesvox/PresenceBenchTest.kt` — the harness (instrumented;
  not in any APK, not run by the gate). Cold/warm load, TTFT, line time, chunk-based decode,
  VmHWM peak, greedy sampling, presence/persona/long shapes, `freshEngine` mode, `maxTokens`,
  `threads` knobs. Also copies the model to internal storage, because a model mmap'd from
  FUSE-backed `/sdcard` measures the storage layer.
- `scripts/presence-bench.sh` — one model+backend row: size-checked push, thermal before/after,
  per-run result file in internal storage, timeout and force-stop recovery.
- `scripts/presence-bench-all.sh` — the sweep (CPU rows, then GPU rows last).
- `scripts/presence-bench-cool.sh` — settled-handset pass plus the 128-token decode measurement.
- `scripts/presence-bench-gpu-probe.sh` — memory sampling during an init that never returns.
- `scripts/presence-bench-report.py` — JSONL → the tables.
- `docs/PRESENCE-BENCH.md` — method, conditions, numbers, findings, recommendation.
- `presence-bench-results/*.jsonl` — every raw row (gitignored).

## Method notes that matter for trust

- Both passes ran on a phone shared with other agents. Pass 1 ran straight after an STT A/B on the
  same device and was 2–3x slower on identical rows (Gemma GPU cold init 107 s vs 40 s, TTFT
  1.76 s vs 1.29 s). **Pass 2 (6-minute idle) is what the doc reports**; pass-1 files carry a
  `-pass1` suffix. `Thermal Status: 0` throughout, battery 25–26 °C; the `BIG` junction reading is
  a cached 97 °C constant and is not used as evidence.
- Every adb session ran under `flock /home/c/phone.lock`; the install and the measurements share
  one window. The first attempt died because another agent's install landed mid-run — that is why
  the driver installs inside the lock.
- `Conversation.getBenchmarkInfo()` returns nothing on these conversations in 0.16.1, so all
  latency figures are wall-clock from the callback stream. Chunk count tracks tokens (8 chunks for
  a 7-token line, 20 for a 20-word line).
- Not measured: presence + STT + TTS resident together in a real call, and battery per audio-hour.
  The RSS numbers are the model in isolation.

## Git

`feat/presence`, three commits, not pushed:
`bench: add the presence-model measurement harness (D4)` ·
`bench: measure what Qwen requires, from internal storage (D4)` ·
`bench: record the Qwen/SmolLM2/Gemma numbers and the D4 recommendation`.
`bash scripts/gate.sh` → **GATE-GREEN** (under `/home/c/build.lock`).
