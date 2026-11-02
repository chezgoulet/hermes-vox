package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #372 — the crawl's *drawn* contrast, both segments, both grounds, measured.
 *
 * [LightModeContrastTest] pins resource colours; that is why it reported #0F172A at
 * 16.36:1 on paper and stayed green while the view painted the unread tail at 35 %
 * — a #A3A7B0 composite, 2.21:1, invisible. A token test cannot see a `Paint.alpha`.
 *
 * This test composites `ink over ground at alpha/255` for the alpha [CrawlInk] hands
 * to the paint (the same constants and the same functions [CrawlView] draws with), over
 * every line position the view can draw — the whole top-fade ramp, not just full fade —
 * and asserts the floors:
 *
 *   spoken (AA floor 4.5:1, and 16.36:1 at full fade — do not regress)
 *   unread tail (AA floor 4.5:1 on paper; night keeps the shipped 0.35 by contract)
 *
 * The grounds and inks are read from the resource tables, so a token change is felt
 * here too. Lowering [CrawlInk.LIGHT_UNSPOKEN_ALPHA] back under the AA threshold
 * fails [the_light_weight_may_not_go_back_under_the_aa_threshold].
 */
class CrawlDrawContrastTest {

    // The two grounds' tokens, read from values/ and values-night/ (not restated).
    private val paper = resColor("hv_bg", night = false)     // #F3F5F9
    private val black = resColor("hv_bg", night = true)      // #000000
    private val lightInk = resColor("hv_crawl_reply", night = false)  // #0F172A
    private val nightInk = resColor("hv_crawl_reply", night = true)   // #D6F4FF

    private val textFloor = 4.5f

    /** The two paint alphas (spoken, tail) a line drawn at [screenY] gets. */
    private fun drawnAt(screenY: Float, height: Float, light: Boolean): Pair<Int, Int> {
        val fadeBand = height * 0.62f                 // CrawlView's band
        val a = CrawlInk.lineAlpha(screenY, fadeBand, light)
        val spoken = CrawlInk.spokenAlpha(a)
        val tail = CrawlInk.tailAlpha(spoken, a, light)
        return spoken to tail
    }

    /** Every (screenY, view height) the view can draw a line at: the whole ramp. */
    private fun ramp(light: Boolean, block: (spoken: Int, tail: Int) -> Unit) {
        var height = 240f
        while (height <= 2400f) {
            var screenY = -50f                        // CrawlView's skip threshold
            while (screenY <= height) {
                val (spoken, tail) = drawnAt(screenY, height, light)
                block(spoken, tail)
                screenY += 1f
            }
            height += 120f
        }
    }

    private fun seen(ink: Int, ground: Int, alpha: Int) =
        Contrast.over(ink, ground, alpha / 255f)

    private fun ratio(ink: Int, ground: Int, alpha: Int) = Contrast.ratio(seen(ink, ground, alpha), ground)

    // ---- paper: both segments, every line -------------------------------------

    @Test fun the_unread_tail_clears_4_5_on_paper_at_every_line_the_view_draws() {
        var worst = Float.MAX_VALUE
        var worstAlpha = 0
        ramp(light = true) { _, tail ->
            val r = ratio(lightInk, paper, tail)
            if (r < worst) { worst = r; worstAlpha = tail }
        }
        assertTrue("paper tail worst case is ${"%.2f".format(worst)}:1 at alpha $worstAlpha " +
            "(floor $textFloor) — the unread words are invisible again", worst >= textFloor)
    }

    @Test fun the_spoken_segment_keeps_full_contrast_on_paper() {
        // At full fade the spoken text is the raw ink: exactly the 16.36:1 the token
        // test measured, and the fix must not have moved it.
        val full = drawnAt(screenY = 5000f, height = 1000f, light = true).first
        assertEquals(255, full)
        assertEquals("spoken at full fade regressed", 16.36f, ratio(lightInk, paper, full), 0.01f)

        // And the fade may soften it, but not under AAA anywhere the view draws it.
        var worst = Float.MAX_VALUE
        ramp(light = true) { spoken, _ ->
            worst = minOf(worst, ratio(lightInk, paper, spoken))
        }
        assertTrue("paper spoken worst case ${"%.2f".format(worst)}:1 is under AAA (7:1)", worst >= 7f)
    }

    @Test fun the_split_survives_on_paper() {
        // The tail has to stay *distinguishable* from the spoken text at every line,
        // or "what has been said" is no longer visible in the transcript.
        ramp(light = true) { spoken, tail ->
            assertTrue("tail alpha $tail is not below spoken $spoken on paper", tail < spoken)
            assertTrue("tail alpha $tail is not drawn at all", tail > 0)
        }
    }

    // ---- night: the shipped render, pinned ------------------------------------

