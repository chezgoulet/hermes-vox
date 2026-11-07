package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #225: the fine-grained look — every knob a bounded scalar whose default is the identity. */
class VisualLookTest {

    @Test fun everyDefaultDrawsWhatAnUntouchedInstallDrew() {
        assertEquals(1f, VisualStyle.density(VisualStyle.DEFAULT_DENSITY))
        assertEquals(1f, VisualStyle.speed(VisualStyle.DEFAULT_SPEED))
        assertEquals(0f, VisualStyle.hue(VisualStyle.DEFAULT_HUE))
        // The default trail keeps the category's own, whatever it is.
        assertEquals(0f, VisualStyle.trails(0f, VisualStyle.DEFAULT_TRAIL))
        assertEquals(0.78f, VisualStyle.trails(0.78f, VisualStyle.DEFAULT_TRAIL))
        assertEquals(6000, VisualStyle.drawCount(6000, VisualStyle.DEFAULT_DENSITY))
        // The colour turn at 0 is the identity.
        assertEquals(0xFF3ED598.toInt(), VisualStyle.turn(0xFF3ED598.toInt(), 0f))
    }

    @Test fun eachKnobIsBounded() {
        assertEquals(VisualStyle.DENSITY_MIN, VisualStyle.density(0f))
        assertEquals(VisualStyle.SPEED_MAX, VisualStyle.speed(9f))
        assertEquals(VisualStyle.TRAIL_MAX, VisualStyle.trail(5f))
        assertEquals(VisualStyle.HUE_MIN, VisualStyle.hue(-999f))
    }

    @Test fun densityIsAPrefixOfTheBufferAndNeverEmpty() {
        assertEquals(1800, VisualStyle.drawCount(6000, 0.3f))
        assertEquals(1, VisualStyle.drawCount(1, 0.3f))
    }

    @Test fun aUserTrailAddsToACategoryWithNone() {
        assertEquals(0.5f, VisualStyle.trails(0f, 0.5f))
        assertTrue(VisualStyle.trails(0.78f, 0.5f) == 0.78f)   // never shortens the category's own
    }

    @Test fun theHueTurnMovesTheColour() {
        val green = 0xFF3ED598.toInt()
        val turned = VisualStyle.turn(green, 120f)
        assertTrue(turned != green)
    }
}
