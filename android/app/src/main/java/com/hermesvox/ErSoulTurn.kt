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

    /** Any spelling of the token: bracketed, half-bracketed or bare, any case. */
    private val TOKEN = Regex("(?i)<{0,2}\\s*escalate\\s*>{0,2}")

    /** How long a spoken line stays "recent" for the same-text guard. */
    const val REPEAT_WINDOW_MS = 30_000L

    /** The parsed decision. */
    sealed class Outcome {
        /** Say this. */
        data class Spoken(val text: String) : Outcome()
        /** Say nothing — the mind's answer is the voice. */
        object Escalate : Outcome()
        /**
         * The mind's turn, opened by the soul: say [opener] now — a few words, the breath a person
         * takes before answering — and let the mind's reply cut in the moment it lands. This is
         * the beat (design §DECISION, A′), GENERATED in the same render as the decision, so it is
         * contextual and never a line from a list.
         */
        data class Beat(val opener: String) : Outcome()
        /** Nothing usable came back: a blank render, or the model was unavailable. */
        object Nothing : Outcome()
    }

    /** What the client is asking the soul for. */
    const val KIND_TURN = "turn"
    const val KIND_GREETING = "greeting"

    /**
     * The call has just connected and the caller has not spoken. Ask the soul to OPEN it.
     *
     * This is the structural fix for the race the field exposed: the soul's mid-turn render
     * (~2.5 s) loses to a healthy gateway's first token (~1.8 s), so on a good connection the
     * soul was being dropped before it could speak. Opening the call has no competitor — the
     * mind has nothing to answer yet — so ER is perceptible from the first second, and turn one
     * is the soul's by construction.
     *
     * "Varied by personality" is the point: the SOUL renders the greeting against its own VOX.md,
     * so it is the entity's hello and not a fixed line. The prohibition is grounded in the field:
     * asked to render with nothing to go on, this model produced "Hello, Christopher. How can I
     * help you today?" — service-desk register. A person greeting someone they know does not ask
     * what they need.
     */
    fun greetingDirective(): String =
        "The phone call has just connected and the caller has not spoken yet. " +
            "Greet them the way you actually would, in your own voice — one short warm sentence, " +
            "under about twenty words. Do not ask what they need and do not offer help; " +
            "just say hello as yourself."

    /**
     * The directive rendered as the user turn.
     *
     * [toolContext] is what the mind is currently doing, when that is known. It is what lets
     * the line be topical — *"checking your inbox now"* rather than a generic greeting — and it
     * rides the SAME render as the decision, so the soul's speech never costs a second call.
     *
     * The mind's-turn branch asks for the BEAT: the escalate token followed by the few words a
     * person says while they start to answer. One render yields both the routing decision and the
     * opening beat, so the soul takes the floor on every turn instead of only on the ones it owns.
     */
    fun directive(callerText: String, toolContext: String? = null): String {
        val ctx = if (toolContext.isNullOrBlank()) "" else " The mind is currently working on: $toolContext."
        return "The caller just said: \"$callerText\".$ctx " +
            "If this is a greeting, some smalltalk, or something about how they are feeling, reply with " +
            "ONE short warm sentence in your own voice, under about twenty words. " +
            "If answering it needs a fact, a tool, a real-world action, or a plan — or if you are not " +
            "sure — reply with $ESCALATE followed by two to six words you would say out loud as you " +
            "start to think about it, in your own voice. Those words must not contain any fact, " +
            "answer, number or promise, and must not repeat an opener you already used."
    }

    /**
     * The mind has started a tool. Offer the soul a beat of narration about it — or silence.
     *
     * The tool is named so the line can be topical, and the soul may decline (the token), because
     * saying nothing is often the right call on a phone.
     */
    fun narrationDirective(tool: String, argsPreview: String = ""): String {
        val what = if (argsPreview.isBlank()) tool else "$tool ($argsPreview)"
        return "The mind has just started using a tool for the caller: $what. " +
            "If a few words about what you are doing for them would feel natural on a call right now, " +
            "say ONE short line in your own voice, under twelve words — what you are doing, never a " +
            "result, a fact or a number. Otherwise reply with exactly $ESCALATE."
    }

    /** Bounds for a beat: long enough to sound human, short enough to be cut cleanly. */
    const val MAX_OPENER_WORDS = 7
    const val MAX_OPENER_CHARS = 60

    /**
     * Sanitise a candidate opener, or null if it cannot be spoken safely. The Contract is enforced
     * here in code, not trusted to a 2B: no digits (a number is a fact), no markup, bounded length.
     * A trailing ellipsis gives the voice a trailing-off prosody, so the cut into the mind's answer
     * sounds like a person continuing rather than being interrupted.
     */
    fun opener(raw: String): String? {
        val line = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        val t = line.trim('"', '\'', '`', '*', '_', ' ', '-', '—', ':')
        if (t.isEmpty() || t.length > MAX_OPENER_CHARS) return null
        if (t.any { it.isDigit() || it == '<' || it == '>' || it == '[' || it == ']' }) return null
        val words = t.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > MAX_OPENER_WORDS) return null
        return if (t.last() in ".!?…") t else "$t..."
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
        // Tolerant token match: a 2B drops or mangles the brackets ("ESCALATE", "<ESCALATE>"),
        // and any spelling of it must route — the phone speaks whatever is left.
        val m = TOKEN.find(t)
        if (m != null) {
            // The beat: the token LEADS (or, from a model that reorders, TRAILS) the opener. A token
            // buried mid-sentence is a model talking about escalating — escalate, say nothing.
            val before = t.substring(0, m.range.first).trim()
            val after = t.substring(m.range.last + 1).trim()
            val candidate = when {
                before.isEmpty() -> after
                after.isEmpty() -> before
                else -> ""
            }
            return opener(candidate)?.let { Outcome.Beat(it) } ?: Outcome.Escalate
        }
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
