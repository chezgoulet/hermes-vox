# Contributing to Hermes Vox

Hermes Vox is **the voice of Hermes** — a thin, local-first Android voice client
where *the entity IS the Hermes agent*: same identity,
memory, tools, and context. The phone carries the conversation UX, the voice
pipeline, and the particle-being avatar; Hermes owns the reasoning. The repo is
`chezgoulet/hermes-vox`. The current release is the latest one on GitHub Releases.

This file is the contribution contract. If a change would break the build gate,
the KEEP-list invariants, or the security model, it does not land. Everything
below is grounded in the repo — code and docs — so read the cited anchors
instead of trusting a paraphrase.

---

## Where things go

- **Issues** — anything broken, and feature requests concrete enough to act on.
  Say what you are trying to do, not only the feature you have in mind.
- **Discussions** — questions, open-ended ideas, and setups worth showing.
- **Security** — use private vulnerability reporting, not a public issue.

## What "tested" means here

CI proves the app compiles and the unit tests pass; it does not prove the phone
works. A change that touches audio, the service lifecycle, networking or the UI
needs a run on a device, with the device named in the pull request. A performance
claim needs the number and the method. If something could not be tested, say so —
an honest gap is reviewable, and a green tick that implies more than it earned is
not.

## Secrets

Never paste API keys, tokens, gateway addresses you would rather not share, or
personal logs into an issue, a commit or a test fixture. A report that contains
them gets edited, and the credential gets rotated.

## Licence

The project is GPL-3.0. By opening a pull request you agree your contribution is
licensed under the same terms; there is no CLA and no copyright assignment. Only
contribute code you have the right to license this way.

## Repository layout

| Path | What lives there |
|---|---|
| `android/` | The Gradle project — the actual product. `android/app` (Kotlin, `com.hermesvox`), Kotlin tests under `android/app/src/test/`. The entity connection is `android/app/src/main/java/com/hermesvox/core/` — `HermesSession` (ping, the streamed and stored turns, the chain head, barge-in) — see `docs/KOTLIN-CORE.md`. Gradle 8.12.1 + AGP 8.7.3 + Kotlin 2.2.21, JVM 17, compileSdk 34, minSdk 24 (`android/build.gradle:8-9`, `android/app/build.gradle:17-29`). |
| `docs/` | The working memory: `docs/RELEASE-<version>.md` release notes, `docs/SPRINT-*.md` / `docs/PLAN-*.md` / `docs/BUILD-DIRECTIVE-*.md` scoped work briefs, `docs/milestones/`, evidence logs. |
| `scripts/` | The build plumbing: `scripts/gate.sh` (the canonical gate), `scripts/fetch-runtime.sh`, `scripts/build.py` (no-flag build driver behind `make build`). |
| `testdata/stream/` | The stream parity fixtures and goldens (`testdata/stream/README.md`). |
| `journal/`, `plans/` | Day logs and plan state. |
| `tools/turnbench/` | Turn-taking benchmark tooling (research, not shipped). |

The Gradle root is `android/`; the repo root holds docs, scripts and fixtures.

---

## Development prerequisites

