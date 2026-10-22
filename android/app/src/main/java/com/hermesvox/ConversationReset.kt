package com.hermesvox

/**
 * ConversationReset — what each way of starting over does to a live call (#252's class).
 *
 * #252 was `/new` stopping the voice controller by hand while `callLive` stayed true: the call
 * UI stayed up, `startCall()` refused to re-arm (`if (callLive) return`), and the call heard
 * nothing until the user hung up. `/clear`, `/reset`, `/reconnect` and Settings → New
 * conversation had the same shape. Every path now ends a live call through `endCall()` — the
 * one teardown that clears `callLive` — and this table says which re-arm it afterwards.
 *
 * Pure JVM, so the rule is proven off-device.
 */
object ConversationReset {

    enum class Trigger { NEW, CLEAR, RECONNECT, SETTINGS }

    data class Plan(val endCall: Boolean, val rearm: Boolean, val deleteServerCopy: Boolean)

    /** @param gatewayOk for [Trigger.RECONNECT]: whether the gateway answered. */
    fun plan(trigger: Trigger, callLive: Boolean, gatewayOk: Boolean = true): Plan = when (trigger) {
        // A new session inside a call is still a call: hang up, reset, dial back in.
        Trigger.NEW -> Plan(endCall = callLive, rearm = callLive, deleteServerCopy = true)
        Trigger.CLEAR -> Plan(endCall = callLive, rearm = callLive, deleteServerCopy = false)
        // Re-dial only a gateway that answered; otherwise the line would open onto nothing.
        Trigger.RECONNECT -> Plan(endCall = callLive, rearm = callLive && gatewayOk, deleteServerCopy = false)
        // Settings says "Clears context and ends any live call": it ends it, and stays ended.
        Trigger.SETTINGS -> Plan(endCall = callLive, rearm = false, deleteServerCopy = false)
    }
}
