package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.1 Part A — the visual CATEGORY table. Three things have to hold or the feature
 * is either a regression or a lie:
 *
 *  1. the default category is an EXACT identity, so an untouched install renders what
 *     0.5.0.3 rendered;
 *  2. the categories are genuinely different from each other on the axes that matter
 *     (palette, light, mass, motion) — not one family relabelled six times;
 *  3. the per-frame work is negligible, because the frame budget is the one thing the
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
        assertFalse("the default must be a light category", d.heavy)
    }

    @Test fun unknown_stale_or_blank_tokens_fall_back_to_the_default() {
        assertEquals(VisualStyle.DEFAULT, VisualStyle.of(null).token)
        assertEquals(VisualStyle.DEFAULT, VisualStyle.of("").token)
        assertEquals(VisualStyle.DEFAULT, VisualStyle.of("aura").token)        // a THEME token, not a category
        assertEquals(VisualStyle.DEFAULT, VisualStyle.of("nonsense").token)
    }

    @Test fun tokens_are_stable_unique_and_label_parallel() {
        assertEquals(VisualStyle.TOKENS.size, VisualStyle.LABELS.size)
        assertEquals(VisualStyle.TOKENS.size, VisualStyle.TOKENS.toSet().size)
        assertTrue(VisualStyle.TOKENS.size >= 6)
        VisualStyle.TOKENS.forEachIndexed { i, t ->
            assertEquals(VisualStyle.LABELS[i], VisualStyle.labelOf(t))
            assertEquals(t, VisualStyle.of(t).token)
        }
    }

    // ---- 2. the categories are actually diverse ----

    @Test fun every_category_renders_a_distinct_palette() {
        val seen = HashSet<Int>()
        for (t in VisualStyle.TOKENS) seen.add(VisualStyle.shade(listen, VisualStyle.of(t), false))
        // Comet and Lumen share a near-neutral tint on purpose (Comet's difference is
        // the trail, not the hue), so allow one collision — no more.
        assertTrue("categories must not collapse onto one palette: $seen",
            seen.size >= VisualStyle.TOKENS.size - 1)
    }

    @Test fun categories_differ_on_motion_light_and_mass_not_just_colour() {
        val energies = VisualStyle.TOKENS.map { VisualStyle.of(it).energy }.toSet()
        val halos = VisualStyle.TOKENS.map { VisualStyle.of(it).halo }.toSet()
        val edges = VisualStyle.TOKENS.map { VisualStyle.of(it).edge }.toSet()
        assertTrue("motion character must vary", energies.size >= 4)
        assertTrue("bloom must vary", halos.size >= 4)
        assertTrue("the light model must vary", edges.size >= 4)
        // The spectrum has a genuinely slow end and a genuinely fast one.
        assertTrue(energies.min() <= 0.8f)
        assertTrue(energies.max() >= 1.15f)
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

    // ---- 3. the frame budget ----

    @Test fun per_frame_style_work_is_negligible() {
        // What a frame actually spends on the category: resolve the style, shade the
        // two eased colours. (Everything else a category changes is either baked into
        // a cached sprite or a multiply the loop was already doing.)
        val reps = 200_000
        var sink = 0
        repeat(2000) {                                   // warm the JIT
            val s = VisualStyle.of("prism")
            sink += VisualStyle.shade(listen, s, false) + VisualStyle.shade(listen, s, true)
        }
        val t0 = System.nanoTime()
        for (i in 0 until reps) {
            val s = VisualStyle.of(VisualStyle.TOKENS[i and 3])
            sink += VisualStyle.shade(listen, s, false) + VisualStyle.shade(think, s, true)
        }
        val perFrameNs = (System.nanoTime() - t0).toDouble() / reps
        assertNotEquals(0, sink)                          // keep the work alive
        println("A_COST per-frame VisualStyle work = %.3f us (budget 33333 us)".format(perFrameNs / 1000.0))
        // Two orders of magnitude of slack over any plausible machine: this asserts
        // "not a frame cost", not a benchmark number.
        assertTrue("per-frame style work must stay under 50us, was ${perFrameNs / 1000.0}us",
            perFrameNs < 50_000)
    }
}
