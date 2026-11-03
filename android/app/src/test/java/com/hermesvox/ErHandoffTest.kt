package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ErHandoff — the receipt and the no-silent-swallow rule.
 *
 * The release-gating report (0.9.1) was "ER sometimes swallows a query": a
 * factual question, a plausible soul answer, no Hermes. The unit tests here pin the two things
 * the client can actually promise — that the directive for that exact utterance asks for an
 * escalation, and that a turn which ends with neither voice answering is recorded — and they pin
 * what the client does NOT promise: the decision is a model output, so a soul `answer` is spoken
 * by design, and the field log is the instrument that measures how often it happens.
 */
class ErHandoffTest {

    /** The reporter's own utterance, verbatim. */
    private val reporterAsk = "what's the current price of a second-hand RTX 4090?"

    // ---- the decision function, driven with the reporter's example ----

    @Test fun the_reporters_question_is_a_turn_the_directive_escalates() {
        val d = ErSoulTurn.directive(reporterAsk)
        // The caller's words ride the render...
        assertTrue("the caller's line must reach the soul verbatim", d.contains("\"$reporterAsk\""))
        // ...and the escalation route is offered for exactly this kind of turn.
        assertTrue("the escalate token must be offered", d.contains(ErSoulTurn.ESCALATE))
        assertTrue("a fact must be named as the mind's", d.contains("needs a fact"))
        assertTrue("doubt must err toward the mind", d.contains("or if you are not sure"))
    }

    @Test fun the_escalation_token_parses_to_a_handoff_not_a_local_answer() {
        // What the directive asks for: the token plus a few words. It must never read as speech.
        val beat = ErSoulTurn.parse("${ErSoulTurn.ESCALATE} Good question")
        assertEquals("beat", ErSoulTurn.decisionName(beat))
        assertTrue("a handoff opens with the beat, it does not answer", beat is ErSoulTurn.Outcome.Beat)
        // A bare token is the same handoff without the opener.
        assertEquals("escalate", ErSoulTurn.decisionName(ErSoulTurn.parse(ErSoulTurn.ESCALATE)))
    }

    @Test fun a_soul_answer_is_spoken_by_design_and_so_must_be_instrumented() {
        // This is the hypothesis, stated as a test: nothing in the client refuses a factual line
        // on the turn lane (the digits/content guard, ErSoulTurn.judge, is the WORKING lane's).
        // So a turn the reporter says should have escalated can come back as `answer`, and the
        // only defence is that the turn is then measured.
        val soul = ErSoulTurn.parse("Probably around eight hundred dollars these days.")
        assertTrue("a turn answer is speech, by design", soul is ErSoulTurn.Outcome.Spoken)
        assertEquals("answer", ErSoulTurn.decisionName(soul))
    }

    // ---- the receipt: it fires on both outcomes ----

    @Test fun the_receipt_fires_on_both_outcomes() {
        val ok = ErHandoff.receipt(7L, true, ErHandoff.REASON_VOICE_TURN, "escalate", reporterAsk.length)
        assertTrue(ok, ok.startsWith("event=er-handoff gen=7 ok=true reason=voice-turn soul=escalate"))
        assertTrue("the caller's length, never the text", ok.endsWith("askChars=${reporterAsk.length}"))
        assertTrue("no transcript in the receipt", !ok.contains("RTX"))

        val dropped = ErHandoff.receipt(8L, false, ErHandoff.REASON_CANCELLED, "answer", 12)
        assertTrue(dropped, dropped.startsWith("event=er-handoff gen=8 ok=false reason=cancelled-before-submit"))
        assertTrue("a dropped turn still names the soul's decision", dropped.contains("soul=answer"))
    }

    // ---- the no-silent-swallow rule ----

    @Test fun a_soul_answer_the_mind_skipped_is_recorded_as_a_swallow() {
        val line = ErHandoff.swallow(
            gen = 11L,
            soulAnswered = true,
            submitted = true,
            mindWentSilent = true,
            cls = ErIntent.Class.MIND,
            askChars = reporterAsk.length,
            soulChars = 34,
        )
        assertNotNull("the reporter's shape must be measurable", line)
        assertTrue(line!!, line.startsWith("event=er-swallow gen=11 mind=skipped"))
        assertTrue(line, line.contains("cls=mind"))
        assertTrue(line, line.contains("askChars=${reporterAsk.length}"))
        assertTrue(line, line.contains("soulChars=34"))
    }

    @Test fun a_soul_answer_on_a_turn_the_mind_never_saw_is_a_swallow() {
        val line = ErHandoff.swallow(
            gen = 12L, soulAnswered = true, submitted = false, mindWentSilent = false,
            cls = ErIntent.Class.MIND, askChars = 40, soulChars = 20,
        )
        assertNotNull(line)
        assertTrue(line!!, line.contains("mind=not-submitted"))
    }

    @Test fun a_soul_answer_the_mind_overrode_is_not_a_swallow() {
        // The caller got an answer. Counting this as a swallow would inflate the rate that is
        // supposed to decide whether the guard needs teeth.
        assertNull(ErHandoff.swallow(
            gen = 13L, soulAnswered = true, submitted = true, mindWentSilent = false,
            cls = ErIntent.Class.MIND, askChars = 40, soulChars = 20,
        ))
    }

    @Test fun a_turn_the_soul_did_not_answer_is_not_a_swallow() {
        // Silence is the soul doing its job: the mind's turn, untouched.
        assertNull(ErHandoff.swallow(
            gen = 14L, soulAnswered = false, submitted = true, mindWentSilent = false,
            cls = ErIntent.Class.MIND, askChars = 40, soulChars = 0,
        ))
    }
}
