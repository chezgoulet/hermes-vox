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
 * resetting it to 0 the instant a read drops back below that floor. [decide] then
 * fires only once the sustain requirement is met:
 *
 *  - VAD available: rms > rmsMin sustained for >= [VAD_SUSTAIN_MS] AND the VAD
 *    reports speech on the current read.
 *  - No VAD fallback: rms > rmsMin * 1.4 sustained for >= [NO_VAD_SUSTAIN_MS].
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

    fun decide(rms: Float, vadSpeech: Boolean?, sustainedMs: Long, rmsMin: Float, vadAvailable: Boolean): Boolean {
        if (rmsMin <= 0f) return false
        if (vadAvailable) {
            // double gate: sustained RMS above rmsMin for >=200ms AND VAD says speech
            return sustainedMs >= VAD_SUSTAIN_MS && vadSpeech == true && rms > rmsMin
        }
        // no-VAD fallback: higher floor (rmsMin*1.4), longer sustain (350ms)
        return sustainedMs >= NO_VAD_SUSTAIN_MS && rms > rmsMin * NO_VAD_RMS_BOOST
    }
}
