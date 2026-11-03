package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #243 — the interrupted reply as a record, and the one directive the next turn carries. */
class ResumeTest {

    private val answer = "First, open the settings. Second, choose Hearing. Third, turn on the switch."

    @Test fun `the mark splits what was heard from what was not`() {
        val m = Resume.mark(answer, 26, answer, finished = true, atMs = 0L)!!
        assertEquals("First, open the settings.", m.heard)
        assertEquals("Second, choose Hearing. Third, turn on the switch.", m.unsaid)
        assertTrue(m.finished)
    }

    @Test fun `the cut is placed by words when the voice text and the written text differ`() {
        // The voice's text had its whitespace folded; the entity's text did not.
        val written = "First,   open the settings.\n\nSecond, choose Hearing."
        assertEquals("Second, choose Hearing.", Resume.unsaidAfter("First, open the settings.", written))
        // Markup the voice never saw: placed by the heard tail.
        val marked = "**Steps:** First, open the settings. Second, choose Hearing."
        assertEquals("Second, choose Hearing.", Resume.unsaidAfter("First, open the settings.", marked))
    }

    @Test fun `a cut before the first word still records what was coming`() {
        val m = Resume.mark("", 0, answer, finished = false, atMs = 0L)!!
        assertEquals("", m.heard)
        assertEquals(answer, m.unsaid)
        val d = Resume.directive(m, 1L)!!
        assertTrue(d, d.contains("cut off before your first words"))
        assertTrue(d, d.contains("you had not finished writing it"))
    }

    @Test fun `nothing to interrupt leaves no mark`() {
        assertNull(Resume.mark("", 0, "", finished = false, atMs = 0L))
    }

    @Test fun `the directive says it straight and asks for no repeat`() {
        val d = Resume.directive(Resume.mark(answer, 26, answer, true, 0L)!!, 1_000L)!!
        assertTrue(d.startsWith("[You were interrupted."))
        assertTrue(d, d.contains("\"First, open the settings.\""))
        assertTrue(d, d.contains("They did not hear: \"Second, choose Hearing."))
        assertTrue(d, d.contains("Answer what they just said first"))
        assertTrue(d, d.contains("do not repeat what they already heard"))
        assertTrue(d, d.contains("drop it"))
        assertTrue("one line", !d.contains('\n'))
    }

    @Test fun `long sides are quoted, not replayed`() {
        val long = "word ".repeat(400).trim()
        val m = Resume.mark(long, 1_000, long, finished = true, atMs = 0L)!!
        val d = Resume.directive(m, 0L)!!
        assertTrue(d.length < Resume.HEARD_TAIL_CHARS + Resume.UNSAID_CHARS + 600)
        assertTrue(d.contains("…"))
    }

    @Test fun `the mark is used once and expires`() {
        val slot = Resume.Slot()
        slot.put(Resume.mark(answer, 26, answer, true, 0L))
        assertNotNull(slot.take(1_000L))
        assertNull("used once", slot.take(2_000L))
        slot.put(Resume.mark(answer, 26, answer, true, 0L))
        assertNull("stale", slot.take(Resume.TTL_MS + 1))
        assertNull("and gone", slot.take(0L))
    }

    @Test fun `only a voice barge is an interruption`() {
        assertTrue("barge-in" in Resume.BARGE_REASONS)
        assertFalse("hush" in Resume.BARGE_REASONS)
        assertFalse("stop" in Resume.BARGE_REASONS)
    }
}
