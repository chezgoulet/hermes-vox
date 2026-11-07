package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #275: on the light theme the being is ink on the page, not light in a black box.
 *
 * These pin the SPECIFIED blend — `out = paper + (ink - paper) * cover` — and its
 * authored inputs: a family's ink value is how dark the body is, its coverage gain how
 * eagerly emission becomes ink. The dark branch is untouched (`uInk = 0`).
 */
class LightInkTest {

    private val paper = floatArrayOf(0xF3 / 255f, 0xF5 / 255f, 0xF9 / 255f)   // @color/hv_bg, light
    private fun lum(c: FloatArray) = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]

    @Test fun noEmissionIsThePaperItself() {
        // The empty field is the screen's own background: no box, no seam.
        val c = LightInk.map(0f, 0f, 0f, paper, 0.39f, 1.7f)
        for (i in 0..2) assertEquals(paper[i], c[i], 1e-3f)
    }

    @Test fun emissionDarkensThePageInsteadOfBrighteningIt() {
        val faint = LightInk.map(0.05f, 0.1f, 0.12f, paper, 0.39f, 1.7f)
        val dense = LightInk.map(1.5f, 2.5f, 3f, paper, 0.39f, 1.7f)
        assertTrue(lum(faint) < lum(paper))
        assertTrue("denser is darker", lum(dense) < lum(faint))
    }

    @Test fun theHueSurvives() {
        // The listening green stays green and the speaking violet stays violet on white.
        val green = LightInk.map(0.24f, 0.84f, 0.6f, paper, 0.32f, 2f)
        assertTrue(green[1] > green[0] && green[1] > green[2])
        val violet = LightInk.map(0.6f, 0.42f, 1f, paper, 0.32f, 2f)
        assertTrue(violet[2] > violet[0] && violet[2] > violet[1])
    }

    @Test fun evenTheHottestCoreLetsSomePaperThrough() {
        val core = LightInk.map(50f, 50f, 50f, paper, 0.39f, 1.7f)
        // The coverage ceiling is 92 %, so the page is never punched to black.
        assertTrue(lum(core) > 0.05f)
        assertTrue("never darker than the ink itself", lum(core) >= 0.39f - 0.02f)
    }

    @Test fun aDeeperAuthoredInkIsDarkerOnThePage() {
        val soft = LightInk.map(0.5f, 0.9f, 0.7f, paper, 0.45f, 1.6f)
        val deep = LightInk.map(0.5f, 0.9f, 0.7f, paper, 0.30f, 1.6f)
        assertTrue("lower authored value must be more ink", lum(deep) < lum(soft))
    }

    @Test fun theShaderHasTheSameLightBranch() {
        val fs = BeingShaders.FRAGMENT_COMPOSITE
        assertTrue(fs.contains("uniform vec3 uPaper;"))
        assertTrue(fs.contains("uniform float uInk, uInkValue, uInkCover;"))
        assertTrue(fs.contains("clamp(lt * uInkCover, 0.0, 1.0) * 0.92"))
        assertTrue(fs.contains("clamp(hue * uInkValue, 0.0, 1.0)"))
        assertTrue("hue is normalised by max channel, not luma",
            fs.contains("vec3 hue = c / max(max(c.r, max(c.g, c.b)), 1e-3);"))
        assertTrue(fs.contains("vec3 light = mix(uPaper, ink, cover);"))
        assertTrue("dark output unchanged at uInk = 0", fs.contains("o = vec4(mix(dark, light, uInk), 1.0);"))
    }
}
