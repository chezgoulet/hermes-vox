# Performance baseline, per release (#209)

The point is **drift**: the same list at each cut, so a regression is visible before a user
reports it. `docs/PRESENCE-BENCH.md` and `docs/STT-AB.md` measured the parts alone; this is the
assembled product.

## How to record it

1. Install the **release APK** (not a debug build), settings untouched, on both devices.
2. Cold start: force-stop, clear from recents, launch; place a call.
3. One short turn ("what time is it?"), then a ten-minute call with ordinary use.
4. Hang up. Settings → About → *Share full log*.
5. `python3 scripts/baseline-report.py <log>` — paste the table below, one column per device.

What the table reads (all logged by the app, none estimated): the `loaded:` lines for model
warm; `event=turn` for speech→text, first byte, first text, first audio and full reply;
`event=er-soul ms=` for the Presence render; `event=res` / `event=res-summary` (ResourceProbe,
every 10 s of a call) for peak RSS, the lowest `MemAvailable` and the thermal state at start
and end; `event=turn-dropped` and failed outcomes for dropped turns.

**Not yet logged:** cold start to first frame. Until it is, record it by hand with
`adb shell am start -W com.hermesvox/.MainActivity` (`TotalTime`), and say so in the row.

## 0.9.1

| Measure | Pixel 7 | Pixel 9 |
|---|---|---|
| cold start → first frame (`am start -W`, connected) | 157 / 169 / 209 ms | pending |
| warm: STT / TTS / presence | Whisper base 590 ms · Nemotron load 4290 + warm 3033 ms · Supertonic 295–395 ms · Gemma 7.3 s (4.3 GB free) to ~50 s (0 MB free) | pending |
| short turn: first text / first audio / full reply | pending (no spoken turn yet) | pending |
| presence render p50 / p95 | greeting 1216 ms (n=1) | pending |
| peak RSS / lowest MemAvailable | Realtime: 638 MB / 94 MB · Enhanced + Nemotron: 3306–3490 MB / 0–1089 MB (see below) | pending |
| ten-minute call: thermal start → end, dropped turns | 11 min Enhanced, all four legs: none → none (67 samples, skin ≈ 27 °C), 0 dropped | pending |

