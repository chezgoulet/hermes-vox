package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ErFillers — the presence filler state machine (Miles rule #3): preamble
 * beat, density cap ≤2 per 3s, fail-soft lag acknowledgment after 4s, then
 * silence (the motion carries the presence).
 */
class ErFillersTest {

    private fun out(now: Long, recent: Int = 0, warm: Boolean = false) =
        ErFillers.tick(nowMs = now, mindStartedAt = 10_000L, recentCount = recent, warm = warm, userGoneMs = 0L)

    @Test fun preamble_beat_is_silent() {
        // <900ms: the soul hasn't "opened its mouth" yet.
        assertEquals(ErFillers.State.SILENT, out(10_000L + 400L).state)
        assertEquals(null, out(10_000L + 400L).speak)
    }

    @Test fun filler_emits_after_the_preamble() {
        val o = out(10_000L + 1_200L)
        assertTrue(o.speak != null)
        assertEquals(ErFillers.State.HOLDING, o.state)
    }

    @Test fun density_cap_silences_after_two() {
        assertEquals(null, out(10_000L + 1_500L, recent = 2).speak)
        assertEquals(ErFillers.State.SILENT, out(10_000L + 1_500L, recent = 2).state)
    }

    @Test fun fail_soft_arrives_after_lag_threshold() {
        val o = out(10_000L + 5_000L)
        assertTrue("lag ack must be an in-character sentence", o.speak?.contains("mind's a bit slow") == true || o.speak?.contains("working on it") == true || o.speak?.contains("taking a little longer") == true)
        assertEquals(ErFillers.State.LAG_ACK, o.state)
    }

    @Test fun fail_soft_says_once_then_goes_silent() {
        // recent=1 (already said something): the lag slot stays quiet.
        val o = ErFillers.tick(nowMs = 10_000L + 5_000L, mindStartedAt = 10_000L, recentCount = 1, warm = false, userGoneMs = 0L, lagSaidCount = 1)
        assertEquals(null, o.speak)
    }

    // 0.6.5: the lag line is ONCE per window — keyed to the MONOTONIC lag count,
    // not the 3s trailing count (which fell back to 0 and re-armed the fail-soft
    // every ~3s — the "same phrase over and over" the field heard).
    @Test fun lag_never_repeats_after_the_trailing_count_falls_back() {
        // said one lag line 5s ago; by +9s the 3s trailing count is 0 again —
        // with lagSaidCount=1 the fail-soft stays silent regardless.
        val o = ErFillers.tick(10_000L + 9_000L, 10_000L, recentCount = 0, warm = false, userGoneMs = 19_000L, lagSaidCount = 1)
        assertEquals(null, o.speak)
        assertEquals(ErFillers.State.SILENT, o.state)
    }

    @Test fun count_recent_drops_stale_entries() {
        val now = 100_000L
        val times = listOf(now - 1000, now - 2000, now - 4000)   // third is stale
        assertEquals(2, ErFillers.countRecent(times, now))
    }

    @Test fun lag_lines_never_carry_facts() {
        // The sacrosanct sweep: no inventory line may teach or promise.
        val all = listOf("Hmm —", "Okay, let me see —", "Right, so —", "One sec —",
            "Mm, let me think —", "Okay —", "Right —",
            "my mind's a bit slow right now, give me a sec",
            "still working on it — one more moment",
            "this one's taking a little longer")
        val factish = Regex("(?i)\\b(is|are|was|will|costs|opens|closes)\\b|\\d")
        for (l in all) assertTrue("filler must not carry facts: $l", !factish.containsMatchIn(l))
    }
}
