package com.hermesvox

/**
 * CallWindow — opening another app without losing the call: PiP first, then the intent.
 *
 * The case that started this: the mind launches another app mid-call (`apps.launch` /
 * `open`), that app takes the screen, and Vox — and with it the only visible sign that a
 * call is running — disappears. On Android 12+ the system shrinks us when the *user*
 * swipes home, but it does not know why the app itself is starting an activity, so this
 * is the one path the app has to arrange by hand.
 *
 * THE ORDER (and its two escape hatches)
 * --------------------------------------
 *  1. no live call -> start immediately. An idle app must never float.
 *  2. live call, `enterPip()` accepted -> start after the window exists, so the call is
 *     already visible beside the app the mind opened.
 *  3. live call but PiP refused (feature absent, window refused, an OEM that says no) ->
 *     start immediately anyway. **A launch is never failed because of PiP**; the call
 *     continues in the background exactly as it did before this file.
 *
 * Pure JVM, host injected: [Host] is `MainActivity` on device and a recording fake in
 * `CallWindowTest`, which proves the order and the fallback without an Activity.
 */
object CallWindow {

    /**
     * A quarter second: long enough for the system to have accepted the PiP transition
     * before the new activity is started over it, short enough that a tap still feels
     * immediate. The call is already live either way; this only orders two frames of UI.
     */
    const val AFTER_PIP_MS = 250L

    /** What the window can ask its host. */
    interface Host {
        /** Is a call running right now? Read at the moment of the launch, never cached. */
        fun callLive(): Boolean

        /** Enter PiP now. False means the window would not float (see [Pip.supported]). */
        fun enterPip(): Boolean

        /** Run [action] later on the host's own thread (the main thread, on device). */
        fun post(delayMs: Long, action: () -> Unit)
    }

    /** The two arrivals, named so a caller (and a test) can say which one happened. */
    enum class Order { PIP_THEN_LAUNCH, LAUNCH_NOW }

    /**
     * Start [start] — through PiP when a call is live and the window will float, otherwise
     * straight away. Returns the [Order] used, which is what the tests assert; callers do
     * not branch on it.
     */
    fun launch(host: Host, start: () -> Unit): Order {
        if (!host.callLive() || !host.enterPip()) {
            start()
            return Order.LAUNCH_NOW
        }
        host.post(AFTER_PIP_MS, start)
        return Order.PIP_THEN_LAUNCH
    }
}
