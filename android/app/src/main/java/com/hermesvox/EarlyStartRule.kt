package com.hermesvox

/**
 * EarlyStartRule — the pure rules that keep the partial-STT early start from
 * ever sending Hermes a truncated transcript.
 *
 * The defect (0.8.0 and before): the partial worker transcribed only the LAST
 * 6 s of the segment, and when two partials matched across a >= 450 ms pause
 * THAT TAIL TEXT became the turn. So a >6 s utterance lost its beginning, and an
 * ordinary mid-sentence thinking pause (~0.5 s) ended the turn with half a
 * thought. The fix splits the two jobs the partial was doing:
 *
 *  1. TRIGGER only. A partial may end the capture early (before the normal
 *     `vad_silence_ms` pause) only on a genuine end-of-turn: stable across two
 *     snapshots, silent for at least [MIN_EARLY_SILENCE_MS] (a floor the pref
 *     cannot undercut), and the hypothesis must [looksComplete] — ends in
 *     terminal punctuation, not on a conjunction/article/filler that promises
 *     more ("so I was thinking that…", "and the").
 *  2. TEXT from the whole utterance. The committed text is always a
 *     transcription of the WHOLE segment (windowed past 25 s, see SttWindows).
 *     The latency win survives where it is safe: a partial is REUSED as the
 *     turn text only when it [mayReusePartial] — it was decoded from sample 0
 *     and nothing the VAD called speech arrived after its snapshot. Otherwise
 *     the whole segment is transcribed.
 */
object EarlyStartRule {

    /** Hard floor on the early-start pause (ms). The old 450 ms default sits
     *  inside ordinary between-phrase pauses; the pref may raise, never lower. */
    const val MIN_EARLY_SILENCE_MS = 600L

    /** Partial snapshots cover the whole segment up to this length, so the common
     *  short turn can reuse its partial; longer segments snapshot a 6 s tail that
     *  is a trigger only (never reusable — it did not start at sample 0). */
    const val PARTIAL_WHOLE_MAX_MS = 8_000
    const val PARTIAL_TAIL_MS = 6_000

    /** Words a finished thought does not end on. */
    private val DANGLING = setOf(
        "and", "but", "or", "so", "because", "cause", "if", "then", "that", "which", "who",
        "the", "a", "an", "to", "of", "for", "with", "in", "on", "at", "from", "about", "into",
        "my", "your", "our", "their", "his", "her", "its", "is", "are", "was", "were", "i",
        "um", "uh", "er", "like", "just", "maybe", "also", "when", "where", "while", "until",
    )

    fun effectiveSilenceMs(prefMs: Long): Long = maxOf(prefMs, MIN_EARLY_SILENCE_MS)

    /** The hypothesis reads as a finished utterance: it ends in . ? or ! (not an
     *  ellipsis — Whisper writes "…" / "..." for trailing-off speech), and its last
     *  word is not one that promises more. */
    fun looksComplete(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (t.endsWith("...") || t.endsWith("…") || t.endsWith(",") || t.endsWith("-")) return false
        if (t.last() !in ".?!") return false
        val last = t.trimEnd('.', '?', '!', '"', '\'', ' ').substringAfterLast(' ')
            .lowercase().filter { it.isLetter() }
        return last.isNotEmpty() && last !in DANGLING
    }

    /** Snapshot start for a partial over a segment of [segSamples]: the whole
     *  segment when it is short enough, else the trigger-only tail. */
    fun snapshotStart(segSamples: Int, sampleRate: Int): Int =
        if (segSamples.toLong() * 1000 <= PARTIAL_WHOLE_MAX_MS.toLong() * sampleRate) 0
        else maxOf(0, segSamples - sampleRate * PARTIAL_TAIL_MS / 1000)

    /** May the partial decoded from [snapStart, snapEnd) stand in for a whole-
     *  segment transcription? Only if it began at the segment's first sample and
     *  covered every sample up to the last VAD-speech frame ([lastSpeechEnd]). */
    fun mayReusePartial(snapStart: Int, snapEnd: Int, lastSpeechEnd: Int): Boolean =
        snapStart == 0 && snapEnd > 0 && lastSpeechEnd <= snapEnd
}
