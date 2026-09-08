# Shape-Expansion — Christopher's Shapes (addendum to design menu)

Nine new concepts, each with the one-line "reason" (what the being is DOING that's
worth showing), the state it serves, the geometry approach, and an honest difficulty
grade. I've wired them into the same vocabulary as the existing 13 so they slot in as
new `A_*` constants + `when(arch)` branches. Overlaps with existing shapes are called
out.

---

## 1. A_WAVEform · theme "waveform"
**Reason:** the being IS voice — a literal audio waveform, the whole swarm drawn as a
sound wave that dances with the real amplitude. On-theme, instantly readable.
**Serves:** SPEAKING. (A_VOICE is the jellyfish; this is the flat waveform — a second,
more literal "I am speaking" reading.)
**Geometry:** a horizontal wave — `fy = cy + amp * sin(kx + ph)` across the particle
field, amplitude driven by the live RMS. Easy, clean.
**Difficulty:** LOW. **Overlap:** none — A_VOICE is organic, this is geometric.

## 2. A_ARC · theme "arc"
**Reason:** electricity leaps — jagged cracks of light connecting two points, a
*connection being made*. The spark of an idea or a tool firing.
**Serves:** THINKING / TOOL-call (the "hook-up" between request and answer).
**Geometry:** random-walk jagged paths between two anchor points, redrawn a few times a
second with slight variation. Moderate — needs a zigzag interpolation helper.
**Difficulty:** MEDIUM. **Overlap:** none — distinct from A_FLAME (upward), A_GYRE (spiral).

## 3. A_NUCLEUS · theme "nucleus"
**Reason:** a dense bright core with electrons buzzing in tilted orbits — the being as
a *nucleus of thought*, ordered and alive. The opposite of the dispersed cloud — a
tightly-held center with motion around it.
**Serves:** FOCUS / deep concentration / a quiet "yes."
**Geometry:** a bright core + particles on a few co-planar orbit rings (tilted, so it
reads 3-D), each ring rotating. Moderate — orbit math with per-ring inclination.
**Difficulty:** MEDIUM. **Overlap:** none — A_ORB is dispersed, this is a structured atom.

## 4. A_INVADER · theme "invader"
**Reason:** playful, retro, unmistakable — a grid of blocky sprites descending, one
little ship dodging. The being is *playing* / being whimsical. Rare in a "voice agent"
context; delightfully unexpected.
**Serves:** IDLE-playful, or a light/fun tool state. (Not a work state.)
**Geometry:** a loaded pixel-grid pattern (a few Invader rows) rendered from particle
slots, with a slow descent + a single ship that strafes. High — literal sprite
recurrence, but no physics coupling needed (it IS the grid).
**Difficulty:** MEDIUM-HIGH. **Overlap:** none. Caveat: it's `A_*` geometry that doesn't
flow like the swarm; it's a deliberate retro break. Only right if it fits the being's
mood — the being is serious, so an Invader is a *surprise* move. Flag for your call.

## 5. A_SEEKER · theme "eye" (the eye looking around)
**Reason:** the being is AWARE — it looks. A bright iris/pupil that darts, tracks, and
blinks. "I see you." Alive.
**Serves:** LISTENING / idle presence. (A_BREATH is receptive; this is attentive.)
**Geometry:** a sclera formed by the dispersed field + a brighter pupil that lerps to
a target point (occasionally darting), with periodic blinks (a horizontal collapse).
Medium — needs a track/dart/blink state machine, but the shape is simple.
**Difficulty:** MEDIUM. **Overlap:** none.

## 6. A_TAKU · theme "octopus"
**Reason:** a creature that *undulates* — a bell-head with tentacles that wave
organically. Richer/more literal than the jellyfish A_VOICE: visible individual arms.
**Serves:** SPEAKING / a complex state that should feel alive and many-armed.
**Geometry:** a head (dense bell) + 5-8 tentacles each a chain of points driven by a
traveling sine down its length. Higher — more point-coupling than other shapes.
**Difficulty:** MEDIUM-HIGH. **Overlap:** overlaps A_VOICE thematically (both "sea
creature speaking"); this is the *arm-y* version. Pick ONE of jellyfish/octopus unless
you want both as siblings.

## 7. A_STORM · theme "storm"
**Reason:** weather — falling rain streaks with periodic lightning. The being *works
under pressure*; lightning is the flash of insight. Dramatic and rare.
**Serves:** THINKING hard / a heavy, consequential moment.
**Geometry:** vertical falling streaks (high `biasY` + speed) + sparse lightning (a
jagged arc-draw, reused from A_ARC) flashing on a timer. MEDIUM-HIGH (two sub-systems).
**Difficulty:** MEDIUM-HIGH. **Overlap:** none.

## 8. A_CYCLONE · theme "cyclone"
**Reason:** a tight, fast, violent spiral — the vortex *intensified*.
**Geometry:** it IS A_GYRE, turned up (higher spin, wider arm, more contraction).
**Difficulty:** LOW. **Overlap:** **this is the existing A_GYRE/VORTEX.** Before we make
it a new shape, decide: do you want a *distinct* tighter cyclone, or just an energy knob
on the existing vortex? Recommend: make it a **visual-energy/scale variant of A_GYRE**,
not a 14th archetype — cleaner, and the energy dial already exists.

## 9. A_BORE (tide) · theme "water"
**Reason:** a liquid surface — soft rolling ripples across the whole field, like water.
Calm, continuous, fluid. The being *flows*.
**Serves:** STREAMING / a long narrative / a peaceful interlude. (A_WAVE in my Tier 1 was
a traveling band; this is the *whole surface* rippling.)
**Geometry:** `fy = cy + wave-field(sin, cos)` superposition, two counter-moving trains.
Easy, very smooth.
**Difficulty:** LOW-MEDIUM. **Overlap:** overlaps A_WAVE (Tier 1) — A_WAVE = a single
traveling band, this = an all-over liquid field. Keep both as siblings (different feel).

---

## Consolidated vocabulary the being could grow to
**Existing (13):** orb, breath, flame, gyre, voice, held, burst, sweep, forge, nodes,
ribbon, infall, bloom.
**My added (design menu):** wave* (→ water sibling), core, prism, pendulum, storm*,
halo, knot, ascend, lattice.
**Your added (this doc):** waveform, arc, nucleus, invader, eye, octopus, storm*,
cyclone*(→gyre variant), water*.
*(overlap noted — resolve as siblings or variants before build)*

That's a **~19-22 shape vocabulary** if we take most of them — genuinely "doubled,
heading to tripled." Each is a single `when(arch)` branch; the cost is design review per
shape (does it have a reason? does it render as intended, not generic?), not code
complexity.

## My honest recommendation
- **Certain keeps (strong, clean, on-theme):** waveform (speaking — could even replace/
  complement the jellyfish), arc (tool connection), nucleus (focus), eye (awareness),
  water (streaming), storm (hard thinking).
- **Consider splitting:** octopus vs jellyfish (pick one as "the sea creature"), cyclone
  vs gyre (make it an energy variant, not a new shape).
- **Your one risky-but-delightful call:** Invader. It breaks the swarm aesthetic
  entirely — I'd only do it if you *want* a delightful surprise in the roster, and I'd
  make it an opt-in idle shape, not something that fires mid-work.

## What I need from you
1. Confirm which of YOUR nine to build (all? drop any? make cyclone a gyre-variant?).
2. For octopus vs jellyfish and water vs wave — siblings or one only?
3. Invader — want it, or too much of a break?
4. Then I lock the idle/cycle set (Part 1 Edits 1/2/4) and write the per-shape
   `when(arch)` specs for the chosen set.
