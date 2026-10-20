package com.hermesvox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capture loop's glue guard. A wrong answer in either direction is silent: too
 * eager and the microphone never opens, too lax and the entity transcribes its own
 * greeting as the caller (0.9.1 field, Pixel 9).
 */
class GlueWindowTest {

    @Test
    fun `glue playing is always in the window`() {
        val w = GlueWindow()
        assertTrue(w.inWindow(glueSpeaking = true, nowMs = 0L, skipMs = 700L))
        assertTrue(w.inWindow(glueSpeaking = true, nowMs = 60_000L, skipMs = 700L))
    }

    @Test
    fun `the tail after glue stops is inside the window`() {
        val w = GlueWindow()
        assertTrue(w.inWindow(true, 1_000L, 700L))
        assertTrue(w.inWindow(false, 1_100L, 700L))   // just stopped
        assertTrue(w.inWindow(false, 1_699L, 700L))   // still inside 700 ms
    }

    @Test
    fun `past the tail the microphone reopens`() {
        val w = GlueWindow()
        assertTrue(w.inWindow(true, 1_000L, 700L))
        assertFalse(w.inWindow(false, 1_701L, 700L))  // one ms past
        assertFalse(w.inWindow(false, 5_000L, 700L))
    }

    @Test
    fun `a zero skip means no tail`() {
        val w = GlueWindow()
        assertTrue(w.inWindow(true, 1_000L, 0L))
        assertFalse("0 disables the tail entirely", w.inWindow(false, 1_001L, 0L))
        assertFalse(w.inWindow(false, 10_000L, 0L))
    }

    @Test
    fun `no glue ever means the window never opens`() {
        val w = GlueWindow()
        assertFalse(w.inWindow(false, 0L, 700L))
        assertFalse(w.inWindow(false, 50_000L, 700L))
    }

    @Test
    fun `a second glue phrase restarts the window`() {
        val w = GlueWindow()
        assertTrue(w.inWindow(true, 1_000L, 700L))
        assertFalse(w.inWindow(false, 2_000L, 700L))   // tail expired
        assertTrue(w.inWindow(true, 3_000L, 700L))     // filler starts
        assertTrue("tail runs from the SECOND phrase", w.inWindow(false, 3_500L, 700L))
    }
}
