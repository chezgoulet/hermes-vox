# SPEC — 0.5.2-QWEN: massively expand the visualizations; expose every style as a
# Settings category; add "cycle all animations across all categories"

## Christopher's direction (verbatim, 2026-09-07)
"Massively expanding the visualizations. Make sure that Qwen exposes all of the styles
as categories inside the settings UI as well as an option for all of the animations
across all of the categories." Designer owns the palette; the being "exists within the
forms and likes to play with them." Restraint + timing = inhabited.

## Current ground truth (0.5.1, what EXISTS now)
- `VisualStyle.kt` — category table. `STYLES` array (:112) of {token, label, colors+},
  `TOKENS`/`LABELS` derived (:143-145). `DEFAULT="lumen"` (:60) — the 0.5.0.2/0.5.0.3
  look. `KEY_CATEGORY`, `KEY_ENERGY`, `KEY_GLOW` prefs; ENERGY/GLOW seek bars bound in
  SettingsActivity (:542-548).
- `SettingsActivity.kt:528-550` — a "Visuals" group with a CATEGORY picker (row +
  set_visual_category_val dropdown using TOKENS/LABELS), plus energy/glow sliders.
- `AvatarView.kt` — the 13-archetype swarm (A_ORB..A_BLOOM), PorterDuff ADD, flow-field,
  specular embers. This is the BODY; a category re-colours/re-shapes it in EVERY state,
  orthogonal to the idle shape (0.5.1's documented design).
- There is currently NO "cycle all categories" option.

## The two asks
### A1 — MASSIVELY expand the visualization
The designer owns this. The spec sets the bar, not the taste:
- Add MANY more visual categories (the current set is small — expand it substantially,
  e.g. a dozen+ distinct painterly families: Ember, Nebula, Ripple, Aura, Lumen,
  Aurora, Ink, Prism, Raven, Tide, Sculpt, Static... you author the palette). Each must
  be a DISTINCT character — different color logic, different flow/energy signature,
  different feel — not a hue-shift of the same thing. Christopher's test: toggling
  between two categories mid-call must feel like a DIFFERENT being, not a recolour.
- Categories must transform the whole being (every state: listening/thinking/speaking/
  stall/recoil/tool modes), orthogonally to the idle theme — preserve the 0.5.1 design
  contract.
- Enrich the swarm where tasteful: more per-state gesture differentiation, richer
  particle behavior per category, but KEEP the frame-cost guardrail (no per-particle
  allocation, additive/ADD blending is cheap). Re-measure; if a category is heavy, gate
  it (default = the light 0.5.0.2/0.5.0.3 lumen look).
- Optionally: more archetypes/gestures if that advances "massively expanded" — but do
  NOT drop any existing MotionState.Motion (every state must still render).

### A2 — expose ALL styles as categories in Settings UI
- The Settings "Visuals" category picker must list EVERY style/category (all the new
  ones + the existing), driven by VisualStyle.TOKENS/LABELS so it never drifts (the picker
  is already data-driven via those arrays; ensure every entry in STYLES is exposed and
  selectable). No hard-coded truncated list.

### A3 — an option for all the animations across all the categories
- Add a "cycle all categories" option in Settings (bool pref, e.g. `visual_cycle_all`,
  default false). When ON, the being cycles through ALL categories over time (or per
  state-change / per turn — designer's choice), so the user sees the whole breadth
  animating. Must be clean: no jarring snap on transition (flow through it), must not
  thrash the frame budget, must be settable off to return to a fixed category.

## KEEP-LIST (zero-touch)
MotionState.kt enum/signals (UNCHANGED — pure contract), the public AvatarView API
MainActivity calls (applyMotion/driveMotion/setStall/onTool/pulseTool/setStateLevel/
preview/setIdleTheme/setCycleThemes), silenceAll, stream fence, retirement, focus,
BargeGate/EscapeRule, ReplySettleRule, crash guard, static-transcript reveal boundary,
screen-alive, the conn-test probe, CrawlView, SherpaTts, VoiceController turn engine.
ONLY AvatarView rendering internals + VisualStyle + the Settings visual surface
(SettingsActivity + activity_settings.xml) change.

## Frame-cost gate (REQUIRED, prove it)
Re-measure the swarm under the new categories. State the per-frame cost (µs/frame,
% of 33.3ms budget) and confirm 0 alloc/frame in the hot loop. If a category is heavier,
gate it behind a setting (default light). Christopher's frame-budget standard stands.

## Gate
cd android && JAVA_HOME=/home/c/jdk-17.0.12+7 /home/c/gradle-8.12.1/bin/gradle
:app:testDebugUnitTest -q --no-daemon  (exit 0) AND assembleRelease compiles.

## Deliverable — one commit
'anim: massively expanded visual categories + cycle-all option — 0.5.2-qwen'
versionCode 91 / versionName 0.5.2 + docs/RELEASE-0.5.2-qwen.md
Print: A1_PALETTE (list the categories + how each is a distinct character),
A2_UI (the picker exposes ALL styles, data-driven), A3_CYCLE (the cycle-all option +
how it transitions cleanly), A1_COST (re-measured frame cost, heavy families gated).
