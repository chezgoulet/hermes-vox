package com.hermesvox

/**
 * SoulGate — the ER turn's sequencing rules, pure JVM.
 *
 * The soul decides BEFORE the mind's turn is submitted, for as long as that is cheap. Knowing the
 * decision first is what lets the mind be told the truth about the turn: that the voice already
 * answered (so it may stay silent — [MindSkip]), or which opener it used (so it does not repeat
 * it). The warm soul conversation makes a decision a few hundred ms on a phone GPU, and that wait
 * is not dead air: the beat is playing through it.
 *
 * The wait is bounded and adaptive. It never exceeds [MAX_WAIT_MS], and when the soul has been
 * measured slow (a CPU fallback) the gate does not wait at all — the turn goes out immediately
 * and the soul runs in parallel, exactly as before. A late decision is then only allowed to
 * speak a beat, never an answer, because the mind was not told about it.
 */
object SoulGate {
    /** The longest the mind's submit will ever wait on the soul. */
    const val MAX_WAIT_MS = 900L
    /** With no measurement yet (first turns), assume a warm GPU render. */
    const val DEFAULT_WAIT_MS = 700L
    /** Above this measured median the soul is too slow to sequence — run in parallel. */
    const val SLOW_SOUL_P50_MS = 1200L
    /** Scheduling slack on top of the measured median. */
    private const val SLACK_MS = 200L

    /** How long to wait for the soul's decision before submitting the mind's turn. */
    fun waitMs(renderP50Ms: Long?): Long = when {
        renderP50Ms == null || renderP50Ms <= 0 -> DEFAULT_WAIT_MS
        renderP50Ms > SLOW_SOUL_P50_MS -> 0L
        else -> minOf(MAX_WAIT_MS, renderP50Ms + SLACK_MS)
    }

    /** May a soul decision that arrives AFTER the mind's turn was submitted still be spoken?
     *  Only a beat: an answer the mind was not told about is the double answer. */
    fun lateMaySpeak(isBeat: Boolean): Boolean = isBeat
}

/**
 * MindSkip — the mind's way of staying silent when the voice already answered, pure JVM.
 *
 * When the soul answers a turn itself (a greeting, small talk), the mind's turn carries that fact
 * in the soul-sync epilogue and may reply with exactly [TOKEN]. The token must never be heard or
 * shown, and the reply streams token-by-token into the voice, so [Filter] holds back the start of
 * the stream until it can tell the token from a real answer.
 *
 * The mind keeps the right to override (locked decision #1): anything other than the token is a
 * real reply and plays normally. A stock gateway that ignores the instruction just answers, which
 * is today's behaviour — never worse.
 */
object MindSkip {
    const val TOKEN = "<<SKIP>>"

    /** The reply with a leading skip token removed. Blank = the mind chose silence. */
    fun strip(reply: String): String {
        val t = reply.trimStart()
        return if (t.startsWith(TOKEN)) t.removePrefix(TOKEN).trimStart() else reply
    }

    /** Streaming filter: feed each delta, speak/display what it returns. */
    class Filter(private val active: Boolean) {
        private val held = StringBuilder()
        private var decided = !active
        var skipped = false
            private set

        fun feed(delta: String): String {
            if (decided) return delta
            held.append(delta)
            val t = held.trimStart().toString()
            if (t.isEmpty()) return ""
            if (t.length < TOKEN.length && TOKEN.startsWith(t)) return ""   // could still be the token
            decided = true
            if (t.startsWith(TOKEN)) {
                skipped = true
                return t.removePrefix(TOKEN).trimStart()
            }
            return held.toString()
        }

        /** The stream ended while still undecided: release what was held (or nothing). */
        fun finish(): String {
            if (decided) return ""
            decided = true
            val t = held.trim().toString()
            if (TOKEN.startsWith(t)) { skipped = true; return "" }
            return held.toString()
        }
    }
}
