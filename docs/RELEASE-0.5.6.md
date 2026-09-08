# Hermes Vox 0.5.6 — settings-ux, P0 wave 1 (voice mode, reset scopes, bootstrapping)

Release of the first wave of the settings-UX sprint (issues #111-#114 + bootstrapping #6,
#12, #16). Two non-colliding sub-agents (Settings vs Models/Main), KEEP-list clean, gated
green on a fresh clone (UX-GATE-EXIT=0).

## The settings flow is more logical
- **#111 Voice Mode promoted** out of Entity & Connection to its own top-level row on the
  Settings home (it's the app's headline interaction model — no longer buried under
  gateway/endpoint/key).
- **#112 Reset scopes unified** — each setting belongs to exactly one reset. Voice Mode
  folds into "Restore defaults (Entity & Connection)"; voice register folds into
  "Restore defaults (TTS & Voice)". Removed the overlapping standalone Mode/Voice reset
  rows (was a silent surprise: resetting Entity also reset your mode).
- **#113 "clear" vs "reset"** — "New conversation" now reads "clear" (distinct from
  settings-restore "reset") and has a confirm dialog, since it wipes conversation context.
- **#114 Model count unified** — the home badge and the Models screen both count the
  required (recommended) set, so "3/3 required" agrees everywhere (no more 0/6 vs 0/3).

## Bootstrapping (the app now guides the first run)
- **#6** zero-installed models now render a warning/"setup required" blocking state, not
  neutral gray — models are a real requirement, not informational.
- **#12** the "no models installed" status is now a tappable CTA into the Voice-models
  download screen, plus a proper first-run empty state (icon + copy + "Download voice
  models") instead of a dead-end label.
- **#16** each model card carries a short plain-language purpose and download progress.

## KEEP-list — verified
Only the scoped settings/Models/Main files changed; A never touched B's files. The voice
pipeline + visuals are untouched. Gated: UX-GATE-EXIT=0, BUILD SUCCESSFUL, go test ok.

## Notes
- Version 0.5.6 (versionCode 100). Wave 1 of the settings-UX sprint.
- Wave 2 (issues #115-#120): status subtitles, Basic/Advanced split, Appearance/Visuals
  reconciliation, onboarding + learnability — planned next.
