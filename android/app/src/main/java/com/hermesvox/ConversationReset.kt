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
 * #351: the steps are ORDERED, because the order was the defect. A live `/new` used to run the
 * server-copy DELETE before it re-dialled, so the microphone stayed down for a network
 * round-trip (measured 2.4 s foreground, 13.6 s backgrounded; up to
 * `GatewayClient.TIMEOUT_MS` = 20 s on a slow gateway). The plan now lists the steps in the
 * order they must run and puts the delete last, after the call is armed: the server copy is
 * housekeeping, not a precondition for the user to speak.
 *
 * Pure JVM, so the rule — and the order — is proven off-device.
 */
object ConversationReset {

    enum class Trigger { NEW, CLEAR, RECONNECT, SETTINGS }

    /** One action a reset performs. [Plan.steps] holds them in run order. */
    enum class Step {
        /** Drop the client-side chain head (`HermesSession.resetConversation`). */
        RESET_CHAIN,
        /** Open the voice line again (`MainActivity.startCall`). Never waits on the network. */
        REARM_CALL,
        /** Delete the server's copy of the chain head — after the call is armed. */
        DELETE_SERVER_COPY
    }

    data class Plan(val endCall: Boolean, val steps: List<Step>) {
        /** Whether the plan re-arms the call after ending it. */
        val rearm: Boolean get() = Step.REARM_CALL in steps

        /** Whether the plan deletes the server-side copy. */
        val deleteServerCopy: Boolean get() = Step.DELETE_SERVER_COPY in steps

        /** Position of [step] in the run order, or -1 when the plan does not perform it. */
        fun orderOf(step: Step): Int = steps.indexOf(step)

        /** True only when both steps run and [first] runs before [second]. */
        fun precedes(first: Step, second: Step): Boolean {
            val a = orderOf(first); val b = orderOf(second)
            return a >= 0 && b >= 0 && a < b
        }
    }

    /** @param gatewayOk for [Trigger.RECONNECT]: whether the gateway answered. */
    fun plan(trigger: Trigger, callLive: Boolean, gatewayOk: Boolean = true): Plan {
        val steps = mutableListOf(Step.RESET_CHAIN)
        // A new session inside a call is still a call: hang up, reset, dial back in.
        // Re-dial only a gateway that answered; otherwise the line would open onto nothing.
        if (callLive) when (trigger) {
            Trigger.NEW, Trigger.CLEAR -> steps += Step.REARM_CALL
            Trigger.RECONNECT -> if (gatewayOk) steps += Step.REARM_CALL
            // Settings says "Clears context and ends any live call": it ends it, and stays ended.
            Trigger.SETTINGS -> Unit
        }
        // #351: the delete is LAST. It is the only step that can block on the gateway, and
        // nobody should wait for housekeeping to hear the entity again.
        if (trigger == Trigger.NEW) steps += Step.DELETE_SERVER_COPY
        return Plan(endCall = callLive, steps = steps)
    }
}
