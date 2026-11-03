package com.hermesvox

/**
 * Backchannel — #245: the being acknowledges while it listens.
 *
 * A short nonverbal "mm-hm" (a recorded clip, [ErClips] — never synthesised words, the
 * [ErFillers] hard rule) while the caller is still talking, timed from the continuous
 * capture stream rather than at the endpoint. Pure JVM; the capture loop asks [decide] on
 * every frame and plays the clip itself.
 *
 * When an acknowledgement may land — every condition, none optional:
 *
 *  1. **The caller has been talking a while** — [MIN_TALK_MS] from the utterance's speech
 *     onset. A short turn needs no encouragement.
 *  2. **Inside a pause, not at the boundary.** The pause has run at least [PAUSE_MIN_MS]
 *     (never on top of the caller's own words), and less than [pauseCeilingMs] — a fraction
 *     of the turn-ending silence, so an acknowledgement never lands where the turn ends and
 *     never spends the reply's latency budget (#217/D18).
 *  3. **The thought is visibly unfinished.** The live hypothesis must end on a word that
 *     cannot end a turn ([SemanticEndpoint.finished] is false — "and", "so", "um", "the"…):
 *     the pause after a finished-sounding clause may be the turn ending, and an
 *     acknowledgement there would sit on the boundary. With semantic endpointing on, the
 *     pause must also be judged [SemanticEndpoint.Verdict.CONTINUE].
 *  4. **The rate allows it** ([Rate]): a minimum gap and a per-minute cap. Silence is a valid
 *     output and the usual one.
 *  5. **Nothing else is speaking** — no reply in flight, no greeting, no clip already playing.
 *
 * An acknowledgement must not extend the caller's turn: the clip itself (and its echo) would
 * read as speech and reset the pause clock. For [MUTE_MS] after one fires the capture loop
 * keeps the frames but does not let the VAD reset the silence count ([vadMuted]), so the
 * endpoint arrives exactly when it would have without the clip.
 *
 * Behind the Alpha gate's turn-taking set ([AlphaGate.FeatureSet.TURN_TAKING]) and continuous
 * capture (#228), whose 100 ms cadence is what makes "a pause inside an utterance" observable.
 *
 * Timing is fitted to English; the research note (RESEARCH-2026-10 §5) is clear that
 * backchannel timing does not transfer between languages, so this policy says nothing about
 * any other.
 */
object Backchannel {

    const val KEY_RATE = "backchannel_rate"

    /** How often the being may acknowledge. [OFF] is a real choice, not a failure. */
    enum class Rate(val key: String, val label: String, val minGapMs: Long, val perMinute: Int) {
        OFF("off", "Off", Long.MAX_VALUE, 0),
        SPARSE("sparse", "Sparse", 20_000L, 2),
        NORMAL("normal", "Normal", 10_000L, 4);

        companion object {
            fun of(key: String?): Rate = entries.firstOrNull { it.key == key } ?: DEFAULT
        }
    }

    /** Inside the opt-in set the rate starts sparse — the set itself is the opt-in. */
    val DEFAULT = Rate.SPARSE

    const val MIN_TALK_MS = 6_000L
    const val PAUSE_MIN_MS = 250L
    const val PAUSE_MAX_MS = 500L
    /** The clip (≤ ~0.6 s) plus its echo tail. */
    const val MUTE_MS = 900L

    /** The pause an acknowledgement must start inside: well short of the turn-ending
     *  silence, so it can never be the boundary. */
    fun pauseCeilingMs(endSilenceMs: Long): Long = minOf(PAUSE_MAX_MS, endSilenceMs * 6 / 10)

    /** Everything [decide] needs, as plain values. */
    data class Moment(
        val nowMs: Long,
        /** When this utterance's speech began; 0 = not in speech. */
        val speechStartAt: Long,
        /** The pause so far (ms since the last speech frame). */
        val silentMs: Long,
        /** The silence that ends a turn (the user's `vad_silence_ms`). */
        val endSilenceMs: Long,
        /** The live hypothesis so far ("" = none yet). */
        val partial: String,
        /** The semantic endpointer's verdict on this pause, or null when it is off. */
        val semantic: SemanticEndpoint.Verdict?,
        /** A reply, greeting or another clip is sounding. */
        val othersSpeaking: Boolean,
    )

    /** Call-scoped memory: what has already been said, for the gap and the cap. */
    class Ledger {
        private val fired = ArrayDeque<Long>()
        var mutedUntil = 0L
            private set

        fun recent(nowMs: Long): Int {
            while (fired.isNotEmpty() && nowMs - fired.first() >= 60_000L) fired.removeFirst()
            return fired.size
        }

        fun last(): Long = fired.lastOrNull() ?: Long.MIN_VALUE / 2

        fun record(nowMs: Long) {
            fired.addLast(nowMs)
            mutedUntil = nowMs + MUTE_MS
        }
    }

    /** Why an acknowledgement did or did not fire — for tests and the one log line. */
    enum class Why { FIRE, OFF, NOT_TALKING, TOO_SOON_IN_TURN, NO_PAUSE, AT_BOUNDARY, READS_FINISHED, LEANING_FINAL, BUSY, GAP, CAP }

    fun decide(m: Moment, rate: Rate, ledger: Ledger): Why {
        if (rate == Rate.OFF) return Why.OFF
        if (m.speechStartAt <= 0L) return Why.NOT_TALKING
        if (m.othersSpeaking) return Why.BUSY
        if (m.nowMs - m.speechStartAt - m.silentMs < MIN_TALK_MS) return Why.TOO_SOON_IN_TURN
        if (m.silentMs < PAUSE_MIN_MS) return Why.NO_PAUSE
        if (m.silentMs >= pauseCeilingMs(m.endSilenceMs)) return Why.AT_BOUNDARY
        if (m.partial.isBlank() || SemanticEndpoint.finished(m.partial)) return Why.READS_FINISHED
        if (m.semantic != null && m.semantic != SemanticEndpoint.Verdict.CONTINUE) return Why.LEANING_FINAL
        // The gap (≥ 10 s) is far longer than any qualifying pause: one pause, one at most.
        if (m.nowMs - ledger.last() < rate.minGapMs) return Why.GAP
        if (ledger.recent(m.nowMs) >= rate.perMinute) return Why.CAP
        return Why.FIRE
    }

    /** Fire-and-record in one step; the caller plays the clip on [Why.FIRE]. */
    fun tryFire(m: Moment, rate: Rate, ledger: Ledger): Boolean {
        if (decide(m, rate, ledger) != Why.FIRE) return false
        ledger.record(m.nowMs)
        return true
    }

    /** While true the VAD may not reset the pause clock: the clip is not the caller. */
    fun vadMuted(nowMs: Long, ledger: Ledger): Boolean = nowMs < ledger.mutedUntil
}
