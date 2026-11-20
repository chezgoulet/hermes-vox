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
    const val LINE_FR = "Pardon — je n'ai pas reçu de réponse. Voulez-vous que je réessaie ?"

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
    /** The caller answered the wait card with "stop". */
    const val REASON_WAIT_STOP = "wait-stop"
    /** The wait card's window closed without an answer. */
    const val REASON_WAIT_NO_ANSWER = "wait-no-answer"
    /** The caller's extension ran out with the stream still silent. */
    const val REASON_WAIT_EXTENDED = "wait-extended"

    /** The app gave up waiting, and nothing had been started that it knows of. */
    const val LINE_GAVE_UP = "That took too long, so I stopped waiting. Want to ask again?"
    const val LINE_GAVE_UP_FR = "C'était trop long, j'ai donc arrêté d'attendre. Voulez-vous reposer la question ?"

    /** The three wait-card endings are already one plain sentence each; no retry pitch. */
    fun waitLine(reason: String, lang: String = "en"): String? = when (reason) {
        REASON_WAIT_STOP -> if (lang == "fr") WaitPrompt.LINE_STOPPED_FR else WaitPrompt.LINE_STOPPED
        REASON_WAIT_NO_ANSWER -> if (lang == "fr") WaitPrompt.LINE_NO_ANSWER_FR else WaitPrompt.LINE_NO_ANSWER
        REASON_WAIT_EXTENDED -> if (lang == "fr") WaitPrompt.LINE_EXTENDED_FR else WaitPrompt.LINE_EXTENDED
        else -> null
    }

    /**
     * The turn ended without an answer AFTER the mind had started work (a tool call was seen).
     * The guardrail: a refusal must never claim nothing happened when something did, and
     * "try again" could do it twice — so the line says the outcome is unknown and offers the
     * safe next step.
     */
    const val LINE_UNCERTAIN = "I didn't get an answer back, and it had already started on it, " +
        "so I can't say what got done. Ask me to check."
    const val LINE_UNCERTAIN_FR = "Je n'ai pas reçu de réponse et le travail avait déjà commencé, " +
        "je ne peux donc pas confirmer ce qui a été fait. Demandez-moi de vérifier."

    /**
     * The one line for this ending. [workStarted]: the mind started a tool this turn, so
     * something may have happened. A timeout or a silent stream says the app stopped waiting —
     * not that the mind failed — because that is what the app knows.
     */
    fun line(reason: String, workStarted: Boolean, lang: String = "en"): String = waitLine(reason, lang) ?: when {
        // A wait-card ending says only what the app did ("stopped waiting"). That is true
        // whatever the mind had started, so the unknown-outcome guard does not override it.
        workStarted -> if (lang == "fr") LINE_UNCERTAIN_FR else LINE_UNCERTAIN
        reason == REASON_TIMEOUT || reason == REASON_SILENT -> if (lang == "fr") LINE_GAVE_UP_FR else LINE_GAVE_UP
        else -> if (lang == "fr") LINE_FR else LINE
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
