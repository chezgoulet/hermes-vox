package com.hermesvox

import kotlin.math.cos
import kotlin.math.sin

/**
 * VisualStyle — the pure, emulator-free table of VISUAL CATEGORIES: the painterly
 * families the user picks in Settings (0.5.1), massively expanded in 0.5.2.
 *
 * WHY THIS IS NOT A SEVENTH THEME LABEL
 * -------------------------------------
 * `particles_theme` (Aura/Iris/Vortex/…) chooses the being's IDLE SHAPE — which
 * archetype the swarm forms while nothing is happening. It changes the blob and
 * nothing else: the light, the palette and the motion character are identical in
 * every one of them, and the moment a turn starts the shape is owned by the state
 * anyway, so the pick stops mattering.
 *
 * A visual CATEGORY is orthogonal to that. It is a transform applied to every state
 * the being can be in — listening, thinking, speaking, stalled, recoiling — across
 * four independent axes:
 *
 *   PALETTE  a family tint + saturation pulled through the authored per-state hues,
 *            so Ember's listening is still recognisably listening but the whole being
 *            is forge-warm; and an accent TURN (a hue rotation applied only to the
 *            ~30% accent particles), which is how Prism reads as split-spectrum and
 *            Aurora as a green curtain thrown toward magenta, rather than as one colour.
 *   LIGHT    the sprite bake itself: [coreHeat] (how white-hot the specular centre is)
 *            and [edge] (how tight the falloff is). Ink's hard small points, Sculpt's
 *            polished marble and Nebula's wide diffuse gas are genuinely different
 *            light models, not different colours.
 *   MASS     [halo] (the ambient bloom behind the body) and [size] (sprite scale).
 *   MOTION   [energy] multiplies the flow field and the tremor, and [flicker] the
 *            per-particle shimmer, so Abyss is a slow heavy body, Gale a fast streaming
 *            one and Static a nervous electric one — in EVERY state, including the
 *            still ones.
 *
 * 0.5.2 (A1) expands the table from 7 families to 20. The bar is the maintainer's: toggling
 * between two categories mid-call must feel like a DIFFERENT BEING, not a recolour. So no
 * two entries differ on hue alone — each owns a distinct combination of all four axes
 * (fire vs radiance, deep water vs surface tide vs set ice, bioluminescent growth vs dull
 * moss, diffuse gas vs polished stone vs electric snow…). The palette is grouped below by
 * substance so the families read against each other.
 *
 * Two user scalars (Settings sliders) ride on top: motion energy and glow. They
 * multiply [energy] and [halo], so a category is a starting point the user can push.
 *
 * THE GROUND (#275)
 * -----------------
 * For 0.5–0.9 the being had ONE ground: black. In light mode it was the same light
 * painted inside a black window, and the window was the defect. A family is not only
 * a set of colours; it is a set of colours *on a ground*. Light on black is additive:
 * overlapping light sums toward white, a bloom is light spilling into the dark, and a
 * white-hot core is the brightest thing on screen. On paper every one of those
 * sentences is false — white is the background, so a white-hot core is invisible, a
 * bloom is a smudge, and "sums to white" (Prism) sums to nothing.
 *
 * So each [Style] carries an authored [Light] variant. It is NOT the dark palette
 * inverted through luminance: inverting "deep water" gives pale grey, and pale grey is
 * not deep water. The light variant authors, per family, the ink hue it becomes on
 * paper ([Light.tint]), how strongly the state hue is pulled toward it
 * ([Light.tintAmt]), its saturation and accent turn, how dark the body is
 * ([Light.value]), how eagerly emission becomes coverage ([Light.cover]), and its own
 * bake ([Light.coreHeat], [Light.edge]) and ambient wash ([Light.halo]).
 *
 * The blend is SUBTRACTIVE, specified once and mirrored by [LightInk]:
 *
 *     out = paper + (ink - paper) * cover        (cover from the emission's luminance)
 *
 * A darker emission is a denser mark; a hotter core is a denser centre, not a brighter
 * one. The light source becomes the paper, and the being is what blocks it. Families
 * whose whole idea was a glow say what they become instead in the table below.
 *
 * [PAPER_LIGHT] is the actual light surface (`@color/hv_bg` in values/), and
 * [Style.light] is never invented: [LightModeContrastTest] fails if a family ships
 * without one, and measures every family in both grounds against the ratio floors.
 *
 * CYCLE-ALL (0.5.2 A3)
 * --------------------
 * [KEY_CYCLE_ALL] turns the category into a rotation: the being inhabits every family in
 * turn, dwelling [CYCLE_ALL_SEC] on each. AvatarView crossfades the axes per frame (it
 * eases a render-style toward the target [Style] and shades through the eased-scalar
 * [shade] overload below), so a transition FLOWS — the palette rotates through the wheel,
 * the light model and the mass glide — instead of snapping. Off returns to the fixed pick.
 *
 * COST
 * ----
 * Every axis above is resolved ONCE PER FRAME (two colours, a handful of scalars) or baked
 * ONCE into a cached sprite. Nothing here runs per particle: the hot loop gains
 * exactly two multiplies (size, flicker), both on values it was already computing.
 * The single exception is [trails], which costs a second blit per particle — it is
 * flagged [heavy], it is never the default, and the label says so. Expanding the TABLE is
 * free at runtime: only one family renders at a time, so 20 categories cost the same per
 * frame as 7 did.
 *
 * Pure JVM — no Android deps, colour is plain ARGB integer math — so the whole
 * palette is proven off-device like MotionState / BargeGate / ReplySettleRule.
 */
