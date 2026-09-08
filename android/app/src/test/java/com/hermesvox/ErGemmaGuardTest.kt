package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ErGemmaGuard — the render rails. Under test: char cap, spacing, blank
 * refusal, main-thread refusal, and the pass-through of honest renders.
 */
class ErGemmaGuardTest {

    @Test fun honest_render_passes() {
        val out = ErGemmaGuard.checkRender("Just a sec — let me look that up.", 10_000L, 0L, onMain = false)
        assertEquals("Just a sec — let me look that up.", out)
    }

    @Test fun runaway_render_is_capped_not_dropped() {
        val ramble = "well ".repeat(300)   // 1500 chars
        val out = ErGemmaGuard.checkRender(ramble, 10_000L, 0L, onMain = false)
        assertEquals(ErGemmaGuard.MAX_RENDER_CHARS, out!!.length)
    }

    @Test fun blank_render_is_refused() {
        assertNull(ErGemmaGuard.checkRender("  ", 10_000L, 0L, onMain = false))
    }

    @Test fun main_thread_render_is_refused() {
        assertNull(ErGemmaGuard.checkRender("fine text", 10_000L, 0L, onMain = true))
    }

    @Test fun rapid_rerender_is_refused() {
        assertNull(ErGemmaGuard.checkRender("again", 10_500L, 10_000L, onMain = false))
        // Past the spacing window: allowed.
        assertTrue(ErGemmaGuard.checkRender("again", 11_300L, 10_000L, onMain = false) != null)
    }
}
