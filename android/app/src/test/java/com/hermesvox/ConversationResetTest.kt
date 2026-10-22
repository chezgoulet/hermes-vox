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

    @Test fun withNoCallThereIsNothingToEndOrReArm() {
        for (t in Trigger.values()) {
            val p = ConversationReset.plan(t, callLive = false)
            assertEquals("$t", false, p.endCall); assertEquals("$t", false, p.rearm)
        }
    }
}
