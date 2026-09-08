# TODO

Current, project-specific tracking for Hermes Vox. (The old boilerplate TODO — deploy
pipeline, Ollama governance scaffold — described the Ebitengine template, not this app;
removed.)

## High value — next

- [ ] **Enhanced Realtime: wire Gemma presence into the immersive view.** The Gemma 4 E2B
      expression layer loads (LiteRT-LM) but is Main-only. Issue #52.
- [ ] **Recapture the README screenshots against the current build** (Post-0.5.6). The
      settings.png is unrelated, main_rest/onboarding are duplicated. A device task.
- [ ] **Strip the dead Ebitengine bind** (`game/`, `mobile/mobile.go` `Start()`), `go mod
      tidy` away Ebitengine, rebuild `mobile.aar`, and re-gate the whole app. Keeps the
      portable `voice/` + `mobile/session.go`; drops dead native surface + the X11 build
      hazard.
- [ ] **Settings-UX remaining polish** — status subtitles, Basic/Advanced split,
      Appearance/Visuals reconcile, onboarding/learnability already landed in 0.5.6.x;
      the remaining polish items are the Settings-home subtitle live-updates on pref change
      (they populate on bind; verify they refresh on return).

## Backlog

- [ ] Model downloads: per-download progress + plain-language purpose are in; verify
      re-download / cancel edge cases across the blessed set.
- [ ] The Opus-review findings still open (pre-roll quadratic buffer, AudioRecord leak,
      Listener main-thread contract) — reliability backlog.
- [ ] Play store readiness: AAB build + App Signing, privacy/data-safety form, BETA
      framing, discovery-friendly description/topics (the 0.6 gates).
