package com.hermesvox

/**
 * ErBargeGate — the semantic barge gate (ER Phase 5), pure JVM.
 *
 * Two-stage "fire-and-hold": the PHYSICAL barge (audio level) always cuts the
 * P3 filler instantly — the user may always interrupt the soul's presence —
 * but whether it also CANCELS THE MIND is a SEMANTIC decision:
 *   GENUINE BARGE ("stop", "wait", "actually…", "never mind") → cancel the
 *   mind; the utterance becomes the new context.
 *   BACKCHANNEL ("take your time", "okay", "go on") → do NOT cancel the mind;
 *   the soul acknowledges and keeps holding while the mind continues.
 *
 * SAFETY ASYMMETRY (sacrosanct): default to NOT cancelling the mind on
 * ambiguity. A missed cancel is cheap (the user repeats); a false cancel
 * regenerates 15s of work. The filler ALWAYS cuts either way.
 *
 * The utterance text arrives from the partial-STT pipeline already running —
 * no round-trip to the cloud (Miles: backchannel must be local/low-latency).
 */
object ErBargeGate {

    enum class Verdict { CANCEL_MIND, HOLD_MIND }

    /** The decision for a user utterance that interrupted a live mind turn.
     *  hasUtterance=false (the barge fired but no stable text yet) → HOLD —
     *  we never cancel on silence (the asymmetry). */
    fun decide(utterance: String?): Verdict {
        if (utterance.isNullOrBlank()) return Verdict.HOLD_MIND
        return if (ErIntent.isGenuineBarge(utterance)) Verdict.CANCEL_MIND else Verdict.HOLD_MIND
    }
}
