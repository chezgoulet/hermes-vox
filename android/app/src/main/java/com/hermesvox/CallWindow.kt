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

        /**
         * Run [action] later on the host's own thread (the main thread, on device).
         *
         * Returns false when the window cannot take it — the activity is finishing or being
         * destroyed — so a launch that will never run is refused rather than reported (M6).
         */
        fun post(delayMs: Long, action: () -> Unit): Boolean
    }

    /** The two arrivals, named so a caller (and a test) can say which one happened. */
    enum class Order { PIP_THEN_LAUNCH, LAUNCH_NOW }

    /**
     * How the start was arranged: [order], and whether the host accepted a posted start.
     * `posted` is always true for [Order.LAUNCH_NOW] because the start ran immediately.
     */
    data class Plan(val order: Order, val posted: Boolean)

    /**
     * Start [start] — through PiP when a call is live and the window will float, otherwise
     * straight away. Returns the [Plan], which is what the tests assert and the engine uses to
     * decide whether it is a start, a pending handoff, or a refusal.
     */
    /**
     * Where a start lands. Only [OUTSIDE] — a browser, another app, the system file picker —
     * covers the call and earns the PiP funnel. [OWN_SCREEN] is one of ours (Settings,
     * Models, Sessions, About): the user chose to look at it, the call carries on behind it,
     * and shrinking the call into a floating window there is wrong (field-2, item 1).
     */
    enum class Target { OWN_SCREEN, OUTSIDE }

    /**
     * Start [start] for a [target]. [Target.OWN_SCREEN] starts immediately and never asks the
     * host anything — not `callLive`, not `enterPip`. [Target.OUTSIDE] is [launch].
     */
    fun open(host: Host, target: Target, start: () -> Unit): Plan = when (target) {
        Target.OWN_SCREEN -> {
            start()
            Plan(Order.LAUNCH_NOW, posted = true)
        }
        Target.OUTSIDE -> launch(host, start)
    }

    fun launch(host: Host, start: () -> Unit): Plan {
        if (!host.callLive() || !host.enterPip()) {
            start()
            return Plan(Order.LAUNCH_NOW, posted = true)
        }
        val posted = host.post(AFTER_PIP_MS, start)
        return Plan(Order.PIP_THEN_LAUNCH, posted)
    }
}
