package com.hermesvox

/**
 * BargeGate — the pure, emulator-free decision rule for single-capture barge-in.
 *
 * The realtime loop keeps ONE AudioRecord running through the turn and drains it
 * for a barge check while the turn gate is closed. It cannot interrupt on a single
 * loud frame (that was the source of the self-cut: the app's own playback leaking
 * at rms~0.16 through a second, AEC-less recorder). Instead it requires a SUSTAINED
 * speech-like level AND, when the on-device VAD is available, VAD agreement too —
 * the "double gate".
 *
 * 0.4.0.3 — why the 0.4.0.2 escape never fired (field log docs/evidence-0403-failure.log):
 *
 *  1. UNREACHABLE BAR. The escape bar was activeFloor * 1.6 = 0.16 at the shipped
 *     0.10 floor. Turn 10's near-miss probes report peakRms — the running MAX of the
 *     current above-0.7*floor run — as 0.172 and 0.182 while the user was talking
 *     over the reply. The bar sat inside the top decile of the user's own dynamic
 *     range, so the level crossed it only at crests. Meanwhile the residual echo
 *     measured mid-reply on this single-capture/AEC path is 0.081-0.095 (same log,
 *     21:20:22.036 / 21:20:48.153 / 21:18:17.179) — NOT the 0.15-0.17 of the
 *     AEC-less second-recorder era the 1.6x bar was derived from. The bar is
 *     re-derived here for the architecture that actually ships:
 *     [LEVEL_ONLY_BOOST] 1.6 -> 1.3 (a 0.130 bar), which clears the measured
 *     residual-echo band by ~37% and sits ~24% under the measured barge band.
 *
 *  2. UNREACHABLE SUSTAIN. Both accumulators reset to 0 on ANY sub-bar read, at a
 *     64ms read granularity (1024 shorts @ 16kHz). Every one of the 15 near-miss
 *     lines in the session reports sustainedMs as 0 or exactly 64 — never 128 — so
 *     two consecutive above-floor reads were already rare at the 0.10 floor; seven
 *     consecutive above-0.16 reads (400ms) was arithmetically out of reach. The
 *     accumulators are now LEAKY ([accumulate]): +frameMs above the bar,
 *     -[SUSTAIN_LEAK_MS] below it, clamped at 0. That turns "contiguous time" into
 *     "net occupancy", whose discriminator is DUTY CYCLE at the bar rather than
 *     amplitude: with a 96ms leak against 64ms frames the break-even duty is 0.6, so
 *     a voice that holds the bar through brief phoneme dips charges while an
 *     amplitude-modulated echo envelope (crests over the bar, troughs back down in
 *     the residual band) drains. This is the anti-self-cut defence now — it replaces
 *     the amplitude margin that (1) gave up.
 *
 *  3. SAME-FRAME VAD CONJUNCTION. The VAD path required vad==true on the CURRENT
 *     read. The log shows VAD agreement arriving on different frames than the loud
 *     ones during a barge attempt (turn 3: vad=false at peak 0.114, vad=true at peak
 *     0.147; turn 10: vad=true at 0.135 between vad=false peaks of 0.172/0.182), so
 *     the conjunction can miss by a frame. [VAD_RECENT_MS] accepts agreement from
 *     the last 250ms instead. The level requirement is unchanged, so this only
 *     widens the instant at which an already-qualifying level may fire.
 *
 * The caller tracks [sustainedMs]: net time the RMS has held above the active floor
 * (rmsMin when VAD is available, rmsMin * [NO_VAD_RMS_BOOST] without). The caller
 * ALSO tracks [sustainedLevelMs] against the raised bar activeFloor *
 * [LEVEL_ONLY_BOOST], independently, so a read that dips between the floor and the
 * raised bar drains the level sustain without touching the VAD sustain. [decide]
 * fires once a sustain requirement is met:
 *
 *  - VAD available: rms > rmsMin sustained for >= [VAD_SUSTAIN_MS] AND the VAD
 *    reported speech on this read or within [VAD_RECENT_MS].
 *  - No VAD fallback: rms > rmsMin * 1.4 sustained for >= [NO_VAD_SUSTAIN_MS].
 *  - Level-only escape (regardless of VAD): when [levelOnlyMs] > 0 and the raised
 *    bar has held for >= [levelOnlyMs] of net occupancy, fire even though VAD says
 *    false — the echo-masked-speech path. Under echo-dominant playback the mixed
 *    frame reads as non-speech to Silero at the frame level, so the VAD path can
 *    starve (0 disables the escape).
 *
 * The playback grace window (skip checks for [DEFAULT_GRACE_MS] after `speaking`
 * flips true, so the TTS onset is never mistaken for the user) is enforced by the
 * caller, not here; generation mode is exempt because nothing plays yet.
 */
object BargeGate {

    /** `barge_rms_min` pref default: RMS floor for a barge candidate with VAD. */
    const val DEFAULT_RMS_MIN = 0.10f

    /** `barge_grace_ms` pref default: playback grace before checks resume. */
    const val DEFAULT_GRACE_MS = 500

    /** Sustain required over the RMS floor when VAD agrees (ms). */
    const val VAD_SUSTAIN_MS = 200L

    /** Sustain required without VAD (ms) — longer, the double gate is RMS-only. */
    const val NO_VAD_SUSTAIN_MS = 350L

    /** RMS floor multiplier used when no VAD is available (higher bar). */
    const val NO_VAD_RMS_BOOST = 1.4f

