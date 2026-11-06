package com.hermesvox

/**
 * PhoneBootstrap — the one-button setup: ask the agent once, keep the phone's listener open for as
 * long as the agent works, and believe only what the phone can observe.
 *
 * **Why this exists.** Enabling Phone tools starts this phone's listener and mints its credential.
 * It does not teach the agent that the phone exists: that is one entry in the agent's own
 * configuration on the host. The sheet used to offer a prompt to copy, so an owner who pressed
 * *enable* and then asked his agent about the phone got a truthful "I have no such ability" — the
 * one-time step had never happened. The owner should not have to perform a host configuration by
 * hand, so the button does the whole sequence and shows each step.
 *
 * **There is no deadline.** This turn is not an ordinary voice turn: an agent may take minutes to
 * reach a host, run a configuration command and verify it. The flow therefore drives the run's own
 * event stream ([PhoneSetupRun]) instead of a blocking call with a turn timeout, and its only
 * clocks are the *ages* it shows — never a countdown to a stop. The run is stopped for exactly two
 * reasons: the owner cancels the attempt, or this sheet goes away. Both have their own words
 * ([Failure.CANCELLED], [Failure.LEFT]) so the card says what happened rather than going quiet.
 *
 * **No restart.** A running gateway reconciles its MCP configuration on its own: a server added
 * with `hermes mcp add` appears, is registered, and is callable within seconds. There is no
 * restart, reload or lifecycle route on the API surface, and the app must not invent one. The
 * instruction therefore asks only for the add, and this flow never claims the connection dropped,
 * restarted or came back.
 *
 * **The listener follows the run.** `hermes mcp add` proves reachability *at the moment it runs*,
 * and this phone's server only listens while it is up. So the press opens the listener, and it
 * stays open while the run is alive — an hour if the agent takes an hour. When the run ends it
 * stays open for [HANDSHAKE_GRACE_MS] in case the gateway connects just then, and closes at once if
 * that handshake arrives. A live call keeps it past both, as it always has.
 *
 * **Advancement is evidence, never prose.** The reply moves the flow from adding to waiting only
 * when it reports that the add succeeded — a refusal leaves it failed with the agent's own words.
 * **Connected** is only the phone's own MCP `initialize`, recorded by [PhoneMcpServer]. An agent
 * saying "done" changes nothing.
 *
 * **The work is shown.** [Progress.activity] is the agent's tool calls, in arrival order, each one
 * a line built from the tool's name and [PhoneSetupActivity]'s own phrase for it. Arguments are
 * never read, so the instruction's token cannot reach the screen through them. Silence is an age
 * ([PhoneSetupActivity.silence]), not a deadline.
 *
 * **One attempt.** [Progress] is a value, and [PhoneBootstrapStore] refuses a second press while the
 * flow is running. A failure is terminal for that attempt: it offers the fallback rather than
 * looping.
 *
 * Pure JVM: every transition is proved off-device by [PhoneBootstrapTest].
 */
object PhoneBootstrap {

    /**
     * How long the phone's listener stays open **after the run ends**, so a gateway that reconciles
     * the new entry just after the answer can still reach it. A handshake in this window closes it
     * at once; the run itself is not bounded by this or anything else.
     */
    const val HANDSHAKE_GRACE_MS = 20_000L

    // ---- the button and the promise made before it is pressed ----

    /** The one action in the not-set-up state. */
    const val BUTTON = "SET THIS UP WITH MY AGENT"

    /**
     * The button's own line, shown before the press. The owner is told what the agent will receive,
     * that the phone's listener is open for the length of the attempt, and — since this is the fact
     * that used to be wrong — that Hermes does **not** restart. The listener is no longer stated as
     * a fixed number of minutes: it follows the run.
     */
    const val BUTTON_WARNING =
        "Your agent will be given this phone's address and its token. Pressing this opens this " +
            "phone's listener for as long as this attempt runs, so your agent can reach it " +
            "whenever it gets there. Hermes picks the new server up by itself — nothing restarts " +
            "and this call is not dropped. Nothing is written to your host by Vox itself: your " +
            "agent does the configuring, exactly as if you had pasted the prompt by hand."

    // ---- the steps, in the owner's words ----

    const val ADDING_HEADING = "Your agent is working on this"
    const val ADDING_MEANING =
        "The instruction has been sent on this phone's authenticated connection, and this phone " +
            "stays open to your agent for as long as the attempt runs. What it does appears below " +
            "as it happens; only the MCP handshake can prove the setup took."

    const val WAITING_HEADING = "Your agent has answered"
    const val WAITING_MEANING =
        "The run is over and your agent did not report a failed add. This phone stays open a " +
            "moment longer in case Hermes connects now; that handshake is the only proof the " +
            "setup took."

