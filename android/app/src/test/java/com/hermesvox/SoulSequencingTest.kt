package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The ER turn's sequencing: the soul decides first (bounded, adaptive wait), the mind is told what
 * the voice did, the mind's skip token is never heard, and the soul's warm conversation rotates
 * before its KV cache fills.
 */
class SoulSequencingTest {

    // ---- SoulGate: how long the mind's submit waits on the soul ----

    @Test fun no_measurement_yet_assumes_a_warm_render() {
        assertEquals(SoulGate.DEFAULT_WAIT_MS, SoulGate.waitMs(null))
    }

    @Test fun a_fast_soul_is_waited_for_just_past_its_median() {
        assertEquals(600L, SoulGate.waitMs(400L))
    }

    @Test fun the_wait_is_capped() {
        assertEquals(SoulGate.MAX_WAIT_MS, SoulGate.waitMs(1100L))
    }

    @Test fun a_slow_soul_is_never_waited_for() {
        // A CPU fallback must not add latency to every mind turn: run in parallel, as before.
        assertEquals(0L, SoulGate.waitMs(2400L))
    }

    @Test fun a_late_decision_may_only_speak_a_beat() {
        assertTrue(SoulGate.lateMaySpeak(isBeat = true))
        assertFalse("a late answer the mind was not told about is the double answer",
            SoulGate.lateMaySpeak(isBeat = false))
    }

    // ---- MindSkip: the mind's silence is never heard ----

    private fun stream(active: Boolean, vararg deltas: String): Pair<String, Boolean> {
        val f = MindSkip.Filter(active)
        val out = StringBuilder()
        for (d in deltas) out.append(f.feed(d))
        out.append(f.finish())
        return out.toString() to f.skipped
    }

    @Test fun the_skip_token_split_across_deltas_is_swallowed() {
        assertEquals("" to true, stream(true, "<<", "SK", "IP", ">>"))
    }

    @Test fun a_real_answer_passes_through_intact() {
        assertEquals("Actually, one thing — your meeting moved." to false,
            stream(true, "Actually,", " one thing — your meeting moved."))
    }

    @Test fun an_answer_that_starts_like_the_token_is_released() {
        assertEquals("<<b>> is markdown" to false, stream(true, "<<", "b>> is markdown"))
    }

    @Test fun text_after_the_token_is_the_minds_override() {
        assertEquals("One more thing." to true, stream(true, "<<SKIP>> ", "One more thing."))
    }

    @Test fun an_inactive_filter_is_the_identity() {
        assertEquals("<<SKIP>>" to false, stream(false, "<<SKIP>>"))
    }

    @Test fun strip_removes_only_a_leading_token() {
        assertEquals("", MindSkip.strip("  <<SKIP>>  "))
        assertEquals("fine <<SKIP>>", MindSkip.strip("fine <<SKIP>>"))
    }

    // ---- The mind is told the voice answered ----

    @Test fun the_epilogue_tells_the_mind_the_voice_answered_and_offers_the_token() {
        val e = ErDrift.epilogue(
            listOf(ErDrift.SoulAction(0, "answer", "Hey! Good to hear you.")), ErDrift.Vibe(), soulAnswered = true)
        assertTrue(e.contains("answer=\"Hey! Good to hear you.\""))
        assertTrue(e.contains("the voice already answered"))
        assertTrue(e.contains(MindSkip.TOKEN))
    }

    @Test fun a_beat_is_synced_but_the_mind_still_answers() {
        val e = ErDrift.epilogue(listOf(ErDrift.SoulAction(0, "beat", "Hmm, good one...")), ErDrift.Vibe())
        assertTrue(e.contains("beat=\"Hmm, good one...\""))
        assertTrue(e.contains("answer the question itself"))
        assertFalse(e.contains(MindSkip.TOKEN))
    }

    // ---- SoulBudget: the warm conversation rotates before the KV cache fills ----

    @Test fun the_conversation_rotates_before_the_cache_is_full() {
        assertFalse(SoulBudget.shouldRotate(600))
        assertFalse(SoulBudget.shouldRotate(SoulBudget.MAX_TOKENS - SoulBudget.HEADROOM_TOKENS - 1))
        assertTrue(SoulBudget.shouldRotate(SoulBudget.MAX_TOKENS - SoulBudget.HEADROOM_TOKENS))
        assertTrue("an unreadable count rotates", SoulBudget.shouldRotate(-1))
    }

    // ---- The orchestrator: a turn decision is never dropped behind a narration ----

    private class SlowExpress : VoxExpress {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile var cancelled = false
        override val available = true
        override fun express(intent: String, content: String, tone: String): String {
            if (intent == VoiceOrchestrator.INTENT_SOUL_NARRATE) {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                return if (cancelled) "" else "narration"
            }
            return "decision:$content"
        }
        override fun cancelInFlight() { cancelled = true; release.countDown() }
    }

    @Test fun an_urgent_turn_preempts_a_narration_and_still_runs() {
        val ex = SlowExpress()
        val orch = VoiceOrchestrator(ex) { }
        var narration: String? = "unset"
        val narrDone = CountDownLatch(1)
        orch.expressAsync(VoiceOrchestrator.INTENT_SOUL_NARRATE, "tool") { narration = it; narrDone.countDown() }
        assertTrue(ex.started.await(2, TimeUnit.SECONDS))

        var decision: String? = null
        val decided = CountDownLatch(1)
        orch.expressAsync(VoiceOrchestrator.INTENT_SOUL_TURN, "hi") { decision = it; decided.countDown() }

        assertTrue("the turn decision must run, not be dropped", decided.await(3, TimeUnit.SECONDS))
        assertEquals("decision:hi", decision)
        assertTrue(narrDone.await(2, TimeUnit.SECONDS))
        assertEquals("the cancelled narration must not be spoken half-formed", "", narration)
    }

    @Test fun a_narration_is_dropped_while_another_render_runs() {
        val ex = SlowExpress()
        val orch = VoiceOrchestrator(ex) { }
        orch.expressAsync(VoiceOrchestrator.INTENT_SOUL_NARRATE, "a") { }
        assertTrue(ex.started.await(2, TimeUnit.SECONDS))
        var second = false
        orch.expressAsync(VoiceOrchestrator.INTENT_SOUL_NARRATE, "b") { second = true }
        ex.release.countDown()
        Thread.sleep(200)
        assertFalse(second)
    }

    // ---- The stand-in never speaks a directive ----

    @Test fun the_stand_in_has_nothing_to_say_for_a_soul_directive() {
        // Without the model, RoutedExpress's default branch echoed its content — which for a soul
        // intent is the DIRECTIVE ("The caller just said…"), parsed as an answer and spoken.
        val r = RoutedExpress()
        assertEquals("", r.express(VoiceOrchestrator.INTENT_SOUL_TURN, ErSoulTurn.directive("hi")))
        assertEquals("", r.express(VoiceOrchestrator.INTENT_SOUL_NARRATE, ErSoulTurn.narrationDirective("web_search")))
        assertEquals(ErSoulTurn.Outcome.Nothing, ErSoulTurn.parse(r.express(VoiceOrchestrator.INTENT_SOUL_TURN, "x")))
    }
}
