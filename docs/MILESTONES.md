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
fresh install**. It gates Presence, continuous capture, semantic endpointing, and the Presence
behaviours below — and anything else marked alpha as it lands. When it is off, the settings it
covers are **dimmed and unreachable**: not hidden, and not silently running.

`Presence` remains the feature's public name in copy and release notes. "Alpha Features" is the
gate, not the name. "Enhanced Realtime" is retired as vocabulary.

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

## 0.9.2 — the gate opens

- **Presence on by default.** The Alpha gate opens for Presence: it stops being opt-in.
- **Per-profile settings** — voice, model and look, the things still device-wide. *The only
  feature work explicitly deferred.*

## 1.0 — GA

When the owner deems the product ready to leave beta. No feature list, no date.

## What this map changes in the older documents

- **D24's** sentence *"Presence remains 0.9.2"* is **superseded** by D28.
- **PLAN-0.9.1's** old "Not in 0.9.1" list is **superseded**: the Presence behaviours are in
  0.9.1 behind the gate; only per-profile settings wait.
- **ROADMAP's** 0.9.2 section keeps its content links but not its claim that Presence arrives
  there; 0.9.2 is where the gate opens.
- **Multi-profile** no longer sits under 1.0.
