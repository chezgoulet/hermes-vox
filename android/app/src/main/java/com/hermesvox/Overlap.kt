package com.hermesvox

/**
 * Overlap — #242: barge-in classifies what it heard, not just its level.
 *
 * The level gate ([BargeGate]) still owns the physical cut: a sound loud and long enough over
 * the reply stops the voice at once, because a missed stop is the thing a caller notices
 * first. What changes is what happens NEXT. The words the barge carried into the next segment
 * ([BargeCarry]) are classified, and each class has its own response:
 *
 *  - [Kind.BACKCHANNEL] — "mm-hm", "right", "go on": the caller was listening, not asking. The
 *    reply is not abandoned: the mind is asked to carry on from the cut, without repeating
 *    what was heard ([Resume.Slot.continueAfterBackchannel]). Nothing is answered.
 *  - [Kind.BARGE] — a word or two ("wait", "hold on", "no"): stop and listen. The being goes
 *    quiet and waits; no turn is sent, and the interrupted answer stays marked so whatever the
 *    caller says next can pick it up.
 *  - [Kind.NEW_TURN] — a full sentence: a new turn, answered, carrying the interruption mark
 *    (#243) so the entity can choose to resume.
 *
 * The two that stop feel different on purpose: a barge is silence and attention, a new turn is
 * an answer. When the evidence is thin the cheaper error wins — an empty or unreadable carry is
 * [Kind.BARGE] (stop and listen), never a turn built from nothing.
 *
 * Behind the Alpha turn-taking set; without it every carried utterance is a turn, as before.
 * The lexicon is [ErIntent]'s backchannel list — one list, one definition. Pure JVM.
 */
object Overlap {

    enum class Kind { BACKCHANNEL, BARGE, NEW_TURN }

    /** A barge is at most this many words; more is a sentence. */
    const val BARGE_MAX_WORDS = 2

    /** The listening sounds however the recogniser spells them: mm-hm, mhm, mm hmm, uh-huh, hmm. */
    private val NONVERBAL = Regex("""^(m+[- ]?h+m*|m+|h+m+|u+h+[- ]?h+u+h+|a+h+a+)$""")

    fun words(text: String): Int =
        text.trim().split(Regex("[^\\p{L}\\p{N}']+")).count { it.isNotEmpty() }

    fun classify(heard: String?): Kind {
        val t = heard?.trim().orEmpty()
        if (t.isEmpty()) return Kind.BARGE
        if (NONVERBAL.matches(t.lowercase().replace(Regex("[^a-z -]"), "").trim())) return Kind.BACKCHANNEL
        if (ErIntent.classify(t).cls == ErIntent.Class.BACKCHANNEL) return Kind.BACKCHANNEL
        return if (words(t) <= BARGE_MAX_WORDS) Kind.BARGE else Kind.NEW_TURN
    }
}
