package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ErSoulTurn — the soul's turn contract. Under test: the directive asks the question AND
 * carries the tool context in one call, the parse errs toward escalating, and the same-text
 * guard catches the exact repetition the field produced.
 */
class ErSoulTurnTest {

    // ---- the directive: one call, and it carries what the soul needs ----

    @Test fun the_directive_carries_the_callers_words_and_the_token() {
        val d = ErSoulTurn.directive("hey, how are you?")
        assertTrue(d.contains("\"hey, how are you?\""))
        assertTrue("the escalate route must be offered", d.contains(ErSoulTurn.ESCALATE))
    }

    @Test fun the_directive_carries_the_tool_context_when_known() {
        // Point 1, Christopher: the soul should know what the mind is using so it can be
        // topical — and it rides the SAME render as the decision, so it costs no extra call.
        val d = ErSoulTurn.directive("what's in my inbox?", "reading the email inbox")
        assertTrue(d.contains("reading the email inbox"))
        // And with no context it must not leave an empty clause behind.
        assertFalse(ErSoulTurn.directive("hi", null).contains("working on"))
        assertFalse(ErSoulTurn.directive("hi", "  ").contains("working on"))
    }

    // ---- the parse: escalation wins ----

    @Test fun a_bare_or_wrapped_token_escalates() {
        for (r in listOf(
            ErSoulTurn.ESCALATE,
            "  ${ErSoulTurn.ESCALATE}  ",
            "\"${ErSoulTurn.ESCALATE}\"",
            "<ESCALATE>",
            "ESCALATE",
            "escalate.",
        )) {
            assertEquals("'$r' must escalate", ErSoulTurn.Outcome.Escalate, ErSoulTurn.parse(r))
        }
    }

    @Test fun a_line_that_merely_mentions_the_token_still_escalates() {
        // The asymmetry is deliberate: a false escalate costs the mind answering instead, while
        // a missed escalate means the soul spoke when it should not have.
        assertEquals(ErSoulTurn.Outcome.Escalate,
            ErSoulTurn.parse("I would ${ErSoulTurn.ESCALATE} this one."))
    }

    @Test fun an_answer_is_spoken() {
        assertEquals(ErSoulTurn.Outcome.Spoken("Hello, Christopher."),
            ErSoulTurn.parse("  Hello, Christopher.  "))
    }

    @Test fun a_blank_render_is_nothing_not_speech() {
        assertEquals(ErSoulTurn.Outcome.Nothing, ErSoulTurn.parse(null))
        assertEquals(ErSoulTurn.Outcome.Nothing, ErSoulTurn.parse(""))
        assertEquals(ErSoulTurn.Outcome.Nothing, ErSoulTurn.parse("   "))
    }

    // ---- the same-text guard: the field defect, pinned ----

    @Test fun the_same_line_inside_the_window_is_a_repeat() {
        // Exactly the field pair: this sentence seven times in thirty-nine seconds.
        val said = "Hello, Christopher. How can I help you today?"
        val recent = listOf(said to 1_000L)
        assertTrue(ErSoulTurn.isRepeat(said, recent, 2_000L))
        assertTrue("case/punctuation-insensitive", ErSoulTurn.isRepeat("hello christopher how can i help you today", recent, 2_000L))
        assertTrue("still inside the window", ErSoulTurn.isRepeat(said, recent, 30_000L))
    }

    @Test fun a_different_line_is_not_a_repeat_and_the_window_expires() {
        val recent = listOf("Hello, Christopher." to 1_000L)
        assertFalse("a different line is what a person says next",
            ErSoulTurn.isRepeat("Checking your inbox now.", recent, 2_000L))
        assertFalse("outside the window it may be said again",
            ErSoulTurn.isRepeat("Hello, Christopher.", recent, 40_000L))
    }

    @Test fun silence_is_treated_as_a_repeat() {
        assertTrue(ErSoulTurn.isRepeat("   ", emptyList(), 0L))
    }
}