object VisualStyle {

    // ---- pref keys + shipped defaults (Settings -> Visuals) ------------------
    const val KEY_CATEGORY = "visual_category"
    const val KEY_ENERGY = "visual_energy"
    const val KEY_GLOW = "visual_glow"
    /** 0.5.2 (A3): when true the being cycles through EVERY category over time, so the
     *  user sees the whole breadth animating. Default false = a fixed category. */
    const val KEY_CYCLE_ALL = "visual_cycle_all"
    const val DEFAULT_CYCLE_ALL = false
    /** The SharedPreferences file the visual prefs live in (MainActivity + Settings both
     *  use "hv"). AvatarView reads [KEY_CYCLE_ALL] from here inside setVisualCategory, so
     *  the cycle-all toggle rides the EXISTING call MainActivity already makes on every
     *  resume — no new call site, honouring the 0.5.2 scope list. */
    const val PREFS_NAME = "hv"
    /** Dwell per category while cycle-all is on. AvatarView's crossfade completes well
     *  inside this, so each family is INHABITED for a beat, not flashed past. 20 families
     *  × 6s = a ~2 minute rotation through the whole palette. */
    const val CYCLE_ALL_SEC = 6f

    /** The default category is the 0.5.0.2/0.5.0.3 look, byte-for-byte: every one of
     *  its transforms is an identity, so shipping this table changes nothing until
     *  the user chooses otherwise. */
    const val DEFAULT = "lumen"
    const val DEFAULT_ENERGY = 1f
    const val DEFAULT_GLOW = 1f

    /** #275: the light ground — the same value as `@color/hv_bg` in values/colors.xml.
     *  It is the paper the being is drawn as ink on, and the reference surface for every
     *  light-mode contrast floor. Kept here (rather than read from Android) because this
     *  file is pure JVM; [LightModeContrastTest] checks the XML still agrees. */
    const val PAPER_LIGHT = 0xFFF3F5F9.toInt()

    const val ENERGY_MIN = 0.5f; const val ENERGY_MAX = 1.6f; const val ENERGY_STEP = 0.1f

    // #225: the fine-grained look. Each is a scalar over the category, never a replacement,
    // and each default is the identity — an untouched install draws exactly what it drew.
    /** How many of the being's particles are drawn, as a fraction. */
    const val KEY_DENSITY = "visual_density"
    const val DEFAULT_DENSITY = 1f
    const val DENSITY_MIN = 0.3f; const val DENSITY_MAX = 1f; const val DENSITY_STEP = 0.1f
    /** How fast the being's own clock runs: its drift, shimmer and breath. */
    const val KEY_SPEED = "visual_speed"
    const val DEFAULT_SPEED = 1f
    const val SPEED_MIN = 0.5f; const val SPEED_MAX = 1.8f; const val SPEED_STEP = 0.1f
    /** Light trails behind the particles; 0 keeps the category's own (most have none). */
    const val KEY_TRAIL = "visual_trail"
    const val DEFAULT_TRAIL = 0f
    const val TRAIL_MIN = 0f; const val TRAIL_MAX = 0.9f; const val TRAIL_STEP = 0.1f
    /** A hue turn applied to the whole palette, in degrees; 0 keeps the authored colours. */
    const val KEY_HUE = "visual_hue"
    const val DEFAULT_HUE = 0f
    const val HUE_MIN = -180f; const val HUE_MAX = 180f; const val HUE_STEP = 15f

