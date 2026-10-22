package com.hermesvox

import kotlin.math.exp
import kotlin.math.max

/**
 * LightInk — the light theme's composite (#275), on the JVM.
 *
 * On black the being is light: additive sprites, bloom, a filmic tone curve. On the light
 * theme it used to be the same light inside a black window (the "black box" of #275). Now the
 * composite lays the same emission down as **ink on the page**: how much of the paper a pixel
 * covers follows the tone-mapped luminance, and its colour is the emission's hue at a darker
 * value — a glow becomes a wash, a hot core a dense mark. This mirrors the light branch of
 * [BeingShaders.FRAGMENT_COMPOSITE] so its rules are proven off-device; the two change together.
 */
object LightInk {

    /** The ink a fully covered pixel shows: the hue at this value. */
    const val INK_VALUE = 0.5f
    /** Coverage ceiling — even the densest core lets a little paper through. */
    const val MAX_COVER = 0.92f

    private fun lum(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b

    /** Map one composited emission sample (pre-tone-map RGB) onto [paper]. */
    fun map(r: Float, g: Float, b: Float, paper: FloatArray): FloatArray {
        val l = max(lum(r, g, b), 1e-4f)
        val lt = 1f - exp(-l * 1.4f)
        val k = lt / l
        val hue = floatArrayOf(r * k / max(lt, 1e-3f), g * k / max(lt, 1e-3f), b * k / max(lt, 1e-3f))
        val cover = (lt * 1.6f).coerceIn(0f, 1f) * MAX_COVER
        return FloatArray(3) { i ->
            val ink = (hue[i] * INK_VALUE).coerceIn(0f, 1f)
            paper[i] + (ink - paper[i]) * cover
        }
    }
}
