package com.hermesvox

/**
 * PhoneBootstrap — the one-button setup: ask the agent once, keep the phone's listener open while
 * it works, and believe only what the phone can observe.
 *
 * **Why this exists.** Enabling Phone tools starts this phone's listener and mints its credential.
 * It does not teach the agent that the phone exists: that is one entry in the agent's own
 * configuration on the host. The sheet used to offer a prompt to copy, so an owner who pressed
 * *enable* and then asked his agent about the phone got a truthful "I have no such ability" — the
 * one-time step had never happened. The owner should not have to perform a host configuration by
 * hand, so the button does the whole sequence and shows each step.
 *
 * **No restart.** A running gateway reconciles its MCP configuration on its own: a server added
 * with `hermes mcp add` appears, is registered, and is callable within seconds. There is no
 * restart, reload or lifecycle route on the API surface, and the app must not invent one. The
 * instruction therefore asks only for the add, and this flow never claims the connection dropped,
 * restarted or came back.
 *
 * **The listener needs a window.** `hermes mcp add` proves reachability *at the moment it runs*, and
 * this phone's server only listens while it is up. So the press opens a **setup window**
 * ([SETUP_WINDOW_MS], three minutes) during which the listener is up whether or not a call is live.
 * If the window cannot be opened there is nothing for the agent to reach, so the instruction is not
 * sent and the sheet says so and offers the copyable prompt instead.
 *
 * **Advancement is evidence, never prose.** The reply moves the flow from adding to waiting only
 * when it reports that the add succeeded — a refusal leaves it failed with the agent's own words.
 * **Connected** is only the phone's own MCP `initialize`, recorded by [PhoneMcpServer]. An agent
 * saying "done" changes nothing.
 *
 * **The window is stated.** The handshake gets [HANDSHAKE_WINDOW_MS] (a generous minute and a half)
 * after the reply, and never past the setup window's end. Without it the flow becomes
 * [Step.FAILED] with [Failure.NO_HANDSHAKE], names the step it reached, keeps the agent's last
 * words on screen, and the sheet offers the copyable prompt as the fallback. It never becomes
 * Connected by timeout.
 *
 * **One attempt.** [Progress] is a value, and [PhoneBootstrapStore] refuses a second press while the
 * flow is running. A failure is terminal for that attempt: it offers the fallback rather than
 * looping.
 *
 * Pure JVM: every transition is proved off-device by [PhoneBootstrapTest].
 */
object PhoneBootstrap {

    /**
     * How long the phone's listener stays up for the setup, from the press. Long enough for an
     * agent to run `hermes mcp add` (which probes the address before it saves) and for the gateway
     * to complete the handshake afterwards; then the window closes by itself.
     */
    const val SETUP_WINDOW_MS = 3 * 60_000L

    /**
     * How long the gateway has, after the agent's reply, to complete the MCP handshake. Generous,
     * because the gateway reconciles on its own schedule; the setup window still caps it, since the
     * listener cannot be reached after it closes.
     */
    const val HANDSHAKE_WINDOW_MS = 90_000L

    // ---- the button and the promise made before it is pressed ----

    /** The one action in the not-set-up state. */
    const val BUTTON = "SET THIS UP WITH MY AGENT"

    /**
     * The button's own line, shown before the press. The owner is told what the agent will receive,
     * that the phone's listener opens for a stated three minutes, and — since this is the fact that
     * used to be wrong — that Hermes does **not** restart.
     */
    const val BUTTON_WARNING =
        "Your agent will be given this phone's address and its token. Pressing this opens this " +
            "phone's listener for three minutes so your agent can reach it. Hermes picks the new " +
            "server up by itself — nothing restarts and this call is not dropped. Nothing is " +
            "written to your host by Vox itself: your agent does the configuring, exactly as if " +
            "you had pasted the prompt by hand."

    // ---- the steps, in the owner's words ----

    const val ADDING_HEADING = "Adding this phone to your agent"
    const val ADDING_MEANING =
        "The instruction has been sent on this phone's authenticated connection. This phone is " +
            "listening for three minutes, so your agent can reach it now. Its reply is not proof — " +
            "only the MCP handshake is."

