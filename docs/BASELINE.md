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
| cold start → first frame (`am start -W`) | pending | pending |
| warm: STT / TTS / presence | pending | pending |
| short turn: first text / first audio / full reply | pending | pending |
| presence render p50 / p95 | pending | pending |
| peak RSS / lowest MemAvailable | pending | pending |
| ten-minute call: thermal start → end, dropped turns | pending | pending |

**Status: not recorded.** The instrument landed in 0.9.1 (ResourceProbe + `baseline-report.py`);
the run needs the release APK on both devices.
