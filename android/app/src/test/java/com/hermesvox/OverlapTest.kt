package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #242 — what a barge carried decides what happens after the cut. */
class OverlapTest {

    @Test fun `a listening sound is a backchannel`() {
        for (s in listOf("mm-hm", "uh-huh", "right", "yeah", "okay", "go on", "okay, go on", "mm"))
            assertEquals(s, Overlap.Kind.BACKCHANNEL, Overlap.classify(s))
    }

    @Test fun `a word or two is a barge`() {
        for (s in listOf("wait", "hold on", "stop", "no", "sorry", "hang on", "Wait!"))
            assertEquals(s, Overlap.Kind.BARGE, Overlap.classify(s))
    }

    @Test fun `a full sentence is a new turn`() {
        for (s in listOf("what about tomorrow instead", "can you check my calendar",
                "okay what's the weather", "wait, I meant the other file"))
            assertEquals(s, Overlap.Kind.NEW_TURN, Overlap.classify(s))
    }

    @Test fun `thin evidence stops and listens, never builds a turn`() {
        assertEquals(Overlap.Kind.BARGE, Overlap.classify(null))
        assertEquals(Overlap.Kind.BARGE, Overlap.classify("   "))
    }

    private val answer = "First, open the settings. Second, choose Hearing. Third, turn on the switch."

    @Test fun `a backchannel continues the cut reply, once`() {
        val slot = Resume.Slot()
        slot.put(Resume.mark(answer, 26, answer, true, 0L))
        assertTrue(slot.continueAfterBackchannel(10L))
        val (_, d) = slot.take(20L)!!
        assertTrue(d, d.startsWith("[The caller only made a listening sound"))
        assertTrue(d, d.contains("You still had to say: \"Second, choose Hearing."))
        assertTrue(d, d.contains("Do not repeat what they heard"))
        assertNull(slot.take(30L))
    }

    @Test fun `a backchannel with nothing left to say needs no turn`() {
        val slot = Resume.Slot()
        assertFalse("no mark", slot.continueAfterBackchannel(0L))
        slot.put(Resume.mark(answer, answer.length, answer, finished = true, atMs = 0L))
        assertFalse("they heard all of it", slot.continueAfterBackchannel(0L))
        slot.put(Resume.mark(answer, 26, answer, true, 0L))
        assertFalse("stale", slot.continueAfterBackchannel(Resume.TTL_MS + 1))
    }

    @Test fun `a barge keeps the mark for whatever comes next`() {
        val slot = Resume.Slot()
        slot.put(Resume.mark(answer, 26, answer, true, 0L))
        // A barge sends no turn and does not touch the slot; the next sentence carries the mark.
        assertTrue(slot.has(1_000L))
        val (_, d) = slot.take(2_000L)!!
        assertTrue(d, d.startsWith("[You were interrupted."))
    }
}
