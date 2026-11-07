package com.hermesvox

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.hermesvox.core.HermesException
import com.hermesvox.core.HermesSession
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * PhoneBootstrapStore — the Android edge of [PhoneBootstrap]: one attempt, a listener that follows
 * the run, and no persistence of the instruction.
 *
 * **The press owns the credential, the listener and the turn.** [start] is called at the moment of
 * the press. It opens the phone's setup listener ([PhoneMcp.openWindow]) so the address proves
 * reachable to `hermes mcp add` whenever the agent gets there, resolves the connection in force
 * ([PhoneSetupStore.session]), creates the phone's token if there is not one yet, composes
 * [PhoneSetup.prompt] with that token, sends it as an ordinary stored turn, and keeps the text only
 * in [PhoneSetupStore.lastPrompt] — memory for this process, never a preference, log or audit entry.
 *
 * **The turn is driven by the run's own events, with no deadline.** [PhoneBootstrapStore] starts a
 * streamed turn ([HermesSession.startSetupStream]) and drains it until the run ends, however long
 * that takes. On `/v1/runs` the reader reattaches to the same run after a drop and never posts
 * `/stop`; the only stop this file asks for is [cancel] — the owner's **Stop this attempt** or the
 * sheet going away. Either way the card says so ([PhoneBootstrap.Failure.CANCELLED] /
 * [PhoneBootstrap.Failure.LEFT]) instead of going quiet.
 *
 * **The work is shown, never the arguments.** Each tool event the run delivers becomes one line
 * ([PhoneBootstrap.observed] → [PhoneSetupActivity]); only the tool's name is read, so the token
 * that rides the instruction cannot reappear on the screen, in a log or in the record.
 *
 * **No restart is triggered.** The gateway reconciles its MCP configuration while it runs; there is
 * no restart route and none is called. The only host effect is the agent's own configuration edit.
 *
 * **The wait is the phone's own evidence.** A short pass re-reads the handshake recorded by
 * [PhoneMcpServer]. The stored state moves only through [PhoneBootstrap]'s pure transitions, so a
 * reply cannot connect the phone, and neither can a timeout. When the run ends without a handshake
 * the flow fails with the copyable prompt and the agent's last words.
 *
 * **One attempt.** [running] is an [AtomicBoolean]; a second press while the flow is in flight is
 * ignored. A failure ends the thread and [PhoneBootstrap.offersFallback] is true, so the sheet shows
 * the copyable prompt instead of trying again by itself.
 */
object PhoneBootstrapStore {

    /** How often the drain loop wakes to look for events when the push-side wake is quiet. */
    private const val POLL_MS = 250L

    /** How often the post-run grace re-reads the phone's handshake. */
    private const val GRACE_POLL_MS = 500L

    private val running = AtomicBoolean(false)
    private val progress = AtomicReference(PhoneBootstrap.Progress())

    /** Set by [cancel] so the drain loop never reports a cancel as a transport failure. */
    @Volatile private var cancelled = false

    /** The stream this attempt drains; empty until the admission returns. */
    @Volatile private var stream = ""

    /** The session the attempt rides, so [cancel] can stop the run it names. */
    @Volatile private var active: HermesSession? = null

    /** The latest step, safe to read from the UI thread. */
    fun progress(): PhoneBootstrap.Progress = progress.get()

    /** True while the one attempt is in flight. */
    fun busy(): Boolean = running.get()

    /** A rotated token invalidates the attempt and everything it observed. */
    fun reset() {
        if (!running.get()) progress.set(PhoneBootstrap.Progress())
    }

