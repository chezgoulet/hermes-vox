# Hermes Vox 0.4.0 — release notes

> Ships as `0.4.0-beta1` (the whole 0.4.0 batch stays inside this single beta).

## Security (C0 — no baked gateway key)

- **Hermes Vox never ships with a key. You enter your own.** The build-time gateway
  key injection is gone. The ONLY key sources are onboarding and Settings → Entity
  re-entry, both user-entered and stored via SecureStore (encrypted at rest).
  There is no default, no fallback to a baked value, and no read site that pulls a
  compiled-in key. A missing key reaches a clear user-facing
  "enter your gateway API key in Settings" state instead of failing silently.
- A release-build guard fails the build if `HERMES_VOX_API_KEY` is set in the
  environment or a `buildConfigField` key injection is re-added to the gradle
  sources — the baked-key regression can never ship again.

## VPN usage made explicit (C1 — a private network is assumed)

- **A user-managed private network is now a product statement, not a footnote.**
  Vox speaks HTTP + bearer auth to your Hermes gateway, so it is safe only over a
  network you control. Onboarding gained a short Network panel ("we recommend
  Tailscale or Nebula"), Settings → Entity carries the same one-liner under the URL
  field, and the README states the requirement. Vox suggests these tools generically —
  it never requires a specific product.

## Walkie-talkie mode removed (C2 — hands-free realtime is the only loop)

- **Push-to-talk (PTT / walkie-talkie) mode is gone.** The walkie toggle + hold-to-talk
  button, the inline voice-reply switch, and the PTT-only keyboard row were removed
  from the main screen. Call/hang-up, hush (tap-to-stop during speech), and the voice
  channel gate are unchanged — the ✆ call button now opens the single hands-free loop.
- **The controller is single-mode.** The `continuous=false` branch (one turn then
  stop-until-push), the PTT `commitUtterance` release path, and the two-mode re-listen
  state were deleted. One loop remains: VAD endpointing → streamed entity turn →
  barge-in/retirement, then keep listening. Half-duplex state that historically lived
  at the walkie fork can no longer regress.
- **Settings + catalog trimmed.** The Voice-mode picker now offers Realtime and
  Enhanced Realtime only; the `MODE_WALKIE` constant and its references are gone.
  A stale stored voice-mode token still reads safely as Realtime (no crash, no
  migration needed). Verified by grep: zero `walkie`/`ptt` matches across the app
  (the `stopTts` identifier is the only case-insensitive substring remainder).

## Audio lifecycle hardening (C3 — focus, route changes, session hygiene)

- **Audio focus is now owned, start to stop.** The call requests `AUDIOFOCUS_GAIN`
  (VOICE_COMMUNICATION / CONTENT_TYPE_SPEECH) the moment it acquires its wake lock,
  and abandons it on the exact paths that release the wake lock today — hang-up,
  /new, conversation reset, and stop-with-no-call — so focus can never be leaked
  out of step. MainActivity is the single focus owner (documented in-code) because
  focus is a call-scoped resource whose release sites are the wake-lock paths and
  whose >30s rule runs the existing hang-up path.
- **A real interruption behaves.** `LOSS`/`LOSS_TRANSIENT` (and `CAN_DUCK`, treated
  as a full transient loss — TTS ducking is unavailable on the one-track writer, so
  silence beats garble) immediately silence the in-flight reply through the ONE
  silence path (`silenceAll("focus-loss")`) and park the mic — the foreground
  service and the call stay live, so re-accepting is one tap. `GAIN` resumes
  *listening only* on a fresh-turn window (the interrupted reply is not auto-
  resumed). If the outage lasted >30s the call hangs up cleanly instead of re-arming
  a zombie mic.
- **Route changes rebuild the line.** An `AudioDeviceCallback` is registered per call
  and unregistered on stop. When a BT (SCO/A2DP), USB, or wired headset appears or
  disappears mid-call, the app logs `event=audio-route devices=…` and rebuilds
  capture + playback with a fresh-call composition (stop → 250ms → start) so both
  halves re-attach to the new default route. No SCO routing code was added (out of
  scope) — `VOICE_COMMUNICATION` capture follows whatever route the platform keeps.
- **Context creep is measurable.** `event=turn` now carries `session_turns=N`, the
  count of completed turns since the last `resetConversation`/new call, so gateway
  history growth is visible in field logs. Measurement only — no truncation or
  summarization yet (no defensive limit without data).

## Log honesty (C4 — firstAudio at real audio, bounded VoxLog)

- **`firstAudio` now means what its name says.** It was pushed on the first *text*
  delta, overstating-to-misstating when audio actually started. `event=turn` now
  records `firstText=` (first text delta) and keeps `firstAudio=` pushed only when
  the first real audible streamed-TTS write of a turn completes — a genuine audio
  milestone, measured from the same launch origin as `firstByte`/`firstText`.
  ⚠ **Field numbers are NOT comparable across versions:** any `firstAudio=` quoted
  before this change (in earlier 0.4.0 betas or the 5118/19348/29962ms values in
  the C4 spec) measured text, not audio, so treat pre- and post-C4 values as
  different quantities. A text-only turn (voice channel closed / non-streaming
  engine) now honestly reports `firstAudio=-` instead of a text latency in disguise.
- **The runtime log is bounded.** `hermes-vox.log` is capped at 5MB and rotates
  current → `hermes-vox.log.1` (single generation) on exceed — checked on open and
  every ~50 writes, never per line. The Settings export/copy ships the **merged
  pair** (old generation + current) so field logs stay complete across the seam;
  "Clear logs" clears the whole rotated set. No new permissions; the debug-only
  (`dd`) channel is unchanged.

