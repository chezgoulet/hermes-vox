package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the speech-locked transcript cursor (0.5.0-previewA). The display used to
 * be paced by SSE DELTA ARRIVAL — the whole reply lands in ~1s while Piper speaks it
 * over 8-15s, so the crawl ran sentences ahead of the voice. The cursor paces it by
 * the playback head instead: segments of (textLen, samples) in the order they are
 * written to the track, walked to a char count. Pure JVM — no Android, no AudioTrack.
 */
class SpeechCursorTest {

    // Two phrases: 10 chars over 1000 samples, then 20 chars over 2000 samples.
    private fun twoPhrases() = SpeechCursor(listOf(10, 20), listOf(1000, 2000))

    @Test fun nothing_played_reveals_nothing() {
        assertEquals(0, twoPhrases().charsSpoken(0))
        assertEquals(0, twoPhrases().charsSpoken(-5))   // a bogus/negative head is not a reveal
    }

    @Test fun a_fully_covered_segment_reveals_all_of_its_chars() {
        assertEquals(10, twoPhrases().charsSpoken(1000))   // exactly the first phrase
    }

    @Test fun a_partial_segment_interpolates_within_the_phrase() {
        // half-way through the second phrase: all of phrase 1 + half of phrase 2
        assertEquals(10 + 10, twoPhrases().charsSpoken(2000))
        // a quarter into the FIRST phrase
        assertEquals(2, twoPhrases().charsSpoken(250))
    }

    @Test fun past_the_end_clamps_to_what_was_fed() {
        val c = twoPhrases()
        assertEquals(30, c.charsSpoken(3000))            // exactly done
        assertEquals(30, c.charsSpoken(9_000_000))       // never over-reveals
    }

    @Test fun empty_accounting_reveals_nothing() {
        val c = SpeechCursor(emptyList(), emptyList())
        assertEquals(0, c.charsSpoken(0))
        assertEquals(0, c.charsSpoken(48_000))
        assertEquals(0, c.totalChars)
    }

    @Test fun a_silent_segment_is_skipped_not_divided_by_zero() {
        val c = SpeechCursor(listOf(5, 5), listOf(0, 1000))
        assertEquals(5, c.charsSpoken(0 + 1))            // the 0-sample phrase is already past
        assertEquals(10, c.charsSpoken(1000))
    }

    @Test fun the_reveal_is_monotonic_in_samples_played() {
        val c = SpeechCursor(listOf(12, 7, 41), listOf(1200, 800, 4100))
        var prev = 0
        for (s in 0..6200 step 13) {
            val now = c.charsSpoken(s)
            assertTrue("charsSpoken went backwards at $s: $prev -> $now", now >= prev)
            prev = now
        }
        assertEquals(60, prev)   // and it arrives at exactly the fed length
    }

    @Test fun freeze_is_just_purity_a_held_sample_count_holds_its_chars() {
        val c = twoPhrases()
        val frozen = c.charsSpoken(1500)                 // cut mid-phrase-2
        repeat(5) { assertEquals(frozen, c.charsSpoken(1500)) }
        assertEquals(15, frozen)                         // 10 + 25% of 2000 samples -> 5 of 20 chars
        assertTrue(frozen < c.totalChars)
    }

    @Test fun a_one_shot_reply_is_a_single_segment_no_special_case() {
        val c = SpeechCursor.of(listOf(Pair(199, 100_000)))
        assertEquals(0, c.charsSpoken(0))
        assertEquals(99, c.charsSpoken(50_000))
        assertEquals(199, c.charsSpoken(100_000))
    }

    // ---- PlaybackClock: the device-reliability fallback for the sample source ----

    @Test fun a_working_head_is_the_truth() {
        assertEquals(4410, PlaybackClock.samples(4410, headEverMoved = true, msSinceAudio = 50L, sampleRate = 22050))
    }

    @Test fun a_head_that_has_moved_and_now_holds_is_a_real_stall_not_a_fallback() {
        // freezing the reveal with the voice is CORRECT here (underrun / pause).
        assertEquals(0, PlaybackClock.samples(0, headEverMoved = true, msSinceAudio = 5000L, sampleRate = 22050))
    }

    @Test fun a_head_that_never_moves_falls_back_to_the_wall_clock() {
        assertEquals(0, PlaybackClock.samples(0, false, PlaybackClock.STALL_MS - 1, 22050))  // still inside the grace
        assertEquals(22050, PlaybackClock.samples(0, false, 1000L, 22050))                    // 1s of audio at 22.05kHz
    }

    @Test fun no_sample_rate_means_no_guess() {
        assertEquals(0, PlaybackClock.samples(0, false, 5000L, 0))
    }

    @Test fun the_fallback_still_feeds_a_monotonic_cursor() {
        val c = SpeechCursor(listOf(10, 20), listOf(1000, 2000))
        var prev = 0
        for (ms in 0L..300L step 5L) {
            val now = c.charsSpoken(PlaybackClock.samples(0, false, ms * 10, 1000))
            assertTrue(now >= prev); prev = now
        }
        assertEquals(30, prev)   // 3000ms of wall clock at 1000Hz == the 3000 fed samples
    }
}
