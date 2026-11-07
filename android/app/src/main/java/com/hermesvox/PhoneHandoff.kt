package com.hermesvox

/**
 * PhoneHandoff — the one bridge from the phone's MCP actions to the screen that owns the call.
 *
 * [PhoneEngine] is pure and [LocalActions] holds only the application context; neither can
 * speak through the being, run the activity's hang-up teardown, or ask the live window to
 * shrink into picture-in-picture. Those three things belong to [MainActivity], which attaches
 * itself here for as long as it exists. A platform that finds no host takes the honest
 * fallback: nothing to announce, no call to end, and the handoff still opens the owner's app.
 *
 * The bridge carries **no phone numbers and no message text**: [announceDial] gets the fixed
 * line, [window] is the PiP host, and the action itself is started by [LocalActions].
 */
object PhoneHandoff {

    /** What the live screen can do that the application context cannot. */
    interface Host {
        /** Is a Vox call running in this window right now? */
        fun callLive(): Boolean

        /**
         * Is an activity of ours resumed, or in picture-in-picture and visible? This is the
         * background-activity-launch gate: Android refuses a start from an app with no visible
         * window without throwing, so the handoffs ask this before they act (B2).
         */
        fun canStartActivity(): Boolean

        /**
         * Voice [line] through the being and run [after] once it is done — or once it is
         * clear that nothing will speak, so a missing TTS engine never blocks a dial.
         */
        fun announceDial(line: String, after: () -> Unit)

        /** The same teardown the on-screen hang-up runs. */
        fun endCall()

        /** The PiP-first launch funnel ([CallWindow]), or null when this window cannot float. */
        fun window(): CallWindow.Host?

        /** Language for spoken announcement. */
        fun lang(): String = "en"
    }

    @Volatile
    private var live: Host? = null

    /** Called by [MainActivity] when its window exists. One window at a time; last one wins. */
    fun attach(host: Host) {
        live = host
    }

    /** Called when that window is destroyed. A stale host is never left to answer for a new one. */
    fun detach(host: Host) {
        if (live === host) live = null
    }

    fun callLive(): Boolean = live?.callLive() ?: false

    /**
     * The one background-launch gate. No window means no visible activity, so the answer is
     * false: a handoff with no screen to start from is refused rather than silently dropped.
     */
    fun canStartActivity(): Boolean = live?.canStartActivity() ?: false

    /** Announce through the being; with no window, run [after] immediately. */
    fun announceDial(line: String, after: () -> Unit) {
        val host = live
        if (host == null) after() else host.announceDial(line, after)
    }

    fun endCall() {
        live?.endCall()
    }

    fun window(): CallWindow.Host? = live?.window()

    fun lang(): String = live?.lang() ?: "en"
}
