package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #275 — the light ground's contract, measured.
 *
 * The owner's complaint ("the light mode animations are still hard to see") and the
 * issue's ("a black box is painted") are the same defect seen from two sides: the being
 * had one ground and it was black. This test makes the fix a property of the TABLE, not
 * of a screenshot: every family, in both grounds and every state a call shows, has to
 * clear the WCAG floors on the surface it is actually drawn on.
 *
 * Floors: 4.5:1 for a thin body or any text (AA, SC 1.4.3), 3:1 for a solid body
 * (SC 1.4.11). The light body is measured after the specified ink blend at
 * [LightInk.BODY_COVER] — the coverage the dense part of the body reaches — so the
 * number is the colour a person sees, not the raw palette entry.
 *
 * A future family cannot ship invisible on white: [every_family_has_an_authored_light_variant]
 * fails first, and the two exhaustive loops fail with the family/state named.
 */
class LightModeContrastTest {

    private val paper = VisualStyle.PAPER_LIGHT
    private val black = 0xFF000000.toInt()

    /** The authored state palettes (AvatarView.resolvePalette), body + accent. */
    private class State(val name: String, val body: Int, val accent: Int)
    private val states = listOf(
        State("listening", 0xFF3ED598.toInt(), 0xFFB9F6DE.toInt()),
        State("thinking", 0xFFFFB43D.toInt(), 0xFFFF6A2E.toInt()),
        State("speaking", 0xFF9B6BFF.toInt(), 0xFFE2CCFF.toInt()),
        State("stalled", 0xFF4E7FE8.toInt(), 0xFF93B7FF.toInt()),
        State("idle", 0xFF5FA8C4.toInt(), 0xFFA6E6F5.toInt()),
        State("streaming", 0xFF2AC3DC.toInt(), 0xFFA9F1FF.toInt()),
        State("recoil", 0xFFF3FBFF.toInt(), 0xFFFFDCA8.toInt()),
    )

    /** A body is "thin" when its points are small or its falloff is hard: the same
     *  coverage reveals less of the page, so it must clear the TEXT floor, not the
     *  non-text one. */
    private fun thin(s: VisualStyle.Style): Boolean = s.size <= 0.9f || s.edge <= 0.75f
    private fun floor(s: VisualStyle.Style): Float = if (thin(s)) 4.5f else 3f

    // ---- the table is complete ------------------------------------------------

    @Test fun every_family_has_an_authored_light_variant() {
        assertEquals(VisualStyle.SIZE, VisualStyle.TOKENS.size)
        for (t in VisualStyle.TOKENS) {
            val s = VisualStyle.of(t)
            assertEquals("family $t resolved to a different token", t, s.token)
            // A variant that is all zeroes is a placeholder, not a design.
            assertTrue("$t has no light ink value", s.light.value in 0.15f..0.5f)
            assertTrue("$t has no light coverage gain", s.light.cover in 1f..3f)
        }
        // And the paper the table is authored against is the real light surface token.
        assertEquals("values/ values-night/ diverged: light hv_bg must equal PAPER_LIGHT",
            VisualStyle.PAPER_LIGHT, resColor("hv_bg", night = false))
    }

    // ---- LIGHT: every family, every state, body and accent --------------------

