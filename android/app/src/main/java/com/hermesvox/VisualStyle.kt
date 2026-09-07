package com.hermesvox

import kotlin.math.cos
import kotlin.math.sin

/**
 * VisualStyle — the pure, emulator-free table of VISUAL CATEGORIES: the painterly
 * families the user picks in Settings (0.5.1).
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
 *            ~30% accent particles), which is how Prism reads as split-spectrum
 *            rather than as one colour.
 *   LIGHT    the sprite bake itself: [coreHeat] (how white-hot the specular centre is)
 *            and [edge] (how tight the falloff is). Ink's hard small points and
 *            Abyss's wide soft bloom are genuinely different light models, not
 *            different colours.
 *   MASS     [halo] (the ambient bloom behind the body) and [size] (sprite scale).
 *   MOTION   [energy] multiplies the flow field and the tremor, so Abyss is a slow
 *            heavy body and Ember is a fast volatile one — in EVERY state, including
 *            the still ones.
 *
 * Two user scalars (Settings sliders) ride on top: motion energy and glow. They
 * multiply [energy] and [halo], so a category is a starting point the user can push.
 *
 * COST
 * ----
 * Every axis above is resolved ONCE PER FRAME (two colours, four scalars) or baked
 * ONCE into a cached sprite. Nothing here runs per particle: the hot loop gains
 * exactly two multiplies (size, flicker), both on values it was already computing.
 * The single exception is [trails], which costs a second blit per particle — it is
 * flagged [heavy], it is never the default, and the label says so.
 *
 * Pure JVM — no Android deps, colour is plain ARGB integer math — so the whole
 * palette is proven off-device like MotionState / BargeGate / ReplySettleRule.
 */
object VisualStyle {

    // ---- pref keys + shipped defaults (Settings -> Visuals) ------------------
    const val KEY_CATEGORY = "visual_category"
    const val KEY_ENERGY = "visual_energy"
    const val KEY_GLOW = "visual_glow"

    /** The default category is the 0.5.0.2/0.5.0.3 look, byte-for-byte: every one of
     *  its transforms is an identity, so shipping this table changes nothing until
     *  the user chooses otherwise. */
    const val DEFAULT = "lumen"
    const val DEFAULT_ENERGY = 1f
    const val DEFAULT_GLOW = 1f

    const val ENERGY_MIN = 0.5f; const val ENERGY_MAX = 1.6f; const val ENERGY_STEP = 0.1f
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
        val trails: Boolean = false
    ) {
        /** True when the category costs materially more than the default. Settings
         *  says so in the label; the default is always a light one. */
        val heavy: Boolean get() = trails
    }

    /**
     * The palette. Deliberately not six variations of one house style — each entry
     * is a different substance the being can be made of.
     */
    private val STYLES = arrayOf(
        // The being's own light: the authored 0.5.0.2 renderer, untouched.
        Style("lumen", "Lumen — its own light", 0x00000000, 0f, 1f, 0f,
            1f, 1f, 1f, 1f, 1f, 1f),
        // Forge-warm and volatile: amber/red through every state, hot specular
        // points, hard flicker, a body that never quite settles.
        Style("ember", "Ember — forge-warm", 0xFFFF6A2E.toInt(), 0.34f, 1.12f, 0f,
            1.06f, 0.88f, 1.12f, 0.96f, 1.55f, 1.18f),
        // Deep water: indigo, wide bloom, big dim slow particles. The stillest
        // category — its listening is nearly motionless.
        Style("abyss", "Abyss — deep water", 0xFF1B3A8C.toInt(), 0.40f, 0.88f, 0f,
            0.88f, 1.18f, 1.35f, 1.12f, 0.60f, 0.72f),
        // Bioluminescent: green/gold, crisp, with the accent turned just far enough
        // to read as a second organism inside the first.
        Style("verdant", "Verdant — bioluminescent", 0xFF2FE08A.toInt(), 0.34f, 1.06f, 24f,
            1f, 0.96f, 1f, 1f, 1.10f, 0.95f),
        // Split spectrum: the accent hue is thrown to the far side of the wheel, so
        // the swarm is always two opposed colours summing to white where it is dense.
        Style("prism", "Prism — split spectrum", 0x00000000, 0f, 1.25f, 150f,
            1.02f, 0.92f, 0.95f, 1f, 1.20f, 1.05f),
        // 1-bit: no hue at all. Hard little points on black, bloom almost off — the
        // being as a plotter drawing. Also the cheapest thing here.
        Style("ink", "Ink — 1-bit", 0xFFFFFFFF.toInt(), 0.28f, 0.06f, 0f,
            1.15f, 0.55f, 0.34f, 0.82f, 0.90f, 0.90f),
        // Trails: each particle drags its own recent past. The one heavy category —
        // 2x blits — so it is opt-in and labelled.
        Style("comet", "Comet — trails (heavier)", 0xFF9BD8FF.toInt(), 0.16f, 1.05f, 0f,
            1f, 0.90f, 1.05f, 0.92f, 1f, 1.10f, trails = true)
    )

    /** Stable pref tokens, in picker order. */
    val TOKENS: Array<String> = Array(STYLES.size) { STYLES[it].token }
    /** Human labels, parallel to [TOKENS]. */
    val LABELS: Array<String> = Array(STYLES.size) { STYLES[it].label }

    /** The style for a stored token. An unknown/blank/stale token is the DEFAULT,
     *  never a crash and never a blank being. */
    fun of(token: String?): Style {
        val t = token?.lowercase() ?: return STYLES[0]
        for (s in STYLES) if (s.token == t) return s
        return STYLES[0]
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
    fun shade(color: Int, s: Style, accent: Boolean): Int {
        var v = if (s.tintAmt > 0f) mix(color, s.tint, s.tintAmt) else color or 0xFF000000.toInt()
        if (s.sat != 1f) v = saturate(v, s.sat)
        if (accent && s.accentTurn != 0f) v = turn(v, s.accentTurn)
        return v
    }
}
