package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.1 Part A — the visual CATEGORY table — extended for 0.5.2 (A1 massively expanded
 * palette, A3 cycle-all). The things that have to hold or the feature is a regression or
 * a lie:
 *
 *  1. the default category is an EXACT identity, so an untouched install renders what
 *     0.5.0.3 rendered;
 *  2. the categories are genuinely different from each other on the axes that matter
 *     (palette, light, mass, motion) — Christopher's test: not one family relabelled, and
 *     never a pure recolour of another;
 *  3. the eased-scalar shade path the crossfade runs on is the SAME math as the Style
 *     path, so a cycle-all transition converges exactly on each target family;
 *  4. the cycle-all members are consistent and default OFF (a fixed, light category);
 *  5. the per-frame work is negligible, because the frame budget is the one thing the
 *     renderer is not allowed to spend.
 */
class VisualStyleTest {

    private val listen = 0xFF3ED598.toInt()   // the authored LISTENING base
    private val think = 0xFFFFB43D.toInt()    // the authored THINKING base

    // ---- 1. the default is the old look, bit for bit ----

    @Test fun default_category_is_an_exact_identity() {
        val d = VisualStyle.of(VisualStyle.DEFAULT)
        assertEquals(listen, VisualStyle.shade(listen, d, false))
        assertEquals(listen, VisualStyle.shade(listen, d, true))
        assertEquals(think, VisualStyle.shade(think, d, false))
        assertEquals(1f, d.energy, 0f)
        assertEquals(1f, d.halo, 0f)
        assertEquals(1f, d.size, 0f)
        assertEquals(1f, d.flicker, 0f)
        assertEquals(1f, d.coreHeat, 0f)
        assertEquals(1f, d.edge, 0f)
        assertEquals(0f, d.tintAmt, 0f)
        assertEquals(1f, d.sat, 0f)
        assertEquals(0f, d.accentTurn, 0f)
        assertFalse("the default must be a light category", d.heavy)
    }

    @Test fun unknown_stale_or_blank_tokens_fall_back_to_the_default() {
        assertEquals(VisualStyle.DEFAULT, VisualStyle.of(null).token)
        assertEquals(VisualStyle.DEFAULT, VisualStyle.of("").token)
        // "iris" is an idle-SHAPE theme token, not a category — it must not resolve to one.
        // (Note: "aura" is BOTH a theme token and, since 0.5.2, a category; the two live in
        // separate prefs — particles_theme vs visual_category — so there is no collision.)
        assertEquals(VisualStyle.DEFAULT, VisualStyle.of("iris").token)
        assertEquals(VisualStyle.DEFAULT, VisualStyle.of("nonsense").token)
    }

    // ---- A2: the table is expanded and every entry is exposed ----

    @Test fun tokens_are_stable_unique_and_label_parallel() {
        assertEquals(VisualStyle.TOKENS.size, VisualStyle.LABELS.size)
        assertEquals(VisualStyle.TOKENS.size, VisualStyle.TOKENS.toSet().size)
        assertEquals(VisualStyle.TOKENS.size, VisualStyle.SIZE)
        // A1 "massively expanded": a dozen+ distinct families, up from 7 in 0.5.1.
        assertTrue("palette must be massively expanded (was 7), got ${VisualStyle.TOKENS.size}",
            VisualStyle.TOKENS.size >= 12)
        VisualStyle.TOKENS.forEachIndexed { i, t ->
            assertEquals(VisualStyle.LABELS[i], VisualStyle.labelOf(t))
            assertEquals(t, VisualStyle.of(t).token)
            assertEquals(i, VisualStyle.indexOf(t))    // the picker exposes every slot
        }
    }

    @Test fun the_named_families_from_the_spec_all_ship() {
        // The spec named these as the bar; the designer authored the rest around them.
        val named = listOf("ember", "nebula", "ripple", "aura", "lumen", "aurora",
            "ink", "prism", "raven", "tide", "sculpt", "static")
        for (n in named) assertTrue("missing named family: $n", VisualStyle.TOKENS.contains(n))
    }

    // ---- 2. the categories are actually diverse, not recolours ----

    @Test fun every_category_renders_a_distinct_palette() {
        val seen = HashSet<Int>()
        for (t in VisualStyle.TOKENS) seen.add(VisualStyle.shade(listen, VisualStyle.of(t), false))
        // Lumen is the only base-identity now; allow at most one accidental collision.
        assertTrue("categories must not collapse onto one palette: ${seen.size}/${VisualStyle.TOKENS.size}",
            seen.size >= VisualStyle.TOKENS.size - 1)
    }

    @Test fun no_category_is_a_pure_recolour_of_another() {
        // Christopher's test, made precise: two families must differ on more than hue. The
        // NON-colour axes are the light model (coreHeat, edge), the mass (halo, size) and
        // the motion (flicker, energy) + trails. If two shared ALL of those they would be
        // the same being wearing a different colour. Assert every family has a distinct
        // light+mass+motion signature.
        val sigs = HashSet<String>()
        for (t in VisualStyle.TOKENS) {
            val s = VisualStyle.of(t)
            sigs.add("${s.coreHeat}|${s.edge}|${s.halo}|${s.size}|${s.flicker}|${s.energy}|${s.trails}")
        }
        assertEquals("a category is a pure recolour of another (same light+mass+motion)",
            VisualStyle.TOKENS.size, sigs.size)
    }

