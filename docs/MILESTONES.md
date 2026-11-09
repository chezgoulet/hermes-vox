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

## 0.9.1.3 — the rest of 0.9.1, in one cut

Set 2026-11-09 by the owner: **one release, not a train of patches.** The work still owed to 0.9.1
lands on `testing` as it is ready, and 0.9.1.3 is cut when the batch is coherent — not once per
item. `versionCode` 150. 0.9.2 stays closed until 0.9.1 is signed off.

**In the batch.**

*The voice leg.* #538 (Supersonic demoted, then speech off until `/new`), with #373 and #496 in
the same investigation. First defect already merged: #549 — an `AudioTrack` that could not be
built was counted as a dead engine, and spent a one-shot recovery budget.

*How a connection is named.* The header names the connection once: **Hermes-Agent** for the
built-in default, the profile's own label otherwise, and the status pill never repeats it. Two
gaps #174 exposed: a saved row cannot be removed, and a host-side rename leaves a stale row.

*The route that can approve.* #404 flips the turn stream to `/v1/runs`; #376 and #282 are the
acceptance items that never passed because no approval could reach the phone on the route in use;
#533 is the same gap in the profile ask.

*Continuous listening.* #228 and #229 — the mechanics the rest of Presence rests on, and the one
item in the batch that is a feature rather than a repair. The behaviours built on them (#217,
#223, #242, #245) are not built again here; they need the owner's ear.

*The leftovers.* #405, #249, #209, #202, and whatever #413 left declined without a reason.

**Gate for the cut.** The standing checklist in `docs/RELEASE-PROCESS.md`, plus: a Supersonic
install runs a long call with no silent downgrade; an approval raised by a tool call during an
ordinary turn is answered by voice and by touch, and a denial stops the run; the header names one
connection once; and every number quoted in the release note was measured on this build.

**Not in the batch, because it is not code.** #165's F-Droid listing is a merge request in
somebody else's queue. The acceptance run (#375) and the Presence behaviours (#217, #223, #242, #245) need the owner's
ear and a device before anything is built on top of them. The 0.9.0.3 upgrade path
(G3) needs a decision about the test device before it needs a build.

## 0.9.2 — the gate opens, and whatever we have learned by then

**Not open.** 0.9.1.3 ships first; this milestone opens when 0.9.1 is signed off.

A living milestone, not a promise. What it holds today:

- **Presence on by default.** The Alpha gate opens for Presence: it stops being opt-in.
- **Per-profile settings** — *landed early, in 0.9.1*: a profile carries its own voice, model and
  look (`docs/PRESENCE.md`). This line stays as the record that it was carried here first.

What else lands there is decided by what the 0.9.1 acceptance run and real use turn up. Expect new
features to be wanted, expect some of them to be the ones we have not thought of yet, and expect
this list to grow between now and the cut. That is the normal shape of a beta milestone; a frozen
list would mean we stopped listening.

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
