# BRIEF — 0.5.0.2: the animation facelift. YOU are the motion designer.

## Christopher's vision (verbatim, 2026-09-07)
"The AI agent exists within the forms and likes to play with them and use them to
express its state, its feelings, its ideas." ---- and ---- "I'd like to let the
designer have access and ability to all of those attributes to define and portray
workloads and states of different kinds" ---- and ---- "There should be some static
or relatively static animations and then others will need to be much more energetic."

So: the being is an INHABITANT, not a visualization. It plays with itself to express
what it's doing/feeling/thinking. The designer owns the whole attribute space, and
the static↔energetic spectrum is a tool, not a mandate.

## The current system (read it, don't trust this summary)
`android/app/src/main/java/com/hermesvox/AvatarView.kt` — 320 generative particles;
a `when(state)` shape function computes each particle's (x,y) from trig/hash (a
polar spiral for gather, seeded nodes for waiting, radial flinch for recoil, dispersed
aura for idle). Color lerps toward one per-frame hue; alpha eases. `MotionState.kt`
is the pure motion enum + `renderParams(shape,radius,speed,bright,theme,orbit)`.
`MainActivity` feeds state/tool/stall/amp signals into it.

## Why it reads AI-created (the tells you must kill)
1. **Dots on a curve, not a body.** Particles land on a parametric formula and ease
   toward it. No physical coupling (no swarm/attraction/flow-field), so it never forms
   a coherent being — just specks tracing a shape.
2. **Mathematically clean & even.** Perfectly smooth spiral/nodes. Organic light
   clusters, is brighter where denser, has a bright core + dark falloff. This is uniform.
3. **One flat hue, no depth.** Single color lerped globally, no radial-gradient falloff,
   no additive blending, no glow. It's a flat disc of dots, not luminous.
4. **Snap transitions.** State changes tween to a new curve. An alive being FLOWS from
   one posture to another through the in-between, like a flame changing shape.

## The design direction (your "inhabitant" made concrete)
Replace the parametric-curve-of-dots with an **emergent luminous swarm — the being
SHAPES its own light**:
- **Physical swarming** — particles attracted toward a moving attractor with per-particle
  jitter and a flow field, so motion is emergent, not traced. Density/brightness
  variation for free (clusters where the being's attention is).
- **Depth & light** — radial gradient with real falloff, additive/SCREEN blending so
  overlaps glow, a bright core, darker edges, a vignette. Reads as LIGHT, not a render.
- **A coherent silhouette** — the being holds a real body (flame / jellyfish / breathing
  orb) the particles form, and states TRANSFORM it, not outline it.
- **Flowing transitions** — state changes morph through the flow field (thinking→speaking
  is a gesture, recoil ripples through the swarm). Not a geometric cut.
- **Micro-detail** — per-particle size/brightness variance, a second harmonic, a subtle
  flicker so a still "listening" state is never a frozen blob.

## The palette ownership (Christopher #1)
You may use EVERY state/workload/motion-signal available (MotionState enum + the stall,
tool, recoil signals). Make each one itself — not a house style imposed on all. The
full mapping is yours to design. DON'T drop any state; every MotionState.Motion must
still render, and the MainActivity wiring (feed/applyMotion/driveMotion/setStall/
onTool/pulseTool) must keep working with your new rendering.

## The static↔energetic spectrum (Christopher #2)
Use the whole range. Some states are near-still (idle/listening/long drift) — a being
that's always moving is exhausting and reads as busyness. Others are explosive (recoil,
idea, tool-result). The static moments are what make the energetic ones land. Design the
spectrum deliberately per state. Restraint + timing = inhabited.

## KEEP-LIST
- MotionState.kt enum/signals UNCHANGED (it's the pure contract MainActivity feeds).
- The public API MainActivity calls: applyMotion, driveMotion, setStall, onTool,
  pulseTool, setStateLevel, setIdleTheme, setCycleThemes, preview. Keep their signatures
  and semantics. You change the RENDERING and the shape-function internals.
- The 0.5.0-A transcript reveal, silenceAll, fence, retirement, focus, BargeGate,
  ReplySettleRule, crash guard — zero-touch. This is AvatarView rendering only.

## Device realism
This is Android Canvas (onDraw), 320 particles, ~30fps tick, on a phone. Emergent-
swarm > parametric-curve costs more per frame; keep it frame-budget-friendly (O(n)
per frame, no per-particle allocations in the hot loop, reuse buffers). If you need
more particles, keep the count reasonable and prove the frame cost.

## Deliverable — one commit
'anim: emergent swarm — the being shapes its own light (0.5.0.2)'
Update MotionState.renderParams if you need NEW shape keys, but keep the enum intact.
Print:
  DESIGN_NOTES — the swarm/light/body design, how it's not-curve-dots
  STATE_MAP — every MotionState.Motion -> its shape/energy/color, and why
  PERF — per-frame cost, any allocation, count, how you proved it
  KEEP_LIST_VERIFY — the public API kept, KEEP-list zero-touch
  RISK — frame cost on device, what could look busy, how to tune
Build: cd android && JAVA_HOME=/home/c/jdk-17.0.12+7 /home/c/gradle-8.12.1/bin/gradle
:app:testDebugUnitTest -q --no-daemon exit 0 (+ assembleRelease must compile).
