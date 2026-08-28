package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Proves the #18 bounded pre-roll ring: O(1) append/overwrite sliding window
 * (the old ArrayList<Float> did add() + removeAt(0) per sample — an O(n)
 * arraycopy + a Float box per sample). Pure JVM — no Android deps.
 */
class FloatRingTest {

    @Test fun appends_and_drains_in_order() {
        val ring = FloatRing(4)
        ring.add(floatArrayOf(1f, 2f, 3f))
        assertEquals(3, ring.size)
        val out = ArrayList<Float>()
        ring.drainInto(out)
        assertEquals(listOf(1f, 2f, 3f), out)
        assertEquals(0, ring.size)            // drain-once
    }

    @Test fun overflowing_keeps_the_latest_window() {
        val ring = FloatRing(3)
        ring.add(floatArrayOf(1f, 2f, 3f, 4f, 5f))   // capacity 3 -> keeps 3,4,5
        assertEquals(3, ring.size)
        val out = ArrayList<Float>()
        ring.drainInto(out)
        assertEquals(listOf(3f, 4f, 5f), out)
    }

    @Test fun wraps_around_correctly_across_drains() {
        val ring = FloatRing(3)
        ring.add(floatArrayOf(1f, 2f, 3f))
        ring.drainInto(ArrayList())                    // drain but reuse the ring
        ring.add(floatArrayOf(4f, 5f, 6f, 7f))         // full overwrite from head
        val out = ArrayList<Float>()
        ring.drainInto(out)
        assertEquals(listOf(5f, 6f, 7f), out)
    }

    @Test fun zero_capacity_is_clamped_to_one() {
        val ring = FloatRing(0)
        ring.add(floatArrayOf(42f))
        val out = ArrayList<Float>()
        ring.drainInto(out)
        assertEquals(listOf(42f), out)
    }
}
