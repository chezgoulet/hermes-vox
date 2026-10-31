package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D20 — shape is repaired, content is refused.
 *
 * What a small model gets wrong about FORMATTING (a long line, markdown, a fence, a title, a
 * courtesy) no longer costs the caller the line: the guard cuts or strips it and speaks what is
 * left. What it gets wrong about CONTENT (a number nobody supplied, a result nobody produced) is
 * still refused, because those are the failures that cost trust rather than tone.
 *
 * The repair functions are pure; every case here runs off-device.
 */
class PresenceRepairTest {

    // ---- rule four: the cut ----

    @Test fun a_twelve_word_line_with_a_clause_end_is_cut_there_and_spoken() {
        // Twelve words, a comma after "reading". The budget is seven, so the last clause end that
        // fits is that comma: the spoken line is the clause before it, not a word-boundary stub.
        val line = "I am still reading, through the logs for you now and later"
        assertEquals(12, line.split(Regex("\\s+")).size)

        val spoken = ErSoulTurn.workingLine(line)

        assertEquals("I am still reading...", spoken)
        assertEquals(
            "the cut is the clause before the comma",
            "I am still reading",
            spoken!!.removeSuffix("..."),
        )
        assertEquals(4, spoken.removeSuffix("...").split(Regex("\\s+")).size)
        assertNull(ErSoulTurn.workingReject(line))
    }

    @Test fun a_twelve_word_line_with_no_clause_end_is_dropped_not_truncated_mid_phrase() {
        // No comma, semicolon, colon, dash or full stop inside the budget. A word-boundary cut
        // would say "I am still reading through the" — a fragment that sounds like a fault in the
        // phone. Silence is better, so the line is dropped whole.
        val line = "I am still reading through the logs for you now and later"
        assertEquals(12, line.split(Regex("\\s+")).size)

        assertNull(ErSoulTurn.workingLine(line))
        assertEquals("too-many-words", ErSoulTurn.workingReject(line))
        assertNull(ErSoulTurn.fitToBound(line))
        // And the decision is pinned as a decision: the first seven words are never the answer.
        assertFalse(
            "I am still reading through the..." == ErSoulTurn.workingLine(line),
        )
    }

    @Test fun a_full_stop_inside_the_budget_is_kept_and_the_rest_dropped() {
        val line = "Checking the logs. It is taking a little longer than I thought"
        assertEquals("Checking the logs.", ErSoulTurn.workingLine(line))
    }

    @Test fun a_line_inside_the_budget_is_untouched() {
        assertEquals("Checking on it now...", ErSoulTurn.workingLine("Checking on it now"))
    }

    // ---- markdown, fences, titles, courtesies ----

    @Test fun markdown_emphasis_bullets_and_backticks_are_stripped_and_the_line_is_spoken() {
        assertEquals("Checking on it...", ErSoulTurn.workingLine("**Checking** on it"))
        assertEquals("Still working on it...", ErSoulTurn.workingLine("_Still working_ on it"))
        assertEquals("Checking on it...", ErSoulTurn.workingLine("- Checking on it"))
        assertEquals("checking the logs...", ErSoulTurn.workingLine("`checking the logs`"))
        // Stripping happened before the bounds: the wrapper never counts against the budget.
        assertNull(ErSoulTurn.workingReject("**Checking** on it"))
    }

    @Test fun a_fence_and_a_title_line_are_removed() {
        assertEquals(
            "Checking the logs now...",
            ErSoulTurn.workingLine("```\nChecking the logs now\n```"),
        )
        assertEquals(
            "I am reading the logs...",
            ErSoulTurn.workingLine("# Update\nI am reading the logs"),
        )
        assertEquals(
            "I am reading the logs...",
            ErSoulTurn.workingLine("**Update**\nI am reading the logs"),
        )
    }

    @Test fun a_trailing_courtesy_is_removed_and_the_line_is_spoken() {
        assertEquals(
            "Checking the logs now...",
            ErSoulTurn.workingLine("Checking the logs now. Hope this helps!"),
        )
        assertEquals(
            "Still on it...",
            ErSoulTurn.workingLine("Still on it — let me know if you need anything else."),
        )
        // A render that is ONLY a courtesy has nothing left to say: silence, not a spoken "Thanks".
        assertNull(ErSoulTurn.workingLine("Hope this helps!"))
        assertNull(ErSoulTurn.workingLine("Thanks so much."))
    }

    // ---- the log: shortened is not silenced ----

    @Test fun every_repair_path_reports_what_changed_and_still_speaks() {
        // Each token is the field log's witness that the line was SHORTENED. The line itself is
        // asserted non-null: a repair that ended in silence would be indistinguishable from a
        // refusal, which is exactly what D20 forbids.
        fun repaired(raw: String, vararg reasons: String) {
            assertTrue("$raw was spoken", ErSoulTurn.workingLine(raw) != null)
            assertEquals(listOf(*reasons), ErSoulTurn.workingRepairs(raw))
        }

        repaired("I am still reading, through the logs for you now and later", "cut")
        repaired("**Checking** on it", "markup")
        repaired("- Checking on it", "markup")
        repaired("```\nChecking the logs now\n```", "fence")
        repaired("# Update\nI am reading the logs", "title")
        repaired("Checking the logs now. Hope this helps!", "courtesy")

        // Two repairs in one render are both named.
        assertEquals(
            listOf("fence", "markup"),
            ErSoulTurn.workingRepairs("```\n**Checking** on it\n```"),
        )
    }

