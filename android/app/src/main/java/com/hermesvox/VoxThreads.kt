package com.hermesvox

/**
 * VoxThreads — the CPU-thread budget for the on-device speech legs.
 *
 * The voice models stay on the CPU while the Gemma express layer moves to the
 * GPU. That trade is deliberate: it hands the CPU back to Whisper/Piper/Silero,
 * which share the same live call, instead of having all four fight for it. Both
 * speech legs shipped pinned to a single thread, which left a modern big.LITTLE
 * phone mostly idle.
 *
 * The defaults are DERIVED, not guessed:
 *  - STT (Whisper) — the only speech stage with real headroom, and then mainly on
 *    long audio. Half the cores, floored at 2 (one thread starves the encoder)
 *    and capped at 4 (past that the decode is memory-bound and the extra threads
 *    only steal CPU from the capture path).
 *  - TTS (Piper) — already outruns real time, so a modest 2; more would compete
 *    with the reply's own streaming playback for no throughput gain.
 *  - VAD — stays at ONE thread on purpose: it is evaluated per 30 ms frame on the
 *    capture path, where frame jitter costs more than any throughput win.
 *
 * The user override (Settings → "Voice CPU threads") is optional; AUTO keeps the
 * derived value. These are pure functions so the policy is unit-testable off-device.
 * `numThreads` is baked into a recognizer/synth session at construction, so a
 * change applies on the next voice-model load, not mid-turn.
 */
object VoxThreads {

    /** Settings pref. [AUTO] = derived; [MIN]..[MAX] = an explicit count for both legs. */
    const val PREF = "voice_threads"
    const val AUTO = 0
    const val MIN = 1
    const val MAX = 4

    /** Silero VAD is deliberately single-threaded (per-frame, on the capture path). */
    const val VAD = 1

    /** Whisper thread count. A [pref] outside AUTO/MIN..MAX falls back to AUTO. */
    fun stt(cores: Int, pref: Int): Int =
        if (pref in MIN..MAX) pref else (cores / 2).coerceIn(2, MAX)

    /** Piper thread count. */
    fun tts(cores: Int, pref: Int): Int =
        if (pref in MIN..MAX) pref else (cores / 4).coerceIn(1, 2)

    /** The value string shown on the Settings row. */
    fun label(pref: Int): String =
        if (pref in MIN..MAX) "$pref thread${if (pref == 1) "" else "s"}" else "Auto"
}
