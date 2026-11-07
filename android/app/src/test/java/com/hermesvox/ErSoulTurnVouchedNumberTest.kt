package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D29/#406 — the no-new-numbers rule is dropped; the false-action-claim guard stays.
 *
 * Before: a number the directive did not carry was refused as `digits-unvouched` (#218, D20). Now
 * a number is never refused for being new. The client's own numbers are still computed
 * ([ErSoulTurn.vouchedNumbers]) because they are the grounded side of #256's observation: a
 * spoken number outside them is counted ([ErSoulTurn.unvouchedNumbers]) and logged, never gated.
 */
class ErSoulTurnVouchedNumberTest {

    /** The controller's directive: the caller has been waiting ninety seconds. */
    private val directive = ErSoulTurn.workingDirective("check the logs", null, emptyList(), 90L)
    private val vouched = ErSoulTurn.vouchedNumbers(directive)

    @Test fun a_line_with_the_clients_number_is_spoken() {
        assertEquals("We're about 90 seconds in...", ErSoulTurn.workingLine("We're about 90 seconds in"))
        assertNull(ErSoulTurn.workingReject("We're about 90 seconds in"))
    }

    @Test fun a_new_number_is_no_longer_refused_for_being_new() {
        // #406 acceptance: the guard no longer refuses a number for being new.
        assertEquals("We're about 45 seconds in...", ErSoulTurn.workingLine("We're about 45 seconds in"))
        assertNull(ErSoulTurn.workingReject("It'll take 2 minutes"))
        assertNull(ErSoulTurn.workingReject("90 seconds in, 90 to go"))
        assertNull(ErSoulTurn.workingReject("About 1 minute 30 seconds in"))
        assertNull(ErSoulTurn.workingReject("Three minutes and 40 seconds in"))
    }

    @Test fun the_false_action_claim_guard_is_unchanged_and_unconditional() {
        // #406 acceptance: the false-action-claim path is unchanged and still tested — a number,
        // grounded or not, buys a claimed result nothing.
        assertEquals(ErSoulTurn.REASON_UNBACKED_RESULT, ErSoulTurn.workingReject("I've booked it"))
        assertEquals(ErSoulTurn.REASON_UNBACKED_RESULT, ErSoulTurn.workingReject("I've booked 2 tables"))
        assertEquals(ErSoulTurn.REASON_UNBACKED_RESULT, ErSoulTurn.workingReject("It's done in 90 seconds"))
        assertNull(ErSoulTurn.workingLine("I've sent 3 emails"))
        assertEquals(ErSoulTurn.REASON_UNBACKED_RESULT, ErSoulTurn.workingReject("You're all set"))
    }

    @Test fun the_observation_names_the_numbers_the_client_did_not_supply() {
        assertEquals(setOf("90"), vouched)
        assertEquals(emptyList<String>(), ErSoulTurn.unvouchedNumbers("We're about 90 seconds in", vouched))
        assertEquals(listOf("45"), ErSoulTurn.unvouchedNumbers("We're about 45 seconds in", vouched))
        assertEquals(listOf("3"), ErSoulTurn.unvouchedNumbers("90 seconds in with 3 things left", vouched))
        assertEquals(listOf("1", "30"), ErSoulTurn.unvouchedNumbers("About 1 minute 30 seconds in", vouched))
        // A spelled number is prose; the observation is digit-shaped, as the old rule was.
        assertEquals(emptyList<String>(), ErSoulTurn.unvouchedNumbers("About ninety seconds in", vouched))
    }

    @Test fun every_number_the_directive_carries_is_grounded_not_only_the_wait() {
        val timer = ErSoulTurn.workingDirective("set a timer for 5 minutes")
        assertEquals(setOf("5"), ErSoulTurn.vouchedNumbers(timer))
        assertEquals(emptyList<String>(), ErSoulTurn.unvouchedNumbers("I'll give it 5 minutes", ErSoulTurn.vouchedNumbers(timer)))
    }

    @Test fun the_directive_offers_the_wait_it_knows() {
        assertTrue(directive.contains("waiting 90 seconds"))
        assertFalse("the old prohibition is gone", directive.contains("no other number"))
        assertFalse(ErSoulTurn.workingDirective("check the logs").contains("waiting"))
    }

    @Test fun no_prompt_bans_numbers_any_more() {
        // D29: the lexical prohibition goes from the prompts too, not only from the guard.
        assertFalse(ErSoulTurn.directive("hello").contains("number"))
        assertFalse(ErSoulTurn.narrationDirective("web_search").contains("number"))
        assertFalse(directive.contains("number"))
    }

    // ---- dropping the number rule widens nothing else ----

    @Test fun the_length_bounds_are_unchanged() {
        val long = "Extraordinarily complicated authentication verification ongoing 90"
        assertEquals("too-long", ErSoulTurn.workingReject(long))
        val eight = "We are about 90 seconds into this check"
        assertEquals("too-many-words", ErSoulTurn.workingReject(eight))
        assertNull(ErSoulTurn.workingReject("We are about 90 seconds in"))
        assertEquals(7, ErSoulTurn.MAX_OPENER_WORDS)
        assertEquals(60, ErSoulTurn.MAX_OPENER_CHARS)
    }

    @Test fun the_repeat_and_markup_bounds_are_unchanged() {
        assertEquals("repeat", ErSoulTurn.workingReject("About 90 seconds in", listOf("about 90 seconds in")))
        assertEquals("digits-or-markup", ErSoulTurn.workingReject("[thinking] about 90 seconds in"))
        assertEquals("digits-or-markup", ErSoulTurn.workingReject("<aside> about 90 seconds in"))
    }

    @Test fun the_beat_may_carry_a_number_but_never_a_claimed_result() {
        // The beat's opener no longer refuses a number (#406), and it now runs the
        // false-action-claim guard it did not run before — which can only refuse more lines.
        assertEquals("about 90 seconds in...", ErSoulTurn.opener("about 90 seconds in"))
        assertEquals(ErSoulTurn.Outcome.Beat("about 90 seconds in..."), ErSoulTurn.parse("<<ESCALATE>> about 90 seconds in"))
        assertNull(ErSoulTurn.opener("I've booked it"))
        assertEquals(ErSoulTurn.Outcome.Escalate, ErSoulTurn.parse("<<ESCALATE>> I've booked it"))
    }
}