    const val WAITING_HEADING = "Waiting for it to appear"
    const val WAITING_MEANING =
        "Your agent has answered without reporting a failed add. Waiting for Hermes to connect to " +
            "it; that handshake is the only proof the setup took. It has a minute and a half after " +
            "the reply, and never past the end of this phone's window."

    const val CONNECTED_HEADING = PhoneToolsView.CONNECTED_HEADING
    const val CONNECTED_MEANING =
        "Your agent reached this phone and completed the MCP handshake."

    // ---- the failures, each named ----

    const val NO_ANSWER_HEADING = "Your agent did not answer"
    const val NO_ANSWER_MEANING =
        "The instruction was sent, but no answer came back. The add may not have taken. Copy the " +
            "setup prompt below and hand it over yourself."

    const val ADD_REFUSED_HEADING = "Your agent could not add this phone"
    const val ADD_REFUSED_MEANING =
        "It says the add did not succeed; its own words are below. The setup may not have taken. " +
            "Copy the setup prompt below and hand it over yourself."

    const val WINDOW_CLOSED_HEADING = "This phone could not be made reachable"
    const val WINDOW_CLOSED_MEANING =
        "The listener could not be opened, so there is nothing for your agent to reach and no " +
            "instruction was sent. Copy the setup prompt below and hand it over yourself once this " +
            "phone can listen."

    const val NO_HANDSHAKE_HEADING = "The setup may not have taken"
    const val NO_HANDSHAKE_MEANING =
        "The window closed without the MCP handshake, so the add may not have taken. Your agent " +
            "last said what is shown below. Copy the setup prompt below and hand it over yourself."

    const val SEND_FAILED_HEADING = "The instruction did not complete"
    const val SEND_FAILED_MEANING =
        "The cause is named below. Nothing else was sent; the copyable prompt is the fallback."

    const val NOT_CONFIGURED_MEANING =
        "No connection configured: set the gateway under Entity & Connection, then try again."

    const val NO_TAILNET_MEANING =
        "Tailscale is not running, so there is no real address for the agent and no listener to " +
            "open. Nothing was sent. Start it and open this sheet again."

    const val ALPHA_OFF = PhoneToolsView.ALPHA_OFF

    const val STOPPED_HEADING = "The setup stopped unexpectedly"
    const val STOPPED_MEANING =
        "The flow could not continue. Nothing else was sent. Copy the setup prompt below and hand " +
            "it over yourself."

    /** The steps the flow can be on. [FAILED] is terminal for one attempt. */
    enum class Step { IDLE, ADDING, WAITING, CONNECTED, FAILED }

    /** What stopped the flow. Each has its own words; none is a generic "could not reach". */
    enum class Failure {
        NOT_CONFIGURED,
        NO_TAILNET,
        ALPHA_OFF,
        WINDOW_CLOSED,
        NO_ANSWER,
        ADD_REFUSED,
        SEND_FAILED,
        NO_HANDSHAKE,
        STOPPED,
    }

    /**
     * Everything one attempt knows. There is deliberately no field for the instruction or the
     * token: the message is held only for the duration of the send, so no view, log or record can
     * accidentally carry it.
     *
     * [startedAt] is when the attempt began (and the baseline for a handshake that counts);
     * [windowEndsAt] is when the phone's listener closes; [answeredAt] is when the agent's reply
     * arrived, which starts the handshake window. [reached] keeps the furthest step for the failure
     * copy.
     */
    data class Progress(
        val step: Step = Step.IDLE,
        val failure: Failure? = null,
        val reached: Step? = null,
        val startedAt: Long = 0L,
        val windowEndsAt: Long = 0L,
        val answeredAt: Long = 0L,
    )

    /** A new attempt: the listener window is open and the instruction has just gone out. */
    fun start(now: Long, windowEndsAt: Long = now + SETUP_WINDOW_MS): Progress =
        Progress(step = Step.ADDING, startedAt = now, windowEndsAt = windowEndsAt)

