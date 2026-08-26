package com.hermesvox

/**
 * VoxExpress — the on-device "phone-call presence" layer (Gemma 4 E2B).
 *
 * Per the Hermes Vox architecture contract (see the skill reference):
 *   - Gemma NEVER runs the agentic stream — it only EXPRESSES content Hermes
 *     pushes, as natural moment-to-moment conversation.
 *   - Gemma holds the floor by default (acknowledgments/narration/back-channels).
 *   - Hermes TRUMPS Gemma whenever it has a real call (precedence rule).
 *
 * This is the PLUGGABLE interface: the routing stand-in works today (proves the
 * orchestration); the LiteRT-LM Gemma-4-E2B backend plugs in on-device later.
 */
interface VoxExpress {
    /** Render a Hermes-pushed content directive into natural conversational
     *  expression (the persona voice). intent: answer|acknowledge|working|
     *  notification|app_blessing. tone: calm|warm|urgent|etc. */
    fun express(intent: String, content: String, tone: String = "warm"): String

    /** True when the on-device expression model is actually available. */
    val available: Boolean
}

/** Routing stand-in so the ORCHESTRATION is provable before the model port.
 *  It canonically renders each intent in the persona's voice. When the
 *  LiteRT-LM Gemma backend lands it replaces this (same interface). */
class RoutedExpress : VoxExpress {
    override val available get() = true
    override fun express(intent: String, content: String, tone: String): String = when (intent) {
        "acknowledge" -> when (tone) {
            "warm" -> "Mm — got it, I'm on it."
            else -> "Right."
        }
        "working" -> when (tone) {
            "calm" -> "Just a sec — let me look that up."
            else -> "Let me dig into that... one sec."
        }
        "answer" -> "Right. $content"
        "notification" -> "Heads up — $content"
        else -> content
    }
}

/**
 * The orchestration / precedence rule. Device-side state machine for the
 * phone-call presence:
 *   - Gemma holds the floor by default (NARRATION).
 *   - Hermes (a real call) PREEMPTS Gemma (AUTHORITATIVE); then hands back.
 *   - The user's voice (barge-in) interrupts both.
 */
enum class VoiceOwner { GEMMA, HERMES }

class VoiceOrchestrator(private val express: VoxExpress) {
    var owner: VoiceOwner = VoiceOwner.GEMMA; private set
    var gemmaAvailable: Boolean = true

    /** The user spoke — Gemma acknowledges/narrates (holds the floor). */
    fun onUserSpeech(): String? {
        owner = VoiceOwner.GEMMA
        return if (gemmaAvailable) express.express("acknowledge", "", "warm") else null
    }

    /** Hermes is mid-work (a tool call / thinking) — Gemma narrates the work. */
    fun onWorkNarration(): String? {
        return if (gemmaAvailable) express.express("working", "", "calm") else null
    }

    /** Hermes produces a SUBSTANTIVE directive/content — PREEMPTS Gemma. */
    fun onHermesDirective(intent: String, content: String, tone: String): String {
        owner = VoiceOwner.HERMES
        return express.express(intent, content, tone)
    }

    /** Hermes's authoritative reply (the real answer) — trumps Gemma, then the
     *  floor returns to Gemma once delivered. */
    fun onHermesReply(finalText: String): Pair<String, VoiceOwner> {
        owner = VoiceOwner.HERMES
        return finalText to owner
    }

    fun handBack() { owner = VoiceOwner.GEMMA }

    /** The user interrupted — cut whoever holds the floor + reset. */
    fun onBargeIn() { owner = VoiceOwner.GEMMA }
}
