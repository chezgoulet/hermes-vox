package com.hermesvox

/**
 * StreamDrain — how a streamed reply's track is played out and let go (#538, the reporter's
 * log of 2026-11-10). Pure: no Android types, so the decision is unit-proven.
 *
 * The field defect. #517 grew the persistent stream track from 0.25 s to 4 s
 * (`STREAM_BUFFER_SECS`). A MODE_STREAM AudioTrack starts rendering only when the written data
 * reaches its start threshold, and the platform's default threshold is the whole buffer. A
 * reply with less than 4 s of audio therefore never fills it: the track sits in "filling", the
 * playback head stays at 0, and the old drain loop (`head >= written - 1`, 120 s ceiling) waited
 * the full two minutes. Every one of those minutes held the turn gate: the screen said "The
 * entity is working…", the mic did not take a turn, barge-in had nothing to hear, and the gateway
 * — which had answered in two seconds — was idle. In the reporter's log (0.9.1 block, counted by
 * `scripts/field-log-turns.py --since-field speechEndToRenderDone`), 12 replies of 14–50
 * characters ran to the ceiling (render done 126.8–128.8 s), and 25 of the 44 replies estimated
 * under 4 s were held more than 15 s after their text arrived — the ceiling, or the caller giving
 * up. Whether longer replies drained promptly is not measurable from that log: it predates
 * `event=tts-drain` and records no audio length for a streamed reply. (An earlier version of this
 * comment said "28 of the 29 … every reply of 4 s or more drained in under 6 s"; neither count
 * reproduces.)
 *
 * Two fixes, two places:
 * - the cause: [startThresholdFrames] — on API 31+ the track starts after a quarter-second,
 *   whatever its capacity (the capacity stays 4 s: it is the synthesis worker's headroom);
 * - the backstop, every API level: [step]. A head that has not moved for [STUCK_MS] while
 *   audio is still owed gets [Step.FORCE] — the caller `stop()`s the track, which in streaming
 *   mode plays out what was written and then stops — and every drain is bounded by the audio
 *   actually owed plus slack, not by a flat two minutes.
 */
internal class StreamDrain(
    private val written: Long,
    private val sampleRate: Int,
    startMs: Long,
    startHead: Long,
) {
    enum class Step { WAIT, FORCE, DONE }

    /** The witness: how the drain ended (`event=tts-drain result=`). */
    enum class Result(val label: String) {
        PLAYED("played"),           // the head reached the end on its own
        FORCED("forced"),           // the head was stuck; stop() played it out
        TIMEOUT("timeout"),         // the head moved but never reached the end in time
        NOT_PLAYING("not-playing"), // someone else paused/stopped the track first
    }

    companion object {
        /** A head that has not advanced for this long, with audio still owed, is stuck. Head
         *  updates arrive every HAL period (tens of ms; a Bluetooth route can be ~100–200 ms). */
        const val STUCK_MS = 400L
        /** Output latency allowed past the owed audio while the head is still moving. */
        const val SLACK_MS = 3_000L
        /** After a forced play-out the head may reset instead of reaching the end, so the clock
         *  decides: the owed audio plus this much route latency. */
        const val FORCED_SLACK_MS = 750L
        /** The start threshold the 0.25 s track had in effect before #517. */
        const val START_SECS_DIVISOR = 4

        fun audioMs(samples: Long, sampleRate: Int): Long =
            if (sampleRate <= 0 || samples <= 0L) 0L else samples * 1000L / sampleRate

        /** Frames that must be written before a streaming track starts: a quarter-second (or
         *  the platform minimum, if larger), never more than the track holds. */
        fun startThresholdFrames(sampleRate: Int, minBufFrames: Int, capacityFrames: Int): Int {
            val want = maxOf(sampleRate / START_SECS_DIVISOR, minBufFrames, 1)
            return want.coerceAtMost(maxOf(capacityFrames, 1))
        }
    }

    var result: Result? = null
        private set
    var forced = false
        private set

    private var lastHead = startHead
    private var lastMoveAt = startMs
    private var deadline = startMs + audioMs(written - startHead, sampleRate) + SLACK_MS

    /** One poll of the track. [head] is the playback head in frames (unsigned), [playing] whether
     *  the track still reports PLAYSTATE_PLAYING. */
    fun step(nowMs: Long, head: Long, playing: Boolean): Step {
        if (head >= written - 1) { result = if (forced) Result.FORCED else Result.PLAYED; return Step.DONE }
        if (forced) {
            // A streaming track that finished its play-out after stop() may report 0 again.
            if (head < lastHead || nowMs >= deadline) { result = Result.FORCED; return Step.DONE }
            if (head > lastHead) lastHead = head
            return Step.WAIT
        }
        if (head > lastHead) { lastHead = head; lastMoveAt = nowMs }
        if (!playing) { result = Result.NOT_PLAYING; return Step.DONE }
        if (nowMs - lastMoveAt >= STUCK_MS) {
            forced = true
            deadline = nowMs + audioMs(written - head, sampleRate) + FORCED_SLACK_MS
            return Step.FORCE
        }
        if (nowMs >= deadline) { result = Result.TIMEOUT; return Step.DONE }
        return Step.WAIT
    }
}
