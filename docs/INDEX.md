# Documentation index

This file is the map of the repository's documentation. It says which documents teach the app as
it is now, which are records of a moment, and where the files that are not documents sit. If you
are new to the app, read [`README.md`](../README.md) first; this file is for finding a document
once you know what you are looking for.

**Living documents** describe current behaviour and current process. They are kept true, and the
claims in them that can be checked by machine are listed in [DOC-TRUTH.yml](DOC-TRUTH.yml).

**Records** are history by nature. They describe the state and reasoning at the time they were
written, are not maintained, and may describe behaviour that has since changed. Where a record
carries the banner *"Historical — kept as a record"*, the banner repeats that. Some records are
still added to — the decision log, the release notes — but none is a claim about the current
build.

## Living documents

### Start here

| Document | What it answers |
|---|---|
| [`README.md`](../README.md) | What Hermes Vox is, what it needs, how to install it and how to connect it to a gateway. |
| [`docs/INDEX.md`](INDEX.md) | This map. |
| [`docs/ARCHITECTURE.md`](ARCHITECTURE.md) | How the app is put together: the pieces, what talks to what, and the phone/host boundary. |

### Using the app

| Document | What it answers |
|---|---|
| [`docs/PRESENCE.md`](PRESENCE.md) | The Presence layer (the optional on-device voice): VOX.md, the opening beat (two to six words while the mind starts), the read-only phone context, and the gate it sits behind. |
| [`docs/MCP-PHONE.md`](MCP-PHONE.md) | The phone as a Model Context Protocol (MCP) server: the one-button setup, the one `phone` tool, the grants, the results and the permissions. |
| [`docs/MCP-SURFACE.md`](MCP-SURFACE.md) | Every action of the `phone` tool: its group, tier, arguments, result fields, undo, OS needs and refusals. |
| [`docs/RUNS-ROUTE.md`](RUNS-ROUTE.md) | The `/v1/runs` turn route, the 404 fallback to `/v1/responses`, interrupting a reply, and the device checks still owed. |
| [`docs/WAKE-WORD.md`](WAKE-WORD.md) | the wake word: the alpha gate, what enrolment records and where it lives, that nothing is trained or installed, cost and limits. Checked against `wake/WakeListening.kt`, `wake/WakeWordEngine.kt`, `wake/EnrolmentStore.kt` |
| [`docs/APP-SKILL.md`](APP-SKILL.md) | The `hermes-vox-app` skill: what it teaches, how it is installed on the agent, and how a false claim is found and fixed. |
| [`docs/MODELS-SCREEN.md`](MODELS-SCREEN.md) | The Voice models screen and the single model catalogue behind it. |
| [`docs/APPROVALS-ACCEPTANCE.md`](APPROVALS-ACCEPTANCE.md) | The approval cards on a device, and the debug arm used to test them. |
| [`docs/ASSISTANT-ROLE.md`](ASSISTANT-ROLE.md) | Android's digital-assistant role: what the app asks for and what the device maker owns. |
| [`docs/MTLS-PER-ORIGIN.md`](MTLS-PER-ORIGIN.md) | Per-origin client certificates: what is sent, to whom, the failure reasons and what it does not protect. |
| [`docs/KOTLIN-CORE.md`](KOTLIN-CORE.md) | The `com.hermesvox.core` connector: the turn routes, the stream and the session. |

### Building and shipping

| Document | What it answers |
|---|---|
| [`CONTRIBUTING.md`](../CONTRIBUTING.md) | What a change needs to be reviewable. |
| [`docs/RELEASE-PROCESS.md`](RELEASE-PROCESS.md) | How a release is cut, gated, signed and verified. |
| [`docs/REPRODUCIBLE.md`](REPRODUCIBLE.md) | The reproducible-build status and the commands that prove it. |
| [`docs/REGRESSION.md`](REGRESSION.md) | The per-cut regression set, the witness lines, and what CI cannot see. |
| [`docs/FDROID.md`](FDROID.md) | The F-Droid rail: the source-built runtime, the draft recipe, and what is still missing. |
| [`docs/GIT-FLOW.md`](GIT-FLOW.md) | Which branches exist and what merges where. |
| [`DEVICE_VERIFY.md`](../DEVICE_VERIFY.md) | Sideloading and verifying the app on a phone. |
| [`AGENTS.md`](../AGENTS.md) | The rules this repository's coding agents work under. |
| [`CLA.md`](../CLA.md) | The contributor licence agreement and how to accept it. |

