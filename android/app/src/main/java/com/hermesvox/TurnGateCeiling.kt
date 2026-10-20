package com.hermesvox

/**
 * TurnGateCeiling — when the single-capture barge drain may give up on a turn gate
 * that never released (0.9.1).
 *
 * The drain used to exit at a flat 60 s from GATE CLOSE. A long voice reply outlives
 * that while perfectly healthy: in the 0.9.0 field log (2026-10-20) gen=2 closed its
 * gate at 00:17:30.483, generated for 40 s, and spoke a 2,077-char reply until ~00:20:31;
 * the drain quit at 00:18:30.548 — 60.07 s after the close, ~20 s into the speech —
 * and the same happened to gen=3 (00:20:31.770 -> segment open 00:21:31.8). From
 * that moment, for the rest of the reply:
 *
 *  - nothing checked for barge-in ("it keeps talking even when I'm talking"), and
 *  - the capture loop reopened under the playing reply, its utterance clock already
 *    running, so the user's next words were closed at their first pause (see
 *    [EndpointRule.utteranceMs]: "and not", "okay i just want to point out in the").
 *
 * No line was logged: the gate-timeout warn also needs 30 s of no activity, and every
 * drain read is activity.
 *
 * The rule now: the ceiling counts from the last moment the turn was ALIVE (a stream
 * in flight, the reply speaking, or the streaming voice worker running), so it still
 * frees a genuinely wedged gate [IDLE_MS] after the turn stopped doing anything, and
 * never cuts a live one. [HARD_MS] bounds a turn that claims to be alive forever.
 */
internal object TurnGateCeiling {

    /** The old 60 s ceiling, now measured from the turn's last sign of life. */
    const val IDLE_MS = 60_000L

    /** Absolute backstop from gate close, for a liveness flag that never clears.
     *  Well past the stream budget (StreamPollGate, 120 s) plus a long spoken reply. */
    const val HARD_MS = 600_000L

    fun expired(nowMs: Long, gateClosedAtMs: Long, lastAliveAtMs: Long): Boolean =
        nowMs - lastAliveAtMs >= IDLE_MS || nowMs - gateClosedAtMs >= HARD_MS
}
