package com.hermesvox

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale

/**
 * FileOffer — the phone offers a file, the agent fetches it (SPEC-conversational-bridge §B).
 *
 * The API accepts text and inline images and refuses `file` parts by design, so nothing here
 * uploads anything: the file is copied into a private staging directory, the app serves it
 * once over the tailnet, and the *turn* carries a capability URL, a size and a hash. The
 * agent fetches it with `curl`, verifies both, and keeps it.
 *
 * Everything in this object is pure JVM: the offer/refuse decision, the token and URL shape,
 * the turn text (including the name encoding), the inline fallback, the status lines and the
 * reading of the agent's reply. The socket lives in [FileServer]; the file/staging edges in
 * [FileStage]; the Android skin in [FileOfferSheet].
 *
 * Two rules are load-bearing and are enforced here rather than in the UI:
 *
 *  * **the app never claims success on its own.** [Reply] only says what the agent said, and
 *    [collected] needs the transfer to have completed *and* the agent to have said it kept the
 *    file. A truncated fetch or an invented "kept it" cannot be turned into the success line by
 *    this file; a completed transfer the agent never acknowledged is reported by [FileShare]'s
 *    expiry, which says exactly that (the agent never named a path) rather than guessing one.
 *  * **the contents never enter the transcript.** The turn for a big file carries name, size,
 *    type, hash and URL — no bytes. The inline fallback is the one place bytes travel, and
 *    the caller shows [transcriptLine] instead of the turn so the body is not repeated on
 *    screen or in the conversation.
 */
object FileOffer {

    /** The reserved first token of the turn. */
    const val MARKER = "[[vox:file]]"

    /**
     * The hard cap: 100 MB, and "MB" is decimal — 100,000,000 bytes.
     *
     * The spec's own example fixes the unit: `bytes=8123` is shown to the user as `8.1 KB`,
     * which is 8123/1000 rounded to one decimal — Android's SI formatting, not MiB. The cap
     * is the same unit so the two numbers can never disagree.
     */
    const val MAX_BYTES = 100_000_000L

    /** Text under 8 KB travels inside the turn; at or above it the server is used. */
    const val INLINE_BELOW = 8_000L

    /** The offer is good for five minutes. */
    const val EXPIRY_MS = 300_000L

    /** 128 bits of token. */
    const val TOKEN_BITS = 128

    /** 16 random bytes as 32 lowercase hex characters — the one path the server serves. */
    const val TOKEN_HEX = 32

    /** The one plain sentence that follows the marker line for a served file. */
    const val OFFER_SENTENCE =
        "I am sharing one file with you. Fetch it now, verify it, and keep it."

    /** The one plain sentence for the inline fallback. */
    const val INLINE_SENTENCE =
        "I am sharing one small text file with you; it is in the fenced block below, " +
            "labelled with its name."

    // ---- the spec's four lines, verbatim; the app may add the failure lines below ----

    const val EXPIRED_LINE = "the agent did not collect the file · tap to try again"
    const val MISMATCH_LINE = "the agent received a different file than we sent · nothing was kept"

    /** One file, as the offer sees it: the display name, its type, size and hash. */
    data class File(
        val name: String,
        val mime: String,
        val bytes: Long,
        val sha256: String,
    )

    /** What the app will do with a chosen file. */
    enum class Route {
        /** Serve it once over the tailnet and send the offer turn. */
        OFFER,

        /** Under 8 KB of text: the body travels in the turn, no server at all. */
        INLINE,

        /** Above [MAX_BYTES]: refused, with the number in the line. */
        REFUSED,

        /** Tailscale is down: no server nobody can reach. Say so; offer inline if possible. */
        UNREACHABLE,
    }

    /**
     * The decision for one file. [line] is what the user is shown *now*; [inlineTurn] is
     * non-null only when inlining is possible (route INLINE, or UNREACHABLE with a small
     * text file the user may still choose to send).
     */
    data class Plan(
        val route: Route,
        val file: File,
        val line: String,
        val inlineTurn: String? = null,
    )

