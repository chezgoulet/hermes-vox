# Hermes Vox 0.3.32.2 — release notes

## Fixes
- **Immediate silence on barge/hush/hangup**: previously up to 4s of audio kept
  playing after the cut (the stream worker resurrected playback after every stop).
  A stopped fence now holds before the track is torn down, the VoiceController
  stream worker breaks before each chunk, and barge/hush/endCall/stop share one
  synchronous `silenceAll` path — gate release + last-audio land within ~150ms of
  the cut (`event=tts-stop reason=...`).
- **Echo-routing default OFF after field A/B (2026-09-06)**: `tts_voice_usage` now
  ships false — OFF gave loud volume AND clean barge-in (single capture + double
  gate); ON was in-call-quiet on Pixel 9. The toggle remains for leaky devices.

## Device-test checklist
1. Barge a long reply -> gate release + last-audio within ~150ms, `event=tts-stop`.
2. Hang up mid-sentence -> silence the same instant.
3. Volume loud with default settings (no manual toggle).
4. Export log via Settings → Debug → logs; verify production log carries metadata
   only — no transcript text.