    /**
     * Start one attempt. Returns false when a flow is already running (the press is ignored) or a
     * precondition failed; in the latter case [progress] is [PhoneBootstrap.Step.FAILED] and carries
     * the named cause. [onTick] runs whenever the step changes and once when the thread ends; it may
     * be called off the UI thread, so the sheet posts its repaint.
     */
    fun start(activity: AppCompatActivity, onTick: () -> Unit = {}): Boolean {
        if (!running.compareAndSet(false, true)) return false
        cancelled = false
        stream = ""
        val app = activity.applicationContext ?: activity

        if (!AlphaGate.allows(app, AlphaGate.FeatureSet.PHONE_TOOLS)) {
            return refuse(PhoneBootstrap.startFailure(PhoneBootstrap.Failure.ALPHA_OFF), onTick)
        }
        val status = PhoneMcp.status(app)
        val host = status.tailnet
        if (host.isNullOrBlank()) {
            return refuse(PhoneBootstrap.startFailure(PhoneBootstrap.Failure.NO_TAILNET), onTick)
        }
        // #430: resolved at the press, from the connection in force. The same session then carries
        // the turn while the listener stays open for the run.
        val session = PhoneSetupStore.session(activity)
        if (session == null) {
            return refuse(PhoneBootstrap.startFailure(PhoneBootstrap.Failure.NOT_CONFIGURED), onTick)
        }
        val token = PhoneMcp.token(app)
        if (token.isBlank()) {
            return refuse(PhoneBootstrap.startFailure(PhoneBootstrap.Failure.ALPHA_OFF), onTick)
        }

        // The listener must be up *before* the instruction goes out: `hermes mcp add` proves
        // reachability when it runs, and this phone is idle. With no listener there is nothing to
        // reach, so nothing is sent — the sheet says so and offers the prompt instead.
        if (!PhoneMcp.openWindow(app) { args -> LocalActions.shared(app).phone(args) }) {
            return refuse(PhoneBootstrap.startFailure(PhoneBootstrap.Failure.WINDOW_CLOSED), onTick)
        }

        val now = System.currentTimeMillis()
        val prompt = PhoneSetup.prompt(host, token)
        active = session
        PhoneSetupStore.rememberSent(prompt)
        progress.set(PhoneBootstrap.start(now))
        PhoneSetupStore.begin(app, now)
        tick(onTick)
        Thread({ run(app, session, prompt, token, onTick) }, "phone-bootstrap")
            .apply { isDaemon = true }
            .start()
        return true
    }

    /** A precondition refused the press: record the step and release the one-attempt latch. */
    private fun refuse(next: PhoneBootstrap.Progress, onTick: () -> Unit): Boolean {
        progress.set(next)
        running.set(false)
        tick(onTick)
        return false
    }

    /**
     * Stop the one attempt: the owner's **Stop this attempt**, or the sheet going away. This is one
     * of the only two reasons a setup run is ever stopped. The card keeps the reason
     * ([PhoneBootstrap.Failure.CANCELLED] / [PhoneBootstrap.Failure.LEFT]) and the history it
     * already showed; nothing is silently abandoned.
     */
    fun cancel(
        app: Context,
        failure: PhoneBootstrap.Failure = PhoneBootstrap.Failure.CANCELLED,
    ) {
        if (!running.get()) return
        cancelled = true
        val context = app.applicationContext ?: app
        val sid = stream
        val session = active
        if (sid.isNotEmpty() && session != null) {
            try {
                session.cancelStream(sid)
            } catch (e: Exception) {
                VoxLog.w("event=phone-setup stop=failed err=${e.javaClass.simpleName}")
            }
        }
        val current = progress.get()
        // A handshake already earned is not undone by stopping the turn that carried it.
        val next = if (current.step == PhoneBootstrap.Step.CONNECTED) current
        else PhoneBootstrap.failed(current, failure)
        if (next.step == PhoneBootstrap.Step.FAILED) {
            PhoneSetupStore.failedAfterReply(context, PhoneBootstrap.failureLine(next))
        }
        update(next) {}
        PhoneMcp.closeWindow("setup-stopped")
    }

