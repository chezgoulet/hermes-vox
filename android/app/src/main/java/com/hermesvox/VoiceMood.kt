package com.hermesvox

/**
 * VoiceMood — the caller's mood as the soul HEARD it, and what it changes, pure JVM.
 *
 * Sesame's first component of voice presence is emotional intelligence: reading the emotional
 * context and responding to it. On the phone that has two halves:
 *  - HEAR it: the soul model (Gemma 4 E2B) takes the caller's audio alongside the transcript, so
 *    its turn render can tag the caller's tone — `{calm}`, `{warm}`, `{lively}` or `{tense}` —
 *    ahead of its answer or beat. A transcript alone cannot carry a sigh, a laugh or irritation.
 *  - ANSWER it: the tag steers the voice's delivery (speed and pause length on the on-device
 *    voice) and rides to the mind as the vibe in the request instructions.
 *
 * The mapping is deliberately conservative: a tense caller gets a steadier, slower voice (never a
 * mirrored tension), a lively caller a slightly brisker one. The tag is a hint — absent or unknown
 * means WARM, today's delivery exactly.
 */
enum class VoiceMood(
    /** Multiplies the engine's speaking speed. */
    val speed: Float,
    /** The engine's pause length between phrases (Supertonic `silenceScale`; 0.2 is its default). */
    val silenceScale: Float,
    /** The vibe mood token sent to the mind (ErDrift.Vibe). */
    val vibeMood: String,
    /** The vibe energy token sent to the mind. */
    val vibeEnergy: String,
) {
    CALM(0.94f, 0.28f, "calm", "low"),
    WARM(1.0f, 0.2f, "neutral", "normal"),
    LIVELY(1.06f, 0.16f, "warm", "high"),
    TENSE(0.92f, 0.3f, "frustrated", "normal");

    companion object {
        /** A marker's word: letters, `_` or `-`, an optional stray `/` before it (`<</CALM>>`). */
        private const val WORD = "/?\\s*([A-Za-z][A-Za-z_-]{1,23})"

        /**
         * A leading marker in any of the known bracket forms — `{calm}`, `<<CALM>>`,
         * `<</CALM>>`, `[CALM]` — case-insensitively, with whatever word the model put inside.
         *
         * Field-2: the soul's ONE rolling conversation (GemmaExpress) carries both the turn's
         * `{tone}` tag and the `<<ESCALATE>>` token, and a later render that was never asked for
         * a tag led with a hybrid of the two (`<<CALM>>`, `<<ESCALM>>`), which was read aloud.
         */
        private val MARKER = Regex(
            "^\\s*(?:\\{\\s*$WORD\\s*\\}|<<\\s*$WORD\\s*>>|\\[\\s*$WORD\\s*\\])\\s*"
        )

        private fun wordOf(m: MatchResult): String =
            m.groupValues.drop(1).first { it.isNotEmpty() }

        /**
         * Is this leading marker one [clean] removes? A known mood in any form; an invented word
         * only in the soul's own `<<…>>` token family (the field's `<<ESCALM>>`). An unknown
         * `{word}` or `[word]` is left for the presence markup bound to refuse, as D20 pins.
         */
        private fun strippable(m: MatchResult): Boolean =
            known(wordOf(m)) != null || m.groupValues[2].isNotEmpty()

        private fun known(word: String): VoiceMood? =
            values().firstOrNull { it.name.equals(word, ignoreCase = true) }

        /**
         * Split a leading mood tag off a soul render. Returns the mood (null when there is no tag)
         * and the rest, which is then parsed as the decision. The tag must LEAD: a brace later in
         * the text is ordinary content. Any known bracket form is accepted; a marker whose word is
         * not one of the four moods is left in place (it may be the `<<ESCALATE>>` token the
         * decision parser needs).
         */
        fun split(rendered: String?): Pair<VoiceMood?, String> {
            val t = rendered ?: return null to ""
            val m = MARKER.find(t) ?: return null to t
            val mood = known(wordOf(m)) ?: return null to t
            return mood to t.substring(m.range.last + 1)
        }

        /**
         * The boundary sanitiser for words that will be SPOKEN or written to the transcript (glue,
         * narration, a working line). On every line, leading markers in the known forms are
         * removed — a known mood is returned (the first one seen) for a consumer that wants it,
         * an invented `<<WORD>>` is discarded — and a line that was nothing but a marker is dropped. A
         * marker mid-sentence is content and stays.
         */
        fun clean(text: String?): Pair<VoiceMood?, String> {
            val t = text ?: return null to ""
            var mood: VoiceMood? = null
            val out = t.lines().mapNotNull { line ->
                var rest = line
                while (true) {
                    val m = MARKER.find(rest)?.takeIf { strippable(it) } ?: break
                    if (mood == null) mood = known(wordOf(m))
                    rest = rest.substring(m.range.last + 1)
                }
                if (rest !== line && rest.isBlank()) null else rest
            }
            return mood to out.joinToString("\n")
        }
    }
}
