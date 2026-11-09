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

## 0.9.1.x — the point releases that finish 0.9.1

Set 2026-11-09 by the owner. The remaining 0.9.1 work ships as point releases, cut from `main`
by `docs/RELEASE-PROCESS.md`; `versionCode` rises by one per cut. **0.9.2 does not open until
0.9.1 is signed off** — its headline, Presence on by default, rests on mechanisms that are not
proven yet.

Each patch below is a scope and a gate, not a promise of a date. A patch is cut when its gate
is met, and the order can change if a regression arrives — one already has.

**0.9.1.3 — the voice leg.** #538 is a report against 0.9.1: Supersonic stops, the system voice
takes over, and the being shows a working state while the gateway runs nothing. The bounded
fallback that replaces a voice that will not warm is itself new in 0.9.1 (#373, #499), so the
first question is why the local engine is dropped on a device that carried it before, and the
second is why the call keeps a working state with no run behind it. #373 and #496 belong to the
same investigation. *Gate: the reporter's diagnostic block and a logcat capture name the failing
engine and the moment; a Supersonic install runs a long call with no silent downgrade; an
engine that is replaced says which one speaks and why.*

**0.9.1.4 — how a connection is named.** The header names the connection once: **Hermes-Agent**
for the built-in default, the profile's label for anything else, and the status pill never
repeats it. Two gaps #174 exposed: a saved row cannot be removed, and a host-side rename leaves
the old row until a refresh. *Gate: two profiles, a switch, a rename and a deletion are each
visible on the main screen and in the sheet, with no stale row and no repeated name.*

**0.9.1.5 — the route that can approve.** #404 flips the turn stream to `/v1/runs` and proves it
on a device; #376 and #282 are the acceptance items that never passed, because no approval could
reach the phone on the route in use; #533 is the same gap in the profile ask. *Gate: an approval
raised by a tool call during an ordinary turn reaches the phone and is answered by voice and by
touch; a denial stops the run; a timeout resolves to no.*

**0.9.1.6 — continuous listening.** #228 keeps the mic open for the call; #229 replaces the
silence threshold with a judgement and a preset; #223 fills the transcript that the readable
layout leaves blank; #242 classifies an overlap and #217 gives the ear an instrument. This is the
substance Presence on by default needs. *Gate: a call in which the mic never reopens per turn,
the end of a turn is decided by the endpointer, the transcript is readable, and one long run is
summarised from the log.*

**0.9.1.7 — the measurements and the leftovers.** #405 (the talk-heavy soak, since the numbers so
far come from a silent room), #249 (the render budget against its gate), #209 (the per-release
baseline), #202 (truncation after a barge-in) and whatever #413's second review left declined
without a reason. *Gate: each number is recorded against this build, and every declined finding
says why it was declined.*

**Not in the train, because it is not code.** #165's F-Droid listing is a merge request in
somebody else's queue. The acceptance run (#375) and the Presence behaviours already merged but
never heard (#217, #223, #242, #245) need the owner's ear and a device, not a lane. The 0.9.0.3
upgrade path (G3) needs a decision about the test device before it needs a build.

## 0.9.2 — the gate opens, and whatever we have learned by then

**Not open.** The train above runs first; this milestone opens when 0.9.1 is signed off.

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
