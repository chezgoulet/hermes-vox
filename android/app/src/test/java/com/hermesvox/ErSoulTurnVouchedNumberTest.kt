package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #218 — an aside may say how long the wait has been.
 *
 * The working guard refuses any digit, because a number the model invents is a fact it cannot
 * know. There is exactly one number the CLIENT knows and the model does not: how long the caller
 * has been waiting. The directive offers that value; the guard accepts that value, once.
 *
 * Every test here passes its voucher explicitly, except the one that pins the no-voucher case —
 * which is the whole old rule, still in force.
 */
class ErSoulTurnVouchedNumberTest {

    /** The controller's snapshot: the caller has been waiting ninety seconds. */
    private val vouched = 90L

    // ---- the voucher ----

    @Test fun a_line_with_the_vouched_number_is_spoken() {
        assertEquals(
            "We're about 90 seconds in...",
            ErSoulTurn.workingLine("We're about 90 seconds in", emptyList(), vouched),
        )
        assertNull(ErSoulTurn.workingReject("We're about 90 seconds in", emptyList(), vouched))
    }

    @Test fun a_different_number_is_refused_and_the_reason_says_so() {
        // 45 is a fact the model invented; the wait was 90. The token is the number's own, not
        // the generic markup one: a field log has to separate "invented a number" from "wrapped
        // the line in brackets".
        assertEquals(
            "digits-unvouched",
            ErSoulTurn.workingReject("We're about 45 seconds in", emptyList(), vouched),
        )
        assertEquals("digits-unvouched", ErSoulTurn.REASON_UNVOUCHED_NUMBER)
        assertNull(ErSoulTurn.workingLine("We're about 45 seconds in", emptyList(), vouched))
    }

    @Test fun two_numbers_are_refused_even_when_both_are_the_vouched_value() {
        // One number is the client's measurement. Two is the model doing arithmetic.
        assertEquals(
            "digits-unvouched",
            ErSoulTurn.workingReject("90 seconds in, 90 to go", emptyList(), vouched),
        )
    }

    @Test fun the_wait_written_the_way_a_person_would_is_two_numbers_and_is_refused() {
        // "a minute and a half" cannot be vouched: the client vouches a value, so the wait is
        // written in seconds — one number — and the directive asks for exactly that.
        assertEquals(
            "digits-unvouched",
            ErSoulTurn.workingReject("About 1 minute 30 seconds in", emptyList(), vouched),
        )
    }

    @Test fun a_number_with_nothing_vouched_is_refused_as_before() {
        // The whole old rule, unchanged: no voucher, no number.
        assertEquals("digits-unvouched", ErSoulTurn.workingReject("We're about 90 seconds in"))
        assertNull(ErSoulTurn.workingLine("We're about 90 seconds in"))
        assertEquals(
            "digits-unvouched",
            ErSoulTurn.workingReject("It'll take 2 minutes", emptyList(), null),
        )
    }

    @Test fun a_vouched_value_does_not_excuse_a_different_number_elsewhere_in_the_line() {
        // The voucher is a value, not permission for any digit at all.
        assertEquals(
            "digits-unvouched",
            ErSoulTurn.workingReject("90 seconds in with 3 things left", emptyList(), vouched),
        )
    }

    @Test fun a_spelled_number_is_prose_the_guard_does_not_police() {
        // DECIDED, and pinned: the number rule stays digit-shaped, so a spelled wait is ordinary
        // prose and passes as it always did. See the class note in the report: a word-number
        // lexicon would refuse "One second" / "Just a moment" / "one more thing", which are
        // legitimate working lines already pinned in PresenceGuardBoundsTest, and would move
        // bounds this change is not allowed to move.
        assertEquals(
            "About ninety seconds in...",
            ErSoulTurn.workingLine("About ninety seconds in", emptyList(), vouched),
        )
        // The honest limit of that choice: a spelled wait is not checked against the voucher, so
        // "ten" is not refused where 10 is. The directive asks for the digits, the only form the
        // guard can verify.
        assertNull(ErSoulTurn.workingReject("About ten seconds in", emptyList(), vouched))
        assertEquals(
            "digits-unvouched",
            ErSoulTurn.workingReject("About 10 seconds in", emptyList(), vouched),
        )
    }

    @Test fun the_directive_offers_the_wait_it_can_vouch_for() {
        val d = ErSoulTurn.workingDirective("check the logs", null, emptyList(), vouched)
        assertTrue("the value is named, in the digits the guard can check", d.contains("\"90 seconds\""))
        assertTrue("it is offered once, and nothing else is", d.contains("once, with no other number"))
        assertTrue("the old prohibition still stands", d.contains("never a result, a fact"))
    }

    @Test fun the_directive_offers_no_wait_when_none_is_known() {
        val plain = ErSoulTurn.workingDirective("check the logs")
        assertFalse(plain.contains("seconds"))
        assertFalse(plain.contains("waiting"))
    }

    // ---- the seam: the voucher widens nothing else ----

    @Test fun the_voucher_does_not_widen_the_length_bound() {
        val long = "We are 90 seconds into this and still checking every single one of the details"
        assertTrue(long.length > ErSoulTurn.MAX_OPENER_CHARS)
        assertEquals("too-long", ErSoulTurn.workingReject(long, emptyList(), vouched))
    }

    @Test fun the_voucher_does_not_widen_the_word_bound() {
        val eight = "We are about 90 seconds into this check"
        assertEquals(8, eight.split(Regex("\\s+")).size)
        assertEquals("too-many-words", ErSoulTurn.workingReject(eight, emptyList(), vouched))
        // The same line one word shorter speaks: the count is still the bound.
        assertNull(ErSoulTurn.workingReject("We are about 90 seconds in", emptyList(), vouched))
    }

    @Test fun the_voucher_does_not_widen_the_repeat_bound() {
        assertEquals(
            "repeat",
            ErSoulTurn.workingReject("About 90 seconds in", listOf("about 90 seconds in"), vouched),
        )
        assertNull(
            ErSoulTurn.workingLine("About 90 seconds in", listOf("about 90 seconds in"), vouched),
        )
    }

    @Test fun the_voucher_does_not_admit_markup() {
        // A vouched number buys the line nothing: markup is refused on its own.
        assertEquals(
            "digits-or-markup",
            ErSoulTurn.workingReject("[thinking] about 90 seconds in", emptyList(), vouched),
        )
        assertEquals(
            "digits-or-markup",
            ErSoulTurn.workingReject("<aside> about 90 seconds in", emptyList(), vouched),
        )
    }

    @Test fun the_bounds_themselves_are_unchanged() {
        assertEquals(7, ErSoulTurn.MAX_OPENER_WORDS)
        assertEquals(60, ErSoulTurn.MAX_OPENER_CHARS)
    }

    @Test fun the_beat_is_not_vouched_for_a_number() {
        // The beat on every mind turn is composed by the same opener with no voucher, so a number
        // there still costs the beat — exactly as before. Only the working lane got the exception.
        assertEquals(ErSoulTurn.Outcome.Escalate, ErSoulTurn.parse("<<ESCALATE>> about 90 seconds in"))
        assertNull(ErSoulTurn.opener("about 90 seconds in"))
    }
}
