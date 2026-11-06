package com.hermesvox

/**
 * PhoneBootstrap — the one-button setup: ask the agent once, trigger the restart job it named, and
 * believe only what the phone can observe.
 *
 * **Why this exists.** Enabling Phone tools starts this phone's listener and mints its credential.
 * It does not teach the agent that the phone exists: that is one entry in the agent's own
 * configuration on the host, plus a restart for Hermes to read it. The sheet used to offer a prompt
 * to copy, so an owner who pressed *enable* and then asked his agent about the phone got a truthful
 * "I have no such ability" — the one-time step had never happened. The owner should not have to
 * perform a host configuration by hand, so the button does the whole sequence and shows each step.
 *
 * **The app does not touch the host.** It sends one ordinary turn; the agent edits its own
 * configuration and creates a one-shot restart job. The only direct request the app makes beyond the
 * turn is `POST /api/jobs/{id}/run` for the job the agent named. There is no restart route on the
 * API surface and the app must not try to find one.
 *
 * **Advancement is evidence, never prose.** The reply moves the flow only when it carries a job id
 * in the exact form the instruction asks for; the restart is observed as a drop and a return of
 * `/v1/health`; **Connected** is only the phone's own MCP `initialize`, recorded by
 * [PhoneMcpServer]. An agent saying "done" changes nothing.
 *
 * **The window is stated.** Once the gateway answers again, the handshake gets
 * [HANDSHAKE_WINDOW_MS] (three minutes). Without it the flow becomes [Step.FAILED] with
 * [Failure.NO_HANDSHAKE], names the step it reached, and the sheet offers the copyable prompt as the
 * fallback. It never becomes Connected by timeout.
 *
 * **One attempt.** [Progress] is a value, and [PhoneBootstrapStore] refuses a second press while the
 * flow is running. A failure is terminal for that attempt: it offers the fallback rather than
 * looping.
 *
 * Pure JVM: every transition is proved off-device by [PhoneBootstrapTest].
 */
object PhoneBootstrap {

    /**
     * How long the gateway has, after it answers again, to complete the MCP handshake. The same
     * three minutes [PhoneSetup] allows for a handover, stated where the wait is about this flow.
     */
    const val HANDSHAKE_WINDOW_MS = PhoneSetup.TIMEOUT_MS

    /**
     * How long the gateway has to drop and come back after the job is triggered. A gateway that
     * never drops is not evidence of a restart — the job may not have run — so the flow fails
     * rather than calling a live-but-unchanged gateway "restarting" for ever.
     */
    const val RESTART_WINDOW_MS = 60_000L

    /** The marker the agent must put on its own line. Nothing else is read as a job id. */
    const val JOB_ID_PREFIX = "JOB_ID:"

    /** The gateway's own job-id shape (`[a-f0-9]{12}`); anything else is refused, not guessed. */
    private val JOB_ID = Regex("^[0-9a-f]{12}$")

    // ---- the button and the promise made before it is pressed ----

    /** The one action in the not-set-up state. */
    const val BUTTON = "SET THIS UP WITH MY AGENT"

    /**
     * The button's own line, shown before the press. The owner is told what the agent will receive
     * and that Hermes will restart — the part that would otherwise be discovered as a dropped call.
     * It also says who acts: Vox writes nothing to the host itself.
     */
    const val BUTTON_WARNING =
        "Your agent will be given this phone's address and its token. Hermes will restart to pick " +
            "up the new server, which drops this call for a few seconds. Nothing is written to " +
            "your host by Vox itself — your agent does the configuring, exactly as if you had " +
            "pasted the prompt by hand."

    // ---- the steps, in the owner's words ----

    const val ASKING_HEADING = "Asking your agent"
    const val ASKING_MEANING =
        "The instruction has been sent on this phone's authenticated connection. Waiting for your " +
            "agent to add this phone and create the restart job. Its reply is not proof — only " +
            "the MCP handshake is."

    const val TRIGGERING_HEADING = "Your agent answered — starting the restart"
    const val TRIGGERING_MEANING =
        "Its answer named the restart job in the required form. Vox is starting that job now; " +
            "this trigger is the only effect Vox has on the host."

