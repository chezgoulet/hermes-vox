package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #368 — the pure half of the crawl scrollback: the history ring's bounds, what
 * survives a new reply, the scroll state machine (idle → reading → returning), and the
 * promise that a live reply never resets the reader's position.
 *
 * These drive the same [CrawlHistory], [CrawlScroll] and [CrawlDocument] the view and
 * the activity use. What they cannot prove — that a finger enters the state, that the
 * same pixels land under the same finger after a relayout, that TalkBack reads the
 * revealed region, and that night/paper both stay legible while scrolled — is listed in
 * the PR's "needs a device" section.
 */
class CrawlScrollbackTest {

    // ---- the bounded ring ---------------------------------------------------

    @Test fun the_ring_keeps_only_the_last_entries() {
        val h = CrawlHistory(maxEntries = 3, maxChars = 10_000)
        (1..5).forEach { h.add("reply $it", CrawlHistory.Kind.REPLY, it.toLong()) }
        assertEquals(listOf("reply 3", "reply 4", "reply 5"), h.entries().map { it.text })
        assertEquals(3, h.size)
    }

    @Test fun the_ring_keeps_only_the_last_characters() {
        val h = CrawlHistory(maxEntries = 100, maxChars = 20)
        h.add("a".repeat(15), CrawlHistory.Kind.REPLY, 1)
        h.add("b".repeat(15), CrawlHistory.Kind.REPLY, 2)
        assertTrue("document is ${h.text().length} chars, over the 20 cap", h.text().length <= 20)
        assertTrue("the newest line's tail must survive", h.text().endsWith("b".repeat(15)))
    }

    @Test fun one_line_longer_than_the_budget_keeps_its_tail() {
        val h = CrawlHistory(maxEntries = 10, maxChars = 20)
        h.add("x".repeat(200) + "THE END", CrawlHistory.Kind.REPLY, 1)
        assertEquals(20, h.text().length)
        assertTrue(h.text().endsWith("THE END"))
    }

    @Test fun a_new_reply_keeps_the_one_before_it() {
        // The previous turn is what the reader scrolls back to; only the ring's oldest
        // entry may go, never the newest finished one.
        val h = CrawlHistory(maxEntries = 4, maxChars = 10_000)
        listOf("one", "two", "three").forEachIndexed { i, t ->
            h.add(t, CrawlHistory.Kind.REPLY, i.toLong())
        }
        h.add("four", CrawlHistory.Kind.REPLY, 3)
        assertEquals(listOf("one", "two", "three", "four"), h.entries().map { it.text })
        h.add("five", CrawlHistory.Kind.REPLY, 4)
        assertEquals(listOf("two", "three", "four", "five"), h.entries().map { it.text })
    }

    @Test fun a_soul_answer_echoed_by_the_mind_is_one_line() {
        // Presence speaks the soul's answer through speakGlue, then the mind's final
        // text may repeat it in the same turn. That is one thing that was said.
        val h = CrawlHistory()
        assertTrue(h.add("Steady.", CrawlHistory.Kind.REPLY, 7))
        assertFalse(h.add("Steady.", CrawlHistory.Kind.REPLY, 7))
        assertEquals(1, h.size)
    }

    @Test fun the_same_words_in_a_new_turn_are_not_a_duplicate() {
        // "Say yes." twice really is two replies; the turn id is what tells them apart.
        val h = CrawlHistory()
        h.add("Yes.", CrawlHistory.Kind.REPLY, 7)
        h.add("Yes.", CrawlHistory.Kind.REPLY, 8)
        assertEquals(2, h.size)
    }

    @Test fun a_presence_aside_is_kept_and_marked() {
        val h = CrawlHistory()
        h.add("One moment.", CrawlHistory.Kind.ASIDE, 1)
        h.add("Built.", CrawlHistory.Kind.REPLY, 1)
        assertEquals("· One moment.\n\nBuilt.", h.text())
        assertEquals(CrawlHistory.Kind.ASIDE, h.entries().first().kind)
    }

    @Test fun blank_lines_are_not_recorded() {
        val h = CrawlHistory()
        assertFalse(h.add("", CrawlHistory.Kind.REPLY, 1))
        assertFalse(h.add("   \n ", CrawlHistory.Kind.ASIDE, 1))
        assertEquals(0, h.size)
    }

    @Test fun clear_empties_the_trail() {
        val h = CrawlHistory()
        h.add("hello", CrawlHistory.Kind.REPLY, 1)
        h.clear()
        assertEquals("", h.text())
        assertEquals(0, h.size)
    }

    // ---- the scroll state machine ------------------------------------------

    private fun readingAt(offset: Float, max: Float = 500f): CrawlScroll {
        val s = CrawlScroll()
        s.setMaxOffset(max)
        s.press()
        assertTrue(s.drag(offset))
        assertEquals(CrawlScroll.State.READING, s.state)
        return s
    }

