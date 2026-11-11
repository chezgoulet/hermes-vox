# Milestones — the current map

Set 2026-11-03 by the owner, after the pre-release architecture review found the documents
describing a release that no longer existed. Revised the same day: **the Presence product work
is in 0.9.1 behind the gate, not deferred.** Authoritative over `ROADMAP.md`,
`docs/PLAN-0.9.1.md` and any decision text it supersedes (D28).

## How versions mean things

`0.9.x` is **beta**. `1.0` is **general availability**, and it arrives when the owner says so —
not on a schedule. Between now and then the scheme is:

```
(GA major).(features, upgrades, major releases).(point releases, refinements, finesses).(fixes)
```

`0.9.1` is the current beta milestone and **stays open, absorbing work, until the owner signs it
off**. There is no feature bucket called "1.0" — GA is a state the product reaches.

## The Alpha gate

There is **one gate for everything unfinished**: **Enable Alpha Features**. It is **off on a
fresh install**, the row immediately above the alpha block at the end of Settings. Its panel
lists six sets, each selected on its own: **Presence**, **Continuous listening**, **Turn-taking**,
**Phone tools**, **Wake word** and **Digital assistant**.
Continuous listening requires Presence; Phone tools is independent of it. When it is
off, the settings those sets cover are **dimmed and unreachable**: not hidden, and not silently
running. Quiet calls (#325) is not a set; it is its own switch, off by default.
**Sessions** and **Switching profiles** left the gate on 2026-11-06 (D39), so `/sessions`
and the profile switcher are on for every install.

`Presence` remains the feature's public name in copy and release notes. "Alpha Features" is the
gate, not the name. "Enhanced Realtime" is retired as vocabulary.

`0.9.1.1` is the patch cut on 0.9.1: the profile list a gateway returns is read in the shapes an
agent actually writes, and a profile's local label can be corrected after its address changes.
`0.9.1.2` corrects that patch's default rule: only the built-in `default` answers at the host
root, so a name the gateway does not serve is reported rather than saved.
`0.9.1.3` is the cleanup cut: the short-reply drain that held calls for two minutes, the readiness
gate that says what a call is waiting for, Piper as the recommended voice, and the profile and
Settings repairs.

## 0.9.1 — the release in acceptance

Everything in flight, plus everything discovered until the owner is satisfied.

**The loop and its surfaces.** The state audit, operations, discovery, the commands screen, the
settings split, light mode, the being's look controls, the entity named by the host.

**Approvals, natively.** The turn stream moves onto the route that carries `approval.request`
(PLAN §10). A debug arm is test tooling, never the shipped mechanism.

**Profiles from the host**, with switching flawless.

**Share with agent** — the clipboard and files offered to the agent rather than uploaded, plus Vox
as a share target.

**The transcript scrolls back** to what the agent said.

**Presence — the layer.** Ships behind the gate: the soul per profile, the handoff receipt, the
cost controls (dormant), the first-run hardware check that advises.

**Presence — the behaviours, also here, also behind the gate.** Asides and backchannels
(#245, #243), resume after an interruption (#243), overlap classification (#242), the tuning bar
(#217), a Presence transcript that is not empty (#223), the invention measurement (#256), and a
fresh pass on the presence directive and its rails.

**Presence — its tool use (D38, which supersedes D32–D34's two-lane shape).** The layer is not
finished at "the phone talks and the mind acts". It includes: the mind's **ongoing context** of what
the phone is doing (actions running, what the soul last said, device state) carried on every voice
turn in the ephemeral instructions; the phone's capabilities — timer, alarm, reminder, DND, volume,
share — reached by the mind through MCP, with a result for every call; and the mind's **veto**,
pre-execution for consequential acts and post-hoc undo for reversible ones. Presence may read that
context and may not act on the phone. #421 carries the design.

**VOX.md per profile**, presence-gated: fetched on the first call for a profile when Presence is
on, and the correct soul loaded into the local model when the profile switches.

**The turn budget** pauses for an approval and abandons route-aware.

**First run (#230)** and **the retirement of the mode vocabulary (#222)** — a default now has to
be decided at first run.

**The hardware check advises, never enables.** A cheap RAM/free-memory gate at first run, then a
real load attempt on the first call that can demote the choice. If the phone cannot carry
Presence it stays off, with one line saying why and where to turn it on.

**Carried, without a number**: #191 (presence crash), #202 (truncation), #208 (second handset),
#179 (co-residency), #165 (store rail), #249 (render budget). Done in 0.9.1 if achievable;
otherwise they close in whatever release closes them.

## 0.9.1.3 — the cleanup cut

Set 2026-11-10 by the owner: **0.9.1.3 is a cleanup release.** It carries the repairs and the
polish owed to 0.9.1 and does not wait for a feature. `versionCode` 150.

**In the cut.**

- *The voice leg.* #549 (an unbuilt track read as a dead engine), the re-warmed engine adopted
  by the open reply, the stuck-turn wedge retired, and `event=run-admit` so the next field log
  can say whether a turn reached the gateway (#553).
- *A connection named once.* The title is the connection — **Hermes-Agent** for the built-in
  default, the profile's own label otherwise — and the pill never repeats it (#552).
- *A saved profile can be removed.* Long-press a row; the host keeps its profile, the phone
  forgets the address, key, model and VOX.md (#554).
- *#533 — the profile ask stops travelling on a route with no approval notifier.*
- *#202 — truncation after a barge-in.*
- *#413's remaining findings*, each fixed or declined with a reason.
- *The measurements that need no room full of speech*: #249's render budget and #209's baseline.

**Moved out of the cut, deliberately.**

- **#228 and #229, continuous listening.** A feature, not a cleanup. It gets its own release, and
  Presence on by default waits for it.
- **#404, #376 and #282, the approvals route.** A transport change with a device proof of its
  own; it is a release, not a patch.
- **#405's talk-heavy soak.** It needs speech in the room, and the numbers it produces belong to
  the feature above.

**After the cut: the governance review.** Set 2026-11-11. When 0.9.1.3 is published the owner
turns to governance and process — the release rhythm, what quality gates are adopted rather than
proposed, the commercial position, and the community space — and the outcome is published to the
community rather than decided in private. Nothing in that review blocks this release.

**Gate for the cut.** The standing checklist, plus: the voice witnesses on a real Supertonic
call; a removed row gone from a real sheet; an approval-free profile ask that still names what
the host serves; and every number quoted in the release note measured on this build.

## 0.10 — the next minor, and the gate opens

Set 2026-11-11. **`0.9.2` never opened.** The 0.9.1 work went out as point releases — `0.9.1.1`,
`0.9.1.2`, `0.9.1.3` — and everything tagged for 0.9.2 moves here. The label is `milestone/0.10`;
`milestone/0.9.2` is retired, and the issues once carried by it are re-tagged.

What it holds today, from the issues tagged to it:

- **Presence on by default.** The Alpha gate opens for Presence. It waits on continuous listening
  (#228) and semantic endpointing (#229) — the mechanics the rest of the layer rests on.
- **The approvals route.** #404 flips the turn stream to `/v1/runs`; #376 and #282 are the proof
  that a sign-off reaches the phone and can be answered.
- **Localisation.** #528's contract first, then the staged pull requests that follow it.
- **Operations** (#281) — what is running, and on what — and **first run** (#230).
- **Voice and hearing quality.** #569 (the recogniser at RTF 1.0–1.8 on the field device), #559
  (Supertonic's dropped words), #562 (a smaller presence model, or no model for most lines), #374.
- **The measurements.** #209, #208, #179, #405, #217, #279.
- **Presence tool use** (#421) and **per-profile mTLS** (#514).

A living milestone, not a promise: it is expected to grow as field reports arrive. A frozen list
would mean we stopped listening.

## Polish is continuous, and belongs to no milestone

Refinements are not saved up. A rough edge found in use — a wording, a colour, a timing, a screen
that needs explaining — is fixed when it is found and ships in the next point release. The
Perpetual task does not get a milestone number, and it does not wait for 0.9.2 to be planned.

**Refinement belongs to the milestone that commits the feature (D31).** A feature is not finished
when its first working version lands. Deciding what it actually *is* — its design, its edges, the
shape a careful review would give it — is that milestone's work, not the next number's. Deferring
it is how a feature ships as a sketch and stays one. What does not get a number is polish of things
already shipped: the rough edge found in use, fixed when found.

The same applies to defects: a real one is fixed when it is known, not scheduled. What a milestone
records is the work that needed deciding, not the work that simply needed doing.

## 1.0 — GA

When the owner deems the product ready to leave beta. No feature list, no date.

## What this map changes in the older documents

- **D24's** sentence *"Presence remains 0.9.2"* is **superseded** by D28.
- **PLAN-0.9.1's** old "Not in 0.9.1" list is **superseded**: the Presence behaviours are in
  0.9.1 behind the gate, and per-profile settings landed there too.
- **ROADMAP's** 0.9.2 section keeps its content links but not its claim that Presence arrives
  there; 0.9.2 is where the gate opens.
- **Multi-profile** no longer sits under 1.0.