### Data, security and the agent's own description

| Document | What it answers |
|---|---|
| [`PRIVACY.md`](../PRIVACY.md) | What data the app processes, where it goes, and how to delete it. |
| [`SECURITY.md`](../SECURITY.md) | What the app protects, what is out of scope, and how to report a vulnerability. |
| [`skills/hermes-vox-app/SKILL.md`](../skills/hermes-vox-app/SKILL.md) | The app's self-description for the agent, with every factual claim anchored to a file and a token. |

### Current release and milestones

| Document | What it answers |
|---|---|
| [`docs/MILESTONES.md`](MILESTONES.md) | The milestone map: what the current beta means, what the alpha gate (the switch for unfinished features) holds, and what is open. |
| [`docs/0.9.1-SCOPE.md`](0.9.1-SCOPE.md) | Every item in the current release, by real status. |
| [`docs/RELEASE-0.9.1.3.md`](RELEASE-0.9.1.3.md) | The current release's contents: the short-reply drain, the readiness gate, and the cleanup, with its evidence. |
| [`docs/RELEASE-NOTES-0.9.1.md`](RELEASE-NOTES-0.9.1.md) | the published note for 0.9.1, as written by the owner: what changed, grouped by what the reader is trying to do, with contributors named |
| [`docs/BUDGETS-0.9.1.md`](BUDGETS-0.9.1.md) | The CPU, GPU, battery and memory budget for the continuous listening lane. |

### Instruments and measurements

| Document | What it answers |
|---|---|
| [`docs/MATRIX.md`](MATRIX.md) | The per-device behaviour matrix and the log line that proves each case. |
| [`docs/PRESENCE-REPORTS.md`](PRESENCE-REPORTS.md) | The `presence-report` template, its diagnostic block and the daily aggregate. |
| [`docs/PRESENCE-TUNING-BAR.md`](PRESENCE-TUNING-BAR.md) | The presence tuning bar and the script that prints it. |
| [`docs/INVENTION-MEASUREMENT.md`](INVENTION-MEASUREMENT.md) | How invented claims are counted; observation only, gating nothing. |
| [`docs/VOX-RESYNC-FIXTURES.md`](VOX-RESYNC-FIXTURES.md) | The `testdata/vox-resync/` corpus behind `VoxSoul.resync`. |

## Records

### Decisions and project records

[`docs/DECISIONS.md`](DECISIONS.md) — the numbered decision log, with dated amendments where a
later decision changed an earlier one · [`docs/BASELINE.md`](BASELINE.md) — the per-release
performance baseline instrument and the numbers recorded for each cut · [`ROADMAP.md`](../ROADMAP.md)
— the project roadmap as it stood · [`TODO.md`](../TODO.md) — the old open-work list ·
[`WIRING.md`](../WIRING.md) — the branch note for the notification-dismiss and apps lane.

### Release notes

`docs/RELEASE-0.3.31.md` · `RELEASE-0.3.32.md` · `RELEASE-0.3.32.1.md` · `RELEASE-0.3.32.2.md` ·
`RELEASE-0.3.32.3.md` · `RELEASE-0.3.32.4.md` · `RELEASE-0.4.0.md` · `RELEASE-0.4.0.1.md` ·
`RELEASE-0.4.0.2.md` · `RELEASE-0.4.0.3.md` · `RELEASE-0.4.0.4.md` · `RELEASE-0.5.0.md` ·
`RELEASE-0.5.0-previewA.md` · `RELEASE-0.5.0-previewB.md` · `RELEASE-0.5.0.1.md` ·
`RELEASE-0.5.0.2.md` · `RELEASE-0.5.0.3.md` · `RELEASE-0.5.1.md` · `RELEASE-0.5.2-claude.md` ·
`RELEASE-0.5.2-qwen.md` · `RELEASE-0.5.3.md` · `RELEASE-0.5.4.md` · `RELEASE-0.5.5.md` ·
`RELEASE-0.5.5.1.md` · `RELEASE-0.5.5.2.md` · `RELEASE-0.5.5.3.md` · `RELEASE-0.5.5.4.md` ·
`RELEASE-0.5.5.5.md` · `RELEASE-0.5.6.md` · `RELEASE-0.5.6.1.md` · `RELEASE-0.5.6.2.md` ·
`RELEASE-0.5.7.md` · `RELEASE-0.6.0.md` · `RELEASE-0.6.1.md` · `RELEASE-0.6.2.md` ·
`RELEASE-0.6.3.md` · `RELEASE-0.6.4.md` · `RELEASE-0.6.5.md` · `RELEASE-0.6.6.md` ·
`RELEASE-0.7.0.md` · `RELEASE-0.7.2.md` · `RELEASE-0.7.3.md` · `RELEASE-0.8.0.md` ·
`RELEASE-0.8.1.md` · `RELEASE-0.9.0.md` · `RELEASE-0.9.0.1.md` · `RELEASE-0.9.0.2.md` ·
`RELEASE-0.9.0.3.md` · `RELEASE-0.9.1.md` · `RELEASE-0.9.1.1.md` · `RELEASE-0.9.1.2.md` — one record
per cut, saying what that version contained.

