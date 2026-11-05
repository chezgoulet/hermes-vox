package com.hermesvox

/**
 * TurnRepair — F9 of the 2026-11-04 Presence review: **in a voice call, every turn ends audibly.**
 *
 * The field shape: an empty mind reply (a run that ended after tool calls, or an answer that went
 * out only as commentary) ended with `onState("idle")` — no sound, no words. A failure played a
 * two-tone cue and set a pill nobody driving can read. Either way the caller was left to work out
 * whether they had been heard, which is exactly the "I don't know if it processed that" in the
 * report.
 *
 * This is the one line said instead, and the rule for when to say it:
 *
 *  - **not** when the caller cut the turn — a barge, a hush or a hangup means they stopped it on
 *    purpose, and an apology for that is noise on top of the interruption;
 *  - **not** for a turn that is no longer current (a newer turn owns the floor);
 *  - **not** when the phone is not listening (the call is over) or speech is off;
 *  - otherwise: say it, and offer the one action the caller can take.
 *
 * A `suppressed-inflight` turn is deliberately NOT repaired: since F3 it is queued and answered,
 * and apologising for a turn that is about to be taken would talk over it.
 *
 * Pure JVM, unit-tested in TurnRepairTest.
 */
object TurnRepair {

    /** What a failed or empty turn says. Short, true, and actionable. */
    const val LINE = "Sorry — I didn't get an answer back on that. Want me to try again?"

    /** The turn ended with nothing to say (the gateway returned no final text). */
    const val REASON_EMPTY = "empty-reply"
    /** The stream was lost (connection, gateway restart, poll abort). */
    const val REASON_LOST = "stream-lost"
    /** The turn ended on a gateway error. */
    const val REASON_ERROR = "reply-error"
    /** The agent budget ran out ([StreamPollGate]): the app stopped waiting. */
    const val REASON_TIMEOUT = "turn-timeout"
    /** The stream sent nothing for [TurnWait.SILENT_STREAM_MS]: the app stopped waiting. */
    const val REASON_SILENT = "stream-silent"

    /** The app gave up waiting, and nothing had been started that it knows of. */
    const val LINE_GAVE_UP = "That took too long, so I stopped waiting. Want to ask again?"

    /**
     * The turn ended without an answer AFTER the mind had started work (a tool call was seen).
     * The guardrail: a refusal must never claim nothing happened when something did, and
     * "try again" could do it twice — so the line says the outcome is unknown and offers the
     * safe next step.
     */
    const val LINE_UNCERTAIN = "I didn't get an answer back, and it had already started on it, " +
        "so I can't say what got done. Ask me to check."

    /**
     * The one line for this ending. [workStarted]: the mind started a tool this turn, so
     * something may have happened. A timeout or a silent stream says the app stopped waiting —
     * not that the mind failed — because that is what the app knows.
     */
    fun line(reason: String, workStarted: Boolean): String = when {
        workStarted -> LINE_UNCERTAIN
        reason == REASON_TIMEOUT || reason == REASON_SILENT -> LINE_GAVE_UP
        else -> LINE
    }

    /** Should [LINE] be spoken for this turn's ending? */
    fun speaks(
        cancelled: Boolean,
        stopped: Boolean,
        listening: Boolean,
        voiceOn: Boolean,
        sameTurn: Boolean,
    ): Boolean = sameTurn && listening && !stopped && !cancelled && voiceOn
}
