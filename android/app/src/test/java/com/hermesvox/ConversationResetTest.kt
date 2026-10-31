package com.hermesvox

import com.hermesvox.ConversationReset.Plan
import com.hermesvox.ConversationReset.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #252's class: no way of starting over may leave a call that looks live and hears nothing. */
class ConversationResetTest {

    @Test fun everyTriggerEndsALiveCallThroughTheRealTeardown() {
        for (t in Trigger.values()) assertTrue("$t", ConversationReset.plan(t, callLive = true).endCall)
    }

    @Test fun commandsReArmTheCallSettingsDoesNot() {
        assertEquals(Plan(endCall = true, rearm = true, deleteServerCopy = true), ConversationReset.plan(Trigger.NEW, true))
        assertEquals(Plan(endCall = true, rearm = true, deleteServerCopy = false), ConversationReset.plan(Trigger.CLEAR, true))
        assertEquals(Plan(endCall = true, rearm = false, deleteServerCopy = false), ConversationReset.plan(Trigger.SETTINGS, true))
    }

    @Test fun reconnectReArmsOnlyAGatewayThatAnswered() {
        assertTrue(ConversationReset.plan(Trigger.RECONNECT, callLive = true, gatewayOk = true).rearm)
        assertEquals(false, ConversationReset.plan(Trigger.RECONNECT, callLive = true, gatewayOk = false).rearm)
    }

    /**
     * #252 — OPEN at the time of writing. This test records the CURRENT behaviour on purpose;
     * it is not the wanted behaviour and must be replaced when the fix lands.
     *
     * A live `/new` asks for the server-copy delete AND the re-arm, but a [Plan] is two
     * independent booleans: nothing here can say the call comes back *first*. The Activity
     * therefore runs the DELETE first (MainActivity.kt:1982) and re-dials only after it
     * (MainActivity.kt:1986), behind a 20 s network timeout (core/GatewayClient.kt:49) — the
     * line was measured down for 2.4 s (foreground) and 13.6 s (backgrounded) after `/new` on a
     * Pixel 7. See docs/evidence/252-new-mid-call-redial.md.
     *
     * The wanted rule is an ordered plan: reset the chain, re-arm, then delete the server copy.
     */
    @Test fun newRequestsTheDeleteAndTheRearmWithNoOrderBetweenThem_252Open() {
        val plan = ConversationReset.plan(Trigger.NEW, callLive = true)
        assertTrue("the delete is requested", plan.deleteServerCopy)
        assertTrue("the re-arm is requested", plan.rearm)
        assertTrue("both on the same plan: the order between them is what is missing",
            plan.endCall && plan.rearm && plan.deleteServerCopy)
    }

    @Test fun withNoCallThereIsNothingToEndOrReArm() {
        for (t in Trigger.values()) {
            val p = ConversationReset.plan(t, callLive = false)
            assertEquals("$t", false, p.endCall); assertEquals("$t", false, p.rearm)
        }
    }
}
