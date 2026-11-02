package com.hermesvox

/**
 * CrawlInk — the alphas `CrawlView` actually paints with, per ground (#372).
 *
 * WHY THIS EXISTS
 * ---------------
 * The crawl splits every line at the speech cursor and paints the not-yet-spoken
 * tail at a fraction of the line's own alpha. That fraction is *contrast*, so it
 * cannot be one number for both grounds:
 *
 *   light  ink `hv_crawl_reply` #0F172A on `hv_bg` #F3F5F9
 *   night  ink `hv_crawl_reply` #D6F4FF on `hv_bg` #000000
 *
 * The shipped 0.35 was tuned on the night ground, where pale ink at 35 % reads as a
 * deliberate glow (2.73:1). The same 0.35 on paper composites to #A3A7B0 — 2.21:1,
 * i.e. an invisible tail, because dimming dark ink toward a light page *removes*
 * contrast. Measured with [Contrast.over] on those two grounds:
 *
 *   alpha on paper   0.35 -> 2.21:1   0.55 -> 3.93:1   0.60 -> 4.61:1   1.00 -> 16.36:1
 *   alpha on black   0.35 -> 2.73:1                                       1.00 -> 18.24:1
 *
 * So paper gets 0.60 (the first round weight over AA, 4.5:1) and the night ground
 * keeps 0.35 untouched. [CrawlDrawContrastTest] composites these exact constants —
 * not copies of them — for both segments on both grounds.
 *
 * THE TOP FADE
 * ------------
 * `CrawlView` multiplies both segments by a fade that goes to 0 near the top of the
 * view (so the words never run into the being). On paper that fade is the *same*
 * operation as the unspoken weight — it dims ink toward the page — so multiplying
 * the tail by it would take 4.61:1 back down to 2.2:1 on the first faded line. The
 * light ground therefore stops the fade at [LIGHT_LINE_FLOOR] (7.62:1 for the spoken
 * text, AAA) and paints the tail at its constant weight: on paper the fade may
 * soften the spoken words, never the unread tail. The night ground keeps the
 * shipped 0 → 1 ramp, byte-identical (see [lineAlpha] and [tailAlpha]).
 */
object CrawlInk {

    /** The unspoken tail's weight relative to the line's own fade alpha. The shipped
     *  value, tuned on black: pale ink at 35 % is a glow, not a defect. */
    const val NIGHT_UNSPOKEN_ALPHA = 0.35f

    /** Paper's weight: 4.61:1 composited. Anything lower drops the unread tail under
     *  AA (0.35 was 2.21:1) — see [CrawlDrawContrastTest], which fails if it shrinks. */
    const val LIGHT_UNSPOKEN_ALPHA = 0.60f

    /** Lowest line alpha the night ramp may reach: the shipped fade, untouched. */
    const val NIGHT_LINE_FLOOR = 0f

    /** Lowest line alpha on paper: 0.75 composites to 7.62:1, so the top of the block
     *  reads as soft text instead of dissolving into the page. */
    const val LIGHT_LINE_FLOOR = 0.75f

    /** The fade: 0 as a line nears the top (approaching the being), 1 at the bottom. */
    fun lineAlpha(screenY: Float, fadeBand: Float, light: Boolean): Float =
        (screenY / fadeBand).coerceIn(if (light) LIGHT_LINE_FLOOR else NIGHT_LINE_FLOOR, 1f)

    /** Paint alpha (0..255) of the spoken side of a line drawn at [lineAlpha]. */
    fun spokenAlpha(lineAlpha: Float): Int = (255 * lineAlpha).toInt()

    /**
     * Paint alpha (0..255) of the not-yet-spoken tail of a line drawn at [lineAlpha].
     *
     * [spokenAlpha] is an input because that is the shipped night arithmetic — it
     * multiplies the already-quantised 0..255 alpha (`bright * 0.35`) — and the night
     * render has to stay byte-identical. On paper the tail is a constant weight: the
     * fade cannot dim what is already at the floor.
     */
    fun tailAlpha(spokenAlpha: Int, lineAlpha: Float, light: Boolean): Int =
        if (light) (255 * maxOf(lineAlpha * LIGHT_UNSPOKEN_ALPHA, LIGHT_UNSPOKEN_ALPHA)).toInt()
        else (spokenAlpha * NIGHT_UNSPOKEN_ALPHA).toInt()
}
