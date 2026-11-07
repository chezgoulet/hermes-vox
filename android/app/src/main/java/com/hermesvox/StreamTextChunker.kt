package com.hermesvox

/**
 * StreamTextChunker — where a streaming reply is cut into TTS chunks.
 *
 * A boundary decides two things at once: how often the voice has to start a fresh
 * synthesis, and how long the blocking write into the playback track is. Pierre's
 * #515 found the real defect behind the boundary gaps — the track held ~250 ms, so
 * chunk N+1 waited for N to finish playing. The track is now 4 s (SherpaTts); this
 * object keeps the other half of that bargain: one chunk must stay well inside it.
 *
 * Measured on the device (Supertonic, 44.1 kHz, 11 real chunks): 12–16 characters
 * per second of speech, mean 14.2, and a ~1.1 s floor for 3–5 character fragments.
 *
 * Rules:
 * 1. A clause seam — `,`, `;`, `:`, a dash, or a true sentence end — is the
 *    preferred cut, once the buffer holds [MIN_CHUNK_CHARS] (~0.85 s at the mean
 *    rate: below that the engine's fragment floor dominates the chunk).
 * 2. Otherwise a hard cap of [CHUNK_CAP_CHARS] (30 chars ≈ 2.1–2.5 s at 12–16
 *    chars/s): 1.5–2× headroom in the 4 s track.
 * 3. A token is never split, so an email, a decimal or an abbreviation stays
 *    whole. At the cap the cut is the nearest whitespace before it; if there is
 *    none, the token is taken whole however long it is.
 * 4. A chunk with no letter or digit is never emitted: punctuation-only audio is
 *    ~1.1 s of near-silence and the lifecycle reads a silent chunk as an engine
 *    failure mid-reply.
 *
 * A buffer-end full stop is NOT a sentence end. Production buffers are prefixes of
 * a live stream, so a delta boundary right after `.` is not evidence of anything —
 * 78–99% of token-shaped streams otherwise split inside a number, an email or
 * "Dr." The next delta settles it; [flush] handles a genuine trailing full stop at
 * the end of the turn.
 *
 * The rule is a function of the text before it, never of wall-clock time or of
 * where a network delta happened to end. [StreamTextChunkerPropertyTest] pins that.
 */
internal object StreamTextChunker {
    /** Longest chunk: 30 chars ≈ 2.1–2.5 s at the measured 12–16 chars/s. */
    const val CHUNK_CAP_CHARS = 30

    /** Shortest chunk worth its own seam: ~0.85 s at the measured mean rate. */
    const val MIN_CHUNK_CHARS = 12

    private const val NO_SEAM = -1
    private const val NEED_MORE = -2

    /**
     * Exclusive end of the next chunk to speak, or -1 while it must keep accumulating.
     * [s] is the live accumulator: a prefix of the reply, not the whole reply. [final]
     * is the end-of-turn read, where nothing more can arrive: a trailing dot IS a full
     * stop then, and a token at the end is complete.
     */
    fun nextChunkEnd(s: CharSequence): Int = nextChunkEnd(s, final = false)

    private fun nextChunkEnd(s: CharSequence, final: Boolean): Int {
        val scanTo = minOf(CHUNK_CAP_CHARS, s.length)   // seams at or after the cap are not seams
        if (MIN_CHUNK_CHARS < scanTo) {
            when (val seam = firstSeam(s, MIN_CHUNK_CHARS, scanTo, final)) {
                NEED_MORE -> return -1                  // the next delta decides; the cap must wait too
                NO_SEAM -> {}
                else -> return seam + 1
            }
        }
        if (s.length < CHUNK_CAP_CHARS) return -1
        return capCut(s, final)
    }

    /** Hand every complete chunk in [acc] to [emit], consuming it from [acc]. */
    fun drain(acc: StringBuilder, emit: (String) -> Unit) = drainInto(acc, final = false, emit = emit)

    private fun drainInto(acc: StringBuilder, final: Boolean, emit: (String) -> Unit) {
        while (acc.isNotEmpty() && acc[0].isWhitespace()) acc.deleteCharAt(0)
        while (true) {
            val end = nextChunkEnd(acc, final)
            if (end <= 0) return
            val chunk = acc.substring(0, end).trim()
            acc.delete(0, end)
            while (acc.isNotEmpty() && acc[0].isWhitespace()) acc.deleteCharAt(0)
            // The rules above make this unreachable; it is the hard fence that keeps a
            // punctuation-only fragment out of the engine if a future edit breaks them.
            if (chunk.any { it.isLetterOrDigit() }) emit(chunk)
        }
    }

    /**
     * End of turn: everything still buffered, chunked with the same rule and with the
     * buffer treated as settled. A genuine trailing full stop is spoken here (it was
     * held back because a live buffer-end dot is not a sentence end), and bare
     * punctuation with nothing to say is dropped rather than synthesized as silence.
     */
    fun flush(acc: StringBuilder): List<String> {
        val out = ArrayList<String>()
        drainInto(acc, final = true) { out += it }
        val rest = acc.toString().trim()
        acc.setLength(0)
        if (rest.any { it.isLetterOrDigit() }) out += rest
        return out
    }

    /** Test/tooling helper: the chunks of a whole string, including the tail. */
    fun chunkAll(text: String): List<String> {
        val acc = StringBuilder(text)
        val out = ArrayList<String>()
        drain(acc) { out += it }
        out += flush(acc)
        return out
    }

