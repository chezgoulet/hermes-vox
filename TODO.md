# TODO

Current, project-specific tracking for Hermes Vox. (The old boilerplate TODO — deploy
pipeline, Ollama governance scaffold — described the Ebitengine template, not this app;
removed.)

## High value — next

- [ ] **Enhanced Realtime: wire Gemma presence into the immersive view.** The Gemma 4 E2B
      expression layer loads (LiteRT-LM) but is Main-only. Issue #52.
- [x] **Remove the Go core** — done in #126. `voice/`, `mobile/`, `go.mod`, gomobile,
      the NDK pin and `mobile.aar` are gone; the gate is Gradle-only and the stream
      parity fixtures pin the behaviour the Go core had.
- [ ] **Settings-UX remaining polish** — status subtitles, Basic/Advanced split,
      Appearance/Visuals reconcile, onboarding/learnability already landed in 0.5.6.x;
      the remaining polish items are the Settings-home subtitle live-updates on pref change
      (they populate on bind; verify they refresh on return).

## Backlog

- [ ] Model downloads: per-download progress + plain-language purpose are in; verify
      re-download / cancel edge cases across the blessed set.
- [ ] The Opus-review findings still open (pre-roll quadratic buffer, AudioRecord leak,
      Listener main-thread contract) — reliability backlog.
- [ ] **F-Droid inclusion** — path B: sherpa-onnx and onnxruntime built from source
      in the recipe, then the metadata and the `Binaries:`/`AllowedAPKSigningKeys`
      submission (#165).
- [ ] **Reproducible APK** — prove byte-identity with two clean builds and enforce it
      in CI; it is the gate for publishing our own signature through F-Droid (#166).
