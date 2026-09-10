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

    // ---- 0.8/M3: a discourse marker must not hide a greeting ----

    @Test fun a_discourse_marker_does_not_hide_a_greeting() {
        // The field miss: "So how's it going?" matched INFORMATION, because no greeting
        // pattern can match a string that begins with "so".
        for (t in listOf(
            "so how's it going?", "So how's it going?", "well hello there",
            "okay so hey", "hey there", "and good morning", "hmm, hello there",
        )) {
            assertEquals("'$t' must be soul-direct", ErIntent.Route.SOUL_DIRECT, route(t))
            assertEquals("'$t'", ErIntent.Class.SMALLTALK, cls(t))
        }
    }

    @Test fun a_greeting_with_a_real_question_behind_it_escalates() {
        // The ordering guard. The greeting check runs BEFORE the information sweep (so
        // "how are you" is never read as a bare "how"), which used to make a real request
        // behind a greeting classify as smalltalk — and smalltalk skips the presence ladder
        // entirely, so the soul went silent for the whole mind-work window. It must
        // escalate instead: the soul cannot answer these.
        for (t in listOf(
            "hey, what's the weather", "hi, can you send the email",
            "hello, where is the file", "hey there, are you busy",
            "good morning — what time is it", "hi, do you remember me",
            // The second path: this one reaches SMALLTALK_PATTERNS, not the greeting list.
            "hey there, what's the weather", "what's up with the server",
            // And the third: emotion is a soul-lane trigger too.
            "i'm tired, can you send the email",
        )) {
            assertEquals("'$t' must escalate", ErIntent.Route.ACK_AND_YIELD, route(t))
        }
    }

    @Test fun a_pure_greeting_is_still_smalltalk() {
        // The traps the guard must NOT over-escalate: every one of these carries no
        // question, and "hey there, how are you" in particular contains a second greeting
        // that a naive leftover scan would read as a question word.
        for (t in listOf(
            "hey there", "how are you", "how are you doing today", "hey there, how are you",
            "so how's it going?", "good morning", "well hello there", "hi", "who are you",
        )) {
            assertEquals("'$t' must stay smalltalk", ErIntent.Route.SOUL_DIRECT, route(t))
        }
    }

    @Test fun a_backchannel_plus_a_short_remnant_stays_a_backchannel() {
        // The backchannel check runs FIRST and is sacrosanct — it must never escalate.
        // "hmm, hi" therefore HOLDS (a marker + a <2-char remnant) rather than greeting.
        // stripDiscourse() does not touch that priority; this pins it so a future edit to
        // the greeting check cannot silently reorder the two rules. (Caught by the gate:
        // this test began life asserting the opposite, and was wrong.)
        //
        // Known edge, deliberately not "fixed" here: this path's ack line is written for
        // patience ("take the time you need"), which reads oddly after a greeting. That is
        // the backchannel ACK's wording, a different concern from routing, and it fires
        // before the stream opens so it is unaffected by the glue-guard narrowing.
        assertEquals(ErIntent.Route.HOLD_ONLY, route("hmm, hi"))
        assertEquals(ErIntent.Class.BACKCHANNEL, cls("hmm, hi"))
    }

    @Test fun stripping_discourse_does_not_swallow_a_real_question() {
        // The strip is scoped to the greeting check, so a question standing behind a
        // marker still escalates. The soul must never answer a real question.
        for (t in listOf(
            "so what's the weather", "well, where is the file", "okay, what time is it",
            "and how much does it cost", "so can you send the email",
        )) {
            assertEquals("'$t' must escalate", ErIntent.Route.ACK_AND_YIELD, route(t))
        }
    }

    @Test fun stripping_never_hides_a_barge() {
        // "stop" and "wait" are NOT discourse markers. They must keep cancelling —
        // a marker list that swallowed an imperative would be a serious regression.
        for (t in listOf("stop", "wait", "actually stop", "no wait — instead do X")) {
            assertTrue("'$t' must still barge", ErIntent.isGenuineBarge(t))
        }
    }
}
