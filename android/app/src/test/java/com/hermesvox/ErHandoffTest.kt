package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ErHandoff — the receipt, and the reporter's question under D30.
 *
 * The release-gating report (0.9.1) was "ER sometimes swallows a query": a
 * factual question, a plausible soul answer, no Hermes. D30 removed the path: the soul is only
 * ever asked for a beat, a render that answers instead is not spoken, and the mind always
 * answers. The receipt still proves, per turn, that the mind was handed the turn.
 */
class ErHandoffTest {

    /** The reporter's own utterance, verbatim. */
    private val reporterAsk = "what's the current price of a second-hand RTX 4090?"

    // ---- the decision function, driven with the reporter's example ----

    @Test fun the_reporters_question_is_the_minds_and_the_soul_only_opens_it() {
        val d = ErSoulTurn.directive(reporterAsk)
        // The caller's words ride the render...
        assertTrue("the caller's line must reach the soul verbatim", d.contains("\"$reporterAsk\""))
        // ...and the soul is told the mind answers: there is no answer branch to misjudge.
        assertTrue("the escalate token must be offered", d.contains(ErSoulTurn.ESCALATE))
        assertTrue(d, d.contains("The mind will answer them; you do not."))
        assertFalse("no smalltalk-answer branch survives D30", d.contains("ONE short warm sentence"))
    }

    @Test fun the_escalation_token_parses_to_a_handoff_not_a_local_answer() {
        // What the directive asks for: the token plus a few words. It must never read as speech.
        val beat = ErSoulTurn.parse("${ErSoulTurn.ESCALATE} Good question")
        assertEquals("beat", ErSoulTurn.decisionName(beat))
        assertTrue("a handoff opens with the beat, it does not answer", beat is ErSoulTurn.Outcome.Beat)
        // A bare token is the same handoff without the opener.
        assertEquals("escalate", ErSoulTurn.decisionName(ErSoulTurn.parse(ErSoulTurn.ESCALATE)))
    }

    @Test fun a_soul_answer_on_a_turn_is_never_spoken() {
        // F1, the reporter's failure: a 2B that "helpfully" answers a price question. It is still
        // logged as `answer` (the decision mix), but a turn speaks only a beat, so it is silent —
        // and the mind, never told otherwise, answers.
        val soul = ErSoulTurn.parse("Probably around eight hundred dollars these days.")
        assertEquals("answer", ErSoulTurn.decisionName(soul))
        assertNull("a turn answer is not spoken", ErSoulTurn.turnBeat(soul))
        assertEquals("Hmm, good question...", ErSoulTurn.turnBeat(ErSoulTurn.parse("${ErSoulTurn.ESCALATE} Hmm, good question")))
        assertNull(ErSoulTurn.turnBeat(ErSoulTurn.parse(ErSoulTurn.ESCALATE)))
        assertNull(ErSoulTurn.turnBeat(ErSoulTurn.Outcome.Nothing))
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
}
