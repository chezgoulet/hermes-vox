package com.hermesvox

/**
 * ErHandoff — did the mind actually get this turn? Pure JVM, so the answer is testable
 * off-device and the field log can be read without a device.
 *
 * The 2026-11 field report was "ER swallowed my question": the voice answered something
 * plausible and Hermes never spoke, with no error anywhere the caller could see. D30 removed the
 * mechanism (the soul no longer answers turns and the mind is never offered a skip), and this
 * object keeps the receipt that proves it per turn:
 *
 *  - [EVENT] `er-handoff` — exactly one per turn. `ok=true` means the gateway accepted the turn
 *    (a stream id came back); `ok=false` names the path that dropped it before submit.
 *
 * The `er-swallow` line and its `mind=skipped|not-submitted` vocabulary went with the skip offer
 * (D30): with the soul never answering a turn there is nothing for it to report.
 *
 * Everything here is formatting; it has no Android imports and no state.
 */
object ErHandoff {

    /** The receipt. One per turn, always. */
    const val EVENT = "er-handoff"

    // ---- ok=true: the mind received the turn ----

    /** A voice turn was submitted (the ER path). */
    const val REASON_VOICE_TURN = "voice-turn"
    /** A typed turn was submitted (no soul decision). */
    const val REASON_TEXT_TURN = "text-turn"

    // ---- ok=false: it did not ----

    /** A previous turn was still in flight, so this one never reached the mind. */
    const val REASON_SUPPRESSED = "suppressed-inflight"
    /** A barge/stop landed between the turn start and the submit. */
    const val REASON_CANCELLED = "cancelled-before-submit"
    /** The executor was stopped (hangup/teardown) before the turn could run. */
    const val REASON_STOPPED = "stopped"
    /** The submit returned no stream id, or the submit itself threw. */
    const val REASON_NO_STREAM = "no-stream"
    /** The phone took the utterance itself (a local action, #421); the mind was never asked, by
     *  design. Not a swallow — `event=local-action` says what was done. */
    const val REASON_LOCAL_ACTION = "local-action"

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
}
