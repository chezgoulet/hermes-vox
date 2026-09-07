# Hermes Vox 0.5.2-qwen — massively expanded visual categories + cycle-all

One field ask from Christopher, 2026-09-07:

> "Massively expanding the visualizations. Make sure that Qwen exposes all of the styles
> as categories inside the settings UI as well as an option for all of the animations
> across all of the categories."

The designer owns the palette; the being "exists within the forms and likes to play with
them." Restraint + timing = inhabited. This release keeps the 0.5.1 design contract — a
visual **category** is orthogonal to the idle **shape**, transforming the whole being in
*every* state — and expands it from 7 families to 20, exposes all 20 in Settings, and
adds a clean crossfading rotation through all of them.

`versionCode 91` / `versionName 0.5.2`.

---

## A1_PALETTE — 20 families, each a distinct being (not a recolour)

The bar is Christopher's test: *toggling between two categories mid-call must feel like a
DIFFERENT being, not a recolour.* So no two entries differ on hue alone. Each owns a
distinct combination of all four axes the 0.5.1 design established — **palette** (tint +
saturation + an accent hue-turn), **light** (`coreHeat` white-hot centre + `edge` falloff
tightness, baked into the sprite), **mass** (`halo` bloom + `size`), **motion** (`energy`
flow/tremor + `flicker` shimmer) — applied to every state (listening / thinking / speaking
/ stall / recoil / every tool motif), orthogonally to the idle theme.

`VisualStyleTest.no_category_is_a_pure_recolour_of_another` enforces this: every family has
a **distinct light+mass+motion signature**, so none is another wearing a different colour.

Grouped by substance (`VisualStyle.STYLES`):

**The being's own light**
- **Lumen — its own light** *(default)* — the authored 0.5.0.2/0.5.0.3 renderer. Every
  transform is an exact identity, so an untouched install renders bit-for-bit what it
  always did. Light, cheap, the reference all others move away from.

**Fire & radiance**
- **Ember — forge-warm** — amber/red, hot specular points, hard flicker (1.55), a fast
  volatile body (energy 1.18) that never settles.
- **Solar — radiant** — gold, the white-hot-est bake here (`coreHeat` 1.30), a radiant
  bloom (1.38). Where Ember is a *red flickering forge*, Solar is a *steady golden
  radiator* — same warmth family, opposite temperament.

**Water & ice**
- **Abyss — deep water** — indigo, the widest watery bloom (1.35), big dim slow particles,
  the stillest family (energy 0.72). Its listening barely moves.
- **Tide — living sea** — the ocean *surface*, not its depths: teal-green, a lapping
  flicker, the accent thrown 65° toward foam. Brighter, greener, busier than Abyss.
- **Frost — set in ice** — pale cyan, a crystalline tight falloff (`edge` 0.80), frozen
  almost still (energy 0.58, flicker 0.62). Colder, whiter, shallower than Abyss.
- **Ripple — rings on water** — cyan, and the flicker *is* the character (1.45): a
  rhythmic pulse radiating outward like rings on a pond, over a slow body.

**Growth & the sky**
- **Verdant — bioluminescent** — green/gold, crisp, the accent turned 24° so a second
  organism reads inside the first.
- **Moss — forest floor** — olive, earthy and desaturated (0.78), soft and slow (0.70).
  Where Verdant *glows*, Moss just *grows* — dull, grounded, calm.
- **Aurora — northern curtain** — a green curtain whose accent is thrown 110° toward
  magenta at 1.30 saturation: two colours flowing past each other, high, bright, always
  moving. The signature is the *turn*, not the tint.

**Spectrum & the split of light**
- **Prism — split spectrum** — no tint at all; the accent hue is thrown 150° to the far
  side of the wheel, so the swarm is two opposed colours summing to white where dense.
- **Dusk — twilight** — a low sun: orange pulled toward purple by a 135° accent turn, a
  soft horizon bloom, the day slowing down (energy 0.84).

**Dark & shadow**
- **Raven — dark iridescent** — blue-black, bloom almost off (`halo` 0.55), desaturated
  but never flat: the accent turned −75° to a cold bright sheen, so it reads as a raven's
  feather — a shadow creature with a rim of light. The dark counterpart to Abyss's bloom.

**Monochrome & texture** (three greys that are nothing alike)
- **Ink — 1-bit** — no hue (sat 0.06), hard little points (`edge` 0.55), bloom almost off.
  The being as a *cold plotter drawing*. The cheapest thing here.
