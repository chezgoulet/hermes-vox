package com.hermesvox

/**
 * ErFillers — the presence filler state machine (ER Phase 4, Miles rule #3),
 * pure JVM.
 *
 * The ladder, as agreed (0.8/M2 — the middle rung restored):
 *  1. **< 900 ms** — silence. The mind hasn't started; motion is the ack.
 *  2. **900 ms – 4 s** — ONE nonverbal PREAMBLE cue (a recorded clip, no words).
 *  3. **≥ 4 s** — the fail-soft LAG acknowledgment, once per window, then silence.
 *
 * HARD RULE (sacrosanct): no WORDS before 4 s. Piper is a sentence-prosody engine
 * and a two-character interjection is its worst case — the field rejected the
 * spoken "Mm?", so the middle rung is a recorded clip instead and [State.PREAMBLE]
 * deliberately carries `speak = null`.
 *
 * History this encodes: 0.6.7 "Tier 0" set SILENCE_FIRST_MS == LAG_AFTER_MS, which
 * made the sentence rung unreachable — the inventory was dead code and, on a
 * healthy gateway, the soul never made a sound at all (no perceptible difference
 * from Realtime). The dead sentence inventory and its vestigial `warm` parameter
 * are deleted; the middle rung is the nonverbal cue.
 */
object ErFillers {

    /** The decision for this tick of the presence loop. */
    data class Out(val speak: String?, val state: State)

    /** PREAMBLE = offer the nonverbal cue (no text). LAG_ACK = the fail-soft line. */
    enum class State { PREAMBLE, LAG_ACK, SILENT }

    /** The fail-soft lines — the ONLY spoken inventory left. Short, cuttable, no
     *  facts, open-ended stems that truncate cleanly when the mind lands. */
    private val LAG = listOf(
        "my mind's a bit slow right now, give me a sec",
        "still working on it — one more moment",
        "this one's taking a little longer",
    )

    const val FILLER_WINDOW_MS = 3000L     // the density-cap window
    const val MAX_FILLERS_PER_WINDOW = 2   // Miles: ≤1-2 per 3s
    const val LAG_AFTER_MS = 4000L         // fail-soft kicks in at 4s
    /** 0.8/M2: the preamble cue slot opens here — the earliest the soul may make
     *  ANY sound. Below this the mind may not have started and motion is the ack. */
    const val PREAMBLE_MS = 900L
    /** Not before this many ms of mind-work is any WORD spoken (see the class doc):
     *  gates spoken fillers, and is the upper bound of the preamble slot. */
    const val SILENCE_FIRST_MS = LAG_AFTER_MS

    /** One tick. Call on a ~1s cadence while the mind is working.
     *  nowMs / mindStartedAt: the caller's clock (injectable for tests).
     *  recentCount: cues already emitted in the last window (the loop's tally).
     *  userGoneMs: picks the lag line by rotation. */
    fun tick(
        nowMs: Long,
        mindStartedAt: Long,
        recentCount: Int,
        userGoneMs: Long,
        cap: Int = MAX_FILLERS_PER_WINDOW,
        /** 0.6.5: how many LAG lines have EVER been said this window (the loop's
         *  monotonic count, not the 3s trailing count). The lag line must be
         *  ONCE per mind-work window — it repeated every ~3s before (the 3s
         *  window let recentCount fall back to 0, re-arming the fail-soft). */
        lagSaidCount: Int = 0,
        /** 0.8/M2: how many times the preamble cue has been OFFERED this window.
         *  At most ONCE — a repeated "mm" is the chatty failure the field rejected. */
        preambleSaid: Int = 0,
        silenceFirstMs: Long = SILENCE_FIRST_MS,
    ): Out {
        val since = nowMs - mindStartedAt
        if (since < PREAMBLE_MS) return Out(null, State.SILENT)   // too early
        if (cap <= 0) return Out(null, State.SILENT)              // 0.6.2: user slider "silent"
        if (since >= LAG_AFTER_MS) {
            // Fail-soft: ONE in-character lag acknowledgment, then hold silent.
            // Keyed on the MONOTONIC count — never re-arms inside a window.
            return if (lagSaidCount == 0 && recentCount < cap) {
                Out(LAG[(userGoneMs % LAG.size).toInt().coerceAtLeast(0)], State.LAG_ACK)
            } else Out(null, State.SILENT)
        }
        // The middle rung: ONE nonverbal cue, no words.
        if (since < silenceFirstMs) {
            return if (preambleSaid == 0 && recentCount < cap) Out(null, State.PREAMBLE)
            else Out(null, State.SILENT)
        }
        // No spoken filler rung exists between the preamble and the fail-soft line —
        // SILENCE_FIRST_MS gates WORDS, and the next voice is the lag line above.
        // (The old NEUTRAL/WARM sentence inventory lived here and was unreachable:
        // SILENCE_FIRST_MS == LAG_AFTER_MS meant nothing could ever select it.)
        return Out(null, State.SILENT)
    }

    /** Reset helper for the loop's tally: cues emitted more than the window
     *  ago no longer count against the cap. */
    fun countRecent(times: List<Long>, nowMs: Long): Int =
        times.count { nowMs - it < FILLER_WINDOW_MS }
}
