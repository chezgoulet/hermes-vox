package com.hermesvox.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L3 (#413 second pass): the reattach wait is exponential, bounded and jittered, and it is
 * never zero — a flapping socket must not turn the reader into a hot loop.
 */
class BackoffTest {

    @Test fun theStepDoublesPerAttempt() {
        assertEquals(250L, Backoff.delayMs(250, 0, 4_000, 1.0))
        assertEquals(500L, Backoff.delayMs(250, 1, 4_000, 1.0))
        assertEquals(1_000L, Backoff.delayMs(250, 2, 4_000, 1.0))
    }

    @Test fun theWaitIsCappedHoweverManyDrops() {
        for (attempt in listOf(5, 10, 29, 30, 63, 1_000, Int.MAX_VALUE)) {
            val top = Backoff.delayMs(250, attempt, 4_000, 1.0)
            assertTrue("attempt $attempt waited $top", top in 2_000L..4_000L)
        }
    }

    @Test fun halfTheStepIsAlwaysKept() {
        for (attempt in 0..8) {
            val step = minOf(4_000L, 250L shl attempt)
            assertEquals("the floor of attempt $attempt", step / 2, Backoff.delayMs(250, attempt, 4_000, 0.0))
            assertTrue(Backoff.delayMs(250, attempt, 4_000, 0.0) > 0)
        }
    }

    @Test fun theJitterStaysInsideTheUpperHalf() {
        for (u in listOf(-1.0, 0.0, 0.25, 0.5, 0.999, 1.0, 7.0)) {
            val d = Backoff.delayMs(1_000, 0, 1_000, u)
            assertTrue("u=$u gave $d", d in 500L..1_000L)
        }
    }

    @Test fun aZeroBaseOrCapMeansNoWait() {
        assertEquals(0L, Backoff.delayMs(0, 3, 4_000, 0.5))
        assertEquals(0L, Backoff.delayMs(250, 3, 0, 0.5))
    }
}