    fun density(pref: Float): Float = pref.coerceIn(DENSITY_MIN, DENSITY_MAX)
    fun speed(pref: Float): Float = pref.coerceIn(SPEED_MIN, SPEED_MAX)
    fun trail(pref: Float): Float = pref.coerceIn(TRAIL_MIN, TRAIL_MAX)
    fun hue(pref: Float): Float = pref.coerceIn(HUE_MIN, HUE_MAX)
    /** The trail the renderer draws: the user's, or the category's own if that is longer. */
    fun trails(category: Float, user: Float): Float = maxOf(category, trail(user))
    /** How many points to draw out of [of] at [density] — never fewer than one. */
    fun drawCount(of: Int, density: Float): Int = maxOf(1, (of * density(density)).toInt())
    const val GLOW_MIN = 0.4f;   const val GLOW_MAX = 1.6f;   const val GLOW_STEP = 0.1f

    /**
     * One painterly family. Every field is a MULTIPLIER or a MIX AMOUNT against the
     * authored per-state values, never a replacement — a category re-colours and
     * re-weights the being, it never overrides what the being is doing.
     */
    class Style(
        /** Stable pref token. */
        val token: String,
        /** Human label (Settings row + picker). */
        val label: String,
        /** The family colour every state palette is pulled toward. */
        val tint: Int,
        /** How far toward [tint] (0 = untouched). */
        val tintAmt: Float,
        /** Saturation scale about luma (1 = as authored, 0 = greyscale). */
        val sat: Float,
        /** Hue rotation, in degrees, applied ONLY to the accent hue. This is what
         *  makes a category two-tone in a way a tint alone cannot. */
        val accentTurn: Float,
        /** White-hot centre amount in the sprite bake (1 = as authored). */
        val coreHeat: Float,
        /** Falloff tightness: <1 pulls the gradient stops in (hard, crisp points),
         *  >1 pushes them out (wide, soft, atmospheric). */
        val edge: Float,
        /** Ambient bloom scale. */
        val halo: Float,
        /** Sprite size scale. */
        val size: Float,
        /** Per-particle flicker scale. */
        val flicker: Float,
        /** Flow-field + tremor scale: the category's motion character. */
        val energy: Float,
        /** A second, dimmer sprite behind each particle at its previous position.
         *  DOUBLES the blit count — the one axis that is not free. */
        val trails: Boolean = false,
        /** #275: the same family authored for the light ground. No default: a new
         *  family must state what it becomes on paper, or it will not compile. */
        val light: Light
    ) {
        /** True when the category costs materially more than the default. Settings
         *  says so in the label; the default is always a light one. */
        val heavy: Boolean get() = trails
    }

    /**
     * One family's authored light-ground variant (#275). All fields are authored per
     * family, not derived from the dark palette: inverting by luminance would turn a
     * deep hue into a pale grey and a glow into fog.
     *
     * [tint] is the ink colour the family becomes on paper; [tintAmt] how strongly the
     * state's own hue is pulled toward it (so listening is still listening); [sat] and
     * [accentTurn] are the light-ground palette transforms. [value] is the max channel
     * of the body ink (smaller = darker = more contrast), [cover] how eagerly emission
     * become ink coverage (a wide diffuse family wants more than a hard-point one),
     * [coreHeat] and [edge] are the bake on this ground (specular becomes DENSITY, not
     * brightness), and [halo] is the ambient wash — a bloom becomes a low-opacity stain.
     */
    class Light(
        val tint: Int,
        val tintAmt: Float,
        val sat: Float,
        val accentTurn: Float,
        val value: Float,
        val cover: Float,
        val coreHeat: Float,
        val edge: Float,
        val halo: Float
    )

