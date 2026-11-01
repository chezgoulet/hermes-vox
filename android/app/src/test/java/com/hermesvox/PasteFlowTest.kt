package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #327 — the paste rules, off-device: what the clipboard holds, what the preview says,
 * what the cap drops, and the one rule the feature exists to keep — nothing is sent
 * until the person taps SEND.
 */
class PasteFlowTest {

    private var clip: String? = null
    private val sent = mutableListOf<String>()
    private val flow = PasteFlow(readClipboard = { clip }, send = { sent += it })

    @Test
    fun `a paste is a preview and does not send`() {
        clip = "https://example.com/a/very/long/path?q=1"
        flow.paste()

        val p = flow.state as ClipboardPaste.State.Preview
        assertEquals(clip, p.text)
        assertEquals(clip, p.snippet)
        assertEquals(clip!!.length, p.chars)
        assertEquals(0, p.dropped)
        assertEquals(0, flow.sends)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `the send action sends exactly the previewed text once`() {
        clip = "https://example.com"
        flow.paste()
        assertTrue(flow.send())

        assertEquals(listOf("https://example.com"), sent)
        assertEquals(1, flow.sends)
        // Consumed: a second call (a double tap, a racing back-press) sends nothing.
        assertFalse(flow.send())
        assertEquals(1, sent.size)
        assertEquals(ClipboardPaste.State.Behind, flow.state)
    }

    @Test
    fun `an empty clipboard says so and never sends`() {
        for (empty in listOf(null, "", "   ", "\n\t ")) {
            clip = empty
            flow.paste()
            assertEquals(ClipboardPaste.State.Empty, flow.state)
            assertEquals("The clipboard is empty — copy something first.", ClipboardPaste.line(flow.state))
            assertFalse(flow.send())
            assertEquals(0, flow.sends)
        }
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `nothing is sent without the send action`() {
        clip = "paste me"
        flow.paste()
        flow.dismiss()
        assertEquals(0, flow.sends)
        assertTrue(sent.isEmpty())

        // A send with no preview waiting (the sheet was never opened) is a no-op too.
        assertFalse(flow.send())
        assertEquals(0, flow.sends)
    }

    @Test
    fun `a long paste is previewed short with its real count`() {
        clip = "x".repeat(5_000)
        flow.paste()

        val p = flow.state as ClipboardPaste.State.Preview
        assertEquals(5_000, p.chars)
        assertEquals(5_000, p.text.length)          // 5,000 is under the cap: sent whole
        assertEquals(0, p.dropped)
        assertEquals(ClipboardPaste.SNIPPET_CHARS + 1, p.snippet.length)   // head + the marker
        assertTrue(p.snippet.endsWith("…"))
        assertEquals("From the clipboard · 5,000 characters", ClipboardPaste.line(p))
    }

    @Test
    fun `the preview collapses whitespace so a stack trace stays one line`() {
        clip = "line one\n\n  line two\tline three"
        flow.paste()
        assertEquals("line one line two line three", (flow.state as ClipboardPaste.State.Preview).snippet)
    }

    @Test
    fun `an oversized paste is capped with a visible marker and a dropped count`() {
        val over = 123
        clip = "a".repeat(ClipboardPaste.MAX_CHARS + over)
        flow.paste()

        val p = flow.state as ClipboardPaste.State.Preview
        assertEquals(ClipboardPaste.MAX_CHARS, p.text.length)
        assertEquals(ClipboardPaste.MAX_CHARS + over, p.chars)
        assertEquals(over, p.dropped)
        assertTrue(p.snippet.endsWith("…"))
        assertEquals(
            "1,000,123 characters · one turn carries at most 1,000,000, so 123 characters dropped",
            ClipboardPaste.line(p))

        // The send keeps the cap, and the count of what was left behind is the same one
        // the sheet showed before the tap.
        assertTrue(flow.send())
        assertEquals(1, sent.size)
        assertEquals(ClipboardPaste.MAX_CHARS, sent[0].length)
    }

    @Test
    fun `the cap never splits a surrogate pair`() {
        // Place an emoji (two UTF-16 units) so the limit falls between its halves.
        clip = "b".repeat(ClipboardPaste.MAX_CHARS - 1) + "\uD83D\uDE00"
        flow.paste()

        val p = flow.state as ClipboardPaste.State.Preview
        assertFalse(Character.isHighSurrogate(p.text.last()))
        assertEquals(ClipboardPaste.MAX_CHARS - 1, p.text.length)
        assertEquals(2, p.dropped)   // both halves of the pair, never one of them
    }

    @Test
    fun `a single character is counted in the singular`() {
        assertEquals("1 character", ClipboardPaste.count(1))
        assertEquals("2 characters", ClipboardPaste.count(2))
        assertEquals("1,234 characters", ClipboardPaste.count(1_234))
    }

    @Test
    fun `the conversation line keeps a head, not the whole paste`() {
        val text = "z".repeat(5_000)
        val line = ClipboardPaste.transcriptLine(text)
        assertTrue(line.startsWith("You: "))
        assertTrue(line.endsWith(" · 5,000 characters pasted"))
        assertEquals("You: ".length + ClipboardPaste.TRANSCRIPT_CHARS + 1 + " · 5,000 characters pasted".length,
            line.length)
    }
}
