package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #218 generalised by D20 — a line may speak a number the client wrote into the directive, and no
 * other.
 *
 * The old rule refused every digit, because a number the model invents is a fact it cannot know.
 * The client already knows some of them (how long the caller has waited, the timer the caller
 * asked for), and it writes those into the directive. Those are grounded; a number that appears
 * nowhere in the directive is the model's invention and is still refused as `digits-unvouched`.
 * Two numbers are refused even when both are grounded — a line doing arithmetic is a guess (#218).
 */
class ErSoulTurnVouchedNumberTest {

    /** The controller's directive: the caller has been waiting ninety seconds. */
    private val directive = ErSoulTurn.workingDirective("check the logs", null, emptyList(), 90L)
    private val vouched = ErSoulTurn.vouchedNumbers(directive)

    // ---- the voucher ----

    @Test fun a_line_with_the_vouched_number_is_spoken() {
        assertEquals(
            "We're about 90 seconds in...",
            ErSoulTurn.workingLine("We're about 90 seconds in", emptyList(), vouched),
        )
        assertNull(ErSoulTurn.workingReject("We're about 90 seconds in", emptyList(), vouched))
    }

    @Test fun a_different_number_is_refused_and_the_reason_says_so() {
        // 45 is a fact the model invented; the directive carried 90. The token is the number's own,
        // not the generic markup one: a field log has to separate "invented a number" from
        // "wrapped the line in brackets".
        assertEquals(
            "digits-unvouched",
            ErSoulTurn.workingReject("We're about 45 seconds in", emptyList(), vouched),
        )
        assertEquals("digits-unvouched", ErSoulTurn.REASON_UNVOUCHED_NUMBER)
        assertNull(ErSoulTurn.workingLine("We're about 45 seconds in", emptyList(), vouched))
    }

    @Test fun two_numbers_are_refused_even_when_both_are_the_vouched_value() {
        // One number is the client's fact. Two is the model doing arithmetic.
        assertEquals(
            "digits-unvouched",
            ErSoulTurn.workingReject("90 seconds in, 90 to go", emptyList(), vouched),
        )
    }

    @Test fun the_wait_written_the_way_a_person_would_is_two_numbers_and_is_refused() {
        // "a minute and a half" cannot be one grounded run: the directive carries 90, so
        // "1 minute 30 seconds" is two numbers and neither is the vouched one.
        assertEquals(
            "digits-unvouched",
            ErSoulTurn.workingReject("About 1 minute 30 seconds in", emptyList(), vouched),
        )
    }

    @Test fun a_number_with_nothing_vouched_is_refused_as_before() {
        // The whole old rule, unchanged: no grounded number in the directive, no number in the line.
        assertEquals("digits-unvouched", ErSoulTurn.workingReject("We're about 90 seconds in"))
        assertNull(ErSoulTurn.workingLine("We're about 90 seconds in"))
        assertEquals(
            "digits-unvouched",
            ErSoulTurn.workingReject("It'll take 2 minutes", emptyList(), emptySet()),
        )
    }

    @Test fun a_vouched_value_does_not_excuse_a_different_number_elsewhere_in_the_line() {
        // The voucher is a value, not permission for any digit at all.
        assertEquals(
            "digits-unvouched",
            ErSoulTurn.workingReject("90 seconds in with 3 things left", emptyList(), vouched),
        )
    }

    @Test fun every_number_the_directive_carries_is_grounded_not_only_the_wait() {
        // D20 generalises #218: the grounds are the directive's digit runs, whatever wrote them. A
        // timer the caller named is the client's fact just as the wait is.
        val timer = ErSoulTurn.workingDirective("set a timer for 5 minutes")
        assertEquals(setOf("5"), ErSoulTurn.vouchedNumbers(timer))
        assertEquals(
            "Five minutes on the clock...",
            ErSoulTurn.workingLine("Five minutes on the clock", emptyList(), ErSoulTurn.vouchedNumbers(timer)),
        )
        assertEquals(
            "I'll give it 5 minutes...",
            ErSoulTurn.workingLine("I'll give it 5 minutes", emptyList(), ErSoulTurn.vouchedNumbers(timer)),
        )
    }

    @Test fun a_spelled_number_is_prose_the_guard_does_not_police() {
        // DECIDED, and pinned: the number rule stays digit-shaped, so a spelled wait is ordinary
        // prose and passes as it always did. A word-number lexicon would refuse "One second" /
        // "Just a moment" / "one more thing", which are legitimate working lines already pinned in
        // PresenceGuardBoundsTest.
        assertEquals(
            "About ninety seconds in...",
            ErSoulTurn.workingLine("About ninety seconds in", emptyList(), vouched),
        )
        // The honest limit of that choice: a spelled wait is not checked against the directive, so
        // "ten" is not refused where 10 is. The directive writes the wait in digits, the only form
        // the guard can verify.
        assertNull(ErSoulTurn.workingReject("About ten seconds in", emptyList(), vouched))
        assertEquals(
            "digits-unvouched",
            ErSoulTurn.workingReject("About 10 seconds in", emptyList(), vouched),
        )
    }

    @Test fun the_directive_offers_the_wait_it_can_vouch_for() {
        assertTrue("the value is named, in the digits the guard can check", directive.contains("waiting 90 seconds"))
        assertFalse("the old prohibition is gone", directive.contains("no other number"))
    }

    @Test fun the_directive_offers_no_wait_when_none_is_known() {
        val plain = ErSoulTurn.workingDirective("check the logs")
        assertFalse(plain.contains("seconds"))
        assertFalse(plain.contains("waiting"))
    }

    // ---- the seam: the voucher widens nothing else ----

    @Test fun the_voucher_does_not_widen_the_length_bound() {
        // Six long words with a grounded number: the voucher buys the number, not the duration.
        val long = "Extraordinarily complicated authentication verification ongoing 90"
        assertTrue(long.length > ErSoulTurn.MAX_OPENER_CHARS)
        assertEquals(6, long.split(Regex("\\s+")).size)
        assertEquals("too-long", ErSoulTurn.workingReject(long, emptyList(), vouched))
    }

    @Test fun the_voucher_does_not_widen_the_word_bound() {
        // D20 repairs the shape: an over-long line is cut at a clause end, or dropped when there is
        // none. This one has no clause end inside the budget, so it is still absent from the voice.
        val eight = "We are about 90 seconds into this check"
        assertEquals(8, eight.split(Regex("\\s+")).size)
        assertEquals("too-many-words", ErSoulTurn.workingReject(eight, emptyList(), vouched))
        // The same line one word shorter speaks: the count is still the budget.
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
        // A grounded number buys the line nothing: bracket markup is refused on its own. Markdown
        // emphasis is a different case — D20 strips it rather than refusing.
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
