package com.hermesvox

/**
 * VoiceTurn — what a voice turn tells the mind, and how.
 *
 * A2 / F4 of the 2026-11-04 Presence review. Until this change every voice turn carried a
 * bracketed prefix glued onto the caller's own words:
 *
 *     [Voice input — the app adds its own spoken acknowledgments like "let me think" … Answer
 *      from what you know; do NOT do exhaustive tool work or re-audit your own docs/state
 *      unless the caller explicitly asks.] <the caller's words>
 *
 * Three things were wrong with that, and the field proves all three:
 *
 *  - **It was in the user's message**, so it entered the agent's stored session history, was
 *    replayed through `previous_response_id`, showed up in memory and session search, and cost
 *    ~100 tokens of context growth per turn. The agent's own transcript read like a protocol.
 *  - **It told the mind not to use its tools.** "Current price of a second-hand 4090" is
 *    exactly the question that clause steers to a stale answer or a refusal, and Presence — the
 *    layer that is supposed to be the same agent — was measurably worse at it than Realtime,
 *    which sends the bare text.
 *  - Parts of it were no longer true ("the app adds its own spoken acknowledgments" — the ack is
 *    silence-first since 0.6.7).
 *
 * The register now rides the request's ephemeral `instructions` field, which the gateway appends
 * to the system prompt for THAT request only and never persists
 * (`gateway/platforms/api_server_response_admissions.py`: `ephemeral_system_prompt`). The
 * caller's words go on the wire untouched. The mind keeps every tool it has.
 *
 * This is a pure object so the contract is unit-tested off-device (VoiceTurnTest).
 */
object VoiceTurn {

    /**
     * The per-request reasoning effort for voice turns — the user's choice, defaulting to
     * [com.hermesvox.core.ResponsesClient.DEFAULT_EFFORT] (`low`). The gateway supports it per
     * request (`model_options.reasoning`); a provider that ignores it behaves as before. It is
     * the same discovery the field reporter made by ear — a zippier answer beats a deeper one
     * in a spoken turn — without changing the model.
     */
    const val PREF_REASONING = "voice_reasoning_effort"

    /**
     * The voice register: how to speak, and nothing about what to be able to do.
     *
     * Deliberately absent:
     *  - any instruction about tools. The mind is the agent; the phone is a loudspeaker.
     *  - "the app says its own acknowledgments" — untrue since 0.6.7, and an invitation to
     *    double-talk now that the beat is rendered separately.
     *  - a greeting rule that fights the soul's greeting (the soul opens the call).
     */
    const val REGISTER =
        "You are speaking out loud on a phone call. Reply in two to four sentences of plain " +
            "speech — no code, no markdown, no lists."

    /**
     * The `instructions` for one voice turn: the register, plus the presence context when the
     * caller has something to say about it ([contexts] — the soul-sync from [ErDrift.sync], the
     * phone's state from [PresenceContext]). Empty contexts leave the register alone.
     */
    fun instructions(vararg contexts: String): String {
        val extra = contexts.map { it.trim() }.filter { it.isNotEmpty() }
        if (extra.isEmpty()) return REGISTER
        return (listOf(REGISTER) + extra).joinToString("\n\n")
    }
}
