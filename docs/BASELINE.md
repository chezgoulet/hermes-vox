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

**Status: partly recorded.** Pixel 7 rows from nightlies, spoken turn now measured (AEC off, clips),
no RTF-first-token equivalent yet; Pixel 9 and the release APK pending.