    @Test fun categories_differ_on_motion_light_and_mass_not_just_colour() {
        val energies = VisualStyle.TOKENS.map { VisualStyle.of(it).energy }.toSet()
        val halos = VisualStyle.TOKENS.map { VisualStyle.of(it).halo }.toSet()
        val edges = VisualStyle.TOKENS.map { VisualStyle.of(it).edge }.toSet()
        val flicks = VisualStyle.TOKENS.map { VisualStyle.of(it).flicker }.toSet()
        assertTrue("motion character must vary widely", energies.size >= 8)
        assertTrue("bloom must vary widely", halos.size >= 8)
        assertTrue("the light model must vary widely", edges.size >= 8)
        assertTrue("the flicker signature must vary", flicks.size >= 8)
        // The spectrum has a genuinely slow end and a genuinely fast one.
        assertTrue(energies.min() <= 0.72f)
        assertTrue(energies.max() >= 1.40f)
    }

    @Test fun ink_is_monochrome_and_prism_splits_the_accent_from_the_base() {
        val ink = VisualStyle.of("ink")
        val c = VisualStyle.shade(listen, ink, false)
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        assertTrue("ink must be near-greyscale, got r=$r g=$g b=$b",
            maxOf(r, g, b) - minOf(r, g, b) <= 12)

        val prism = VisualStyle.of("prism")
        val base = VisualStyle.shade(listen, prism, false)
        val accent = VisualStyle.shade(listen, prism, true)
        assertNotEquals("prism's accent must leave the base hue", base, accent)
    }

    @Test fun only_the_trail_category_is_heavy_and_it_is_not_the_default() {
        val heavy = VisualStyle.TOKENS.filter { VisualStyle.of(it).heavy }
        assertEquals(listOf("comet"), heavy)
        assertTrue(VisualStyle.of("comet").trails)
        assertFalse(VisualStyle.of(VisualStyle.DEFAULT).trails)
    }

    // ---- 3. the crossfade converges: eased-scalar shade == Style shade ----

    @Test fun eased_scalar_shade_matches_the_style_shade_exactly() {
        // AvatarView crossfades by easing the axes and shading through the scalar overload.
        // At ease fraction 1.0 the render-style equals vsty, so the two paths must agree
        // bit-for-bit for every category — otherwise the cycle would drift off-palette.
        for (t in VisualStyle.TOKENS) {
            val s = VisualStyle.of(t)
            for (c in intArrayOf(listen, think, 0xFF000000.toInt(), 0xFFFFFFFF.toInt())) {
                for (acc in booleanArrayOf(false, true)) {
                    assertEquals("$t acc=$acc",
                        VisualStyle.shade(c, s, acc),
                        VisualStyle.shade(c, s.tintAmt, s.tint, s.sat, s.accentTurn, acc))
                }
            }
        }
    }

    // ---- 4. cycle-all (A3) ----

    @Test fun cycle_all_prefs_and_helpers_are_consistent() {
        assertTrue(VisualStyle.KEY_CYCLE_ALL.isNotBlank())
        assertFalse("cycle-all must default OFF (a fixed, light category)",
            VisualStyle.DEFAULT_CYCLE_ALL)
        assertTrue("the dwell must be positive", VisualStyle.CYCLE_ALL_SEC > 0f)
        assertEquals("hv", VisualStyle.PREFS_NAME)
        // indexOf never returns -1: an unknown/blank token starts the rotation at slot 0.
        assertEquals(0, VisualStyle.indexOf(null))
        assertEquals(0, VisualStyle.indexOf(""))
        assertEquals(0, VisualStyle.indexOf("nonsense"))
    }

    // ---- the user scalars ----

    @Test fun user_scalars_clamp_to_their_slider_range() {
        assertEquals(VisualStyle.ENERGY_MIN, VisualStyle.energy(-5f), 0f)
        assertEquals(VisualStyle.ENERGY_MAX, VisualStyle.energy(99f), 0f)
        assertEquals(1.2f, VisualStyle.energy(1.2f), 1e-6f)
        assertEquals(VisualStyle.GLOW_MIN, VisualStyle.glow(0f), 0f)
        assertEquals(VisualStyle.GLOW_MAX, VisualStyle.glow(9f), 0f)
    }

