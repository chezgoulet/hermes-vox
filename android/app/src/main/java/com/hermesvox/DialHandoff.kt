package com.hermesvox

/**
 * DialHandoff — opening the dialer without leaving Vox's own call talking to itself.
 *
 * A dial is a **handoff**, not a call placement: the phone opens the dialer with the
 * number ready and the owner presses call (`ACTION_DIAL`, never `CALL_PHONE`). When a Vox
 * call is live, the handoff has to end it first — the dialer is about to take the audio
 * route and the screen, and a voice line still listening under it is a microphone the owner
 * cannot see. So the order is fixed:
 *
 *   1. **announce** — one line through the existing being: [LINE];
 *   2. **end** — the same teardown the on-screen hang-up runs;
 *   3. **dial** — the `ACTION_DIAL` intent.
 *
 * Ending the call does **not** end the conversation: the thread lives on the gateway, and
 * the next call resumes it. That is what makes this acceptable rather than a lost turn.
 *
 * ## Why the announcement takes a continuation
 *
 * [Host.announce] receives the rest of the handoff as `after`. On device the line is voiced
 * through the being and `after` runs when TTS **finishes** the utterance — with a bounded
 * fallback when nothing can speak or the engine never reports completion. Ending the call is
 * what stops TTS, so firing on *start* would cut the line that has just begun. The test host
 * calls `after` immediately, which is what makes the order assertable without a clock.
 *
 * ## Why it does not wait for the telephony call
 *
 * By the time the dialer's call connects, audio focus has already moved and Vox's teardown
 * would be running under a grab. The announcement + end happen at the handoff, while the
 * phone is still ours to close. Nothing here waits for a connection or observes the call.
 *
 * Pure JVM; [PhoneEngine]'s platform is the host on device, a recording fake in the tests.
 */
object DialHandoff {

    /** The one line the being says at the handoff. Lower case like the rest of the being's talk. */
    const val LINE = "dialling — ending our call"

    /** What the handoff needs from the phone. */
    interface Host {
        /** Is a Vox call running right now? Read at the moment of the dial, never cached. */
        fun callLive(): Boolean = false

        /** Can this phone resolve an `ACTION_DIAL` intent at all? False is `no_app`. */
        fun canDial(): Boolean = true

        /**
         * Say [line] through the being, then run [after] **when the line is done** — not when
         * it starts. On device the done callback is [VoxTts]'s completion; a host that cannot
         * speak runs [after] at once.
         */
        fun announce(line: String, after: () -> Unit) {
            after()
        }

        /** The same teardown the on-screen hang-up runs. */
        fun endCall() {}

        /** Start `ACTION_DIAL` with [number]. False when the intent could not be started. */
        fun dial(number: String): Boolean = false
    }

    /**
     * The one-shot that runs the rest of a live-call handoff.
     *
     * TTS reports *start* and *done* separately, and the teardown cuts whatever is playing, so
     * only **done** may trigger [after]. [onFallback] is the bound measured from the start for an
     * engine that never reports either. Whichever arrives first wins; the rest are ignored.
     */
    class AnnounceOnce(private val after: () -> Unit) {
        private val fired = java.util.concurrent.atomic.AtomicBoolean(false)

        /** Synthesis started. Deliberately does nothing: the line is not over. */
        fun onStarted() = Unit

        /** The line finished (or was cut). Run the handoff now. */
        fun onDone() = fire()

        /** The bound from the start expired. Run the handoff now. */
        fun onFallback() = fire()

        private fun fire() {
            if (fired.compareAndSet(false, true)) after()
        }
    }


    /** Which path the handoff took. The tests assert it; callers do not branch on it. */
    enum class Outcome {
        /** There is no live call: the dialer was opened straight away. */
        DIALED,

        /** A live call: the line was announced, the call is being ended, and the dialer follows. */
        ANNOUNCED_END_THEN_DIAL,

        /** No dialer answers on this phone; nothing was announced and no call was ended. */
        NO_DIALER,

        /** The dialer intent could not be started. */
        DIAL_FAILED,
    }

    /**
     * Hand [number] to the dialer, ending a live Vox call at the handoff. The dialer is
     * resolved **before** anything is ended, so a phone with no dialer is `no_app` rather
     * than a call lost for nothing.
     *
     * [onLiveDial] is called with the dialer's own result only on the live-call path, after
     * [Host.endCall]. The caller has already answered with a pending handoff by then, so this
     * is how the post-teardown outcome becomes its own audited row rather than being lost (H1).
     */
    fun run(
        host: Host,
        number: String,
        onLiveDial: (Boolean) -> Unit = {},
    ): Outcome {
        if (!host.canDial()) return Outcome.NO_DIALER
        if (!host.callLive()) return if (host.dial(number)) Outcome.DIALED else Outcome.DIAL_FAILED
        host.announce(LINE) {
            host.endCall()
            onLiveDial(host.dial(number))
        }
        return Outcome.ANNOUNCED_END_THEN_DIAL
    }
}