    const val RESTARTING_HEADING = "Hermes is restarting — this takes a few seconds"
    const val RESTARTING_MEANING =
        "The connection dropped as expected, not as an error. Vox is polling until the gateway " +
            "answers again, then it waits for the handshake."

    const val AWAITING_HEADING = "Waiting for the MCP handshake"
    const val AWAITING_MEANING =
        "The gateway is back. Waiting for it to complete the MCP handshake with this phone; that " +
            "handshake is the only proof the setup took."

    const val CONNECTED_HEADING = PhoneToolsView.CONNECTED_HEADING
    const val CONNECTED_MEANING =
        "Your agent reached this phone and completed the MCP handshake."

    // ---- the failures, each named ----

    const val NO_ANSWER_HEADING = "Your agent did not answer"
    const val NO_ANSWER_MEANING =
        "The instruction was sent, but no answer came back. The setup may not have taken. Copy " +
            "the setup prompt below and hand it over yourself."

    const val NO_JOB_ID_HEADING = "Your agent did not name a restart job"
    const val NO_JOB_ID_MEANING =
        "Vox will not guess a job id. Nothing was started and Hermes was not restarted. Copy the " +
            "setup prompt below and hand it over yourself."

    const val TRIGGER_REFUSED_HEADING = "The restart job could not be started"
    const val TRIGGER_REFUSED_MEANING =
        "The gateway refused to start the restart job. Nothing was restarted. Copy the setup " +
            "prompt below and hand it over yourself."

    const val NOT_RESTARTED_HEADING = "The gateway did not restart"
    const val NOT_RESTARTED_MEANING =
        "The restart was requested, but the gateway never dropped and came back within one " +
            "minute. The setup may not have taken. Copy the setup prompt below and hand it over " +
            "yourself."

    const val NO_HANDSHAKE_HEADING = "The setup may not have taken"
    const val NO_HANDSHAKE_MEANING =
        "Hermes restarted, but no MCP handshake arrived within three minutes. The listener is only " +
            "up while a call is. Copy the setup prompt below and hand it over yourself."

    const val SEND_FAILED_HEADING = "The instruction did not complete"
    const val SEND_FAILED_MEANING =
        "The cause is named below. Nothing else was sent; the copyable prompt is the fallback."

    const val NOT_CONFIGURED_MEANING =
        "No connection configured: set the gateway under Entity & Connection, then try again."

    const val NO_TAILNET_MEANING =
        "Tailscale is not running, so there is no real address for the agent. Nothing was sent. " +
            "Start it and open this sheet again."

    const val ALPHA_OFF = PhoneToolsView.ALPHA_OFF

    const val STOPPED_HEADING = "The setup stopped unexpectedly"
    const val STOPPED_MEANING =
        "The flow could not continue. Nothing else was sent. Copy the setup prompt below and hand " +
            "it over yourself."

    /** The steps the flow can be on. [FAILED] is terminal for one attempt. */
    enum class Step { IDLE, ASKING, TRIGGERING, RESTARTING, AWAITING_HANDSHAKE, CONNECTED, FAILED }

    /** What stopped the flow. Each has its own words; none is a generic "could not reach". */
    enum class Failure {
        NOT_CONFIGURED,
        NO_TAILNET,
        ALPHA_OFF,
        NO_ANSWER,
        NO_JOB_ID,
        TRIGGER_REFUSED,
        NOT_RESTARTED,
        NO_HANDSHAKE,
        SEND_FAILED,
        STOPPED,
    }

    /**
     * Everything one attempt knows. There is deliberately no field for the instruction or the
     * token: the message is held only for the duration of the send, so no view, log or record can
     * accidentally carry it.
     *
     * [startedAt] is when the attempt began (and the baseline for a handshake that counts);
     * [restartedAt] is when the gateway answered again after a drop; [dropSeen] is the expected
     * disconnect. [reached] keeps the furthest step for the failure copy.
     */
    data class Progress(
        val step: Step = Step.IDLE,
        val jobId: String? = null,
        val failure: Failure? = null,
        val reached: Step? = null,
        val startedAt: Long = 0L,
        val restartedAt: Long = 0L,
        val dropSeen: Boolean = false,
    )

