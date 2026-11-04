package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F8 (2026-11-04 Presence review): "The mind's own progress narration is thrown away; the soul
 * invents one instead."
 *
 * The gateway streams `phase=commentary` — "Let me search for recent listings", the mind saying in
 * its own words what it is doing. It went to the dev console and no further, while a 2B rendered a
 * guess about the same tool from a name and 60 characters of arguments. [ErSoulTurn.commentaryLine]
 * turns it into the line the phone can actually say: one breath, cut at a clause end, or nothing.
 */
class CommentaryLineTest {

    @Test fun a_short_sentence_is_spoken_as_written() {
        assertEquals("Let me search for recent listings.",
            ErSoulTurn.commentaryLine("Let me search for recent listings."))
    }

    @Test fun newlines_and_runs_of_space_collapse_to_one_line() {
        assertEquals("Looking that up now.",
            ErSoulTurn.commentaryLine("  Looking\n\nthat up\tnow.  "))
    }

    @Test fun a_long_narration_is_cut_at_a_clause_end_inside_the_beat_budget() {
        // The same bound the beat uses (seven words, cut at a clause end). A working line that
        // runs long collides with the mind's first text — the reason the bound exists at all.
        val long = "Let me check the two newest listings, then compare them with the one you sent."
        val line = ErSoulTurn.commentaryLine(long)
        assertTrue("spoken: $line", line != null)
        assertTrue("no more than the beat's words: $line", line!!.trim().split(" ").size <= ErSoulTurn.MAX_OPENER_WORDS)
        assertTrue("still the mind's own words: $line", long.startsWith(line))
    }

    @Test fun a_line_with_no_clause_end_is_not_spoken_rather_than_mangled() {
        val noClause = "one two three four five six seven eight nine ten eleven"
        assertNull(ErSoulTurn.commentaryLine(noClause))
    }

    @Test fun blank_narration_is_silence() {
        assertNull(ErSoulTurn.commentaryLine(null))
        assertNull(ErSoulTurn.commentaryLine(""))
        assertNull(ErSoulTurn.commentaryLine("   \n\t "))
    }
}
