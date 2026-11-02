# Budgets for the continuous lane — 0.9.1

`docs/PLAN-0.9.1.md` §2 requires an explicit CPU, GPU and battery budget, because the continuous
listening lane is the first thing in this app that keeps the microphone, the recogniser and the
renderer awake at the same time, and that collision is exactly what F4 (GPU contention) and F5 (the
thirty-minute soak) are meant to catch.

This document states the budgets, how each one is measured, and — kept separate on purpose — what
has actually been measured so far. A number that has not been measured is a **proposal**, and is
labelled as one. Nothing here is inferred from how the device felt.

Device class: the 8 GB phone this release is gated on (Pixel 7, `2A091FDH200AJ1`). Budgets on a
larger device are not evidence for this class.

## The budgets

- **Startup — 250 ms cold start, median of ten.** Measured from `am force-stop` to the first frame,
  by `am start -W` and the app's own `lat` line. Failure to hold this is a release blocker: it is the
  first impression and it was already 161 ms.
- **Memory, idle — 220 MB PSS.** Idle means the main screen with the being visible and no call.
- **Memory, one live call with all three local models warm — 800 MB PSS.** The stack that must fit
  is VAD + TTS + Whisper `base.en`. Any co-resident local renderer is measured separately and does
  not get to hide inside this figure.
- **Memory ceiling for the whole process — 1.5 GB PSS, and no growth over a thirty-minute call.**
  A call that climbs is a leak regardless of where it stabilises.
- **GPU — the render must not starve audio.** The rule is behavioural before it is numeric: no
  dropped audio frame, no missed endpoint, no `event=barge-skipcheck` cluster, while the being is
  animating. Numeric companion: mean GPU utilisation during a render stays under 60 % of the
  device's reported maximum, sampled by `dumpsys gfxinfo com.hermesvox`.
- **CPU — under 25 % of one core at idle, under 60 % during a call on the local models**, sampled
  with `top -b -n 1 -p $(pidof com.hermesvox)` at ten-second intervals. The continuous lane is
  exempt from the idle figure only while a call is actually live.
- **Battery — no worse than 4 % per hour of a live call** on the 8 GB class, from
  `dumpsys batterystats --charged com.hermesvox`, measured across the F5 soak rather than estimated.
- **Thermal — no throttle state change during the thirty-minute soak.** A `THERMAL_STATUS_*`
  transition mid-soak fails the budget even when every other number holds.

## How each one is taken

- Cold start: `scripts/verdict-f8.sh` and the ten-run procedure already used for #209
  (`am force-stop`, three-second pause, `am start -W`).
- Memory: `dumpsys meminfo com.hermesvox | awk '/TOTAL PSS/'`, every two seconds, via
  `device-session.sh`; peak and the last sample both recorded.
- Latency: the app's own ledger — `event=lat ... metric=speech-to-audio p50=… p95=… n=…` — so the
  figure comes from the code path under test rather than from a stopwatch beside it.
- GPU: `dumpsys gfxinfo com.hermesvox` before and after, reporting frame counts and jank percentage.
- Battery and thermal: `dumpsys batterystats --charged` and `dumpsys thermalservice`, both taken at
  the start and end of the soak so the delta is the measurement.

## What has been measured, and what has not

Measured on `0.9.1-nightly-2ed5714` (release-signed, versionCode 147):

- cold start, n=10: 156, 156, 156, 159, 161, 161, 162, 165, 167, 178 ms — median 161.0
- idle PSS: 162 MB
- one live call, three models warm: 640 MB, recorder `src:VOICE_COMMUNICATION`, not silenced

Not yet measured, and therefore not yet claimable:

- RTF and peak RSS with a local renderer co-resident (#179 — blocked on the 2.6 GB model download)
- GPU utilisation during animation (F4)
- the thirty-minute soak, memory slope and thermal trace (F5)
- battery per hour of live call

**The rule that keeps this document honest:** a budget is a budget only once it has a measured
baseline on the gated device. Until then every figure above is a ceiling someone proposed, and this
section is the list of what still has to be earned.