    /** RMS floor multiplier for the level-only escape. 0.4.0.3: 1.6 -> 1.3, re-derived
     *  for the single-capture/AEC path whose measured residual echo is 0.081-0.095,
     *  not the 0.15-0.17 of the AEC-less second recorder. Duty cycle at this bar (see
     *  [SUSTAIN_LEAK_MS]), not amplitude headroom, is what now separates the app's own
     *  echo envelope from a person talking over it. */
    const val LEVEL_ONLY_BOOST = 1.3f

    /** `barge_level_only_ms` pref default: NET occupancy required at the raised
     *  [LEVEL_ONLY_BOOST] bar to fire the level-only escape (0 disables). 0.4.0.3:
     *  400 -> 200. The unit changed meaning — contiguous time (unreachable, see the
     *  class doc) became leaky net occupancy — and 200ms lines the escape up with
     *  [VAD_SUSTAIN_MS]: the same sustain, with a raised level bar standing in for
     *  the VAD agreement the echo denies us. */
    const val DEFAULT_LEVEL_ONLY_MS = 200L

    /** Leak subtracted from a sustain accumulator by a sub-bar read (ms). Against the
     *  drain's 64ms frames this puts the break-even duty cycle at 96/(64+96) = 0.6:
     *  above 60% of reads over the bar the accumulator charges, below it drains. */
    const val SUSTAIN_LEAK_MS = 96L

    /** Ceiling on a sustain accumulator so a long loud stretch cannot bank credit
     *  that outlives it (a full drain from the cap takes ~533ms of sub-bar reads). */
    const val SUSTAIN_CAP_MS = 800L

    /** How stale VAD agreement may be and still count for the VAD path (ms). */
    const val VAD_RECENT_MS = 250L

    /**
     * Leaky sustain accumulator — the 0.4.0.3 replacement for reset-to-0.
     * Above the bar: charge one frame, clamped to [capMs]. Below: leak
     * [SUSTAIN_LEAK_MS], clamped at 0. Pure; the caller owns the state.
     */
    fun accumulate(prev: Long, above: Boolean, frameMs: Long, capMs: Long = SUSTAIN_CAP_MS): Long =
        if (above) minOf(prev + frameMs, maxOf(capMs, frameMs))
        else maxOf(0L, prev - SUSTAIN_LEAK_MS)

    /**
     * @param rms the CURRENT read's level
     * @param vadSpeech VAD verdict on this read (null = no VAD)
     * @param sustainedMs net occupancy above the active floor (leaky)
     * @param rmsMin the active floor
     * @param vadAvailable true when the shared VAD is live
     * @param levelOnlyMs the level-only escape requirement (0 disables)
     * @param sustainedLevelMs net occupancy above the RAISED bar (leaky)
     * @param msSinceVadSpeech wall-clock since the VAD last agreed
     * @param peakLevelMs 0.6.8: the running MAX level of the current near-floor
     *   run (the caller already tracks peakRms for the near-miss probe). The
     *   field failure (0.6.7, gen=5 18:33:18.935): the user's interrupt peaked at
     *   0.110 in ONE 64ms frame — over the floor (0.100), under the raised bar
     *   (0.130) — and both accumulators drained because the PEAK lived in a
     *   single read. Short, soft interrupts ("hey—") are exactly the natural
     *   barge shape, and the ladder made the floor quieter while making the
     *   interrupt MORE likely to be one short word. Fix: when the run's PEAK
     *   clears the raised bar, treat the escape's bar requirement as met even
     *   if the current read has dipped — the sustain still requires
     *   [levelOnlyMs] of net occupancy, so residual echo (which peaks in the
     *   0.081-0.095 band, under the bar) still cannot pass.
     */
    fun decide(rms: Float, vadSpeech: Boolean?, sustainedMs: Long, rmsMin: Float, vadAvailable: Boolean,
               levelOnlyMs: Long = DEFAULT_LEVEL_ONLY_MS, sustainedLevelMs: Long = 0L,
               msSinceVadSpeech: Long = Long.MAX_VALUE, peakLevel: Float = 0f): Boolean {
        if (rmsMin <= 0f) return false
        val floor = if (vadAvailable) rmsMin else rmsMin * NO_VAD_RMS_BOOST
        val levelBar = floor * LEVEL_ONLY_BOOST
        // Level-only escape: ignore VAD (echo can hold the frame-level VAD false)
        // once the raised bar has held levelOnlyMs of NET occupancy. 0.6.8: the
        // bar may be met by the RUN PEAK (a crest in a single frame) instead of
        // only the current read — see the peakLevel doc above.
        if (levelOnlyMs > 0L && sustainedLevelMs >= levelOnlyMs && (rms > levelBar || peakLevel > levelBar)) return true
        if (vadAvailable) {
            // double gate: sustained RMS above rmsMin for >=200ms AND VAD speech on
            // this read or within VAD_RECENT_MS of it
            val vadAgrees = vadSpeech == true || msSinceVadSpeech <= VAD_RECENT_MS
            return sustainedMs >= VAD_SUSTAIN_MS && vadAgrees && rms > rmsMin
        }
        // no-VAD fallback: higher floor (rmsMin*1.4), longer sustain (350ms)
        return sustainedMs >= NO_VAD_SUSTAIN_MS && rms > rmsMin * NO_VAD_RMS_BOOST
    }
}
