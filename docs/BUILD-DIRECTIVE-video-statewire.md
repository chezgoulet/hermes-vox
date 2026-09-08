# BUILD-DIRECTIVE: video state-wiring (Wave 1 shapes fire in their named states + configurable)

REPO (sandbox): `/home/c/vox-wave1` — branch `video-wave1`, HEAD `6053d55` (the 7 Wave 1
shapes already landed + gated). Read `docs/design-menu-shapes.md` + the existing
`docs/BUILD-DIRECTIVE-video-wave1.md`. The 7 shapes are DONE — you add the STATE WIRING +
SETTINGS on top, do NOT re-do the shapes.

## The goal
Right now the 7 new shapes are idle-choosable/cyclable. Make them also fire in their NAMED
states, AND expose per-state shape config in the Settings UI so the user controls which
archetype represents each active state. This is configurable, not a silent replace: the
existing shapes remain available via the picker.

## KEEP-LIST (sacred)
- The 20 A_* constants + their when(arch) geometry byte-for-byte. Do NOT change any shape's
  geometry. (A_WAVEform=13 .. A_TAKU=19, appended after A_BLOOM=12 — leave as-is.)
- The P physics body, MotionState machine, feed() signal dispatch. Do NOT change those.
- The existing settings dials (particles_theme, particles_cycle, visual_category,
  visual_cycle_all, energy, glow). Do NOT break them.
- The zero-alloc render loop.

## Edit 1 — AvatarView.kt: make state→archetype read a configurable pref
The state dispatch is `resolveArch()` (~line 736). Currently it HARDCODES each active state
to one archetype ("speaking" -> A_VOICE, "listening" -> A_BREATH, etc.). Change it so the
state archetypes come from user prefs, DEFAULTING to the new semantic fits so the user sees
soundwave/eye/radar in context out-of-the-box. The prefs live in the SAME prefs file AvatarView
already reads (VisualStyle.PREFS_NAME = "hv") — read them once/cached, not per frame.

Proposed default state→archetype mapping (user-overridable via the picker):
- "speaking" -> A_WAVEform   (soundwave — the audio trace during a voice reply; jellyfish A_VOICE still selectable)
- "listening" -> A_SEEKER    (eye — "I see you" while listening; breath A_BREATH still selectable)
- "thinking" + tool "web"/"search" -> A_RADAR  (scan/assess; sweep A_SWEEP still selectable)
- Keep all other states as they are (recoil->A_BURST, waiting->A_HELD, gather->A_FLAME,
  streaming->A_RIBBON, thinking+other tools->existing, settle/bloom->A_BLOOM, else->A_ORB).
Add a small helper `stateArch(state, tool): Int?` that reads the pref (e.g.
"visual_shape_speaking", "visual_shape_listening", "visual_shape_thinking") and falls back
to the table above when the pref is unset/empty. Do NOT allocate per frame; cache the pref
values at the same place idleTheme is cached.

## Edit 2 — SettingsActivity.kt: expose per-state shape pickers in the Visuals section
In `bindVisuals()` (after the visual_category rows), add THREE picker rows (reuse the existing
`micChoiceString("...", labels, tokens, key, valId)` pattern + the row layout idiom):
- "Speaking shape"  -> "visual_shape_speaking"  (default: "soundwave")
- "Listening shape" -> "visual_shape_listening" (default: "eye")
- "Thinking shape"  -> "visual_shape_thinking"  (default: "radar")
Each lists the FULL vocab by human label + token (all 20: aura/iris/vortex/waveform/scan/
constellation/bracket/flame/ribbon/infall/bloom/soundwave/arc/nucleus/eye/water/radar/octopus)
so the user can pick jellyfish, breath, sweep, octopus, etc. for any state. Store the token;
render the label. The default (when pref unset) matches Edit 1's default so both agree.

You need the SAME label/val arrays for all 20 shapes — make a single shared list used by both
the idle-theme picker and these three state pickers so the vocabulary isn't duplicated (or at
least keep them consistent). If you judge a cleaner approach (e.g. a single "state shape" map
with one picker), do that AND explain why.

## Verify after EACH edit (anchor rule)
cd /home/c/vox-wave1/android && /home/c/gradle-8.12.1/bin/gradle compileReleaseKotlin --no-daemon
MUST succeed before the next edit. FINAL: assembleRelease testReleaseUnitTest -> BUILD SUCCESSFUL.
Do NOT bump versionName/versionCode (release time).

## IMPROVEMENTS MADE (required at end)
Every file:line changed + any deviation from this spec + why (e.g. a cleaner settings layout, a
better default, a shared vocab array). If wiring soundwave->speaking as the DEFAULT felt wrong,
say so and propose the alternative — but you MUST still make it configurable.

## Do NOT
- Do NOT change any when(arch) geometry.
- Do NOT push (the branch is pushed; work locally, I gate + push).
- Do NOT run ./... go build.
- Do NOT delete or rename any existing A_* constant or theme.