    /** A precondition failed before anything was sent. */
    fun startFailure(failure: Failure): Progress =
        Progress(step = Step.FAILED, failure = failure, reached = Step.IDLE)

    /** This attempt stopped at [progress]'s current step; the furthest step is kept for the copy. */
    fun failed(progress: Progress, failure: Failure): Progress = fail(progress, failure)

    /**
     * The agent's answer. A blank answer is [Failure.NO_ANSWER]. An answer that reports the add
     * did not succeed is [Failure.ADD_REFUSED]; anything else — the add succeeded — moves to
     * [Step.WAITING]. Neither is **Connected**; only the handshake is.
     */
    fun answered(progress: Progress, reply: String?, now: Long): Progress {
        if (progress.step != Step.ADDING) return progress
        if (reply.isNullOrBlank()) return fail(progress, Failure.NO_ANSWER)
        if (PhoneSetup.looksRefused(reply) || looksFailed(reply)) {
            return fail(progress, Failure.ADD_REFUSED)
        }
        return progress.copy(step = Step.WAITING, answeredAt = now)
    }

    /**
     * Does the reply say the add did not happen? This is a reading of the agent's words, not a
     * verdict: the reply is shown in full next to the failure, so a wrong guess is still legible.
     * It is deliberately narrow — a sentence that merely mentions a failure hint ("if the probe
     * fails…") must not fail a successful add.
     */
    fun looksFailed(reply: String): Boolean {
        val t = reply.lowercase()
        return FAILURE_MARKERS.any { t.contains(it) }
    }

    private val FAILURE_MARKERS = listOf(
        "could not add", "couldn't add", "cannot add", "can't add", "unable to add",
        "did not add", "didn't add", "failed to add", "add failed", "not added",
        "no such command", "command not found",
    )

    /**
     * A handshake recorded after this attempt began. A handshake from before the press is not
     * evidence for this attempt; one recorded while the agent is still working is the strongest
     * evidence there is, so it connects from either step.
     */
    fun handshake(progress: Progress, handshakeAt: Long): Progress =
        if (handshakeAt > progress.startedAt &&
            (progress.step == Step.ADDING || progress.step == Step.WAITING)
        ) {
            progress.copy(step = Step.CONNECTED)
        } else {
            progress
        }

    /**
     * The clock: a stated window, and then the fallback. In [Step.ADDING] the deadline is the
     * setup window's end (the agent may still be working); in [Step.WAITING] it is the handshake
     * window after the reply, capped by the setup window because the listener closes with it.
     * Never Connected by elapse.
     */
    fun expire(progress: Progress, now: Long): Progress {
        val deadline = deadline(progress)
        if (deadline <= 0L) return progress
        if ((progress.step == Step.ADDING || progress.step == Step.WAITING) && now >= deadline) {
            return fail(progress, Failure.NO_HANDSHAKE)
        }
        return progress
    }

    /** When this attempt's own wait ends; 0 when the step is not waiting on the window. */
    fun deadline(progress: Progress): Long = when (progress.step) {
        Step.ADDING -> progress.windowEndsAt
        Step.WAITING -> {
            val afterReply = if (progress.answeredAt > 0L) {
                progress.answeredAt + HANDSHAKE_WINDOW_MS
            } else {
                0L
            }
            when {
                afterReply == 0L -> progress.windowEndsAt
                progress.windowEndsAt == 0L -> afterReply
                else -> minOf(afterReply, progress.windowEndsAt)
            }
        }
        else -> 0L
    }

    /** Apply the phone's own evidence and the stored window, without mutating the stored attempt. */
    fun settled(progress: Progress, handshakeAt: Long, now: Long): Progress =
        expire(handshake(progress, handshakeAt), now)

    /** True while the one attempt is in flight; a second press is ignored. */
    fun busy(progress: Progress): Boolean =
        progress.step == Step.ADDING || progress.step == Step.WAITING

    /** The failure is terminal for this attempt, and the copyable prompt is the way forward. */
    fun offersFallback(progress: Progress): Boolean = progress.step == Step.FAILED

