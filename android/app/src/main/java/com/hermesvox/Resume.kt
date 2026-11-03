package com.hermesvox

/**
 * Resume — #243: after an interruption, resume the step, address it, do not repeat.
 *
 * The reply becomes a record rather than a stream: when a voice barge cuts it, the controller
 * keeps a [Mark] — what the caller actually heard (the playback cursor's frozen position, not
 * what had been written to the track), and what was written but never heard. The next voice
 * turn carries one plain directive built from it ([directive]), so the entity — not the
 * transport — decides whether to answer the interjection and pick the old answer back up, or
 * drop it.
 *
 * The directive says it straight: you were interrupted, here is where, answer them first,
 * then continue without repeating what they heard, or drop it if they changed course. No
 * persona, no hedging; it is a fact about the call, like the voice prefix.
 *
 * One mark at a time, used once, and only for [TTL_MS]: an interruption from minutes ago is
 * not context the caller expects to be picked up. A hush (the user's own stop) and a hang-up
 * are not interruptions and leave no mark.
 *
 * Behind the Alpha turn-taking set. Pure JVM.
 */
object Resume {

    const val TTL_MS = 120_000L
    /** How much of each side the directive quotes: enough to place the cut, not a replay. */
    const val HEARD_TAIL_CHARS = 160
    const val UNSAID_CHARS = 400

    /** The cut barge reasons [VoiceController.silenceAll] is called with. A hush or stop is not one. */
    val BARGE_REASONS = setOf("barge-in", "er-barge-toggle-off", "er-barge-cancel")

    data class Mark(
        /** What the caller heard, in full. */
        val heard: String,
        /** What the entity had written and the caller did not hear ("" = nothing). */
        val unsaid: String,
        /** Whether the entity had finished writing its answer when it was cut. */
        val finished: Boolean,
        val atMs: Long,
    )

    /**
     * The record of a cut reply. [spoken] is the text handed to the voice (the cursor's index
     * space), [spokenChars] how much of it played, [assembled] everything the entity wrote.
     * Null when there was nothing to interrupt.
     */
    fun mark(spoken: String, spokenChars: Int, assembled: String, finished: Boolean, atMs: Long): Mark? {
        val heard = spoken.take(spokenChars.coerceIn(0, spoken.length)).trim()
        val unsaid = unsaidAfter(heard, assembled.ifBlank { spoken })
        if (heard.isEmpty() && unsaid.isEmpty()) return null
        return Mark(heard, unsaid, finished, atMs)
    }

    /**
     * What follows [heard] in [full]. The voice's text and the entity's text are the same words
     * but not always the same characters (markup stripped, whitespace folded), so the cut is
     * placed by matching the heard tail on folded whitespace; with no match it falls back to the
     * character count, which is right whenever the two are identical.
     */
    internal fun unsaidAfter(heard: String, full: String): String {
        if (heard.isEmpty()) return full.trim()
        val f = fold(full)
        val h = fold(heard)
        if (f.startsWith(h)) return f.substring(h.length).trim()
        val tail = h.takeLast(40)
        val at = f.indexOf(tail)
        if (at >= 0) return f.substring(at + tail.length).trim()
        return if (h.length < f.length) f.substring(h.length).trim() else ""
    }

    private fun fold(s: String) = s.replace(Regex("\\s+"), " ").trim()

    /** The note the next voice turn carries, or null when the mark has expired. */
    fun directive(m: Mark, nowMs: Long): String? {
        if (nowMs - m.atMs > TTL_MS) return null
        val heard = if (m.heard.isEmpty()) "nothing yet — you were cut off before your first words"
            else "\"" + (if (m.heard.length > HEARD_TAIL_CHARS) "…" + m.heard.takeLast(HEARD_TAIL_CHARS).trimStart() else m.heard) + "\""
        val unsaid = when {
            m.unsaid.isNotEmpty() -> "They did not hear: \"" +
                (if (m.unsaid.length > UNSAID_CHARS) m.unsaid.take(UNSAID_CHARS).trimEnd() + "…" else m.unsaid) + "\"" +
                (if (m.finished) "" else " — and you had not finished writing it") + ". "
            m.finished -> "They heard all of it. "
            else -> "You had not finished your answer. "
        }
        return "[You were interrupted. The caller heard you say: $heard. $unsaid" +
            "Answer what they just said first. Then, if your earlier answer still matters, pick " +
            "it up where they cut in — do not repeat what they already heard. If what they said " +
            "changes or ends that task, drop it.] "
    }

    /**
     * #242: the caller's barge was only a listening sound. Nothing was asked: carry on from the
     * cut. Null when there is nothing left to say — then the sound needs no turn at all.
     */
    fun continuation(m: Mark, nowMs: Long): String? {
        if (nowMs - m.atMs > TTL_MS) return null
        if (m.unsaid.isEmpty() && m.finished) return null
        val said = if (m.heard.isEmpty()) "nothing yet"
            else "\"" + (if (m.heard.length > HEARD_TAIL_CHARS) "…" + m.heard.takeLast(HEARD_TAIL_CHARS).trimStart() else m.heard) + "\""
        val rest = if (m.unsaid.isEmpty()) "" else " You still had to say: \"" +
            (if (m.unsaid.length > UNSAID_CHARS) m.unsaid.take(UNSAID_CHARS).trimEnd() + "…" else m.unsaid) + "\"."
        return "[The caller only made a listening sound while you were talking — they asked nothing. " +
            "They heard you say: $said.$rest Carry on from exactly there. Do not repeat what they " +
            "heard, do not answer the sound, and do not start over.] "
    }

    /** One mark, used once. Thread-safe: the cut lands on main, the turn on the executor. */
    class Slot {
        private var mark: Mark? = null
        private var continuing = false
        @Synchronized fun put(m: Mark?) { mark = m; continuing = false }
        @Synchronized fun clear() { mark = null; continuing = false }
        @Synchronized fun has(nowMs: Long): Boolean = mark?.let { nowMs - it.atMs <= TTL_MS } ?: false

        /** #242: the next turn continues the cut reply instead of answering an interjection.
         *  False when there is nothing to continue (no mark, stale, or nothing unsaid). */
        @Synchronized fun continueAfterBackchannel(nowMs: Long): Boolean {
            val m = mark ?: return false
            if (continuation(m, nowMs) == null) return false
            continuing = true
            return true
        }

        /** The directive for the next voice turn, consuming the mark either way. */
        @Synchronized fun take(nowMs: Long): Pair<Mark, String>? {
            val m = mark ?: return null
            val cont = continuing
            mark = null; continuing = false
            val d = (if (cont) continuation(m, nowMs) else directive(m, nowMs)) ?: return null
            return m to d
        }
    }
}
