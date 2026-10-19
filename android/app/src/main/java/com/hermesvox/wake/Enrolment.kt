package com.hermesvox.wake

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * What a recording is for. The verifier (docs/WAKE-WORD.md §3) learns from three
 * kinds of audio, and each kind has its own length and level rules in [TakeMeasure].
 */
enum class TakeKind(val minMs: Int, val maxMs: Int) {
    /** One reading of the wake phrase: a positive example. */
    PHRASE(500, 4_000),
    /** The owner talking about anything: same voice, not the phrase — the negatives. */
    SPEECH(10_000, 15_000),
    /** The owner's room with nobody talking: the background the detector sits in. */
    ROOM(5_000, 15_000),
}

/**
 * The enrolment script: N takes of the wake phrase, each with a prompt that varies
 * tone or loudness so the verifier (docs/WAKE-WORD.md §3) sees the spread of how
 * the owner actually says it, not eight copies of one reading — then one take of
 * free speech and one of the room.
 *
 * openWakeWord's custom verifier needs at least 3 positive clips; D6 asks for 5-10.
 * [MIN_TAKES] is the floor below which a verifier would not be trained. It also needs
 * ≥ ~10 s of the same speaker saying other things and, optionally, ~5 s of background:
 * [TakeKind.SPEECH] and [TakeKind.ROOM] ask for 15 s and keep anything from those floors.
 */
object EnrolmentPrompts {
    const val PHRASE = "Hey Hermes"
    const val MIN_TAKES = 5

    const val SPEECH_PROMPT = "Talk naturally for 15 seconds — about anything, just not the wake phrase"
    const val ROOM_PROMPT = "Stay quiet for 15 seconds where you'll usually use the wake word"
    const val SPEECH_WHY = "Why: hearing you say other things teaches the detector what your voice " +
        "sounds like when you are not calling it, so it does not wake when you talk nearby."
    const val ROOM_WHY = "Why: a recording of your room's usual sound — fridge, fan, street — " +
        "teaches the detector what to ignore. Please don't talk; it records for 15 seconds."

    /** One enrolment step: a phrase take with its prompt, or one of the two long takes. */
    data class Step(val kind: TakeKind, val index: Int, val prompt: String)

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

    /** Every step the screen walks through, in order: the phrase takes, then speech, then room. */
    val steps: List<Step> =
        prompts.mapIndexed { i, p -> Step(TakeKind.PHRASE, i, p) } +
            Step(TakeKind.SPEECH, 0, SPEECH_PROMPT) + Step(TakeKind.ROOM, 0, ROOM_PROMPT)

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
    /** Null when a phrase take is usable, else the one-line reason to redo it. */
    val problem: String? get() = problemFor(TakeKind.PHRASE)

    val ok: Boolean get() = problem == null

    /** Null when a take of [kind] is usable, else the one-line reason to redo it. */
    fun problemFor(kind: TakeKind): String? = when {
        durationMs < kind.minMs -> when (kind) {
            TakeKind.PHRASE -> "Too short — say the whole phrase"
            TakeKind.SPEECH -> "Too short — keep talking for at least ${kind.minMs / 1000} s"
            TakeKind.ROOM -> "Too short — stay quiet for at least ${kind.minMs / 1000} s"
        }
        durationMs > kind.maxMs ->
            if (kind == TakeKind.PHRASE) "Too long — just the phrase" else "Too long — ${kind.maxMs / 1000} s is enough"
        kind == TakeKind.ROOM && rmsDbfs < DEAD_DBFS -> "Silent — the mic recorded nothing; is it muted or covered?"
        kind != TakeKind.ROOM && rmsDbfs < QUIET_DBFS -> "Too quiet — the mic barely heard it"
        kind == TakeKind.ROOM && rmsDbfs > ROOM_LOUD_DBFS -> "Too loud for a quiet take — was someone talking?"
        clippedFraction > MAX_CLIPPED -> "Too loud — the mic clipped"
        else -> null
    }

    companion object {
        /** Whole-take RMS below this is a silent or blocked mic, not a quiet voice. */
        const val QUIET_DBFS = -50.0
        /**
         * A room take is meant to be quiet, so only digital silence (a muted mic or the
         * Android 12+ privacy toggle) is rejected as dead. [est] — tune on real phones.
         */
        const val DEAD_DBFS = -90.0
        /** Room takes louder than this are speech or music, not background. [est] */
        const val ROOM_LOUD_DBFS = -25.0
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