    private fun run(
        app: Context,
        session: HermesSession,
        prompt: String,
        token: String,
        onTick: () -> Unit,
    ) {
        var replied = false
        var sendFailed = false
        var graceServed = false
        try {
            val sid = try {
                session.startSetupStream(prompt)
            } catch (t: Throwable) {
                reportSendFailure(app, t, null, token, onTick)
                sendFailed = true
                ""
            }
            if (sid.isEmpty()) {
                if (!sendFailed) reportSendFailure(app, HermesException("the turn could not start"), null, token, onTick)
                return
            }
            stream = sid
            if (cancelled) {
                try { session.cancelStream(sid) } catch (_: Exception) {}
                return
            }

            var reply: String? = null
            var error: Throwable? = null
            var done = false
            while (!done && !cancelled) {
                val ready = try {
                    session.waitStream(sid, POLL_MS)
                } catch (t: Throwable) {
                    error = t
                    break
                }
                if (!ready) continue
                val poll = try {
                    PhoneSetupRun.parse(session.pollStreamJSON(sid))
                } catch (t: Throwable) {
                    error = t
                    break
                }
                // The work, as it arrives: one line per tool call, appended in order. The poll's
                // arguments are never read — PhoneSetupRun does not even decode them.
                var next = PhoneBootstrap.observed(progress.get(), poll, System.currentTimeMillis())
                next = PhoneBootstrap.handshake(next, PhoneMcp.handshakeAt(app))
                update(next, onTick)
                if (poll.done) {
                    done = true
                    reply = poll.reply
                    if (poll.error.isNotBlank()) error = HermesException(poll.error)
                }
            }
            if (cancelled) return

            if (error != null) {
                reportSendFailure(app, error, reply, token, onTick)
                sendFailed = true
                return
            }

            // The run ended with the agent's answer. The reply is shown in full, but a token the
            // agent echoes back must not reach the record. The token itself is never logged.
            val clean = PhoneSetupSend.redact(reply, token).orEmpty()
            PhoneSetupStore.replied(app, clean, token)
            replied = true
            var answered = PhoneBootstrap.answered(progress.get(), clean, System.currentTimeMillis())
            answered = PhoneBootstrap.settled(answered, PhoneMcp.handshakeAt(app), System.currentTimeMillis())
            update(answered, onTick)
            if (answered.step == PhoneBootstrap.Step.CONNECTED) return

            // The short grace: the run is over, and the gateway may be connecting right now. The
            // listener is still open. Never a deadline on the agent — only this after-the-fact wait.
            awaitHandshake(app, onTick)
            graceServed = true
        } catch (_: Throwable) {
            // Never let a bootstrap crash the app, and never put the instruction in a log line.
            if (!cancelled) {
                PhoneSetupStore.failedAfterReply(
                    app,
                    PhoneSetupSend.message(PhoneSetupSend.Failure.TURN_FAILED, "the setup stopped unexpectedly"),
                )
                update(
                    PhoneBootstrap.Progress(
                        step = PhoneBootstrap.Step.FAILED,
                        failure = PhoneBootstrap.Failure.STOPPED,
                        reached = progress.get().step,
                        startedAt = progress.get().startedAt,
                        activity = progress.get().activity,
                        lastEventAt = progress.get().lastEventAt,
                    ),
                    onTick,
                )
            }
        } finally {
            val now = System.currentTimeMillis()
            val last = PhoneBootstrap.settled(progress.get(), PhoneMcp.handshakeAt(app), now)
            update(last, onTick)
            if (!cancelled && replied && !sendFailed && last.step == PhoneBootstrap.Step.FAILED) {
                // The run ended without a handshake (or refused the add): name what happened for
                // the record and the Settings row, keeping the agent's own words above it.
                PhoneSetupStore.failedAfterReply(app, PhoneBootstrap.failureLine(last))
            }
            // The listener follows the run: closed at once when the handshake is the outcome, or
            // after the short grace that a late gateway connection may still need. When the grace
            // has already been served, there is nothing left to wait for.
            if (last.step == PhoneBootstrap.Step.CONNECTED) {
                PhoneMcp.closeWindow("setup-connected")
            } else if (graceServed) {
                PhoneMcp.closeWindow("setup-ended")
            } else {
                PhoneMcp.closeWindow("setup-ended", PhoneBootstrap.HANDSHAKE_GRACE_MS)
            }
            active = null
            stream = ""
            running.set(false)
            tick(onTick)
        }
    }

    /** The turn could not be made, or it failed. One named cause, never a generic one. */
    private fun reportSendFailure(
        app: Context,
        error: Throwable,
        reply: String?,
        token: String,
        onTick: () -> Unit,
    ) {
        val failure = PhoneSetupSend.reason(error, reply, token)
        if (failure == null) return
        PhoneSetupStore.failed(app, failure)
        val kind = when (PhoneSetupSend.classify(error, reply)) {
            PhoneSetupSend.Failure.NO_CONNECTION -> PhoneBootstrap.Failure.NOT_CONFIGURED
            PhoneSetupSend.Failure.KEY_REJECTED, PhoneSetupSend.Failure.TURN_FAILED ->
                PhoneBootstrap.Failure.SEND_FAILED
            else -> PhoneBootstrap.Failure.NO_ANSWER
        }
        // A handshake recorded while the turn was in flight is stronger evidence than a failed
        // reply: the agent may have added the phone and then lost the turn.
        val observed = PhoneBootstrap.settled(
            progress.get(),
            PhoneMcp.handshakeAt(app),
            System.currentTimeMillis(),
        )
        update(
            if (observed.step == PhoneBootstrap.Step.CONNECTED) observed
            else PhoneBootstrap.failed(progress.get(), kind),
            onTick,
        )
    }

    /**
     * Wait for the handshake the phone itself must record, after the run has ended. Each pass is
     * [GRACE_POLL_MS]; the pure transitions decide when the answer arrived and when the grace is
     * over. There is no host probe and no automatic second attempt.
     */
    private fun awaitHandshake(app: Context, onTick: () -> Unit) {
        while (!cancelled && PhoneBootstrap.busy(progress.get())) {
            val next = PhoneBootstrap.settled(
                progress.get(),
                PhoneMcp.handshakeAt(app),
                System.currentTimeMillis(),
            )
            update(next, onTick)
            if (!PhoneBootstrap.busy(next)) break
            sleep(GRACE_POLL_MS)
        }
    }

    private fun update(next: PhoneBootstrap.Progress, onTick: () -> Unit) {
        if (next != progress.getAndSet(next)) tick(onTick)
    }

    private fun tick(onTick: () -> Unit) {
        try {
            onTick()
        } catch (_: Throwable) {
        }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
