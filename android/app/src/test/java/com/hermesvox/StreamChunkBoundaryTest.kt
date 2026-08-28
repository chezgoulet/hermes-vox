package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Proves the streaming-TTS chunker (#30/#44) never splits on a period that is
 * not a true sentence boundary — a decimal ("71.5%") or an abbreviation
 * ("Dr. Chen") must NOT become an audible seam. Pure JVM — no Android deps.
 */
class StreamChunkBoundaryTest {

    @Test fun period_inside_a_number_is_not_a_seam() {
        assertEquals(-1, indexOfSentenceEnd("disk at 71.5%"))
    }

    @Test fun period_after_an_abbreviation_is_not_a_seam() {
        assertEquals(-1, indexOfSentenceEnd("Dr. Chen"))
    }

    @Test fun period_followed_by_space_and_letter_is_a_seam() {
        // "Dr. Chen said hi." is the chunk; the seam is the '.' after "hi" (abbrev
        // dot at index 2 is correctly skipped, only the true sentence dot counts).
        assertEquals(16, indexOfSentenceEnd("Dr. Chen said hi. Next"))
    }

    @Test fun period_at_the_very_end_is_a_seam() {
        assertEquals(5, indexOfSentenceEnd("Hello."))
    }

    @Test fun terminators_are_always_seams() {
        assertEquals(4, indexOfSentenceEnd("Stop!"))
        assertEquals(6, indexOfSentenceEnd("really? yes"))
    }

    @Test fun no_boundary_returns_minus_one() {
        assertEquals(-1, indexOfSentenceEnd("welcome to the party"))
    }
}
