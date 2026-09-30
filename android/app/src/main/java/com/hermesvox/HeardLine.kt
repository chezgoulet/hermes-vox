package com.hermesvox

/**
 * HeardLine — the pure rules for the dim "heard" line (what a voice turn sent
 * Hermes). The app is presence-first: no chat bubbles, no chrome. But a Whisper
 * mishearing used to be invisible until the reply came back answering the wrong
 * question — the user never saw the words that were sent. The heard line shows
 * them for a few seconds under the being, dim, then fades; a mishearing is caught
 * early and corrected by simply barging in.
 *
 * Privacy: display only. The host keeps it in view state and the in-memory
 * conversation transcript; it is never written to disk (VoxLog only ever sees
 * the text when `log_transcripts` is on, as before).
 */
object HeardLine {

    /** The shortest a line stays up (ms) — long enough to read three words. */
    const val MIN_HOLD_MS = 3_500L
    /** Reading time per word (ms) — ~240 wpm, a comfortable skim. */
    const val PER_WORD_MS = 250L
    /** The longest a line stays up; the reply's own text takes over after this. */
    const val MAX_HOLD_MS = 8_000L
    /** Characters shown (two lines of the monospace face); the rest ellipsizes. */
    const val MAX_CHARS = 140

    /** The visible text: a quiet prompt glyph + the words, whitespace-squeezed. */
    fun displayText(text: String): String {
        val t = text.replace(Regex("\\s+"), " ").trim()
        return "› " + if (t.length > MAX_CHARS) t.take(MAX_CHARS - 1).trimEnd() + "…" else t
    }

    /** How long the line holds before fading, scaled to its length. A screen
     *  reader may lengthen it further (AccessibilityManager's recommended
     *  timeout, applied by the host). */
    fun holdMs(text: String): Long {
        val words = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        return (MIN_HOLD_MS + words * PER_WORD_MS).coerceAtMost(MAX_HOLD_MS)
    }
}
