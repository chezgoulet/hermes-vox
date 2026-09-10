package com.hermesvox

/**
 * ErSoulTurn — the contract for the soul's turn decision (0.8/M3c).
 *
 * The soul model is asked ONE question per turn: given what the caller said (and, when it is
 * known, what the mind is currently doing), do you answer it — or is this the mind's? Its own
 * output IS the decision. There are no keyword lists in this path: the routing lists were
 * deleted because a language judgement cannot be enumerated, and the field proved it three
 * times in one afternoon (see docs/DESIGN-enhanced-realtime-voice.md, "the soul decides").
 *
 * Pure JVM by design: the directive is a string and the decision is a string parse, so both
 * are testable off-device. The render itself lives behind [VoxExpress].
 */
object ErSoulTurn {

    /**
     * The escalate token. Deliberately unmistakable, and the parse strips decoration first so
     * a model that wraps it in punctuation or quotes still escalates rather than reading it
     * aloud.
     *
     * Contract safety: the soul never answers facts, tools, actions or planning. A misjudgement
     * is bounded by the Contract the persona already carries — rule 1 forbids inventing facts,
     * rule 4 forbids claiming capabilities it does not have — and the caller can simply ask
     * again. The expensive direction is the soul *answering* something it should not, which is
     * why the directive errs toward escalating on doubt.
     */
    const val ESCALATE = "<<ESCALATE>>"

    /** How long a spoken line stays "recent" for the same-text guard. */
    const val REPEAT_WINDOW_MS = 30_000L

    /** The parsed decision. */
    sealed class Outcome {
        /** Say this. */
        data class Spoken(val text: String) : Outcome()
        /** Say nothing — the mind's answer is the voice. */
        object Escalate : Outcome()
        /** Nothing usable came back: a blank render, or the model was unavailable. */
        object Nothing : Outcome()
    }

    /**
     * The directive rendered as the user turn.
     *
     * [toolContext] is what the mind is currently doing, when that is known. It is what lets
     * the line be topical — *"checking your inbox now"* rather than a generic greeting — and it
     * rides the SAME render as the decision, so the soul's speech never costs a second call.
     */
    fun directive(callerText: String, toolContext: String? = null): String {
        val ctx = if (toolContext.isNullOrBlank()) "" else " The mind is currently working on: $toolContext."
        return "The caller just said: \"$callerText\".$ctx " +
            "If this is a greeting, some smalltalk, or something about how they are feeling, reply with " +
            "ONE short warm sentence in your own voice. " +
            "If answering it needs a fact, a tool, a real-world action, or a plan — or if you are not " +
            "sure — reply with exactly $ESCALATE and nothing else."
    }

    /**
     * Parse the model's output into the decision.
     *
     * Escalation wins on any sign of the token: a wrapped or bare token escalates, and so does a
     * line that merely mentions it. That asymmetry is deliberate — a false escalate costs the
     * mind answering instead, while a missed escalate means the soul spoke when it should not.
     */
    fun parse(rendered: String?): Outcome {
        val t = rendered?.trim().orEmpty()
        if (t.isEmpty()) return Outcome.Nothing
        val bare = t.replace("<", "").replace(">", "")
            .trim('"', '\'', '`', '*', '.', ',', '!', ' ', '\n')
            .uppercase()
        if (bare == "ESCALATE") return Outcome.Escalate
        if (t.uppercase().contains(ESCALATE)) return Outcome.Escalate
        return Outcome.Spoken(t)
    }

    /**
     * The same-text guard: has this line already been said inside [windowMs]?
     *
     * The 09-10 field session spoke ONE sentence seven times in thirty-nine seconds. The defect
     * was never the count of utterances — a person says three different things while working —
     * it was that they were *identical* and blind to time. Sameness is what this catches.
     *
     * [recent] is the caller's ring of (line, timestampMs), pruned by the caller.
     */
    fun isRepeat(
        text: String,
        recent: List<Pair<String, Long>>,
        nowMs: Long,
        windowMs: Long = REPEAT_WINDOW_MS,
    ): Boolean {
        val n = norm(text)
        if (n.isEmpty()) return true   // nothing to say is a repeat of silence
        return recent.any { (said, at) -> nowMs - at <= windowMs && norm(said) == n }
    }

    /** Case- and punctuation-insensitive, so "Hello, Christopher." and "hello christopher"
     *  are recognised as the same line — which is exactly the pair the field produced. */
    private fun norm(s: String): String = s
        .lowercase()
        .replace(Regex("[^a-z0-9 ]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
