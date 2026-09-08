package com.hermesvox

/**
 * ErFillers — the presence filler state machine (ER Phase 4, Miles rule #3),
 * pure JVM.
 *
 * A small inventory of non-verbal/semi-verbal fillers keyed to sentiment +
 * task type, driven by time-since-user-stopped and time-since-mind-requested.
 * HARD CAP: ≤1-2 fillers per 3s, no full sentences until the mind returns.
 * FAIL-SOFT: after 3-5s the neutral "thinking" becomes an in-character lag
 * acknowledgment — NOT a generic "thinking…".
 */
object ErFillers {

    /** The decision for this tick of the presence loop. */
    data class Out(val speak: String?, val state: State)

    enum class State { HOLDING, LAG_ACK, SILENT }

    // The inventory. Short, cuttable, no facts. Open-ended stems (Miles rule
    // #2) that truncate cleanly when the mind lands mid-filler.
    private val NEUTRAL = listOf("Hmm —", "Okay, let me see —", "Right, so —", "One sec —")
    private val WARM = listOf("Mm, let me think —", "Okay —", "Right —")
    private val LAG = listOf(
        "my mind's a bit slow right now, give me a sec",
        "still working on it — one more moment",
        "this one's taking a little longer",
    )

    const val FILLER_WINDOW_MS = 3000L     // the density-cap window
    const val MAX_FILLERS_PER_WINDOW = 2   // Miles: ≤1-2 per 3s
    const val LAG_AFTER_MS = 4000L         // fail-soft kicks in at 4s
    /** 0.6.7 Tier 0 (silence-first): NOTHING spoken before this much mind-work.
     *  The waiting-constellation motion carries the presence; silence is the
     *  natural human ack. (LAG_AFTER_MS and SILENCE_FIRST_MS coincide at 4s:
     *  under the ladder, the FIRST voice the soul uses is the fail-soft line.) */
    const val SILENCE_FIRST_MS = LAG_AFTER_MS

    /** One tick. Call on a ~1s cadence while the mind is working.
     *  nowMs / mindStartedAt / userStoppedAt: the caller's clock (injectable
     *  for tests). recentCount: fillers already emitted in the last window
     *  (the loop owns the tally). warm: the emotional tone from the classifier. */
    fun tick(
        nowMs: Long,
        mindStartedAt: Long,
        recentCount: Int,
        warm: Boolean,
        userGoneMs: Long,
        cap: Int = MAX_FILLERS_PER_WINDOW,
        /** 0.6.5: how many LAG lines have EVER been said this window (the loop's
         *  monotonic count, not the 3s trailing count). The lag line must be
         *  ONCE per mind-work window — it repeated every ~3s before (the 3s
         *  window let recentCount fall back to 0, re-arming the fail-soft). */
        lagSaidCount: Int = 0,
        /** 0.6.7 the presence ladder (Tier 0): silence-first for SHORT waits.
         *  Nothing is spoken before [SILENCE_FIRST_MS] of mind-work — the
         *  waiting-constellation motion IS the acknowledgment, and silence is
         *  the most natural filler a human gives. Tier 1 (nonverbal clips)
         *  takes over at the lag slot. */
        silenceFirstMs: Long = SILENCE_FIRST_MS,
    ): Out {
        val since = nowMs - mindStartedAt
        if (since < 900L) return Out(null, State.SILENT)      // the preamble beat
        if (cap <= 0) return Out(null, State.SILENT)          // 0.6.2: user slider "silent"
        // Tier 0: short waits are MOTION-ONLY. No spoken filler under the
        // silence-first window — the single biggest naturalness fix, and it
        // structurally removes the double-ack class.
        if (since < silenceFirstMs) return Out(null, State.SILENT)
        if (since >= LAG_AFTER_MS) {
            // Fail-soft: ONE in-character lag acknowledgment, then hold silent
            // (the waiting-constellation motion carries the presence from here).
            // 0.6.5: keyed on the MONOTONIC count — never re-arms inside a window.
            return if (lagSaidCount == 0 && recentCount < cap) {
                Out(LAG[(userGoneMs % LAG.size).toInt().coerceAtLeast(0)], State.LAG_ACK)
            } else Out(null, State.SILENT)
        }
        // Pre-lag: the neutral/warm fillers under the density cap.
        if (recentCount >= cap) return Out(null, State.SILENT)
        // Density is enforced by the caller's recentCount (the loop tallies the
        // trailing 3s); the window arithmetic above is the state marker only.
        val inv = if (warm) WARM else NEUTRAL
        val idx = ((userGoneMs / 1500L) % inv.size).toInt().coerceAtLeast(0)
        return Out(inv[idx], State.HOLDING)
    }

    /** Reset helper for the loop's tally: fillers emitted more than the window
     *  ago no longer count against the cap. */
    fun countRecent(times: List<Long>, nowMs: Long): Int =
        times.count { nowMs - it < FILLER_WINDOW_MS }
}
