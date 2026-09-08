package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ErArbiter — the priority table (Miles rule #4). Under test: P0 preempts
 * everything; the mind (P1) is NEVER cut by a soul utterance (only the P0
 * stop path — outside the arbiter — cuts it); P2 preempts P3; P3 never
 * talks over anything.
 */
class ErArbiterTest {

    @Test fun silence_plays_anything() {
        for (p in listOf(ErArbiter.Priority.P1_MIND, ErArbiter.Priority.P2_SOUL_CRITICAL, ErArbiter.Priority.P3_FILLER)) {
            assertTrue("silence must accept $p", ErArbiter.arbitrate(null, p) is ErArbiter.Decision.Play)
        }
    }

    @Test fun p0_preempts_everything() {
        for (cur in listOf(ErArbiter.Priority.P1_MIND, ErArbiter.Priority.P2_SOUL_CRITICAL, ErArbiter.Priority.P3_FILLER)) {
            val d = ErArbiter.arbitrate(cur, ErArbiter.Priority.P0_STOP)
            assertTrue("P0 must preempt $cur", d is ErArbiter.Decision.Preempt)
        }
    }

    @Test fun the_mind_is_never_cut_by_the_soul() {
        // THE double-talk invariant, from the arbiter side: P1 (mind) playing,
        // P2/P3 (soul) requests — both REJECT. Only the P0 path (semantic barge
        // gate -> silenceAll) ever cuts the mind.
        assertTrue(ErArbiter.arbitrate(ErArbiter.Priority.P1_MIND, ErArbiter.Priority.P2_SOUL_CRITICAL) is ErArbiter.Decision.Reject)
        assertTrue(ErArbiter.arbitrate(ErArbiter.Priority.P1_MIND, ErArbiter.Priority.P3_FILLER) is ErArbiter.Decision.Reject)
    }

    @Test fun critical_soul_preempts_filler() {
        val d = ErArbiter.arbitrate(ErArbiter.Priority.P3_FILLER, ErArbiter.Priority.P2_SOUL_CRITICAL)
        assertTrue(d is ErArbiter.Decision.Preempt)
        assertEquals(ErArbiter.Priority.P3_FILLER, (d as ErArbiter.Decision.Preempt).cut)
    }

    @Test fun filler_never_talks_over_anything() {
        for (cur in listOf(ErArbiter.Priority.P1_MIND, ErArbiter.Priority.P2_SOUL_CRITICAL, ErArbiter.Priority.P3_FILLER)) {
            assertTrue("P3 must not play over $cur",
                ErArbiter.arbitrate(cur, ErArbiter.Priority.P3_FILLER) is ErArbiter.Decision.Reject)
        }
    }
}
