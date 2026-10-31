package com.hermesvox

/**
 * #223 — the persistent transcript (`layout_mode=conversation`): the surface a caller
 * reads when they cannot follow the fading crawl. It is a record of what was SAID —
 * the caller's words, the entity's answer, and the lines the Presence layer voices on
 * its own (its answer, its beats, its working asides, its greeting).
 *
 * What stays OUT is the state narration: thinking / speaking / listening, tool events,
 * operation steps and the status pill. A transcript that narrates every state change is
 * noise; the one thing it must never drop is the reply, which is the bug this class
 * fixes. Presence lines that are not the turn's answer are marked [ASIDE] so a reader
 * can tell the entity's answer from the entity filling a silence.
 *
 * The plain path is unchanged: [line] is the exact arithmetic the activity used before
 * (`(buf + "\n" + line).trim().takeLast(4000)`), so "You: …" / "Agent: …" land
 * byte-for-byte as they always did. The reply ALSO arrives through [delta] as it
 * streams, so a reader does not wait for the turn to complete; [replyFinal] then
 * replaces the open block with the authoritative final text (a guard can rewrite a
 * delta mid-flight) without duplicating it.
 */
class Transcript(private val maxChars: Int = MAX_CHARS) {

    private val buf = StringBuilder()
    /** Start (at the [AGENT] label) of the still-streaming reply block; -1 = none. */
    private var replyStart = -1
    /** Buffer length at the last delta: only a block still at the tail is replaced. */
    private var replyEnd = -1

    val text: String get() = buf.toString()

    fun clear() {
        buf.setLength(0)
        closeReply()
    }

    /** One whole line, exactly the plain path's shape (its join, trim and cap). */
    fun line(line: String) {
        closeReply()
        if (buf.isNotEmpty()) buf.append('\n')
        buf.append(line)
        trimAndClamp()
    }

    fun heard(text: String) = line(YOU + text)
    fun reply(text: String) = line(AGENT + text)
    fun aside(text: String) = line(ASIDE + text)

    /**
     * A line the Presence layer voiced on its own, keyed by the controller's source.
     * The soul's answer IS the turn's reply (the mind may stay silent after it, and in
     * small talk it does); every other source is an aside — a beat, a working line, a
     * filler, the greeting, tool narration.
     */
    fun presence(text: String, source: String) {
        if (source == SOURCE_SOUL_ANSWER) reply(text) else aside(text)
    }

    /** The mind's reply, one streamed delta at a time. */
    fun delta(text: String) {
        if (text.isEmpty()) return
        if (replyStart >= 0 && replyEnd == buf.length) {
            buf.append(text)
        } else {
            closeReply()
            if (buf.isNotEmpty()) buf.append('\n')
            replyStart = buf.length
            buf.append(AGENT).append(text)
        }
        replyEnd = buf.length
        clampHead()
    }

    /** The turn is complete: the final text replaces the streamed block. */
    fun replyFinal(text: String) {
        if (replyStart >= 0 && replyEnd == buf.length) {
            buf.setLength(replyStart)
            buf.append(AGENT).append(text)
            closeReply()
            trimAndClamp()
        } else {
            reply(text)
        }
    }

    private fun closeReply() {
        replyStart = -1
        replyEnd = -1
    }

    private fun trimAndClamp() {
        val s = buf.toString().trim()
        buf.setLength(0)
        buf.append(if (s.length > maxChars) s.substring(s.length - maxChars) else s)
    }

    /** The streamed reply cannot be trimmed at the tail (a delta may end in a space). */
    private fun clampHead() {
        val drop = buf.length - maxChars
        if (drop <= 0) return
        buf.delete(0, drop)
        if (replyStart >= 0) replyStart = (replyStart - drop).coerceAtLeast(0)
        replyEnd = buf.length
    }

    companion object {
        const val MAX_CHARS = 4000
        const val YOU = "You: "
        const val AGENT = "Agent: "
        /** A voiced line that is not the turn's answer: the middle dot keeps the turn
         *  labels ("You:" / "Agent:") meaningful without pretending this is a turn. */
        const val ASIDE = "· "
        /** The one Presence source that is the turn's reply, not an aside. */
        const val SOURCE_SOUL_ANSWER = "soul-answer"
    }
}
