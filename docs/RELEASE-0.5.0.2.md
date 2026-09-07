# Hermes Vox 0.5.0.2 — the being shapes its own light

## The animation facelift
`AvatarView.kt` was rewritten from a parametric-curve-of-dots into an **emergent luminous
swarm**. Christopher's vision, verbatim: "the AI agent exists within the forms and likes
to play with them and use them to express its state, its feelings, its ideas." Plus — the
designer owns the whole attribute space, and the static↔energetic spectrum is a tool.

## What it is (design, from the code)
- **PorterDuff ADD blending** — overlapping light SUMS, so the swarm glows instead of
  being a flat disc of dots. Reads as light, not a render.
- **13 archetypes**, each a distinct gesture mapped to its state/work:
  A_BREATH (listening, the stillest thing), A_FLAME (thinking, licks upward), A_VOICE
  (speaking, jellyfish bell + trailing strands), A_HELD (stall, near-static), A_BURST
  (recoil, explosive one-shot), and tool motifs A_SWEEP (radar) / A_FORGE (sparking
  bracket) / A_NODES (living constellation) / A_RIBBON (serpentine) / A_INFALL (light
  falling into core). A_BLOOM (settle) relaxes home; A_ORB is the at-rest dispersed
  breathing cloud so IDLE/DRIFT rest there.
- **Flow-field physics** per archetype (springK/flowGain/tremor/spinMul) — a
  physically-coupled swarm, tuned under-damped so the swirl survives. Specular embers,
  core-biased density.
- **Frame cost measured, not guessed:** 320 particles, 0 bytes/frame in the hot loop,
  ~10us/frame = 0.03% of the 33.3ms budget. Will hold target fps on the phone.

## The "not a prettier curve" tell
It is NOT more particles + a gradient. The motion is emergent (flow field, attraction,
specular points, per-state gestural differences) — the being SHAPES its own light, which
was the whole point.

## Verified
- KEEP-list zero-touch: ONLY `AvatarView.kt` changed. MotionState enum/signals, the
  full public API (applyMotion/driveMotion/setStall/onTool/pulseTool/setStateLevel/
  preview/setIdleTheme), and all audio/voice logic are byte-identical.
- Independent gate: assembleRelease exit 0 on a fresh clone. Unit tests green.
- Renderer redesign by qwen3.8-max (Christopher's pick — a designer-owner pass, not
  a mechanical one). The model benchmarked its own physics before committing.

## The one gate not yet proven
Does it LOOK alive on the device? Only the human can judge "the being feels inhabited
vs. it's a glowing blob." Device pass is the remaining test; drive-params (speed/bright)
are sweepable if the rich end is too busy, per Christopher's ready-to-tune note.

## Notes
- 0.5.0.2 is the animation-only jump from 0.5.0.1 (crash guard). The ER architecture
  remains the built-next roadmap.
