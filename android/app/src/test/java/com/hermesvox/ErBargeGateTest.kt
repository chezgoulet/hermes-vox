package com.hermesvox

import org.junit.Assert.assertEquals
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
        // THE asymmetry (Christopher's "take your time" question): a patient
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
}
