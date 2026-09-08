package com.hermesvox

/**
 * ErIntent — the soul-side intent classifier (ER Phase 4), pure JVM.
 *
 * Miles rule #1 (adopted): the controller does early intent classification —
 * emotion | smalltalk | information | action (+ backchannel as the 5th class).
 * Smalltalk/emotion → the soul converses directly. Information/action → the
 * soul acknowledges ("let me think…") and yields content authority to the mind.
 * INVARIANT (sacrosanct): if a response would change the user's external state
 * or teach a concrete fact, it originates from the mind, never the soul.
 * ON AMBIGUITY: default to NON-escalation-blocking — classify toward the mind
 * (the "don't lose a real question" asymmetry), but a backchannel NEVER
 * escalates (the "don't cut the mind on 'take your time'" asymmetry).
 *
 * A keyword classifier, honestly labeled: deterministic, instant, testable —
 * the alpha's presence layer. The Gemma-native classifier is the next step.
 */
object ErIntent {

    enum class Class { BACKCHANNEL, EMOTION, SMALLTALK, INFORMATION, ACTION }

    /** What the classifier tells the presence loop to do. */
    enum class Route { SOUL_DIRECT, ACK_AND_YIELD, HOLD_ONLY }

    /** The decision, with the class that drove it (logged for the miss-rate
     *  telemetry Phase 8 measures). */
    data class Decision(val route: Route, val cls: Class)

    // Backchannel: the smallest, best-bounded class — patient acknowledgments
    // while the mind works. NEVER escalate; the soul just holds.
    private val BACKCHANNEL_PATTERNS = listOf(
        "take your time", "take ur time", "no rush", "it's okay", "its okay",
        "that's okay", "thats okay", "it's fine", "its fine", "go on", "go ahead",
        "keep going", "mhmm", "mmhmm", "mm-hmm", "uh huh", "uh-huh", "okay",
        "ok ", "ok", "right", "sure", "yes", "yeah", "yep", "mm", "hm", "hmm",
        "i'm listening", "im listening", "still there", "you there",
    )

    // Genuine barge markers: a redirect/stop/new instruction — the mind's
    // current turn is stale. (Phase 5 consumes these; the classifier owns them.)
    private val BARGE_PATTERNS = listOf(
        "stop", "never mind", "nevermind", "actually", "wait", "no wait",
        "forget it", "scratch that", "instead", "what?", "excuse me",
        "let me ask", "i want to ask", "hold on", "cancel",
    )

    // Escalation markers: facts, tools, plans — the mind's lane.
    private val INFORMATION_PATTERNS = listOf(
        "what", "when", "where", "who", "which", "why", "how", "how much",
        "how many", "how long", "how far", "is there", "are there", "does",
        "do you know", "can you find", "tell me", "explain", "list", "compare",
        "look up", "search", "find out", "how do i", "what is", "whats",
        "what's", "define", "calculate", "convert", "translate",
    )
    private val ACTION_PATTERNS = listOf(
        "remind me", "set a", "send ", "email ", "text ", "message ", "call ",
        "buy ", "order ", "schedule ", "book ", "create ", "delete ", "remove ",
        "add ", "update ", "change ", "turn on", "turn off", "open ", "close ",
        "run ", "start ", "stop the", "write ", "make me", "install", "deploy",
        "commit", "push ", "restart", "shut down", "shut it down", "reboot",
    )

    // Emotion/smalltalk: the soul's own lane — it converses directly.
    private val EMOTION_PATTERNS = listOf(
        "i feel", "i'm sad", "im sad", "i'm happy", "im happy", "i'm worried",
        "im worried", "i'm scared", "im scared", "i'm tired", "im tired",
        "i'm excited", "im excited", "i'm frustrated", "im frustrated",
        "i love", "i miss", "i'm proud", "im proud", "thank you", "thanks",
        "i'm sorry", "im sorry", "that makes me", "i'm nervous", "im nervous",
    )
    private val SMALLTALK_PATTERNS = listOf(
        "hello", "hi ", "hi!", "hey", "good morning", "good afternoon",
        "good evening", "goodnight", "how are you", "how're you", "how you doing",
        "what's up", "whats up", "nice to meet", "talk to you", "see you",
        "who are you", "what are you", "tell me about yourself", "i like",
    )

