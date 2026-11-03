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
     * `hv` pref: bring the engine up WITH its audio encoder. **Shipped default true, because
     * that is what the brain does today** (`GemmaExpress.initEngine` tries the audio path
     * first and falls back to text-only only if it cannot start). The soul's ears are what
     * cost the `static_audio_encoder` + `audio_adapter` models and their XNNPack weight
     * caches; off, they are never built. Measured cost is in docs/PRESENCE-COST.md.
     */
    const val PREF_HEARS = "presence_hears"

    /**
     * `hv` pref: load the presence model at launch. **Shipped default true** — the engine is
     * resident from the first resume, which is today's behaviour. False is the deferred arm:
     * nothing loads until a call starts, so the app idles at ~170 MB and the call pays the
     * load (the call path already gates on `soulReady` and offers a skip after a wait).
     */
    const val PREF_LAZY_LOAD = "presence_lazy_load"

    /**
     * `hv` pref (minutes): release the engine after this long with **no live call while the
     * app stays open**. 0 = never, and **0 is shipped**: the existing [PREF_UNLOAD_DELAY] arm
     * only arms on hangup, so an app left open on the table keeps the ~2.7 GB forever
     * (docs/PRESENCE-COST.md, "at rest"). This is the arm that closes that hole; the reload it
     * charges is the same cold load a process death pays.
     */
    const val PREF_IDLE_OPEN_MIN = "presence_idle_open_min"

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

    /**
     * The "release while the app is open" delay, in minutes. **Absent, 0 and junk all mean
     * never**, which is the shipped behaviour: unlike [unloadDelayMinutes] there is no default
     * minute to fall back to, because this arm is not on until someone turns it on.
     */
    fun idleOpenMinutes(pref: Int?): Int =
        if (pref != null && pref in 1..MAX_DELAY_MINUTES) pref else 0

    /** The same value as a timer delay. 0 means "do not arm a timer". */
    fun idleOpenMs(pref: Int?): Long = idleOpenMinutes(pref) * 60_000L

    /** Whether the engine is built with its audio encoder. Absent pref = yes (shipped). */
    fun hears(pref: Boolean?): Boolean = pref ?: true

    /** Whether the engine loads at launch. Absent pref = yes (shipped). */
    fun lazyLoad(pref: Boolean?): Boolean = pref ?: false

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

    /** The hearing arm's row label. Off is the measurement arm: the engine is built text-only. */
    fun hearsLabel(on: Boolean): String =
        "Hearing (audio encoder): " + if (on) "on (shipped)" else "off — text only"

    /** The deferred-load arm's row label. Off is "load at launch", the shipped behaviour. */
    fun lazyLabel(on: Boolean): String =
        "Load at launch: " + if (on) "on (shipped)" else "off — load when a call starts"

    /** The release-while-open row label. 0 is "never", the shipped behaviour. */
    fun idleOpenLabel(minutes: Int): String {
        val m = idleOpenMinutes(minutes)
        return if (m == 0) "Release while open: never (shipped)"
        else "Release while open: $m min with no call"
    }

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
