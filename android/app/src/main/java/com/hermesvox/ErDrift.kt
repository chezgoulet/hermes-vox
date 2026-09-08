package com.hermesvox

/**
 * ErDrift — the context-drift sync (ER Phase 7, Miles rule #5), pure JVM.
 *
 * The mind operates on stale input (network + compute lag). Fix: before the
 * mind responds it sees (a) the raw user text and (b) a compact log of SOUL
 * actions since the query (which fillers/acks/paraphrases already covered) so
 * it doesn't re-state confirmations; plus (c) the vibe vector {mood, energy,
 * last_ack} it can condition on.
 *
 * The concrete delivery here: the soul-action log + vibe vector ride the
 * voice turn's user text as a bracketed epilogue (the same per-turn, client-
 * side channel the Phase 1 prefix uses — cache-safe, gateway-agnostic). The
 * epilogue is INSTRUCTIONS ABOUT PRESENCE, not facts: the invariant holds —
 * the soul never originates a fact; this log only tells the mind what the
 * soul ALREADY SAID so it doesn't repeat or contradict it.
 */
object ErDrift {

    /** The vibe vector the soul maintains (Miles rule #5). */
    data class Vibe(
        val mood: String = "neutral",     // neutral | warm | frustrated | anxious
        val energy: String = "normal",    // low | normal | high
        val lastAck: String = "",         // the last thing the soul said (≤60 chars)
    )

    /** One soul action (what the presence layer already voiced). */
    data class SoulAction(val atMs: Long, val kind: String, val text: String)

    /** The epilogue appended to the mind's turn input. Empty when nothing to
     *  sync (non-ER / no soul actions this turn) — the turn text is then
     *  byte-identical to today's. */
    fun epilogue(actions: List<SoulAction>, vibe: Vibe): String {
        if (actions.isEmpty() && vibe.lastAck.isBlank() && vibe.mood == "neutral" && vibe.energy == "normal") return ""
        val sb = StringBuilder(" [soul-sync:")
        if (actions.isNotEmpty()) {
            sb.append(" soul already said:")
            for ((i, a) in actions.takeLast(4).withIndex()) {
                if (i > 0) sb.append(";")
                sb.append(" ${a.kind}=\"${a.text.take(60)}\"")
            }
        }
        if (vibe.mood != "neutral" || vibe.energy != "normal") {
            sb.append(" user vibe: mood=${vibe.mood} energy=${vibe.energy}")
        }
        sb.append(" — do not repeat or contradict the soul's lines; answer the question itself]")
        return sb.toString()
    }

    /** Update the vibe from a classified utterance (the classifier's route
     *  is the signal; ErDrift stays pure). */
    fun updateVibe(v: Vibe, route: ErIntent.Route): Vibe = when (route) {
        ErIntent.Route.SOUL_DIRECT -> v.copy(mood = "warm", energy = "high", lastAck = "")
        ErIntent.Route.HOLD_ONLY -> v.copy(mood = v.mood, energy = v.energy)   // patient: no change
        ErIntent.Route.ACK_AND_YIELD -> v.copy(mood = v.mood, energy = v.energy, lastAck = "let me think")
    }

    /** Bounded log: keep the last N actions (the epilogue takes 4). */
    fun cap(actions: List<SoulAction>, max: Int = 12): List<SoulAction> =
        if (actions.size <= max) actions else actions.takeLast(max)
}
