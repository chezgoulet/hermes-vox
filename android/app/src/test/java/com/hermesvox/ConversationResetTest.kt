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
        assertEquals(listOf(Step.RESET_CHAIN, Step.REARM_CALL, Step.DELETE_SERVER_COPY),
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
     * #351 — the order is the fix. A live `/new` used to run the server-copy DELETE before
     * the redial, so the line stayed down for a whole network round-trip (measured 2.4 s
     * foreground and 13.6 s backgrounded on a Pixel 7; up to `GatewayClient.TIMEOUT_MS` =
     * 20 s against a slow gateway). The plan must arm the call first and clean up after.
     * See docs/evidence/252-new-mid-call-redial.md.
     */
    @Test fun theRearmPrecedesTheServerCopyDelete_351() {
        val plan = ConversationReset.plan(Trigger.NEW, callLive = true)
        assertTrue("the re-arm is in the plan", plan.rearm)
        assertTrue("the delete is in the plan", plan.deleteServerCopy)
        assertTrue("the call is armed before the server copy is deleted",
            plan.precedes(Step.REARM_CALL, Step.DELETE_SERVER_COPY))
        assertEquals("the delete is the last step", plan.steps.size - 1,
            plan.orderOf(Step.DELETE_SERVER_COPY))
    }

    /** The property, not one plan: no trigger may put housekeeping in front of the microphone. */
    @Test fun noTriggerDeletesTheServerCopyBeforeArmingTheCall() {
        for (t in Trigger.values()) for (live in listOf(true, false)) for (ok in listOf(true, false)) {
            val p = ConversationReset.plan(t, live, ok)
            assertFalse("$t live=$live ok=$ok: delete before re-arm",
                p.precedes(Step.DELETE_SERVER_COPY, Step.REARM_CALL))
            if (p.deleteServerCopy && p.rearm)
                assertTrue("$t live=$live ok=$ok: re-arm before delete",
                    p.precedes(Step.REARM_CALL, Step.DELETE_SERVER_COPY))
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
