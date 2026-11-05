# Documentation index — what is true for 0.9.1

Set 2026-11-04 against `origin/testing` (`2dd7e56`, versionCode 147, `versionName` 0.9.1).
Every file under `docs/` and the four root documents is classified here. The counts are a
partition: a contradicted document is counted under **contradicted**, not also under historical.
It appears in the historical groups by name, and again in the contradicted table with the reason,
so a reader sees what changed without opening it.

**Read this first.** Most of this tree is history: handoffs, briefs, investigations and build
directives from earlier milestones. That history is kept — it is how the present was decided —
but it is not a claim about the current build. Three classes are used:

- **current** — describes how the app is now, and was checked against the code named in the line.
- **historical** — a record of a moment. It carries the banner *"Historical — kept as a record."*
  at the top and says nothing about the current build.
- **contradicted** — historical, but it states something the code now contradicts. It also carries
  the banner, and the line below says what changed, so a reader who skips the banner is not
  misled.

Counts: **34 current** · **129 historical** · **6 contradicted** · **19 assets** (images, listed at
the end). `README.md`, `CONTRIBUTING.md`, `SECURITY.md` and `NOTICE` are counted as current
root documents, not under `docs/`; `docs/INDEX.md` counts itself.

## Current — true today

| Document | What it is, and what it was checked against |
|---|---|
| [`docs/INDEX.md`](INDEX.md) | this file |
| [`docs/ARCHITECTURE.md`](ARCHITECTURE.md) | the system on one page: app, gateway routes (`/v1/runs` and the `/v1/responses` fallback), approvals, profiles, file offer, MCP phone route, Alpha gate. Checked against `core/TurnRoute.kt`, `core/Stream.kt`, `AlphaGate.kt`, `Profiles.kt`, `FileOffer.kt`, `PhoneTool.kt` |
| [`docs/DECISIONS.md`](DECISIONS.md) | the decision log, D1–D35. D29’s gate entry corrected to the five sets that shipped; D29–D34 checked against `AlphaGate.FeatureSet`, `CallQuiet.kt`, `ErHandoff.kt`, `VoiceTurn.kt`, `LocalActionGrammar.kt`, `PhoneTool.kt` |
| [`docs/MILESTONES.md`](MILESTONES.md) | the current milestone map (D28). Corrected: the gate names its five sets; Presence tool use is D32–D34, not D31 |
| [`docs/0.9.1-SCOPE.md`](0.9.1-SCOPE.md) | every 0.9.1 item by real status (in the build / device pass outstanding / not built / carried / out) |
| [`docs/RELEASE-0.9.1.md`](RELEASE-0.9.1.md) | the dry release list, structured by what needs Alpha and what does not. Rewritten from the tree |
| [`docs/RELEASE-PROCESS.md`](RELEASE-PROCESS.md) | how a cut is made (version bump on `testing`, gate, reproducibility, signing). Checked against `scripts/gate.sh` and `release.yml` |
| [`docs/GIT-FLOW.md`](GIT-FLOW.md) | the branch and release flow. Checked against the current branches and tags |
| [`docs/DOC-TRUTH.yml`](DOC-TRUTH.yml) | the machine-checked invariants behind the claims above (`scripts/check-doc-truth.py`) |
| [`docs/KOTLIN-CORE.md`](KOTLIN-CORE.md) | the `com.hermesvox.core` connector. Corrected: the runs API is no longer “deliberately dropped”; it is the default route |
| [`docs/MODELS-SCREEN.md`](MODELS-SCREEN.md) | the Voice models screen and its one catalogue (`ModelCatalog.blessed`) |
| [`docs/PRESENCE.md`](PRESENCE.md) | Presence: the layer, VOX.md, the beat, local actions, the MCP phone tool, the handoff receipt. Corrected: it runs only behind the Alpha gate, off on a fresh install |
| [`docs/MCP-PHONE.md`](MCP-PHONE.md) | the phone’s MCP server: setup turn, one `phone` tool, grants, tiers, results, permissions. Checked against `PhoneTool.kt`, `PhoneGrants.kt`, `PhoneMcpServer.kt` |
| [`docs/RUNS-ROUTE.md`](RUNS-ROUTE.md) | `/v1/runs`, the default route, its 404 fallback, barge-in and the device pass. Checked against `core/TurnRoute.kt`, `core/Stream.kt`, `RouteChoice.kt` |
| [`docs/FDROID.md`](FDROID.md) | the F-Droid rail (path B, source-built runtime, draft recipe). Corrected: releases already build path B; the listing is 0.9.1 work per D29 and does not exist yet |
| [`docs/APP-SKILL.md`](APP-SKILL.md) | the app skill `hermes-vox-app` and how to install it on the agent. Its 380 claims are checked by `scripts/check-skill-claims.py` |
| [`docs/APPROVALS-ACCEPTANCE.md`](APPROVALS-ACCEPTANCE.md) | the approval acceptance cards and the debug arm. Corrected: ordinary turns now ride `/v1/runs`, not `/v1/responses` |
| [`docs/ASSISTANT-ROLE.md`](ASSISTANT-ROLE.md) | `android.app.role.ASSISTANT`: what the app does, what Android does, what the device maker owns |
| [`docs/BUDGETS-0.9.1.md`](BUDGETS-0.9.1.md) | the CPU/GPU/battery/RSS budgets for the continuous lane, proposed and measured kept apart |
| [`docs/BASELINE.md`](BASELINE.md) | the per-release performance baseline instrument and its 0.9.1 numbers |
| [`docs/MATRIX.md`](MATRIX.md) | the per-device behaviour matrix instrument (single-device; #208 carried) |
| [`docs/PRESENCE-REPORTS.md`](PRESENCE-REPORTS.md) | the `presence-report` issue template, the diagnostic block and the daily aggregate |
| [`docs/PRESENCE-TUNING-BAR.md`](PRESENCE-TUNING-BAR.md) | the presence tuning bar printed by `scripts/presence-session-report.py` |
| [`docs/INVENTION-MEASUREMENT.md`](INVENTION-MEASUREMENT.md) | the #256 invention measurement: observation only, no gate |
| [`docs/VOX-RESYNC-FIXTURES.md`](VOX-RESYNC-FIXTURES.md) | the `testdata/vox-resync/` corpus behind `VoxSoul.resync` |
| [`docs/REPRODUCIBLE.md`](REPRODUCIBLE.md) | the reproducible-build status and the commands that prove it |
| [`docs/16KB-PAGES.md`](16KB-PAGES.md) | the 16 KB page-size requirement and the history of the fix |
| [`docs/_config.yml`](_config.yml) | the GitHub Pages configuration for `docs/` |
| [`docs/index.html`](index.html) | the redirect from the Pages site to hermesvox.org |
| [`docs/fdroiddata/com.hermesvox.yml`](fdroiddata/com.hermesvox.yml) | the draft fdroiddata recipe (not submitted) |

Root documents (current): [`README.md`](../README.md) — what the app is, who it is for, install,
build, licence, and the entry into this index · [`CONTRIBUTING.md`](../CONTRIBUTING.md) — what a
change needs to be reviewable · [`SECURITY.md`](../SECURITY.md) — the threat model and the
disclosure address · [`NOTICE`](../NOTICE) — component licences and the GPL source offer.

## Historical — records of a moment

All of these carry the banner at the top. Where the body says “now” or “today”, it means the day
it was written.

### Release notes (one record per cut)

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
`RELEASE-0.9.0.3.md` — the record of what that version contained. The newest shipped release is
**0.9.0.3**; 0.9.1 is not cut.

### Plans, briefs and build directives

`docs/PLAN-0.9.1.md` · `PLAN-0.9.2.md` · `PLAN-0.9.x-to-1.0.md` (superseded by D21) ·
`PLAN-1.0.md` · `PLAN-2.0.md` (superseded by D21/D22) · `PLAN-FUTURE.md` ·
`PLAN-0.4.0-polish.md` · `PLAN-0501-visual-categories.md` · `PLAN-0502-claude.md` ·
`PLAN-0502-qwen.md` · `PLAN-0503-static-transcript.md` · `PLAN-0.8-accelerator.md` ·
`PLAN-0401-barge-telemetry.md` · `PLAN-0402-level-escape.md` ·
`PLAN-barge-endpoint-modelreview.md` · `PLAN-c1-vpn-explicit.md` · `PLAN-c2-walkie-strip.md` ·
`PLAN-c3-audio-lifecycle.md` · `PLAN-c4-log-honesty.md` · `PLAN-immediate-silence.md` ·
`PLAN-logging-patch.md` · `PLAN-pause-first-silence.md` · `PLAN-presence-motion.md` ·
`PLAN-stt-backend-remote.md` · `PLAN-transcript-sync.md` ·
`PLAN-tts-usage-toggle-barge-ui.md` · `PLAN-worker-retirement.md` ·
`docs/BUILD-ER-enhanced-realtime.md` · `BUILD-DIRECTIVE-fix053.md` ·
`BUILD-DIRECTIVE-video-routing.md` · `BUILD-DIRECTIVE-video-statewire.md` ·
`docs/CLAUDE-BRIEF-0403.md` · `CLAUDE-BRIEF-0404.md` · `CLAUDE-BRIEF-0502-anim.md` ·
`CLAUDE-BRIEF-routing.md` · `docs/SPRINT-MAP.md` · `SPRINT-PLAN.md` ·
`SPRINT-PLAN-040-gates.md` · `SPRINT-PLAN-040-gates-impl.md` · `SPRINT-PLAN-DIRECTIVE.md` ·
`SPRINT-PLAN-foundation.md` · `SPRINT-PLAN-polish.md` · `SPRINT-PLAN-polish-impl.md` ·
`SPRINT-PLAN-realtime.md` · `SPRINT-PLAN-release-security.md` ·
`SPRINT-PLAN-release-security-impl.md` · `SPRINT-2026-10-30-device-free.md` ·
`docs/DESIGN-enhanced-realtime-voice.md` (contradicted — see below) ·
`design-menu-shapes.md` · `DESIGN-presence-motion.md` · `DESIGN-sound-cues.md` ·
`docs/SPEC-conversational-bridge.md` (contradicted — see below) ·
`docs/ACCESSIBILITY-TIER.md` · `docs/WAKE-WORD.md` · `docs/TEST-PLAN-0.9.md` ·
`docs/REVIEW-DIRECTIVE-hermes-gateway.md` · `REVIEW-DIRECTIVE-vox.md` ·
`REVIEW-0.5.2-findings.md` · `docs/reviews/PRESENCE-REVIEW-2026-11-04.md` ·
`docs/REFERENCE-retirement-original.diff` — plans, briefs, directives and reviews. Each says what
was being decided or built when it was written.

### Handoffs

`docs/handoff.md` (contradicted — see below) · `HANDOFF-2026-08-28.md` ·
`HANDOFF-ER-session.md` — the state of the tree and the next agent’s instructions at that moment.

### Investigations, audits and measurements

`docs/BARGE-REGRESSION-ANALYSIS.md` · `BARGE-WHY-0.7-WORKED.md` · `CRAWL-SCROLLBACK.md` ·
`INVESTIGATION-px9-trueblack-aura.md` · `LOGGING-BLIND-SPOTS-AUDIT-2026-09-05.md` ·
`STATE-AUDIT.md` · `STT-AB.md` · `VOICE-BAKEOFF.md` · `PRESENCE-BENCH.md` (contradicted — see
below) · `PRESENCE-COST.md` · `PRESENCE-GUARD-BOUNDS.md` · `PRESENCE-LATE-DIAGNOSIS.md` ·
`docs/RESEARCH-2026-10-voice-agents.md` · `research-on-device-voice-2026-08-27.md` ·
`docs/research/RESEARCH-REFRESH-2026-11-presence.md` · `docs/NOTES-api-events.md` ·
`NOTES-voicemanager.md` · `docs/evidence/235-partial-decode.md` ·
`evidence/252-new-mid-call-redial.md` · `docs/test-artifacts/emulator-stress-2026-08-28.md` ·
`docs/milestones/m1-completeness.md` · `milestones/m2-m6-completeness.md` ·
`milestones/model-downloader.md` · `milestones/warm-voice-runtime.md` — what was measured, found
or proven, and when.

## Contradicted — historical, and would mislead without the note

These keep their body. The line says what the code or a later decision changed, so the banner is
not the only warning.

| Document | What changed |
|---|---|
| [`docs/DESIGN-enhanced-realtime-voice.md`](DESIGN-enhanced-realtime-voice.md) | It designs two modes and a soul that answers short turns. D16/D28 retired the mode vocabulary, and D30 made the soul a companion that offers the beat while the mind always answers. The contract, VOX.md and the specialist review it records still stand. |
| [`docs/SPEC-conversational-bridge.md`](SPEC-conversational-bridge.md) | It opens “Status: proposed. Nothing here is deployed.” Both halves shipped in 0.9.1: profiles come from the host (`ProfilesAsk`, `HostProfiles`) and the file offer is `FileOffer`/`FileStage`/`FileServer`. |
| [`docs/PRESENCE-BENCH.md`](PRESENCE-BENCH.md) | Its opening says D4 makes Qwen 3.5 0.8B the default presence model. D4 was amended the same day and the code keeps **Gemma 4 E2B** (`ModelCatalog`: `gemma-e2b`); the document’s own conclusion says Qwen cannot be the default on a Pixel 7. |
| [`docs/PLAN-0.9.1.md`](PLAN-0.9.1.md) | Its “carried without a number” list still includes **#165**, while D29 makes the F-Droid listing 0.9.1 work and the release wait for it. Everything else in the plan was amended to the D28 shape. |
| [`docs/handoff.md`](handoff.md) | It names **0.3.7** as the latest release and the Go connector as the architecture. The Kotlin core replaced Go in #126 and the newest shipped release is **0.9.0.3**. |
| [`docs/RELEASE-0.6.0.md`](RELEASE-0.6.0.md) | It lists the app's `LICENSE` as **Apache-2.0**. That was true when 0.6.0 shipped; the app was relicensed to **GPL-3.0** on 2026-10-19 (`2beacd9`, D11) because the speech runtime statically links espeak-ng. No current document disagrees: `LICENSE`, `NOTICE`, README, ARCHITECTURE and the F-Droid metadata all say GPL-3.0. |

## Assets

Not documents; listed so the tree is accounted for.

- **Screenshots** — `docs/screenshots/icon.png`, `call.jpg`, `speaking.jpg`, `settings.jpg`,
  `states.jpg`, `shapes.jpg`, `models.jpg`, `tts.jpg`, `visuals.jpg`. Current as *captures*, from
  the 0.8.0 build on an Android 15 emulator (the README says so). They are not evidence of the
  0.9.1 UI.
- **State-audit figures** — `docs/design/state-audit/being-conflation-dark.{png,svg}`,
  `being-off-light.{png,svg}`, `carrier-map.{png,svg}`, `light-mode-contrast.{png,svg}`,
  `pocketed-sound-off.{png,svg}`. Historical: drawn for `STATE-AUDIT.md` at its 2026-10-31
  commit.
- **`docs/REFERENCE-retirement-original.diff`** — historical artifact: the original retirement
  patch, kept verbatim. It is not bannered because a banner would corrupt the diff; treat it as a
  record of the moment, not as current code.