    /** Greeting-smalltalk: content-free by construction. Checked BEFORE the
     *  information sweep so "how are you" / "who are you" are never read as
     *  bare "how"/"who" questions. Identity questions belong here too — the
     *  soul answers "who are you" about ITSELF, no mind needed. */
    private val GREETING_PATTERNS = listOf(
        "hello", "hi", "hey", "good morning", "good afternoon", "good evening",
        "goodnight", "how are you", "how're you", "how you doing", "how is it going",
        "how's it going", "what's up", "whats up", "who are you", "what are you",
    )

    /** Normalize for matching: lowercase, collapse spaces, keep punctuation
     *  only when it carries meaning ("what?"). */
    private fun norm(text: String): String {
        val t = text.lowercase().trim()
        return if (t.length <= 3) t else t.replace(Regex("\\s+"), " ")
    }

    /** Backchannel match: the utterance must be (approximately) JUST the
     *  backchannel — whole-string, or a trailing remnant of <3 chars / pure
     *  punctuation. Compound backchannels ("okay, go on") strip the opener
     *  and re-check the rest. "okay what's the weather" is a backchannel
     *  OPENER + a real question, and the question must escalate. */
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

    /** The classification. Order IS the safety design:
     *  1. backchannel first (it must never escalate),
     *  2. genuine barge second (user redirect beats a stale question match),
     *  3. action (escalate — cheap to over-escalate),
     *  4. greeting-smalltalk before the information sweep (so "how are you"
     *     is not swallowed by the bare "how" question marker),
     *  5. information (escalate),
     *  6. emotion/smalltalk remainder (the soul's lane),
     *  7. else: ambiguous → ACK_AND_YIELD (the mind decides; missed smalltalk
     *     costs one flat reply — the cheap miss; the expensive miss is the
     *     soul ANSWERING a real question). */
    fun classify(text: String): Decision {
        val t = norm(text)
        if (t.isEmpty()) return Decision(Route.HOLD_ONLY, Class.BACKCHANNEL)
        if (isBackchannel(t)) return Decision(Route.HOLD_ONLY, Class.BACKCHANNEL)
        if (BARGE_PATTERNS.any { t.contains(it) }) return Decision(Route.ACK_AND_YIELD, Class.ACTION)
        if (ACTION_PATTERNS.any { t.startsWith(it) || t.contains(it) })
            return Decision(Route.ACK_AND_YIELD, Class.ACTION)
        // Greeting-smalltalk is content-free by construction — check it BEFORE
        // the information sweep so "how are you" never reads as a "how" question.
        if (GREETING_PATTERNS.any { t == it.trim() || t.startsWith("$it ") || t.startsWith("$it?") || t.startsWith("$it!") || t.startsWith("$it,") })
            return Decision(Route.SOUL_DIRECT, Class.SMALLTALK)
        if (INFORMATION_PATTERNS.any { t == it.trim() || t.startsWith("$it ") || t.startsWith("$it'") || t.startsWith("$it?") || t.startsWith("$it,") || t.contains(" how ") })
            return Decision(Route.ACK_AND_YIELD, Class.INFORMATION)
        if (EMOTION_PATTERNS.any { t.contains(it) }) return Decision(Route.SOUL_DIRECT, Class.EMOTION)
        if (SMALLTALK_PATTERNS.any { t == it.trim() || t.startsWith(it) })
            return Decision(Route.SOUL_DIRECT, Class.SMALLTALK)
        // Ambiguity default: yield to the mind (see the class doc).
        return Decision(Route.ACK_AND_YIELD, Class.INFORMATION)
    }

    /** Is this utterance a GENUINE BARGE — the mind's current turn should be
     *  cancelled? Phase 5's semantic gate; default NO on ambiguity (a missed
     *  cancel is cheap — the user repeats; a false cancel regenerates 15s). */
    fun isGenuineBarge(text: String): Boolean =
        classify(text).cls == Class.ACTION && BARGE_PATTERNS.any { norm(text).contains(it) }
}
