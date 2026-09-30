package com.hermesvox

/**
 * ErIntent — the SAFETY classifier (0.8/M3c).
 *
 * What this object is now, and what it deliberately is NOT.
 *
 * It answers exactly two questions, and both are safety rules rather than language
 * judgements:
 *   1. Is this the patient caller — a backchannel that must NEVER escalate or cancel?
 *   2. Is this a genuine interrupt — a redirect that should cancel the mind's turn?
 *
 * Those stay deterministic, enumerable and auditable on purpose. You do not want a 2B model
 * in the abort path, and a rule set that is *supposed* to be finite is correct when it stops
 * growing. Enumerability is a feature here, not a limitation.
 *
 * It NO LONGER decides which lane a turn belongs in. Until this change ErIntent also
 * classified greeting / smalltalk / emotion / information / action against 174 literal
 * substrings and routed smalltalk to the soul lane. That could not converge, and the field
 * proved it inside one afternoon — three misses, each fix breeding the next edge case:
 *   "So how's it going?"       -> information   (a discourse marker hid the greeting)
 *   "hey, what's the weather"  -> smalltalk     (a question rode in behind a greeting)
 *   "how are ya?"              -> information   (a greeting variant the lists lacked)
 * A fourth would have been a fourth patch. Every one of those utterances is now simply the
 * mind's lane until the soul model says otherwise.
 *
 * The judgement moved to the soul: Gemma reads the caller's line with the Contract in front
 * of it and either answers as the soul or emits [ErSoulTurn.ESCALATE]. The lists that
 * decided smalltalk are deleted with the routing.
 *
 * The consequence is honest and visible: every non-backchannel turn is the mind's lane, so
 * the presence ladder always runs and a turn can never be silently skipped by a misroute.
 */
object ErIntent {

    /** HOLD_ONLY = the patient caller, never escalate. ACK_AND_YIELD = the mind's lane. */
    enum class Route { HOLD_ONLY, ACK_AND_YIELD }

    /** BACKCHANNEL = the patient caller. MIND = everything the soul must judge. */
    enum class Class { BACKCHANNEL, MIND }

    /** The decision, with the class that drove it (logged for the telemetry counters). */
    data class Decision(val route: Route, val cls: Class)

    // Backchannel: the smallest, best-bounded class — patient acknowledgments while the mind
    // works. NEVER escalate; the soul just holds. This list is a SAFETY list: it is finite by
    // design, and adding to it is a correctness change, not a language improvement.
    private val BACKCHANNEL_PATTERNS = listOf(
        "take your time", "take ur time", "no rush", "it's okay", "its okay",
        "that's okay", "thats okay", "it's fine", "its fine", "go on", "go ahead",
        "keep going", "mhmm", "mmhmm", "mm-hmm", "uh huh", "uh-huh", "okay",
        "ok ", "ok", "right", "sure", "yes", "yeah", "yep", "mm", "hm", "hmm",
        "i'm listening", "im listening", "still there", "you there",
    )

    // Genuine barge markers: a redirect/stop/new instruction — the mind's current turn is
    // stale. Also a SAFETY list. ErBargeGate owns the cancel/hold verdict.
    private val BARGE_PATTERNS = listOf(
        "stop", "never mind", "nevermind", "actually", "wait", "no wait",
        "forget it", "scratch that", "instead", "what?", "excuse me",
        "let me ask", "i want to ask", "hold on", "cancel",
    )

    /** Normalize for matching: lowercase, collapse spaces, keep punctuation
     *  only when it carries meaning ("what?"). */
    private fun norm(text: String): String {
        val t = text.lowercase().trim()
        return if (t.length <= 3) t else t.replace(Regex("\\s+"), " ")
    }

    /** Backchannel match: the utterance must be (approximately) JUST the backchannel —
     *  whole-string, or a trailing remnant of <3 chars / pure punctuation. Compound
     *  backchannels ("okay, go on") strip the opener and re-check the rest. "okay what's the
     *  weather" is a backchannel OPENER + a real question, and the question must escalate. */
    private fun isBackchannel(t: String): Boolean {
        for (raw in BACKCHANNEL_PATTERNS) {
            val it = raw.trim()
            if (t == it) return true
            val rest = when {
                t.startsWith("$it ") -> t.removePrefix("$it ")
                t.startsWith("$it,") -> t.removePrefix("$it,")
                t.startsWith("$it.") -> t.removePrefix("$it.")
                else -> null
            }?.trim() ?: continue
            val remainder = rest.replace(Regex("[^a-z0-9 ]"), "").trim()
            if (remainder.length < 3) return true
            if (isBackchannel(rest)) return true   // "okay, go on" — opener + backchannel
        }
        return false
    }

    /**
     * The classification. Two outcomes only:
     *  - the patient caller HOLDS (never escalates, never cancels), or
     *  - the turn is the MIND's lane, and whether the soul speaks is the soul's decision.
     *
     * Order still matters for one reason: the backchannel test runs first, because the
     * asymmetry is sacrosanct — a patient "take your time" must never be read as a redirect.
     */
    fun classify(text: String): Decision {
        val t = norm(text)
        if (t.isEmpty()) return Decision(Route.HOLD_ONLY, Class.BACKCHANNEL)
        if (isBackchannel(t)) return Decision(Route.HOLD_ONLY, Class.BACKCHANNEL)
        if (BARGE_PATTERNS.any { t.contains(it) }) return Decision(Route.ACK_AND_YIELD, Class.MIND)
        return Decision(Route.ACK_AND_YIELD, Class.MIND)
    }

    /** Is this utterance a GENUINE BARGE — the mind's current turn should be cancelled?
     *  Default NO on ambiguity (a missed cancel is cheap — the user repeats; a false cancel
     *  regenerates fifteen seconds), and NEVER yes for a backchannel. */
    fun isGenuineBarge(text: String): Boolean {
        val t = norm(text)
        if (isBackchannel(t)) return false
        return BARGE_PATTERNS.any { t.contains(it) }
    }
}