    /**
     * How long the listener has left, in the owner's words, while the attempt is in flight. Empty
     * outside those steps. The window is stated before the press and kept in sight during it.
     */
    fun listeningLine(progress: Progress, now: Long): String {
        if (!busy(progress)) return ""
        val left = (progress.windowEndsAt - now).coerceAtLeast(0L)
        val minutes = left / 60_000L
        val remaining = when {
            minutes >= 2L -> "$minutes minutes"
            minutes == 1L -> "about a minute"
            else -> "less than a minute"
        }
        return "This phone is listening for another $remaining; the window closes by itself."
    }

    // ---- the words each step and failure uses ----

    fun heading(progress: Progress): String = when (progress.step) {
        Step.IDLE -> PhoneToolsView.NOT_SET_UP_HEADING
        Step.ADDING -> ADDING_HEADING
        Step.WAITING -> WAITING_HEADING
        Step.CONNECTED -> CONNECTED_HEADING
        Step.FAILED -> when (progress.failure) {
            Failure.NO_ANSWER -> NO_ANSWER_HEADING
            Failure.ADD_REFUSED -> ADD_REFUSED_HEADING
            Failure.WINDOW_CLOSED -> WINDOW_CLOSED_HEADING
            Failure.NO_HANDSHAKE -> NO_HANDSHAKE_HEADING
            Failure.SEND_FAILED -> SEND_FAILED_HEADING
            Failure.NOT_CONFIGURED -> "Cannot send — no connection is configured"
            Failure.NO_TAILNET -> "Cannot run — Tailscale is not running"
            Failure.ALPHA_OFF -> ALPHA_OFF
            Failure.STOPPED, null -> STOPPED_HEADING
        }
    }

    fun meaning(progress: Progress): String = when (progress.step) {
        Step.IDLE -> PhoneToolsView.NOT_SET_UP_MEANING
        Step.ADDING -> ADDING_MEANING
        Step.WAITING -> WAITING_MEANING
        Step.CONNECTED -> CONNECTED_MEANING
        Step.FAILED -> when (progress.failure) {
            Failure.NO_ANSWER -> NO_ANSWER_MEANING
            Failure.ADD_REFUSED -> ADD_REFUSED_MEANING
            Failure.WINDOW_CLOSED -> WINDOW_CLOSED_MEANING
            Failure.NO_HANDSHAKE -> NO_HANDSHAKE_MEANING
            Failure.NOT_CONFIGURED -> NOT_CONFIGURED_MEANING
            Failure.NO_TAILNET -> NO_TAILNET_MEANING
            Failure.ALPHA_OFF -> ALPHA_OFF
            Failure.STOPPED, null -> STOPPED_MEANING
            Failure.SEND_FAILED -> SEND_FAILED_MEANING
        }
    }

    /**
     * Which step the attempt reached, in the owner's words. Shown under a failure, because "it may
     * not have taken" is only useful when it says how far it got.
     */
    fun reachedLine(progress: Progress): String {
        if (progress.step != Step.FAILED) return ""
        return when (progress.failure) {
            Failure.NOT_CONFIGURED, Failure.NO_TAILNET, Failure.ALPHA_OFF ->
                "It reached: nothing was sent."
            Failure.WINDOW_CLOSED ->
                "It reached: the listener could not be opened; nothing was sent."
            Failure.NO_ANSWER -> "It reached: the instruction was sent; no answer came back."
            Failure.ADD_REFUSED ->
                "It reached: your agent answered, but not with a successful add."
            Failure.NO_HANDSHAKE ->
                "It reached: the instruction was sent; no MCP handshake arrived within the window."
            Failure.SEND_FAILED, Failure.STOPPED, null ->
                "It reached: the instruction was not completed."
        }
    }

    /**
     * Terminal for this attempt: the step it stopped on is kept, so the failure can say how far it
     * got. A later failure never overwrites a further one.
     */
    private fun fail(progress: Progress, failure: Failure): Progress =
        progress.copy(step = Step.FAILED, failure = failure, reached = progress.reached ?: progress.step)
}