The canonical gate (`scripts/gate.sh`) encodes the full toolchain and runs
from any checkout. It reads `JAVA_HOME` and `ANDROID_HOME` from the environment
(falling back to the maintainer's build-host paths only when unset), so export
those for your machine:

- **JDK 17**, **Android SDK** (compileSdk 34). No NDK and no Go: the app builds
  no native code of its own (#126).
- **Gradle** — use the checked-in wrapper (`android/gradlew`). It pins Gradle
  8.12.1 with a verified distribution checksum; no system Gradle is needed.

CI (`.github/workflows/ci.yml`) runs the same checks on every pull request —
no secrets required, so fork PRs are gated too.

---

## The build/test contract (the gate)

**One command, from a clean checkout:** `bash scripts/gate.sh` → prints
`GATE-GREEN` on success. It runs three stages in order:

1. `scripts/fetch-runtime.sh` — the sherpa-onnx runtime AAR, SHA-256 verified.
2. `./gradlew --no-daemon clean assembleDebug testDebugUnitTest` (from `android/`)
3. A check that `StreamParityTest` (the stream parity harness) ran and passed —
   a gate that goes green because the harness stopped running is not a gate.

Gradle links only `android/app/libs/sherpa-onnx-*.aar`; a leftover `mobile.aar`
from a pre-#126 checkout is ignored.

### Running the tests by hand

```bash
# The entity connection (com.hermesvox.core) — offline, against a local fake gateway
./gradlew :app:testDebugUnitTest --tests 'com.hermesvox.core.*' --no-daemon   # from android/

# Live integration (requires a REAL gateway; see below)
HERMES_VOX_LIVE=1 HERMES_VOX_HERMES_API_KEY=<secret> \
  ./gradlew :app:testDebugUnitTest --tests '*LiveGatewayTest' --no-daemon

# Android — pure-JVM unit tests, no emulator or device needed
# (from android/)
./gradlew :app:testDebugUnitTest --no-daemon
./gradlew :app:testReleaseUnitTest --no-daemon   # the documented whole-tree gate pair

# Android — full app build
./gradlew --no-daemon clean assembleDebug        # debug: no keystore needed
./gradlew --no-daemon assembleRelease            # needs keystore/keystore.properties (below)
```

The documented verification pair used across release notes is
`assembleRelease + testReleaseUnitTest` (until #126 it also named
`go test ./voice/...` — e.g. `docs/RELEASE-0.5.4.md:40-44`). A change is not done
until that whole tree is green **on a fresh clone** (a dirty local tree proves nothing).

### The live integration test

`android/app/src/test/java/com/hermesvox/core/LiveGatewayTest.kt` runs real turns
against a live Hermes gateway and is skipped unless `HERMES_VOX_LIVE=1` **and**
`HERMES_VOX_HERMES_API_KEY` are set (optional: `HERMES_VOX_HERMES_URL`,
`HERMES_VOX_HERMES_MODEL`, `HERMES_VOX_HERMES_SESSION_KEY`). That key is the bearer
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
`android/app/proguard-rules.pro` are load-bearing — stripping the
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
   history (no O(n²) uploads — the app sends only the new turn and chains it with
   `previous_response_id`). The response chain head
   (`previous_response_id`) is owned by **one** caller
   (`HermesSession.lastID`) and shared by the streaming voice turns and
   `/compress` — a second, unsynced id orphaning the chain is the exact bug H2
   killed (`core/HermesSession.kt`, `lastID`).
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
4. **The being (`AvatarView` + `BeingRenderer`/`BeingShaders`).** `AvatarView` is the
   brain (state and tool → shape, categories, cycle-all); the GPU renderer draws the 27
   shapes. The shape tokens in `AvatarView.SHAPE_TOKENS` are persisted in prefs — never
   renumber or rename them. The launcher icon is rendered from the same shaders
   (`tools/icon/render_icon.py`); re-run it when the shaders change.
5. **No secrets in the APK.** The gateway API key is user-entered (onboarding +
   Settings), stored via `SecureStore`; there is deliberately no compiled-in
   default and the C0 release guard fails the build if a key injection is ever
   re-added (`android/app/build.gradle:75-115`).

### Code style

- **Alloc-free hot paths.** The render loop and the audio loop allocate once and
  reuse. The being's particles are stateless — each one's position is computed in the
  vertex shader from its identity — so the render thread uploads a handful of uniforms
  per frame and allocates nothing; the audio loop likewise writes primitives only — no
  `Pair`, no lambda, no boxing in the hot path.
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
- **Errors carry context.** The UI side often swallows connector errors — echo
  them at the call boundary instead of dropping them
  (`docs/LOGGING-BLIND-SPOTS-AUDIT-2026-09-05.md:35`).
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
  screenshots in `docs/screenshots/` (the README's images; recapture them when the UI
  changes). The public site is a separate repo, `chezgoulet/hermes-vox-site`
  (hermesvox.org).

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
4. **The gate must pass on a fresh clone**: `bash scripts/gate.sh` +
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
