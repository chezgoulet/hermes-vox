package com.hermesvox

/**
 * CrawlScroll — the press-and-swipe scrollback's state machine (#368). Pure, so the
 * rules below are proven in [CrawlScrollbackTest] instead of by hand on a phone.
 *
 * The crawl is a drawn transcript, not a `TextView`, so this is not a scroll view: it
 * is a vertical offset into one `StaticLayout`, plus the states that decide whether a
 * new line is allowed to move the reader.
 *
 * IDLE       live: the newest text is anchored to the bottom. A new reply lands here.
 * READING    the caller is back in the trail. A live append grows the document *below*
 *            the viewport; the anchor (measured from the document's end) moves with it
 *            and [CrawlView] re-places the same words where they were. Nothing yanks.
 * RETURNING  the exit animation back to the live edge; IDLE when it lands.
 *
 * Entering is a press + downward drag (direct manipulation: the text follows the
 * finger and older lines come down into view). Leaving is a tap — no movement at all —
 * or dragging back up to the live edge and releasing. A tap is deliberately the easier
 * of the two: the exit must never be harder than the entry.
 *
 * `offsetPx` is in layout pixels: positive = the block moved down = older text visible.
 * The view owns the layout and feeds [setMaxOffset]/[setOffset]; this class owns the
 * decisions and the bounds.
 */
class CrawlScroll {

    enum class State { IDLE, READING, RETURNING }

    var state: State = State.IDLE
        private set

    var offsetPx: Float = 0f
        private set

    var maxOffsetPx: Float = 0f
        private set

    /**
     * Where the reader's eye is, as characters from the END of the document. The ring
     * appends at the end and drops from the front, so this is the one anchor that does
     * not move when the trail changes underneath a live reply.
     */
    var anchorFromEnd: Int = 0
        private set

    /** Did the press move? A press with no movement is the tap that exits. */
    var moved: Boolean = false
        private set

    private var returnFromPx = 0f

    val reading: Boolean get() = state != State.IDLE
    val canScroll: Boolean get() = maxOffsetPx > EDGE_PX

    fun setMaxOffset(max: Float) {
        maxOffsetPx = max.coerceAtLeast(0f)
        if (offsetPx > maxOffsetPx) offsetPx = maxOffsetPx
        if (offsetPx < 0f) offsetPx = 0f
    }

    fun setOffset(px: Float) {
        offsetPx = px.coerceIn(0f, maxOffsetPx)
    }

    fun press() {
        moved = false
    }

    /**
     * One finger movement, in view pixels ([dy] positive = downward). Returns true when
     * this is the scrollback gesture (there is somewhere to go). With nothing to read
     * the drag is ignored, so a short reply does not flicker into READING.
     */
    fun drag(dy: Float): Boolean {
        if (state == State.IDLE) {
            if (!canScroll) return false
            state = State.READING
        }
        if (state == State.RETURNING) state = State.READING
        moved = true
        setOffset(offsetPx + dy)
        return true
    }

    /**
     * The finger lifts. No movement at all = a tap = the exit. Otherwise: at the live
     * edge, return; away from it, stay in READING so the trail can be read hands-free.
     */
    fun release(): State {
        if (!moved) {
            if (state == State.READING || state == State.RETURNING) beginReturn()
            else state = State.IDLE
        } else if (offsetPx <= EDGE_PX) {
            beginReturn()
        } else {
            state = State.READING
        }
        moved = false
        return state
    }

    fun beginReturn() {
        if (state == State.IDLE) {
            offsetPx = 0f
            return
        }
        returnFromPx = offsetPx
        state = State.RETURNING
    }

    /** 0..1 of the return animation, eased; lands at the live edge and goes IDLE. */
    fun returnProgress(fraction: Float) {
        if (state != State.RETURNING) return
        val f = fraction.coerceIn(0f, 1f)
        val eased = 1f - (1f - f) * (1f - f) * (1f - f)
        offsetPx = returnFromPx * (1f - eased)
        if (f >= 1f) {
            offsetPx = 0f
            anchorFromEnd = 0
            state = State.IDLE
        }
    }

    /** A new conversation: forget the trail position. */
    fun reset() {
        state = State.IDLE
        offsetPx = 0f
        anchorFromEnd = 0
        moved = false
        returnFromPx = 0f
    }

    /** The view tells us where the eye is after a drag. */
    fun noteAnchor(fromEnd: Int) {
        if (state == State.READING) anchorFromEnd = fromEnd.coerceAtLeast(0)
    }

    /** The character at the reader's anchor in a document of [docLength]. */
    fun anchorChar(docLength: Int): Int {
        if (docLength <= 0) return 0
        return (docLength - anchorFromEnd).coerceIn(0, docLength - 1)
    }

    /**
     * Content grew at the END (a streamed delta, a finished line, the gap before it).
     * Keep the same words under the eye by moving the anchor with the growth; the
     * offset itself is re-derived from the new layout, never reset to live.
     */
    fun onContentAppended(chars: Int) {
        if (state == State.READING && chars > 0) {
            anchorFromEnd += chars
        }
    }

    companion object {
        /** A finger within this many pixels of the live edge counts as being at it. */
        const val EDGE_PX = 0.5f
    }
}
