# Hermes Vox 0.6.0

The major point release that consolidates the 0.5.5→0.5.7 hardening/UX/doc arc into one
stable line, lands the settings-UX sprint, makes the app release/Play-ready, and ships the
app as **100% Android-native** (all legacy Ebitengine code removed).

## 0.6 — what it is
Hermes Vox is now a mature, honest, Android-native voice client for the Hermes agent: the
particle-being you talk to, on-device speech, hands-free. 0.6 is the first release that
carries the complete settings-UX, the ER-alpha truth-in-versioning, the docs/discovery
pass, and a clean release pipeline.

## Release-readiness + Play compliance
- **Android-native** — all legacy Ebitengine code removed (`game/`, the `mobile.go`
  `Start()` bind, Ebitengine deps). The app is Kotlin Canvas rendering + a thin, deliberate
  Go voice core (`voice/` + `mobile/session.go` — the battle-tested streaming engine).
  The `mobile.aar` bind exports only `HermesSession`.
- **Play-compliance build added to the standard release process** (`docs/RELEASE-PROCESS.md`):
  `bundleRelease` produces a signed AAB (verified `CN=Hermes Vox`), the C0 no-secret guard,
  and the upload-key/data-safety steps. Publishing to Play is on hold, but the build is
  ready and part of how we release.
- **LICENSE** (Apache-2.0) + **NOTICE**, **PRIVACY.md** (Play data-safety-ready),
  **CONTRIBUTING.md** + **SECURITY.md**, and a **GitHub Pages** discovery site.
- **Stale build logs swept** (24 committed log files removed) and `.gitignore` hardened.

## Settings-UX sprint (the big UX win)
- Voice Mode promoted to its own top-level home row (#111).
- Reset scopes unified — each setting belongs to one reset (#112).
- "Clear" (context) vs "reset" (settings) differentiated + confirm (#113).
- Model-install count unified to the required set (#114).
- Status subtitles on Settings home rows (#115).
- Speech & Mic split into Basic + collapsed Advanced (#118).
- Appearance & Visuals reconciled (#119); via-plus onboarding + learnability (#120).
- Brand-new screenshots (the being + the real Settings screen).

## Honest versioning
- **Enhanced Realtime = ALPHA** across every surface (the on-device Gemma presence layer;
  not yet wired into the immersive view — it downloads + loads via LiteRT-LM).
- Two voice modes (Realtime + Enhanced Realtime alpha). Walkie-talkie/PTT is gone (stripped
  in 0.4.0).
- Bring-your-own-gateway framing is unmissable — Vox is a client; your data stays with you.

## The being / visuals
- The particle-being identity, now with the full 20-archetype vocabulary (soundwave, arc,
  nucleus, eye, water, radar, octopus, the full ball/sphere, + the original 13).
- OLED-black design language; the app's own swarm is the GitHub Pages theme.

## Reliability (carried from 0.5.3/0.5.4)
- H1 (wall-clock streaming deadline — no more 600-event-cap reply truncation), H2
  (/compress chain unified), H3 (execSubmit crash guard wired), M1 (streamState leak fixed),
  M2-M5 + L1/L2 (conn-test probe, model-cleanup, dead-activity removal, cache LRU, history
  cap, log writer), and the Kotlin 2.2.21 toolchain bump.

## Notes
- Version 0.6.0 (versionCode 104). Built from main at the 0.5.7→0.6 arc.
- Play upload is on hold (Christopher) — the build + docs are ready.
- The Go voice core is kept deliberately (future native-Kotlin replacement = milestone #126).
- Enhanced Realtime is the next engineering focus (wire Gemma presence into the immersive
  view; issue #52).
