package com.hermesvox

/**
 * WorkingRender — one mid-run line's render, started with the run instead of when the line is due.
 *
 * The mid-run slot cannot open before [ErFillers.LAG_AFTER_MS], and with a Conversational
 * register the first soul line is due one interval after that (22 s by default). The render used
 * to be requested *then*, so its ~2.5 s sat between "due" and "delivered" and the mind's first
 * text could land inside that window — the `late` drop (see
 * docs/PRESENCE-LATE-DIAGNOSIS.md). A render requested while the run is still working is
 * already in hand when the slot opens, so the line speaks at once and the window is gone.
 *
 * Pure JVM: the host owns the render call, the clock and the delivery; this owns only the
 * ordering, so both orderings can be tested off-device.
 */
class WorkingRender(
    /** The turn generation this render belongs to; a newer turn discards it. */
    val gen: Long,
    /** When the render was requested (the run's working state becoming known). */
    val requestedAtMs: Long,
    /**
     * #218: the caller's wait that this render's directive was composed against, in seconds — the
     * one number the guard may accept in the line it returns. The voucher travels with the render
     * because the line says the wait as it was when the line was written; delivery must not
     * re-measure it, or an honest line would be refused for a few seconds' drift. Null when no
     * wait was known, which vouches for nothing (the old rule).
     */
    val vouchedSeconds: Long? = null,
) {
    private val lock = Any()
    private var outcome: ErSoulTurn.Outcome? = null
    private var doneAtMs = 0L

    /** The render came back. The first result wins, so a duplicate callback cannot overwrite it. */
    fun settle(o: ErSoulTurn.Outcome, nowMs: Long) {
        synchronized(lock) {
            if (outcome == null) { outcome = o; doneAtMs = nowMs }
        }
    }

    /** The parsed render, or null while it is still in flight. */
    fun take(): ErSoulTurn.Outcome? = synchronized(lock) { outcome }

    /**
     * Did the render yield something the lane can use? A blank render means the request did not
     * really run (the render rails drop a narration while another render is in flight, or
     * inside the spacing floor), so the slot must render again rather than read that as the
     * soul choosing silence. An escalate token *is* a decision — silence — and is kept.
     */
    fun usable(): Boolean = synchronized(lock) {
        outcome != null && outcome != ErSoulTurn.Outcome.Nothing
    }

    /** When the render returned, or 0 while it is still in flight. */
    fun doneAtMs(): Long = synchronized(lock) { doneAtMs }

    companion object {
        /**
         * The `late` rule as a time rather than a latch: a working line may not speak once the
         * mind's first text has landed. [firstTextAtMs] is 0 until it lands, so a run with no
         * reply text is never late for this reason. [deliveredAtMs] is when the line would
         * speak. This is the same rejection the boolean latch made — not a relaxation of it.
         */
        fun late(firstTextAtMs: Long, deliveredAtMs: Long): Boolean =
            firstTextAtMs > 0L && deliveredAtMs >= firstTextAtMs
    }
}
