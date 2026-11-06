package com.hermesvox

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.hermesvox.core.HermesSession
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * PhoneBootstrapStore — the Android edge of [PhoneBootstrap]: one attempt, one thread, and no
 * persistence of the instruction.
 *
 * **The press owns the credential and the turn.** [start] is called at the moment of the press. It
 * resolves the connection in force ([PhoneSetupStore.session]), creates the phone's token if there
 * is not one yet, composes [PhoneSetup.prompt] with that token, sends it as an ordinary stored turn,
 * and keeps the text only in [PhoneSetupStore.lastPrompt] — memory for this process, never a
 * preference, log or audit entry.
 *
 * **The app starts the job; the agent owns it.** After the agent names a job id in the required
 * form, [HermesSession.runJob] sends `POST /api/jobs/{id}/run`. There is no restart endpoint in the
 * app, and none is looked for.
 *
 * **The drop is expected.** After the trigger, [HermesSession.gatewayAnswers] is polled every
 * second. A failed probe is recorded as the restart, not surfaced as an error; a later successful
 * probe moves the flow to the handshake wait. The stored state moves only through
 * [PhoneBootstrap]'s pure transitions.
 *
 * **One attempt.** [running] is an [AtomicBoolean]; a second press while the flow is in flight is
 * ignored. A failure ends the thread and [PhoneBootstrap.offerFallback] is true, so the sheet shows
 * the copyable prompt instead of trying again by itself.
 */
object PhoneBootstrapStore {

    private val running = AtomicBoolean(false)
    private val progress = AtomicReference(PhoneBootstrap.Progress())

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
        val app = activity.applicationContext ?: activity

        if (!AlphaGate.allows(app, AlphaGate.FeatureSet.PHONE_TOOLS)) {
            progress.set(PhoneBootstrap.startFailure(PhoneBootstrap.Failure.ALPHA_OFF))
            running.set(false)
            tick(onTick)
            return false
        }
        val status = PhoneMcp.status(app)
        val host = status.tailnet
        if (host.isNullOrBlank()) {
            progress.set(PhoneBootstrap.startFailure(PhoneBootstrap.Failure.NO_TAILNET))
            running.set(false)
            tick(onTick)
            return false
        }
        // #430: resolved at the press, from the connection in force. The same session then carries
        // the turn, the job trigger and the health probes; a restart leaves its url and key intact.
        val session = PhoneSetupStore.session(activity)
        if (session == null) {
            progress.set(PhoneBootstrap.startFailure(PhoneBootstrap.Failure.NOT_CONFIGURED))
            running.set(false)
            tick(onTick)
            return false
        }
        val token = PhoneMcp.token(app)
        if (token.isBlank()) {
            progress.set(PhoneBootstrap.startFailure(PhoneBootstrap.Failure.ALPHA_OFF))
            running.set(false)
            tick(onTick)
            return false
        }

        val now = System.currentTimeMillis()
        val prompt = PhoneSetup.prompt(host, token)
        progress.set(PhoneBootstrap.start(now))
        PhoneSetupStore.begin(app, now)
        tick(onTick)
        Thread({ run(app, session, prompt, token, onTick) }, "phone-bootstrap")
            .apply { isDaemon = true }
            .start()
        return true
    }

    private fun run(
        app: Context,
        session: HermesSession,
        prompt: String,
        token: String,
        onTick: () -> Unit,
    ) {
        try {
            var reply: String? = null
            var error: Throwable? = null
            try {
                reply = session.turnStored(prompt)
            } catch (t: Throwable) {
                error = t
            }

            val failure = PhoneSetupSend.reason(error, reply, token)
            if (failure != null) {
                PhoneSetupStore.failed(app, failure)
                val kind = when (PhoneSetupSend.classify(error, reply)) {
                    PhoneSetupSend.Failure.NO_CONNECTION -> PhoneBootstrap.Failure.NOT_CONFIGURED
                    PhoneSetupSend.Failure.KEY_REJECTED, PhoneSetupSend.Failure.TURN_FAILED ->
                        PhoneBootstrap.Failure.SEND_FAILED
                    else -> PhoneBootstrap.Failure.NO_ANSWER
                }
                update(PhoneBootstrap.failed(progress.get(), kind), onTick)
                return
            }

            // The reply is shown in full, but a token the agent echoes back must not reach the
            // record. The token itself is never logged.
            val clean = PhoneSetupSend.redact(reply, token).orEmpty()
            PhoneSetupStore.replied(app, clean, token)
            val answered = PhoneBootstrap.answered(progress.get(), clean, System.currentTimeMillis())
            update(answered, onTick)
            if (answered.step == PhoneBootstrap.Step.FAILED) return
            val id = answered.jobId ?: return

            val triggerError = try {
                session.runJob(id)
                null
            } catch (t: Throwable) {
                t
            }
            if (triggerError != null && PhoneBootstrap.triggerRefused(triggerError)) {
                PhoneSetupStore.failedAfterReply(
                    app,
                    PhoneSetupSend.message(
                        PhoneSetupSend.Failure.TURN_FAILED,
                        PhoneSetupSend.scrub(triggerError.message.orEmpty(), token),
                    ),
                )
                update(PhoneBootstrap.triggerFailed(answered), onTick)
                return
            }
            // A transport failure here may be the gateway going down with the job. It is not
            // evidence either way; the health probes decide, and the window fails honestly.
            VoxLog.d(
                "event=phone-bootstrap trigger=" +
                    if (triggerError == null) "accepted" else "transport",
            )
            update(PhoneBootstrap.triggered(answered, System.currentTimeMillis()), onTick)
            poll(app, session, onTick)
        } catch (_: Throwable) {
            // Never let a bootstrap crash the app, and never put the instruction in a log line.
            PhoneSetupStore.failedAfterReply(
                app,
                PhoneSetupSend.message(PhoneSetupSend.Failure.TURN_FAILED, "the setup stopped unexpectedly"),
            )
            update(
                PhoneBootstrap.Progress(
                    step = PhoneBootstrap.Step.FAILED,
                    failure = PhoneBootstrap.Failure.STOPPED,
                    reached = progress.get().step,
                ),
                onTick,
            )
        } finally {
            running.set(false)
            tick(onTick)
        }
    }

    /**
     * Wait for the restart and then for the handshake. Each pass is one second; the pure transitions
     * decide when the expected drop, the return, and either window have been reached.
     */
    private fun poll(app: Context, session: HermesSession, onTick: () -> Unit) {
        while (progress.get().step == PhoneBootstrap.Step.RESTARTING) {
            val up = session.gatewayAnswers()
            val next = PhoneBootstrap.probe(progress.get(), up, System.currentTimeMillis())
            update(next, onTick)
            if (next.step != PhoneBootstrap.Step.RESTARTING) break
            sleep()
        }
        while (progress.get().step == PhoneBootstrap.Step.AWAITING_HANDSHAKE) {
            val next = PhoneBootstrap.settled(
                progress.get(),
                PhoneMcp.handshakeAt(app),
                System.currentTimeMillis(),
            )
            update(next, onTick)
            if (next.step != PhoneBootstrap.Step.AWAITING_HANDSHAKE) break
            sleep()
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

    private fun sleep() {
        try {
            Thread.sleep(1_000L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
