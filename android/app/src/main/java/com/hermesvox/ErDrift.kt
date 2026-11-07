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
 * A2 (2026-11-04 Presence review, F4): the delivery moved out of the user's
 * message. The sync used to be a bracketed epilogue appended to the turn text —
 * protocol text in the caller's mouth, stored in the agent's session history and
 * replayed through `previous_response_id` every turn. It is now part of the
 * request's ephemeral `instructions` ([sync]), which the gateway appends to the
 * system prompt for that request only and never persists.
 *
 * The sync is INSTRUCTIONS ABOUT PRESENCE, not facts: the invariant holds — the
 * soul never originates a fact; this log only tells the mind what the soul
 * ALREADY SAID, so it doesn't repeat or contradict it.
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

    /** The sync for the request's `instructions`. Empty when there is nothing to
     *  sync (non-ER / no soul actions this turn) — the turn's instructions are then
     *  the voice register alone. */
    fun sync(actions: List<SoulAction>, vibe: Vibe): String {
        if (actions.isEmpty() && vibe.lastAck.isBlank() && vibe.mood == "neutral" && vibe.energy == "normal") return ""
        val sb = StringBuilder("The phone's voice has already said to the caller")
        if (actions.isNotEmpty()) {
            sb.append(":")
            for ((i, a) in actions.takeLast(4).withIndex()) {
                if (i > 0) sb.append(";")
                sb.append(" ${a.kind}=\"${a.text.take(60)}\"")
            }
            sb.append(".")
        } else sb.append(".")
        if (vibe.mood != "neutral" || vibe.energy != "normal") {
            sb.append(" The caller sounds mood=${vibe.mood}, energy=${vibe.energy}.")
        }
        // D30: the mind is never told the voice answered, and never offered a way to stay silent.
        // The soul only ever opens a turn; the answer is always the mind's.
        sb.append(" Do not repeat or contradict those lines; answer the question itself.")
        return sb.toString()
    }

    /** Update the vibe from a classified utterance (the classifier's route
     *  is the signal; ErDrift stays pure). */
    fun updateVibe(v: Vibe, route: ErIntent.Route): Vibe = when (route) {
        ErIntent.Route.HOLD_ONLY -> v.copy(mood = v.mood, energy = v.energy)   // patient: no change
        ErIntent.Route.ACK_AND_YIELD -> v.copy(mood = v.mood, energy = v.energy, lastAck = "let me think")
    }

    /** Bounded log: keep the last N actions (the sync quotes 4). */
    fun cap(actions: List<SoulAction>, max: Int = 12): List<SoulAction> =
        if (actions.size <= max) actions else actions.takeLast(max)
}
