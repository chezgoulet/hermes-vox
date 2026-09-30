package com.hermesvox

/**
 * TranscriptValidator — the pure, emulator-free gate every on-device transcript
 * passes before it becomes a turn (the "Hermes must receive a true transcription"
 * rule). Whisper never says "I heard nothing": fed noise, a click or a breath it
 * returns its training-set priors, and the old loop forwarded whatever came back
 * that was not a LEADING bracket tag. The sttbench corpus (tools/sttbench) shows
 * the failure shapes on the shipped whisper-base.en:
 *  - every one of the five non-speech clips came back as a tag ("(buzzer)",
 *    "(clippers buzzing)", "(clicking)") — stripped anywhere by [BRACKETS];
 *  - a clean 3.4 s LibriSpeech sentence decoded as "This was this was this was…"
 *    ten times over (a decoder loop, at the old 300-frame tail padding) — the
 *    [collapseRepeats] / runaway-repetition rule;
 *  - the classic priors ("Thank you.", "Thanks for watching!") on near-silence —
 *    the [NON_SPEECH_PHRASES] rule, applied ONLY when the VAD measured little
 *    speech, so a user who really says "thank you" is heard.
 *
 * Inputs are the raw text and the VAD's own measurement of the clip: [speechMs]
 * (frames the VAD called speech) and [clipMs] (the whole segment incl. pre-roll
 * and the closing pause). Pass speechMs < 0 when unknown (the barge classifier):
 * only the text-shape rules then apply. Pure JVM — no android.* — so the rules
 * are unit-tested on the host (TranscriptValidatorTest).
 */
object TranscriptValidator {

    /** Bracketed non-speech tags anywhere in the text: [BLANK_AUDIO], (buzzing),
     *  *music*. Bounded length so a real parenthetical sentence is not eaten. */
    val BRACKETS = Regex("\\[[^\\]]{0,30}\\]|\\([^)]{0,30}\\)|\\*[^*]{0,30}\\*")

    /** Music-note glyphs Whisper emits over tones / hum. */
    private val NOTES = Regex("[♪♫♬]+")

    /** Faster than any conversational speaker (auctioneers peak near 5 w/s). A
     *  transcript denser than this over the VAD-measured speech is invented. */
    const val MAX_WORDS_PER_SEC = 6.0

    /** The floor on the speech duration the rate is computed over, so a crisp
     *  one-word answer measured at 200 ms of speech is not a "20 w/s" outlier. */
    const val RATE_FLOOR_MS = 500L

    /** Below this share of the clip being speech, the clip is noise the VAD
     *  flickered on (a breath, a door) — nothing a person said. */
    const val MIN_SPEECH_RATIO = 0.10

    /** The blocklist applies only to a clip with at most this much speech … */
    const val BLOCKLIST_MAX_SPEECH_MS = 350L

    /** … or a speech share below this (a short real "thank you" in the usual
     *  ~1.6 s segment is ~0.35+; a hallucinated one rides a sub-0.25 flicker). */
    const val BLOCKLIST_MAX_RATIO = 0.25

    /** Whisper's classic non-speech priors (normalized: lowercase, no punctuation).
     *  Kept small on purpose — each is a phrase YouTube-subtitle training data put
     *  on silence, not a thing people rarely say. */
    val NON_SPEECH_PHRASES = setOf(
        "thank you", "thank you very much", "thanks", "thanks for watching",
        "thank you for watching", "thanks for listening", "thank you for listening",
        "please subscribe", "subscribe", "like and subscribe", "subscribe to my channel",
        "bye", "bye bye", "you", "so", "oh", "um", "uh", "hmm",
        "subtitles by the amaraorg community", "transcribed by", "the end",
    )

    /** A run of the same n-gram repeated at least this many times in a row is a
     *  decoder loop: a unigram needs 5 ("no no no no" is a real answer), a 2..4-gram
     *  needs 3 ("this was this was this was"). */
    const val UNIGRAM_RUN = 5
    const val NGRAM_RUN = 3

    /** When collapsing removed more than this share of the words (and at least
     *  [RUNAWAY_MIN_REMOVED] words), the whole transcript is a loop, not speech. */
    const val RUNAWAY_SHARE = 0.5
    const val RUNAWAY_MIN_REMOVED = 6

    sealed class Verdict {
        /** Pass [text] on as the turn. [collapsed] = a repetition run was folded. */
        data class Accept(val text: String, val collapsed: Boolean = false) : Verdict()
        /** Refuse the turn; [reason] is a short log token (never the transcript). */
        data class Reject(val reason: String) : Verdict()
    }

    fun validate(raw: String?, speechMs: Long, clipMs: Long): Verdict {
        val stripped = NOTES.replace(BRACKETS.replace(raw.orEmpty(), " "), " ")
            .replace(Regex("\\s+"), " ").trim()
        if (normalize(stripped).isEmpty() || stripped.length < 2) return Verdict.Reject("noise-tag")
        val known = speechMs >= 0 && clipMs > 0
        val ratio = if (known) speechMs.toDouble() / clipMs else 1.0
        if (known && ratio < MIN_SPEECH_RATIO) return Verdict.Reject("low-speech-ratio")

        val tokens = stripped.split(' ')
        val kept = collapseRepeats(tokens)
        val removed = tokens.size - kept.size
        if (removed >= RUNAWAY_MIN_REMOVED && removed > tokens.size * RUNAWAY_SHARE)
            return Verdict.Reject("runaway-repetition")
        val text = kept.joinToString(" ")

        val norm = normalize(text)
        val words = norm.split(' ').count { it.isNotEmpty() }
        if (known && words > 3) {
            val secs = maxOf(speechMs, RATE_FLOOR_MS) / 1000.0
            if (words / secs > MAX_WORDS_PER_SEC) return Verdict.Reject("implausible-rate")
        }
        if (known && norm in NON_SPEECH_PHRASES &&
            (speechMs <= BLOCKLIST_MAX_SPEECH_MS || ratio < BLOCKLIST_MAX_RATIO))
            return Verdict.Reject("non-speech-phrase")
        return Verdict.Accept(text, collapsed = removed > 0)
    }

    /** Fold every consecutive run of a repeated 1..4-gram (compared normalized, so
     *  "This was, this was." matches) down to ONE occurrence. Longer n-grams are
     *  tried first so "thank you thank you thank you" folds as a bigram. */
    fun collapseRepeats(tokens: List<String>): List<String> {
        var cur = tokens
        for (n in 4 downTo 1) {
            val need = if (n == 1) UNIGRAM_RUN else NGRAM_RUN
            val key = cur.map { normalize(it) }
            val out = ArrayList<String>(cur.size)
            var i = 0
            while (i < cur.size) {
                var reps = 1
                if (i + n <= cur.size && key.subList(i, i + n).all { it.isNotEmpty() }) {
                    while (i + (reps + 1) * n <= cur.size &&
                        key.subList(i + reps * n, i + (reps + 1) * n) == key.subList(i, i + n)) reps++
                }
                if (reps >= need) { out.addAll(cur.subList(i, i + n)); i += reps * n }
                else { out.add(cur[i]); i++ }
            }
            cur = out
        }
        return cur
    }

    /** Lowercase, drop everything but letters/digits/apostrophes/spaces, squeeze. */
    fun normalize(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9' ]"), "").replace("'", "")
            .replace(Regex("\\s+"), " ").trim()
}
