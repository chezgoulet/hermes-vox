# Contributing to Hermes Vox

Hermes Vox is **the voice of Hermes** — a thin, local-first Android voice client
(plus a thin Go voice core) where *the entity IS the Hermes agent*: same identity,
memory, tools, and context. The phone carries the conversation UX, the voice
pipeline, and the particle-being avatar; Hermes owns the reasoning. The repo is
`chezgoulet/hermes-vox` (private). Current release line: **0.5.x** (HEAD 0.5.7).

This file is the contribution contract. If a change would break the build gate,
the KEEP-list invariants, or the security model, it does not land. Everything
below is grounded in the repo — code and docs — so read the cited anchors
instead of trusting a paraphrase.

---

## Repository layout

| Path | What lives there |
|---|---|
| `voice/` | Go module `voice` — the entity connectors: `HermesClient` (`/v1/chat/completions`), `HermesResponsesClient` (`/v1/responses`, `previous_response_id`), `HermesRunClient` (start/poll/cancel — the barge-in abort), `Conversation`, `Config` (secret-safe env). Source of truth for the entity connection. |
| `mobile/` | The `gomobile bind` package → `mobile.aar`. `mobile/session.go` (`HermesSession`: SSE stream + run cancel + `/compress`) is the only surface the Kotlin app uses (`docs/RELEASE-0.5.7.md`). |
| `android/` | The Gradle project — the actual product. `android/app` (Kotlin, `com.hermesvox`), Kotlin tests under `android/app/src/test/`. Gradle 8.12.1 + AGP 8.7.3 + Kotlin 2.2.21, JVM 17, compileSdk 34, minSdk 24 (`android/build.gradle:8-9`, `android/app/build.gradle:17-29`). |
| `docs/` | The working memory: `docs/RELEASE-<version>.md` release notes, `docs/SPRINT-*.md` / `docs/PLAN-*.md` / `docs/BUILD-DIRECTIVE-*.md` scoped work briefs, `docs/milestones/`, evidence logs. |
| `scripts/` | The build plumbing: `scripts/gate.sh` (the canonical gate), `scripts/fetch-runtime.sh`, `scripts/build.py` (no-flag build driver behind `make build`). |
| `journal/`, `plans/` | Day logs and plan state. |
| `tools/turnbench/` | Turn-taking benchmark tooling (research, not shipped). |

The repo root is the Go module (`module github.com/chezgoulet/hermes-vox`,
`go.mod`); the Gradle root is `android/`. Do not confuse the two.

---

## Development prerequisites

The canonical gate (`scripts/gate.sh`) encodes the full toolchain. It is written
for the House build host (the Thelio) and hardcodes that host's environment at
`scripts/gate.sh:10-20`; on any other machine, set the same variables yourself:

- **Go 1.26.4** pinned via `GOTOOLCHAIN=go1.26.4` (env, not `go.mod` — a
  `toolchain` directive is stripped by `go mod tidy`).
- **gomobile** — the `github.com/ebitengine/gomobile` fork (`go get -tool
  github.com/ebitengine/gomobile/cmd/gobind`).
- **JDK 17**, **Android SDK** (compileSdk 34), **NDK 25.2** (not 30 — the
  gomobile link breaks on NDK 30), **Gradle 8.12.1**.
- **Do NOT use the checked-in wrapper.** `android/gradle/wrapper/gradle-wrapper.properties`
  still pins Gradle 8.2 and `gradle-wrapper.jar` is absent; AGP 8.7.3 + Gradle 8.2
  is a known-failed pair. Use a real Gradle 8.12.1 binary. Fixing the wrapper
  itself is a separate chore — do not slip it into an unrelated PR.

---

## The build/test contract (the gate)

**One command, from a clean checkout:** `bash scripts/gate.sh` → prints
`GATE-GREEN` on success. It runs five stages in order (`scripts/gate.sh:22-44`):

1. `scripts/fetch-runtime.sh` — pinned runtime deps.
2. `go vet ./voice/...`
3. `go test -race ./voice/...` (offline)
4. `gomobile bind -target android -androidapi 23 -javapkg com.hermesvox -o mobile.aar github.com/chezgoulet/hermes-vox/mobile`
5. `gradle --no-daemon clean assembleDebug` (from `android/`)

### The AAR staging step is load-bearing

Step 4 produces `mobile.aar`; step 5 **copies** it into `android/app/libs/`
(`scripts/gate.sh:36-40`). Gradle consumes that copy, and Gradle's
"up-to-date" check lies if you bind into the same path without re-staging —
the last build can silently consume a *stale* AAR. Never skip the staging
step, and never hand-commit `mobile.aar` (it is gitignored by design).

### Running the tests by hand

