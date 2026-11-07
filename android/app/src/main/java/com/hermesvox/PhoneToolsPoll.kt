package com.hermesvox

/**
 * PhoneToolsPoll — the phone tools screen's one-second tick, and the rule for when it exists.
 *
 * **A tick is an attempt's clock, not the screen's.** It exists for exactly the things an attempt
 * can do by itself: the post-run grace can expire (*waiting* → *failed*), the phone's own handshake
 * can land, the silence line can age. None of that is true of a settled screen, and a tick there is
 * pure cost — which is what the owner saw: the tick was re-posted unconditionally for as long as
 * the screen was open, and the lines it ages made the column rebuild every few seconds, so the
 * permissions visibly refreshed while nothing was happening.
 *
 * [follow] is called with the step the screen is drawing, at paint, at a tick, and at a resume: a
 * tick is posted while the step is [PhoneBootstrap.Step.ADDING] or [PhoneBootstrap.Step.WAITING],
 * and [stop]ped the moment it settles. There is at most one posted tick at a time.
 *
 * **Injectable, so the rule is proved off-device.** [post] and [cancel] are the sheet's `Handler`
 * in the app and a recorded list in [PhoneToolsPollTest], which is how "no tick is scheduled in a
 * settled state" is a test rather than a comment.
 */
internal class PhoneToolsPoll(
    private val post: (delayMs: Long, what: Runnable) -> Unit,
    private val cancel: (what: Runnable) -> Unit,
    private val onTick: () -> Unit,
) {

    /** The tick that is posted right now, or null when none is. */
    private var tick: Runnable? = null

    /** Is a tick posted and neither run nor cancelled? False at rest, and at rest is the default. */
    val scheduled: Boolean get() = tick != null

    /**
     * Follow [step]: one tick a second while an attempt is in flight, and none once it settles.
     * Calling this again with a step that still needs a tick keeps the one already posted.
     */
    fun follow(step: PhoneBootstrap.Step) {
        if (ticks(step)) schedule() else stop()
    }

    /** Post the next tick, unless one is already posted. */
    fun schedule() {
        if (tick != null) return
        val what = Runnable {
            // Cleared first: a tick that runs is no longer posted, and the screen re-arms through
            // follow() only while the attempt is still in flight.
            tick = null
            onTick()
        }
        tick = what
        post(PERIOD_MS, what)
    }

    /** Cancel the posted tick, if any. Idempotent: a settled screen calls this freely. */
    fun stop() {
        val what = tick ?: return
        tick = null
        cancel(what)
    }

    companion object {
        /** One second: enough for an age to move, and the cadence the attempt's lines were read at. */
        const val PERIOD_MS = 1_000L

        /**
         * Does [step] need a tick at all? Only the two steps of an attempt in flight — the grace,
         * the handshake and the ages all live there. A settled screen is redrawn by an event.
         */
        fun ticks(step: PhoneBootstrap.Step): Boolean =
            step == PhoneBootstrap.Step.ADDING || step == PhoneBootstrap.Step.WAITING
    }
}
