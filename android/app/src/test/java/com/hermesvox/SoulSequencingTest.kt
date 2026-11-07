package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The ER turn's sequencing (D30): the mind is never told the voice answered, and the soul's warm
 * conversation rotates before its KV cache fills.
 */
class SoulSequencingTest {

    // ---- D30: the mind is never told the voice answered ----

    @Test fun the_sync_never_offers_the_mind_a_skip() {
        // Even an action logged as an answer (an old soul line still in the ring) is synced as
        // context only: the mind answers the question itself, always.
        val e = ErDrift.sync(listOf(ErDrift.SoulAction(0, "answer", "Hey! Good to hear you.")), ErDrift.Vibe())
        assertFalse(e, e.contains("SKIP"))
        assertFalse(e, e.contains("already answered"))
        assertTrue(e, e.lowercase().contains("do not repeat or contradict those lines; answer the question itself"))
    }

    @Test fun a_beat_is_synced_but_the_mind_still_answers() {
        val e = ErDrift.sync(listOf(ErDrift.SoulAction(0, "beat", "Hmm, good one...")), ErDrift.Vibe())
        assertTrue(e.contains("beat=\"Hmm, good one...\""))
        assertTrue(e.lowercase().contains("answer the question itself"))
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
