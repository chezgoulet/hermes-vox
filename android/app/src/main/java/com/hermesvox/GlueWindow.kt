package com.hermesvox

/**
 * GlueWindow — "is presence audio playing, or did it just stop?" for the capture loop.
 *
 * 0.9.1 field, Pixel 9: the ER soul opens the call with its own greeting, which plays
 * before any turn exists. The half-duplex gate (`turnDone`) is awaited *between* turns,
 * so nothing had closed the capture loop and it transcribed the being's greeting as the
 * caller's first utterance. The capture loop now drops frames in this window.
 *
 * Split out as a pure object because a guard that gets this wrong makes the microphone
 * silently deaf — in one direction it transcribes the entity to itself, in the other it
 * never opens the mic at all. Both are invisible without a test.
 *
 * The window is: true while glue is playing, and true for [skipMs] after it stops (the
 * tail of the audio still in the room). [skipMs] <= 0 disables the tail, which is the
 * user's `er_echo_skip_ms` = 0 setting.
 *
 * The tail is measured from the first read that OBSERVES glue stopped, not from the
 * instant it stopped — the capture loop calls this on every read (~64 ms), so in use
 * that is within one frame. If the loop is not calling (a filler during a turn), no
 * window is opened at all, which is correct: the loop resumes after the turn with an
 * empty pre-roll.
 */
internal class GlueWindow {

    private var wasSpeaking = false
    private var endedAt = 0L

    fun inWindow(glueSpeaking: Boolean, nowMs: Long, skipMs: Long): Boolean {
        if (glueSpeaking) {
            wasSpeaking = true
            endedAt = 0L
            return true
        }
        if (wasSpeaking) {
            wasSpeaking = false
            endedAt = nowMs
        }
        return skipMs > 0L && endedAt != 0L && nowMs - endedAt <= skipMs
    }
}
