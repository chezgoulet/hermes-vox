package com.hermesvox

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.hermesvox.core.HermesSession
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * PhoneBootstrapStore — the Android edge of [PhoneBootstrap]: one attempt, one window, and no
 * persistence of the instruction.
 *
 * **The press owns the credential, the listener and the turn.** [start] is called at the moment of
 * the press. It opens the phone's setup window ([PhoneMcp.openWindow]) so the address proves
 * reachable to `hermes mcp add` *now*, resolves the connection in force ([PhoneSetupStore.session]),
 * creates the phone's token if there is not one yet, composes [PhoneSetup.prompt] with that token,
 * sends it as an ordinary stored turn, and keeps the text only in [PhoneSetupStore.lastPrompt] —
 * memory for this process, never a preference, log or audit entry.
 *
 * **No restart is triggered.** The gateway reconciles its MCP configuration while it runs; there is
 * no restart route and none is called. The only host effect is the agent's own configuration edit.
 *
 * **The wait is the phone's own evidence.** A one-second pass re-reads the handshake recorded by
 * [PhoneMcpServer]. The stored state moves only through [PhoneBootstrap]'s pure transitions, so a
 * reply cannot connect the phone, and neither can a timeout. When the window closes without a
 * handshake the flow fails with the copyable prompt and the agent's last words.
 *
 * **One attempt.** [running] is an [AtomicBoolean]; a second press while the flow is in flight is
 * ignored. A failure ends the thread and [PhoneBootstrap.offersFallback] is true, so the sheet shows
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
        // the turn while the window waits for the handshake.
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

        // The listener must be up *before* the instruction goes out: `hermes mcp add` proves
        // reachability when it runs, and this phone is idle. With no window there is nothing to
        // reach, so nothing is sent — the sheet says so and offers the prompt instead.
        val windowEndsAt = PhoneMcp.openWindow(app) { args -> LocalActions.shared(app).phone(args) }
        if (windowEndsAt == null) {
            progress.set(PhoneBootstrap.startFailure(PhoneBootstrap.Failure.WINDOW_CLOSED))
            running.set(false)
            tick(onTick)
            return false
        }

        val now = System.currentTimeMillis()
        val prompt = PhoneSetup.prompt(host, token)
        PhoneSetupStore.rememberSent(prompt)
        progress.set(PhoneBootstrap.start(now, windowEndsAt))
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
                // A handshake recorded while the turn was in flight is stronger evidence than the
                // failed reply: the agent may have added the phone and then lost the turn.
                val observed = PhoneBootstrap.settled(
                    progress.get(),
                    PhoneMcp.handshakeAt(app),
                    System.currentTimeMillis(),
                )
                if (observed.step == PhoneBootstrap.Step.CONNECTED) {
                    update(observed, onTick)
                } else {
                    update(PhoneBootstrap.failed(progress.get(), kind), onTick)
                }
                return
            }

            // The reply is shown in full, but a token the agent echoes back must not reach the
            // record. The token itself is never logged.
            val clean = PhoneSetupSend.redact(reply, token).orEmpty()
            PhoneSetupStore.replied(app, clean, token)
            val answered = PhoneBootstrap.answered(progress.get(), clean, System.currentTimeMillis())
            val next = PhoneBootstrap.settled(answered, PhoneMcp.handshakeAt(app), System.currentTimeMillis())
            update(next, onTick)
            if (next.step == PhoneBootstrap.Step.CONNECTED) return
            awaitHandshake(app, onTick)
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
     * Wait for the handshake the phone itself must record. Each pass is one second; the pure
     * transitions decide when the answer arrived, when the window closed, and when it is time to
     * show the fallback. There is no host probe and no automatic second attempt.
     */
    private fun awaitHandshake(app: Context, onTick: () -> Unit) {
        while (PhoneBootstrap.busy(progress.get())) {
            val next = PhoneBootstrap.settled(
                progress.get(),
                PhoneMcp.handshakeAt(app),
                System.currentTimeMillis(),
            )
            update(next, onTick)
            if (!PhoneBootstrap.busy(next)) break
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
