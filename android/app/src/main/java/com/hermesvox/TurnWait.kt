package com.hermesvox

/**
 * TurnWait — what a spoken turn does while the mind has not started answering, and when it
 * stops waiting (naturalness pass, 2026-11-05). Pure JVM; the poll loop asks, the controller
 * speaks.
 *
 * Measured on a Pixel 7 against the real gateway: the provider's own tail is 3 s, 21.5 s,
 * 86 s for one trivial request (the 86 came back "overloaded on all three attempts"), and a
 * stream can stay open while sending nothing at all. Before this, a stream like that had no
 * failure path short of the 120 s agent budget — no poll errors to count, so nothing aborted —
 * and with Presence off the caller heard a soft ping every 6 s and nothing else.
 *
 * The registers, from the end of the caller's turn (Presence off; with Presence speaking, its
 * own beat, lag line and cadence own the wait and this says nothing):
 *
 *     0 s     the THINKING cue (the existing state cue: "I heard you")
 *     6 s+    the soft PING, every 6 s (the existing ambient cue: "still alive")
 *     15 s    [LINE_FIRST]  — one short, true sentence
 *     40 s    [LINE_SECOND] — one more, and that is the cap ([MAX_CHECK_INS])
 *     60 s of nothing from the stream (no event of any kind, no tool running, no card up):
 *             ask the caller instead of abandoning ([WaitPrompt]): the being says
 *             "This is taking a while — keep waiting, or stop?" and the same question is a
 *             card titled STILL WORKING. Keep waiting extends the turn once; stop, or the
 *             card's ~10 s window closing with no answer, abandons it.
 *     120 s   the agent budget (StreamPollGate) — stop waiting, say so
 *
 * Over-filling is worse than silence, so the lines are few, capped, and never spoken over the
 * caller: a check-in waits until the caller has been quiet [CALLER_QUIET_MS], and a caller who
 * speaks during the wait barges the turn anyway.
 */
object TurnWait {

    /** The first spoken check-in, if the reply has not started. */
    const val CHECK_IN_FIRST_MS = 15_000L

    /** The second (and last) check-in. */
    const val CHECK_IN_SECOND_MS = 40_000L

    const val MAX_CHECK_INS = 2

    /** A check-in is never spoken within this long of the caller's own voice. */
    const val CALLER_QUIET_MS = 1_500L

    /**
     * A stream that has sent no event at all for this long — while no tool is running and no
     * approval is waiting on the caller — is treated as dead. The longest healthy first-event
     * gap in the device runs is the provider retry tail (21.5 s); 60 s is well past that, and
     * leaves the 40 s check-in a real promise ("still waiting") rather than a line spoken five
     * seconds before giving up. A tool
     * that is running is exempt: a 76 s tool call sends nothing until it finishes and is not a
     * stall (PresenceCadence's field finding).
     */
    const val SILENT_STREAM_MS = 60_000L

    const val LINE_FIRST = "Still waiting on it."
    const val LINE_SECOND = "This one's taking a while. I'm still waiting."

    /**
     * The check-in to speak now, or null.
     *
     * @param waitedMs     time since the turn was submitted
     * @param said         check-ins already spoken this turn
     * @param replyStarted the mind's first text has arrived (the reply owns the floor)
     * @param presenceSpeaks Presence is running and voicing its own wait lines
     * @param approvalWaiting a card is up: the wait is the caller's, not the mind's
     * @param callerQuietMs time since the caller's voice was last heard
     */
    fun checkIn(
        waitedMs: Long,
        said: Int,
        replyStarted: Boolean,
        presenceSpeaks: Boolean,
        approvalWaiting: Boolean,
        callerQuietMs: Long,
    ): String? {
        if (replyStarted || presenceSpeaks || approvalWaiting) return null
        if (said >= MAX_CHECK_INS) return null
        if (callerQuietMs < CALLER_QUIET_MS) return null
        return when {
            said == 0 && waitedMs >= CHECK_IN_FIRST_MS -> LINE_FIRST
            said == 1 && waitedMs >= CHECK_IN_SECOND_MS -> LINE_SECOND
            else -> null
        }
    }

    /** Has the stream been silent long enough to stop waiting on it? */
    fun silentStreamExpired(idleMs: Long, toolRunning: Boolean, approvalWaiting: Boolean): Boolean =
        !toolRunning && !approvalWaiting && idleMs >= SILENT_STREAM_MS
}
