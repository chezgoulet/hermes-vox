# BRIEF — 0.5.0.2: the animation facelift. YOU are the motion designer.

## SCOPE WARNING — read this first (this is the #1 trap)
You are working in a repo that is a **Go module at the root** (`go.mod`, root
`AGENTS.md` about the Go voice stack, a top-level `app` WASM binary, MANY `build_*.log`,
root + `docs/handoff.md`). YOUR TASK IS ANDROID-ONLY. Do NOT read or touch any of: the Go
module, `cmd/`, `voice/`, `journal/`, `app` (the wasm binary), `handoff.md`, any
`build_*.log`, or the Go `go.mod`. The ONLY file you edit is:

    android/app/src/main/java/com/hermesvox/AvatarView.kt

and if the design needs a tiny supporting change, `android/app/src/main/java/com/hermesvox/MotionState.kt`
(enum/signals unchanged — you may add shape keys, never remove/reorder). Every other
file is off-limits. The root AGENTS.md describes the Go project, NOT this task — ignore it.

## The current renderer (only this file matters)
`android/app/src/main/java/com/hermesvox/AvatarView.kt` — 320 generative particles; a
`when(state)` shape function computes each (x,y) from trig/hash. Color lerps toward one
per-frame hue; alpha eases. `MainActivity` (under the same `android/app/src/main/java/...`
path) feeds state/tool/stall/amp signals. Read the file and its callers under that one
`android/app/src/main/java/com/hermesvox/` path — and ONLY there.

## Christopher's vision (verbatim, 2026-09-07)
"The AI agent exists within the forms and likes to play with them and use them to
express its state, its feelings, its ideas." ---- "I'd like to let the designer have
access and ability to all of those attributes to define and portray workloads and states
of different kinds" ---- "There should be some static or relatively static animations and
then others will need to be much more energetic."

The being is an INHABITANT, not a visualization. It plays with itself to express what
it's doing/feeling/thinking. The designer owns the whole attribute space; the
static↔energetic spectrum is a tool, not a mandate.

## Why the current one reads AI-created (the tells to kill)
1. **Dots on a curve, not a body** — particles land on a formula and ease toward it; no
   physical coupling (no swarm/attraction/flow-field), so never a coherent being.
2. **Mathematically clean & even** — perfectly smooth spiral/nodes; organic light
   clusters, is brighter where denser, has a bright core + dark falloff. This is uniform.
3. **One flat hue, no depth** — single color lerped globally, no radial-gradient falloff,
   no additive blending, no glow. A flat disc of dots, not luminous.
4. **Snap transitions** — state changes tween to a new curve; an alive being FLOWS
   through the in-between like a flame changing shape.

## The design direction — an emergent luminous swarm, the being SHAPES its own light
- **Physical swarming** — particles attracted toward a moving attractor with per-particle
  jitter + a flow field; motion is emergent, not traced. Density/brightness for free.
- **Depth & light** — radial gradient with real falloff, additive/SCREEN blending so
  overlaps glow, a bright core, darker edges, a vignette. Reads as LIGHT, not a render.
- **A coherent silhouette** — a real body (flame / jellyfish / breathing orb) the particles
  form; states TRANSFORM it, not outline it.
- **Flowing transitions** — state changes morph through the flow field. Not a geometric cut.
- **Micro-detail** — per-particle size/brightness variance, a second harmonic, a subtle
  flicker so a still "listening" state is never a frozen blob.

## Palette ownership (Christopher #1)
Every state/workload/motion-signal is yours (MotMotionState enum + stall/tool/recoil).
Make each one itself, not a house style. DON'T drop any state; every MotionState.Motion
must still render. The MainActivity wiring (feed/applyMotion/driveMotion/setStall/onTool/
pulseTool) MUST keep working with your new rendering.

## Static↔energetic spectrum (Christopher #2)
Use the whole range. Some states near-still (idle/listening/long drift) — a being that's
always moving is exhausting and reads as busyness. Others explosive (recoil, idea,
tool-result). The static moments are what make the energetic ones land. Deliberate per
state. Restraint + timing = inhabited.

## KEEP-LIST (zero-touch)
- MotionState.kt enum/signals UNCHANGED (pure contract MainActivity feeds).
- Public API MainActivity calls: applyMotion, driveMotion, setStall, onTool, pulseTool,
  setStateLevel, setIdleTheme, setCycleThemes, preview — keep signatures + semantics.
  You change RENDERING + shape-function internals.
- 0.5.0-A transcript reveal, silenceAll, fence, retirement, focus, BargeGate,
  ReplySettleRule, crash guard — zero-touch.
- This is AvatarView rendering only.

## Device realism
Android Canvas (onDraw), 320 particles, ~30fps, on a phone. Keep O(n) per frame, no
per-particle allocations in the hot loop, reuse buffers. Prove the frame cost.

## Deliverable — one commit
Commit: 'anim: emergent swarm — the being shapes its own light (0.5.0.2)'
It must ACTUALLY EDIT AvatarView.kt (a real diff, not a no-op). Then:
  cd android && JAVA_HOME=/home/c/jdk-17.0.12+7 /home/c/gradle-8.12.1/bin/gradle
  :app:testDebugUnitTest -q --no-daemon  → exit 0 (and assembleRelease must compile)
Print:
  DESIGN_NOTES — the swarm/light/body design, how it's not-curve-dots
  STATE_MAP — every MotionState.Motion -> its shape/energy/color, and why
  PERF — per-frame cost, allocations, count, how you proved it
  KEEP_LIST_VERIFY — the public API kept, KEEP-list zero-touch
  RISK — frame cost on device, what could look busy, how to tune