    @Test fun every_family_is_visible_on_the_light_ground() {
        val failures = ArrayList<String>()
        for (t in VisualStyle.TOKENS) {
            val s = VisualStyle.of(t)
            for (st in states) {
                for ((role, colour) in listOf("body" to st.body, "accent" to st.accent)) {
                    val ink = VisualStyle.ink(colour, s, role == "accent")
                    val onPage = LightInk.bodyInk(colour, s, role == "accent")
                    val r = Contrast.ratio(onPage, paper)
                    if (r < floor(s)) {
                        failures += "$t/${st.name}/$role ${"%.2f".format(r)}:1 " +
                            "(ink #%06X on #%06X, floor %.1f)".format(ink and 0xFFFFFF, paper and 0xFFFFFF, floor(s))
                    }
                    // The floor above is the contract; this is the absolute minimum stated
                    // by SC 1.4.11 and must hold even for families we do not call thin.
                    assertTrue("$t/${st.name}/$role is under 3:1 on paper", r >= 3f)
                }
            }
        }
        assertTrue("light-ground contrast failures:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun the_light_ink_is_authored_not_an_inversion() {
        // A luminance inversion of a deep hue is a pale grey — the original defect. Every
        // family must produce a DARK mark (luminance below the paper's) whose hue still
        // belongs to the family, and the authored ink must not simply be the dark colour
        // lightened.
        for (t in VisualStyle.TOKENS) {
            val s = VisualStyle.of(t)
            val ink = VisualStyle.ink(states[0].body, s, false)
            assertTrue("$t's light ink must be darker than the paper",
                Contrast.relativeLuminance(ink) < Contrast.relativeLuminance(paper))
            assertNotEquals("$t's light ink is the dark body unchanged — that is the black box",
                VisualStyle.shade(states[0].body, s, false), ink)
        }
    }

    // ---- DARK: the same iterations, on the ground that shipped -----------------

    @Test fun every_family_stays_visible_on_the_dark_ground() {
        // The dark scheme is unchanged (pixel-identical by construction: no dark field was
        // touched). Its dominant body is the sprite's lit centre; FRAGMENT_POINT adds
        // exp(0) * 0.8 * uCore to the base, so the centre is the base at
        // (0.7 + 0.8 * coreHeat). Measuring the raw base would call a deliberately dim
        // family invisible when its centre is not.
        val failures = ArrayList<String>()
        for (t in VisualStyle.TOKENS) {
            val s = VisualStyle.of(t)
            for (st in states) {
                for ((role, colour) in listOf("body" to st.body, "accent" to st.accent)) {
                    val shaded = VisualStyle.shade(colour, s, role == "accent")
                    val core = scale(shaded, 0.7f + 0.8f * s.coreHeat)
                    val r = Contrast.ratio(core, black)
                    if (r < floor(s)) failures += "$t/${st.name}/$role ${"%.2f".format(r)}:1"
                    assertTrue("$t/${st.name}/$role is under 3:1 on black", r >= 3f)
                }
            }
        }
        assertTrue("dark-ground contrast failures:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    // ---- the words and controls follow the ground too -------------------------

    @Test fun every_text_token_clears_4_5_on_its_real_background() {
        val both = listOf("hv_text", "hv_text_dim", "hv_cyan", "hv_status", "hv_status_live",
            "hv_ok", "hv_warn", "hv_danger", "hv_call_idle", "hv_call_live",
            "hv_cmd_done", "hv_cmd_failed", "hv_crawl_reply", "hv_overlay_text",
            "hv_overlay_dim", "hv_verdict_ok", "hv_verdict_cold", "hv_verdict_fail")
        // The dev SSE console keeps its 0.9.0.3 dark colour (#5C6B7D, 3.85:1 on black):
        // it is diagnostic-only and changing it would move the dark scheme, which this
        // issue does not touch. Its LIGHT value is fixed with everything else (4.96:1).
        val lightOnly = listOf("hv_crawl_dim")
        for (night in listOf(false, true)) {
            val ground = if (night) "night" else "light"
            val bg = resColor("hv_bg", night)
            val chip = resColor("hv_surface_high", night)
            for (tok in both + if (night) emptyList<String>() else lightOnly) {
                val c = resColor(tok, night)
                for ((where, back) in listOf("page" to bg, "chip" to chip)) {
                    val r = Contrast.ratio(c, back)
                    assertTrue("$ground/$tok is ${"%.2f".format(r)}:1 on the $where " +
                        "(#%06X on #%06X, floor 4.5)".format(c and 0xFFFFFF, back and 0xFFFFFF),
                        r >= 4.5f)
                }
            }
        }
    }

    @Test fun the_dark_theme_text_tokens_are_the_shipped_ones() {
        // #275 changed only the light ground; these are the night values from before it,
        // so the hero scheme is provably untouched (not just "looks the same").
        assertEquals(0xFF00E5FF.toInt(), resColor("hv_cyan", night = true))
        assertEquals(0xFFE6EDF7.toInt(), resColor("hv_text", night = true))
        assertEquals(0xFF8B98AC.toInt(), resColor("hv_text_dim", night = true))
        assertEquals(0xFF35D07F.toInt(), resColor("hv_call_idle", night = true))
        assertEquals(0xFFFF5B5B.toInt(), resColor("hv_call_live", night = true))
        // The UI that used to be hardcoded also keeps its exact dark values.
        assertEquals(0xFFD6F4FF.toInt(), resColor("hv_crawl_reply", night = true))
        assertEquals(0xFF5C6B7D.toInt(), resColor("hv_crawl_dim", night = true))
        assertEquals(0xFF06070B.toInt(), resColor("hv_overlay_bg", night = true))
        assertEquals(0xFFD6F4FF.toInt(), resColor("hv_overlay_text", night = true))
        assertEquals(0xFF9FB3C9.toInt(), resColor("hv_overlay_dim", night = true))
        assertEquals(0xFF35D07F.toInt(), resColor("hv_verdict_ok", night = true))
        assertEquals(0xFFFFB43D.toInt(), resColor("hv_verdict_cold", night = true))
        assertEquals(0xFFFF5B5B.toInt(), resColor("hv_verdict_fail", night = true))
    }

    @Test fun the_scrim_is_the_dark_theme_blackout_and_a_light_paper_wash() {
        assertTrue("light scrim must be a paper wash, not a blackout",
            Contrast.relativeLuminance(resColor("hv_scrim", night = false)) >
                Contrast.relativeLuminance(resColor("hv_scrim", night = true)))
        assertEquals(0xE606070B.toInt(), resColor("hv_scrim", night = true))
    }

    /**
     * The table in the PR, computed here rather than eyeballed. "Before" is the 0.9.0.3
     * light branch: the dark-shaded hue normalised by its luma, scaled by 0.5, covered at
     * 1.6 × the tone-mapped luminance. "After" is this branch at the measured body
     * coverage. Worst case over every state (body and accent) is the honest per-family
     * number: a family cannot look fine in listening and vanish in speaking.
     */
    @Test fun contrast_table_is_measured_not_eyeballed() {
        println("family,before_body_min,after_body_listening,after_body_min,after_accent_min,dark_min")
        for (t in VisualStyle.TOKENS) {
            val s = VisualStyle.of(t)
            var beforeMin = Float.MAX_VALUE
            var afterBodyMin = Float.MAX_VALUE
            var afterAccMin = Float.MAX_VALUE
            var darkMin = Float.MAX_VALUE
            var afterListen = 0f
            for (st in states) {
                val before = Contrast.ratio(beforeInk(VisualStyle.shade(st.body, s, false)), paper)
                val afterBody = Contrast.ratio(LightInk.bodyInk(st.body, s, false), paper)
                val afterAcc = Contrast.ratio(LightInk.bodyInk(st.accent, s, true), paper)
                val dark = Contrast.ratio(scale(VisualStyle.shade(st.body, s, false), 0.7f + 0.8f * s.coreHeat), black)
                beforeMin = minOf(beforeMin, before)
                afterBodyMin = minOf(afterBodyMin, afterBody)
                afterAccMin = minOf(afterAccMin, afterAcc)
                darkMin = minOf(darkMin, dark)
                if (st.name == "listening") afterListen = afterBody
            }
            println("%s,%.2f,%.2f,%.2f,%.2f,%.2f".format(
                t, beforeMin, afterListen, afterBodyMin, afterAccMin, darkMin))
            assertTrue("$t must clear its floor", afterBodyMin >= floor(s) && afterAccMin >= floor(s))
        }
    }

    /** The 0.9.0.3 light composite, kept only to measure the defect it shipped. */
    private fun beforeInk(shaded: Int): Int {
        fun ch(sh: Int) = ((shaded shr sh) and 0xFF) / 255f
        val r = ch(16); val g = ch(8); val b = ch(0)
        val lum = maxOf(0.2126f * r + 0.7152f * g + 0.0722f * b, 1e-4f)
        val lt = 1f - kotlin.math.exp(-lum * 1.4f)
        val cover = (lt * 1.6f).coerceIn(0f, 1f) * 0.92f
        fun mix(paperCh: Int, hue: Float): Int {
            val ink = (hue * 0.5f).coerceIn(0f, 1f)
            return (paperCh / 255f + (ink - paperCh / 255f) * cover).times(255f).toInt().coerceIn(0, 255)
        }
        return 0xFF000000.toInt() or (mix((paper ushr 16) and 0xFF, r / lum) shl 16) or
            (mix((paper ushr 8) and 0xFF, g / lum) shl 8) or mix(paper and 0xFF, b / lum)
    }

    // ---- helpers --------------------------------------------------------------

    /** Lighten/darken by scaling channels (the sprite's centre term), sRGB-space. */
    private fun scale(c: Int, k: Float): Int {
        fun ch(sh: Int) = (((c shr sh) and 0xFF) * k).toInt().coerceIn(0, 255)
        return 0xFF000000.toInt() or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun resColor(name: String, night: Boolean): Int {
        val dir = if (night) "values-night" else "values"
        val f = listOf(File("src/main/res/$dir/colors.xml"),
            File("android/app/src/main/res/$dir/colors.xml")).firstOrNull { it.isFile }
            ?: throw IllegalStateException("cannot find $dir/colors.xml from ${File(".").absolutePath}")
        val xml = f.readText()
        val m = Regex("""<color name="$name">#([0-9A-Fa-f]{6,8})</color>""").find(xml)
            ?: throw AssertionError("$name missing from ${f.path}")
        val hex = m.groupValues[1]
        val v = hex.toLong(16)
        return if (hex.length == 8) v.toInt() else (0xFF000000L or v).toInt()
    }
}