    /** The earliest definite seam in [from, to): its index, [NO_SEAM], or [NEED_MORE]. */
    private fun firstSeam(s: CharSequence, from: Int, to: Int, final: Boolean): Int {
        var i = from
        while (i < to) {
            when (s[i]) {
                '!', '?', '\n' -> if (speakable(s, i + 1)) return i
                // An em/en dash is clause punctuation on its own; a hyphen only when it is
                // surrounded by spaces ("well-known" and "1-2" are one token).
                '—', '–' -> if (speakable(s, i + 1)) return i
                '-' -> {
                    if (i + 1 >= s.length) {
                        if (!final) return NEED_MORE
                    } else if (i > 0 && s[i - 1].isWhitespace() && s[i + 1].isWhitespace() &&
                        speakable(s, i + 1)) return i
                }
                '.' -> when (dotKind(s, i, final)) {
                    Dot.SENTENCE -> if (speakable(s, i + 1)) return i
                    Dot.NOT_SENTENCE -> {}
                    Dot.NEEDS_MORE -> return NEED_MORE
                }
                ',', ';', ':' -> {
                    if (i + 1 >= s.length) {
                        if (!final) return NEED_MORE            // "3," may become "3,14"
                    } else if (s[i + 1].isWhitespace() && speakable(s, i + 1)) return i
                }
            }
            i++
        }
        return NO_SEAM
    }

    /**
     * The hard cap. Cut at the nearest whitespace before the cap; if there is none the
     * first token is longer than the cap and is taken whole — but only once its end can
     * be seen, because a token is never split. A leading punctuation-only run is not a
     * chunk either, so it attaches to the first token that can be spoken.
     */
    private fun capCut(s: CharSequence, final: Boolean): Int {
        val limit = minOf(CHUNK_CAP_CHARS, s.length)
        var ws = limit - 1
        while (ws >= 0 && !s[ws].isWhitespace()) ws--
        if (ws > 0 && speakable(s, ws)) {
            // The token after the break can be bare punctuation that closes the word before
            // it ("1.2.3 ?" in French typography). Cutting at [ws] would leave that token
            // alone — the near-silent fragment rule 4 forbids — and appending it here would
            // push the chunk over the cap. Move the cut in front of the word instead, so the
            // punctuation stays with it.
            var k = ws
            while (k < s.length && s[k].isWhitespace()) k++
            // The break is the last thing in a LIVE buffer: the next token may turn out to be
            // bare punctuation ("1.2.3 ?"), which must not be left behind. Wait for it; at the
            // end of the turn the buffer is settled, so [ws] is safe.
            if (k >= s.length) return if (final) ws else -1
            var t = k
            while (t < s.length && !s[t].isWhitespace()) t++
            if ((k until t).any { s[it].isLetterOrDigit() }) return ws
            if (t >= s.length && !final) return -1          // bare punctuation may still be arriving
            var w = ws - 1
            while (w >= 0 && !s[w].isWhitespace()) w--
            return if (w > 0) w else t                      // no earlier word: keep it attached
        }
        return firstSpeakableTokenEnd(s, final)
    }

    /** Exclusive end of the first whitespace-delimited token with a letter/digit, or -1. */
    private fun firstSpeakableTokenEnd(s: CharSequence, final: Boolean): Int {
        var i = 0
        while (i < s.length) {
            while (i < s.length && s[i].isWhitespace()) i++
            val start = i
            while (i < s.length && !s[i].isWhitespace()) i++
            if (i >= s.length) return if (final) i else -1  // at end of turn the token is complete
            if ((start until i).any { s[it].isLetterOrDigit() }) return i
        }
        return -1
    }

    /** Does s[0, end) contain anything the voice can pronounce? */
    private fun speakable(s: CharSequence, end: Int): Boolean {
        for (k in 0 until minOf(end, s.length)) if (s[k].isLetterOrDigit()) return true
        return false
    }

    private enum class Dot { SENTENCE, NOT_SENTENCE, NEEDS_MORE }

    /**
     * '.' is a sentence end only when it is followed by whitespace and then a letter,
     * and the token before it is not an abbreviation or an initial. Anything the live
     * buffer cannot settle yet is [Dot.NEEDS_MORE] — including a dot at the buffer end
     * (the delta boundary is not a full stop).
     */
    private fun dotKind(s: CharSequence, i: Int, final: Boolean): Dot {
        val j = i + 1
        if (j >= s.length) return if (final) Dot.SENTENCE else Dot.NEEDS_MORE   // live: not settled
        if (!s[j].isWhitespace()) return Dot.NOT_SENTENCE     // "71.5%", "a.b@c", "1.2.3"
        var k = j
        while (k < s.length && s[k].isWhitespace()) k++
        if (k >= s.length) return if (final) Dot.SENTENCE else Dot.NEEDS_MORE   // trail-off at end
        if (!s[k].isLetter()) return Dot.NOT_SENTENCE         // ". 3" is not a sentence end
        var w = i - 1
        while (w >= 0 && (s[w].isLetterOrDigit() || s[w] == '.')) w--
        val token = s.substring(w + 1, i).lowercase()
        // A dotted abbreviation ("e.g", "U.S") or a single initial ("J. Smith").
        return if (token.length <= 1 || token in STREAM_CHUNK_ABBREVIATIONS) Dot.NOT_SENTENCE
        else Dot.SENTENCE
    }
}

private val STREAM_CHUNK_ABBREVIATIONS = setOf(
    "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc",
    "approx", "dept", "inc", "ltd", "co", "ave", "blvd", "mt",
    "e.g", "i.e", "cf", "al", "fig", "no", "pp", "vol",
)
