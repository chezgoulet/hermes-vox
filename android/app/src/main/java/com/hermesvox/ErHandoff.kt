package com.hermesvox

/**
 * ErHandoff — did the mind actually get this turn? Pure JVM, so the answer is testable
 * off-device and the field log can be read without a device.
 *
 * The 2026-11 field report was "ER swallowed my question": the voice answered something
 * plausible and Hermes never spoke, with no error anywhere the caller could see. The
 * decision layer is a *model*, so the client cannot prove the model escalated — but it can
 * prove, per turn, whether the mind received the turn and whether the caller ever heard it.
 * That is what this object writes:
 *
 *  - [EVENT] `er-handoff` — exactly one per turn. `ok=true` means the gateway accepted the turn
 *    (a stream id came back); `ok=false` names the path that dropped it before submit. This is
 *    the reporter's own suggestion: "did it get through?" answered from a log line.
 *  - [SWALLOW] `er-swallow` — the turn ended with neither voice answering: the soul answered and
 *    the mind either never saw the turn ([MIND_NOT_SUBMITTED]) or saw it and was told the voice
 *    had answered, so it replied with the skip token ([MIND_SKIPPED]). Not a failure — a
 *    measurable event. The rate of these against `event=er-soul decision=answer` is the number
 *    that decides whether the escalate rule needs a code guard rather than the directive.
 *
 * Everything here is formatting and one branch; it has no Android imports and no state.
 */
object ErHandoff {

    /** The receipt. One per turn, always. */
    const val EVENT = "er-handoff"

    /** The soul answered; the caller heard no mind. */
    const val SWALLOW = "er-swallow"

    // ---- ok=true: the mind received the turn ----

    /** A voice turn was submitted (the ER path). */
    const val REASON_VOICE_TURN = "voice-turn"
    /** A typed turn was submitted (no soul decision). */
    const val REASON_TEXT_TURN = "text-turn"

    // ---- ok=false: it did not ----

    /** A previous turn was still in flight, so this one never reached the mind. */
    const val REASON_SUPPRESSED = "suppressed-inflight"
    /** A barge/stop landed between the soul's wait and the submit. */
    const val REASON_CANCELLED = "cancelled-before-submit"
    /** The executor was stopped (hangup/teardown) before the turn could run. */
    const val REASON_STOPPED = "stopped"
    /** The submit returned no stream id, or the submit itself threw. */
    const val REASON_NO_STREAM = "no-stream"

    // ---- why the caller heard no mind ----

    /** The mind never saw the turn at all. */
    const val MIND_NOT_SUBMITTED = "not-submitted"
    /** The mind saw it, was told the voice already answered, and replied with the skip token. */
    const val MIND_SKIPPED = "skipped"

    /**
     * The receipt line.
     *
     * [soul] is the soul's own decision for the turn (`answer` | `beat` | `escalate` |
     * `nothing`), or `-` when the soul was not consulted (presence off, a typed turn, or a turn
     * that ended before the decision landed). [askChars] is the caller's text LENGTH — never the
     * text itself, so the line is safe to keep with transcript logging off.
     */
    fun receipt(gen: Long, ok: Boolean, reason: String, soul: String, askChars: Int): String =
        "event=$EVENT gen=$gen ok=$ok reason=$reason soul=$soul askChars=$askChars"

    /**
     * The swallow line, or null when there is nothing to report.
     *
     * A swallow is narrow on purpose: the soul answered ([soulAnswered]) AND the caller heard no
     * mind — either the mind never saw the turn, or it saw it and skipped. A soul answer the mind
     * then overrode is NOT a swallow (the caller got an answer); it is visible as `er-soul
     * decision=answer` with no `er-swallow` beside it, and the two counts together are the honest
     * denominator/numerator for "how often does the soul answer for the mind".
     *
     * [cls] is [ErIntent]'s safety class ([ErIntent.Class.MIND] for every turn the soul is asked
     * about — [ErIntent.Class.BACKCHANNEL] never reaches the soul). It is carried anyway: the day
     * a finer class exists, the field already has the column, and today `cls=MIND` is itself the
     * evidence that a coarse class cannot carry this question.
     */
    fun swallow(
        gen: Long,
        soulAnswered: Boolean,
        submitted: Boolean,
        mindWentSilent: Boolean,
        cls: ErIntent.Class,
        askChars: Int,
        soulChars: Int,
    ): String? {
        if (!soulAnswered) return null
        val mind = when {
            !submitted -> MIND_NOT_SUBMITTED
            mindWentSilent -> MIND_SKIPPED
            else -> return null
        }
        return "event=$SWALLOW gen=$gen mind=$mind cls=${cls.name.lowercase()} " +
            "askChars=$askChars soulChars=$soulChars"
    }
}
