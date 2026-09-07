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
 * The caller tracks [sustainedMs]: the contiguous time the RMS has held ABOVE the
 * active floor (rmsMin when VAD is available, rmsMin * [NO_VAD_RMS_BOOST] without),
 * resetting it to 0 the instant a read drops back below that floor. The caller ALSO
 * tracks [sustainedLevelMs]: a SECOND accumulator held ABOVE the raised bar
 * activeFloor * [LEVEL_ONLY_BOOST] (its own 1.6x bar, reset-to-0 independently), so a
 * read that dips between the floor and the raised bar can zero the VAD sustain
 * without losing the level-only sustain. [decide] then fires only once a sustain
 * requirement is met:
 *
 *  - VAD available: rms > rmsMin sustained for >= [VAD_SUSTAIN_MS] AND the VAD
 *    reports speech on the current read.
 *  - No VAD fallback: rms > rmsMin * 1.4 sustained for >= [NO_VAD_SUSTAIN_MS].
 *  - Level-only escape (regardless of VAD): when [levelOnlyMs] > 0 and the raised
 *    bar (activeFloor * [LEVEL_ONLY_BOOST]) has held for >= [levelOnlyMs], fire even
 *    though VAD says false — the echo-masked-speech path. Under echo-dominant
 *    playback the mixed frame reads as non-speech to Silero at the frame level, so
 *    the VAD path can starve; a level that out-lasts [levelOnlyMs] at the raised bar
 *    is intentional sustained speech (0 disables the escape).
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

    /** RMS floor multiplier for the level-only escape (raised above the NO_VAD
     *  bar, so content-modulated echo peaks under the bar never build toward it). */
    const val LEVEL_ONLY_BOOST = 1.6f

    /** `barge_level_only_ms` pref default: sustain required at the raised
     *  [LEVEL_ONLY_BOOST] bar to fire the level-only escape (0 disables). */
    const val DEFAULT_LEVEL_ONLY_MS = 400L

    fun decide(rms: Float, vadSpeech: Boolean?, sustainedMs: Long, rmsMin: Float, vadAvailable: Boolean,
               levelOnlyMs: Long = DEFAULT_LEVEL_ONLY_MS, sustainedLevelMs: Long = 0L): Boolean {
        if (rmsMin <= 0f) return false
        // Level-only escape: ignore VAD (echo can hold the frame-level VAD false)
        // once the raised bar (activeFloor * LEVEL_ONLY_BOOST) has held levelOnlyMs.
        if (levelOnlyMs > 0L && sustainedLevelMs >= levelOnlyMs) {
            val floor = if (vadAvailable) rmsMin else rmsMin * NO_VAD_RMS_BOOST
            if (rms > floor * LEVEL_ONLY_BOOST) return true
        }
        if (vadAvailable) {
            // double gate: sustained RMS above rmsMin for >=200ms AND VAD says speech
            return sustainedMs >= VAD_SUSTAIN_MS && vadSpeech == true && rms > rmsMin
        }
        // no-VAD fallback: higher floor (rmsMin*1.4), longer sustain (350ms)
        return sustainedMs >= NO_VAD_SUSTAIN_MS && rms > rmsMin * NO_VAD_RMS_BOOST
    }
}
