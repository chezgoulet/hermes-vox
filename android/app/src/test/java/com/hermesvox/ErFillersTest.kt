package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ErFillers — the presence ladder (0.8/M2): the preamble beat, the NONVERBAL
 * middle rung (once per window, never words), the 4s fail-soft line (once), then
 * silence.
 *
 * Why the reachability test exists: a previous version set
 * SILENCE_FIRST_MS == LAG_AFTER_MS, which silently made the middle of the ladder
 * dead code — so on a healthy gateway the soul never made a sound, and Enhanced
 * Realtime was perceptually identical to Realtime. A test that asserts the rung is
 * REACHABLE is how that cannot happen twice.
 */
class ErFillersTest {

    private fun out(now: Long, recent: Int = 0, preambleSaid: Int = 0, lagSaid: Int = 0) =
        ErFillers.tick(
            nowMs = now, mindStartedAt = 10_000L, recentCount = recent,
            userGoneMs = 0L, preambleSaid = preambleSaid, lagSaidCount = lagSaid,
        )

    @Test fun preamble_beat_is_silent() {
        // <900ms: the mind may not have started; the motion is the ack.
        assertEquals(ErFillers.State.SILENT, out(10_000L + 400L).state)
        assertNull(out(10_000L + 400L).speak)
    }

    @Test fun no_words_under_four_seconds() {
        // THE sacrosanct rule (0.6.4/0.6.7): Piper is a prose reader and a
        // two-character interjection is its worst case. No TEXT before the
        // fail-soft slot — that part of Tier 0 was right and stays.
        for (t in listOf(1_000L, 1_500L, 2_000L, 3_000L, 3_900L)) {
            assertNull("no words under SILENCE_FIRST_MS at +$t", out(10_000L + t).speak)
        }
    }

    @Test fun the_middle_rung_is_reachable_and_nonverbal() {
        // The M2 fix: the cue fires in the 900ms-4s slot, ONCE, and carries no text.
        val o = out(10_000L + 1_000L)
        assertEquals(ErFillers.State.PREAMBLE, o.state)
        assertNull("the preamble cue is a recorded clip, never words", o.speak)
    }

    @Test fun the_preamble_cue_never_repeats_in_a_window() {
        assertEquals(ErFillers.State.SILENT, out(10_000L + 1_000L, preambleSaid = 1).state)
        assertEquals(ErFillers.State.SILENT, out(10_000L + 3_500L, preambleSaid = 1).state)
    }

    @Test fun the_density_cap_suppresses_the_preamble_cue() {
        // The user's "chatty" slider binds on the cue too.
        assertEquals(ErFillers.State.SILENT,
            out(10_000L + 1_000L, recent = ErFillers.MAX_FILLERS_PER_WINDOW).state)
    }

    @Test fun the_fail_soft_slot_is_not_where_the_cue_lives() {
        // Past LAG_AFTER_MS the tick must offer the LAG line, never the cue.
        assertEquals(ErFillers.State.LAG_ACK, out(10_000L + 5_000L).state)
    }

    @Test fun fail_soft_arrives_after_lag_threshold() {
        val o = out(10_000L + 5_000L)
        assertTrue("lag ack must be an in-character sentence",
            o.speak?.contains("mind's a bit slow") == true ||
                o.speak?.contains("working on it") == true ||
                o.speak?.contains("taking a little longer") == true)
        assertEquals(ErFillers.State.LAG_ACK, o.state)
    }

    @Test fun fail_soft_says_once_then_goes_silent() {
        // already said the lag line: the slot stays quiet.
        assertEquals(null, out(10_000L + 5_000L, recent = 1, lagSaid = 1).speak)
    }

    // 0.6.5: the lag line is ONCE per window — keyed to the MONOTONIC lag count,
    // not the 3s trailing count (which fell back to 0 and re-armed the fail-soft
    // every ~3s — the "same phrase over and over" the field heard).
    @Test fun lag_never_repeats_after_the_trailing_count_falls_back() {
        val o = ErFillers.tick(10_000L + 9_000L, 10_000L, recentCount = 0,
            userGoneMs = 19_000L, lagSaidCount = 1)
        assertEquals(null, o.speak)
        assertEquals(ErFillers.State.SILENT, o.state)
    }

    @Test fun count_recent_drops_stale_entries() {
        val now = 100_000L
        val times = listOf(now - 1000, now - 2000, now - 4000)   // third is stale
        assertEquals(2, ErFillers.countRecent(times, now))
    }

    @Test fun spoken_lines_never_carry_facts() {
        // The sacrosanct sweep: no SPOKEN line may teach or promise. (The middle
        // rung carries no line at all — it is a clip.)
        val all = listOf(
            "my mind's a bit slow right now, give me a sec",
            "still working on it — one more moment",
            "this one's taking a little longer",
        )
        val factish = Regex("(?i)\\b(is|are|was|will|costs|opens|closes)\\b|\\d")
        for (l in all) assertTrue("filler must not carry facts: $l", !factish.containsMatchIn(l))
    }
}
