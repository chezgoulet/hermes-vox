# PLAN 0.4.0 — the polish series: no baked secrets, VPN explicit, walkie out, harden in

**Directive (Christopher, 2026-09-06):** 0.3.32.4 is the LAST .3 release. 0.4.0 is
entirely about polishing what exists, and only after it ships do we open Enhanced
Realtime. Non-negotiables: (1) NO keys baked into the APK whatsoever — the user
enters their own API key during app bootstrap; (2) it is EXPLICIT that a user-managed
VPN is assumed (recommend Tailscale and Nebula); (3) walkie-talkie (PTT) mode is
STRIPPED; (4) the codebase catches up on the bad things Torc flagged (below).

## C0 — Remove the baked gateway key (security P0)
- android/app/build.gradle (~:26-28): delete the buildConfigField env-injection of the
  Hermes gateway key. grep the whole app for the BuildConfig field NAME to find every
  read site (MainActivity storedKey/connectFromPrefs path, OnboardingActivity, any
  SecureStore seeding).
- New rule: the ONLY key sources are (a) onboarding/user-entered key stored via
  SecureStore, and (b) Settings re-entry. If no key: connector errors with a clear
  "add your gateway API key in Settings" state (existing empty-key error path, made
  user-facing, not silent).
- BUILD GUARD: release build FAILS if the old env var is set/used anywhere
  (cheap grep step in build.gradle) so we can never accidentally regress.
- Release notes + README: "Hermes Vox never ships with a key. You enter your own."
- Tests: unit test that the connector requires a user-set key (no default).

## C1 — VPN-usage made explicit (trust surface)
- Reality: HTTP + bearer key to a gateway address. Safe ONLY under a user-managed
  tunnel/VPN. Make it a product statement, not a footnote:
  - Onboarding step (after URL): short "Network" panel — "Vox expects you to reach
    your Hermes gateway over a private network you control. We recommend Tailscale
    or Nebula. Without a VPN, your key and audio transits plain HTTP."
  - Settings → Entity: same one-liner under the URL field.
  - README + release notes: VPN requirement + the two recommendations.
- Keep naming generic: the app suggests, never requires a specific product.

## C2 — Strip walkie-talkie (PTT) mode
- Remove: the walkie toggle/UI (RealtimeActivity button, layout bits),
  `continuous=false` branch paths in VoiceController (post-turn stop-listen,
  commitRequested PTT path), #19 leak-fix comment sites that only exist for PTT,
  and any settings/logs referencing it. Realtime hands-free becomes the single
  loop shape — this deletes the most stateful corner of the controller (and every
  half-duplex bug we fought was simpler because two modes shared one loop).
- Keep: hush (tap-to-stop), call button, gate machinery.
- Verify by grep: zero hits for walkie/PTT symbols after removal; tests updated.

## C3 — Audio-lifecycle hardening (the things that bite in public)
1. **Audio focus**: implement AudioManager focus handling — on LOSS/LOSS_TRANSIENT
   duck/pause TTS + stop capture cleanly; on gain, resume. (Today: none found —
   a real phone call arriving mid-reply is undefined behavior.)
2. **BT/USB route changes**: register AudioDeviceCallback; on routing change during
   a call, re-init capture/track like a fresh call start (log event=audio-route).
3. **Session hygiene**: on new-conversation (or reconnect), surface that long
   /v1/responses chains grow context (gateway history growth observed: 2056->8204
   tokens); log cumulative per-session turn count in event=turn (session_turns=)
   so creep is visible; no behavior change yet — measurement first.

## C4 — Logging correctness (known lies in the data)
- firstAudio: currently pushed on first TEXT delta — push on first ACTUAL audio write
  instead; keep both as firstByte/firstAudio/firstSpeak if cheap. (Field numbers
  quoted from it overstate audio start; fix before Enhanced Realtime baselines.)
- VoxLog file rotation (size cap + one generation) — rolling public-app logs must
  not grow unbounded.

## C5 — Stress gate (the checklist we skipped; run ONCE per release now)
On-device, post-0.4.0: (1) rapid start/stop x10, (2) background->foreground mid-call,
(3) airplane-toggle mid-turn, (4) BT headset connect/disconnect mid-reply,
(5) incoming call during speech (focus test), (6) 20-turn chain watching
session_turns + fullReply creep. Log export after each. Known-unknowns to watch:
cleanup-thread vs new-call start (F1 new thread), pause-first on BT routes.

## Definition of done for 0.4.0
All commits gated (:app:testDebugUnitTest exit 0, never weaken tests); grep proof of
no-baked-key and no-walkie; onboarding flows tested on the Thelio-built signed APK;
GitHub release (the standard chain) + README truth. THEN — and only then —
Enhanced Realtime planning resumes (END_SENTENCE streaming, thermal gating,
presence glue, TurnBench).
