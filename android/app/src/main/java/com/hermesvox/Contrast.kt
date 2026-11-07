package com.hermesvox

import kotlin.math.pow

/**
 * Contrast — WCAG 2.2 relative luminance and contrast ratio, in plain ARGB integer maths.
 *
 * WHY THIS EXISTS (#275)
 * ----------------------
 * "The light-mode animations are hard to see" is a measurable claim, not a taste one.
 * WCAG 2.2 defines relative luminance and the ratio
 *
 *     (L1 + 0.05) / (L2 + 0.05),  L1 = the lighter of the two
 *
 * and the floors this app holds itself to are: 3:1 for a non-text body (SC 1.4.11),
 * 4.5:1 for text and for a body thin enough that its coverage is sparse (AA, SC 1.4.3).
 * The being's colours are composited, so the ratio is measured on the *composited*
 * colour over the real surface, not on the authored palette entry.
 *
 * No colour library: the sRGB → linear transfer is four lines, and the repo already
 * prefers plain ARGB integer maths (see [VisualStyle]). Pure JVM, so the palette and
 * the text tokens are proven off-device; [ContrastTest] pins the known WCAG values.
 */
object Contrast {

    /** One 8-bit sRGB channel to linear light, per WCAG 2.2 / IEC 61966-2-1. */
    fun linearize(channel: Int): Float {
        val s = channel.coerceIn(0, 255) / 255f
        return if (s <= 0.04045f) s / 12.92f
        else ((s + 0.055f) / 1.055f).pow(2.4f)
    }

    private fun r(c: Int) = (c ushr 16) and 0xFF
    private fun g(c: Int) = (c ushr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF

    /** WCAG relative luminance of an opaque colour. 0 = black, 1 = white. */
    fun relativeLuminance(argb: Int): Float =
        0.2126f * linearize(r(argb)) +
            0.7152f * linearize(g(argb)) +
            0.0722f * linearize(b(argb))

    /** The WCAG contrast ratio between two opaque colours, always >= 1. */
    fun ratio(a: Int, b: Int): Float {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        val hi = if (la >= lb) la else lb
        val lo = if (la >= lb) lb else la
        return (hi + 0.05f) / (lo + 0.05f)
    }

    /**
     * [fg] composited over the opaque [bg] at [alpha] (0..1), sRGB-space — the way the
     * being's ink is laid on paper (`out = bg + (fg - bg) * alpha`), and the way a
     * translucent UI scrim sits on the page. Alpha in [fg]'s top byte is ignored;
     * [alpha] is explicit so a caller can test the actual coverage it draws.
     */
    fun over(fg: Int, bg: Int, alpha: Float): Int {
        val f = alpha.coerceIn(0f, 1f)
        val rr = (r(bg) + (r(fg) - r(bg)) * f).toInt().coerceIn(0, 255)
        val gg = (g(bg) + (g(fg) - g(bg)) * f).toInt().coerceIn(0, 255)
        val bb = (b(bg) + (b(fg) - b(bg)) * f).toInt().coerceIn(0, 255)
        return 0xFF000000.toInt() or (rr shl 16) or (gg shl 8) or bb
    }

    /** [fg]'s own alpha over [bg], for resource scrims whose alpha is in the token. */
    fun overToken(fg: Int, bg: Int): Int =
        over(fg, bg, ((fg ushr 24) and 0xFF) / 255f)
}
