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
**Reason:** a creature that *propels itself by its own limbs*. A bell-head carried by
tentacles that undulate AND push — it MOVES across the screen, not stationary like the
jellyfish. The most "inhabited" shape: it travels, it's not a fixed figure.
**Serves:** SPEAKING / a state that should feel alive and on the move. (Christopher
confirmed: keep BOTH the jellyfish A_VOICE and this octopus as siblings — the jellyfish
is anchored, the octopus roams.)
**Geometry:** a head (dense bell) + 5-8 tentacles, each a chain of points carrying a
traveling sine down its length. The swarm gets a persistent BODY OFFSET (a slow
perlin/curved drift across the frame), and the lead tentacles' flex is coupled to the
heading so propulsion reads visually: the arms push, the body glides. Higher — the
transport vector is new (no other shape moves its centre).
**Difficulty:** MEDIUM-HIGH. **Overlap:** A_VOICE is the anchored jellyfish; this is the
moving, self-propelled octopus. Sibling pair, both kept.

## 7. A_RADAR · theme "radar" (the radar map of storms — genuinely new)
**Reason:** a WEATHER-RADAR map — concentric range rings, a rotating sweep beam, and
irregular storm cells (nebulous bright blobs) that pulse and drift. The being *scans a
situation*; the storm cells are the things it's watching. Reads as "assessing the field,
reading conditions" — unlike A_SWEEP (one rotating line = a simple radar) this is the
FULL weather-radar panel (confidence rings + cells). Christopher's clarifications:
*genuinely a new shape*, not an A_SWEEP variant.
**Serves:** THINKING / TOOL-scan / a moment of assessment ("holding the field and reading
it").
**Geometry:** (a) a few concentric range rings (static or very slow), (b) ONE rotating
sweep beam (like A_SWEEP but explicit), (c) 2-4 storm cells = gaussian blobs that pulse
and slowly translate, each with a bright core. Three sub-systems but each simple; reuses
the gaussian blob + sweep math that already exists.
**Difficulty:** MEDIUM-HIGH. **Overlap:** none — A_SWEEP is a bare sweep, this is a
weather-radar panel with cells.

## 8. A_BORE (tide) · theme "water"
**Reason:** a liquid surface — soft rolling ripples across the whole field, like water.
Calm, continuous, fluid. The being *flows*.
**Serves:** STREAMING / a long narrative / a peaceful interlude. (A_WAVE in my Tier 1 was
a traveling band; this is the *whole surface* rippling.)
**Geometry:** `fy = cy + wave-field(sin, cos)` superposition, two counter-moving trains.
Easy, very smooth.
**Difficulty:** LOW-MEDIUM. **Overlap:** overlaps A_WAVE (Tier 1) — A_WAVE = a single
traveling band, this = an all-over liquid field. Keep both as siblings (different feel).

---

## Consolidated vocabulary — DECISIONS RESOLVED (per Christopher)
**Existing (13):** orb, breath, flame, gyre, voice, held, burst, sweep, forge, nodes,
ribbon, infall, bloom.
**My added (design menu):** wave* (→ water sibling), core, prism, pendulum, storm*,
halo, knot, ascend, lattice.
**Christopher's added (this doc):** waveform, arc, nucleus, invader, eye, octopus,
radar (was "storm"), water*.

**Christopher's decisions (locked):**
1. **Build all of them** — the full set, both mine and his.
2. **Jellyfish A_VOICE AND octopus A_TAKU both kept as siblings** — jellyfish anchored,
   octopus self-propels across the screen via its tentacles (arms push, body glides).
3. **"Storm" = a WEATHER-RADAR MAP** (A_RADAR): concentric range rings + rotating sweep
   beam + pulsing storm cells — a genuinely new shape, not an A_SWEEP variant.

**Overlaps resolved:** water (A_BORE) + wave (A_WAVE) are siblings (all-over liquid field
vs traveling band). "Cyclone" is dropped as a new archetype — the radar-map storm
supersedes the "intensified vortex" idea.

That's a **~19-22 shape vocabulary** if we take most of them — genuinely "doubled,
heading to tripled." Each is a single `when(arch)` branch; the cost is design review per
shape (does it have a reason? does it render as intended, not generic?), not code
complexity.

## Build order (the honest sequencing — code isn't the bottleneck, design review is)
The routing fix (Part 1) lands FIRST — it's what makes the existing 13 actually show up,
so the new shapes get a working baseline to slot into. Then the new shapes in waves (not
all at once — ~20 shapes reviewed haphazardly is how half end up samey). Wave 1: the
strongest/cleanest new ones (waveform, arc, nucleus, eye, water, radar, octopus).
Invader stays last and opt-in (it breaks the swarm aesthetic — a delightful surprise, not
a work state).

## Decisions — LOCKED (Christopher confirmed)
1. Build ALL of them (the full set).
2. Jellyfish A_VOICE + octopus A_TAKU both kept (jellyfish anchored, octopus self-propels
   via tentacles).
3. "Storm" = A_RADAR (weather-radar map: range rings + sweep beam + storm cells).
4. Water (A_BORE) + wave (A_WAVE) as siblings. "Cyclone" dropped as a new archetype.
5. Invader kept, opt-in at idle only.

**Next step (Torc):** lock the idle/cycle set (Part 1 Edits 1/2/4), then write a
per-shape `when(arch)` spec + geometry build for every confirmed shape, shipped in waves
behind the routing-fix baseline.