    // ---- the decision ----

    /**
     * At a cap, under it, over it; text under 8 KB; text or binary; Tailscale up or down.
     *
     * Order matters and mirrors the spec: the size cap is refused first (it is the one limit
     * the user cannot work around), then the inline path (which needs no network at all),
     * then the tailnet check (a server nobody can reach is worse than a stated failure).
     *
     * [tailnetHost] is the phone's tailnet address, or null when there is none. It is passed
     * as a string because the URL is built here; it is never derived here.
     */
    fun plan(
        file: File,
        inlineText: String?,
        tailnetHost: String?,
    ): Plan {
        if (file.bytes > MAX_BYTES) {
            return Plan(
                Route.REFUSED, file,
                "this file is ${size(file.bytes)} · the most I can offer is ${size(MAX_BYTES)}",
            )
        }
        val canInline = inlineText != null &&
            isTextType(file.mime) &&
            file.bytes < INLINE_BELOW &&
            looksTextual(inlineText)
        // Tailscale is checked before the inline choice, so a small note shared while the
        // tailnet is down is *said* rather than silently handled: the app offers the inline
        // path instead of starting a server nobody can reach (SPEC §B "If Tailscale is down").
        if (tailnetHost.isNullOrBlank()) {
            return if (canInline) Plan(
                Route.UNREACHABLE, file,
                "Tailscale is not connected, so the agent could not reach this phone · " +
                    "send it inline instead",
                inlineTurn = inlineTurn(file, inlineText!!),
            ) else Plan(
                Route.UNREACHABLE, file,
                "Tailscale is not connected, so the agent could not reach this phone · " +
                    "connect Tailscale and try again",
            )
        }
        if (canInline) {
            return Plan(
                Route.INLINE, file,
                "sent inline · ${file.name} (${size(file.bytes)}) · no server needed",
                inlineTurn = inlineTurn(file, inlineText!!),
            )
        }
        return Plan(
            Route.OFFER, file,
            "sharing ${file.name} · waiting for the agent to collect it",
        )
    }

    /**
     * The offer turn. One marker line — every value on it is single-token, because the name
     * is percent-encoded — then the one plain sentence.
     *
     * ```
     * [[vox:file]] name=meeting%20notes.md type=text/markdown bytes=8123 sha256=9f2c… \
     *   url=http://100.82.156.1:49152/6b1f…e0 expires=1793650000
     * I am sharing one file with you. Fetch it now, verify it, and keep it.
     * ```
     */
    fun offerTurn(
        file: File,
        host: String,
        port: Int,
        token: String,
        expiresAtSec: Long,
    ): String = buildString {
        append(MARKER)
        append(" name=").append(encode(file.name))
        append(" type=").append(token(file.mime, "application/octet-stream"))
        append(" bytes=").append(file.bytes)
        append(" sha256=").append(file.sha256.lowercase(Locale.US))
        append(" url=").append(url(host, port, token))
        append(" expires=").append(expiresAtSec)
        append('\n').append(OFFER_SENTENCE)
    }

    /**
     * The inline fallback: the same marker (so a log can name it and the agent can key on
     * it), `inline=1` in place of `url`/`expires`, and the body in a fenced block whose info
     * string is the file's name.
     *
     * The fence is made longer than the longest run of backticks in the body, so a markdown
     * file that itself contains fences cannot break out of the block.
     */
    fun inlineTurn(file: File, text: String): String {
        val fence = "`".repeat(fenceWidth(text))
        val label = safeFenceLabel(file.name)
        return buildString {
            append(MARKER)
            append(" name=").append(encode(file.name))
            append(" type=").append(token(file.mime, "text/plain"))
            append(" bytes=").append(file.bytes)
            append(" sha256=").append(file.sha256.lowercase(Locale.US))
            append(" inline=1")
            append('\n').append(INLINE_SENTENCE)
            append('\n').append(fence).append(label)
            append('\n').append(text)
            if (!text.endsWith('\n')) append('\n')
            append(fence)
        }
    }

