package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Proves the thread-budget policy (VoxThreads): the derived defaults, the
 * override passthrough, and the bounds that keep a small phone from
 * over-subscribing the CPU on the capture path.
 */
class VoxThreadsTest {

    @Test fun auto_stt_is_half_the_cores_capped_at_four() {
        assertEquals(2, VoxThreads.stt(4, VoxThreads.AUTO))
        assertEquals(3, VoxThreads.stt(6, VoxThreads.AUTO))
        assertEquals(4, VoxThreads.stt(8, VoxThreads.AUTO))
        assertEquals(4, VoxThreads.stt(9, VoxThreads.AUTO))     // Pixel 9 class
        assertEquals(4, VoxThreads.stt(16, VoxThreads.AUTO))   // never above MAX
    }

    @Test fun auto_stt_never_starves_a_small_device() {
        // Halving alone would give 1 on a 2-core phone; the floor is 2.
        assertEquals(2, VoxThreads.stt(2, VoxThreads.AUTO))
        assertEquals(2, VoxThreads.stt(1, VoxThreads.AUTO))
    }

    @Test fun auto_tts_is_conservative() {
        assertEquals(1, VoxThreads.tts(2, VoxThreads.AUTO))
        assertEquals(2, VoxThreads.tts(8, VoxThreads.AUTO))
        assertEquals(2, VoxThreads.tts(9, VoxThreads.AUTO))     // Pixel 9 class
        assertEquals(2, VoxThreads.tts(16, VoxThreads.AUTO))
    }

    @Test fun an_explicit_override_wins_for_both_legs() {
        for (n in VoxThreads.MIN..VoxThreads.MAX) {
            assertEquals(n, VoxThreads.stt(9, n))
            assertEquals(n, VoxThreads.tts(9, n))
        }
    }

    @Test fun an_out_of_range_pref_falls_back_to_auto() {
        // The clamp keeps a corrupt/legacy pref from over-subscribing the CPU.
        assertEquals(VoxThreads.stt(9, VoxThreads.AUTO), VoxThreads.stt(9, 5))
        assertEquals(VoxThreads.stt(9, VoxThreads.AUTO), VoxThreads.stt(9, -3))
        assertEquals(VoxThreads.tts(9, VoxThreads.AUTO), VoxThreads.tts(9, 99))
    }

    @Test fun the_vad_stays_single_threaded() {
        assertEquals(1, VoxThreads.VAD)
    }

    @Test fun labels_are_honest() {
        assertEquals("Auto", VoxThreads.label(VoxThreads.AUTO))
        assertEquals("1 thread", VoxThreads.label(1))
        assertEquals("4 threads", VoxThreads.label(4))
        assertEquals("Auto", VoxThreads.label(7))   // out of range reads as derived
    }
}