    @Test fun the_night_render_is_byte_identical() {
        assertEquals(0.35f, CrawlInk.NIGHT_UNSPOKEN_ALPHA, 0f)
        assertEquals(0f, CrawlInk.NIGHT_LINE_FLOOR, 0f)
        var height = 240f
        while (height <= 2400f) {
            var screenY = -50f
            while (screenY <= height) {
                val fadeBand = height * 0.62f
                // The pre-#372 expressions, written out: night must not move by a bit.
                val a = (screenY / fadeBand).coerceIn(0f, 1f)
                val bright = (255 * a).toInt()
                val dim = (bright * 0.35f).toInt()
                assertEquals("night line alpha moved at y=$screenY h=$height",
                    a, CrawlInk.lineAlpha(screenY, fadeBand, light = false), 0f)
                assertEquals("night spoken alpha moved at y=$screenY h=$height",
                    bright, CrawlInk.spokenAlpha(a))
                assertEquals("night tail alpha moved at y=$screenY h=$height",
                    dim, CrawlInk.tailAlpha(bright, a, light = false))
                screenY += 1f
            }
            height += 120f
        }
        // The shipped night numbers, for the record: the pale ink at 35 % is the glow.
        val (spoken, tail) = drawnAt(screenY = 5000f, height = 1000f, light = false)
        assertEquals(18.24f, ratio(nightInk, black, spoken), 0.01f)
        assertEquals(2.73f, ratio(nightInk, black, tail), 0.01f)
    }

    // ---- the guard that makes this test bite ----------------------------------

    @Test fun the_light_weight_may_not_go_back_under_the_aa_threshold() {
        // The smallest alpha whose composite on paper clears AA, found by bisection.
        var lo = 0f
        var hi = 1f
        repeat(64) {
            val mid = (lo + hi) / 2f
            if (ratio(lightInk, paper, (255 * mid).toInt()) < textFloor) lo = mid else hi = mid
        }
        val threshold = (255 * hi).toInt()
        assertTrue("CrawlInk.LIGHT_UNSPOKEN_ALPHA (${CrawlInk.LIGHT_UNSPOKEN_ALPHA}) is under the " +
            "AA threshold (${"%.3f".format(hi)}, alpha $threshold)", CrawlInk.LIGHT_UNSPOKEN_ALPHA >= hi)
        // The value this issue fixed was 0.35 — under the threshold, i.e. this test fails
        // if anyone lowers the weight back toward it.
        assertTrue("the shipped 0.35 must be under the floor, or this test proves nothing",
            ratio(lightInk, paper, 89) < textFloor)
    }

    @Test fun the_view_paints_through_crawlink_and_not_its_own_numbers() {
        // The point of #372: a token test passed while the view drew 35 %. Keep the
        // drawn alphas in one place, so this file measures what the view paints.
        val src = listOf(File("src/main/java/com/hermesvox/CrawlView.kt"),
            File("android/app/src/main/java/com/hermesvox/CrawlView.kt"))
            .firstOrNull { it.isFile }
            ?: throw IllegalStateException("cannot find CrawlView.kt from ${File(".").absolutePath}")
        val text = src.readText()
        assertTrue("CrawlView does not take its line alpha from CrawlInk",
            text.contains("CrawlInk.lineAlpha("))
        assertTrue("CrawlView does not take its tail alpha from CrawlInk",
            text.contains("CrawlInk.tailAlpha("))
    }

    // ---- the numbers, printed (the PR's table) --------------------------------

    @Test fun the_draw_time_contrast_table() {
        println("ground,segment,alpha,before_ratio,after_ratio")
        for ((ground, ink, bg) in listOf(
            Triple("paper", lightInk, paper),
            Triple("night", nightInk, black))) {
            val light = ground == "paper"
            val (spoken, tail) = drawnAt(screenY = 5000f, height = 1000f, light)
            // "before" is what shipped: the 0.35 tail weight on BOTH grounds.
            val beforeTail = (spoken * 0.35f).toInt()
            println("%s,spoken,%d,%.2f,%.2f".format(
                ground, spoken, ratio(ink, bg, spoken), ratio(ink, bg, spoken)))
            println("%s,tail,%d,%.2f,%.2f".format(
                ground, tail, ratio(ink, bg, beforeTail), ratio(ink, bg, tail)))
        }
        // The top-fade line: on paper the pre-fix render faded it toward the page, so
        // the first visible line was 0.00 alpha (1.00:1) whatever the tail weight was.
        val before = ((255 * (0f / (1000f * 0.62f))).toInt() * 0.35f).toInt()
        val (_, after) = drawnAt(screenY = 0f, height = 1000f, light = true)
        println("paper,tail_at_top_fade,0,%.2f,%.2f".format(
            ratio(lightInk, paper, before), ratio(lightInk, paper, after)))
    }

    // ---- helpers --------------------------------------------------------------

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
