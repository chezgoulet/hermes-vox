package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ErIntent — the SAFETY classifier (0.8/M3c). The contract under test is much smaller than
 * it used to be, and that is the point:
 *
 *  - a backchannel HOLDS and never escalates or cancels (the sacrosanct asymmetry), and
 *  - every other utterance is the MIND's lane, so the presence ladder always runs and no
 *    turn can be silently skipped by a misroute.
 *
 * The old suite asserted which of greeting/smalltalk/emotion/information/action each phrase
 * belonged to. That routing is gone — the soul model decides it now — and those assertions
 * went with it. What remains is the part that must never be a judgement call.
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

    @Test fun a_backchannel_plus_a_short_remnant_stays_a_backchannel() {
        // A marker plus a <3-char remnant still holds — "hmm, hi" is a hesitation, not a
        // greeting. This priority is sacrosanct and pinned so a future edit cannot reorder it.
        assertEquals(ErIntent.Route.HOLD_ONLY, route("hmm, hi"))
        assertEquals(ErIntent.Class.BACKCHANNEL, cls("hmm, hi"))
    }

    @Test fun a_backchannel_opener_with_a_real_question_escalates() {
        // The compounding rule that survives: "okay, what's the weather" is a patient opener
        // followed by a real question, and the question must reach the mind.
        assertEquals(ErIntent.Route.ACK_AND_YIELD, route("okay what's the weather"))
    }

    // ---- the de-routing: nothing else is classified as a lane ----

    @Test fun every_non_backchannel_turn_is_the_minds_lane() {
        // The regression this pins: these are exactly the utterances that the deleted keyword
        // routing got WRONG in the field (greeting variants, a question behind a greeting, a
        // discourse marker in front of a greeting). None of them may be routed away from the
        // mind any more, because a lane that skips the presence ladder goes silent — which is
        // the failure this whole change removes. Whether the SOUL also speaks is now the soul
        // model's decision, not this classifier's.
        for (t in listOf(
            "hey there", "how are you", "how are ya?", "so how's it going?",
            "well hello there", "hello there. how are ya?", "who are you",
            "hey, what's the weather", "hey there, what's the weather",
            "what's up with the server", "can you send the email",
            "i'm tired, can you send the email", "what time is it in Windsor",
        )) {
            assertEquals("'$t' must be the mind's lane", ErIntent.Route.ACK_AND_YIELD, route(t))
            assertEquals("'$t'", ErIntent.Class.MIND, cls(t))
        }
    }

    @Test fun the_quiet_caller_still_yields_to_the_mind_rather_than_vanishing() {
        // An empty transcript is the one case that holds: there is nothing to answer.
        assertEquals(ErIntent.Route.HOLD_ONLY, route(""))
    }

    // ---- genuine barge: the safety decision, unchanged ----

    @Test fun barges_are_genuine() {
        assertTrue(ErIntent.isGenuineBarge("never mind"))
        assertTrue(ErIntent.isGenuineBarge("actually, cancel that"))
        assertTrue(ErIntent.isGenuineBarge("no wait — instead do X"))
        assertTrue(ErIntent.isGenuineBarge("stop"))
    }

    @Test fun take_your_time_is_never_a_barge() {
        // THE asymmetry (Christopher's question, sacrosanct): a patient backchannel must never
        // cancel the mind's fifteen seconds of work.
        for (t in listOf("take your time", "no rush", "it's okay", "go on", "still there", "hmm, hi")) {
            assertFalse("'$t' must NOT barge", ErIntent.isGenuineBarge(t))
        }
    }
}
