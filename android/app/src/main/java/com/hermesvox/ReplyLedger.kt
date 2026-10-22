package com.hermesvox

/**
 * ReplyLedger — the cancel lifecycle of a reply, per turn id (#240).
 *
 * `genCancelled` is one flag for the whole controller and is reset when the next turn
 * starts, so it cannot answer "does this audio belong to a reply that was cut?" once the
 * next turn is running — which is how the field log carried 1.77 s of a cut reply into the
 * next turn. This ledger answers it per id, with the four rules from
 * `docs/NOTES-voicemanager.md` §3:
 *
 *  1. exactly **one** end per id — [end] returns true once, and never for an id that
 *     did not begin;
 *  2. **no audio after the end** — [accepts] is false for an ended id, done or cancelled;
 *  3. a cancelled id is **permanently dead** for the session — the ledger keeps a bounded
 *     window of recent ids, and every id older than the window is dead too (turn ids are
 *     monotonic), so a frame already on the wire cannot reopen it;
 *  4. stopping playback is the caller's (`silenceAll`), and happens at the cancel, not at
 *     any later acknowledgement — the ledger is the discard rule, not the stop signal.
 *
 * Pure JVM, so the rules are proven off-device.
 */
internal class ReplyLedger(private val capacity: Int = 32) {

    enum class End { DONE, CANCELLED }

    private val open = HashSet<Long>()
    private val ended = LinkedHashMap<Long, End>()
    /** Every id at or below this has left the window and is dead. */
    private var floor = Long.MIN_VALUE

    /** A reply for [id] started. A re-begin of an ended or evicted id is refused. */
    @Synchronized fun begin(id: Long): Boolean {
        if (id <= floor || ended.containsKey(id)) return false
        return open.add(id)
    }

    /** End [id]. True exactly once, for an id that began; every later call is false. */
    @Synchronized fun end(id: Long, how: End): Boolean {
        if (!open.remove(id)) return false
        ended[id] = how
        while (ended.size > capacity) {
            val oldest = ended.keys.first()
            ended.remove(oldest)
            if (oldest > floor) floor = oldest
        }
        return true
    }

    /** May audio for [id] still play? Only while it is open. */
    @Synchronized fun accepts(id: Long): Boolean = id > floor && open.contains(id)

    /** Was [id] cut (or has it left the window)? */
    @Synchronized fun isDead(id: Long): Boolean = id <= floor || ended[id] == End.CANCELLED
}
