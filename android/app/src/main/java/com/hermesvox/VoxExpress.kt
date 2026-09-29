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

    /** Abandon the render in flight, discarding its output (an urgent render is pre-empting it). */
    fun cancelInFlight() {}
}

/** Routing stand-in so the ORCHESTRATION is provable before the model port.
 *  It canonically renders each intent in the persona's voice. When the
 *  LiteRT-LM Gemma backend lands it replaces this (same interface). */
class RoutedExpress : VoxExpress {
    override val available get() = true
    override fun express(intent: String, content: String, tone: String): String = when (intent) {
        // A soul intent's content is a DIRECTIVE to the model, not text to say. The stand-in has
        // no model, so it has nothing to say — echoing the content would speak the directive.
        in VoiceOrchestrator.SOUL_INTENTS -> ""
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

class VoiceOrchestrator(
    private val express: VoxExpress,
    /** Injected so the render scheduling is testable on the JVM (VoxLog needs android.util.Log). */
    private val log: (String) -> Unit = { VoxLog.d(it) },
) {
    var owner: VoiceOwner = VoiceOwner.GEMMA; private set
    var gemmaAvailable: Boolean = true

    /** 0.6.2: async express — the on-device Gemma generation can take hundreds
     *  of ms; it must NEVER run on the main thread (the ANR risk). The render runs on
     *  a daemon thread; the caller's callback receives the glue (or null on
     *  failure/no model) on ITS thread of choice.
     *
     *  0.8/M2.2 made this one-render-in-flight, dropping any request that arrived while
     *  one ran. That was right for narration and wrong for the caller's turn: a turn
     *  decision that landed during a narration render was dropped outright, so the caller
     *  got neither the soul's answer nor its beat. Now there are two classes:
     *   - URGENT ([INTENT_SOUL_TURN]): never dropped. A running render is cancelled
     *     (its output discarded) and the urgent one runs next; a newer urgent request
     *     supersedes an older queued one (latest-wins — only the newest turn matters).
     *   - everything else: dropped while anything is in flight, as before. */
    private val lock = Any()
    private var busy = false
    private var pending: Req? = null

    private class Req(val intent: String, val content: String, val tone: String, val onGlue: (String?) -> Unit)

    fun expressAsync(
        intent: String,
        content: String = "",
        tone: String = "warm",
        onGlue: (String?) -> Unit,
    ) {
        val req = Req(intent, content, tone, onGlue)
        val urgent = intent == INTENT_SOUL_TURN
        var superseded: Req? = null
        var queued = false
        synchronized(lock) {
            if (busy) {
                if (!urgent) {
                    log("event=er-render-drop reason=in-flight intent=$intent")
                    return
                }
                superseded = pending
                pending = req
                queued = true
            } else {
                busy = true
            }
        }
        if (queued) {
            superseded?.onGlue?.invoke(null)
            log("event=er-render-preempt intent=$intent")
            express.cancelInFlight()
            return
        }
        launch(req)
    }

    private fun launch(req: Req) {
        Thread {
            try {
                val glue = try {
                    if (gemmaAvailable) express.express(req.intent, req.content, req.tone) else null
                } catch (_: Throwable) { null }
                req.onGlue(glue)
            } finally {
                val next = synchronized(lock) {
                    val n = pending
                    pending = null
                    if (n == null) busy = false
                    n
                }
                if (next != null) launch(next)
            }
        }.apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }.start()
    }

    companion object {
        /** The caller's turn (and the call-open greeting): the soul's decision + beat. Urgent. */
        const val INTENT_SOUL_TURN = "soul-turn"
        /** Tool narration: the soul may say a line about what the mind is doing, or nothing. */
        const val INTENT_SOUL_NARRATE = "soul-narrate"
        /** Intents whose content is a complete soul directive (ErSoulTurn), parsed by the caller. */
        val SOUL_INTENTS = setOf(INTENT_SOUL_TURN, INTENT_SOUL_NARRATE)
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