- **Sculpt — carved marble** — near-monochrome like Ink but **warm**, polished (`coreHeat`
  1.22) and carved (`edge` 0.72), heavy and still (energy 0.66, flicker 0.50). Ink is a pen
  plot; Sculpt is a *lit stone* — the specular rolls off a solid form, not a flat dot.
- **Static — electric snow** — cold blue-white, the **most** flicker here (1.95) and the
  fastest tremor (energy 1.42), tiny hard points, almost no bloom. A *nervous body of TV
  interference* — agitated where Ink is calm and Sculpt is still.

**Atmosphere & air**
- **Nebula — interstellar** — magenta-violet, the softest light model here (`coreHeat` 0.72
  — no hard points, it is diffuse gas) and the widest falloff (`edge` 1.32), a huge bloom
  (1.45), slow and majestic. The lowest-contrast, most atmospheric family.
- **Aura — soft field** — warm pale gold, halo-dominant (1.40), very soft and the gentlest
  motion here (energy 0.62). The being as a *quiet warmth around itself* rather than a
  bright core — almost static, all ambience.
- **Gale — moving air** — pale silver-blue, desaturated and airy, the **fastest** flow here
  (energy 1.48) but smooth, not jittery — *wind, not static*. A restless body that streams
  past itself.

**The one heavy family (gated)**
- **Comet — trails (heavier)** — each particle drags its own recent past. The only axis
  that is not free (see A1_COST).

Expanding the **table** is free at runtime: only one family renders at a time, so 20
categories cost the same per frame as 7 did. The 13 body archetypes (`A_ORB`…`A_BLOOM`) and
every `MotionState.Motion` are untouched — each category re-colours, re-lights, re-masses
and re-paces all of them, so the swarm now has 20 × 13 distinct readings without one new
shape function or one dropped state.

---

## A2_UI — the picker exposes ALL 20, data-driven, never drifts

The Settings › Visuals **Visual category** picker is driven straight off
`VisualStyle.TOKENS` / `VisualStyle.LABELS`, which are derived from the `STYLES` array
(`Array(STYLES.size) { … }`). Adding a family to the table adds it to the picker — there is
**no second, hard-coded, truncated list** to fall out of sync. `bindVisuals` passes those
arrays directly to the single-choice dialog; `VisualStyleTest.tokens_are_stable_unique_and_label_parallel`
asserts `TOKENS.size == LABELS.size == SIZE`, every token is unique, every label is
parallel, and `indexOf` resolves every slot — so all 20 are exposed and selectable.

---

## A3_CYCLE — "Cycle all categories", and how it transitions cleanly

A new switch in Settings › Visuals (bool pref `visual_cycle_all`, **default false**). Off =
the fixed pick renders. On = the being inhabits **every** family in turn, dwelling
`CYCLE_ALL_SEC` (6s) on each — a ~2-minute rotation through the whole palette, so the user
sees the entire breadth animating.

**No jarring snap — it flows through.** A category is no longer applied as a cut. AvatarView
keeps an **eased render-style**: ten scalar fields + one colour, lerped toward the target
`Style` every frame (`easeStyle`, rate `STYLE_EASE`). When the rotation retargets the family
(`advanceCycle`), the crossfade does the rest:
- the **palette** shades through the *eased* axes via the new `VisualStyle.shade(color,
  tintAmt, tint, sat, accentTurn, accent)` overload, so the tint mix, the saturation and the
  accent **hue-turn rotate smoothly through the colour wheel** instead of jumping;
- the **light model** (`coreHeat`/`edge`) glides, and because it is baked into the sprite it
  is *quantised into the cache key* (4 buckets each) so the glide bakes a handful of sprites,
  not one per frame;
- the **mass** (`halo`/`size`) and **motion** (`energy`/`flicker`) ease too, so the bloom
  swells/shrinks and the flow ramps up/down rather than lurching.

`VisualStyleTest.eased_scalar_shade_matches_the_style_shade_exactly` proves the crossfade
**converges exactly** on each target family (the eased path is bit-identical to the `Style`
path at ease fraction 1.0), so the rotation never drifts off-palette.

**It does not thrash the frame budget.** The crossfade is ~11 lerps + 2 shade calls + the
sprite-key buckets, **once per frame, all primitives, none of it in the 320-particle loop**
(A1_COST). The sprite cache was bumped 16 → 48 so a crossfade's worth of sprites stays
resident without evicting. The hot loop is byte-for-byte the same cost as 0.5.1 — it reads
two *eased* frame-constants (`rFlicker`, `rSize`) where it read two category constants
before; same two multiplies, still **0 alloc/frame**.

