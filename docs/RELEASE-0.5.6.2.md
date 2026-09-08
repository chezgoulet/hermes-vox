# Hermes Vox 0.5.6.2 — ER-alpha labeling + docs accuracy

Bundles the Enhanced Realtime (alpha) labeling and the docs-accuracy fixes (the P0s from
Christopher's documentation audit).

## Enhanced Realtime is now honestly alpha
All six in-app "Enhanced Realtime" surfaces now carry "(alpha)":
- mode picker (Settings), modeLabel, onboarding voice-mode line,
  ModelCatalog Gemma model + description, ModelsActivity text.
README + status reflect it too (two voice modes; ER = alpha; the earlier
"wired end-to-end" claim corrected — the Gemma presence layer isn't wired into
the immersive view yet).

## Docs accuracy (P0s from the audit)
- **P0-1** — walkie-talkie mode + screenshot removed (it was stripped in C2; the code has
  no walkie/PTT). README now says "two voice modes."
- **P0-2** — screenshot captions corrected; the main_rest/onboarding duplication +
  settings.png noted for recapture (a device task).
- **P0-3** — the in-app API-key string told a falsehood ("never stored in the app"). The key
  IS stored encrypted (SecureStore, reloaded each launch). Now: "encrypted on-device, never
  sent anywhere but your gateway" — truthful.

## KEEP-list — verified
Label strings + README/docs only; no logic/behavior changed. Gated green on fresh clones
(ERALPHA-GATE-EXIT=0, DOCS-GATE-EXIT=0).

## Notes
- Version 0.5.6.2 (versionCode 102).
- The docs audit also flagged ROADMAP.md/TODO.md (describe the original boilerplate project),
  the TTS-default framing, and the vestigial game//cmd/app + stray WASM binary — those are
  Christopher's product/repo-hygiene calls, tracked separately.
