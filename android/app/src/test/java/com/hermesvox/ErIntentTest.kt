package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ErIntent — the 5-class classifier. The invariant under test: real questions
 * and actions ESCALATE to the mind (never answered by the soul), backchannels
 * NEVER escalate, and ambiguity defaults to the mind. Plus the Phase 5 seed:
 * genuine barges vs backchannels.
 */
class ErIntentTest {

    private fun route(t: String) = ErIntent.classify(t).route
    private fun cls(t: String) = ErIntent.classify(t).cls

    // ---- backchannel: HOLD_ONLY, never escalates ----

    @Test fun backchannels_hold_and_never_escalate() {
        for (t in listOf(
            "take your time", "no rush", "it's okay", "go on", "go ahead",
            "mhmm", "uh huh", "okay", "ok", "sure", "right", "yeah", "still there",
        )) {
            assertEquals("'$t' must hold", ErIntent.Route.HOLD_ONLY, route(t))
            assertEquals("'$t'", ErIntent.Class.BACKCHANNEL, cls(t))
        }
    }

    @Test fun backchannel_with_punctuation_still_holds() {
        assertEquals(ErIntent.Route.HOLD_ONLY, route("Take your time."))
        assertEquals(ErIntent.Route.HOLD_ONLY, route("okay, go on"))
    }

    // ---- information/action: ACK_AND_YIELD (the mind's lane) ----

    @Test fun questions_escalate_to_the_mind() {
        for (t in listOf(
            "what's the wind at the grasslands?",
            "when does the season open",
            "how do I brine a turkey",
            "tell me about the Bjorkquist decision",
            "is there rain coming",
        )) {
            assertEquals("'$t' must yield to the mind", ErIntent.Route.ACK_AND_YIELD, route(t))
        }
    }

    @Test fun actions_escalate_to_the_mind() {
        for (t in listOf(
            "remind me to call the bank",
            "send an email to Josh",
            "set a timer for 20 minutes",
            "turn on the porch lights",
            "schedule the coop delivery",
        )) {
            assertEquals(ErIntent.Route.ACK_AND_YIELD, route(t))
            assertEquals(ErIntent.Class.ACTION, cls(t))
        }
    }

    // ---- the soul's own lane: SOUL_DIRECT ----

    @Test fun emotion_and_smalltalk_stay_with_the_soul() {
        for (t in listOf(
            "I'm worried about the move",
            "hello!",
            "good morning",
            "how are you today",
            "thank you for yesterday",
            "who are you really",
        )) {
            assertEquals("'$t' must be the soul's", ErIntent.Route.SOUL_DIRECT, route(t))
        }
    }

    // ---- the ambiguity default ----

    @Test fun ambiguity_defaults_to_the_mind() {
        // A bare noun-ish utterance that matches nothing: yield (cheap miss).
        assertEquals(ErIntent.Route.ACK_AND_YIELD, route("hmm the thing about the coop"))
    }

    // ---- the ordering guarantees (safety design) ----

    @Test fun a_question_after_backchannel_opening_still_escalates() {
        // "okay what's the weather" — the backchannel opener must not swallow
        // the question. Backchannel matches are exact/leading-token; the
        // question word still triggers escalation.
        assertEquals(ErIntent.Route.ACK_AND_YIELD, route("okay what's the weather"))
    }

    @Test fun genuine_barge_beats_a_question_shape() {
        // "wait, what's this?" — the redirect is the barge, not a question to answer.
        assertTrue(ErIntent.isGenuineBarge("wait, what's this?"))
        assertTrue(ErIntent.isGenuineBarge("actually, cancel that"))
        assertTrue(ErIntent.isGenuineBarge("no wait — instead do X"))
    }

    @Test fun take_your_time_is_never_a_barge() {
        // THE asymmetry (Christopher's question, sacrosanct): a patient
        // backchannel must never cancel the mind's 15s of work.
        for (t in listOf("take your time", "no rush", "it's okay", "go on", "still there")) {
            assertTrue("'$t' must NOT barge", !ErIntent.isGenuineBarge(t))
        }
    }
}
