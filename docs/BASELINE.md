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

**Status: partly recorded.** Pixel 7 rows from nightlies, no spoken turn yet; Pixel 9 and the
release APK pending.