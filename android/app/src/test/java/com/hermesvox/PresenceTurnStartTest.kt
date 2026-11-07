package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F5 (2026-11-04 Presence review): "Every confirmation gets a canned non-sequitur."
 *
 * The field's best moment —
 *
 *     "Want me to write those points to a markdown file?"  →  "Yes."
 *     →  *"okay — take the time you need"*
 *
 * — is what [ErPresence.autoLine] exists to make impossible. The backchannel list was written for
 * the patient caller *while the mind works*; applied at turn start it answered a confirmation with
 * a non-sequitur and suppressed the beat. So: at turn start the phone says nothing of its own (the
 * soul may still open the turn with a beat), and during the mind's work the hold line stays for the
 * patient caller only.
 */
class PresenceTurnStartTest {

    @Test fun a_turn_start_never_gets_the_hold_line() {
        for (route in ErIntent.Route.values()) {
            assertNull("'$route' at turn start must be answered, not acknowledged",
                ErPresence.autoLine(route, atTurnStart = true))
        }
    }

    @Test fun yes_at_turn_start_is_an_answer_to_the_last_question() {
        // The classification of "yes" does not change; what changes is what the phone does with it.
        assertEquals(ErIntent.Route.HOLD_ONLY, ErIntent.classify("yes").route)
        assertNull(ErPresence.autoLine(ErIntent.Route.HOLD_ONLY, atTurnStart = true))
    }

    @Test fun the_patient_caller_is_still_acknowledged_while_the_mind_works() {
        // During the work the same words mean "take your time", and that is what the line is for.
        assertEquals(ErPresence.HOLD_LINE, ErPresence.autoLine(ErIntent.Route.HOLD_ONLY, atTurnStart = false))
    }

    @Test fun a_content_utterance_inside_a_turn_is_never_acknowledged_by_the_phone() {
        // "yes, and what about the second one" is the mind's lane: no canned line, no filler ack.
        assertNull(ErPresence.autoLine(ErIntent.Route.ACK_AND_YIELD, atTurnStart = false))
    }

    @Test fun the_line_itself_is_the_non_sequitur_that_was_the_bug() {
        // Pinned so the wording cannot come back at turn start by accident: this exact string is
        // what answered a confirmation, and `a_turn_start_never_gets_the_hold_line` is why it no
        // longer can.
        assertTrue(ErPresence.HOLD_LINE.contains("take the time"))
        assertNotEquals(ErPresence.HOLD_LINE, ErPresence.autoLine(ErIntent.Route.HOLD_ONLY, atTurnStart = true))
    }
}