### Plans, briefs, directives and sprints

`docs/PLAN-0.4.0-polish.md` · `PLAN-0.8-accelerator.md` · `PLAN-0.9.1.md` · `PLAN-0.9.2.md` ·
`PLAN-0.9.x-to-1.0.md` · `PLAN-1.0.md` · `PLAN-2.0.md` · `PLAN-0401-barge-telemetry.md` ·
`PLAN-0402-level-escape.md` · `PLAN-0501-visual-categories.md` · `PLAN-0502-claude.md` ·
`PLAN-0502-qwen.md` · `PLAN-0503-static-transcript.md` · `PLAN-barge-endpoint-modelreview.md` ·
`PLAN-c1-vpn-explicit.md` · `PLAN-c2-walkie-strip.md` · `PLAN-c3-audio-lifecycle.md` ·
`PLAN-c4-log-honesty.md` · `PLAN-FUTURE.md` · `PLAN-immediate-silence.md` ·
`PLAN-logging-patch.md` · `PLAN-pause-first-silence.md` · `PLAN-presence-motion.md` ·
`PLAN-stt-backend-remote.md` · `PLAN-transcript-sync.md` · `PLAN-tts-usage-toggle-barge-ui.md` ·
`PLAN-worker-retirement.md` · `docs/BUILD-ER-enhanced-realtime.md` ·
`BUILD-DIRECTIVE-fix053.md` · `BUILD-DIRECTIVE-video-routing.md` ·
`BUILD-DIRECTIVE-video-statewire.md` · `docs/CLAUDE-BRIEF-0403.md` · `CLAUDE-BRIEF-0404.md` ·
`CLAUDE-BRIEF-0502-anim.md` · `CLAUDE-BRIEF-routing.md` · `docs/SPRINT-MAP.md` ·
`SPRINT-2026-10-30-device-free.md` · `SPRINT-PLAN.md` · `SPRINT-PLAN-040-gates.md` ·
`SPRINT-PLAN-040-gates-impl.md` · `SPRINT-PLAN-DIRECTIVE.md` · `SPRINT-PLAN-foundation.md` ·
`SPRINT-PLAN-polish.md` · `SPRINT-PLAN-polish-impl.md` · `SPRINT-PLAN-realtime.md` ·
`SPRINT-PLAN-release-security.md` · `SPRINT-PLAN-release-security-impl.md` — what was being
decided or built when each was written.

### Design and specifications

`docs/DESIGN-enhanced-realtime-voice.md` · `design-menu-shapes.md` · `DESIGN-presence-motion.md` ·
`DESIGN-sound-cues.md` · `docs/SPEC-conversational-bridge.md` · `docs/ACCESSIBILITY-TIER.md` ·
`docs/WAKE-WORD-RESEARCH.md` · `docs/TEST-PLAN-0.9.md` — designs, specs and test plans. The ones that
state behaviour the code no longer has are named in the table below.

### Handoffs

`docs/handoff.md` · `HANDOFF-2026-08-28.md` · `HANDOFF-ER-session.md` — the state of the tree and
the next agent's instructions at that moment.

### Investigations, audits and measurements

