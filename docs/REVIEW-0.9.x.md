# Adversarial review: the 0.9.x roadmap, the 47 open issues, and the code

Reviewed at `9fd95bd8` (`v0.9.1.3`), detached, with the uncommitted plan files read as the proposal.
No device was attached. Every number below comes from code, a committed doc, the tracker, or
`review-context/hermes-vox-merged.log`. Where only a phone can settle something, I say so.

**Unit suite at this sha:** `./gradlew --no-daemon testDebugUnitTest` → BUILD SUCCESSFUL in 1 m 31 s,
**2,308 tests, 0 failures, 0 errors, 0 skipped** (summed from `app/build/test-results/testDebugUnitTest/*.xml`).
I did not run `scripts/gate.sh`.


> **Record, with errata (added 2026-11-12).** This is the review as delivered at `9fd95bd8`. Three
> things in it changed after it was written, and the text below is left as it was:
>
> 1. **#281's "residual defect" was wrong.** On the runs route the gateway sends a one-line
>    `preview`, not arguments (`docs/RUNS-ROUTE.md:217-218`). The app passes it through
>    (`core/Stream.kt:917-918`), and `Operations.previewTarget` names the subject from it
>    (`Operations.kt:203-219`, pinned by `OperationsTest`). The line keeps its subject on both
>    routes. #281 is device acceptance only.
> 2. **The D45 notice and call windows this review counts no longer exist.** D47 (2026-11-11) removed
>    every fixed waiting window. A default-on graduation still needs a design note and a call, open
>    until the cut, but adds no days.
> 3. **The field log moved.** `review-context/` no longer holds it. The recount is reproducible with
>    `scripts/field-log-turns.py --since-field speechEndToRenderDone` (PR #574) on the reporter's
>    log, which is not committed because it contains their tunnel hostname.
>
> What came of it: `SPRINT-PLAN.md` (the sequence) and `TRACKER-STATE.md` (what the tracker pass
> did), both at the repository root.

---

## 1. Verdict

**What is right.** The ordering instinct is right: measure first, and promote small sets before
risky ones. The Presence/continuous-listening coupling really is gate-only. Presence hears the
per-turn audio (`VoiceController.kt:2460`), and the only link between the two sets is
`AlphaGate.kt:101-104`. The freeze is the right tool for 0.10.

**What is wrong.**

1. **The floor the plan protects is a configuration nobody runs.** With the gate off, the call uses
   offline `whisper-base` (`ModelCatalog.kt:198`), a 900 ms partial cadence
   (`ContinuousCapture.kt:48`) and an 800 ms VAD silence. The owner's field device runs Nemotron,
   100 ms cadence and Presence (#569: "every Alpha feature on"). Every human run (G4 defaults to
   the owner) therefore exercises the alpha app. The plan's central rule ("no less stable with the
   gate off") has no instrument and no person behind it.
2. **Graduating continuous listening replaces the floor; it does not build on it.** Both switches
   default on (`ContinuousCapture.kt:41`, `SemanticEndpoint.kt:31`). Removing the gate at 0.9.4
   changes every user's capture path. Under D45 that is a *major*, which needs 14 + 7 days. The
   roadmap mentions neither.
3. **The evidence rule's own reference script miscounts.** `field-log-turns.py` treats `fullReply`
   as "ms of audio". It is gateway latency (`VoiceController.kt:2823`). Its "12 of 78" has the
   right numerator and the wrong denominator. The post-mortem then built a false contradiction on
   it (§7).
4. **Four items are scheduled as if unbuilt but already shipped:** the runs route (#404), the
   approval client (#282), the operations line (#281), and the #528 contract.
5. **0.9.4 cannot finish as scoped.** #242 needs two devices (declined, #208) and speaker enrolment
   (#46, deferred to 1.1). The owner ruled it future on 2026-11-06, and PLACEMENT reverses that
   without saying so.

**What I would do first:** correct the record (§6, item 1). Then define the floor as the
gate-off default configuration and give it a comparator before any set graduates.

---

## 2. The issues, verified

**How to read the table.** "Placement" is PLACEMENT.md's proposal; "Mine" is where I would put the
issue. A **bold** entry in "Mine" is a disagreement. All 47 bodies and comments were read with
`gh issue view --json` (the plain form fails on a projects-classic GraphQL error).

| # | Verdict | Evidence | Placement | Mine |
|---|---|---|---|---|
| 45 | **REAL** (body's citations stale) | No detector exists: `WakeWordEngine.kt:17-18` ("Nothing in this build implements it"); only `InertWakeWordEngine`. The body's `ModelCatalog.kt:72-74` "walkie" enum and `VoiceController.kt:132-221` are from 2026-08. | future | future (agree). Also remove the false blurb at `AlphaGate.kt:69` now, not at 0.9.3. |
| 46 | **REAL** | No speaker-embedding path anywhere; owner comment 2026-10-19 defers to 1.1. | future | future (agree) |
| 179 | **REAL**, half measured | Memory/thermal measured 2026-11-02 with **Whisper base.en**, not Nemotron (comment 3). RTF and stutter under a live turn are still missing. The only in-situ Nemotron RTF is #569's. | 0.9.2 | **merge into #569**: same measurement (`event=stt-rtf`, `VoiceController.kt:1280`), same A/B |
| 202 | **UNVERIFIABLE WITHOUT A DEVICE**, but not evidence-free | `scripts/truncation-triage.py` on the surviving log: heard-whole 646, user-cut 429, **synthesis 16, retirement 3**, not-enough-log 1. Nobody has run it on this log. | close (limitation) | close with limitation, **but quote those 19 candidates in the closing comment**. They may be confounded by the #538 drain bug in the same log (UNVERIFIED). |
| 208 | **MISDESCRIBED** | Title says "two devices"; owner comment 2026-11-03 (D29): "No second handset is being acquired." A two-device instrument cannot run every release. | standing instrument | **retitle to single-device**, or drop it from the instruments |
| 209 | **REAL**, partly measured | Pixel 7 cold-start rows exist (comments 10-31, 11-01). The 11-11 comment's "the 189 s render p95 … is that bug" is **wrong**: 189,442 ms is log line 51204, a 2,001-char reply (~131 s of audio at 15.3 chars/s). It is playback, not the drain bug. | standing instrument | standing instrument, plus a comparator (§3, rule test). `speechEndToRenderDone` grows with reply length, so drop it as a drift metric. |
| 217 | **ALREADY FIXED** (instrument) / **REAL** (tuning) | `scripts/presence-session-report.py --bar` exists (comment 2026-10-20, #246). Its rows (speak-rate of working slots, late, repeats, render p95) measure **Presence lines**. | 0.9.5 | **0.9.6.** It is Presence's bar, not turn-taking's; 0.9.5 needs its own (§3). |
| 228 | **ALREADY BUILT** behind alpha | `VoiceController.kt:945-947`, `ContinuousCapture.kt`. Still open: speech-loaded soak (#428 G7) and battery (#405 run was on charge). | 0.9.4 | 0.9.4 (agree), if decoupled |
| 229 | **MISDESCRIBED** | The title promises "commit early, and be walkable back", but PROVISIONAL is computed and never acted on (`ContinuousCapture.kt:98`). The endpointer is an English word list plus partial stability (`SemanticEndpoint.kt:59-67`). Its own bench: "no setting of this arm beats the fixed 800 ms pause on both at once by more than 100 ms" (`:35-36`). | 0.9.4 | **0.9.4 graduates continuous capture only.** Semantic endpointing stays off by default until it has a non-English answer (it collides with 0.10, §3). |
| 230 | **REAL**, partly built | `aeefdc6f` added the hardware check and part of first run. Owner ruled "full revamp" (2026-11-06). | 0.9.8 | 0.9.8 (agree), but 0.9.8 is overloaded (§3) |
| 232 | REAL (future design) | No code. | future | future |
| 233 | REAL (future design) | No code. | future | future |
| 234 | REAL (future design) | No code. | future | future |
| 237 | watch item | Not work. | future | **close; move to a watch-list doc.** Judgement call: a tracker with "watch" issues hides the real backlog. |
| 238 | **ALREADY FIXED** (acted on) | Comment 2026-10-20: notes merged as `docs/NOTES-voicemanager.md` (#239); outcome recorded. | future | **close** |
| 242 | **REAL** | `BargeGate.kt` is a level test. Owner ruled 2026-11-06: "future work, with no milestone". The acceptance needs "two devices" and "the enrolled user's cadence" (#46, 1.1). | 0.9.4 | **future**, as ruled. If turn-taking needs something, write a narrower rule: a sub-300 ms voiced burst during playback never cancels. |
| 268 | stale body | Says "0.9.2 makes Presence the product — one mode, no selector", which contradicts the new roadmap. 0 comments in 21 days. | standing | **rewrite against the new series, or move to Discord** (D46) |
| 279 | **REAL**, never run | Needs a stranger, calendar/email tools on a gateway, and an approval (step 4). Under the 7-day rule a stranger cannot be a per-release dependency. | standing instrument | **0.9.7 acceptance only**; drop it from the standing instruments |
| 281 | **ALREADY FIXED** (code) | `Operations.kt` (`75849e07`, `715cadb1`); #377 fixed per #375 comment 2026-11-02. Residual REAL defect: on the shipped runs route `tool.started` carries no arguments (`Operations.kt:205`), so the line loses its subject. | 0.9.7 | **rewrite as "subject on the runs route"; any release** |
| 282 | **ALREADY BUILT**; device proof missing | `core/Approval.kt` (385 lines), `ApprovalUi.kt`, PR #364; emulator proof in comment 2026-11-01. The runs route is active (`TurnRoute.kt:36`). | 0.9.7 | **merge with #376.** One issue: a device proof via a deterministic arm. |
| 292 | watch item | Not work. | future | **close → watch doc** |
| 293 | watch item | Not work. | future | **close → watch doc** |
| 326 | **REAL**, design converged | Reporter and owner agreed 2026-11-02/08: chosen not automatic, settings, input-only (gain, NS, VAD sensitivity). | 0.9.8 | **decide now** (it is decided). It must land with or after F3b, because both move the same barge/VAD thresholds (§3, 0.9.4). |
| 373 | **ALREADY FIXED** (code); retest owed | `462c5940` (in v0.9.1) replaced "a single delayed isSpeaking poll that could never fire" with a listener plus watchdog. `CallReadiness.kt:24-28`: no call without voice. | 0.9.2 retest | **close on the owner's retest this week.** The "reporter" is the owner. |
| 374 | **MISDESCRIBED** | The body says "signature of an endpoint/partials problem". Its own comment (2026-11-02) reproduces a **substitution** ("good to" → "going to") with a complete endpoint. | 0.9.2 / close | close as evidence (agree), with the corrected diagnosis |
| 375 | tracker for a released version | v0.9.1 through v0.9.1.3 are tagged. | close at sign-off | **close now**; carry any unticked child |
| 376 | **REAL** | 0 `event=approval` lines in the 0.9.1 block of the reporter's log too (grep). The comment shows the acoustic arm blocked by STT. Needs a gateway-side always-ask policy: an upstream dependency. | 0.9.7 | 0.9.7, **timeboxed** (§6) |
| 388 | **REAL**; owner comment wrong | The owner wrote "Supersonic is no longer a backend". The reporter meant **Supertonic**, which is a backend (`SherpaTts.kt:184` `speed = voiceSpeed * m.speed`; 15 files reference it). | 0.9.8 | **any pre-freeze release, now.** ROADMAP:248 calls it the current good first issue; scheduling it six releases out contradicts that. |
| 404 | **ALREADY FIXED** (code); **STALE** body | Runs is the default since v0.9.1 (`c5fd226a`, `TurnRoute.kt:36`). Stop is implemented (`GatewayClient.kt:117`, `TurnAbandon.kt:37`). The 11-03 comment ("no POST stop anywhere") predates it. | 0.9.7 | **0.9.2: device pass of the route users already run** (`docs/RUNS-ROUTE.md` checks). It is on the default path *now*. |
| 405 | **REAL**, half measured | 2026-11-04: silent room, on charge; +~300 MB; MemAvailable floor 347 MB. Talk-heavy and battery missing. | 0.9.4 | 0.9.4 (agree) |
| 421 | partly superseded | Route 2 (MCP) shipped as Phone tools (`PhoneMcp.kt`, D34–D38). D38 ("the phone parses nothing") rejected path 1's local grammar. The body's `ResponsesClient.kt:125` quote now lives at `VoiceTurn.kt:10`. | future | future; **rewrite to what remains** ("context up" and veto) |
| 428 | tracker for a released version | — | close | **close now**; F-Droid to its own issue (D40) |
| 429 | **STALE** | Body: "one `phone` tool with six actions". Code: 13 groups (`PhoneGrants.kt`, 13 `label =`; `docs/MCP-SURFACE.md:6`). Owner comment 2026-11-06 confirms. | verify | **close**; Tier 3 lives in #446 |
| 442 | future design | — | future | future |
| 443 | future design | — | future | future |
| 444 | future design | — | future | future |
| 445 | umbrella | — | future | future |
| 446 | future design | "Vox already holds the assistant role" holds only if the user grants it (`AssistantRole.State`). | future | future |
| 447 | upstream | — | future | future |
| 448 | future, **coupled to #562** | Its premise is E2B's multimodal `Content`. If #562 picks a text-only 270M model or a catalogue, #448 loses its model. | future | future; **record the coupling on #562** |
| 496 | **BLOCKED ON A PERSON** (5 days); **plausibly ALREADY FIXED** | Reporter on 0.9.0.3 with `tts=System` (log in body). `462c5940` (in v0.9.1, not v0.9.0.3) fixed exactly "the caller's speaking latch stuck after the first utterance" on the system engine. Not the #538 drain bug: #517 is 0.9.1. | 0.9.2 retest | **close on 2026-11-13 under the 7-day rule**, citing `462c5940` |
| 514 | **BLOCKED ON THE OWNER** (4 days) | PierreCsn asked 2026-11-07 whether same-origin profiles may hold different certificates; no answer. | 0.9.8 | **answer now; land whenever ready before the freeze.** Independent of every theme. |
| 528 | **ALREADY FIXED** (contract) | PierreCsn agreed to all three open questions on 2026-11-07. His layout question of 2026-11-08 is unanswered. | 0.9.2 (contract) | **extraction in 0.9.2 or its own release** (§3, §6) |
| 538 | **REAL**, fixed in v0.9.1.3 | `199fcbae`. The closing comment told the reporter "of 40 replies … 22 ran to 126.8–127.1 s" (does not reproduce, §7) and "Your log shows the recogniser running slower than real time". **The log has 0 `event=stt-rtf` and 0 `stt-live-fallback` lines**; the RTF evidence is the owner's (#569). | close on confirmation | close on confirmation, **after posting a correction** |
| 559 | **REAL** (report); **checkable without a phone** | The issue itself says the three-way split test "does not need a phone". A voice-leg fidelity defect, not Presence. | 0.9.6 | **0.9.2 host bench (≤1 day), or close** with "Piper recommended" (shipped in 0.9.1.3) as the limitation |
| 562 | **REAL**; research with no timebox | Needs "a few hundred real turns" replayed per candidate, `.litertlm` GPU availability, and an owner blind rating. | 0.9.2 | 0.9.2, **timeboxed to 5 working days** (§6) |
| 569 | **MISDESCRIBED** (scope) | Its log line `cadenceMs=100` is the continuous-capture cadence (`ContinuousCapture.kt:45,63`). With the gate off, cadence is 900 ms and the default recogniser is offline Whisper, which emits no `stt-rtf` (`VoiceController.kt:1276`, `if (live != null …)`). This is a defect of the alpha configuration, not the floor. Also `rtf=1,01` is printed with a locale-dependent `"%.2f".format` (`VoiceController.kt:1281`). | 0.9.2 | 0.9.2 as measurement, **with a gate-off arm added**. Its fix belongs to 0.9.4. |

### Placements I would change, summarised

- **Close now (11):** #375, #428, #429, #238, #237, #292, #293, #373 (on the owner's retest), #374,
  #496 (on 11-13), #538 (after the correction).
- **Pull forward to 0.9.2:** the #404 device pass (it is the shipped default route); the #559 host
  bench; string extraction (#528).
- **Push out:** #242 to future, as the owner ruled.
- **Move between releases:** #217 to 0.9.6; #326 next to F3b.
- **Unblock, don't schedule:** #514 and #388 can land in any pre-freeze release; the owner owes
  #514 an answer.
- **Merge:** #179 into #569; #282 into #376.
- **Labels:** `awaiting-reporter` is on 11 issues. On #217, #228, #229, #230, #281, #282 and #404
  the "reporter" is the owner. Read literally with the new 7-day rule, it would auto-close the core
  features. Rename it `awaiting-device` for owner-held items.

---

## 3. The roadmap, falsified

### 3.0 The five claims the plan depends on

**(a) "The single-turn call is the stable floor; with the gate off every release is no less stable."**
This is not measurable with what is in `scripts/` today, for three reasons:

- **There is no comparator.** `baseline-report.py` prints one build's table ("the point is drift
  between cuts", its own docstring), and `verdict-f8.sh` writes `docs/verdicts/<label>.md`. Nothing
  diffs two of them, and no threshold says what "worse" means. `docs/verdicts/` does not exist, so
  F8 has never produced a row.
- **The cut checklist omits the instruments.** `docs/templates/RELEASE-CUT.md` has no line for
  #208, #209, #279, F4, F5 or F8, although ROADMAP:233 and PLACEMENT say they "move into" it.
- **Nobody runs the floor.** The default configuration is `whisper-base` (`ModelCatalog.kt:198`),
  legacy 900 ms partials (`ContinuousCapture.kt:48`), and an 800 ms VAD silence
  (`SemanticEndpoint.kt:6`). The owner's field phone runs every alpha set (#569), and the human run
  defaults to the owner (G4). The one external field log is also alpha-shaped: Supertonic with
  Presence turned on at the owner's request (#538 comment 2026-11-10).

**Where it breaks:** the first release whose instruments are all taken on the owner's phone will
report "stable" about a configuration a fresh install never sees. Fix it with a gate-off row in
#209 on every cut, recorded on a fresh-install profile, plus a 30-line diff script with stated
tolerances. Only a device can produce that row.

**(b) "Continuous listening can graduate at 0.9.4 while the gate says it requires Presence."**
The coupling is enforced at runtime but exists only in the gate:

- `AlphaGate.dependencyRefusal` (`AlphaGate.kt:101-104`) feeds `allows()` (`:112-113`), which the
  capture setup reads (`VoiceController.kt:945`).
- Nothing in the capture or endpoint path uses Presence. Presence's "hearing" is the per-turn
  segment audio (`VoiceController.kt:2460`, `heardAudio = … turnAudio`), which exists without
  continuous capture.

So decoupling means deleting four lines, editing the blurb at `AlphaGate.kt:51`, and **amending
D38** (`DECISIONS.md:1190-1192`), not D29 as ROADMAP:135 says. The real problem is elsewhere:
promotion **turns continuous capture and semantic endpointing on for every user**
(`ContinuousCapture.kt:41`, `SemanticEndpoint.kt:31`). That is a major under D45. It also falls
foul of the roadmap's own rule ("unless the promotion changes no behaviour, it is unproven by
construction", ROADMAP:51-52): this promotion necessarily changes behaviour, so it can only be
proven on the promotion build itself.

**(c) "Presence's work adds no screens, so it can slip past the freeze."** False in two ways:

- **Promotion is itself a settings-structure change.** Presence is a row in the alpha block
  (`AlphaBlock.kt:40`, `Row("presence", "row_grp_presence", …)`) and a section that
  `AlphaBlock.canOpen` gates. D39's precedent for Sessions and Profiles was "the set was deleted and
  its panel row with it". ROADMAP:211 freezes "the settings structure".
- **The #562 decision rewrites user-visible strings.** These sentences assume the 2.6 GB model:
  `AlphaGate.kt:40-44` ("about 2.6 GB", "8 GB of memory", "about 3 GB"),
  `SettingsHints.kt:71` ("≈2.6 GB"), `MemoryBudget.kt:65` (`WARM_COST_LABEL = "≈2.6 GB"`), the
  readiness line "Loading local model" (`docs/RELEASE-0.9.1.3.md:29`), and `HardwareCheck.kt:24`'s
  8 GB bar. The first-run step #230 item 4 also explains "the model it needs".

**Pull forward:** the #562 decision and the Presence promotion's settings move must precede v0.9.8.
Otherwise #230's Presence step is written against a model that may not ship.

**(d) "The twelve regression paths are each witnessable."** Each path, with the code that would
witness it:

| Path | Named witness | Emitted? | Where |
|---|---|---|---|
| 1 install over | `versionName` | yes (dumpsys) | `regression-smoke.sh` `field versionName` |
| 2 cold start | `am start -W` **and the `event=lat` cold row** | **the cold row does not exist** | `event=lat` labels are only `turn` (`LatencyStats.kt:132-138`, called only from `VoiceController.kt:3974`). The baseline (#209) it is "within" has no gate-off row. **Cannot be walked as written.** |
| 3 call reaches green | `event=tts-ready` | yes | `TtsEngineHost.kt:271`. The better witness is `event=call-ready phase=green` (`MainActivity.kt:1055`). |
| 4 short reply | `event=tts-drain result=played` | yes | `SherpaTts.kt:587` |
| 5 long reply | `event=tts-retire` "with the full chunk count" | partly | `VoiceController.kt:3344` logs `chunks=`, but **nothing logs the expected count** to compare against |
| 6 barge-in | `outcome=barge-in` | yes | `LatencyStats.kt:122` |
| 7 presence | `event=er-soul`, `er-glue-reject reason=repeat` | yes | `MainActivity.kt:1898`, `VoiceController.kt:3678`. **Requires the alpha gate**, so it is not a floor path (contradicts ROADMAP:16-17). |
| 8 switch profile | header text | UI only, no log line | — |
| 9 refresh profiles | `event=profiles-ask … status=200 seen>0` | yes | `ProfileDialogs.kt:350-354` |
| 10 settings in call | absence of `event=pip enter` | yes (negative) | `MainActivity.kt:1230` |
| 11 hang up / redial | `event=gate-release`, fresh `startStream` | yes | `VoiceController.kt:3964`, `:2606` |
| 12 log export | `MM-dd HH:mm:ss.SSS` | yes | `VoxLog.kt:39` |

**Result:** 9 of 12 have a working log witness, path 8 is UI-only, path 5 is half a witness, and
path 2 cannot be walked. Separately, `regression-smoke.sh finish` reads `adb logcat -d`. The
reporter's log has 20,398 `barge-stat` lines out of 58,873, so a ten-minute walk may roll the
logcat ring before `finish` runs. Whether it does depends on the device's buffer (UNVERIFIED).

**(e) "Promotion is removing the set from `AlphaGate.FeatureSet`."** Not sufficient. The enum,
or the gate, is read by name on **~38 non-comment lines across 11 files** (grep excluding
`AlphaGate.kt`): `PhoneMcp.kt` ×4, `PhoneToolsSheet.kt` ×2,
`PhoneBootstrapStore.kt`, `PhoneToolsOffer.kt`, `SettingsActivity.kt` ×14, `AlphaBlock.kt` ×5,
`AlphaPanel.kt`, `WakeListening.kt`, `HardwareCheck.kt:130`, `PresenceGate.kt:84`, and
`VoiceController.kt` at 945, 964, 1330, 2582, 3525 and 3533. Deleting an entry is a compile error
at each site; each call must become unconditional, and each row must move out of `AlphaBlock.ROWS`.

Two sets also hold **behaviour on a different set's key**:

- the transcript "what was heard" after a cut (#223) is gated on **PRESENCE**
  (`VoiceController.kt:3525`);
- resume (#243) is gated on **TURN_TAKING** (`VoiceController.kt:3533`, `:2582`).

So promoting Turn-taking at 0.9.5 leaves the cut transcript behind Presence until 0.9.6. And
**Digital assistant is not really behind the gate**: the manifest handles `ASSIST` unconditionally
(`AndroidManifest.xml:166-181`), and `AssistRoute.kt`/`AssistantRole.kt` never read `AlphaGate`.
The gate only rewords a settings row (`SettingsActivity.kt:338`).

### 3.1 Per release

Probabilities are my judgement from the evidence cited. They are not measurements.

#### 0.9.2 — The floor, measured · **P(complete as scoped) = 0.25**

- **Reason.** Three items need people the 7-day rule will not wait for: #279 a stranger, #208 a
  second handset (declined), and the #562 blind rating. #562 has no timebox. The #528 contract is
  already agreed, so that item is a no-op.
- **Claimed dependency that is not real.** "#569's cause may turn out to be #562" (ROADMAP:93).
  #569's log is at 100 ms cadence with Nemotron. The cadence cliff is documented (#569 body, STT-AB
  "80 ms … past RTF 1"), and the gate-off path never runs it. #562 can only explain the Presence-on
  half.
- **Missing dependency.** The #404 device pass: the runs route has been the default since v0.9.1
  (`TurnRoute.kt:36`) and is unproven on a device. It is floor work filed under 0.9.7.
- **Can it finish?** Not as written. It has two unbounded research items (#562, #569's cause) and an
  instrument (#208) whose precondition was declined.
- **Raise to ~0.7 by:** timeboxing #562 (5 days) and #569 (2 days); dropping #279 to 0.9.7;
  retitling #208 to single-device; adding the comparator.
- **Falsifier:** if `docs/verdicts/` gains a gate-off F8 row and a #209 gate-off row within 10
  working days of the cut opening, I was too pessimistic.

#### 0.9.3 — Phone tools and Digital assistant graduate · **P = 0.55**

- **Reason.** The work is small, but the pass is under-scoped. "A timer set by voice … and one
  refusal" (ROADMAP:100) promotes 13 groups, including Messages, Calls, Notifications, Contacts and
  Apps (`PhoneGrants.kt`). The blurb understates them ("timer … DND … volume, through one tool",
  `AlphaGate.kt:62-65`). There is also no field evidence of use: the reporter's log has 60×
  `event=phone-mcp unavailable reason=alpha-off`, and no other value.
- **Claimed dependency that is not real.** "Promoting them exercises the promotion ritual." Digital
  assistant's gate gates nothing at runtime (3.0(e)), so its promotion proves nothing about the
  ritual.
- **Missing dependency.** D45. Graduating Phone tools "opens the Alpha gate for a feature by
  default"? Only if the groups default on. They default **Off** (#429, "Every group defaults to
  Off"). So Phone tools is probably *not* a major, but the release must say so explicitly. Wake
  word removal deletes a settings row, so it must precede the freeze. Fine here.
- **Can it finish?** Yes, if the pass covers one action per tier: a Tier-0 timer, the D36
  message/call handoff, and the D37 uninstall card.
- **Falsifier:** a recorded device pass showing a Tier-2 handoff and the uninstall card works on
  the first attempt.

#### 0.9.4 — Hearing graduates · **P = 0.15 as scoped; 0.5 rescoped**

- **Reason.** #242 cannot finish (two devices plus #46 enrolment; ruled future 11-06). #229's
  endpointer barely beats 800 ms on its own corpus (`SemanticEndpoint.kt:35-36`). #405 lacks a
  talk-heavy and battery run. The owner's configuration already shows RTF 1.0–1.8 at this cadence
  (#569).
- **Claimed dependency that is not real.** "Every conversation feature below assumes it"
  (ROADMAP:124). Presence does not: it hears per-turn audio (`VoiceController.kt:2460`). Only
  Turn-taking's acknowledgements need continuous capture (`VoiceController.kt:965`).
- **Missing dependency (1).** **0.10.** Semantic endpointing's `DANGLING` list is English, and the
  tokeniser strips non-ASCII (`SemanticEndpoint.kt:59-74`, `Regex("[^a-z']+")`). Once STT follows
  the UI locale (ROADMAP:201), a French "… et" reads as *finished* and commits after 400 ms instead
  of 1,600. Graduating it at 0.9.4 schedules a localisation regression. The research doc says the
  same of backchannel timing (§5, "the model is language-specific").
- **Missing dependency (2).** **#326.** The outdoor preset (0.9.8) and F3b's adaptive barge floor
  (here) tune the same thresholds; whichever lands second re-tunes the first.
- **Can it finish?** Not with #242 in it. A done-condition exists for continuous capture (#228's
  constant-tail rule, D17/D18). None exists for F3b, which has no issue (PLACEMENT puts it at
  0.9.3, ROADMAP at 0.9.4).
- **Rescope:** graduate continuous capture only; keep semantic endpointing default-off; publish a
  D45 note 14 days before the RC.
- **Falsifier:** an `stt-rtf` table at 100 ms cadence with Presence off showing RTF ≤ 0.8 on the
  Pixel 9 (the #569 A/B arm 1).

#### 0.9.5 — Turn-taking graduates · **P = 0.3**

- **Reason.** The acceptance bar is #217's (ROADMAP:144-145), but #217's rows measure Presence
  working lines (`presence-session-report.py` docstring: "speak-rate spoken / offered working
  slots"). No row measures an acknowledgement, a yield or a resume. The release has no bar of its
  own.
- **Claimed dependency that is not real.** The PLACEMENT note "Turn-taking cannot be promoted
  without #242". Turn-taking's code (`Backchannel.kt`, `Resume.kt`) does not call the barge
  classifier.
- **Missing dependency.** Truncation after a barge (carried to 0.9.6 without an issue) is already
  Turn-taking's Resume: the next turn carries "what the caller actually heard"
  (`Resume.kt:3-12`, prepended at `VoiceController.kt:2582-2587`). The transcript half sits on the
  Presence key (`:3525`). That is one mechanism split across two releases. Also, Turn-taking has no
  `dependencyRefusal` on continuous listening, so with continuous off it degrades silently
  (`VoiceController.kt:968`, `event=backchannel skip`).
- **Can it finish?** Only after someone writes the bar: ack rate per long utterance, false-yield
  rate, and resume-without-repeat rate, each from log events that already exist (`resume-mark`,
  `resume-offer`).
- **Falsifier:** a bar for turn-taking committed with a log replay that scores it before 0.9.4
  ships.

#### 0.9.6 — Presence graduates · **P = 0.15**

- **Reason.** The scope depends on #562's answer. Option 2 ("no model for most lines") replaces the
  line source, and the roadmap forbids "promotes and rebuilds the same set" in one release
  (ROADMAP:51-52). Three items have no issue. The bar's render p95 < 3 s is above the field p95 of
  3.5 s (#562 body).
- **Claimed dependency that is not real.** "Slip past the freeze" (3.0(c)).
- **Missing dependency.** #448 (future) assumes E2B's multimodality, and #562 can remove it. #559
  (placed here) is a TTS defect unrelated to Presence.
- **Can it finish?** No. "Tuned on a long run" has no length, and "pre-rendered personality stems"
  has neither an issue nor a done-condition.
- **Falsifier:** #562 deciding "keep E2B" within its timebox, plus one long-run log passing all
  `--bar` rows.

#### 0.9.7 — The loop closes · **P = 0.45**

- **Reason.** Most of the code exists: the runs route (`TurnRoute.kt:36`), the approval client
  (`core/Approval.kt`), and operations (`Operations.kt`). ROADMAP:178's "last new interaction
  surface built from scratch" is wrong in the good direction. What remains depends on others: a
  gateway policy that always asks (#376 comment) and a stranger (#279).
- **Claimed dependency that is not real.** "It needs the conversation settled (an approval arrives
  mid-turn)". The approval path is independent of every alpha set; the emulator proof ran without
  them (#282 comment 11-01).
- **Missing dependency.** The operations subject on the runs route (`Operations.kt:205`).
- **Can it finish?** Yes, if #376's arm gets a 3-day timebox, with "harness-only proof" as the
  stated fallback.
- **Falsifier:** one `event=approval-request` from a real gateway on the Pixel within the
  timebox.

#### 0.9.8 — First run, and the last words · **P = 0.3**

- **Reason.** It carries five items: a full first-run revamp (owner, 11-06), the copy pass, #388,
  #326 and #514. The copy pass is not "the last strings only" (ROADMAP:187-188). The app has **23**
  `<string>` resources (`values/strings.xml`), **234** literal `android:text="…"` attributes in
  layouts against 8 `@string` references, and at least **109** literal UI sets in Kotlin
  (`.text = "`, `setText("`, `setTitle("`, `setMessage("`, `Toast`). Blurbs, hints and readiness
  lines built in Kotlin are uncounted.
- **Claimed dependency that is not real.** None found.
- **Missing dependency.** Extraction must finish *before* first run's strings are written, or first
  run is written twice.
- **Can it finish?** Only if extraction has run as its own lane since 0.9.2, which the "one theme"
  rule (ROADMAP:62-63) forbids unless extraction *is* a theme.
- **Falsifier:** the literal counts above falling below 50 before 0.9.6 is tagged.

#### 0.10 — Localisation · **P = 0.2**

- **Reason.** "Not in it: any new screen, any rewritten sentence, any new setting" (ROADMAP:205),
  yet two of its own items need settings: an STT locale override and the voice-mismatch warning.
  Language logic also lives in code, not resources: `SemanticEndpoint.DANGLING`, the backchannel
  timing policy, and #374's English-tuned acoustic model.
- **Missing dependency.** Log number formatting follows the device locale
  (`VoiceController.kt:1281`, `"%.2f".format`; #569 already shows `rtf=1,01`). Every log script
  with integer-only regexes (`field-log-turns.py:32`, `(-?\d+)`) silently truncates decimals on a
  French phone.
- **Falsifier:** a French-locale call log that the existing scripts parse without loss, and a
  semantic-endpoint bench run on French clips.

### 3.2 Governance contradictions the series will hit

- **Veto vs no-calendar.** "Within 7 days a new date is set" (`GOVERNANCE.md:167`) against "Nothing
  in public copy names a date for a release" (`:63-64`, D42).
- **D45 and the plan.** D45 makes "opens the Alpha gate by default" a major. ROADMAP never mentions
  D45, so each default-on graduation (0.9.4, 0.9.5, 0.9.6) owes ≥ 21 days of notice. That is not
  in any release's scope.
- **PLACEMENT vs ROADMAP.** They disagree on carried work: F3b is 0.9.3 vs 0.9.4; stems, F6a and
  truncation are 0.9.5 vs 0.9.6. That is the "two documents holding one sequence"
  failure ROADMAP:5-7 says it was written to end.

---

## 4. Claims we should stop repeating

### 4.0 The recount: release note vs script vs code comment

**What I ran.** `python3 -I scripts/field-log-turns.py review-context/hermes-vox-merged.log --since-line 48957`
reproduces the script's figures exactly: 152 turns, short 78 / long 42 / absent 32; short 32 / 11
/ 0 / **12** / 23 not measured; stuck range 126,814–128,756 ms.

**The boundary holds.** `speechEndToRenderDone` is absent from `LatencyStats.kt` at `v0.9.0.3`
and present at `v0.9.1` (`git show`). Its first occurrence in the log is line **48958**; 48957 is
the `gate-release` before it, which is harmless. Two caveats:

- The rule marks "0.9.1 *or later*". `v0.9.1.1` and `.2` also carry the field, and the reporter
  was told 0.9.1.2 was out on 11-09.
- The script itself does **not** document the boundary, despite the brief's statement.
  `docs/REVIEW-0.9.1.md:128-131` does.

**The script is wrong about what it counts.** Its docstring defines a reply's length as
`event=turn fullReply=<ms of audio>` (`field-log-turns.py:14`). In the code, `fullReply` is
`uptimeMillis() - t0`, pushed when the gateway's `done` event arrives (`VoiceController.kt:2823`,
`LatencyStats.kt:63`). That is **time from turn launch to the full reply text**, not audio
duration. So its "short" means "the gateway finished within 4 s", and **78 / 42 is a latency
split**. Also, `TURN = …\bevent=turn\b` matches `event=turn-dropped` as well (14 lines). Those
rows land in "audio length absent", so 1,233 turns vs 1,217 real `event=turn` lines over the whole
file.

**No log line records streamed audio length.** Only one-shot synthesis logs `generated N samples`;
streamed chunks do not. The best proxy is reply characters (`turn done: … len=`). I calibrated it
on the 205 one-shot lines in the file: median 15.3 chars/s (range 9.3–24.5). With that rule
(`/tmp/vox-review/recount.py`, `tail.py`, not committed), in the 0.9.1 block:

| | release note | StreamDrain.kt:14-16 | script | my proxy count |
|---|---|---|---|---|
| short replies (< 4 s audio) | 40 | 29 | 78 (*latency*, not audio) | **44** (< 61 chars) |
| short replies that hit the ~127 s ceiling | **22** | — | 12 | **12** |
| short replies held until the ceiling **or** the caller stopped | — | **28** | — | **25** (12 ceiling + 11 `stop` + 2 `focus-loss`, held > 15 s after the text completed) |
| long replies (≥ 4 s audio) | 83 | — | 42 (*latency*) | **74** |
| long replies that "ran that long" | 9 | **0** ("all drained in under 6 s") | 5 render ≥ 120 s | **0 stuck.** The 5 render ≥ 120 s are 1,506–2,694-char replies (~98–176 s of audio). That is playback, not a stall. |

The ceiling set is the same twelve turns under every definition, because they are both short in
audio and fast at the gateway. They are 14–50 characters (≈0.9–3.3 s), all `outcome=stream-done`,
with render 126.8–128.8 s (log lines 49147, 52791, 55330, 55725, 56436, 57124, 57465, 57501, 58243,
58386, 58456, 58487).

**Verdict.**

- **The release note is wrong.** "22 … ran to 126.8–127.1 s" does not reproduce: there are 12. No
  definition I tried gives 40 short or 83 long. The range 126.8–127.1 matches neither the render
  range (126.8–128.8) nor the `tts-retire ms=` range (125.9–127.1). It looks like a blend of both.
- **The code comment is wrong in its numbers** (28 of 29). It is right in kind: the short-reply
  stall is the only stall in the log.
- **The script has the right numerator, the wrong denominator, and a false docstring.**
- **The post-mortem is wrong in one inference.** "five long replies also ran past two minutes,
  which contradicts the code comment" (`docs/REVIEW-0.9.1.md:140-142`) mistakes long playback for a
  stall.
- **On "every reply of 4 s or more drained in under 6 s":** the log cannot measure it. There is no
  `event=tts-drain` before 0.9.1.3, and no audio length. My proxy finds 8 of 74 long replies whose
  gate release came more than 6 s after (estimated audio + text completion). All 8 are within the
  chars/s estimate's error band. So: **not refuted, not supported, unmeasurable from this log.**

**Why it matters.** G5 (D44) blocks a tag on "a number without … the rule that counted it". The
script written to enforce that rule counts a different quantity than its docstring states. Fix the
script before G5 is enforced against anyone. The same miscount has already reached a user twice in
the #538 thread (§2, row 538).

### 4.1 Sentences contradicted by the code

| Quoted claim | Where | Contradicted by |
|---|---|---|
| "40 replies carried under four seconds of audio and 22 of them ran to 126.8–127.1 s … Of the 83 … 9 ran that long" | `docs/RELEASE-0.9.1.3.md:16-18`; #538 comment 11-11 | §4.0: 12 hit the ceiling; long replies did not stall |
| "28 of the 29 replies under 4 s … every reply of 4 s or more drained in under 6 s" | `StreamDrain.kt:14-16` | §4.0 |
| "a reply's length event=turn fullReply=<ms of audio>" | `scripts/field-log-turns.py:14` | `VoiceController.kt:2823` |
| "five long replies also ran past two minutes, which contradicts the code comment" | `docs/REVIEW-0.9.1.md:140-142` | log lines 50534, 50813, 51204, 51661, 53841: 1,506–2,694-char replies |
| "Your log shows the recogniser running slower than real time on the Pixel 9" | #538 comment 2026-11-11 | 0 `event=stt-rtf` lines in `hermes-vox-merged.log`; the data is the owner's (#569) |
| "the 189 s render p95 … are that bug, not the product" | #209 comment 2026-11-11 | log line 51204: 2,001 chars ≈ 131 s of audio |
| "the twelve regression paths … all run through it [the single-turn call]" | `ROADMAP.md:16-17` | path 7 needs Presence (`REGRESSION.md:68-69`); path 2's witness does not exist (§3.0(d)) |
| "What is not stable: the hearing timing (a recogniser slower than real time on the field device)" (listed as floor instability) | `ROADMAP.md:19-20` | only at 100 ms cadence with a live recogniser, i.e. alpha (`ContinuousCapture.kt:63`, `VoiceController.kt:1276`); the default is offline `whisper-base` (`ModelCatalog.kt:198`) |
| "the one set whose work adds no screens, so if it slips it can slip past the freeze" | `ROADMAP.md:165-167`, `:214-216` | `AlphaBlock.kt:40`; string sites in §3.0(c) |
| "Decouple … honouring D29 without amending it" / "The gate says … (D29)" | `ROADMAP.md:127-137` | the coupling is D38 (`DECISIONS.md:1190-1192`) |
| "`#528`'s contract agreed" (as 0.9.2 work) | `ROADMAP.md:88` | PierreCsn agreed on 2026-11-07 (#528 comment 1) |
| "`#404` the turn route on `/v1/runs`" (as 0.9.7 work) | `ROADMAP.md:173` | `TurnRoute.kt:36` `active = TurnRoute.RUNS`, shipped in `c5fd226a` ⊂ v0.9.1 |
| "the last new interaction surface built from scratch" | `ROADMAP.md:178` | `Operations.kt`, `ApprovalUi.kt`, `core/Approval.kt` exist |
| "the copy pass, which by now is the last strings only" | `ROADMAP.md:187-188` | 23 resources vs 234 + ≥109 literals (§3.1, 0.9.8) |
| instruments "move into `docs/templates/RELEASE-CUT.md`" | `PLACEMENT.md:28-29`; `ROADMAP.md:233` | the template has no instrument line |
| "`#242` … Turn-taking cannot be promoted without it" | `PLACEMENT.md:30` | owner ruling 2026-11-06 (#242); `Backchannel.kt`/`Resume.kt` never call a classifier |
| "a turn ending on a judgement rather than a silence" | `ROADMAP.md:118-119` | `SemanticEndpoint.decide` is a silence ratio scaled by a word list (`:79-95`); ≤ 100 ms better than 800 ms fixed (`:35-36`) |
| "Saying the wake phrase wakes the app hands-free" | `AlphaGate.kt:69` (user-visible) | `WakeWordEngine.kt:17-18`: no detector |
| Phone tools: "set a timer or an alarm, change Do Not Disturb or the volume, through one tool" | `AlphaGate.kt:62-65` (user-visible) | 13 groups incl. Messages, Calls, Notifications (`PhoneGrants.kt`) |
| "It is the closest set to finished" (Phone tools) | `docs/MILESTONES.md:66` | the only field log has 60× `phone-mcp unavailable reason=alpha-off`; the pass covers 1 of 13 groups |
| "Supersonic is no longer a backend in this tree" | #388 comment 2026-11-06 | Supertonic is: `SherpaTts.kt:184`, 15 files |
| "the signature of an endpoint/partials problem" | #374 body | #374 comment 2026-11-02 (substitution, full endpoint) |
| "There is no `POST /v1/runs/{run_id}/stop` anywhere in the app" | #404 comment 2026-11-03 | `GatewayClient.kt:117-118`, `TurnAbandon.kt:37` |
| "Within 7 days a new date is set" | `GOVERNANCE.md:167` | `GOVERNANCE.md:63-64`, D42: no date in public copy |

**Hygiene, not a claim.** `review-context/hermes-vox-merged.log` contains the reporter's tunnel
hostname (e.g. line 48879, `event=mtls … host=…`). It is untracked; keep it that way, or redact it
before anything in `review-context/` is committed.

---

## 5. The research gap

Field numbers below are from the 0.9.1 block of the reporter's log (lines 48957–58873). That is
one user, one local llama.cpp gateway reached through a tunnel, a build with the #538 defect, and
Presence on part of the time. Treat them as one sample, not a baseline.

| Practice (source) | What this app does | `file:line` | What the distance costs a user |
|---|---|---|---|
| **Latency: speech-end → first reply audio p50 ≤ 700 ms, p95 ≤ 1.4 s** (RESEARCH §2, D18) | Field: **p50 4,226 ms, p95 22,240 ms; 0 of 111 turns ≤ 1.4 s.** First *sound* p50 1,430 ms, but 77 of 116 first sounds are a `cue` (an earcon), 34 the reply, 5 a wait line. STT decode p50 249 ms; first text from turn launch p50 2,546 ms. | `LatencyStats.kt:122-125` fields; counts from the log | Roughly 4 s of beep-then-silence per turn. **Most of it is the mind, not the client**, and no release in the plan has a latency done-condition. D18's numbers gate nothing. |
| **Turn end as a classifier with an eagerness preset** (RESEARCH §5; #229) | Floor: fixed 800 ms VAD silence. Alpha: a word-list heuristic plus partial stability; the presets differ only in `stableMs`; PROVISIONAL is never acted on; English-only. | `SemanticEndpoint.kt:47-50, 59-74, 87-95`; `ContinuousCapture.kt:98` | On the floor, every turn pays ≥ 800 ms before STT even finalises. With the alpha heuristic the gain is ≤ 100 ms on its own 22-clip corpus (`:35-36`). |
| **Early / speculative start** | Exists: a stable partial after ≥ 600 ms of silence may start the turn early (`early=true` in #496's log). | `EarlyStartRule.kt:30` | The one place the floor beats a fixed pause. Unmeasured as a field rate (UNVERIFIED). |
| **Constant tail: post-speech wait independent of utterance length** (D17/D18, #228) | Default recogniser is offline `whisper-base`: `live == null`, so no partials and decode after the endpoint. Streaming Nemotron is opt-in. | `ModelCatalog.kt:198`; `VoiceController.kt:1276` | On the floor the tail grows with utterance length by construction. Size it on a device. |
| **Interruption classified by overlap type** (RESEARCH §3; Full-Duplex-Bench v1.5) | A duty-cycle level test against an echo-calibrated floor. | `BargeGate.kt`; `EchoFloor.kt`; #242 ruled future | A user's "mm-hm" cuts the reply; or, with the floor raised, a quiet user cannot interrupt (#186). Default path, every user. |
| **Recovery after interruption: resume, address, don't repeat** (RESEARCH §4; IHBench) | Built: a mark of heard vs unsaid text, prepended to the next voice turn as a directive. Behind TURN_TAKING. It is an instruction to the mind, not enforced. | `Resume.kt:3-24`; `VoiceController.kt:2582-2587`, `:3533` | Off for every default user. When on, compliance is the model's. No metric counts repeats after a resume (§3.1, 0.9.5). |
| **Backchannels as continuous, language-specific prediction** (RESEARCH §5) | Rate presets (default SPARSE), timed from continuous partials, behind TURN_TAKING and continuous capture; one policy for all languages. | `Backchannel.kt:33, 56`; `VoiceController.kt:964-968` | None on the floor. At 0.10 the policy is wrong for every non-English language, per the research the repo itself cites. |
| **Full-duplex vs half-duplex** | Half-duplex: "it listens OR speaks, never both", plus a barge watcher during playback. | `VoiceController.kt:974` | Standard for a cascaded on-device stack. Not a defect, but it caps how natural overlap can feel. |
| **Cascaded on-device stack, streaming TTS from text deltas** (RESEARCH §1) | Yes: VAD → STT → gateway → streaming TTS. First audio arrives ~400–500 ms after first text in the field rows of #209's table. | `VoiceController.kt` streamed turn; #209 comment 11-11 | Aligned with the field. Code and research agree here. |
| **Power measured per component** (RESEARCH §6) | Never measured. The #405 run was on charge in a silent room. | #405 comment 2026-11-04 | Unknown battery cost for a feature about to default on (0.9.4). |

**Answer.** If the app met its plan exactly, it would be **a well-built app that is not yet a
competent voice agent**. Three gaps decide it:

1. **The latency that users judge is not in any release.** 0 of 111 field turns reached first
   reply audio within 1.4 s. Nothing in 0.9.2–0.9.8 owns that number. The client's answer is an
   earcon, plus a Presence line whose render is p50 1.4 s / p95 3.5 s (#562): slower than the gap
   it is meant to fill.
2. **Every conversational practice graduates as a heuristic, and an English one.** The endpoint's
   word list, the backchannel policy and the level barge gate are each the "trigger-happy VAD" pole
   the research names. #242, the only item that addresses it, is ruled future.
3. **The default user gets none of it.** All of it sits behind sets whose graduation the plan
   sequences behind Presence-era uncertainty. Meanwhile the floor is fixed-pause, level-gated and
   half-duplex, with offline STT whose tail grows with utterance length.

---

## 6. What to do from here

Efforts are **estimates** in working days. Ordered by dependency.

1. **Correct the record.** 0.5–1 d · confidence high (text and one script).
   - Fix `field-log-turns.py`: rename "short" to "fast gateway", add a chars-based audio proxy, and
     exclude `turn-dropped`.
   - Correct `RELEASE-0.9.1.3.md:16-18` and `StreamDrain.kt:14-16`'s comment.
   - Post corrections on #538 (RTF attribution) and #209 (the 189 s row).
   - *Dependency:* G5 cannot block anyone's tag while its reference tool miscounts.
2. **Close and answer.** 0.5 d · high.
   - Close the 11 issues in §2.
   - Answer #514 and #528's layout question.
   - Rename `awaiting-reporter` for owner-held items.
   - *Dependency:* the 7-day rule is only fair once the owner's own queue is empty.
3. **Define the floor and give it a comparator.** 2–4 d (1 d script, 1–3 d device) · medium.
   - Gate-off, fresh-install defaults as a #209 row.
   - A diff script with stated tolerances.
   - The instruments and the comparison written into `RELEASE-CUT.md`.
   - Fix regression path 2's witness (add an `event=lat label=cold` row or drop the clause).
   - Path 7 becomes "if Presence is on".
   - *Dependency:* every "no less stable" judgement after this needs it.
4. **Device pass of what already ships on the default path.** 1–2 d device · medium.
   - The #404 runs-route checks (`docs/RUNS-ROUTE.md`).
   - The #373 retest.
   - *Dependency:* the floor includes the runs route today.
5. **#569 A/B, timebox 2 d, feeds a decision:** may continuous capture graduate at 100 ms cadence,
   and is Nemotron offered alongside it? Add a fourth arm: gate off, default config.
   - *Dependency:* gates item 10.
6. **#562 bench, timebox 5 d, feeds a decision:** Presence's line source (keep E2B, 270M/1B, or a
   catalogue), which fixes 0.9.6's scope and the strings in §3.0(c).
   - If no `.litertlm` candidate converts in 2 d, decide between E2B and the catalogue only.
   - *Dependency:* items 8, 13 and 14.
7. **#559 host bench, timebox 1 d, feeds a decision:** whether Supertonic stays experimental or
   leaves.
8. **String extraction as a lane, starting now.** 8–15 d elapsed, 2–4 d of owner review · low–medium
   (the 234 + 109 count is a floor).
   - PierreCsn has offered; the roadmap's one-theme rule should name this lane as exempt.
   - *Dependency:* must finish before #230's first-run strings are written.
9. **0.9.3 as planned, with a wider pass.** 2–4 d · medium.
   - Cover one Tier-0 action, a D36 handoff, and the D37 uninstall card.
   - Remove Wake word, and correct the blurbs at `AlphaGate.kt:62-73`.
10. **0.9.4 rescoped.** 4–7 d plus 21 d of D45 notice · low–medium.
    - Decouple (`AlphaGate.kt:101-104`, amend D38); graduate continuous capture only; keep semantic
      endpointing default-off.
    - Run the talk-heavy soak and a battery measurement off charge (#405, #428 G7).
    - Publish the D45 note on the day item 5 decides.
11. **Write turn-taking's bar** from the existing `resume-*` and `backchannel` events. 2–3 d · medium.
    Only then schedule 0.9.5.
12. **#376 approval arm, timebox 3 d, feeds a decision:** real-gateway proof, or harness-only
    acceptance with the gap stated. Then 0.9.7 is device acceptance plus #279.
13. **0.9.6 per item 6's outcome.** 5–15 d · low.
    - Open issues for stems and F6a first.
    - Fold "truncation after a barge" into Resume.
14. **0.9.8: #230 and the residue of item 8.** 5–10 d · low–medium.

**Research items carry their timebox and decision:** #569 (2 d, the cadence/arm decision), #562
(5 d, the line-source decision), #559 (1 d, the voice decision) and #376 (3 d, the acceptance
decision). #229's endpointer gets no timebox in this plan: it stays default-off until someone
proposes a bounded bench on non-English clips.

### The three most expensive mistakes we are about to make

1. **Graduating continuous listening changes every user's floor, and it is measured only on the
   owner's all-alpha phone.**
   - Evidence: `ContinuousCapture.kt:41` and `SemanticEndpoint.kt:31` default on;
     `VoiceController.kt:945-947`; #569 shows RTF 1.0–1.8 at exactly this cadence.
   - The #405 soak was silent and on charge. G4 defaults to the owner, and the owner runs every set.
   - Cost: the first release that changes the floor is also the first one nobody can compare
     against.
2. **Enforcing an evidence rule whose own tool counts the wrong quantity.**
   - Evidence: `field-log-turns.py:14` vs `VoiceController.kt:2823`; the post-mortem's false
     "five long replies" inference; two wrong statements to a reporter in #538 and #209.
   - G5 now blocks tags (D44). A rule that blocks on arithmetic, enforced with wrong arithmetic,
     either blocks a correct release or launders a wrong number.
3. **Treating localisation as a copy pass at the end.**
   - Evidence: 23 string resources vs 234 literal layout texts and ≥ 109 literal Kotlin UI sets.
   - Language logic lives in code: `SemanticEndpoint.kt:59-74`, the `Backchannel.kt` timing, and
     locale-dependent log formatting (`VoiceController.kt:1281`, `rtf=1,01` in #569).
   - The volunteer who agreed the contract on 2026-11-07 is told to wait for 0.9.3.
   - Cost: 0.10 slips by the size of the extraction, and the contributor most likely to do it
     leaves.

---

## 7. What I did not check, and the hypotheses I killed

**Not read or not run.**

- `scripts/gate.sh`. Only the unit suite was run.
- `StreamingStt.kt` / `OfflineStt` internals, beyond the class name and the default model.
- `SherpaTts.kt` beyond the drain witness and the speed line; `StreamDrain.step` logic.
- `BargeGate.kt` and `EchoFloor.kt` internals; `Backchannel.kt` beyond its header and default.
- `PhoneMcp.kt` security; `CallReadiness.kt` beyond its header.
- `ReleaseNotes.kt`, not opened.
- Whether `SpeechCursor` was replaced by the upsert renderer (#229 acceptance): **UNVERIFIED**.
- The diffs of `RELEASE-PROCESS.md`, `CONTRIBUTING.md`, `INDEX.md`, `COMMUNITY.md`, and
  `templates/VETO-NOTICE.md`.
- In full: `PRESENCE.md`, `PRESENCE-COST.md`, `BUDGETS-0.9.1.md`, `WAKE-WORD-RESEARCH.md`,
  `INVENTION-MEASUREMENT.md`, `STT-AB.md`, `PRESENCE-TUNING-BAR.md`.
- Whether a release APK equals its tag. I used git ancestry as "shipped".

**Only a device can settle:**

- whether `logcat -d` keeps a ten-minute smoke;
- the gate-off floor's latency and RTF;
- battery under continuous capture;
- #202's 19 flagged candidates;
- whether the owner's Pixel 7 still refuses nightly installs over its older-signed build
  (memory note dated 2026-10-21, UNVERIFIED now).

**Hypotheses I tested and killed.**

- *"The script's 12 is wrong."* No. It is the same twelve turns under every definition.
- *"The 48957 boundary is arbitrary."* No. `git show` confirms the field first appears at
  `v0.9.1`.
- *"The five ≥ 120 s long renders are stalls."* No. They are 98–176 s of audio.
- *"#569's RTF is in the reporter's log."* No: zero `stt-rtf` lines.
- *"Continuous listening needs Presence at runtime."* No. Presence hears per-turn audio.
- *"#404 is unbuilt."* No. Shipped in v0.9.1.
- *"#496 is the #538 drain bug."* No. It predates #517, and fits `462c5940` instead.
- *"Digital assistant's gate protects the ASSIST entry."* No. The manifest handles it ungated.
- *"Phone tools' promotion is a D45 major."* Probably not. Every group defaults Off
  (`PhoneGrants.kt:18`).
- *"The suite is red at this sha."* No: 2,308 / 0.
- *"#429 still describes the tool."* Confirmed stale.

**Judgement calls, and the alternative I rejected.**

- **Audio-length proxy:** reply characters at 15.3 chars/s. Rejected `tts-retire ms=`, which
  includes waiting for text. The proxy's ±50 % range is why I report the long-reply drain claim as
  unmeasurable rather than refuted.
- **"Held until the ceiling or a hang-up":** gate release more than 15 s after the text completed.
  Rejected "any `stop` outcome", which counts ordinary hang-ups.
- **"Floor":** the gate-off fresh-install configuration. Rejected the owner's daily configuration,
  because the roadmap's rule says "with the alpha gate off".
- **#404's device pass:** put in 0.9.2 rather than 0.9.7, because it is on the default path now.
- **Watch issues:** closed into a doc rather than kept as `future`. It is a tracker-hygiene
  preference; the owner may reasonably disagree.
- **Owner-held `awaiting-reporter` issues:** not counted as "blocked on a person".
- **Probabilities:** my judgement from the cited evidence, rounded to 0.05. They are not
  measurements.

---

**If the owner does exactly this plan, where does it break first, and how much survives?**

It breaks first at **0.9.2's exit**, quietly. The release exists to make "stable" a number, but:

- it has no comparator, and the cut template has no instrument line;
- every device run happens on the all-alpha phone;
- #208 and #279 need people the 7-day rule will not wait for;
- #562 has no timebox.

So it closes "with limitations", and the series' governing rule ("no less stable with the gate
off") never gets a baseline. The break becomes visible at **0.9.4**: #242 cannot finish, and the
promotion flips two defaults for every user, owing 21 days of D45 notice that no release planned.

**About three of the eight survive as written:**

- **0.9.3**, with a wider pass;
- **0.9.7**, whose code mostly exists;
- **the freeze itself**.

0.9.2 survives if it is rescoped (§6, items 1–7). 0.9.5 and 0.9.6 must be rewritten after #562
and a turn-taking bar exist. 0.10 survives only if extraction starts now rather than at 0.9.8.
