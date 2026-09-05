# Hermes Vox 0.3.31.0 — release notes

## New capabilities
- **Per-turn evidence logging** (PR #62): every settled turn emits
  `event=turn … outcome=… stt=… firstByte=… firstAudio=… fullReply=…`;
  P50/P95 latency stats now ride a true rolling 8-turn window.
  A debug/production split keeps the exported production log to warnings +
  errors unless Settings → Debug (debug_log) is enabled.
- **Remote STT backend** (PR #63): Settings → STT backend → *remote* lets you
  point the app at any OpenAI-compatible `POST /audio/transcriptions` server
  (URL + model + optional bearer key; key stored via SecureStore). On probe or
  call failure the leg swaps back to the on-device whisper (or platform) with
  an `event=stt-fallback` log line — selecting remote can never break the loop.
  Privacy: audio leaves the device only when you select remote and save a URL;
  logs carry metadata (durations, lengths) only, never transcript text.

## Device-test checklist (8+ turn call, then export Settings → Debug → logs)
1. `event=turn` lines appear for every turn with non-empty `stt=` and
   `firstAudio=` fields (validates the P0 logging on real hardware).
2. Mode A on-device: record p50 of the `stt=` values.
3. Mode B remote (after exposing the voice server on your network): same
   p50 — this A/B is the measured case for remote STT.
4. Kill the STT server mid-session: expect one `event=stt-fallback` and the
   loop continuing on-device (no dead turn).
5. Barge-in during a reply: expect `event=barge` arm/fire lines and
   `outcome=barge-in` on that turn.
