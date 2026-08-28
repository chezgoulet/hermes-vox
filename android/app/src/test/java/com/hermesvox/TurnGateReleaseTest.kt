package com.hermesvox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Proves the realtime turn gate releases EXACTLY once per turn epoch (#60), even
 * when many paths race to release (barge-in + settle + error + speak-off). Pure
 * JVM — no Robolectric needed; VoiceLoopState has no Android deps.
 */
class TurnGateReleaseTest {

    @Test fun releases_exactly_once_under_racing_callers() {
        val state = VoiceLoopState()
        val latch = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        state.arm()
        val results = java.util.concurrent.ConcurrentLinkedQueue<Boolean>()
        for (i in 0 until 256) pool.submit { results.offer(state.release() == true && { latch.countDown(); true }()) }
        pool.shutdown(); pool.awaitTermination(5, TimeUnit.SECONDS)
        assertEquals(0L, latch.count)          // the turn-gate latch fired exactly once
        assertEquals(1, results.count { it })  // exactly one caller observed the "real" release
    }

    @Test fun rearming_for_a_new_turn_allows_another_release() {
        val state = VoiceLoopState(); val latch = CountDownLatch(1)
        state.arm(); assertTrue(state.release()); assertFalse(state.release())  // 2nd same-epoch release is a no-op
        state.arm(); assertTrue(state.release())                                // new epoch re-arms
    }

    @Test fun stable_partial_gates_early_turn_start() {
        val state = VoiceLoopState(earlySilenceMs = 350)
        assertFalse(state.mayStart("hello", silentMs = 200, nowMs = 1_000))   // pause too short -> no early start
        assertFalse(state.mayStart("he",   silentMs = 500, nowMs = 1_400))   // fragment (len<3) -> no
        assertTrue(state.mayStart("hello world", silentMs = 500, nowMs = 1_900)) // phrase-pause + non-trivial -> early start
    }
}
