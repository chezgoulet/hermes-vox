package com.hermesvox

/**
 * SoulBudget — when the soul's rolling conversation must rotate, pure JVM.
 *
 * The soul keeps one warm LiteRT-LM conversation so the persona is prefilled once instead of on
 * every render (GemmaExpress). The engine's KV cache is fixed at [MAX_TOKENS]
 * (`EngineConfig.maxNumTokens`), so the conversation must be replaced before a render could run
 * out of room: the next directive (~100 tokens) plus a full-length reply (256) must always fit.
 * Rotating costs one persona prefill, roughly once every sixty turns.
 */
object SoulBudget {
    /** Mirrors GemmaExpress's EngineConfig.maxNumTokens. */
    const val MAX_TOKENS = 8192

    /** Room kept free for the next directive + the longest reply, with margin. */
    const val HEADROOM_TOKENS = 1024

    fun shouldRotate(tokenCount: Int, maxTokens: Int = MAX_TOKENS): Boolean =
        tokenCount < 0 || tokenCount >= maxTokens - HEADROOM_TOKENS
}
