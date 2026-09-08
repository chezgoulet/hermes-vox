# Hermes Vox 0.5.5.2 — iris centers properly

Patch on 0.5.5.1. The previous eye-fix narrowed the dart range but the iris still chased a
new target every ~1.15s forever, so it never rested at center and never read as an eye.
Christopher: "a real iris sits centered and only moves a small margin."

## The fix — A_SEEKER (the eye), behavioral
The iris now behaves like a real iris:
- **Rests at dead center (0,0) ~72% of the time** — default state is looking at you.
- Every ~3s a **brief saccade** (~28% of the cycle) darts to a **small ±0.30 margin**
  (~15% body radius, commensurate with real iris movement) via an ease-in/out envelope
  (`fsin`), then **eases back to center**.
- No more continuous chasing — the eye sits centered, glances a small amount, returns.

## KEEP-list — verified
Only the A_SEEKER look-target updater in AvatarView.kt touched. Geometry, blink cadence, and
all other shapes unchanged. Built + gated on a fresh clone: EYECENTER-GATE-EXIT=0,
BUILD SUCCESSFUL. (Also caught + fixed a duplicate `val per` shadow-introduced during the
edit — the saccade period is now `saccPer` so it compiles clean.)

## Notes
- Version 0.5.5.2 (versionCode 96). Patch on 0.5.5.1 (95).
