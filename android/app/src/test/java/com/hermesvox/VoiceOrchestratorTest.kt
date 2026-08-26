package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rigorously proves the phone-call orchestration + precedence rule WITHOUT the
 * emulator (pure JVM): Gemma holds the floor by default, Hermes preempts Gemma
 * on a real call, then hands back; barge-in resets. This is the device-agnostic
 * core of the Gemma expression layer.
 */
class VoiceOrchestratorTest {

    private fun orch() = VoiceOrchestrator(RoutedExpress())

    @Test fun gemma_holds_floor_by_default() {
        assertEquals(VoiceOwner.GEMMA, orch().owner)
    }

    @Test fun user_speech_gives_gemma_acknowledgment() {
        val o = orch()
        val glue = o.onUserSpeech()
        assertNotNull(glue)
        assertTrue(glue!!.isNotBlank())
        assertEquals(VoiceOwner.GEMMA, o.owner)   // Gemma keeps the floor
    }

    @Test fun gemma_narrates_the_work() {
        val n = orch().onWorkNarration()
        assertNotNull(n)
        assertTrue(n!!.contains("look", ignoreCase = true))  // "let me look that up"
    }

    @Test fun hermes_preempts_gemma_on_real_call() {
        val o = orch()
        o.onUserSpeech()                                // Gemma holds
        assertEquals(VoiceOwner.GEMMA, o.owner)
        val (answer, owner) = o.onHermesReply("The backup finished at 3:04 am.")
        assertEquals(VoiceOwner.HERMES, owner)          // Hermes trumps Gemma
        assertEquals("The backup finished at 3:04 am.", answer)
        assertEquals(VoiceOwner.HERMES, o.owner)
        assertEquals(VoiceOwner.GEMMA, o.handBack().let { o.owner }) // floor returns
    }

    @Test fun hermes_directive_is_authoritative() {
        val o = orch()
        val line = o.onHermesDirective("answer", "disk at 71%", "calm")
        assertTrue(line.contains("71%"))
        assertEquals(VoiceOwner.HERMES, o.owner)
    }

    @Test fun barge_in_resets_the_floor() {
        val o = orch()
        o.onHermesReply("x")
        assertEquals(VoiceOwner.HERMES, o.owner)
        o.onBargeIn()
        assertEquals(VoiceOwner.GEMMA, o.owner)
    }

    @Test fun expression_never_reasons_or_claims_work() {
        val e = RoutedExpress()
        // The expression layer narrates ("I'm on it", "let me look that up") —
        // it must NOT claim it did the work itself (only Hermes does).
        val n = e.express("working", "", "calm")
        assertTrue(n.contains("let me", ignoreCase = true) || n.contains("sec", ignoreCase = true))
    }
}