**Pixel 7 conditions (2026-10-22).** Nightly `ca0e66f`/`097e1be`, not the release APK; on USB
power (so no battery figure). The phone carried another app (`org.phononai.app`) holding a
2.5 GB on-device model resident; with it running, MemAvailable reached **0**, Gemma took ~50 s
to load and the low-memory killer cleared dozens of apps, including that one (#191, #179).
Without it, a 5.3 min and an 11 min Enhanced call bottomed at 1088–1089 MB available. The
memory row is therefore a property of the phone's whole load, not of Vox alone.

### Co-residency, one live call (#179) — Pixel 7, 2026-10-22

Ten minutes and nine seconds on the clean phone: `org.phononai.app` disabled (it holds a 2.5 GB
on-device model), nothing else resident. Enhanced Realtime — Silero VAD + **Nemotron streaming
(160 ms)** + Supertonic TTS + the Gemma presence renderer — through the shipped pipeline. 13 turns
from 12 pre-recorded LibriSpeech clips (one played twice as a deliberate interruption), about 50 s
apart.

| Measure | Value | How |
|---|---|---|
| STT RTF in situ | p50 **0.71**, p95 **0.93**, max 1.17 (n=9) | `event=stt-live finishMs` ÷ (restMs + 500 ms tail) |
| STT decode per segment | 540–3608 ms for 0.9–4.8 s of tail audio | `event=stt-live` |
| peak RSS | **3467 MB** | app's own `event=res-summary` |
| worst `MemAvailable` | **688 MB** (median 891) | `event=res`, 72 samples |
| presence render per turn | 2582–3362 ms; greeting 1444 ms | `event=er-soul ms=` |
| CPU (whole run) | **185.8 % of one core** (23.2 % of 8) | `/proc/<pid>/stat`, 121 samples / 604 s |
| thermal, start → end | skin 29.4 → 30.8 °C, battery 26.3 → 28.3 °C, status **none → none** | `event=res`, `dumpsys thermalservice` |
| dropped turns / stutter | 0 dropped, 0 `stt-reject`; 5 turns cut by the next clip during the reply | `event=turn` outcomes |
| spoken turn → first text / first audio | p50 9.06 s / 11.81 s (first byte 3–8 ms) | `event=turn` |
| battery | **pending** — USB powered (level 75 → 73 % over the run is not a measurement) | — |

**Conditions, and how the caller was produced.** The reviewer cannot speak, so the caller was 12
public-domain LibriSpeech clips from `tools/sttbench/corpus`, amplified 15 dB and played through the
phone's own speaker by a throwaway 60-line player that deliberately does **not** request audio
focus (a media app that takes focus makes Vox pause its mic — its C3 focus-loss path — so the clip
would play into a closed mic). Media volume 13/25 (max is 25): at 25/25 the being's own replies were
loud enough to re-enter as turns, at 13/25 they did not barge (echo floor 0.036–0.039, 85 near-misses,
6 barges, all from the clips). **Mic source `MIC`, AEC and the extra noise suppressor off:** with
`VOICE_COMMUNICATION` the phone's own AEC cancels its speaker almost completely — a 3.3 s clip
produced a segment with 768 ms of speech and an **empty** transcript, and a louder clip produced no
segment at all. That is AEC doing its job, and it is why a spoken-turn row has to be measured with
AEC off: the numbers above are therefore **not** the shipped default's barge behaviour.

Transcripts came back close to the references (`"Was at the March election eighteen fifty five"`,
`"Yes, I do because as you know, having a master's degree can just give me a better paid job later"`),
so these were real spoken turns, not noise that passed the validator.

**Two instrumentation gaps this run walked into.** (1) `event=turn stt=` is `now - segStart`, where
the segment opens when the mic loop starts, not when speech does — an idle segment reports its idle
time (165 s and 76 s outliers in this run), so it is not a decode time; use `event=stt-live`. (2)
Presence **first token** is not logged by this build — `event=er-soul ms=` is the whole render — so
the 289 ms first-token baseline in `docs/PRESENCE-BENCH.md` cannot be compared to these rows.

> **Both gaps are closed in `feat/memory-budget`.** `event=turn` now carries
> `speechEndToFirstText=`, `speechEndToFirstAudio=`, `speechEndToRenderDone=` and `sttDecodeMs=`
> on the same line as the old fields, measured from the last speech sample rather than from the
> segment's open; `event=er-soul-first-token ms= intent=` logs the render's first token on the same
> stream. The rows above keep their old meaning and are still comparable to each other.

**Status: partly recorded.** Pixel 7 rows from nightlies, spoken turn now measured (AEC off, clips),
no RTF-first-token equivalent yet; Pixel 9 and the release APK pending.

## Memory: what the presence layer holds, and what releasing it returns

Measured 2026-10-24 on the Pixel 7 (`2A091FDH200AJ1`, USB, `org.phononai.app` disabled), with the
**installed nightly `0.9.0.1-nightly-097e1be`** — the 0.9.1 code, Enhanced mode, the presence model
installed and loaded. Nothing about the build was changed for these rows: no call was placed, no
setting was touched. `MemAvailable` is read from `/proc/meminfo`, the footprint from
`dumpsys meminfo com.hermesvox`.

The question is the one the release has to answer: *how much can the app give back without changing
how it behaves?* On this phone the answer is **≈2.2 GB, and the price of taking it is 5.6–7.0 s of
reload** — a trade, not a free win.

### 1. What is resident while idle

Two days after the last call, with no activity on screen and no call live, the process still held
everything it had ever loaded:

| Measure | Value | How |
|---|---|---|
| `MemAvailable` seen | **967–1221 MB** (of 7468 MB total) | `/proc/meminfo`, 3 samples |
| total PSS / RSS | 2 366 353 kB / 1 985 292 kB | `dumpsys meminfo` |
| of which GL-tracked | **1 878 912 kB private dirty** | `GL mtrack` row |
| native heap alloc | 707 198 kB | `Native Heap` row |
| swapped out | 462 392 kB | `TOTAL SWAP PSS` |
| activities / views / frames | 0 / 0 / 0 | `dumpsys meminfo`, `dumpsys gfxinfo` |

The GL share is not the being: that process had no activity, no surface and no frame. It is the
GPU backend's own allocations, which is where the 2.5 GB model file ends up when it is loaded
(1.79 GB of it resident, the rest file-backed and reclaimable).

### 2. The A/B that needs no new build (process death = release)

| State | `MemAvailable` | Δ |
|---|---|---|
| force-stopped (no app) | **3144 MB** | — |
| app launched, presence loaded, idle, no call | 1221 MB | **−1923 MB** |
| force-stopped again, then relaunched | 3472 MB → 1052 MB | **−2420 MB** |

Two different pre-states, same conclusion: the app holds **1.9–2.4 GB** it could return the moment
it stops being a presence. It never returns any of it today, on hangup or on leaving Enhanced.

### 3. What releasing it costs (n=3 cold loads)

Each row is a force-stop, a settle, then `am start` — i.e. exactly what "unload on teardown" would
make the *next* call pay, with the current code's cold path:

| Run | `MemAvailable` before | after | Δ | `event=presence-load` |
|---|---|---|---|---|
| 1 | 3029 MB | 1201 MB | 1828 MB | 7008 ms |
| 2 | 3496 MB | 1266 MB | 2230 MB | 6049 ms |
| 3 | 3499 MB | 1248 MB | 2251 MB | 5636 ms |

Also logged per load: `primeMs=2736` then a warm probe of `980 ms` (backend `gpu`, `hears=true`), so
the 5.6–7.0 s is the end-to-end "the soul can speak again", not the engine construct alone. Cold
start to first frame stayed at 364 ms (`am start -W TotalTime`).

### 4. What is not in these numbers

- **The KV-cache arms (4096 / 2048) are unmeasured.** They need a build that reads the pref
  (`MemoryBudget.PREF_KV_TOKENS`, dev-only: long-press "Verbose file log" in Settings). The cache
  lives inside the 707 MB native allocation, so these rows cannot separate it from the weights.
  One consequence is already structural: `SoulBudget.HEADROOM_TOKENS` is a fixed 1024, so a 2048
  cache can use **1024 tokens** for persona (~600) plus history before it rotates — the small arm
  is not "2 GB cheaper, same voice", it is a shorter memory with a persona re-prefill each rotation.
- **The 30-prompt substance comparison is unmeasured** for the same reason. The soul's output at each
  arm has to be read, not inferred.
- **RTF / peak RSS over a 10-minute Enhanced call are unmeasured here**: this host's Hermes gateway
  exposes no `/v1/responses` (`config.yaml` has no API server; the running gateway is messaging
  only), so no call could be placed. The 0.9.1 co-residency row above remains the reference.
- **The sherpa arena setting does not exist in this binding.** `sherpa-onnx 1.13.6`'s
  `OnlineModelConfig` / `OfflineModelConfig` Kotlin constructors expose `numThreads` and `provider`
  and no CPU-memory-arena flag (`javap` on the pinned AAR), so "arena off/shrunk" cannot be a runtime
  A/B at all. Threads already are one: `voice_threads` (Settings → Voice CPU threads, 1–4).

### Recommendation per phase

- **Phase 1 (instruments): take it as-is.** Both fields are additive, unit-tested, and leave every
  previously recorded number comparable.
- **Phase 2 (KV cache): do not shrink on this evidence.** The measured prize is in phase 4 (2.2 GB),
  not in the cache, which these rows cannot even resolve; and 2048 trades voice memory for rotation
  cost before it trades any megabytes. Run the three arms on a signed build before deciding.
- **Phase 3 (allocator/threads): arena is impossible in this binding; leave threads alone.** The
  shipped 185.8 % of one core and max RTF 1.17 have no measured headroom story yet, and a setting
  whose max RTF reaches 1.0 is rejected by the brief — unmeasurable here, so unchanged.
- **Phase 4 (unload on call end): the only lever with a measured number, and it is a real trade.**
  2.2 GB back, 5.6–7.0 s to reload, paid inside the call's warm gate. For a phone that is otherwise
  at 688 MB free during a call (#179), that is the difference between the low-memory killer clearing
  other apps and not. Implemented behind `MemoryBudget.PREF_UNLOAD`, default **off**; the owner
  decides.

**Status: partly recorded, and the gaps are named.** Measured: the resident cost of the shipped
presence layer, and the cold reload it would cost to release it. Unmeasured: the KV arms, the
output comparison, the in-call RTF/RSS, and the warm (process-alive) reload — all blocked on an
installable instrumented build (the release keystore is not on this host) and a reachable mind.