    /**
     * The conversation line for a file turn. Deliberately metadata only: for the inline
     * fallback the bytes *did* go to the agent, but repeating them on screen would put the
     * body in the transcript — and for a served file there are no bytes here at all.
     */
    fun transcriptLine(file: File, inline: Boolean): String =
        "You: shared ${file.name} (${size(file.bytes)})" + if (inline) " inline" else ""

    // ---- the state the user sees ----

    /** `collected · meeting-notes.md (8.1 KB) · ~/.hermes/inbox/meeting-notes.md` */
    fun collected(file: File, keptPath: String): String {
        val where = keptPath.trim().ifEmpty { "the agent did not say where it kept it" }
        return "collected · ${file.name} (${size(file.bytes)}) · $where"
    }

    fun expired(): String = EXPIRED_LINE

    fun mismatch(): String = MISMATCH_LINE

    /** `104.9 MB` / `8.1 KB` / `812 bytes`, decimal (see [MAX_BYTES]). */
    fun size(bytes: Long): String = when {
        bytes < 1_000L -> "$bytes " + if (bytes == 1L) "byte" else "bytes"
        bytes < 1_000_000L -> oneDecimal(bytes / 1_000.0, "KB")
        else -> oneDecimal(bytes / 1_000_000.0, "MB")
    }

    private fun oneDecimal(v: Double, unit: String): String {
        val s = String.format(Locale.US, "%.1f", v)
        // "100.0 MB" reads as a measurement of a cap that is a round number; show "100 MB".
        val shown = if (s.endsWith(".0") && s.length > 4) s.dropLast(2) else s
        return "$shown $unit"
    }

    // ---- token and URL ----

    /** 128 bits from a SecureRandom, lowercase hex. The only path the server answers. */
    fun token(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(TOKEN_BITS / 8)
        random.nextBytes(bytes)
        return hex(bytes)
    }

    /** `http://100.82.156.1:49152/<token>`; an IPv6 tailnet address is bracketed. */
    fun url(host: String, port: Int, token: String): String {
        val h = host.trim().removeSurrounding("[", "]")
        val shown = if (h.contains(':')) "[$h]" else h
        return "http://$shown:$port/$token"
    }

    /** The epoch second the offer dies at, from the millisecond clock. */
    fun expiresAt(nowMs: Long): Long = (nowMs + EXPIRY_MS) / 1000L

    // ---- the agent's reply ----

    /**
     * What the agent said about the file. This is a *report*, not a verification: the hash
     * was checked by the agent, which is the only party that read the bytes.
     */
    sealed class Reply {
        /** The agent says the file did not match what we sent. Never shown as success. */
        data object Mismatch : Reply()

        /** The agent says it kept the file, at [path] when it named one. */
        data class Kept(val path: String) : Reply()

        /** The agent said nothing usable about it. */
        data object Silent : Reply()
    }

    /**
     * Read the agent's reply.
     *
     * A mismatch is looked for first and wins: a reply that reports both a path and a failed
     * verification is a failed verification. The phrase set is deliberately narrow and
     * documented rather than clever — the app must never invent a failure, and must never
     * swallow a stated one. Negations ("no mismatch", "without a mismatch") are removed
     * before the test so a success sentence cannot be read as a failure.
     *
     * A keep needs **evidence** — a path, or a word that says the file was kept. An ordinary
     * reply that happens to follow an offer ("Which file did you mean?") is [Reply.Silent]:
     * "the agent said something" is not "the agent has the file", and the success line is
     * only shown when the agent put it there in words. A *denied* keep ("nothing was kept")
     * is silent too, never a collection.
     */
    fun readReply(text: String?): Reply {
        val body = text?.trim().orEmpty()
        if (body.isEmpty()) return Reply.Silent
        val positive = NEGATED.replace(body.lowercase(Locale.US), " ")
        if (MISMATCH.containsMatchIn(positive)) return Reply.Mismatch
        val path = keptPath(body)
        if (path.isNotEmpty()) return Reply.Kept(path)
        if (!KEPT.containsMatchIn(NOT_KEPT.replace(positive, " "))) return Reply.Silent
        return Reply.Kept("")
    }

