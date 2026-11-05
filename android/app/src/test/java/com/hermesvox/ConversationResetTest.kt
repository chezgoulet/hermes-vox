package com.hermesvox

import com.hermesvox.ConversationReset.Step
import com.hermesvox.ConversationReset.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #252's class: no way of starting over may leave a call that looks live and hears nothing. */
class ConversationResetTest {

    @Test fun everyTriggerEndsALiveCallThroughTheRealTeardown() {
        for (t in Trigger.values()) assertTrue("$t", ConversationReset.plan(t, callLive = true).endCall)
    }

    @Test fun commandsReArmTheCallSettingsDoesNot() {
        assertEquals(listOf(Step.RESET_CHAIN, Step.REARM_CALL),
            ConversationReset.plan(Trigger.NEW, true).steps)
        assertEquals(listOf(Step.RESET_CHAIN, Step.REARM_CALL),
            ConversationReset.plan(Trigger.CLEAR, true).steps)
        assertEquals(listOf(Step.RESET_CHAIN),
            ConversationReset.plan(Trigger.SETTINGS, true).steps)
    }

    @Test fun reconnectReArmsOnlyAGatewayThatAnswered() {
        assertTrue(ConversationReset.plan(Trigger.RECONNECT, callLive = true, gatewayOk = true).rearm)
        assertFalse(ConversationReset.plan(Trigger.RECONNECT, callLive = true, gatewayOk = false).rearm)
    }

    /**
     * A reset is local. It drops the client chain head and re-arms the line; it never
     * sends the host a request, so there is no server-copy step to order and nothing to
     * delete on the gateway. [NoDestructiveRequestsTest] holds the wire side of the same
     * promise.
     */
    @Test fun noResetPlanTouchesTheHost() {
        val local = setOf(Step.RESET_CHAIN, Step.REARM_CALL)
        for (t in Trigger.values()) for (live in listOf(true, false)) for (ok in listOf(true, false)) {
            val p = ConversationReset.plan(t, live, ok)
            assertTrue("$t live=$live ok=$ok: ${p.steps}", local.containsAll(p.steps))
            assertEquals("$t: a reset starts by dropping the local chain", Step.RESET_CHAIN, p.steps.first())
        }
    }

    @Test fun withNoCallThereIsNothingToEndOrReArm() {
        for (t in Trigger.values()) {
            val p = ConversationReset.plan(t, callLive = false)
            assertEquals("$t", false, p.endCall); assertEquals("$t", false, p.rearm)
            assertEquals("$t: the chain is still reset", Step.RESET_CHAIN, p.steps.first())
        }
    }
}
