package com.hermesvox

/**
 * SemanticEndpoint — the end of a turn as a judgement, not a silence constant (#229).
 *
 * Today a turn ends after a fixed pause (`vad_silence_ms`, 800 ms). That waits the full pause
 * after a finished sentence and still cuts a speaker who stops to think mid-sentence. This
 * judges from what the app already has — no new model (the issue's cheaper arm): how long the
 * caller has been silent, whether the words so far read as finished, and an eagerness preset.
 *
 *  - a partial that ends on a word that cannot end an English sentence ("…and", "…the",
 *    "…because", "…um") needs a long pause before the turn is committed;
 *  - so does one the recogniser is still revising: on the corpus the partial was usually still
 *    changing half a second into a word gap ("…nor" → "…northern"), so a turn counts as
 *    finished only once its text has held still for the preset's [Eagerness.stableMs];
 *  - one that reads as finished, and has stopped changing, commits after a short one;
 *  - every turn ends at the preset's ceiling, whatever the words say (the timeout).
 *
 * The verdicts follow the VoiceManager frame kinds (docs/NOTES-voicemanager.md §1): CONTINUE,
 * PROVISIONAL ("they probably stopped" — a latency hint, revisable) and FINAL (commit). Only
 * FINAL is acted on irreversibly.
 *
 * The thresholds are a first fit to the sttbench corpus, replayed on every build by
 * EndpointBenchTest; they are hypotheses until a field bench says otherwise (D20). Ships behind
 * its own switch (KEY), off by default.
 */
object SemanticEndpoint {

    const val KEY = "semantic_endpoint"
    const val KEY_EAGERNESS = "endpoint_eagerness"

    /**
     * The presets are three points on the frontier the corpus measured (EndpointBenchTest;
     * 22 sttbench clips, 2026-10-22) — fewer false endpoints costs latency, and no setting of
     * this arm beats the fixed 800 ms pause on both at once by more than 100 ms:
     *
     *     800 ms fixed (today)   6 false   900 ms median after the last word
     *     PATIENT  stable 400    4 false  1000 ms
     *     BALANCED stable 300    6 false   800 ms
     *     EAGER    stable 200   11 false   500 ms
     *
     * [stableMs] is how long the partial must have held still; it is the feature that
     * carries the result (the dangling-word rule moves it little on read speech).
     */
    enum class Eagerness(val key: String, val finishedMs: Long, val stableMs: Long, val unfinishedMs: Long, val ceilingMs: Long) {
        PATIENT("patient", 400, 400, 1600, 2200),
        BALANCED("balanced", 400, 300, 1600, 2200),
        EAGER("eager", 400, 200, 1600, 2200);

        companion object {
            fun of(key: String?): Eagerness = values().firstOrNull { it.key == key } ?: BALANCED
        }
    }

    enum class Verdict { CONTINUE, PROVISIONAL, FINAL }

    /** Words an English turn does not end on: conjunctions, articles, prepositions, fillers,
     *  a dangling subject. Seen in the corpus as the word before a mid-sentence pause. */
    private val DANGLING = setOf(
        "and", "or", "but", "so", "because", "if", "then", "than", "that", "which", "who", "when",
        "while", "where", "the", "a", "an", "my", "your", "his", "her", "their", "our", "its",
        "of", "to", "with", "in", "on", "at", "for", "from", "by", "about", "into", "like",
        "um", "uh", "er", "erm", "i", "we", "you", "they", "he", "she", "it's", "is", "are",
        "was", "were", "be", "been", "do", "does", "did", "have", "has", "had", "will", "would",
        "can", "could", "should", "not", "very", "just", "also", "some",
    )

    /** Does [partial] read as a finished turn? Nothing heard yet is not finished. */
    fun finished(partial: String): Boolean {
        val words = partial.trim().lowercase().split(Regex("[^a-z']+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        return words.last() !in DANGLING
    }

    /** The probability the caller has finished, from the pause, the words and how long the
     *  words have held still [stableMs] (0..1). */
    fun probability(silenceMs: Long, partial: String, stableMs: Long, e: Eagerness): Float {
        val settled = finished(partial) && stableMs >= minOf(silenceMs, e.stableMs)
        val need = if (settled) e.finishedMs else e.unfinishedMs
        return (silenceMs.toFloat() / need).coerceIn(0f, 1f)
    }

    /** The verdict after [silenceMs] of silence following speech, on the words so far, which
     *  have not changed for [stableMs]. */
    fun decide(silenceMs: Long, partial: String, stableMs: Long, e: Eagerness): Verdict {
        if (silenceMs >= e.ceilingMs) return Verdict.FINAL
        val p = probability(silenceMs, partial, stableMs, e)
        return when {
            p >= 1f -> Verdict.FINAL
            p >= 0.5f -> Verdict.PROVISIONAL
            else -> Verdict.CONTINUE
        }
    }
}
