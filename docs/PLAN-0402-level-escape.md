# SPEC — 0.4.0.2: level-sustained barge escape (VAD veto under echo)

## Field evidence (2026-09-07 0.4.0.1 log — probes did their job)
- ZERO barge-gap lines -> starvation theory dead (cause A eliminated by absence).
- SIX barge-nearmiss lines, peakRms 0.071-0.161; sustainedMs always 0 or 64.
- Every FAILED attempt shows vad=false at level >= 0.123; every SUCCESS had vad=true.
- vad?.reset() audited: only at VoiceController.kt:507 (loop iteration end, after
  gate release) -> teardown-stomper theory DEAD too.
- Mechanism (single-capture reality): loud TTS bleeds into the same recorder
  (leak era measured ~0.15-0.17). During tails, the mixed frame is echo-dominant:
  Silero VAD says false at the frame level AND the user's 200ms-vad path needs
  vad=true on the CURRENT read. sustainedMs=64 shows level crosses floor then a
  sub-floor read zeroes it before 200ms — short phonemes can't accumulate.
- Conclusion: under echo-dominant playback, need a path that ignores VAD but uses
  a slightly raised bar: level > floor*1.6 sustained >= 400ms. The user's voice
  sustains through phoneme gaps; a sub-floor read still zeroes the accumulator.
  (Note: at 1.4x/350ms the existing NO_VAD bar would already catch attempts at
  0.161 — but echo peaks also sit in that band (leak era: 0.15-0.17), hence 1.6x
  bar + 400ms, whose extra duration margin is what separates content-modulated
  echo peaks from intentional sustained speech. Risk stated honestly in notes.)

## L1 — BargeGate: add level-sustained escape (pure function change)
decide(level, vad, sustainedMs, rmsMin, vadAvailable, levelOnlyMs):
  - existing vad path unchanged (rms>floor, vad true, sustained>=VAD_SUSTAIN_MS)
  - NEW: if levelOnlyMs > 0 && sustainedLevelMs >= levelOnlyMs &&
         level > floor * LEVEL_ONLY_BOOST (=1.6f, reuse pattern of NO_VAD boost),
         fire regardless of VAD. Track sustainedLevelMs with the 1.6*floor bar
         (separate accumulator from sustainedMs — the caller tracks BOTH:
         reset-to-0 on drop below its own bar).
  - default levelOnlyMs = 400L (enabled); 0 disables (escape hatch pref).
  - Update BargeGateTest truth table: level-escape rows (fires at 0.16/400ms even
    vad=false; no fire at 0.155/450ms; no fire with levelOnlyMs=0; no fire when
    sustained below own-bar despite momentary 0.3 like the successful barge frames
    that already fire via vad path).
## L2 — Pref + Settings: 'barge_level_only_ms' slider 0..800 step 50 (default 400).
  Label: 'Level-only barge (ms, 0=off)' helper: 'Cut the entity with a sustained
  loud voice even when VAD misses it through speaker echo. Raise if TV/noise barges.'
  restore-defaults covers it.
## L3 — versionCode 81 versionName 0.4.0.2 + docs/RELEASE-0.4.0.2.md
  Notes MUST state the leak-band risk honestly: self-cut regression possible at
  loud media playback; dial to 0 disables; report event=barge-in lines that appear
  while nobody spoke.

## Rules
- Touch nothing else: silence, retirement, fence, focus, endpointing, probes stay.
- The two accumulators in the caller are O(1) floats; reuse readAt clock discipline.
- Gate: /home/c/gradle-8.12.1/bin/gradle :app:testDebugUnitTest -q exit 0.
- Commit 'barge: level-sustained escape for echo-masked speech — 0.4.0.2'
- Print L_DONE <hash> + ACCUMULATOR_SITES <file:line> + ESCAPE_ROWS <tests added>.
