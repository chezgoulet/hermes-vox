package com.hermesvox.wake

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The enrolment script: N takes of the wake phrase, each with a prompt that varies
 * tone or loudness so the verifier (docs/WAKE-WORD.md §3) sees the spread of how
 * the owner actually says it, not eight copies of one reading.
 *
 * openWakeWord's custom verifier needs at least 3 positive clips; D6 asks for 5-10.
 * [MIN_TAKES] is the floor below which a verifier would not be trained.
 */
object EnrolmentPrompts {
    const val PHRASE = "Hey Hermes"
    const val MIN_TAKES = 5

    /** One prompt per take, in the order the screen asks for them. */
    val prompts: List<String> = listOf(
        "Your normal voice, phone at arm's length",
        "Quietly, as if someone nearby is asleep",
        "Loudly, as if from across the room",
        "Quickly, running the words together",
        "Slowly and clearly",
        "Low and tired, end-of-day voice",
        "Bright, with a smile",
        "Phone on the table, you a step away",
    )

    val takes: Int get() = prompts.size

    fun isReady(recorded: Int): Boolean = recorded >= MIN_TAKES
}

/**
 * The measurement every take must pass before it is stored. Pure arithmetic over
 * PCM16 so it is unit-testable; the screen shows the numbers it measured.
 */
data class TakeMeasure(
    val durationMs: Int,
    val rmsDbfs: Double,
    val peakDbfs: Double,
    val clippedFraction: Double,
) {
    /** Null when the take is usable, else the one-line reason to redo it. */
    val problem: String?
        get() = when {
            durationMs < MIN_MS -> "Too short — say the whole phrase"
            durationMs > MAX_MS -> "Too long — just the phrase"
            rmsDbfs < QUIET_DBFS -> "Too quiet — the mic barely heard it"
            clippedFraction > MAX_CLIPPED -> "Too loud — the mic clipped"
            else -> null
        }

    val ok: Boolean get() = problem == null

    companion object {
        const val MIN_MS = 500
        const val MAX_MS = 4_000
        /** Whole-take RMS below this is a silent or blocked mic, not a quiet voice. */
        const val QUIET_DBFS = -50.0
        /** More than 1% of samples at full scale means the take is distorted. */
        const val MAX_CLIPPED = 0.01
        private const val CLIP = 32_000

        fun of(pcm: ShortArray, sampleRate: Int = WakeWordEngine.SAMPLE_RATE): TakeMeasure {
            val ms = if (sampleRate > 0) (pcm.size * 1000L / sampleRate).toInt() else 0
            if (pcm.isEmpty()) return TakeMeasure(ms, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0)
            var sumSq = 0.0
            var peak = 0
            var clipped = 0
            for (s in pcm) {
                val a = kotlin.math.abs(s.toInt())
                sumSq += a.toDouble() * a
                if (a > peak) peak = a
                if (a >= CLIP) clipped++
            }
            val rms = sqrt(sumSq / pcm.size)
            return TakeMeasure(ms, dbfs(rms), dbfs(peak.toDouble()), clipped.toDouble() / pcm.size)
        }

        private fun dbfs(x: Double): Double =
            if (x <= 0.0) Double.NEGATIVE_INFINITY else 20 * log10(x / 32_768.0)
    }
}
