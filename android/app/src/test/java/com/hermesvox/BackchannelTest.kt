package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #245 — when the being may acknowledge while the caller is still talking. */
class BackchannelTest {

    private val B = Backchannel
    private fun m(
        now: Long, start: Long = 1_000L, silent: Long = 300L, end: Long = 800L,
        partial: String = "and then we went over to the", sem: SemanticEndpoint.Verdict? = null, busy: Boolean = false,
    ) = Backchannel.Moment(now, start, silent, end, partial, sem, busy)

    @Test fun `a long talker in a mid-thought pause gets one`() {
        assertEquals(Backchannel.Why.FIRE, B.decide(m(now = 9_000L), Backchannel.Rate.SPARSE, Backchannel.Ledger()))
    }

    @Test fun `every refusal names its reason`() {
        val l = Backchannel.Ledger()
        assertEquals(Backchannel.Why.OFF, B.decide(m(9_000L), Backchannel.Rate.OFF, l))
        assertEquals(Backchannel.Why.NOT_TALKING, B.decide(m(9_000L, start = 0L), Backchannel.Rate.SPARSE, l))
        assertEquals(Backchannel.Why.BUSY, B.decide(m(9_000L, busy = true), Backchannel.Rate.SPARSE, l))
        assertEquals(Backchannel.Why.TOO_SOON_IN_TURN, B.decide(m(5_000L), Backchannel.Rate.SPARSE, l))
        assertEquals(Backchannel.Why.NO_PAUSE, B.decide(m(9_000L, silent = 100L), Backchannel.Rate.SPARSE, l))
        assertEquals(Backchannel.Why.AT_BOUNDARY, B.decide(m(9_000L, silent = 500L), Backchannel.Rate.SPARSE, l))
        assertEquals(Backchannel.Why.READS_FINISHED,
            B.decide(m(9_000L, partial = "that is what I wanted"), Backchannel.Rate.SPARSE, l))
        assertEquals(Backchannel.Why.READS_FINISHED, B.decide(m(9_000L, partial = ""), Backchannel.Rate.SPARSE, l))
        assertEquals(Backchannel.Why.LEANING_FINAL,
            B.decide(m(9_000L, sem = SemanticEndpoint.Verdict.PROVISIONAL), Backchannel.Rate.SPARSE, l))
    }

    @Test fun `the ceiling stays inside a short end-of-turn silence`() {
        // A user who ends turns on 400 ms of silence: the window closes at 240 ms, before 250 —
        // so no acknowledgement is ever possible rather than one landing on the boundary.
        assertEquals(240L, B.pauseCeilingMs(400L))
        assertEquals(Backchannel.Why.AT_BOUNDARY, B.decide(m(9_000L, silent = 260L, end = 400L), Backchannel.Rate.NORMAL, Backchannel.Ledger()))
    }

    @Test fun `the gap and the per-minute cap hold`() {
        val l = Backchannel.Ledger()
        assertTrue(B.tryFire(m(9_000L), Backchannel.Rate.NORMAL, l))
        assertEquals(Backchannel.Why.GAP, B.decide(m(15_000L), Backchannel.Rate.NORMAL, l))
        assertTrue(B.tryFire(m(19_100L), Backchannel.Rate.NORMAL, l))
        assertTrue(B.tryFire(m(29_200L), Backchannel.Rate.NORMAL, l))
        assertTrue(B.tryFire(m(39_300L), Backchannel.Rate.NORMAL, l))
        assertEquals(Backchannel.Why.CAP, B.decide(m(49_400L), Backchannel.Rate.NORMAL, l))
        // A minute after the first, the window has room again.
        assertEquals(Backchannel.Why.FIRE, B.decide(m(69_100L), Backchannel.Rate.NORMAL, l))
    }

    @Test fun `its own sound does not reset the pause`() {
        val l = Backchannel.Ledger()
        assertTrue(B.tryFire(m(9_000L), Backchannel.Rate.SPARSE, l))
        assertTrue(B.vadMuted(9_000L + B.MUTE_MS - 1, l))
        assertFalse(B.vadMuted(9_000L + B.MUTE_MS, l))
    }

    @Test fun `the rate setting reads back and defaults sparse`() {
        assertEquals(Backchannel.Rate.SPARSE, Backchannel.Rate.of(null))
        assertEquals(Backchannel.Rate.SPARSE, Backchannel.Rate.of("nonsense"))
        Backchannel.Rate.entries.forEach { assertEquals(it, Backchannel.Rate.of(it.key)) }
    }

    /**
     * The acceptance shape, simulated: a 70 s monologue at 100 ms frames, with hesitation
     * pauses (after "and"/"so"), clause-end pauses, and one turn-ending pause. The loop
     * drives [Backchannel] exactly as the capture loop does, including the VAD mute.
     */
    @Test fun `a recorded-shape monologue gets a few acknowledgements, all mid-thought, none at the end`() {
        val end = 800L
        val l = Backchannel.Ledger()
        val start = 1_000L
        val fired = ArrayList<Long>()
        // (talk ms, pause ms, words before the pause)
        val script = ArrayList<Triple<Long, Long, String>>()
        repeat(14) { i ->
            script += Triple(3_000L, 350L, "and")          // a hesitation: plausible point
            script += Triple(1_500L, 450L, "it was fine")  // a clause end: never
            if (i % 3 == 0) script += Triple(500L, 150L, "um")  // too short a pause
        }
        script += Triple(2_000L, 900L, "that's all")       // the turn ends here
        var t = start
        var silent = 0L
        var talkEndsAt = 0L
        for ((talk, pause, words) in script) {
            val talkEnd = t + talk
            while (t < talkEnd) { if (!B.vadMuted(t, l)) silent = 0; t += 100 }
            val pauseEnd = t + pause
            while (t < pauseEnd) {
                silent += 100
                if (silent > end) break
                if (B.tryFire(m(t, start, silent, end, partial = "so we $words"), Backchannel.Rate.SPARSE, l)) fired += t
                t += 100
            }
            talkEndsAt = t
        }
        assertTrue("a 70 s monologue should get at least one: $fired", fired.isNotEmpty())
        assertTrue("sparse means at most two a minute: $fired", fired.size <= 2 * ((talkEndsAt - start) / 60_000 + 1))
        fired.zipWithNext().forEach { (a, b) -> assertTrue("gap $a→$b", b - a >= Backchannel.Rate.SPARSE.minGapMs) }
        assertTrue("none in the first ${B.MIN_TALK_MS} ms", fired.all { it - start >= B.MIN_TALK_MS })
        assertTrue("none in the final pause", fired.all { it < talkEndsAt - 900L })
    }
}
