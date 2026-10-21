# Plan — 1.0, the general release

1.0 is the release that carries everything still plotted. It is not a declaration release with no
features, and it is not the end of the work — it is the point at which the app's feature set is
complete and stable enough to call general release, judged in the community's hands rather than
against a calendar (D21). It absorbs what was numbered 2.0, minus the two items that need a
community to want them first.

Milestone label: `milestone/1.0`. Roadmap: [`../ROADMAP.md`](../ROADMAP.md). The call for direction
is #268.

## What lands in 1.0

### Continuity (#231)

One conversation, moved between surfaces with the same session, the same memory and the same voice —
done twice, on real hardware, without repeating itself or losing a turn.

**What it depends on, and what is not ours.** The gateway's session-key semantics
(`X-Hermes-Session-Key` for a named conversation, `X-Hermes-Session-Id` for a thread), a resume
token that means *this conversation, this far*, and a per-surface renderer. Two of those are a
Hermes-side contract, not an app change.

**Two constraints recorded rather than discovered later.** Android Auto's Car App Library is
template-only — the being cannot be drawn there, so the car gets voice plus the platform's own list
and message templates, and that is a design constraint rather than a missing feature. And two
surfaces holding the same session will race unless one is clearly the writer; handoff is an
explicit transfer, not two clients guessing.

**The second surface is a decision to make first.** Desktop was ruled out as a client (D1), so the
realistic candidates are a web or PWA text surface, the car, or a satellite device. Only one of them
can draw the being.

### The household (#234, #46)

Speaker ID, per-person relationships, and identity rather than connection bookkeeping.

**This is a security property, not a nicety.** Today any detected voice triggers the agent; in a
multi-person app that stops being `sev/low`. Per-person identity also decides how the entity
addresses a family rather than a user, which is the difference between a tool and a presence in the
house.

### The store (#165)

F-Droid path B: sherpa-onnx and onnxruntime built from source in the recipe, `Binaries:` plus
`AllowedAPKSigningKeys` naming our signature, submission against the released tag, and a rebuild of
that tag reproducing our APK. Per D15 the gate is **submission and reproduction**, not acceptance —
review latency is external, and a gate that cannot close on the day it is judged is a wish.

A mismatch — F-Droid skipping a version because their rebuild did not match ours — falsifies our
reproducibility claim and is treated as a severe bug, not their problem.

### The gates, re-run

Reproducible build, live gateway suite, behaviour matrix on both devices, performance baseline, and
no open severe bug without a written reason. These are standing requirements; 1.0 is where they are
all held at once.

## What "complete and stable" means for 1.0

- **Complete** — every item marked for 1.0 in the roadmap is either shipped or explicitly moved, and
  nothing user-facing is described as coming next.
- **Stable** — a release cycle passes in the field with no severe regression that reaches users; the
  bug list is triaged rather than carried.
- **Wanted** — the community's answers in #268 are reflected in what was prioritised. If the
  direction and the answers disagree, the answers win, and the disagreement is written down.

## Not in 1.0, and where each went

- **Entity calls (#232)** and **remote control of the interface (#233)** — unnumbered, community-led.
  The armed session is not being built ahead of a demonstrated want.
- **Wake word (#45)** — unnumbered; D6 step one needs training compute the House does not have.
- **A cloud voice mode, a platform SDK, an offline-only mode** — not this product.
