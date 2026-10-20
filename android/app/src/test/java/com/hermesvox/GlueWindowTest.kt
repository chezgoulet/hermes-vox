package com.hermesvox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The capture loop's glue guard. A wrong answer in either direction is silent: too
 * eager and the microphone never opens, too lax and the entity transcribes its own
 * greeting as the caller (0.9.1 field, Pixel 9).
 *
 * Note the observation model: the tail is measured from the first read that *sees*
 * glue stopped, not from the instant it stopped. The capture loop calls this every
 * ~64 ms read, so in use that is within one frame — but the tests have to speak in
 * read times, not wall-clock intuition.
 */
class GlueWindowTest {

    @Test
    fun `glue playing is always in the window`() {
        val w = GlueWindow()
        assertTrue(w.inWindow(glueSpeaking = true, nowMs = 0L, skipMs = 700L))
        assertTrue(w.inWindow(glueSpeaking = true, nowMs = 60_000L, skipMs = 700L))
    }

    @Test
    fun `the tail runs from the read that observes glue stop`() {
        val w = GlueWindow()
        assertTrue(w.inWindow(true, 1_000L, 700L))
        assertTrue("first read after glue sets the tail", w.inWindow(false, 1_050L, 700L))
        assertTrue(w.inWindow(false, 1_700L, 700L))    // 650 ms into a 700 ms tail
    }

    @Test
    fun `past the tail the microphone reopens`() {
        val w = GlueWindow()
        assertTrue(w.inWindow(true, 1_000L, 700L))
        assertTrue(w.inWindow(false, 1_050L, 700L))
        assertFalse(w.inWindow(false, 1_751L, 700L))   // 701 ms after the observed stop
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
        assertTrue(w.inWindow(false, 1_050L, 700L))
        assertFalse(w.inWindow(false, 1_800L, 700L))   // tail expired
        assertTrue(w.inWindow(true, 3_000L, 700L))     // a filler starts
        assertTrue("the tail restarts for the second phrase", w.inWindow(false, 3_050L, 700L))
        assertFalse(w.inWindow(false, 3_800L, 700L))
    }
}
