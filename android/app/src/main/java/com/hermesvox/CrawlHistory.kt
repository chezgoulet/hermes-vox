package com.hermesvox

/**
 * CrawlHistory — the bounded ring behind the crawl's scrollback (#368).
 *
 * Until this existed the crawl kept only the *current* reply: `CrawlView.full` holds the
 * whole in-flight answer, so the start of a long reply survives an interruption and can
 * be reached again, but earlier turns were gone the moment the next one started. This
 * ring is that missing record: the last few finished agent lines, bounded in BOTH
 * entries and characters.
 *
 * Bounded, not the conversation: the phone already runs four models (see
 * `docs/STATE-AUDIT.md` and the House skill's on-device notes), so an unbounded copy of
 * every turn is a memory bug, not a feature. The transcript (`Transcript`, conversation
 * layout) keeps its own 4 000-character record; this is the presence-layout reading
 * trail and deliberately stops at [MAX_ENTRIES] lines / [MAX_CHARS] characters.
 *
 * What goes in:
 *   - [Kind.REPLY]  the agent's finished turn (a soul answer is the turn's reply too);
 *   - [Kind.ASIDE]  a line Presence voiced on its own, marked with [ASIDE] so the
 *                   scrollback reads like the transcript, not like an answer;
 *   - [Kind.SAID]   the caller's own line, replayed from a resumed session's transcript
 *                   (#437) — history the host wrote, not something this app said now.
 * State narration (thinking / listening / tool steps) never enters — the crawl is what
 * the entity said, not what it was doing.
 *
 * Pure JVM: no Android types, so [CrawlScrollbackTest] proves the bounds off-device.
 */
class CrawlHistory(
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxChars: Int = MAX_CHARS,
) {

    enum class Kind { REPLY, ASIDE,
        /**
         * #437: a line from a resumed session's transcript that the caller said. It is not an
         * aside (nothing was voiced on its own) and it is not this app's reply either — it is
         * what the host's transcript holds, drawn in the same trail so the screen matches the
         * session instead of starting blank.
         */
        SAID }

    data class Entry(val text: String, val kind: Kind, val turn: Long) {
        /** The line as the crawl draws it: asides keep the transcript's middle dot. */
        val display: String get() = if (kind == Kind.ASIDE) ASIDE + text else text
    }

    private val ring = ArrayDeque<Entry>()
    private val entryCap = maxEntries.coerceAtLeast(1)
    private val charCap = maxChars.coerceAtLeast(1)

    val size: Int get() = ring.size

    fun entries(): List<Entry> = ring.toList()

    /** Every recorded line, oldest first, in the crawl's own spacing. */
    fun text(): String = ring.joinToString(GAP) { it.display }

    /**
     * Record one finished line. Blank text is ignored (a silent soul answer is not a
     * line). A reply identical to the previous reply **in the same turn** is a duplicate:
     * the soul answered, then the mind echoed it in its final text, and one scrollback
     * line is the truth. A repeated reply in a *new* turn is kept — the caller really did
     * say "yes" twice.
     *
     * Returns true when the ring changed.
     */
    fun add(text: String, kind: Kind, turn: Long): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val last = ring.lastOrNull()
        if (kind == Kind.REPLY && last != null && last.kind == Kind.REPLY &&
            last.turn == turn && last.text == t) return false
        ring.addLast(Entry(t, kind, turn))
        trim()
        return true
    }

    fun clear() = ring.clear()

    private fun trim() {
        while (ring.size > entryCap) ring.removeFirst()
        while (chars() > charCap && ring.isNotEmpty()) {
            if (ring.size > 1) {
                ring.removeFirst()
            } else {
                // One line longer than the whole budget: keep its tail, which is where
                // the answer landed. A line cannot be allowed to exceed the cap.
                val only = ring.first()
                val prefix = only.display.length - only.text.length
                val keep = (charCap - prefix).coerceAtLeast(0)
                if (keep == 0) ring.removeFirst()
                else ring[0] = only.copy(text = only.text.takeLast(keep))
                return
            }
        }
    }

    /** Rendered length, including the separator and an aside's marker. */
    private fun chars(): Int {
        if (ring.isEmpty()) return 0
        var n = GAP.length * (ring.size - 1)
        for (e in ring) n += e.display.length
        return n
    }

    companion object {
        /** How many finished lines the ring keeps. */
        const val MAX_ENTRIES = 24
        /** How many rendered characters the ring keeps. */
        const val MAX_CHARS = 8_000
        /** Same marker the transcript uses for a voiced line that is not the answer. */
        const val ASIDE = "· "
        /** The gap between two recorded lines; [CrawlDocument] uses the same one. */
        const val GAP = "\n\n"
    }
}
