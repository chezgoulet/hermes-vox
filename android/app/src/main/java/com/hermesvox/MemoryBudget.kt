package com.hermesvox

/**
 * MemoryBudget — the knobs this investigation exists to measure, and the ONE place their
 * defaults are written down. Pure JVM, so the policy is testable without a device.
 *
 * **No shipped default changes here.** [kvTokens] returns the shipped 8192 unless a debug
 * pref names a smaller arm of the A/B, and [shouldUnload] is false unless it is switched on.
 * Presence costs ~2.2 GB of MemAvailable while resident on a Pixel 7 (docs/BASELINE.md,
 * "Memory"); whether to trade that for a 5.6–7.0 s reload is the owner's call, so the code
 * only makes the trade measurable — it never takes it.
 *
 * Both knobs are read at ENGINE LOAD, not per render: `maxNumTokens` IS the KV cache, so a
 * new value needs a reload to exist at all.
 */
object MemoryBudget {

    /** `hv` pref holding the KV-cache size in tokens. Absent = [KV_DEFAULT]. */
    const val PREF_KV_TOKENS = "presence_kv_tokens"

    /** `hv` pref: release the Engine when the call ends / the mode leaves Enhanced. */
    const val PREF_UNLOAD = "presence_unload"

    /**
     * The shipped cache (GemmaExpress's `EngineConfig.maxNumTokens`, mirrored by
     * `SoulBudget.MAX_TOKENS`) and the two arms the A/B compares it against.
     * 8k exists for a growing VOX.md (~600 tokens) plus a rolling conversation; 2k still
     * leaves the persona, a rotation window and a 256-token reply.
     */
    val KV_CHOICES = intArrayOf(2048, 4096, 8192)

    /** Shipped today. A phone with no pref set must behave exactly as 0.9.1 does. */
    const val KV_DEFAULT = 8192

    /** The cache to build the engine with. Anything unrecognised is the shipped value — a
     *  corrupt or hand-edited pref cannot silently halve the soul's memory mid-session. */
    fun kvTokens(pref: Int): Int = if (pref in KV_CHOICES) pref else KV_DEFAULT

    /** Phase 4: unload on call end / mode leave. Off unless explicitly enabled. */
    fun shouldUnload(pref: Boolean): Boolean = pref

    /**
     * Whether a conversation using [tokenCount] of a [maxTokens] cache must rotate now.
     * Delegates to [SoulBudget] so there is one rotation rule and it always matches the
     * cache the engine was actually built with — a 2048 cache with an 8192 rotation
     * threshold would run out of room and fail the render instead of rotating.
     */
    fun mustRotate(tokenCount: Int, maxTokens: Int): Boolean =
        SoulBudget.shouldRotate(tokenCount, maxTokens)
}
