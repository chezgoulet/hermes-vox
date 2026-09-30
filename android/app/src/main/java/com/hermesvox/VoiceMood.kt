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
 *    voice) and rides to the mind as the vibe in the soul-sync epilogue.
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
        private val TAG = Regex("^\\s*\\{\\s*(calm|warm|lively|tense)\\s*\\}\\s*", RegexOption.IGNORE_CASE)

        /**
         * Split a leading mood tag off a soul render. Returns the mood (null when there is no tag)
         * and the rest, which is then parsed as the decision. The tag must LEAD: a brace later in
         * the text is ordinary content.
         */
        fun split(rendered: String?): Pair<VoiceMood?, String> {
            val t = rendered ?: return null to ""
            val m = TAG.find(t) ?: return null to t
            return valueOf(m.groupValues[1].uppercase()) to t.substring(m.range.last + 1)
        }
    }
}