**Settable off, returns to the fixed pick.** The user's category choice is remembered
(`userCat`) separately from the rotation: the cycle *starts* from it (so enabling flows from
the look already on screen) and switching off *eases back* to it. Restore-defaults resets
`visual_cycle_all` to false.

**No new wiring.** MainActivity already calls `avatar.setVisualCategory(…)` on create and
every resume (`applyParticlePrefs`); AvatarView reads `visual_cycle_all` from the same `"hv"`
prefs inside that setter, so the toggle rides the **existing** call — MainActivity is
untouched, honouring the 0.5.2 scope list (only AvatarView internals + VisualStyle + the
Settings surface changed).

---

## A1_COST — re-measured, and the one heavy family is gated

- **Per-frame category work, end to end** (`easeStyle` 10 lerps + 1 colour lerp, 2 eased
  `VisualStyle.shade` calls, 3 `spriteKey` lookups): **0.205 µs/frame = 0.00062 % of the
  33,333 µs (33.3 ms) budget**, measured in the gate
  (`VisualStyleTest.per_frame_category_work_is_negligible` prints `A1_COST`). That is ~160,000×
  inside budget. The 320-particle hot loop is unchanged and still allocates nothing.
- **The palette expansion itself costs nothing per frame** — only one family renders at a
  time, so 20 categories ≡ 7 categories ≡ 1 category at runtime. The light model is baked
  into the *cached* sprite, so a hard 1-bit point (Ink) and a wide atmospheric bloom (Nebula)
  both cost one blit, same as the default; the bake happens on a category/colour change,
  never in the loop.
- **Comet is the one heavy family and it is gated**: a trail sprite per particle **doubles
  the blit count** (320 → 640). It is opt-in, never the default, and its Settings label says
  "heavier". `VisualStyleTest.only_the_trail_category_is_heavy_and_it_is_not_the_default`
  locks `heavy == [comet]`. Under cycle-all the rotation passes through Comet for one 6s
  dwell — a bounded, deliberate doubling on ADD blending (cheap), and cycle-all is itself off
  by default. The default path (Lumen, 320 blits, cycle-all off) is the light 0.5.0.2/0.5.0.3
  look, unchanged.
- **The default category is an exact identity** on every axis, and the eased render-style is
  initialised to that identity, so for Lumen every ease delta is 0 and nothing moves —
  asserted in `VisualStyleTest.default_category_is_an_exact_identity`.

---

## Verified

- **KEEP-list zero-touch**: `MotionState.kt` (pure contract) and the public `AvatarView` API
  MainActivity calls — `applyMotion` / `driveMotion` / `setStall` / `onTool` / `pulseTool` /
  `setStateLevel` / `preview` / `setIdleTheme` / `setCycleThemes` — are unchanged (all 9
  signatures intact). silenceAll, the stream fence, retirement, focus, BargeGate/EscapeRule,
  ReplySettleRule, the crash guard, the static-transcript reveal boundary, screen-alive, the
  conn-test probe, CrawlView, SherpaTts and the VoiceController turn engine are untouched.
  **`MainActivity.kt` has no diff** — cycle-all is read from prefs inside the setter it
  already calls. Only `AvatarView` rendering internals, `VisualStyle`, and the Settings
  visual surface (`SettingsActivity` + `activity_settings.xml`) changed, plus the version
  bump and the test.
- **Gate**: `:app:testDebugUnitTest` exit 0 — **166 tests, 0 failures** (14 in
  `VisualStyleTest`, all pure JVM, no emulator), including the new distinctness
  (no-pure-recolour), crossfade-convergence, cycle-all-consistency and A1_COST cases.
- **Release compiles**: `:app:compileReleaseSources` and `:app:processReleaseResources`
  BUILD SUCCESSFUL; `:app:assembleRelease` compiles all the way to `PackageApplication` and
  stops **only** at signing — `../keystore/release.keystore` is not present on this machine
  (it is gitignored; secrets never live in the repo). That is environmental and pre-existing
  (same as 0.5.1), not a code failure.

## The gate not yet proven
Whether all 20 families *read* as distinct substances on the device, whether the cycle-all
crossfade looks as smooth in motion as it measures, and whether Comet's doubled blit count
holds frame rate on the phone. All three are one device pass away; Comet is the only thing
that could cost frames, and it is off by default and one bounded dwell under cycle-all.
