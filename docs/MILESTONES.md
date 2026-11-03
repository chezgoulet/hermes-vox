# Milestones — the current map

Set 2026-11-03 by the owner, after the pre-release architecture review found the documents
describing a release that no longer existed. Authoritative over `ROADMAP.md`,
`docs/PLAN-0.9.1.md` and any decision text it supersedes (D28).

## How versions mean things

`0.9.x` is **beta**. `1.0` is **general availability**, and it arrives when the owner says so —
not on a schedule. Between now and then the scheme is:

```
(GA major).(features, upgrades, major releases).(point releases, refinements, finesses).(fixes)
```

So `0.9.1` is the current beta milestone and **stays open, absorbing work, until the owner signs
it off**. `0.9.2` and `0.9.3` are the next beta milestones. There is no feature bucket called
"1.0" — GA is a state the product reaches, not a bag of work.

## 0.9.1 — the release in acceptance

Everything in flight, plus everything discovered until the owner is satisfied. In scope:

- **The loop and its surfaces** — the state audit, operations, discovery, the commands screen,
  the settings split, light mode, the being's look controls, the entity named by the host.
- **Approvals, natively** — the turn stream moves onto the route that carries `approval.request`
  (PLAN §10). A debug arm is test tooling, never the shipped mechanism.
- **Profiles from the host** — populated from what the host serves, probed before use, and
  **switching must work flawlessly**. Per-profile settings are *not* in this release.
- **Share with agent** — the clipboard and files offered to the agent rather than uploaded,
  plus Vox as a share target.
- **The transcript scrolls back** to what the agent said.
- **Presence ships as a feature, behind one gate.**
- **VOX.md per profile**, presence-gated: fetched on the first call for a profile when Presence
  is on, and the correct soul loaded into the local model when the profile switches.
- **Presence cost controls** land **dormant** — `hears`, lazy load and idle release all default
  to today's behaviour. The owner sets the defaults after the acceptance run.
- **The turn budget** pauses for an approval and abandons route-aware.
- **Carried, without a number**: #191 (presence crash), #202 (truncation), #208 (second
  handset), #179 (co-residency), #165 (store rail), #249 (render budget). Done in 0.9.1 if
  achievable; otherwise they close in whatever release closes them.

### The Alpha gate

There is **one gate for everything unfinished**: **Enable Alpha Features**. It is **off on a
fresh install**. It gates Presence, continuous capture and semantic endpointing, and anything
else marked alpha as it lands. When it is off, the settings it covers are **dimmed and
unreachable**, not hidden and not silently active.

`Presence` remains the feature's public name in copy and release notes. "Alpha Features" is the
gate, not the name.

### The hardware check

First run measures the phone and **advises** — it does not enable anything:

1. a cheap gate up front (total RAM and free memory), decided before any model is fetched;
2. a real load attempt on the first call that can **demote** the choice if it fails.

If the phone cannot carry Presence: Presence stays off, and one line says why and where to turn
it on.

### First run (#230) ships here

A fuller first run than a single check: the hardware check, the Alpha gate, the Presence
default, the explanation line — plus naming **the connection, the voice and the being** in the
same pass.

### Retired vocabulary

"Enhanced Realtime" disappears from the product. There is one selector, and it is the Alpha
gate (#222 completes here).

## 0.9.2 — Presence as the product

With the layer already shipping, 0.9.2 is where it stops being optional and starts being the
experience:

- asides and backchannels (#245, #243)
- resume after an interruption (#243)
- overlap classification (#242)
- the tuning bar (#217)
- a Presence transcript that is not empty (#223)
- **per-profile settings** — voice, model and look, the things still device-wide
- the invention measurement (#256)
- a fresh pass on the presence directive and its rails

## 1.0 — GA

When the owner deems the product ready to leave beta. No feature list, no date.

## What this map changes in the older documents

- **D24's** sentence *"Presence remains 0.9.2 and waits until 0.9.1 is genuinely good"* is
  **superseded** by D28. The layer ships in 0.9.1 behind the Alpha gate; the product work is
  0.9.2.
- **PLAN-0.9.1's** *"Not in 0.9.1"* list is superseded: Presence-as-product stays out, Presence
  as a layer is in, and first run plus the selector retirement move in.
- **ROADMAP's** 0.9.2 section keeps its content but not its claim that Presence arrives there.
- **Multi-profile** no longer sits under 1.0 — profiles ship in 0.9.1, per-profile settings in
  0.9.2.
