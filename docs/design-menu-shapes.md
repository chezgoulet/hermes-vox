# Shape-Expansion Design Menu — Part 2 (proposal for your pick)

**Framing:** each new archetype is one `when(arch)` case (a ~5-12 line closed-form
particle-position formula) in AvatarView.kt, plus a constant + routing. The cheap
model can write the geometry; YOU + I decide the *concept* so it's not a generic blob.
A good shape has a *reason* — it says something about the state it serves. A bad one
is "another cloud with a twist."

Below are candidate new archetypes, each with the **one-line concept** (what it means)
and **what state it serves**. I've graded them by how *non-trivial* the geometry is —
not by difficulty, but by how much of a "reason" each has. Pick the ones that feel
like the being; the rest we leave.

## The design principle
The being is a luminous swarm that *does* things. The best shapes make the work
visible: fire climbs, the jellyfish pulses with the voice, the constellation holds
during a wait. New shapes should add a NEW *kind* of statement, not a reshade of an
existing one.

## Candidates (each = new A_* constant + one `when(arch)` branch + one theme name)

### Tier 1 — strong "reason", low risk (recommended first)
1. **A_WAVE (theme "wave")** — a traveling ripple: a band that rolls across the body
   like water. *Serves:* STREAMING / a long narrative. Distinct from A_RIBBON (one
   serpentine line) — this is a whole-field traveling pulse. Reason: "the being is
   moving through a thought, one wave at a time."
2. **A_CORE (theme "core")** — a dense, tight bright center with a faint, pulsing
   halo — everything drawn inward to a point. *Serves:* FOCUS / deep concentration /
   a quiet secret. Opposite of the dispersed cloud; the being "gathered to a yes."
3. **A_PRISM (theme "prism")** — a few out-and-back spokes that fan and fold, like
   light refracting. *Serves:* TOOL "web"/search — "looking for the answer in many
   directions at once." Distinct from A_SWEEP (one rotating line) — this is multiple
   static rays that pulse.

### Tier 2 — characterful, slightly more geometry
4. **A_PENDULUM (theme "pendulum")** — a held figure that swings as one mass: a slow,
   deliberate rock. *Serves:* LISTENING (the being leaning in, considering). Contrast
   A_BREATH (still breath) with a *moving* contemplation.
5. **A_STORM (theme "storm")** — a turbulent, high-tremor churn that dissipates the
   cloud into ragged filaments. *Serves:* THINKING under pressure / a hard problem.
   The opposite of A_FLAME's upward intent — this is *chaos that works*.
6. **A_HALO (theme "halo")** — a clean, slow ring — not a full disc, a thin orbit of
   light. *Serves:* the being's *presence* between turns (idle). Motion is a long,
   quiet circulation. Minimal, serene, "I'm here."

### Tier 3 — concept-first, geometry follows (want these? design the concept with me)
7. **A_KNOT** — the being ties itself into a knot (interlaced). *Serves:* a *puzzle* /
   an unresolved thread. Distinct and memorable — but interlacing geometry is fiddly,
   so I'd want to design the particle coupling first.
8. **A_ASCEND** — particles rise and *escape* off the top, then loop back — a fount.
   *Serves:* RECOIL/a burst of inspiration (softer than A_BURST's flinch). The
   "idea has left the ground" moment.
9. **A_LATTICE** — particles snap to a grid, hold for a beat, then release.
   *Serves:* a *structured* tool (a table, a file being written). Reads as "the being
   is organising." Geometry is regular → easy, but risks feeling static; needs a
   breathe-in so it doesn't read as frozen.

## The one line that matters
**Every shape answers: "what is the being DOING that's worth showing?"** If a
candidate can't answer that, it's a reshade — skip it. If it can, we build it.

## My honest recommendation for the batch
Start with **Tier 1 (A_WAVE, A_CORE, A_PRISM)** — three new shapes, each with a clear
statement, each a clean additive branch. That alone doubles the "at-rest + tool"
vocabulary once the routing fix lands. Then, if they feel right, add Tier 2. Tier 3
only if one of those concepts genuinely grabs you — they're the higher-risk ones.

## How we'd build them (the two-model split, confirmed):
1. You pick the concepts (this doc).
2. I write the design intent + the exact `when(arch)` spec per chosen shape.
3. deepseek-v4-flash writes the geometry branches (mechanical, follows existing pattern).
4. I gate each against the "does it have a reason?" bar + verify it renders as intended
   (not a generic blob) before it ships.

## What I need from you
- Which concepts feel like the being? (pick any — 1, 2, all 3 of Tier 1, etc.)
- Any shape from your own head that's missing? (name the feeling it should evoke and
  the state it serves — I'll write the concept up.)
- Then I finalise the idle/cycle set (Part 1, Edits 1/2/4) to match.
