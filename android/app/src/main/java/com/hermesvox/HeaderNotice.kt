package com.hermesvox

/**
 * One blue notice at a time, on a clock.
 *
 * The rules the second phone pass asked for, as state rather than as a timer in the
 * Activity:
 *
 *  * a switch arms a notice once; a second arm while one is showing is refused, so
 *    notices never stack;
 *  * the green action line wins: arming is refused while an action is on the line, and
 *    the caller retires the notice with [clear] the moment an action takes the pill;
 *  * a notice lives [durationMs] and is gone after it ([showing]/[text]/[expired]).
 *
 * Pure JVM: the five-to-six-second rule is a test, not a device measurement.
 */
class HeaderNotice(private val durationMs: Long = Header.NOTICE_MS) {

    private var text: String? = null
    private var startedAt: Long = 0L

    /** Arm [text] at [nowMs]. False when it was dropped: an action is running, the words
     *  are empty, or another notice is still showing. */
    fun arm(text: String, nowMs: Long, actionRunning: Boolean = false): Boolean {
        val t = Header.switchNotice(text)
        if (t.isEmpty() || actionRunning || showing(nowMs)) return false
        this.text = t
        startedAt = nowMs
        return true
    }

    /** Whether a notice is on the line at [nowMs]. */
    fun showing(nowMs: Long): Boolean = text != null && nowMs - startedAt < durationMs

    /** The notice's words at [nowMs], or null when there is nothing to say. */
    fun text(nowMs: Long): String? = if (showing(nowMs)) text else null

    /** True once a notice has run its course (the caller repaints the resting line). */
    fun expired(nowMs: Long): Boolean = text != null && !showing(nowMs)

    /** Retire the notice now — an action is taking the pill. */
    fun clear() { text = null }

    /** The ms left on the current notice at [nowMs]; 0 when none is showing. */
    fun remaining(nowMs: Long): Long =
        if (showing(nowMs)) durationMs - (nowMs - startedAt) else 0L
}
