package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fix 1 — a long utterance is transcribed WHOLE: windows cover every sample and
 *  cut at word gaps, never past Whisper's 30 s encoder input. */
class SttWindowsTest {

    private val sr = 16000

    /** Tone "speech" with silent gaps at the given seconds (100 ms each). */
    private fun speech(seconds: Double, gapsAt: List<Double>): FloatArray {
        val x = FloatArray((seconds * sr).toInt()) { i -> (0.3 * Math.sin(i * 0.05)).toFloat() }
        for (g in gapsAt) {
            val a = (g * sr).toInt()
            for (k in a until minOf(x.size, a + sr / 10)) x[k] = 0f
        }
        return x
    }

    @Test fun a_clip_within_the_window_is_one_span() {
        val x = speech(24.0, emptyList())
        assertEquals(listOf(0 to x.size), SttWindows.spans(x, sr))
    }

    @Test fun spans_are_contiguous_and_cover_everything() {
        val x = speech(70.0, listOf(22.5, 45.0, 66.0))
        val spans = SttWindows.spans(x, sr)
        assertEquals(0, spans.first().first)
        assertEquals(x.size, spans.last().second)
        for (k in 1 until spans.size) assertEquals(spans[k - 1].second, spans[k].first)
        for ((a, b) in spans) assertTrue("window ${b - a} > 25 s", b - a <= sr * SttWindows.WHISPER_WINDOW_MS / 1000)
    }

    @Test fun cuts_land_in_the_word_gap() {
        // A gap at 22.5-22.6 s lies inside the first window's 21-25 s search band.
        val x = speech(40.0, listOf(22.5))
        val cut = SttWindows.spans(x, sr)[0].second
        assertTrue("cut at ${cut.toDouble() / sr} s", cut >= (22.5 * sr).toInt() && cut <= (22.6 * sr).toInt())
    }

    @Test fun no_gap_still_cuts_inside_the_band() {
        val x = speech(40.0, emptyList())
        val cut = SttWindows.spans(x, sr)[0].second
        assertTrue(cut >= 21 * sr && cut <= 25 * sr)
    }

    @Test fun join_drops_blank_windows() {
        assertEquals("one two", SttWindows.join(listOf(" one ", null, "", "two")))
    }
}