    /** `no mismatch`, `without mismatch`, `not a mismatch` are not a mismatch. */
    private val NEGATED = Regex(
        """\b(?:no|without|not\s+a|never\s+a)\s+(?:a\s+|an\s+|any\s+)?""" +
            """(?:sha-?256\s+|hash\s+|checksum\s+)?mismatch\b""",
    )

    private val MISMATCH = Regex(
        """\b(?:""" +
            """sha-?256\s+(?:does\s+not|doesn't|did\s+not|didn't|do\s+not|don't)\s+match""" + "|" +
            """(?:hash|checksum|digest)s?\s+(?:does\s+not|doesn't|did\s+not|didn't|do\s+not|don't)\s+match""" + "|" +
            """(?:byte\s+count|size|length)\s+(?:does\s+not|doesn't|did\s+not|didn't|do\s+not|don't)\s+match""" + "|" +
            """mismatch""" + "|" +
            """truncated""" + "|" +
            """corrupt(?:ed|ion)?""" + "|" +
            """(?:could\s+not|couldn't|cannot|can't|unable\s+to|failed\s+to)\s+verify""" + "|" +
            """verification\s+failed""" + "|" +
            """checksum\s+failed""" + "|" +
            """different\s+file""" + "|" +
            """not\s+the\s+same\s+file""" +
            """)\b""",
    )

    /**
     * A keep, said in the agent's own words, when it named no path: "kept it", "saved to
     * memory", "written into the inbox". Deliberately short and literal — a wider net would
     * read an ordinary sentence as a collection.
     */
    private val KEPT = Regex(
        """\b(?:kept|saved|stored|filed|written|wrote|downloaded|placed)\b""" + "|" +
            """\bin\s+(?:the\s+|my\s+)?inbox\b""" + "|" +
            """\bhave\s+(?:it|the\s+file|the\s+notes)\b""" + "|" +
            """\bit\s+is\s+mine\b""",
    )

    /** "nothing was kept", "I did not save it", "it was never stored" are not a keep. */
    private val NOT_KEPT = Regex(
        """\b(?:(?:nothing|none)\s+(?:was|were)\s+|(?:was|were)\s+not\s+|""" +
            """(?:has|have|had)\s+not\s+been\s+|never\s+|not\s+|""" +
            """(?:did\s+not|didn't|do\s+not|don't|could\s+not|couldn't)\s+)""" +
            """(?:keep|kept|save|saved|store|stored|file|filed|write|written|wrote|download|downloaded|place|placed)\b""" +
            """|\bnothing\s+was\s+kept\b""",
    )

    /**
     * A path the agent says it kept. Cosmetic only: it is never used for anything but the
     * success line, and a reply that names none still counts as collected when the transfer
     * completed (the app says so instead of inventing a path).
     */
    private fun keptPath(text: String): String {
        // A URL is not a path the agent kept: take every scheme token out before looking.
        val plain = URL.replace(text, " ")
        for (m in PATH.findAll(plain)) {
            val before = plain.substring(0, m.range.first).takeLast(8).lowercase(Locale.US)
            if (before.endsWith("http:") || before.endsWith("https:")) continue
            if (before.endsWith("http:/") || before.endsWith("https:/")) continue
            if (m.value.startsWith("//")) continue
            val v = m.value.substringAfterLast('`').substringBefore('`')
                .trimEnd('.', ',', ';', ')', ']', '>', '"', '\'')
            if (v.length in 2..200) return v
        }
        return ""
    }

    private val PATH = Regex("""(?:~|/)[A-Za-z0-9._~@%+\-/]{2,}""")

    private val URL = Regex("""[a-zA-Z][a-zA-Z0-9+.-]*://\S+""")

    // ---- small, pinned helpers ----

