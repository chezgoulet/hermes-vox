# Hermes Vox 0.5.7 — 100% Android-native (Ebitengine fully removed)

The release that strips all remaining Ebitengine code. Hermes Vox is now
Android-native rendering (Kotlin Canvas particle-being) + a thin Go voice core,
with **zero Ebitengine**.

## What shipped (the bind strip — issue #125)

- **Deleted `game/`** (the Ebitengine game / boilerplate "conversation shell" coupled to
  the old VoiceBackend architecture) and **`mobile/mobile.go`** (which bound Ebitengine's
  `Start()`/`IsRunning()`/`UpdateTouchesOnAndroid` — nothing in the Kotlin app ever called it).
- **Kept `mobile/session.go`** — the real `HermesSession` voice logic (SSE + streaming +
  /compress), the only thing the Kotlin bind surface uses.
- **`go.mod`** — Ebitengine + its transitive game deps removed; kept `golang.org/x/mobile`
  (the Android↔Go bind bridge, load-bearing) + `ebitengine/gomobile` (the `gobind` tool).
- **`gate.sh`** — removed the Ebitengine js-wasm/`cmd/app` build step.

## Verified (the authoritative gate, on the Thelio)
- The rebuilt `mobile.aar` exports **only** `com/hermesvox/mobile/HermesSession` + `Mobile`
  — **no `Start`/`IsRunning`/ebiten symbols.** It shrank (~22MB → 20MB, and the dead native
  surface + the X11/GLFW build hazard are gone).
- `go test ./voice/...` ok; `assembleRelease` + `testReleaseUnitTest` — BUILD SUCCESSFUL.

## Why this is the right call (settled during the pass)
Christopher asked whether keeping Ebitengine enabled a desktop version. It did not — the
real being renders via Android Canvas; `game/` is boilerplate coupled to the old architecture;
a future desktop frontend would reuse `voice/`+`session.go` and write a new renderer. So
removing it loses nothing of value and removes dead native surface + the build hazard.

## KEEP-list — verified
The voice pipeline (`voice/` Go), `mobile/session.go`, the particle-beings (`AvatarView.kt`),
and all visuals/barge/reveal behavior are untouched. Only the Ebitengine game surface + its
bind + deps were removed.

## Notes
- Version 0.5.7 (versionCode 103).
- The Go *core* (`voice/` + `mobile/session.go`) is kept deliberately — it's the battle-tested
  streaming/response engine and the source of the H1/H2/M1 reliability work. Rewriting it to
  Kotlin is a separate, larger decision (see the Go-removal cost discussion), not this release.
