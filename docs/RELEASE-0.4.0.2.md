# Hermes Vox 0.4.0.2 — release notes

> The level-escape release: a second, VAD-independent barge path for the missed
> barge-in under echo-dominant playback, plus its honest leak-band risk. Zero
> behavior change to silence, retirement, fence, focus, endpointing, or the
> 0.4.0.1 probes.

## Why (the 0.4.0.1 field evidence)

The probes did their job and eliminated two whole theories:

- ZERO `barge-gap` lines → mic-drain starvation is dead (cause A).
- `vad?.reset()` audited to the one site after gate release → teardown-stomper is
  dead too.
- SIX `barge-nearmiss` lines (peakRms 0.071-0.161, sustainedMs always 0 or 64):
  every FAILED attempt shows `vad=false` at level >= 0.123; every SUCCESS had
  `vad=true`. Under loud TTS the mixed single-capture frame is echo-dominant and
  Silero says false at the frame level, while short phonemes can't hold the floor
  for 200ms — the VAD path starves even when the user is audibly interrupting.

## Level-only escape (the fix)

BargeGate now has a second, additive decision path. The drain loop maintains a
SECOND accumulator (`sustainedLevelMs`) beside the existing `sustainedMs`: the
contiguous time the RMS holds ABOVE a raised bar — the active floor × 1.6 (the
shipped 0.10 floor → a ~0.16 bar) — reset to 0 independently on a sub-bar read.
When that level-only sustain reaches the `barge_level_only_ms` pref (default
400ms, slider 0-800ms step 50, 0 = off) the gate fires REGARDLESS of VAD. The
existing VAD double-gate and no-VAD fallback are byte-for-byte unchanged; the
escape is purely additive and can never veto a VAD-path fire. The extra duration
margin (400ms > the 350ms no-VAD bar) is what separates content-modulated echo
peaks from intentional sustained speech.

## Honest risk: the leak band is real

This path ignores VAD by construction, so its level band overlaps the measured
playback leak (0.15-0.17): a SELF-CUT REGRESSION IS POSSIBLE at loud media/TTS
playback that holds the raised bar past `barge_level_only_ms`. That is the
accepted trade for catching echo-masked speech, and it is fully user-controlled:

- Dial **Level-only barge to 0** in Settings → Speech & Mic to disable the escape
  and return to the VAD-only double gate.
- Raise the slider (toward 800ms) if TV/noise barges through.
- If the entity cuts itself off while nobody spoke, that is the leak band firing:
  report the `event=barge-in source=single-capture` lines (rms in the leak era,
  `vad=false`, playback mode) from the exported log.

## Version

- `versionCode 81` · `versionName 0.4.0.2`.

## Device test

Talk over long replies (the 17s/44s tails) and watch for barge-in lines that fire
with `vad=false` — the echo-masked speech now cuts through. Then play loud media
over a reply and confirm no `event=barge-in` fires while nobody spoke; if it does,
raise the slider or set it to 0.
