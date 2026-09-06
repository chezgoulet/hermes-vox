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

