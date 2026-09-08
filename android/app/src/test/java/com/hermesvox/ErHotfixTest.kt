package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.6.2 hotfix invariants, pure JVM:
 *  - HOLD-drop: a released-by-hold gate must settle like an ordinary turn
 *    (the reply speaks); a genuinely-cancelled turn still drops.
 *  - filler cap: the user slider reaches ErFillers.tick (0 = fully silent).
 *  - ErArbiter unchanged (the arbiter test covers the table).
 */
class ErHotfixTest {

    // ---- HOLD-drop (the settle rule gains a hold-alive witness) ----

    @Test fun hold_then_done_on_nonstreaming_speaks_normally() {
        // The exact bug: er-barge-hold released gen=5's gate; the mind finishes;
        // the non-streaming leg settles with gateReleased=true. Without the hold
        // witness this was DROP (silent text). With it: SPEAK.
        assertEquals(ReplySettle.SPEAK,
            decide(streamed = false, genSpokeAudio = false, gateReleased = true, holdAlive = true))
        // And the streaming leg is unaffected (it retires through the worker).
        assertEquals(ReplySettle.RETIRE_STREAM,
            decide(streamed = true, genSpokeAudio = true, gateReleased = true, holdAlive = true))
    }

    @Test fun a_real_cancel_still_drops_even_after_a_prior_hold() {
        // The witness is keyed to the gen and consumed once: a LATER turn that
        // was genuinely cancelled must still DROP (no ghost re-speak regression).
        assertEquals(ReplySettle.DROP,
            decide(streamed = false, genSpokeAudio = false, gateReleased = true, holdAlive = false))
    }

    private fun decide(
        streamed: Boolean,
        streamingEngine: Boolean = true,
        genSpokeAudio: Boolean,
        gateReleased: Boolean,
        speakAllowed: Boolean = true,
        hasText: Boolean = true,
        holdAlive: Boolean,
    ): ReplySettle {
        // Mirror the 0.6.2 settleReply arithmetic: holdAlive neutralizes the
        // released-gate-as-cancel read (the controller consumes the witness).
        val cancelled = gateReleased && !holdAlive
        return ReplySettleRule.decide(streamed, streamingEngine, genSpokeAudio, cancelled, speakAllowed, hasText)
    }

    // ---- filler cap (the user slider) ----

    @Test fun cap_zero_is_fully_silent() {
        val o = ErFillers.tick(10_000L + 1_500L, 10_000L, 0, warm = false, userGoneMs = 0L, cap = 0)
        assertEquals(null, o.speak)
        assertEquals(ErFillers.State.SILENT, o.state)
    }

    @Test fun cap_one_allows_one_filler_then_silence() {
        val first = ErFillers.tick(10_000L + 1_500L, 10_000L, 0, warm = false, userGoneMs = 0L, cap = 1)
        assertTrue(first.speak != null)
        val second = ErFillers.tick(10_000L + 2_500L, 10_000L, 1, warm = false, userGoneMs = 0L, cap = 1)
        assertEquals(null, second.speak)
    }

    @Test fun default_cap_unchanged_at_two() {
        val second = ErFillers.tick(10_000L + 2_500L, 10_000L, 2, warm = false, userGoneMs = 0L)
        assertEquals(null, second.speak)
        val first = ErFillers.tick(10_000L + 1_500L, 10_000L, 1, warm = false, userGoneMs = 0L)
        assertTrue(first.speak != null)
    }

    // ---- async express contract ----

    @Test fun async_express_delivers_the_fallback_glue() {
        var got: String? = null
        val orch = VoiceOrchestrator(RoutedExpress())
        orch.expressAsync("working", "", "calm") { got = it }
        // The fallback path is instant; a bounded join keeps the test deterministic.
        val deadline = System.currentTimeMillis() + 2_000
        while (got == null && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("async express must deliver the fallback glue", got != null)
        assertTrue(got!!.contains("look", ignoreCase = true) || got!!.contains("dig", ignoreCase = true))
    }
}
