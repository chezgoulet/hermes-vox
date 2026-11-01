package com.hermesvox

import kotlin.math.exp
import kotlin.math.max

/**
 * LightInk — the light ground's composite (#275), on the JVM.
 *
 * On black the being is light: additive sprites, bloom, a filmic tone curve. In light
 * mode the being is INK ON PAPER — the specified blend, mirrored here and in
 * [BeingShaders.FRAGMENT_COMPOSITE]:
 *
 *     out = paper + (ink - paper) * cover        cover = f(emission luminance) * MAX_COVER
 *
 * A darker emission is a denser mark, never a brighter one. The tone-mapped luminance
 * still decides *how much* paper a pixel covers, but the hue is normalised by its own
 * MAX CHANNEL and placed at the family's authored [VisualStyle.Light.value] — so a deep
 * hue (Abyss' indigo) stays deep, and a high-luma hue (a green) cannot exceed the
 * authored value and clip toward white. The "sums to white" families (Prism, Solar)
 * become dense centres instead of holes in the page.
 *
 * This object exists to prove the shader's light branch off-device: the strings in
 * [LightInkTest] pin the GLSL, and the contrast table is computed with [map] and
 * [composite]. The dark branch is untouched: at `uInk = 0` the composite is exactly
 * the 0.9.0 output.
 */
object LightInk {

    /** Coverage ceiling: even the densest core lets a little paper through, so the
     *  being can never punch a black hole in the page. */
    const val MAX_COVER = 0.92f

    /** The coverage the body's contrast floor is measured at. This is the dense part of
     *  the body (the shader can reach [MAX_COVER]); measuring here states what the eye
     *  reads as the body colour, not what a single faint particle reads as. */
    const val BODY_COVER = 0.85f

    private fun lum(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b

    /**
     * Map one composited emission sample (pre-tone-map RGB, 0..1+ per channel) onto
     * [paper] for a family whose authored ink is [inkValue] dark and whose [coverGain]
     * decides how fast emission becomes coverage. Mirrors the light branch of
     * [BeingShaders.FRAGMENT_COMPOSITE] op-for-op.
     */
    fun map(r: Float, g: Float, b: Float, paper: FloatArray,
            inkValue: Float = 0.4f, coverGain: Float = 1.6f): FloatArray {
        val l = max(lum(r, g, b), 1e-4f)
        val lt = 1f - exp(-l * 1.4f)
        // The shader normalises the tone-mapped hue by its MAX CHANNEL (uniform scaling
        // cancels), so the authored ink value is the body's value. Keep that exactly;
        // normalising by luma here would flatter the dark/high-luma families.
        val m = max(max(r, g), max(b, 1e-4f))
        val hue = floatArrayOf(r / m, g / m, b / m)
        val cover = (lt * coverGain).coerceIn(0f, 1f) * MAX_COVER
        return FloatArray(3) { i ->
            val ink = (hue[i] * inkValue).coerceIn(0f, 1f)
            paper[i] + (ink - paper[i]) * cover
        }
    }

    /** The occluded page a dense body shows: [ink] over [paper] at [cover]. */
    fun composite(ink: Int, paper: Int, cover: Float = BODY_COVER): Int =
        Contrast.over(ink, paper, cover)

    /** The family's body ink as the contrast test sees it. */
    fun bodyInk(color: Int, s: VisualStyle.Style, accent: Boolean): Int =
        composite(VisualStyle.ink(color, s, accent), VisualStyle.PAPER_LIGHT)
}