    /**
     * Percent-encode a file name for the marker grammar. Unreserved characters
     * (`A-Z a-z 0-9 - . _ ~`) pass through; everything else — space, `=`, `&`, the quote
     * family, every non-ASCII byte — becomes `%XX` of its UTF-8 bytes.
     *
     * Not `URLEncoder.encode`: that is form encoding, which turns a space into `+`. A `+` in
     * a file name is a literal plus, so form encoding would make the round trip lossy.
     */
    fun encode(name: String): String {
        val sb = StringBuilder()
        for (b in name.toByteArray(StandardCharsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if (c < 0x80 && isUnreserved(c.toChar())) sb.append(c.toChar())
            else sb.append('%').append(ENCODE_HEX[c ushr 4]).append(ENCODE_HEX[c and 0x0F])
        }
        return sb.toString()
    }

    /** The inverse of [encode]; only used by tests and by anyone debugging a turn. */
    fun decode(encoded: String): String {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < encoded.length) {
            val c = encoded[i]
            if (c == '%' && i + 2 < encoded.length) {
                val hi = Character.digit(encoded[i + 1], 16)
                val lo = Character.digit(encoded[i + 2], 16)
                if (hi >= 0 && lo >= 0) {
                    out.write((hi shl 4) or lo); i += 3; continue
                }
            }
            out.write(c.toString().toByteArray(StandardCharsets.UTF_8))
            i++
        }
        return String(out.toByteArray(), StandardCharsets.UTF_8)
    }

    private fun isUnreserved(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~'

    /** The mimes that are treated as text before the bytes are even looked at. */
    private val TEXT_TYPES = setOf(
        "application/json", "application/x-ndjson", "application/xml",
        "application/javascript", "application/x-javascript", "application/yaml",
        "application/x-yaml", "application/toml", "application/sql", "application/x-sh",
        "application/x-httpd-php", "application/x-tex", "application/rtf",
    )

    fun isTextType(mime: String): Boolean {
        val m = mime.substringBefore(';').trim().lowercase(Locale.US)
        return m.startsWith("text/") || m in TEXT_TYPES || m.endsWith("+json") || m.endsWith("+xml")
    }

    /** True when [s] can be fenced and read back: no NUL, no replacement characters. */
    fun looksTextual(s: String): Boolean = s.indexOf('\u0000') < 0 && s.indexOf('\uFFFD') < 0

    /** Strict UTF-8, or null when the bytes are not text (a binary file mislabelled text). */
    fun decodeUtf8(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
            .takeIf { looksTextual(it) }
    } catch (_: Throwable) {
        null
    }

    /** A fence wider than any run of backticks in the body (minimum three). */
    fun fenceWidth(text: String): Int {
        var longest = 0
        var run = 0
        for (c in text) {
            if (c == '`') { run++; if (run > longest) longest = run } else run = 0
        }
        return maxOf(3, longest + 1)
    }

    /**
     * A fence label the name can safely become: the name itself when it holds no backtick,
     * newline or carriage return; otherwise the percent-encoded form (which holds none).
     */
    private fun safeFenceLabel(name: String): String {
        val n = name.ifBlank { "file" }
        return if (n.any { it == '`' || it == '\n' || it == '\r' }) encode(n) else n
    }

    /** A mime value that cannot break the marker line: no whitespace. */
    private fun token(value: String, fallback: String): String {
        val v = value.trim()
        return if (v.isEmpty() || v.any { it.isWhitespace() }) fallback else v
    }

    /** Lowercase hex — the hash shape the turn carries and the agent verifies. */
    fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            sb.append(HEX[c ushr 4]).append(HEX[c and 0x0F])
        }
        return sb.toString()
    }

    /** Hashes are lowercase; percent-encoding is uppercase (RFC 3986's recommendation). */
    private val HEX = "0123456789abcdef".toCharArray()
    private val ENCODE_HEX = "0123456789ABCDEF".toCharArray()

    /** SHA-256 of a byte array, as the turn shows it. */
    fun sha256(bytes: ByteArray): String =
        hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** SHA-256 of a staged file, streamed so a 100 MB file is never in memory twice. */
    fun sha256(file: java.io.File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
    }
}
