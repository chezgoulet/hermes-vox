package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ErBargeGate — the semantic barge decision (Phase 5). The sacrosanct rule
 * under test: the filler ALWAYS cuts (physical), but the mind is cancelled
 * ONLY on a genuine barge; ambiguity defaults to HOLD (the asymmetry).
 */
class ErBargeGateTest {

    @Test fun genuine_barges_cancel_the_mind() {
        for (t in listOf("stop", "wait, actually", "never mind", "no wait — instead do X",
            "scratch that", "cancel that", "excuse me", "what?")) {
            assertEquals("'$t' cancels", ErBargeGate.Verdict.CANCEL_MIND, ErBargeGate.decide(t))
        }
    }

    @Test fun backchannels_hold_the_mind() {
        // THE asymmetry (the maintainer's "take your time" question): a patient
        // user must never kill 15s of the mind's work.
        for (t in listOf("take your time", "no rush", "it's okay", "go on",
            "mhmm", "okay, go on", "still there", "right")) {
            assertEquals("'$t' holds", ErBargeGate.Verdict.HOLD_MIND, ErBargeGate.decide(t))
        }
    }

    @Test fun silence_holds_the_mind() {
        // A physical barge with no stable utterance text yet: HOLD. We never
        // cancel on nothing.
        assertEquals(ErBargeGate.Verdict.HOLD_MIND, ErBargeGate.decide(null))
        assertEquals(ErBargeGate.Verdict.HOLD_MIND, ErBargeGate.decide(""))
        assertEquals(ErBargeGate.Verdict.HOLD_MIND, ErBargeGate.decide("   "))
    }

    @Test fun ambiguous_utterances_hold_the_mind() {
        // The default: anything not clearly a genuine barge holds the mind.
        for (t in listOf("hmm", "the thing", "okay then", "so")) {
            assertEquals("'$t' defaults to hold", ErBargeGate.Verdict.HOLD_MIND, ErBargeGate.decide(t))
        }
    }

    // ---- F3: a HOLD with words carries them into the next turn ----

    @Test fun a_re_ask_spoken_during_generation_holds_the_mind_AND_is_carried() {
        // The reviewer's field case: the mind is mid-lookup and the caller says this again.
        // It is not a "stop" barge, so the mind keeps working — and the words must not vanish,
        // which is what "seeded=false reason=hold" used to mean. `carries` is the rule the
        // controller uses to queue them ([PendingTurn]) instead of discarding them.
        for (t in listOf(
            "what about the second one",
            "so what's the price",
            "and the email one",
            "I asked you about the train times",
        )) {
            assertEquals("'$t' keeps the mind working", ErBargeGate.Verdict.HOLD_MIND, ErBargeGate.decide(t))
            assertTrue("'$t' is carried into the next turn", ErBargeGate.carries(t))
        }
    }

    @Test fun the_patient_caller_is_absorbed_not_queued() {
        // A backchannel is the one thing a hold is FOR: it must not become a turn of its own,
        // or "mm-hm" would be answered as a question.
        for (t in listOf("take your time", "no rush", "okay, go on", "mhmm", "still there", "right")) {
            assertEquals("'$t' holds", ErBargeGate.Verdict.HOLD_MIND, ErBargeGate.decide(t))
            assertFalse("'$t' is not a turn", ErBargeGate.carries(t))
        }
    }

    @Test fun nothing_is_carried_from_silence() {
        assertFalse(ErBargeGate.carries(null))
        assertFalse(ErBargeGate.carries(""))
        assertFalse(ErBargeGate.carries("   "))
    }

    @Test fun a_genuine_barge_cancels_rather_than_queueing() {
        // A cancel already becomes the next turn through the audio carry (BargeCarry): queueing
        // it as well would ask the same question twice.
        assertEquals(ErBargeGate.Verdict.CANCEL_MIND, ErBargeGate.decide("no wait, stop"))
    }
}