`docs/BARGE-REGRESSION-ANALYSIS.md` · `BARGE-WHY-0.7-WORKED.md` · `CRAWL-SCROLLBACK.md` ·
`INVESTIGATION-px9-trueblack-aura.md` · `LOGGING-BLIND-SPOTS-AUDIT-2026-09-05.md` ·
`STATE-AUDIT.md` · `STT-AB.md` · `VOICE-BAKEOFF.md` · `PRESENCE-BENCH.md` ·
`PRESENCE-COST.md` · `PRESENCE-GUARD-BOUNDS.md` · `PRESENCE-LATE-DIAGNOSIS.md` ·
`RESEARCH-2026-10-voice-agents.md` · `research-on-device-voice-2026-08-27.md` ·
`research/RESEARCH-REFRESH-2026-11-presence.md` · `NOTES-api-events.md` ·
`NOTES-voicemanager.md` · `16KB-PAGES.md` · `evidence/235-partial-decode.md` ·
`evidence/252-new-mid-call-redial.md` · `test-artifacts/emulator-stress-2026-08-28.md` ·
`milestones/m1-completeness.md` · `milestones/m2-m6-completeness.md` ·
`milestones/model-downloader.md` · `milestones/warm-voice-runtime.md` · [`REPORT.md`](../REPORT.md)
· [`REPORT-nemotron.md`](../REPORT-nemotron.md) — what was measured, found or proven, and when.

### Reviews

`docs/REVIEW-0.5.2-findings.md` · `REVIEW-DIRECTIVE-hermes-gateway.md` ·
`REVIEW-DIRECTIVE-vox.md` · `reviews/PRESENCE-REVIEW-2026-11-04.md` — external and internal
reviews, and the briefs that commissioned them.

### Records that state behaviour the code no longer has

These keep their bodies. The line says what changed, so a reader who skips the banner is not
misled.

| Record | What changed |
|---|---|
| [`docs/DESIGN-enhanced-realtime-voice.md`](DESIGN-enhanced-realtime-voice.md) | It designs two modes and a soul that answers short turns. The app has one operation, and the soul offers the opening beat while the mind answers. |
| [`docs/SPEC-conversational-bridge.md`](SPEC-conversational-bridge.md) | It says nothing in it is deployed. The profile list and the file offer are in the code (`ProfilesAsk`, `HostProfiles`, `FileOffer`, `FileStage`, `FileServer`). |
| [`docs/PRESENCE-BENCH.md`](PRESENCE-BENCH.md) | It names Qwen 3.5 0.8B as the default presence model. The catalogue ships Gemma 4 E2B (`ModelCatalog`, `gemma-e2b`). |
| [`docs/PLAN-0.9.1.md`](PLAN-0.9.1.md) | It counts the F-Droid listing as carried. The decision log makes the listing current release work. |
| [`docs/handoff.md`](handoff.md) | It names 0.3.7 as the latest release and a Go connector as the architecture. There is no Go tree; the connector is Kotlin in `com.hermesvox.core`. |
| [`docs/RELEASE-0.6.0.md`](RELEASE-0.6.0.md) | It lists the app's licence as Apache-2.0. `LICENSE` is GPL-3.0 because the speech runtime links espeak-ng. |

## Other files

### Inside `docs/`

- [`docs/DOC-TRUTH.yml`](DOC-TRUTH.yml) — the machine-checked invariants between a document's
  claim and the code that settles it.
- [`docs/_config.yml`](_config.yml) — the GitHub Pages configuration for `docs/`.
- [`docs/index.html`](index.html) — the redirect from the Pages site to `hermesvox.org`.
- [`docs/fdroiddata/com.hermesvox.yml`](fdroiddata/com.hermesvox.yml) — the draft F-Droid
  recipe, not submitted.

### At the repository root

- [`LICENSE`](../LICENSE) — GPL-3.0.
- [`NOTICE`](../NOTICE) — component licences and the source offer.

### Assets

- **Screenshots** — `docs/screenshots/icon.png`, `docs/screenshots/call.jpg`,
  `docs/screenshots/speaking.jpg`, `docs/screenshots/settings.jpg`, `docs/screenshots/states.jpg`,
  `docs/screenshots/shapes.jpg`, `docs/screenshots/models.jpg`, `docs/screenshots/tts.jpg`,
  `docs/screenshots/visuals.jpg`. Captures of the app; they are not evidence of the current UI.
- **State-audit figures** — `docs/design/state-audit/being-conflation-dark.{png,svg}`,
  `docs/design/state-audit/being-off-light.{png,svg}`,
  `docs/design/state-audit/carrier-map.{png,svg}`,
  `docs/design/state-audit/light-mode-contrast.{png,svg}`,
  `docs/design/state-audit/pocketed-sound-off.{png,svg}`. Drawn for the state audit and kept
  with it.
- [`docs/REFERENCE-retirement-original.diff`](REFERENCE-retirement-original.diff) — the original
  retirement patch, kept verbatim. It is not bannered because a banner would corrupt the diff;
  treat it as a record of the moment.
