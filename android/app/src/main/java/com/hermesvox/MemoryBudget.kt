package com.hermesvox

/**
 * MemoryBudget — the knobs this investigation exists to measure, and the ONE place their
 * defaults are written down. Pure JVM, so the policy is testable without a device.
 *
 * **The KV cache never changes by itself.** [kvTokens] returns the shipped 8192 unless a debug
 * pref names a smaller arm of the A/B, and [shouldUnload] is false unless it is switched on. The
 * one default that is a decision rather than a measurement is the idle release: **one minute
 * after hangup** (D27). Presence costs ~2.2 GB of MemAvailable while resident on a Pixel 7
 * (docs/BASELINE.md, "Memory") and returning it costs a 5.6–7.7 s reload; a minute is long
 * enough that a redial inside it stays warm, short enough that a phone left alone gets the RAM.
 *
 * Both knobs are read at ENGINE LOAD, not per render: `maxNumTokens` IS the KV cache, so a
 * new value needs a reload to exist at all. The release policy ([release]) is read at call
 * end instead, because it decides what happens *next*.
 */
object MemoryBudget {

    /** `hv` pref holding the KV-cache size in tokens. Absent = [KV_DEFAULT]. */
    const val PREF_KV_TOKENS = "presence_kv_tokens"

    /** `hv` pref: release the Engine when the call ends / Presence is switched off. */
    const val PREF_UNLOAD = "presence_unload"

    /** `hv` pref (minutes): release the Engine this long after the call ends. 0 = never. */
    const val PREF_UNLOAD_DELAY = "presence_unload_delay_min"

    /**
     * Shipped idle-release delay: **one minute after hangup** (D27). Long enough that a redial
     * inside the window finds the engine warm — the measured reload is 5.6–7.7 s — and short
     * enough that a phone which is not being used gets ~1.75 GB back.
     */
    const val DELAY_DEFAULT = 1

    /** What the dev dialog offers, in minutes. 0 is the off switch; 2 is the arm the
     *  measurement run used — short enough to observe, long enough that a redial inside the
     *  window stays warm. */
    val DELAY_CHOICES = intArrayOf(0, 1, 2, 5, 10, 30)

    /** Past this it is not a delay, it is a typo (12 h). */
    const val MAX_DELAY_MINUTES = 720

    /** The delay a pref names, in minutes. Junk and negatives are [DELAY_DEFAULT] (the shipped
     *  minute), so a corrupt or hand-edited pref cannot remove the release or invent a schedule. */
    fun unloadDelayMinutes(pref: Int): Int =
        if (pref in 0..MAX_DELAY_MINUTES) pref else DELAY_DEFAULT

    /** The same value as a timer delay. 0 means "do not arm a timer". */
    fun unloadDelayMs(pref: Int): Long = unloadDelayMinutes(pref) * 60_000L

    /**
     * What the call-end / presence-off hook should do, given both prefs. One function so the
     * precedence is a tested fact instead of an `if` buried in the activity: the delayed arm
     * wins over unload-on-every-hangup, because a delay exists precisely to stop charging the
     * 7.7 s reload to every redial. Both off is [Release.KEEP] — the shipped behaviour.
     */
    enum class Release { KEEP, ON_HANGUP, AFTER_DELAY }

    fun release(unloadPref: Boolean, delayPref: Int): Release = when {
        unloadDelayMinutes(delayPref) > 0 -> Release.AFTER_DELAY
        shouldUnload(unloadPref) -> Release.ON_HANGUP
        else -> Release.KEEP
    }

    /** The dev dialog's row labels live here so the label and the pref it writes cannot drift
     *  apart, and so a unit test can assert the three KV arms are actually offered. */
    fun kvLabel(tokens: Int): String =
        "$tokens tokens" + if (tokens == KV_DEFAULT) "  (shipped)" else ""

    fun unloadLabel(on: Boolean): String = "Unload on hangup: " + if (on) "on" else "off (shipped)"

    fun delayLabel(minutes: Int): String {
        val m = unloadDelayMinutes(minutes)
        return when {
            m == 0 -> "Idle release: never"
            m == DELAY_DEFAULT -> "Idle release: $m min after hangup  (shipped)"
            else -> "Idle release: $m min after hangup"
        }
    }

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
