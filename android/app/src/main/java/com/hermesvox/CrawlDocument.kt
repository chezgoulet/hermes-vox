package com.hermesvox

/**
 * CrawlDocument — how the scrollback ring and the in-flight reply become one piece of
 * text (#368). Pure, so the shape is testable without a layout.
 *
 * `CrawlView` paints a single `StaticLayout`; the transcript is therefore one string:
 *
 *     finished history  +  "\n\n"  +  the reply being spoken now
 *
 * The live reply always sits at the END. The speech cursor only makes sense for that
 * part, so [liveStart] is the only offset the view needs when it splits a line at the
 * spoken boundary; everything before it is finished speech and paints fully bright.
 *
 * [appended] answers the one question the scroll needs when text changes mid-read:
 * *how much grew at the end?* The ring only ever appends at the end and drops from the
 * front, so the overlap is the longest suffix of the old document that the new one
 * begins with. Appending that growth to the reader's anchor is what keeps the words
 * under the eye still while a live reply keeps streaming.
 */
object CrawlDocument {

    /** The whole painted document. No trailing gap when either side is empty. */
    fun join(history: String, live: String): String = when {
        history.isBlank() -> live
        live.isBlank() -> history
        else -> history + CrawlHistory.GAP + live
    }

    /** Character index where the in-flight reply begins (0 when there is no history). */
    fun liveStart(history: String, live: String): Int =
        if (history.isBlank() || live.isBlank()) 0 else history.length + CrawlHistory.GAP.length

    /**
     * Characters appended at the document's end when [old] became [new].
     *
     * A shrink (the reply was sealed into history, the live text cleared) or a
     * replacement with no shared suffix returns 0: the reader keeps the same distance
     * from the end rather than being pushed. History eviction is not growth either —
     * it removes from the front, which cannot move an anchor measured from the end.
     */
    fun appended(old: String, new: String): Int {
        if (new.isEmpty()) return 0
        if (old.isEmpty()) return new.length
        var overlap = minOf(old.length, new.length)
        while (overlap > 0 && !new.startsWith(old.substring(old.length - overlap))) overlap--
        // No shared suffix = a replacement (a new turn), not growth at the end. Leave
        // the anchor where it is rather than pushing the reader forward by the whole text.
        if (overlap == 0) return 0
        return new.length - overlap
    }
}