    const val CONNECTED_HEADING = PhoneToolsView.CONNECTED_HEADING
    const val CONNECTED_MEANING =
        "Your agent reached this phone and completed the MCP handshake."

    // ---- the failures, each named ----

    const val NO_ANSWER_HEADING = "Your agent did not answer"
    const val NO_ANSWER_MEANING =
        "The instruction was sent, but the run ended without an answer. The add may not have " +
            "taken. Copy the setup prompt below and hand it over yourself."

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
        "The run ended without the MCP handshake, so the add may not have taken. Your agent last " +
            "said what is shown below. Copy the setup prompt below and hand it over yourself."

    const val SEND_FAILED_HEADING = "The instruction did not complete"
    const val SEND_FAILED_MEANING =
        "The cause is named below. Nothing else was sent; the copyable prompt is the fallback."

    const val CANCELLED_HEADING = "You stopped this attempt"
    const val CANCELLED_MEANING =
        "You stopped the setup, so the run was stopped and this phone stopped listening. Nothing " +
            "else was sent. Copy the setup prompt below and hand it over yourself if you want the " +
            "agent to have it."

    const val LEFT_HEADING = "The setup stopped when this sheet closed"
    const val LEFT_MEANING =
        "Closing this sheet stopped the run and closed this phone's listener, so your agent may " +
            "have stopped part-way through. Open this sheet again and press once more. Copy the " +
            "setup prompt below and hand it over yourself."

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
        CANCELLED,
        LEFT,
    }

    /**
     * Everything one attempt knows. There is deliberately no field for the instruction or the
     * token: the message is held only for the duration of the send, so no view, log or record can
     * accidentally carry it. [activity] is the same rule from the other side — it holds tool
     * *names* and phrases, never an argument ([PhoneSetupActivity.Line]).
     *
     * [startedAt] is when the attempt began (and the baseline for a handshake that counts);
     * [answeredAt] is when the run ended with the agent's answer, which starts the short grace for
     * the handshake; [reached] keeps the furthest step for the failure copy; [lastEventAt] is when
     * the stream last said anything, which is the silence line's only input.
     */
    data class Progress(
        val step: Step = Step.IDLE,
        val failure: Failure? = null,
        val reached: Step? = null,
        val startedAt: Long = 0L,
        val answeredAt: Long = 0L,
        val activity: List<PhoneSetupActivity.Line> = emptyList(),
        val lastEventAt: Long = 0L,
    )

    /**
     * A new attempt: the listener has just been opened and the instruction is about to go out.
     * There is no window end to record, because the listener follows the run.
     */
    fun start(now: Long): Progress = Progress(step = Step.ADDING, startedAt = now)

    /** A precondition failed before anything was sent. */
    fun startFailure(failure: Failure): Progress =
        Progress(step = Step.FAILED, failure = failure, reached = Step.IDLE)

    /** This attempt stopped at [progress]'s current step; the furthest step is kept for the copy. */
    fun failed(progress: Progress, failure: Failure): Progress = fail(progress, failure)

    /**
     * Apply one drained poll: every tool call it carried becomes one more line, in arrival order,
     * and any event at all moves the silence line's age. A poll with no events changes nothing —
     * a keepalive is not news.
     */
    internal fun observed(progress: Progress, poll: PhoneSetupRun.Poll, now: Long): Progress {
        if (poll.events <= 0) return progress
        var next = progress.copy(lastEventAt = now)
        for (tool in poll.tools) {
            val line = PhoneSetupActivity.line(tool.name, now) ?: continue
            next = next.copy(activity = next.activity + line)
        }
        return next
    }

    /**
     * The agent's answer, which is the end of the run. A blank answer is [Failure.NO_ANSWER]. An
     * answer that reports the add did not succeed is [Failure.ADD_REFUSED]; anything else moves to
     * [Step.WAITING] for the handshake grace. Neither is **Connected**; only the handshake is.
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
     *
     * [Step.IDLE] is included because the progress is per-process and the handshake is not: after
     * this app restarts there is no attempt to speak of, while the phone's record still holds the
     * `initialize` it saw. A handshake with no attempt in this process is therefore the restart
     * case, and it connects. A [Step.FAILED] attempt is deliberately absent: an attempt that just
     * failed is not resurrected by a handshake, whenever it was recorded.
     */
    fun handshake(progress: Progress, handshakeAt: Long): Progress =
        if (handshakeAt > progress.startedAt &&
            (progress.step == Step.IDLE || progress.step == Step.ADDING || progress.step == Step.WAITING)
        ) {
            progress.copy(step = Step.CONNECTED)
        } else {
            progress
        }

    /**
     * The only clock in the flow: the short grace after the run ended, for the handshake. It is
     * not a deadline on the agent — [Step.ADDING] has none, no matter how many hours pass. Never
     * Connected by elapse.
     */
    fun expire(progress: Progress, now: Long): Progress {
        val deadline = deadline(progress)
        if (deadline <= 0L) return progress
        if (now >= deadline) return fail(progress, Failure.NO_HANDSHAKE)
        return progress
    }

    /** When the post-run handshake grace ends; 0 when the step is not waiting on it. */
    fun deadline(progress: Progress): Long =
        if (progress.step == Step.WAITING && progress.answeredAt > 0L) {
            progress.answeredAt + HANDSHAKE_GRACE_MS
        } else {
            0L
        }

    /**
     * Apply the phone's own evidence and the grace, without mutating the stored attempt.
     *
     * This is the one reading of the flow's state: the sheet draws from it and the Settings section
     * row calls it too, with the same arguments — the in-process [Progress], the handshake the
     * record kept ([PhoneSetup.Record.handshakeAt]) and the clock. [PhoneBootstrapStore.progress]
     * alone is per-process, so a row that read only its step would say *not set up* after a restart
     * while the sheet, which folds the kept handshake in, said *Connected*.
     */
    fun settled(progress: Progress, handshakeAt: Long, now: Long): Progress =
        expire(handshake(progress, handshakeAt), now)

    /** True while the one attempt is in flight; a second press is ignored. */
    fun busy(progress: Progress): Boolean =
        progress.step == Step.ADDING || progress.step == Step.WAITING

    /**
     * Should the listener be open? While the run is alive, always — however long that is — and
     * through the short post-run grace. The handshake ends it at once, and so does a failure.
     */
    fun listenerOpen(progress: Progress): Boolean = busy(progress)

    /** The failure is terminal for this attempt, and the copyable prompt is the way forward. */
    fun offersFallback(progress: Progress): Boolean = progress.step == Step.FAILED

    /**
     * The work so far, in arrival order, rendered. Empty before the first tool call; kept after
     * the attempt ends, so the history stays visible with the outcome.
     */
    fun activityLines(progress: Progress): List<String> =
        PhoneSetupActivity.renderAll(progress.activity)

    /** How long since the stream last said anything, while the attempt is in flight. */
    fun silence(progress: Progress, now: Long): String =
        if (busy(progress)) PhoneSetupActivity.silence(progress.lastEventAt, now) else ""

    /**
     * The listener's life, in the owner's words. It is a statement, not a countdown: the window
     * follows the run, and there is no deadline to count towards. Empty outside the live steps.
     */
    fun listeningLine(progress: Progress, now: Long): String = when (progress.step) {
        Step.ADDING -> "This phone is listening for this attempt; it closes when the run ends."
        Step.WAITING -> "This phone is listening a moment longer, in case your agent connects now."
        else -> ""
    }

    /**
     * The line the attempt leaves in its persisted record when it ends without a connection. It is
     * a name for what happened, not a replacement for the agent's words (which the record keeps).
     */
    fun failureLine(progress: Progress): String = when (progress.step) {
        Step.FAILED -> when (progress.failure) {
            Failure.NO_ANSWER -> "The run ended without an answer from your agent."
            Failure.ADD_REFUSED -> "Your agent answered, but not with a successful add."
            Failure.NO_HANDSHAKE -> "The run ended without the MCP handshake, so the add may not have taken."
            Failure.WINDOW_CLOSED -> "The listener could not be opened, so nothing was sent."
            Failure.NOT_CONFIGURED -> NOT_CONFIGURED_MEANING
            Failure.NO_TAILNET -> NO_TAILNET_MEANING
            Failure.ALPHA_OFF -> ALPHA_OFF
            Failure.CANCELLED -> "You stopped this attempt, so the run was stopped."
            Failure.LEFT -> "The sheet closed, so the run was stopped."
            Failure.SEND_FAILED, Failure.STOPPED, null -> "The instruction did not complete."
        }
        else -> ""
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
            Failure.CANCELLED -> CANCELLED_HEADING
            Failure.LEFT -> LEFT_HEADING
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
            Failure.CANCELLED -> CANCELLED_MEANING
            Failure.LEFT -> LEFT_MEANING
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
            Failure.NO_ANSWER -> "It reached: the instruction was sent; the run ended without an answer."
            Failure.ADD_REFUSED ->
                "It reached: your agent answered, but not with a successful add."
            Failure.NO_HANDSHAKE ->
                "It reached: the instruction was sent; the run ended without an MCP handshake."
            Failure.CANCELLED, Failure.LEFT ->
                "It reached: the instruction was sent, and then the attempt was stopped."
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