    /**
     * The palette — 20 families, deliberately not variations of one house style. Each
     * entry is a different SUBSTANCE the being can be made of, distinguished on all four
     * axes at once (colour logic, light model, mass, motion) so that switching between
     * any two reads as a different creature rather than a hue shift. Grouped by element.
     */
    private val STYLES = arrayOf(
        // ── the being's own light ────────────────────────────────────────────
        // The authored 0.5.0.2 renderer, untouched: every transform is an identity, so
        // this is the look an untouched install ships and the reference all others move
        // away from. Light, cheap, the default.
        // LIGHT: its own light cannot be its own light on paper, so it becomes its own
        // ink — the state hue at a deep value, no family tint, the hot core a denser mark.
        Style("lumen", "Lumen — its own light", 0x00000000, 0f, 1f, 0f,
            1f, 1f, 1f, 1f, 1f, 1f,
            light = Light(0x00000000, 0f, 1f, 0f, 0.39f, 1.70f, 1f, 1f, 1f)),

        // ── fire & radiance ──────────────────────────────────────────────────
        // Forge-warm and volatile: amber/red through every state, hot specular points,
        // hard flicker (1.55), a fast body (1.18) that never quite settles.
        // LIGHT: forge glow -> rust ink; the specular points stay hard but read as dense
        // embers, not bright ones.
        Style("ember", "Ember — forge-warm", 0xFFFF6A2E.toInt(), 0.34f, 1.12f, 0f,
            1.06f, 0.88f, 1.12f, 0.96f, 1.55f, 1.18f,
            light = Light(0xFF8A2A12.toInt(), 0.34f, 1.12f, 0f, 0.37f, 1.80f, 1.06f, 0.88f, 1.00f)),
        // The sun: gold, the white-hot-est bake here (coreHeat 1.30), a radiant bloom
        // (1.38). Where Ember is a red flickering forge, Solar is a steady golden
        // radiator — same warmth family, opposite temperament.
        // LIGHT: gold radiance -> burnt-amber ink. The hottest core in the table becomes
        // the DENSEST centre on paper; a white core here would be a hole in the page.
        Style("solar", "Solar — radiant", 0xFFFFC247.toInt(), 0.38f, 1.15f, 25f,
            1.30f, 0.94f, 1.38f, 1.04f, 1.35f, 1.12f,
            light = Light(0xFF8A5300.toInt(), 0.38f, 1.15f, 25f, 0.36f, 1.80f, 1.30f, 0.94f, 1.10f)),

        // ── water & ice ──────────────────────────────────────────────────────
        // Deep water: indigo, the widest bloom of the watery set (1.35), big dim slow
        // particles, the stillest family (energy 0.72). Its listening barely moves.
        // LIGHT: the idea survives without translation — indigo becomes a deep-indigo ink
        // pulled hard toward the family tint (0.58), so no state turns it pale grey.
        Style("abyss", "Abyss — deep water", 0xFF1B3A8C.toInt(), 0.40f, 0.88f, 0f,
            0.88f, 1.18f, 1.35f, 1.12f, 0.60f, 0.72f,
            light = Light(0xFF102A5F.toInt(), 0.58f, 0.95f, 0f, 0.30f, 2.00f, 0.88f, 1.18f, 1.15f)),
        // The ocean SURFACE, not its depths: teal-green, a lapping flicker (1.25), the
        // accent thrown 65° toward foam. Brighter, greener and busier than Abyss.
        // LIGHT: sea-green ink, accent still thrown toward foam; the wide wash becomes a
        // shallow tide-line rather than a glow.
        Style("tide", "Tide — living sea", 0xFF2AB8A8.toInt(), 0.36f, 1.00f, 65f,
            0.96f, 1.12f, 1.15f, 1.00f, 1.25f, 0.98f,
            light = Light(0xFF0B5A52.toInt(), 0.50f, 1.05f, 65f, 0.34f, 2.00f, 0.96f, 1.12f, 1.10f)),
        // Ice: pale cyan, a crystalline tight falloff (edge 0.80), frozen almost still
        // (energy 0.58, flicker 0.62). Colder, whiter, shallower than Abyss — a body set
        // in frost rather than sunk in water.
        // LIGHT: pale cyan cannot survive on white — pale on pale is the original defect.
        // It becomes a GLACIER INK: a cold grey-cyan at a deep value, keeping the tight
        // crystalline falloff so the ice still reads as ice, not as water.
        Style("frost", "Frost — set in ice", 0xFFBFEFFF.toInt(), 0.34f, 0.80f, -20f,
            1.10f, 0.80f, 1.05f, 0.94f, 0.62f, 0.58f,
            light = Light(0xFF46666F.toInt(), 0.55f, 0.75f, -20f, 0.35f, 1.60f, 1.10f, 0.80f, 0.80f)),
        // Concentric calm: cyan, and the flicker IS the character (1.45) — a rhythmic
        // pulse radiating outward like rings on a pond, over a slow body (0.88).
        // LIGHT: pond ink; the rings become darker rings on the page, and the flicker
        // still drives them — motion untouched.
        Style("ripple", "Ripple — rings on water", 0xFF3FE0E0.toInt(), 0.36f, 1.04f, -30f,
            0.95f, 1.05f, 1.10f, 0.95f, 1.45f, 0.88f,
            light = Light(0xFF0C5C5C.toInt(), 0.50f, 1.00f, -30f, 0.36f, 2.00f, 0.95f, 1.05f, 1.05f)),

        // ── growth & the sky ─────────────────────────────────────────────────
        // Bioluminescent: green/gold, crisp, the accent turned just far enough (24°) to
        // read as a second organism inside the first.
        // LIGHT: bioluminescence -> chlorophyll ink; the second organism is a second dark
        // hue, not a second glow.
        Style("verdant", "Verdant — bioluminescent", 0xFF2FE08A.toInt(), 0.34f, 1.06f, 24f,
            1f, 0.96f, 1f, 1f, 1.10f, 0.95f,
            light = Light(0xFF0C5A36.toInt(), 0.50f, 1.05f, 24f, 0.32f, 2.00f, 1.00f, 0.96f, 0.95f)),
        // The forest floor: olive, earthy and desaturated (0.78), soft and slow (0.70).
        // Where Verdant glows, Moss just grows — dull, grounded, calm.
        // LIGHT: umber-olive ink; moss was already a dark, earthy idea, so it survives the
        // ground almost unchanged — it simply stops pretending to emit.
        Style("moss", "Moss — forest floor", 0xFF7A9A3A.toInt(), 0.38f, 0.78f, 35f,
            0.86f, 1.08f, 1.00f, 1.02f, 0.72f, 0.70f,
            light = Light(0xFF43501E.toInt(), 0.55f, 0.78f, 35f, 0.34f, 1.80f, 0.86f, 1.08f, 1.00f)),
        // The northern lights: a green curtain whose accent is thrown 110° toward magenta
        // at 1.30 saturation — two colours flowing past each other, high, bright, always
        // moving (1.10). The signature is the TURN, not the tint.
        // LIGHT: the curtain is a glow by definition, so it becomes two crossing WASHES of
        // ink — green body, magenta accent — that overlap subtractively to graphite. The
        // turn survives; the luminance does not need to.
        Style("aurora", "Aurora — northern curtain", 0xFF4CFFB0.toInt(), 0.30f, 1.30f, 110f,
            0.92f, 1.10f, 1.20f, 1.02f, 1.15f, 1.10f,
            light = Light(0xFF0B5C3C.toInt(), 0.30f, 1.25f, 110f, 0.34f, 2.00f, 0.92f, 1.10f, 1.15f)),

        // ── spectrum & the split of light ────────────────────────────────────
        // Split spectrum: no tint at all — the accent hue is thrown 150° to the far side
        // of the wheel at 1.25 saturation, so the swarm is always two opposed colours
        // summing to white where it is dense.
        // LIGHT: "sums to white" is the one idea that CANNOT survive: on paper white is
        // the ground, so the sum would be invisible. Prism becomes split INK — the two
        // opposed hues as two dark inks, and their overlap a neutral graphite, which is
        // the subtractive sum. No tint, exactly as on black.
        Style("prism", "Prism — split spectrum", 0x00000000, 0f, 1.25f, 150f,
            1.02f, 0.92f, 0.95f, 1f, 1.20f, 1.05f,
            light = Light(0x00000000, 0f, 1.25f, 150f, 0.34f, 1.90f, 1.02f, 0.92f, 0.90f)),
        // Twilight: a low sun. Orange pulled toward purple by a 135° accent turn — the
        // warm/cool split of a sunset, a soft horizon bloom (1.22), the day slowing (0.84).
        // LIGHT: burnt-sienna body with a violet accent — the sunset's warm/cool split,
        // drawn rather than lit.
        Style("dusk", "Dusk — twilight", 0xFFFF8A4C.toInt(), 0.32f, 1.08f, 135f,
            0.94f, 1.14f, 1.22f, 1.03f, 0.90f, 0.84f,
            light = Light(0xFF7A3418.toInt(), 0.45f, 1.05f, 135f, 0.35f, 1.80f, 0.94f, 1.14f, 1.10f)),

        // ── dark & shadow ────────────────────────────────────────────────────
        // Dark iridescent: blue-black, bloom almost off (halo 0.55), desaturated (0.70)
        // but never flat — the accent turned -75° to a cold bright sheen, so it reads as
        // a raven's feather: a shadow creature with a rim of light. The dark counterpart
        // to Abyss's bloom.
        // LIGHT: the shadow creature has nowhere darker to hide, so its role inverts: it
        // becomes the calmest graphite body on the page, the cold sheen now a dense blue
        // accent. Bloom stays almost off — on paper that is a small wash, not a halo.
        Style("raven", "Raven — dark iridescent", 0xFF2A2350.toInt(), 0.46f, 0.70f, -75f,
            1.05f, 0.85f, 0.55f, 0.92f, 0.85f, 0.80f,
            light = Light(0xFF1A1C3A.toInt(), 0.62f, 0.75f, -75f, 0.28f, 1.70f, 1.05f, 0.85f, 0.55f)),

        // ── monochrome & texture ─────────────────────────────────────────────
        // 1-bit: no hue at all (sat 0.06). Hard little points on black (edge 0.55), bloom
        // almost off (0.34) — the being as a plotter drawing. Cold, crisp, cheapest here.
        // LIGHT: the 1-bit idea finally has a natural ground: a black pen on a white page.
        // The white plotter points become the darkest ink here, and the hard falloff is
        // kept exactly, so this is the family that gains the most from the ground.
        Style("ink", "Ink — 1-bit", 0xFFFFFFFF.toInt(), 0.28f, 0.06f, 0f,
            1.15f, 0.55f, 0.34f, 0.82f, 0.90f, 0.90f,
            light = Light(0xFF111111.toInt(), 0.30f, 0.06f, 0f, 0.18f, 1.50f, 1.15f, 0.55f, 0.34f)),
        // Marble: near-monochrome like Ink but WARM, polished (coreHeat 1.22) and carved
        // (edge 0.72), heavy and still (energy 0.66, flicker 0.50). Ink is a cold pen
        // plot; Sculpt is a lit stone — the specular rolls off a solid form, not a dot.
        // LIGHT: warm stone ink. A specular highlight cannot be lighter than the page, so
        // the polish becomes a DENSER centre — the carving is still legible, the light is
        // now the page.
        Style("sculpt", "Sculpt — carved marble", 0xFFEDE4D6.toInt(), 0.34f, 0.22f, 20f,
            1.22f, 0.72f, 0.70f, 1.06f, 0.50f, 0.66f,
            light = Light(0xFF5E5346.toInt(), 0.50f, 0.22f, 20f, 0.34f, 1.70f, 1.22f, 0.72f, 0.70f)),
        // Electric interference: cold blue-white, the MOST flicker here (1.95) and the
        // fastest tremor (energy 1.42), tiny hard points (size 0.74), almost no bloom. A
        // nervous body of TV snow — agitated where Ink is calm and Sculpt is still.
        // LIGHT: cold graphite specks; the tiny hard points are kept, because they are the
        // family, and the low coverage keeps the static thin rather than a grey cloud.
        Style("static", "Static — electric snow", 0xFFCFE4FF.toInt(), 0.24f, 0.40f, -50f,
            1.18f, 0.62f, 0.48f, 0.74f, 1.95f, 1.42f,
            light = Light(0xFF2C3A4F.toInt(), 0.40f, 0.40f, -50f, 0.30f, 1.60f, 1.18f, 0.62f, 0.48f)),

        // ── atmosphere & air ─────────────────────────────────────────────────
        // Interstellar gas: magenta-violet, the softest light model here (coreHeat 0.72 —
        // no hard points, it is diffuse gas) and the widest falloff (edge 1.32), a huge
        // bloom (1.45), slow and majestic (0.68). The lowest-contrast, most atmospheric.
        // LIGHT: a glow-only family has no direct translation. The gas becomes a wide,
        // low-opacity VIOLET WASH with a denser core — the bloom's job (an area of colour
        // around the body) is kept, but it stains the page instead of lighting it. Its
        // coverage gain (2.40) is the highest here so the wash stays visible on paper.
        Style("nebula", "Nebula — interstellar", 0xFFB44CFF.toInt(), 0.42f, 1.10f, 40f,
            0.72f, 1.32f, 1.45f, 1.18f, 0.55f, 0.68f,
            light = Light(0xFF4A1A66.toInt(), 0.55f, 1.10f, 40f, 0.38f, 2.40f, 0.72f, 1.32f, 0.90f)),
        // A personal field: warm pale gold, halo-dominant (1.40), very soft (edge 1.22)
        // and the gentlest motion here (energy 0.62). The being as a quiet warmth around
        // itself rather than a bright core — almost static, all ambience.
        // LIGHT: another glow-dominant family: the field becomes a warm ochre WASH around
        // a small dark core, i.e. the halo is now a stain whose edge fades into the paper.
        // It is still the gentlest family; it is no longer the palest.
        Style("aura", "Aura — soft field", 0xFFFFE6B0.toInt(), 0.30f, 0.92f, 15f,
            0.90f, 1.22f, 1.40f, 1.05f, 0.70f, 0.62f,
            light = Light(0xFF6B4A12.toInt(), 0.45f, 0.92f, 15f, 0.35f, 2.20f, 0.90f, 1.22f, 1.20f)),
        // Moving air: pale silver-blue, desaturated and airy (0.66), the FASTEST flow here
        // (energy 1.48) but smooth, not jittery — wind, not static. A restless body that
        // streams past itself.
        // LIGHT: slate-blue ink; pale silver on white would be wind you cannot see, so the
        // air is drawn as fine dark streaks, with the same fast flow.
        Style("gale", "Gale — moving air", 0xFFA8C4DC.toInt(), 0.28f, 0.66f, -40f,
            1.00f, 0.92f, 0.95f, 0.88f, 1.40f, 1.48f,
            light = Light(0xFF334A5E.toInt(), 0.45f, 0.66f, -40f, 0.35f, 1.80f, 1.00f, 0.92f, 0.85f)),

        // ── the one heavy family (gated, opt-in, labelled) ───────────────────
        // Trails: each particle drags its own recent past. DOUBLES the blit count, so it
        // is opt-in, never the default, and the label says "heavier".
        // LIGHT: trails are frame feedback and work unchanged; on paper each trail is a
        // fading ink smear behind a blue-grey body rather than a light streak.
        Style("comet", "Comet — trails (heavier)", 0xFF9BD8FF.toInt(), 0.16f, 1.05f, 0f,
            1f, 0.90f, 1.05f, 0.92f, 1f, 1.10f, trails = true,
            light = Light(0xFF2A4C6B.toInt(), 0.30f, 1.05f, 0f, 0.38f, 1.90f, 1.00f, 0.90f, 1.00f))
    )