    @Test fun a_line_that_was_not_repaired_or_was_refused_reports_nothing() {
        assertEquals(emptyList<String>(), ErSoulTurn.workingRepairs("Checking on it now"))
        // A refusal is silence, not a repair: the detail log carries the reason instead.
        assertEquals(emptyList<String>(), ErSoulTurn.workingRepairs("I've booked it"))
        assertEquals(emptyList<String>(), ErSoulTurn.workingRepairs("It'll take 2 minutes"))
        assertEquals(emptyList<String>(), ErSoulTurn.workingRepairs(ErSoulTurn.ESCALATE))
        assertEquals(emptyList<String>(), ErSoulTurn.workingRepairs(""))
    }

    // ---- no new numbers ----

    @Test fun a_number_the_directive_carried_is_spoken_and_the_same_number_twice_is_refused() {
        val directive = ErSoulTurn.workingDirective("check the logs", null, emptyList(), 90L)
        val vouched = ErSoulTurn.vouchedNumbers(directive)
        assertEquals(setOf("90"), vouched)

        assertEquals(
            "We're about 90 seconds in...",
            ErSoulTurn.workingLine("We're about 90 seconds in", emptyList(), vouched),
        )
        // #218's rule survives D20: two numbers are arithmetic, even when both are grounded.
        assertEquals(
            "digits-unvouched",
            ErSoulTurn.workingReject("We're 90 seconds in, 90 to go", emptyList(), vouched),
        )
    }

    @Test fun a_number_the_directive_did_not_carry_is_refused_as_digits_unvouched() {
        val directive = ErSoulTurn.workingDirective("check the logs", null, emptyList(), 90L)
        val vouched = ErSoulTurn.vouchedNumbers(directive)

        assertEquals("digits-unvouched", ErSoulTurn.workingReject("It'll take 2 minutes", emptyList(), vouched))
        assertNull(ErSoulTurn.workingLine("It'll take 2 minutes", emptyList(), vouched))
        assertEquals("digits-unvouched", ErSoulTurn.REASON_UNVOUCHED_NUMBER)
    }

    // ---- content: the result claim ----

    @Test fun an_impossible_result_claim_is_refused_not_repaired() {
        // Nothing has been booked: the mind has not said so. Shape repair must never make this
        // line speakable — it is three words with no markup, and it is still refused.
        assertEquals("result-unbacked", ErSoulTurn.workingReject("I've booked it"))
        assertNull(ErSoulTurn.workingLine("I've booked it"))
        assertEquals("result-unbacked", ErSoulTurn.workingReject("It's done"))
        assertEquals("result-unbacked", ErSoulTurn.REASON_UNBACKED_RESULT)
        // Present-tense work and future intent are not claims of a result.
        assertEquals("Still checking on it...", ErSoulTurn.workingLine("Still checking on it"))
        assertEquals("I'll keep you posted...", ErSoulTurn.workingLine("I'll keep you posted"))
    }

    // ---- the directive ----

    @Test fun the_directive_is_the_five_rules_and_carries_none_of_the_old_bans() {
        val d = ErSoulTurn.workingDirective("check the logs", "terminal (sleep 70)", emptyList(), 90L)

        // The five rules, as written.
        assertTrue(d.contains("You are Hermes, in your own voice"))
        assertTrue(d.contains("The caller asked: \"check the logs\""))
        assertTrue(d.contains("Never state a result you do not have: nothing is done until the mind says it is done."))
        assertTrue(d.contains("One line, in your own voice, no longer than a breath."))
        assertTrue(d.contains("Silence is a valid answer, and often the right one"))

        // The five rules are the whole instruction: the wait is part of the moment (rule two), not
        // a sixth conditional sentence telling the model what to do with it.
        assertTrue("the measurement rides the moment", d.contains("The caller has been waiting 90 seconds."))
        assertFalse(d.contains("if that is worth saying"))

        // The old lexical contract, exactly, is gone.
        for (ban in listOf(
            "never a number",
            "no other number",
            "under 7 words",
            "60 characters",
            "must not contain any fact",
            "never a result, a fact",
            "no markup",
            "do not use markdown",
        )) {
            assertFalse("the directive still carries the old ban: '$ban'", d.contains(ban))
        }
    }

    @Test fun the_rules_themselves_carry_no_digits() {
        // A digit in the directive is a grounded number (D20), so a numbered rule would vouch for
        // "1". The only digits in a working directive are the ones the client wrote.
        val d = ErSoulTurn.workingDirective("check the logs", "terminal (sleep 70)", emptyList(), 90L)
        assertEquals(setOf("70", "90"), Regex("\\d+").findAll(d).map { it.value }.toSet())
    }
}
