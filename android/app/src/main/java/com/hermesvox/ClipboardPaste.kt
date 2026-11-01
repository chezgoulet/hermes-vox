package com.hermesvox

import java.util.Locale

/**
 * #327 — paste is a decision, not a binding.
 *
 * The clipboard itself is Android; everything that can be got wrong lives here, where a
 * JVM test can reach it: what the clipboard holds, what the preview says, what is dropped
 * when the text is longer than one turn can carry, and the rule that nothing is sent
 * until the person taps SEND.
 *
 * Three states, never two: [State.Empty] (there is nothing to paste), [State.Preview]
 * (read, counted, and waiting for the send action), and [PasteFlow.send] (the send
 * action). Opening the sheet is a read — it is never a send.
 */
object ClipboardPaste {

    /**
     * The most characters one pasted turn may carry.
     *
     * This is measured, not invented. The gateway's API server accepts a request body up
     * to 10,000,000 bytes (`MAX_REQUEST_BYTES`, gateway/platforms/api_server.py) and
     * `/v1/responses` takes `input` as a plain string with no per-text cap — the 65,536
     * character cap there applies only to array/content-part input, which this client
     * does not send. This client writes that string as JSON (`core/Json.kt`), whose worst
     * case is 6 bytes per UTF-16 code unit (`\u001f`). A 1,000,000 character paste is
     * therefore at most 6,000,000 bytes, comfortably inside the gateway's 10 MB with the
     * request envelope on top. Lower this only with a new measurement.
     */
    const val MAX_CHARS = 1_000_000

    /** How much of the text the preview shows: a short look, never the whole paste. */
    const val SNIPPET_CHARS = 240

    /** How much of the sent text the conversation line repeats. */
    const val TRANSCRIPT_CHARS = 160

    /** What the clipboard gave us, after the one decision that matters. */
    sealed class State {
        /** Not read yet (also the state after a send or a dismiss). */
        data object Behind : State()

        /** The clipboard was empty, or held only whitespace / no text item. */
        data object Empty : State()

        /**
         * Ready for the send action.
         *
         * [text] is exactly what SEND will send (already capped). [snippet] is only what
         * the preview shows. [chars] is the clipboard's full length, and [dropped] is
         * what the cap left behind — stated on screen, never silent.
         */
        data class Preview(
            val text: String,
            val snippet: String,
            val chars: Int,
            val dropped: Int,
        ) : State()
    }

    /** Reads the clipboard text (null/blank = nothing to paste) and decides the state. */
    fun preview(raw: String?): State {
        if (raw == null || raw.isBlank()) return State.Empty
        val chars = raw.length
        val text = if (chars > MAX_CHARS) cutSafely(raw, MAX_CHARS) else raw
        return State.Preview(
            text = text,
            snippet = snippet(text),
            chars = chars,
            dropped = chars - text.length,
        )
    }

    /**
     * The one line the sheet leads with: what is held and, when the cap bites, exactly
     * how much is dropped. The limit is named in the same line as the problem.
     */
    fun line(state: State): String = when (state) {
        is State.Behind, is State.Empty -> "The clipboard is empty — copy something first."
        is State.Preview ->
            if (state.dropped == 0) "From the clipboard · ${count(state.chars)}"
            else "${count(state.chars)} · one turn carries at most ${number(MAX_CHARS)}, " +
                "so ${count(state.dropped)} dropped"
    }

    /**
     * The conversation's line for a pasted turn. The full text was already shown in the
     * preview and went to the agent; repeating a megabyte into the transcript would bury
     * the reply, so it keeps a short head and the real size.
     */
    fun transcriptLine(text: String): String =
        "You: ${snippet(text, TRANSCRIPT_CHARS)} · ${count(text.length)} pasted"

    /** A short, single-line look at [text]; ends in `…` when it is not the whole thing. */
    fun snippet(text: String, max: Int = SNIPPET_CHARS): String {
        if (max <= 0) return ""
        val out = StringBuilder()
        var pendingSpace = false
        for (c in text) {
            if (c.isWhitespace()) {
                pendingSpace = out.isNotEmpty()
                continue
            }
            if (out.length >= max) return out.append('…').toString()
            if (pendingSpace) {
                out.append(' ')
                pendingSpace = false
                if (out.length >= max) return out.append('…').toString()
            }
            out.append(c)
        }
        return out.toString()
    }

    /** `1,234 characters` — grouping and plural in one place so the tests can pin it. */
    fun count(n: Int): String = number(n) + if (n == 1) " character" else " characters"

    private fun number(n: Int): String = String.format(Locale.US, "%,d", n)

    /**
     * Truncates at [max] without splitting a surrogate pair (a lone half is not text a
     * person can read, and it is not something to send).
     */
    private fun cutSafely(text: String, max: Int): String {
        var end = max.coerceAtMost(text.length)
        if (end > 0 && end < text.length && Character.isHighSurrogate(text[end - 1])) end--
        return text.substring(0, end)
    }
}

/**
 * The paste → preview → send flow. [readClipboard] is the Android edge; [send] is the
 * only way anything leaves this class, and it is reachable only from [send], which a test
 * can prove is never called by [paste].
 */
class PasteFlow(
    private val readClipboard: () -> String?,
    private val send: (String) -> Unit,
) {
    var state: ClipboardPaste.State = ClipboardPaste.State.Behind
        private set

    /** How many times a preview became a send — the "never send silently" witness. */
    var sends: Int = 0
        private set

    /** The one read: fills the preview. Never sends. */
    fun paste() {
        state = ClipboardPaste.preview(readClipboard())
    }

    /**
     * The one send action. It is a no-op unless a preview is waiting, so a stray call (a
     * double tap, a back-press race) cannot repeat a turn. Returns whether it sent.
     */
    fun send(): Boolean {
        val p = state as? ClipboardPaste.State.Preview ?: return false
        state = ClipboardPaste.State.Behind      // consumed before the send: no second turn
        sends++
        send(p.text)
        return true
    }

    /** Closes the preview without sending anything. */
    fun dismiss() {
        state = ClipboardPaste.State.Behind
    }
}