    /** Stable pref tokens, in picker order. Settings lists EVERY one of these (A2): the
     *  picker is data-driven off this array, so expanding the table above expands the UI
     *  with no second list to drift. */
    val TOKENS: Array<String> = Array(STYLES.size) { STYLES[it].token }
    /** Human labels, parallel to [TOKENS]. */
    val LABELS: Array<String> = Array(STYLES.size) { STYLES[it].label }
    /** How many families ship. */
    val SIZE: Int get() = STYLES.size

    /** The style for a stored token. An unknown/blank/stale token is the DEFAULT,
     *  never a crash and never a blank being. */
    fun of(token: String?): Style {
        val t = token?.lowercase() ?: return STYLES[0]
        for (s in STYLES) if (s.token == t) return s
        return STYLES[0]
    }

    /** The picker index of a token. Cycle-all starts its rotation from the user's fixed
     *  pick so enabling it flows from the family already on screen. Unknown/blank = 0,
     *  never -1, never a crash. */
    fun indexOf(token: String?): Int {
        val t = token?.lowercase() ?: return 0
        for (i in STYLES.indices) if (STYLES[i].token == t) return i
        return 0
    }

    fun labelOf(token: String?): String = of(token).label

    fun energy(pref: Float): Float = pref.coerceIn(ENERGY_MIN, ENERGY_MAX)
    fun glow(pref: Float): Float = pref.coerceIn(GLOW_MIN, GLOW_MAX)