    @Test fun colour_math_is_bounded_and_opaque_for_every_category() {
        for (t in VisualStyle.TOKENS) {
            val s = VisualStyle.of(t)
            for (c in intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), listen, think)) {
                for (acc in booleanArrayOf(false, true)) {
                    val v = VisualStyle.shade(c, s, acc)
                    assertEquals("alpha must stay opaque ($t)", 0xFF, (v ushr 24) and 0xFF)
                    for (sh in intArrayOf(16, 8, 0)) {
                        val ch = (v shr sh) and 0xFF
                        assertTrue("channel out of range ($t): $ch", ch in 0..255)
                    }
                }
            }
        }
    }

    // ---- 5. the frame budget (A1_COST) ----

    @Test fun per_frame_category_work_is_negligible() {
        // What ONE frame spends on the category in 0.5.2, end to end:
        //   easeStyle      10 scalar lerps + 1 colour lerp
        //   resolvePalette 2 eased VisualStyle.shade calls (the real code)
        //   spriteKey x3   colour quantise + 2 light-model buckets each
        // All once per frame, all primitives, NONE of it inside the 320-particle loop.
        // The sprite/colour math that lives in AvatarView (Android, not JVM-testable) is
        // replicated here op-for-op so the cost is measurable; shade() is called for real.
        val reps = 200_000
        val tgt = VisualStyle.of("nebula")     // an arbitrary family to ease toward
        // eased render-style, starting at the default identity like the real fields
        var tA = 0f; var sat = 1f; var turn = 0f; var heat = 1f; var edge = 1f
        var halo = 1f; var size = 1f; var flick = 1f; var energy = 1f; var tint = 0
        var sink = 0
        repeat(3000) {                          // warm the JIT
            val k = 0.04f
            tA += (tgt.tintAmt - tA) * k; sat += (tgt.sat - sat) * k
            turn += (tgt.accentTurn - turn) * k; heat += (tgt.coreHeat - heat) * k
            edge += (tgt.edge - edge) * k; tint = lerpRgb(tint, tgt.tint, k)
            sink += VisualStyle.shade(listen, tA, tint, sat, turn, false)
        }
        val t0 = System.nanoTime()
        for (i in 0 until reps) {
            val k = 0.04f                       // a fixed ease step (~dt * STYLE_EASE)
            // easeStyle
            tA += (tgt.tintAmt - tA) * k
            sat += (tgt.sat - sat) * k
            turn += (tgt.accentTurn - turn) * k
            heat += (tgt.coreHeat - heat) * k
            edge += (tgt.edge - edge) * k
            halo += (tgt.halo - halo) * k
            size += (tgt.size - size) * k
            flick += (tgt.flicker - flick) * k
            energy += (tgt.energy - energy) * k
            tint = lerpRgb(tint, tgt.tint, k)
            // resolvePalette: the two real eased shade calls
            sink += VisualStyle.shade(listen, tA, tint, sat, turn, false)
            sink += VisualStyle.shade(think, tA, tint, sat, turn, true)
            // spriteKey x3 (glowFor base, glowFor accent, haloFor base)
            sink += spriteKeyRep(listen, edge, heat)
            sink += spriteKeyRep(think, edge, heat)
            sink += spriteKeyRep(listen, edge, heat)
            // keep the mass/motion scalars alive (they are the loop's two multiplies)
            sink += (halo * size * flick * energy).toInt()
        }
        val perFrameNs = (System.nanoTime() - t0).toDouble() / reps
        assertNotEquals(0, sink)                // keep the work alive
        val us = perFrameNs / 1000.0
        println("A1_COST per-frame category work (easeStyle + 2 shade + 3 spriteKey) = " +
            "%.3f us/frame = %.5f%% of the 33333 us budget".format(us, us / 33333.0 * 100.0))
        // Two orders of magnitude of slack over any plausible machine: this asserts
        // "not a frame cost", not a benchmark number.
        assertTrue("per-frame category work must stay under 50us, was ${us}us", perFrameNs < 50_000)
    }

    // ---- replicated AvatarView math (op-for-op) so the cost is JVM-measurable ----

    /** AvatarView.lerpColor without android.graphics.Color: 3 channel lerps -> packed RGB. */
    private fun lerpRgb(a: Int, b: Int, f: Float): Int {
        val ff = if (f < 0f) 0f else if (f > 1f) 1f else f
        val r = (((a ushr 16) and 0xFF) * (1 - ff) + ((b ushr 16) and 0xFF) * ff).toInt()
        val g = (((a ushr 8) and 0xFF) * (1 - ff) + ((b ushr 8) and 0xFF) * ff).toInt()
        val bl = ((a and 0xFF) * (1 - ff) + (b and 0xFF) * ff).toInt()
        return 0xFF000000.toInt() or (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or bl.coerceIn(0, 255)
    }

    /** AvatarView.spriteKey + lightBucket: colour high-nibbles + 2 quantised light buckets. */
    private fun spriteKeyRep(color: Int, edge: Float, heat: Float): Int {
        val rn = (color ushr 20) and 0xF
        val gn = (color ushr 12) and 0xF
        val bn = (color ushr 4) and 0xF
        val eb = (((edge - 0.40f) / (1.45f - 0.40f) * 3.999f).toInt()).coerceIn(0, 3)
        val hb = (((heat - 0.60f) / (1.35f - 0.60f) * 3.999f).toInt()).coerceIn(0, 3)
        return (rn shl 16) or (gn shl 12) or (bn shl 8) or (eb shl 2) or hb
    }
}
