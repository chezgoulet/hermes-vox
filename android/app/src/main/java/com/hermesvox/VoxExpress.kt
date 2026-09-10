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

    /** 0.6.2: async express — the on-device Gemma generation can take hundreds
     *  of ms (runBlocking inside GemmaExpress.express); it must NEVER run on the
     *  main thread (the UI callback that drives narration lives there — the ANR
     *  risk). The render runs on a daemon thread; the caller's callback receives
     *  the glue (or null on failure/no model) on ITS thread of choice. The
     *  fallback (RoutedExpress) is instant, so the async hop costs ~nothing. */
    /**
     * 0.8/M2.2: at most ONE render in flight — latest-wins, matching speakGlue's own
     * "latest narration wins" contract.
     *
     * Each render is a full prefill of the persona (LiteRT-LM's Conversation exposes
     * no reset, so every express() builds a fresh conversation), and renders SERIALIZE
     * inside one Engine. Overlapping requests therefore cost N generations of wall
     * time and yield at most one usable line: the 09-10 field log measured 18221ms and
     * 24401ms for renders whose uncontended cost is ~2200-3200ms. A request arriving
     * while one is running is dropped outright.
     */
    private val renderInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    fun expressAsync(
        intent: String,
        content: String = "",
        tone: String = "warm",
        onGlue: (String?) -> Unit,
    ) {
        if (!renderInFlight.compareAndSet(false, true)) {
            VoxLog.d("event=er-render-drop reason=in-flight")
            return
        }
        Thread {
            try {
                val glue = try {
                    if (gemmaAvailable) express.express(intent, content, tone) else null
                } catch (_: Throwable) { null }
                onGlue(glue)
            } finally {
                renderInFlight.set(false)
            }
        }.apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }.start()
    }

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