    // ---- colour math. Plain ARGB integers so this file stays JVM-pure ---------

    private fun r(c: Int) = (c ushr 16) and 0xFF
    private fun g(c: Int) = (c ushr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF
    private fun rgb(r: Float, g: Float, b: Float): Int =
        0xFF000000.toInt() or
            (r.toInt().coerceIn(0, 255) shl 16) or
            (g.toInt().coerceIn(0, 255) shl 8) or
            b.toInt().coerceIn(0, 255)

    /** Linear per-channel mix. f = 0 returns [a] exactly (the identity the default
     *  category relies on). */
    fun mix(a: Int, b2: Int, f: Float): Int {
        val ff = f.coerceIn(0f, 1f)
        return rgb(r(a) * (1 - ff) + r(b2) * ff,
            g(a) * (1 - ff) + g(b2) * ff,
            b(a) * (1 - ff) + b(b2) * ff)
    }

    /** Saturation about Rec.601 luma. s = 1 is the identity; 0 is greyscale; >1
     *  pushes the hue out without moving the brightness. */
    fun saturate(c: Int, s: Float): Int {
        if (s == 1f) return c or 0xFF000000.toInt()
        val y = 0.299f * r(c) + 0.587f * g(c) + 0.114f * b(c)
        return rgb(y + (r(c) - y) * s, y + (g(c) - y) * s, y + (b(c) - y) * s)
    }

    /** Luminance-preserving hue rotation (the standard YIQ-derived matrix). deg = 0
     *  is the identity. Used once per frame on ONE colour — never per particle. */
    fun turn(c: Int, deg: Float): Int {
        if (deg == 0f) return c or 0xFF000000.toInt()
        val a = deg * (Math.PI.toFloat() / 180f)
        val co = cos(a); val si = sin(a)
        val m0 = 0.213f + co * 0.787f - si * 0.213f
        val m1 = 0.715f - co * 0.715f - si * 0.715f
        val m2 = 0.072f - co * 0.072f + si * 0.928f
        val m3 = 0.213f - co * 0.213f + si * 0.143f
        val m4 = 0.715f + co * 0.285f + si * 0.140f
        val m5 = 0.072f - co * 0.072f - si * 0.283f
        val m6 = 0.213f - co * 0.213f - si * 0.787f
        val m7 = 0.715f - co * 0.715f + si * 0.715f
        val m8 = 0.072f + co * 0.928f + si * 0.072f
        val rr = r(c).toFloat(); val gg = g(c).toFloat(); val bb = b(c).toFloat()
        return rgb(m0 * rr + m1 * gg + m2 * bb,
            m3 * rr + m4 * gg + m5 * bb,
            m6 * rr + m7 * gg + m8 * bb)
    }

    /**
     * The per-frame entry: take an authored state colour and return it as this
     * category renders it. [accent] selects the second hue (the ~30% of particles
     * that carry it), which is the only one the hue turn touches.
     *
     * Called exactly twice per frame. For the default category every branch is an
     * identity and the result is bit-identical to the input.
     */
    fun shade(color: Int, s: Style, accent: Boolean): Int =
        shade(color, s.tintAmt, s.tint, s.sat, s.accentTurn, accent)

    /**
     * The EASED-SCALAR overload (0.5.2 A3). AvatarView crossfades a category by easing
     * its axes per frame and shading from those eased values, so a cycle-all transition
     * flows through the palette — the tint mix, the saturation and the accent hue turn all
     * glide — instead of snapping at the moment the target family changes. Identical math
     * to [shade]/[Style], identical two-calls-per-frame cost, and it needs no Style object
     * at the call site (so the eased render-style allocates nothing per frame).
     */
    fun shade(color: Int, tintAmt: Float, tint: Int, sat: Float, accentTurn: Float, accent: Boolean): Int {
        var v = if (tintAmt > 0f) mix(color, tint, tintAmt) else color or 0xFF000000.toInt()
        if (sat != 1f) v = saturate(v, sat)
        if (accent && accentTurn != 0f) v = turn(v, accentTurn)
        return v
    }

    // ---- #275: the light ground ------------------------------------------------
    // The same two-call-per-frame contract as [shade], but the state hue is placed on
    // paper: it is normalised to its own hue (max channel = 1, so a deep hue does not
    // clip), pulled toward the family's light tint, saturated/turned, and then written at
    // the authored ink value. The shader only has to scale that hue by [Light.value];
    // [ink] is the final body colour the contrast test measures.

    /** The body (or accent) ink a light-family makes of an authored state colour:
     *  the final dark mark on paper, as an opaque ARGB integer. */
    fun ink(color: Int, s: Style, accent: Boolean): Int =
        valueTo(inkHue(color, s.light, accent), s.light.value)

    fun ink(color: Int, l: Light, accent: Boolean): Int =
        valueTo(inkHue(color, l, accent), l.value)

    /** The same ink at unit value — the hue the particle bake carries. The composite
     *  applies [Light.value] once, per pixel, so the ink is not darkened twice and the
     *  emission keeps the same coverage character as on black. */
    fun inkHue(color: Int, s: Style, accent: Boolean): Int = inkHue(color, s.light, accent)

    fun inkHue(color: Int, l: Light, accent: Boolean): Int =
        inkHue(color, l.tintAmt, l.tint, l.sat, l.accentTurn, accent)

    /** The EASED-SCALAR overload, parallel to [shade]'s: AvatarView crossfades a family
     *  on the light ground by easing exactly the same axes and calling this. */
    fun inkHue(color: Int, tintAmt: Float, tint: Int, sat: Float, accentTurn: Float, accent: Boolean): Int {
        var v = normalize(color)
        // A tint of 0 (Lumen, Prism) has no hue to pull toward; 0 means "none", not black.
        if (tintAmt > 0f && maxChannel(tint) > 0) v = mix(v, normalize(tint), tintAmt)
        if (sat != 1f) v = saturate(v, sat)
        if (accent && accentTurn != 0f) v = turn(v, accentTurn)
        return normalize(v)
    }

    private fun maxChannel(c: Int) = maxOf(r(c), g(c), b(c))

    /** Scale a colour to its hue at max channel 1 (value 1), preserving ratios. */
    private fun normalize(c: Int): Int {
        val m = maxChannel(c)
        if (m <= 0) return 0
        val k = 255f / m
        return rgb(r(c) * k, g(c) * k, b(c) * k)
    }

    /** Scale a colour's hue to max channel [v] (0..1) — the authored ink darkness. */
    private fun valueTo(c: Int, v: Float): Int {
        val m = maxChannel(c)
        if (m <= 0) return 0xFF000000.toInt()
        val k = (v.coerceIn(0f, 1f) * 255f) / m
        return rgb(r(c) * k, g(c) * k, b(c) * k)
    }
}