    /** A new attempt: the instruction has just gone out. */
    fun start(now: Long): Progress = Progress(step = Step.ASKING, startedAt = now)

    /** A precondition failed before anything was sent. */
    fun startFailure(failure: Failure): Progress =
        Progress(step = Step.FAILED, failure = failure, reached = Step.IDLE)

    /** This attempt stopped at [progress]'s current step; the furthest step is kept for the copy. */
    fun failed(progress: Progress, failure: Failure): Progress = fail(progress, failure)

    /**
     * The agent's answer. A blank answer is [Failure.NO_ANSWER]; an answer without exactly one
     * job id in [JOB_ID_PREFIX] form is [Failure.NO_JOB_ID]. Neither advances the flow.
     */
    fun answered(progress: Progress, reply: String?, now: Long): Progress {
        if (progress.step != Step.ASKING) return progress
        if (reply.isNullOrBlank()) return fail(progress, Failure.NO_ANSWER)
        val id = jobId(reply) ?: return fail(progress, Failure.NO_JOB_ID)
        return progress.copy(step = Step.TRIGGERING, jobId = id)
    }

    /**
     * Read the job id from the agent's answer, and only from a line marked [JOB_ID_PREFIX].
     * Exactly one such line, and only the gateway's 12-hex form, is accepted: prose such as
     * "the job is job_abc" is not a job id, and two different marked ids are ambiguous.
     */
    fun jobId(reply: String): String? {
        val marked = reply.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith(JOB_ID_PREFIX) }
            .toList()
        if (marked.size != 1) return null
        val raw = marked.single().removePrefix(JOB_ID_PREFIX).trim().trim('`', '*', '"', '\'', ' ')
        return raw.takeIf { JOB_ID.matches(it) }
    }

    /**
     * The job was accepted by the gateway. Only a progress that actually holds a job id can be
     * triggered; a missing id is a refusal, never an invitation to guess.
     */
    fun triggered(progress: Progress, now: Long): Progress =
        if (progress.step == Step.TRIGGERING && progress.jobId != null) {
            progress.copy(step = Step.RESTARTING, startedAt = now, dropSeen = false, restartedAt = 0L)
        } else {
            progress
        }

    /** The gateway refused `POST /api/jobs/{id}/run`; the flow stops and offers the fallback. */
    fun triggerFailed(progress: Progress): Progress =
        if (progress.step == Step.TRIGGERING) fail(progress, Failure.TRIGGER_REFUSED) else progress

    /**
     * One health probe while waiting for the restart. A failed probe is the expected drop and is
     * recorded, not treated as an error. A later successful probe is [Step.AWAITING_HANDSHAKE].
     *
     * A successful probe before the drop is not evidence: the gateway may not have restarted yet,
     * so the flow waits, and [RESTART_WINDOW_MS] decides when that is a failure.
     */
    fun probe(progress: Progress, up: Boolean, now: Long): Progress {
        if (progress.step != Step.RESTARTING) return progress
        if (!up) {
            val dropped = progress.copy(dropSeen = true)
            return if (now - progress.startedAt >= RESTART_WINDOW_MS) {
                fail(dropped, Failure.NOT_RESTARTED)
            } else {
                dropped
            }
        }
        if (progress.dropSeen) return progress.copy(step = Step.AWAITING_HANDSHAKE, restartedAt = now)
        return if (now - progress.startedAt >= RESTART_WINDOW_MS) {
            fail(progress, Failure.NOT_RESTARTED)
        } else {
            progress
        }
    }

    /**
     * A handshake recorded after this attempt began. A handshake from before the press is not
     * evidence for this attempt; one seen while the gateway is down is still the proof if it was
     * recorded by the phone.
     */
    fun handshake(progress: Progress, handshakeAt: Long): Progress =
        if (handshakeAt > progress.startedAt &&
            (progress.step == Step.RESTARTING || progress.step == Step.AWAITING_HANDSHAKE)
        ) {
            progress.copy(step = Step.CONNECTED)
        } else {
            progress
        }

    /** The clock: a stated window, and then the fallback. Never Connected by elapse. */
    fun expire(progress: Progress, now: Long): Progress = when {
        progress.step == Step.RESTARTING && now - progress.startedAt >= RESTART_WINDOW_MS ->
            fail(progress, Failure.NOT_RESTARTED)
        progress.step == Step.AWAITING_HANDSHAKE &&
            now - progress.restartedAt >= HANDSHAKE_WINDOW_MS ->
            fail(progress, Failure.NO_HANDSHAKE)
        else -> progress
    }

    /** Apply the phone's own evidence and the stored window, without mutating the stored attempt. */
    fun settled(progress: Progress, handshakeAt: Long, now: Long): Progress =
        expire(handshake(progress, handshakeAt), now)

    /** True while the one attempt is in flight; a second press is ignored. */
    fun busy(progress: Progress): Boolean = when (progress.step) {
        Step.ASKING, Step.TRIGGERING, Step.RESTARTING, Step.AWAITING_HANDSHAKE -> true
        else -> false
    }

    /** The failure is terminal for this attempt, and the copyable prompt is the way forward. */
    fun offersFallback(progress: Progress): Boolean = progress.step == Step.FAILED

    // ---- the words each step and failure uses ----

    fun heading(progress: Progress): String = when (progress.step) {
        Step.IDLE -> PhoneToolsView.NOT_SET_UP_HEADING
        Step.ASKING -> ASKING_HEADING
        Step.TRIGGERING -> TRIGGERING_HEADING
        Step.RESTARTING -> RESTARTING_HEADING
        Step.AWAITING_HANDSHAKE -> AWAITING_HEADING
        Step.CONNECTED -> CONNECTED_HEADING
        Step.FAILED -> when (progress.failure) {
            Failure.NO_ANSWER -> NO_ANSWER_HEADING
            Failure.NO_JOB_ID -> NO_JOB_ID_HEADING
            Failure.TRIGGER_REFUSED -> TRIGGER_REFUSED_HEADING
            Failure.NOT_RESTARTED -> NOT_RESTARTED_HEADING
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
        Step.ASKING -> ASKING_MEANING
        Step.TRIGGERING -> TRIGGERING_MEANING
        Step.RESTARTING -> RESTARTING_MEANING
        Step.AWAITING_HANDSHAKE -> AWAITING_MEANING
        Step.CONNECTED -> CONNECTED_MEANING
        Step.FAILED -> when (progress.failure) {
            Failure.NO_ANSWER -> NO_ANSWER_MEANING
            Failure.NO_JOB_ID -> NO_JOB_ID_MEANING
            Failure.TRIGGER_REFUSED -> TRIGGER_REFUSED_MEANING
            Failure.NOT_RESTARTED -> NOT_RESTARTED_MEANING
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
            Failure.NO_ANSWER -> "It reached: the instruction was sent; no answer came back."
            Failure.NO_JOB_ID ->
                "It reached: your agent answered, but its answer did not name a restart job."
            Failure.TRIGGER_REFUSED ->
                "It reached: your agent answered and named the restart job; the gateway refused to start it."
            Failure.NOT_RESTARTED ->
                "It reached: the restart was requested; the gateway never dropped and came back within one minute."
            Failure.NO_HANDSHAKE ->
                "It reached: Hermes restarted; no MCP handshake arrived within three minutes."
            Failure.SEND_FAILED, Failure.STOPPED, null -> "It reached: the instruction was not completed."
        }
    }

    /**
     * Does a thrown job trigger mean the job definitely did not start? An HTTP status the gateway
     * gave is a refusal; a transport failure or a timeout may be the restart beginning, so it is
     * allowed to proceed to the observed-restart phase.
     */
    fun triggerRefused(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        return listOf(" 400 ", " 401 ", " 403 ", " 404 ", " 500 ", " 502 ", " 503 ")
            .any { message.contains(it) }
    }

    /**
     * Terminal for this attempt: the step it stopped on is kept, so the failure can say how far it
     * got. A later failure never overwrites a further one.
     */
    private fun fail(progress: Progress, failure: Failure): Progress =
        progress.copy(step = Step.FAILED, failure = failure, reached = progress.reached ?: progress.step)
}
