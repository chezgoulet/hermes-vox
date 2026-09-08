# Hermes Vox 0.5.5 — the video release (shape routing + Wave 1 + state-wiring)

The being's visual vocabulary grows from 13 to 20 archetypes, the routing that was
starving the at-rest scene is fixed, and the new shapes now fire in their named states.
This is the "video point release" — the one that makes the being visibly diverse and
semantically alive.

## Routing fix (what made the existing shapes actually SHOW UP)
The at-rest scene + "thinking" fallback were collapsing to a narrow set (ORB cloud + gyre
spiral) even though 13 archetypes existed. Fixed the 4 routing surfaces so the characterful
shapes render:
- themeArch: every idle theme maps to a real archetype (flame/ribbon/infall now reachable at rest).
- cycleList: grown to the curated idle vocab.
- mapTool: explicit web/agent/vision motifs (fewer thinks fall to the spiral).
- settings picker: flame/ribbon/infall/bracket/bloom user-choosable at idle.

## Wave 1 — seven new shapes (each its own commit, KEEP-list verified)
- **A_WAVEform "soundwave"** — a literal audio trace (sibling to the existing "waveform" jellyfish).
- **A_ARC** — a restruck lightning crack across the idle field.
- **A_NUCLEUS** — a dense core + 3 tilted orbiting rings.
- **A_SEEKER "eye"** — a darting, blinking pupil.
- **A_BORE "water"** — an all-over liquid ripple field.
- **A_RADAR** — a weather-radar panel: range rings + rotating sweep + storm cells.
- **A_TAKU "octopus"** — a limbed, self-propelling being with a WANDER→FIXATE→MOVE-ON
  transport state machine + cooldown (Christopher's exact spec: it swims, occasionally
  fixates on something interesting, then moves on — tentacles coupled to the heading).

## State-wiring (new shapes fire in their named states, configurable)
soundwave→speaking, eye→listening, radar→thinking are the DEFAULTS, plus per-state shape
pickers in Settings (Speaking / Listening / Thinking shape) so the user configures which
archetype represents each active state. The existing shapes (jellyfish/breath/sweep) remain
selectable — configurable, not a silent replace.

## KEEP-list — verified
All 20 A_* constants + when(arch) geometry byte-for-byte. Only the 3 hardcoded
state↔shape mappings were replaced by the configurable stateArch() call (no geometry change).
The P physics body, MotionState machine, existing settings dials, and zero-alloc render loop
are intact. Gated on a fresh clone: WAVE1-FULL-GATE-EXIT=0, BUILD SUCCESSFUL, go test ./voice/...
ok.

## Notes
- Version 0.5.5 (versionCode 94). Built from merged main.
- Wave 2 (invader + the design-menu remainder) is a follow-up point release.
- 0.6 release gates tracked as GitHub issues #91-100.
