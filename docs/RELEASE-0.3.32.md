# Hermes Vox 0.3.32.0 — release notes

## New capabilities
- **Single-capture barge-in** (B1): the capture path no longer stops and re-opens a
  second recorder to watch for interruptions. One `AudioRecord` keeps running the
  whole loop; during playback/generation its frames feed a barge check while the
  turn gate is closed. A pure `BargeGate` decision rule double-gates on sustained
  RMS + VAD (`barge_rms_min` 0.15, `barge_grace_ms` 500) before firing
  `event=barge-in source=single-capture mode=<playback|generation>` — a self-cut
  (speaker echo at rms~0.16) should no longer end a reply. Playback AudioTracks are
  routed as `USAGE_VOICE_COMMUNICATION` speech so the platform AEC has a real echo
  reference (`tts_voice_usage` kill-switch, default true).
- **Talk-until-you're-done endpointing** (B2, revised): an utterance is no longer
  chopped at `vad_max_ms` (15s) mid-speech. Turn ends are driven by YOUR natural
  pause (800ms silence break, unchanged). A still-talking speaker is never cut:
  past `vad_max_ms` the segment simply continues, logging one
  `event=endpoint-extended ms=$elapsedMs`. There is NO absolute time ceiling by
  default — `vad_max_hard_ms` ships disabled (0); set it positive only as an
  opt-in crash-guard for noisy environments where the VAD never releases.
  A 15-25s ramble — or a 10-minute dictation — now transcribes in full.
- **Chat connector model/provider parity** (B3): the send-text chat path now sends
  the `provider` alongside `model` (mirroring the voice responses connector), so a
  provider-qualified switch — e.g. `/models` → `deepseek-v4-flash` — is honored by
  the gateway on both connectors instead of silently ignored on the text side.

## Device-test checklist (8+ turn call, then export Settings → Debug → logs)
1. Model switch to `deepseek-v4-flash` via `/models`: label updates, next turn
   `event=turn firstByte` small, total turn latency ~2s class, no HTTP 400.
2. Long reply + speak-over: play a long reply and talk over it — expect NO self-cut
   at rms~0.16, and `event=barge-in source=single-capture` lines only when you really
   spoke.
3. 15-25s continuous utterance: expect `event=endpoint-extended`, complete transcript
   (nothing chopped mid-word).
4. Remote STT toggle + fallback (still untested on device).
5. Log export via Settings → Debug → logs; verify production log carries metadata
   only — no transcript text.
