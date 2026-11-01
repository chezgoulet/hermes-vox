package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The WCAG 2.2 helper itself. These are the published anchors, so a future "fix" to the
 * transfer function cannot quietly move every contrast floor in the app.
 */
class ContrastTest {

    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()

    @Test fun blackOnWhiteIsTheTheoreticalMaximum() {
        assertEquals(21f, Contrast.ratio(black, white), 1e-4f)
        assertEquals(21f, Contrast.ratio(white, black), 1e-4f)
    }

    @Test fun aColourAgainstItselfIsOne() {
        assertEquals(1f, Contrast.ratio(white, white), 1e-6f)
        assertEquals(1f, Contrast.ratio(0xFF3ED598.toInt(), 0xFF3ED598.toInt()), 1e-6f)
    }

    @Test fun theRatioIsSymmetric() {
        val a = 0xFF0F172A.toInt()
        val b = 0xFFF3F5F9.toInt()
        assertEquals(Contrast.ratio(a, b), Contrast.ratio(b, a), 1e-6f)
    }

    @Test fun relativeLuminanceRunsFromZeroToOne() {
        assertEquals(0f, Contrast.relativeLuminance(black), 1e-6f)
        assertEquals(1f, Contrast.relativeLuminance(white), 1e-6f)
        // Green is weighted far above blue by definition.
        assertTrue(Contrast.relativeLuminance(0xFF00FF00.toInt()) >
            Contrast.relativeLuminance(0xFF0000FF.toInt()))
    }

    @Test fun thePublishedWcagAnchorsHold() {
        // #777777 on white is the canonical 4.48:1; #767676 is the 4.5:1 boundary.
        assertEquals(4.478f, Contrast.ratio(0xFF777777.toInt(), white), 0.01f)
        assertTrue(Contrast.ratio(0xFF767676.toInt(), white) >= 4.5f)
        // Pure blue on white is the canonical 8.59:1.
        assertEquals(8.59f, Contrast.ratio(0xFF0000FF.toInt(), white), 0.02f)
    }

    @Test fun compositingIsTheSpecifiedPaperBlend() {
        // Half black over white is mid-grey in sRGB space.
        val mid = Contrast.over(black, white, 0.5f)
        assertEquals(0xFF7F7F7F.toInt(), mid)
        // Coverage 0 returns the paper exactly; coverage 1 returns the ink exactly.
        assertEquals(white, Contrast.over(black, white, 0f))
        assertEquals(black, Contrast.over(black, white, 1f))
        // A token's own alpha is honoured by overToken (the coach scrim).
        val scrim = 0xD9F3F5F9.toInt()
        val onPage = Contrast.overToken(scrim, 0xFF06070B.toInt())
        assertTrue(Contrast.ratio(onPage, 0xFF06070B.toInt()) > 1f)
    }
}
