package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #12 (the vanish) — the words that interrupt a turn become the next turn: the
 * carry starts at the speech onset minus pre-roll, keeps everything after the
 * barge decision, and seeds the next segment unless the barge was an ER HOLD.
 */
class BargeCarryTest {

    private val sr = 16000
    private val read = 1024   // the drain's 64 ms read

    private fun frames(v: Float) = FloatArray(read) { v }

    @Test fun carry_starts_at_the_onset_minus_pre_roll_and_keeps_every_later_frame() {
        val c = BargeCarry(sr, preRollMs = 128, historyMs = 2000)
        repeat(10) { c.push(frames(0.01f), speechLike = false) }   // 640 ms of reply/room
        repeat(4) { c.push(frames(0.5f), speechLike = true) }      // the user starts: onset
        c.fire()                                                   // barge decides after 256 ms
        repeat(6) { c.push(frames(0.6f), speechLike = true) }      // the user keeps talking
        val out = c.take()!!
        // 2 pre-roll reads + 4 onset reads + 6 post-fire reads, nothing dropped.
        assertEquals(12 * read, out.size)
        assertEquals(0.01f, out[0])                                // pre-roll
        assertEquals(0.5f, out[2 * read])                          // the first word's first sample
        assertEquals(0.6f, out.last())                             // the words after the decision
    }

    @Test fun a_brief_dip_does_not_move_the_onset() {
        val c = BargeCarry(sr, preRollMs = 0, historyMs = 2000, onsetQuietMs = 400)
        repeat(3) { c.push(frames(0.5f), true) }
        repeat(2) { c.push(frames(0.0f), false) }                  // 128 ms between words
        repeat(3) { c.push(frames(0.5f), true) }
        c.fire()
        assertEquals(8 * read, c.take()!!.size)                    // the run spans the dip
    }

    @Test fun a_long_quiet_resets_the_onset() {
        val c = BargeCarry(sr, preRollMs = 0, historyMs = 4000, onsetQuietMs = 400)
        repeat(3) { c.push(frames(0.5f), true) }                   // an old blip
        repeat(10) { c.push(frames(0.0f), false) }                 // 640 ms quiet: that run is over
        repeat(4) { c.push(frames(0.5f), true) }
        c.fire()
        assertEquals(4 * read, c.take()!!.size)
    }

    @Test fun history_bounds_the_lead() {
        val c = BargeCarry(sr, preRollMs = 300, historyMs = 1000)
        repeat(40) { c.push(frames(0.5f), true) }                  // 2.5 s of speech-like frames
        c.fire()
        assertEquals(sr * 1000 / 1000, c.take()!!.size)             // capped at the 1 s ring
    }

    @Test fun no_onset_keeps_the_last_second() {
        val c = BargeCarry(sr, preRollMs = 300, historyMs = 2000)
        repeat(30) { c.push(frames(0.1f), false) }                 // the level-only escape fired w/o an onset
        c.fire()
        assertEquals(sr, c.take()!!.size)
    }

    @Test fun take_resets_and_unfired_takes_nothing() {
        val c = BargeCarry(sr)
        repeat(5) { c.push(frames(0.5f), true) }
        assertFalse(c.fired)
        assertNull(c.take())
        c.push(frames(0.5f), true); c.fire()
        assertTrue(c.fired)
        c.take()
        assertFalse(c.fired)
        assertNull(c.take())
    }

    @Test fun carry_is_capped() {
        val c = BargeCarry(sr, preRollMs = 0, historyMs = 1000, maxMs = 2000)
        c.push(frames(0.5f), true); c.fire()
        repeat(100) { c.push(frames(0.5f), true) }                 // 6.4 s more
        assertEquals(2 * sr, c.take()!!.size)
    }

    @Test fun seed_rule() {
        assertTrue(BargeCarry.shouldSeed(fired = true, gateReleased = true, held = false))    // barge -> next turn
        assertFalse(BargeCarry.shouldSeed(fired = true, gateReleased = true, held = true))    // ER HOLD: a backchannel
        assertFalse(BargeCarry.shouldSeed(fired = true, gateReleased = false, held = false))  // stop / gate timeout
        assertFalse(BargeCarry.shouldSeed(fired = false, gateReleased = true, held = false))  // a natural turn end
    }
}
