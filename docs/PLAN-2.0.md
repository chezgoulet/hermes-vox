# Plan — 2.0, "the presence that follows you"

> **Superseded 2026-10-21 by D21.** The 2.0 number is retired. Continuity and the household move
> into 1.0 ([`PLAN-1.0.md`](PLAN-1.0.md)); "it reaches you" and "it shows you" become unnumbered,
> community-led items in [`../ROADMAP.md`](../ROADMAP.md). This page is kept for the constraints it
> records, not as a plan.

1.x makes the entity good at being in one place: it hears you, it holds a long gap, it
answers. 2.0 is the series where it stops being an app you open and becomes one you can
reach — and that can reach you — across the surfaces you already own.

**Why one spine and not a feature list.** The record already held two unrelated 2.0 ideas:
the agent controlling the application through an MCP surface, and notification routing with
a user-supplied push endpoint (D12). A series needs a single claim, so those become features
of this one rather than rivals to it.

**What 2.0 is not.** Not a cloud service, not a platform or SDK for other apps, not an
offline mode, not a rewrite. Each of those would stop this being this project.

---

## 2.0.0 — the declaration, and its gates

2.0.0 is a declaration like 1.0 was: no new features on the day, only the proof that the
series' claim is true.

- [ ] **One conversation, moved.** A dialogue begun on the phone continues on a second
      surface with the same session, the same memory and the same entity voice — done
      twice, on real hardware, without repeating itself or losing a turn.
- [ ] **It can reach an armed user** with no infrastructure of ours: the user's own
      endpoint, their own ceiling, and a visible way to stop it.
- [ ] **The expression vocabulary is closed and testable**: the being's drawn output comes
      from a finite scene grammar, not free drawing, so it can be described to a screen
      reader and replayed deterministically.
- [ ] No open severe bug; performance within the measured baseline; F-Droid rail live.

## 2.x — the milestones

### Continuity

The declaration's own work, and the hardest. A conversation moves between phone, desktop
(or the web surface), the car, and a satellite without the entity forgetting where it is.

Depends on things outside this app: the gateway's session-key semantics
(`X-Hermes-Session-Key` for a named conversation, `X-Hermes-Session-Id` for a thread), a
resume token that means "this conversation, this far", and a per-surface renderer.

**Two honest constraints.** Android Auto's Car App Library is template-only — the being
cannot be drawn there, so the car gets voice plus the platform's own list and message
templates, and that is a design constraint rather than a missing feature. And two surfaces
holding the same session will race unless one is clearly the writer; handoff must be an
explicit transfer, not two clients guessing.

### It reaches you

D12's 2.x half: per-event-class routing (voice call / text / none / delegated to another
surface) together with the user-supplied push endpoint, with a per-class rate ceiling.
This is what turns the armed session from 1.x into a proactive presence.

### It shows you

The MCP surface inside the app and a blessed skill, so the agent can *draw* meaning — a
shape, a label, an arrow, a timeline — from a closed vocabulary. Per-tool grants, a visible
"the agent has control" state, an audit log, and a dead-man switch on screen-lock or
call-end. The vocabulary is closed precisely so a language model is never asked to be a
renderer.

### The household

Speaker ID, per-person relationships, and multi-profile as **identity** rather than
connection bookkeeping. This is where #46 stops being `sev/low`: any detected voice
currently triggers the agent, and in a proactive, multi-person 2.x that is a security
property, not a nicety. It also decides how the entity addresses a family rather than a
user.

---

## What has to be true before any of this is buildable

- **1.x has settled the listening half.** Continuity of a conversation presupposes a
  conversation that ends correctly.
- **The armed session works in the field** (D12's 1.x half), because proactivity is its
  extension.
- **A second surface exists.** Desktop was ruled out of the *client* (D1), so the realistic
  second surface is a web or PWA **text** surface — not voice, per the standing constraint —
  or the car. That choice is load-bearing and should be made first.

## Open questions this plan does not answer

- **Which second surface** carries continuity first: the car, a web text surface, or a
  satellite device (the Echo Dot route). Each has a different constraint, and only one of
  them can draw the being.
- **Whether the entity may initiate** unprompted, or only on an arm the user set. The
  second is safer and is what the current design assumes.
- **How much continuity the gateway can support** without a change to its session model.