```bash
# Go — the offline connector suite (gate runs it with -race)
go vet ./voice/...
go test -race ./voice/...        # the gate command
go test ./mobile/...             # the bind/session suite

# Go — live integration (requires a REAL gateway; see below)
HERMES_VOX_LIVE=1 HERMES_VOX_HERMES_API_KEY=<secret> go test ./voice/ -run Live -v

# Android — pure-JVM unit tests, no emulator or device needed
# (from android/, using a Gradle 8.12.1 binary)
gradle :app:testDebugUnitTest --no-daemon
gradle :app:testReleaseUnitTest --no-daemon   # the documented whole-tree gate pair

# Android — full app build
gradle --no-daemon clean assembleDebug        # debug: no keystore needed
gradle --no-daemon assembleRelease            # needs keystore/keystore.properties (below)
```

The documented verification pair used across release notes is
`assembleRelease + testReleaseUnitTest` (Kotlin) + `go test ./voice/...` (Go) —
e.g. `docs/RELEASE-0.5.4.md:40-44`. A change is not done until that whole tree
is green **on a fresh clone** (a dirty local tree proves nothing).

### The live integration test

`voice/integration_live_test.go` runs a real turn against a live Hermes gateway
and is skipped unless `HERMES_VOX_LIVE=1` **and** `HERMES_VOX_HERMES_API_KEY`
are set (`voice/integration_live_test.go:24-29`). That key is the bearer
credential of a live agent with tools — it is a House secret (env store only).
**Never** commit it, log it, or paste it into an issue/PR. If you cannot get the
key, say so and leave the test skipped — a green offline suite still gates.

### Release builds and signing

`assembleRelease` is signed with the House release keystore: gitignored
`keystore/keystore.properties` at the **repo root**, resolved by
`android/app/build.gradle:10-15,30-38` (`storePassword` from the House env
store, never the repo). A missing properties file fails the build loudly —
there is no silent fallback to the public debug key
(`android/app/build.gradle:4-9`). Verify signatures with
`apksigner verify --print-certs` (cert must be `hermes-vox`, never
`androiddebugkey`). Release builds also run R8 shrink
(`android/app/build.gradle:39-47`); the JNI keep rules in
`android/app/proguard-rules.pro` are load-bearing — stripping the gomobile /
sherpa-onnx / litertlm bindings crashes at runtime, so any dependency change
must be re-verified on a shrunk build.

### Emulator/device smoke

`adb install -r android/app/build/outputs/apk/debug/app-debug.apk`, then launch
`com.hermesvox/.MainActivity`. The emulator's `SpeechRecognizer` is flaky at the
binder level — true mic/voice verification needs a real device
(`docs/HANDOFF-2026-08-28.md`).

---

## Conventions

### The KEEP-list — sacred invariants

The project has a standing KEEP-list: behavior contracts that no "improvement"
may violate. They are protected by **behavior-contract tests, not snapshots**
(`docs/BUILD-DIRECTIVE-fix053.md:21-26,39-41`), and each release notes file
re-verifies them under a "KEEP-list — verified" section. Current list:

1. **The entity IS Hermes.** No personality layer, no second brain, no on-device
   agent pretending to be the entity. Gemma (when enabled) may express; Hermes
   reasons, runs tools, owns memory and context.
2. **Per-conversation prompt caching is sacred.** Never invalidate a
   conversation's cache mid-conversation; never rebuild/resend the message
   history (no O(n²) uploads — `voice/conversation.go:58-72` caps the legacy
   history for exactly this reason). The response chain head
   (`previous_response_id`) is owned by **one** caller
   (`HermesSession.lastID`) and shared by the streaming voice turns and
   `/compress` — a second, unsynced id orphaning the chain is the exact bug H2
   killed (`voice/conversation.go:17-27,74-87`).
3. **The narrow-waist core — behavior over snapshots.** The voice pipeline's
   behavior contracts: **barge-in** (cut the reply + cancel the run;
   `BargeGate.kt`, echo-cancelled source), **reveal-freeze** (the reply crawl
   never touches the being), the **escape rule** (level-sustained barge escape
   — `docs/PLAN-0402-level-escape.md`), **reply-settle** (a completed reply
   settles exactly once, no ghost re-speak after hush — `ReplySettleRule.kt`),
   and the **crash-guard** (teardown-race guard on every executor submit —
   `ExecGuard.kt`). Each is extracted as a pure JVM rule class with an off-device
   test (`BargeGateTest.kt`, `ReplySettleRuleTest.kt`, `ExecGuardTest.kt`,
   `TurnGateReleaseTest.kt`). Behavior changes here require the matching contract
   test change in the same commit.
4. **The particle-being `AvatarView`.** The emergent swarm's visual vocabulary
   (grown from 13 to 20 archetypes in 0.5.5 — `docs/RELEASE-0.5.5.md`), the
   `VisualStyle` category system, and cycle-all must stay intact.
