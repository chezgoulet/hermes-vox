package com.hermesvox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoTailRuleTest {
    private val guard = 10_450L   // the reply ended at 10 000 ms

    @Test fun a_short_blip_inside_the_window_is_echo() {
        assertTrue(EchoTailRule.isEchoTail(speechEndAtMs = 10_300, speechMs = 250, guardUntilMs = guard))
        assertTrue("VAD hangover margin", EchoTailRule.isEchoTail(10_590, 300, guard))
    }

    @Test fun a_caller_who_answers_immediately_is_never_dropped() {
        // Starts 50 ms after the reply and keeps talking: the old cooldown clipped exactly this.
        assertFalse(EchoTailRule.isEchoTail(speechEndAtMs = 11_800, speechMs = 1_700, guardUntilMs = guard))
        // Short, but it ended after the window: a real "yes".
        assertFalse(EchoTailRule.isEchoTail(10_900, 350, guard))
        // Long, even if it somehow ended inside the window.
        assertFalse(EchoTailRule.isEchoTail(10_400, 900, guard))
    }

    @Test fun no_reply_no_guard() {
        assertFalse(EchoTailRule.isEchoTail(10_300, 200, guardUntilMs = 0L))
    }
}