    @Test fun idle_to_reading_to_returning_to_idle() {
        val s = CrawlScroll()
        s.setMaxOffset(400f)
        assertEquals(CrawlScroll.State.IDLE, s.state)

        s.press()
        assertTrue(s.drag(90f))
        assertEquals(CrawlScroll.State.READING, s.state)
        assertEquals(90f, s.offsetPx, 0f)

        assertEquals(CrawlScroll.State.READING, s.release())   // released away from live
        assertEquals(CrawlScroll.State.READING, s.state)

        s.beginReturn()
        assertEquals(CrawlScroll.State.RETURNING, s.state)
        s.returnProgress(0.5f)
        assertTrue("the return must be under way", s.offsetPx in 0f..90f)
        s.returnProgress(1f)
        assertEquals(CrawlScroll.State.IDLE, s.state)
        assertEquals(0f, s.offsetPx, 0f)
        assertEquals(0, s.anchorFromEnd)
    }

    @Test fun a_short_reply_does_not_enter_reading() {
        // There is nowhere to go; the press must stay a no-op, not flash a mode.
        val s = CrawlScroll()
        s.setMaxOffset(0f)
        s.press()
        assertFalse(s.drag(80f))
        assertEquals(CrawlScroll.State.IDLE, s.state)
        assertEquals(0f, s.offsetPx, 0f)
    }

    @Test fun the_drag_cannot_leave_the_document() {
        val s = CrawlScroll()
        s.setMaxOffset(100f)
        s.press()
        s.drag(900f)
        assertEquals(100f, s.offsetPx, 0f)
        s.drag(-900f)
        assertEquals(0f, s.offsetPx, 0f)
    }

    @Test fun releasing_at_the_live_edge_returns() {
        val s = readingAt(40f)
        s.drag(-40f)                    // back to the live edge while still holding
        assertEquals(0f, s.offsetPx, 0f)
        assertEquals(CrawlScroll.State.RETURNING, s.release())
        s.returnProgress(1f)
        assertEquals(CrawlScroll.State.IDLE, s.state)
    }

    @Test fun a_tap_is_the_exit() {
        // Enter by press + drag, leave by press + release with no movement.
        val s = readingAt(120f)
        s.release()
        assertEquals(CrawlScroll.State.READING, s.state)

        s.press()
        assertEquals(CrawlScroll.State.RETURNING, s.release())
        s.returnProgress(1f)
        assertEquals(CrawlScroll.State.IDLE, s.state)
    }

    @Test fun a_live_reply_does_not_reset_the_reader() {
        // The issue's barge case: read the start of a reply while the rest streams in.
        val s = readingAt(120f)
        s.noteAnchor(300)               // eye is 300 chars from the document's end

        s.onContentAppended(80)         // a delta arrives below the viewport

        assertEquals("a live append must not leave the trail", CrawlScroll.State.READING, s.state)
        assertEquals("a live append must not move the text under the eye", 120f, s.offsetPx, 0f)
        assertEquals("the anchor moves with the growth", 380, s.anchorFromEnd)
        // The same words: character 700 from the start, before and after the append.
        assertEquals(700, s.anchorChar(1_080))
    }

    @Test fun a_reply_that_finishes_while_reading_is_kept_and_does_not_move_the_eye() {
        // Sealing the live reply moves text into history. The document's end grows by
        // exactly that text, so an anchor from the end still points at the same words.
        val s = readingAt(160f)
        s.noteAnchor(220)
        s.onContentAppended(420)        // the reply's remaining text + the gap into history
        assertEquals(CrawlScroll.State.READING, s.state)
        assertEquals(640, s.anchorFromEnd)
        // Character 280 (500 - 220) is still character 280 (920 - 640).
        assertEquals(280, s.anchorChar(920))
    }

    @Test fun a_new_conversation_resets_the_trail() {
        val s = readingAt(200f)
        s.reset()
        assertEquals(CrawlScroll.State.IDLE, s.state)
        assertEquals(0f, s.offsetPx, 0f)
    }

    // ---- the painted document ----------------------------------------------

    @Test fun history_and_live_text_are_one_document_with_one_gap() {
        assertEquals("earlier\n\nnow", CrawlDocument.join("earlier", "now"))
        assertEquals("now", CrawlDocument.join("", "now"))
        assertEquals("earlier", CrawlDocument.join("earlier", ""))
        assertEquals(0, CrawlDocument.liveStart("", "now"))
        assertEquals("earlier\n\n".length, CrawlDocument.liveStart("earlier", "now"))
    }

    @Test fun a_streamed_append_is_measured_from_the_end() {
        assertEquals(3, CrawlDocument.appended("abc", "abcXYZ"))
        assertEquals(3, CrawlDocument.appended("", "abc"))
        assertEquals(0, CrawlDocument.appended("abc", "abc"))
        assertEquals(0, CrawlDocument.appended("abc", ""))
        assertEquals(0, CrawlDocument.appended("abc", "xyz"))   // replacement, not growth
    }

    @Test fun history_that_evicts_at_the_front_and_appends_is_measured_as_growth() {
        // new = tail(old) + gap + the new line; the overlap is old's suffix.
        val old = "old line one\n\nold line two"
        val new = "old line two\n\nnew line"
        assertEquals(CrawlHistory.GAP.length + "new line".length,
            CrawlDocument.appended(old, new))
    }
}
