package com.hermesvox

/**
 * VoiceLoopState — the pure-JVM, emulator-free core of the realtime loop's turn
 * gate + the streaming-STT stable-partial rule.
 *
 * - arm(): start a NEW turn epoch; retires the previous epoch's gate. Only a
 *   fresh arm() re-arms; prior release() latches are discarded.
 * - release(): countDown the active epoch's latch exactly once. Every racing
 *   release (barge-in + settle + error + speak-off) collapses to one real
 *   release (#60). Later releases for the same epoch are no-ops.
 * - mayStart(): the early-turn-start gate. A turn may START once the normalized
 *   partial hypothesis is unchanged since the previous snapshot AND the speaker
 *   has been silent for >= earlySilenceMs — a genuine pause, not a mid-word cut.
 */
class VoiceLoopState(private val earlySilenceMs: Long = 450L) {

    @Volatile private var epoch = 0L
    @Volatile private var releasedForEpoch = false
    @Volatile private var lastNormalized = ""
    @Volatile private var lastPartialAt = 0L

    @Synchronized fun arm() { epoch++; releasedForEpoch = false }

    @Synchronized fun release(): Boolean {
        if (releasedForEpoch) return false
        releasedForEpoch = true
        return true
    }

    @Synchronized fun mayStart(text: String, silentMs: Long, nowMs: Long): Boolean {
        val n = normalize(text)
        val stable = n.isNotBlank() && silentMs >= earlySilenceMs && n == lastNormalized
        if (n != lastNormalized) { lastNormalized = n; lastPartialAt = nowMs }
        return stable
    }

    @Synchronized fun reset() { releasedForEpoch = false; lastNormalized = "" }

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ").trim()
}
