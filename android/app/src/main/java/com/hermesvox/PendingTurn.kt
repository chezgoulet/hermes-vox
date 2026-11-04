package com.hermesvox

/**
 * PendingTurn — the caller's words are never thrown away (F3 of the 2026-11-04 Presence review).
 *
 * The defect, verified in the review: while the mind was working, any speech that did not
 * contain one of the fifteen "barge" phrases was classified `HOLD_MIND`, transcribed only to
 * classify it, and then discarded (`barge-carry seeded=false reason=hold`). After a hold the
 * gate was open again, so the caller's *next* sentence hit `turnInFlight` and was dropped as
 * `suppressed-inflight`. A re-ask, a correction or a follow-up during a twenty-second web
 * search never reached the mind — exactly the "if I ask again, it ignores me" in the field
 * report. Realtime, on the same gateway, cancels and carries.
 *
 * The rule now: a hold with WORDS queues the utterance, and a turn that arrives while another
 * is in flight queues too. When the current turn settles, the queued words are submitted as
 * the next turn. One slot, **newest wins** — a noisy car cannot pile up a backlog, and the last
 * thing the caller said is the thing they meant. Anything that looks stale ([ttlMs]) is
 * dropped rather than answered out of the blue, and a genuinely new turn from the caller
 * clears the slot because their own words supersede a queued echo of them.
 *
 * Pure JVM (no android.*), unit-tested in PendingTurnTest. Thread-safe: offered from the
 * capture/decision thread, taken from the main thread.
 */
class PendingTurn(private val ttlMs: Long = DEFAULT_TTL_MS) {

    private var text: String? = null
    private var atMs = 0L

    /** Queue [said] as the next turn. False when there is nothing to carry. Newest wins: an
     *  earlier pending utterance is replaced, never concatenated (two half-sentences joined
     *  by a 20-second gap are not one question). */
    @Synchronized
    fun offer(said: String?, nowMs: Long): Boolean {
        val t = said?.trim().orEmpty()
        if (t.isEmpty()) return false
        text = t
        atMs = nowMs
        return true
    }

    /** Is something queued right now? (Diagnostics / tests; the age is not checked.) */
    @Synchronized
    fun pending(): Boolean = text != null

    /** The queued utterance if it is still fresh, otherwise null. Does not consume it. */
    @Synchronized
    fun peek(nowMs: Long): String? {
        val t = text ?: return null
        if (fresh(nowMs)) return t
        clear()
        return null
    }

    /** Consume the queued utterance: the next turn takes it exactly once. Null when empty,
     *  stale, or already taken. */
    @Synchronized
    fun take(nowMs: Long): String? {
        val t = peek(nowMs) ?: return null
        clear()
        return t
    }

    /** Drop whatever is queued — a new turn from the caller supersedes it. */
    @Synchronized
    fun clear() {
        text = null
        atMs = 0L
    }

    private fun fresh(nowMs: Long): Boolean =
        // A clock that went backwards is not a stale utterance: hold it rather than answer a
        // question the caller has forgotten.
        nowMs < atMs || nowMs - atMs <= ttlMs

    companion object {
        /** How long queued words stay answerable. Long enough to outlast a slow tool run,
         *  short enough that the caller still remembers saying it. */
        const val DEFAULT_TTL_MS = 60_000L

        /**
         * F3: is the floor free for the queued turn?
         *
         * A queued utterance waits for the CURRENT turn to be over in both senses — the mind's
         * turn finished (`turnInFlight`) and its voice finished (`speaking`, a glue line, or a
         * streaming reply still draining). Starting the queued turn against a reply's tail would
         * talk over the answer the caller is listening to, which is the double-talk the whole
         * design avoids.
         */
        fun floorFree(turnInFlight: Boolean, speaking: Boolean, glueSpeaking: Boolean, streamingReply: Boolean): Boolean =
            !turnInFlight && !speaking && !glueSpeaking && !streamingReply
    }
}
