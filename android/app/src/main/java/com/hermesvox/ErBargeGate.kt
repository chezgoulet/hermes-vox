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

    /**
     * F3 (2026-11-04 review): a HOLD that has WORDS the caller must not lose.
     *
     * A hold says "keep working on the current turn" — it never said "and forget what I just
     * said", which is what the client did with it. Two kinds of utterance reach HOLD: the
     * patient caller ("mm-hm", "take your time"), which is *meant* to be absorbed, and a
     * re-ask, a correction or a follow-up ("what about the second one?"), which is a turn the
     * caller is waiting for. Only the second is carried: an utterance with content — one the
     * intent classifier does not read as a backchannel — is queued as the next turn
     * ([PendingTurn]) instead of being discarded.
     *
     * Silence carries nothing: there is nothing to answer.
     */
    fun carries(utterance: String?): Boolean {
        if (utterance.isNullOrBlank()) return false
        return ErIntent.classify(utterance).route == ErIntent.Route.ACK_AND_YIELD
    }
}
