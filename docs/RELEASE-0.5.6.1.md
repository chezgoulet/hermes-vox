# Hermes Vox 0.5.6.1 — settings-ux wave 2

Completes the settings-UX sprint (issues #111-120 all addressed across 0.5.6 + 0.5.6.1).
Wave 2 covers the P1/P2 clarity + learnability work, built on the P0 fixes (0.5.6).

## Settings clarity
- **#115 status subtitles** — the Settings home rows each now show a live-value subtitle
  (entity connected/not-configured, voice mode, STT backend, TTS engine, theme, visual
  category, version) so you can see state without drilling in.
- **#117 "Test connection" relabeled** — "Test entity connection" (main gateway) vs
  "Test STT server connection" (remote STT) — no more same-label-different-target confusion.
- **#118 Speech & Mic → Basic / Advanced** — the ~12 flat controls split into a Basic set
  (barge-in, AEC, noise suppression) and a collapsed "Advanced — timing tuning" panel
  (VAD/barge sliders) — progressive disclosure, not a wall.
- **#119 Appearance & Visuals reconciled** — the Appearance "Layout" row is now "Layout
  mode" so "Presence" means one thing (the Visuals "Presence shape"; the redundant
  "Mic / Speech" in-section header was removed).

## Learnability (the gap: nothing taught a new user)
- **#120 onboarding** — a "how this works" step (the being is Hermes, the voice modes,
  hands-free vs PTT), a voice-mode comparison line, one-time first-run coach marks on the
  main screen, and jargon tooltips. Built around the REAL 2-mode set — walkie-talkie/PTT was
  stripped in C2, so the copy teaches the truth (no PTT button; hands-free in every mode).

## KEEP-list — verified
Settings + Onboarding/Main/Models only; sub-agents non-colliding. Gated green on a fresh
clone (UX4-GATE-EXIT=0). Three compile bugs caught by the gate (a sub-agent's un-compiled
`coachRow`, a wrong `BuildConfig` bet, and a compileSdk-34-missing `setLineSpacingExtra`)
were all fixed; the last uses `setLineSpacing(float,float)` verified against the jar.

## Notes
- Version 0.5.6.1 (versionCode 101). Sprint complete: settings + bootstrapping.
- The comprehensive docs report (walkie-talkie staleness + broader docs refresh) is
  Christopher's; the app copy now reflects the true 2-mode set.
