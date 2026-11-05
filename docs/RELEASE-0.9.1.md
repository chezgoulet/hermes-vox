# Hermes Vox 0.9.1 — release notes

**Status:** prepared, not cut. Version code **147** · `versionName` `0.9.1` · built and gated on
`testing`; the release commit is cherry-picked to `main`, where `release.yml` builds twice and
compares the payloads before the tag is made. The list below is read from the tree at
`42ad6e0` (after #476); nothing here is announced as shipped until the tag exists.

The shape of this release is the Alpha gate. Most of what 0.9.1 adds needs no switch at all;
the unfinished work sits behind **Settings → Alpha features** (off on a fresh install), in six
sets a tester takes one at a time. This document is ordered that way.

## Ships with no gate

### The call and the agent
- **Approvals on the phone (#282).** When Hermes stops to ask before a consequential command, the
  approval reaches the live call: what is being approved, why, and yes/no by voice or touch. The
  choices are the gateway's own; `deny` is always offered and a timeout resolves to *no*.
- **Turns ride `/v1/runs` by default (#404).** The route that carries approvals, stops the run on
  a barge-in and replays a dropped event stream without leaving a hole. A gateway that answers the
  admission with **404** is remembered per profile and the turn falls back to `/v1/responses`;
  every other error stays visible.
- **A voice turn tells the mind through the API (D31).** The register, the soul-sync and the
  phone's state ride the request's ephemeral `instructions`, with a per-request reasoning effort
  (default low). The caller's words go as `input`, unchanged; the anti-tool clause is gone.
- **`/new` during a call** re-opens the microphone *before* deleting the server copy, so the next
  utterance is recognised without hanging up.
- **Interrupted audio** is keyed to its reply id and ended once: nothing from a stopped reply
  resumes after the next turn begins.
- **Quiet calls (#325).** An optional switch, **off by default**, silences notifications in the
  phone's priority Do Not Disturb mode for the length of a call only, restores exactly what it
  changed, and recovers at app start after a crash. It is not an Alpha set.
- **Same agent, many profiles.** The switcher fills itself from the host, probes every name before
  it is usable, marks unknown names unavailable rather than deleting them, and ends a live call on
  a switch. Each profile carries its own key (Keystore-encrypted), model, voice and look; a switch
  starts a fresh conversation. **Profile switching, as a feature, is in the Alpha panel** (below);
  the saved-connection machinery and the connection form are not.
- **One identity.** Presence remains the public name; "Enhanced Realtime" is retired as
  vocabulary.

### Surfaces
- **Share with agent (#370, #386).** The clipboard, a file, or another app's share becomes a named
  artifact. The card names the file, its type and its size; nothing is sent until confirmed. The
  file is staged, hashed and served once on the tailnet (100 MB cap, five-minute expiry, `410`
  after the first transfer); text under 8 KB travels inline.
- **The line (#281).** One plain-language line saying what is running and on what; long jobs show
  movement without inventing a percentage; failures name what failed.
- **Commands screen (#226).** Grouped by what a command does, in two groups, with real
  queued/running/done/failed state instead of a toast. `/diagnostics` is in the catalogue only
  while its switch in About & Diagnostics is on.
- **Discovery (#284).** The stuck hint, error naming, and a searchable catalogue that matches what
  a person says ("it's slow" finds `/compress`, "can't reach it" finds `/reconnect`).
- **Assistant role (#329).** Vox can hold `android.app.role.ASSISTANT`; the side-button mapping
  belongs to the device maker.
- **The app teaches your agent about itself (#285).** After the connection is verified it offers,
  once, to install `hermes-vox-app` on the gateway — installed is not enabled, and the mechanism is
  documented.
- **The transcript scrolls back (#368)** to what the agent said, bounded, without a live reply
  yanking the view.
- **Light mode is a ground, not a black panel.** Every visual family carries an authored light
  value; measured floors are enforced by `LightModeContrastTest` (worst solid body 4.13:1, reply
  text 16.36:1).
- **Settings split by what you are changing** — Being, Hearing, Voice, Presence — with one
  disclosure per section and a hint on each row. Particle controls with a live preview.
- **The hardware check** runs at first run for everyone and advises in the Alpha panel; it never
  enables anything.

### The phone is not a second decision-maker
- The phone no longer matches your words against a local phrase list. *"Set a timer"* goes to
  Hermes like every other turn: with **Phone tools** set up and the timer group granted, Hermes
  calls the phone tool; without it, Hermes says what it cannot do instead of the phone acting
  behind the conversation's back (D38).

### Fixed earlier in the 0.9.x line, still shipping here
`#328` cold-start listening retry, `#330` a failed TTS engine must not block a call, `#346` TTS
package visibility, `#240` in-flight audio, `#351` `/new` re-arm.

## Needs Alpha features

Each set is selected on its own; with the master off, everything below is dimmed and unreachable.

### Presence (the first set)
- The on-device Gemma 4 E2B layer: it greets you and speaks the opening **beat** of a turn in the
  agent's voice while Hermes thinks. It is a companion, not a gatekeeper: every turn still goes to
  Hermes, which answers (D30). The `<<SKIP>>` offer is retired.
- Voice and identity come from the agent's own `VOX.md`, mirrored read-only, **one document per
  profile**, with the pre-profile mirror migrated.
- It hears tone through the model's own audio encoder; mood steers delivery.
- The handoff receipt (`event=er-handoff`) proves what the mind was handed; queued words
  (`event=er-pending`) prove the caller was not dropped.
- Presence may read what the phone is doing — a running timer, the time left, the device facts —
  so it can say *"your timer has about four minutes left"*. It may not set, cancel or change
  anything: phone actions belong to the mind. Presence and **Phone tools** are independent sets.
- Presence is unfinished and says so. It needs the 2.6 GB presence model and a phone with 8 GB of
  memory; the hardware check advises and the first real load can demote the choice.

### Continuous listening
- #228/#229: the microphone and recogniser stay live for the call, partials are read every 100 ms,
  and semantic endpointing judges the end of a turn instead of waiting out a fixed silence. Both
  switches default **on inside this set**; about 300 MB more memory during a call. With the gate
  off, the old 800 ms endpoint runs unchanged.
- This set **requires Presence**, because continuous listening is how the being hears. It cannot
  be selected without Presence; a stored selection from an earlier build is inert and the picker
  says why. Turning Presence off leaves it inert.

### Turn-taking
- **Backchannels (#245)** — a recorded nonverbal acknowledgement in a mid-thought pause, capped,
  never while anything else sounds; needs continuous listening.
- **Resume after an interruption (#243)** — a barge leaves a record, and the next voice turn
  carries one plain directive to answer the interjection first, then pick up without repeating.
- **Overlap classification (#242)** — the words the barge carried decide whether to carry on, stop
  and listen, or start a new turn.

### Phone tools
- This is the mind acting on the phone, and it is independent of Presence: take either set without
  the other.
- The mind acts on the phone through **one MCP tool**, `phone`, on the tailnet, with a result for
  every call and an undo for every reversible one. The tool schema is built from **thirteen
  capability groups**, each Off / Ask / Allow and **all Off by default**. The three words mean one
  thing for every group: Off refuses, Ask raises the phone's card for **every** action, Allow runs
  without one; there is no per-tier restriction. `uninstall` and `file_offer` are built and run at
  Allow or after their card; `install` is not on the surface at all (a direct call is
  `unknown-action`). The app sets the server up by asking the agent to edit its own configuration,
  and says **Connected** only after its own server has seen an authenticated MCP handshake.
- The surface this release adds: `file_list` / `file_read` (with a 65 536-byte cap per answer and a
  windowed read above it), `file_offer`, `apps` (the installed list, plus what usage access can
  honestly say is running), and `notification_dismiss`. `notification_dismiss` is Tier 1 and not
  undoable; `apps` is a read.
- Files are keyed by the grant's tree id, and the `folder` enum is the granted labels. Android 11+
  will not grant the `Download` root, so the picker opens there and the owner picks a subfolder.
- There is no local phone lane any more. A timer now costs a round trip and needs this set plus the
  timer group; that is the price of the mind being the only decision-maker (D38).

### Switching profiles
- The **Profiles** command appears in the commands sheet under *The agent*; each row is the agent
  you would be talking to, with **IN USE** on the active one, and a switch ends the live call and
  starts a fresh conversation. Unfinished until a host's own profile list arrives reliably (#436).
- It leaves Alpha once a device pass shows profile switching works as intended.

### Sessions
- The conversations your agent already has, including ones started at your desk, can be picked up
  on the phone; resuming shows what was said and continues the same conversation.
- It leaves Alpha once a device pass shows conversation switching works as intended.

## Not in this release

- **#202** (clipping) and **#240** (interruption) are fixed in code and await a human ear on this
  build; they are not "known broken", they are "unconfirmed by the only instrument that counts".
- **#208** the two-device behaviour matrix; one handset only, and `docs/MATRIX.md` says so.
- **F4, GPU contention** — not run. F5's resident-stack soak is done.
- **The F-Droid listing** — the recipe is a draft and the app is not listed. Per D29 this is 0.9.1
  work and the release waits for it; see `docs/FDROID.md`.
- **The wake word** — foundation only. **Tier 3 / screen control** — design only. **Full-duplex**
  — long horizon.
- **Two handoff edges are open, and the release notes do not promise them.** Android may silently
  refuse a background activity start, so `launch`/`open`/`message`/`dial` report the handover, not
  proof another app came forward. And `dial` ends Vox's own call — which is also the MCP listener's
  lifetime — so its result can be lost before the mind receives it. Both are tracked, not fixed
  here.
- **A device pass for Sessions and Switching profiles.** D39 keeps both behind the gate until that
  pass; neither is promoted by this cut.

## Verification

- `bash scripts/gate.sh` (the toolchain check, the runtime fetch, `assembleDebug`,
  `testDebugUnitTest`, the stream parity harness, the doc-truth and skill-claims checks) is green
  on the tree this document describes.
- The device pass is the owner's: the nine checks in `docs/RUNS-ROUTE.md`, the approval cards in
  `docs/APPROVALS-ACCEPTANCE.md`, and the per-set checks named with each Alpha feature.
- The measured baselines are in `docs/BASELINE.md` and `docs/BUDGETS-0.9.1.md`; the cold-start
  median on the 0.9.1 build is 161 ms without Presence and ~210 ms with it, and a live call with
  Presence costs about 3 GB resident.

## Release mechanics

1. Bump the version in `android/app/build.gradle` on `testing` (done: 147 / 0.9.1).
2. Update `ReleaseNotes.kt` for anything the user must act on.
3. Cherry-pick the release commit to `main`.
4. Dispatch `release.yml` from `main`; it builds twice (path-B sherpa from source) and asserts
   version, signature and payload equality.
5. Attach the APK and `SHA256SUMS` to the GitHub release; nightlies install over the release because
   both are signed with the release key.
6. `docs/RELEASE-0.9.1.md` is filled with the final hashes and the run's verdict, not before.
