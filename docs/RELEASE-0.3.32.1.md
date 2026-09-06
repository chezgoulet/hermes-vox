# Hermes Vox 0.3.32.1 — release notes

## New capabilities
- **Settings toggle "Voice-call echo routing"** (default ON): a switch under
  Settings → Mic/Speech that routes TTS playback as `USAGE_VOICE_COMMUNICATION`
  speech so the platform AEC has a real echo reference. OFF restores loud
  media-volume playback if the incall bus is quiet on your device — test both
  positions.
- **Barge sensitivity is now a slider**: the `barge_rms_min` floor and
  `barge_grace_ms` grace are adjustable seek bars under Settings → Mic/Speech.
  The default floor is lowered 0.15 → 0.10 after the 0.3.32.0 field log proved
  real speech sits above 0.15 (echo levels 0.153–0.172 overlapped the old floor
  and ate the user's own barge-in). The VAD agreement + sustain remain the echo
  defense.

## Device-test checklist
1. Volume comparison turn: Settings → Mic/Speech → "Voice-call echo routing" ON
   vs OFF — confirm which bus is loud on your device.
2. Talk over a long reply with the toggle OFF — expect
   `event=barge-in source=single-capture` ONLY on your voice, no `aec` regression
   (self-cut at rms<0.15 with hardware AEC on the main capture = acceptable
   finding, log it).
3. Settings → restore defaults works and brings back echo routing ON,
   barge rms 0.10, grace 500ms.
4. Export the log via Settings → Debug → logs.
