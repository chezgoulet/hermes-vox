package com.hermesvox

/**
 * PresenceCadence — how often the entity speaks while the mind works, pure JVM.
 *
 * The field finding this answers: on a 76-second tool call the soul said its beat, the filler
 * tick said its one lag line at 4 s, and then the call was silent for 71 seconds. The language
 * was right; the cadence stopped. A person on the phone who is busy for a minute says something
 * every so often — not once.
 *
 * A run is one mind-work window (the caller's turn until the mind's reply). Its lines are
 * numbered from 1: line 1 is the existing fail-soft lag line at [ErFillers.LAG_AFTER_MS] (that
 * 4 s rule is unchanged — nothing is said before it), and every later line is a mid-run soul
 * line, one [Density.intervalMs] after the previous one, until [Density.cap] lines have been
 * taken so a wedged turn cannot talk forever.
 *
 * A slot the soul declines (it may say nothing) still counts as a line: silence was its line,
 * and counting it keeps the render budget at one per interval whatever the soul decides.
 */
object PresenceCadence {

    /**
     * The register the caller chose ("Presence while working"). It governs the mid-run line
     * interval and, through [fillerCap], the existing per-window filler cap.
     */
    enum class Density(val key: String, val intervalMs: Long, val cap: Int, val fillerCap: Int) {
        QUIET("quiet", 30_000L, 3, 1),
        CONVERSATIONAL("conversational", 18_000L, 6, ErFillers.MAX_FILLERS_PER_WINDOW),
        CHATTY("chatty", 10_000L, 12, 3),
    }

    val DEFAULT = Density.CONVERSATIONAL

    /** The pref the register is stored under, and the raw slider it replaces. */
    const val PREF = "er_presence_density"
    const val LEGACY_PREF = "er_filler_cap"

    /**
     * The register from the stored prefs. A stored register wins; otherwise the old filler-cap
     * slider is migrated — 0 (its "silent") → QUIET, 1–2 → CONVERSATIONAL, 3+ → CHATTY. A slider
     * never touched ([legacyCap] null) read as its default of 2, so it maps to the default too.
     */
    fun resolve(stored: String?, legacyCap: Int?): Density {
        Density.entries.firstOrNull { it.key == stored }?.let { return it }
        return when {
            legacyCap == null -> DEFAULT
            legacyCap <= 0 -> Density.QUIET
            legacyCap <= 2 -> Density.CONVERSATIONAL
            else -> Density.CHATTY
        }
    }

    /** One run's state. [IDLE] is "no mind at work" — the state a reply, barge or call end leaves. */
    data class Run(val startedAt: Long = 0L, val lastLineAt: Long = 0L, val linesSaid: Int = 0) {
        /** A line (or a declined slot) was taken at [nowMs]. */
        fun said(nowMs: Long) = copy(lastLineAt = nowMs, linesSaid = linesSaid + 1)

        /** How long since the previous line (or since the mind started, for line 1). */
        fun gap(nowMs: Long) = nowMs - if (linesSaid == 0) startedAt else lastLineAt

        companion object {
            val IDLE = Run()
            fun start(nowMs: Long) = Run(startedAt = nowMs)
        }
    }

    /**
     * Is it time for the next line? Nothing before [ErFillers.LAG_AFTER_MS]; line 1 (the lag
     * line) as soon as that passes; every later line one interval after the last; nothing once
     * the cap is reached or when no mind is at work (`mindStartedAt <= 0`).
     */
    fun shouldSpeak(nowMs: Long, mindStartedAt: Long, lastLineAt: Long, linesSaid: Int, density: Density): Boolean {
        if (mindStartedAt <= 0L) return false
        if (linesSaid >= density.cap) return false
        if (nowMs - mindStartedAt < ErFillers.LAG_AFTER_MS) return false
        if (linesSaid == 0) return true
        return nowMs - lastLineAt >= density.intervalMs
    }

    fun shouldSpeak(nowMs: Long, run: Run, density: Density): Boolean =
        shouldSpeak(nowMs, run.startedAt, run.lastLineAt, run.linesSaid, density)

    /** Has this run used every line it may? */
    fun capped(run: Run, density: Density): Boolean = run.linesSaid >= density.cap
}