5. **No secrets in the APK.** The gateway API key is user-entered (onboarding +
   Settings), stored via `SecureStore`; there is deliberately no compiled-in
   default and the C0 release guard fails the build if a key injection is ever
   re-added (`android/app/build.gradle:75-115`).

### Code style

- **Alloc-free hot paths.** The render loop and the audio loop allocate once and
  reuse. `AvatarView` allocates all 320 particles in `init`; the per-frame loop
  writes primitives only — no `Pair`, no lambda, no boxing, no `Random`, no
  `sin()` in the loop (sine LUTs instead) (`AvatarView.kt:91-98,132-133,167-168`).
  Use **const/preallocated arrays, not `listOf`, in hot loops**.
- **Pure-JVM rule objects.** Anything with a decision worth testing off-device
  (gate logic, settle rules, endpoint rules, key resolution) is a plain object
  with injected dependencies — the Android Keystore, `Context`, and executors
  stay out of it so the test runs without an emulator (`GatewayKey.kt:11-13`).
- **Behavior-contract tests over snapshots.** Assert the contract (fires / does
  not fire / exactly once), never pixel or string snapshots.
- **Honest logging.** Log through `VoxLog`; a silent `catch` that hides a
  degraded-but-alive path is a defect (see the logging audit in
  `docs/LOGGING-BLIND-SPOTS-AUDIT-2026-09-05.md`). Never log the key or tokens.
- **Go.** `gofmt` clean, `go vet` clean, errors carry context. The Kotlin side
  often swallows Go errors — echo them at the call boundary instead of dropping
  them (`docs/LOGGING-BLIND-SPOTS-AUDIT-2026-09-05.md:35`).
- **Kotlin.** JVM 17, `-Xskip-metadata-version-check`; follow the existing file
  header convention (the change history + the invariant it touches lives in a
  comment above the code — `ReplySettleRule.kt:3-26` is the model).

### Commits

- One coherent change per commit (one commit per fix; never a grab-bag).
- Conventional prefixes: `fix(voice):`, `fix(android):`, `feat(android):`,
  `docs:`, `chore(release):`. Reference issue numbers (`#N`).
- **Never commit secrets** — no keys, no tokens, no keystore material
  (`keystore/` is gitignored; keep it that way).

### Where docs go

- **Every release** gets `docs/RELEASE-<version>.md`: what shipped, the
  verification transcript, a "KEEP-list — verified" section, and honest notes.
- Scoped work gets a plan brief first: `docs/SPRINT-*.md` (sprint), then
  `docs/PLAN-*.md` / `docs/BUILD-DIRECTIVE-*.md` / `docs/CLAUDE-BRIEF-*.md`
  with exact file:line targets, a KEEP-list, verification, and risk.
- Milestone status lives in `docs/milestones/`; day logs in `journal/`;
  screenshots in `docs/screenshots/`.

---

## Contribution flow

1. **Branch off `main`.** Name it for the work: `feature/<slug>`,
   `fix/<slug>` (history: `feature/release-security`, `fix-batch-053`,
   `er-alpha`).
2. **Commit per change** with the conventions above; run the change's tests
   before moving on.
3. **Open a PR to `main`.** The PR body states: what changed, the KEEP-list
   items it touches and why they survive, and the verification evidence
   (commands + output — run, don't guess).
4. **The gate must pass on a fresh clone**: `go test ./voice/...` +
   `:app:testReleaseUnitTest` + `assembleRelease` (+ emulator/device smoke when
   the change touches the runtime). A PR that cannot demonstrate this is not
   reviewable.
5. **Review expectations.** Every PR gets an adversarial read (the reviewer
   hunts for what the author missed, not typos). Security-sensitive work gets
   the full `REVIEW-DIRECTIVE` treatment — a separate, read-only adversarial
   review pass before merge — and security clusters are double-read by the
   adversarial co-steward pair (`docs/SPRINT-PLAN-release-security.md` review
   gate). If your change touches the security model, say so in the PR so it is
   routed for that review.

---

## Releases (how versions move)

- Version lives in `android/app/build.gradle:23-24` (`versionCode`,
  `versionName`). Bump both; the APK is named `hermes-vox-<versionName>.apk`
  (`android/app/build.gradle:48-52`).
- Write `docs/RELEASE-<version>.md`; commit the bump + notes as
  `chore(release): <version> (<code>) — <summary>`.
- Convention: **tag === versionName === APK**, no leading `v`; clean semver
  (Obtainium-friendly). Releases are signed with the House release keystore.
- Signature-change note: a release-signed build will **not** install over a
  debug-signed install (Android signature mismatch) — the first release-signed
  build after a debug-signed one is a fresh install (`docs/SPRINT-PLAN-release-security.md`).
