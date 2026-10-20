package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The echo measurement under BargeAudioPath's playback floor (0.9.1). */
class EchoFloorTest {

    private fun learnBlock(e: EchoFloor, level: Float) = repeat(EchoFloor.BLOCK_READS) { e.learn(level) }

    @Test fun uncalibrated_the_floor_is_barge_rms_min() {
        val e = EchoFloor()
        assertFalse(e.calibrated)
        assertEquals(0.10f, e.floor(0.10f), 0f)
        assertEquals(0.13f, e.levelBar(0.10f, e.floor(0.10f)), 1e-6f)
        repeat(EchoFloor.BLOCK_READS - 1) { e.learn(0.001f) }
        assertFalse("one read short of a block", e.calibrated)
    }

    @Test fun the_floor_is_margin_over_the_echo_clamped_both_ways() {
        val e = EchoFloor()
        learnBlock(e, 0.04f)
        assertTrue(e.calibrated)
        assertEquals(0.06f, e.floor(0.10f), 1e-6f)                  // 1.5 x 0.04
        assertEquals(0.08f, e.levelBar(0.10f, 0.06f), 1e-6f)        // the escape keeps 0.080
        assertEquals(0.03f, EchoFloor().also { learnBlock(it, 0.002f) }.floor(0.10f), 1e-6f)   // minimum
        assertEquals(0.10f, EchoFloor().also { learnBlock(it, 0.09f) }.floor(0.10f), 1e-6f)    // never above
        // The pref scales the band: a raised barge_rms_min raises the minimum with it.
        assertEquals(0.042f, EchoFloor().also { learnBlock(it, 0.002f) }.floor(0.14f), 1e-6f)
    }

    @Test fun a_block_keeps_its_second_highest_read() {
        val e = EchoFloor()
        repeat(EchoFloor.BLOCK_READS - 2) { e.learn(0.01f) }
        e.learn(0.30f)        // one outlier read
        e.learn(0.05f)
        assertEquals(0.05f, e.echoLevel, 0f)
    }

    @Test fun the_median_of_blocks_shrugs_off_two_spoken_blocks() {
        val e = EchoFloor()
        learnBlock(e, 0.20f)                       // the user talked through these two
        learnBlock(e, 0.15f)
        assertEquals("max while fewer than three", 0.20f, e.echoLevel, 0f)
        repeat(3) { learnBlock(e, 0.005f) }
        assertEquals(0.005f, e.echoLevel, 0f)
        assertEquals(0.03f, e.floor(0.10f), 1e-6f)
        // ... and old blocks age out: five quiet blocks later the spoken ones are gone.
        repeat(5) { learnBlock(e, 0.004f) }
        assertEquals(0.004f, e.echoLevel, 0f)
    }

    @Test fun playing_looks_back_six_reads() {
        val e = EchoFloor()
        assertFalse(e.playing(0.01f))
        assertTrue(e.playing(0.05f))
        repeat(EchoFloor.WINDOW_READS - 1) { assertTrue("tail read $it", e.playing(0f)) }
        assertFalse(e.playing(0f))
        e.playing(0.05f)
        e.clearReference()
        assertFalse(e.playing(0f))
    }

    @Test fun window_rms_reads_across_the_phrase_boundary() {
        val prev = FloatArray(100) { 0.5f }
        val cur = FloatArray(100) { 0.1f }
        // Head 120: the window [100, 120) is all in cur (base 100).
        assertEquals(0.1f, EchoFloor.windowRms(120, 20, cur, 100, prev, 0), 1e-6f)
        // Head 110, window 20: [90, 100) from prev, [100, 110) from cur.
        val mixed = Math.sqrt((10 * 0.25 + 10 * 0.01) / 20.0).toFloat()
        assertEquals(mixed, EchoFloor.windowRms(110, 20, cur, 100, prev, 0), 1e-6f)
        // Head before the current phrase (its predecessor's buffered tail): prev only.
        assertEquals(0.5f, EchoFloor.windowRms(90, 20, cur, 100, prev, 0), 1e-6f)
        // Nothing known about the samples: silence. Nothing played yet: 0.
        assertEquals(0f, EchoFloor.windowRms(400, 20, cur, 100, prev, 0), 0f)
        assertEquals(0f, EchoFloor.windowRms(0, 20, cur, 100, prev, 0), 0f)
    }
}
