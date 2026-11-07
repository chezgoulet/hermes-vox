package com.hermesvox

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * ShareSurface — the one place that decides what a shared *thing* is called and how it is
 * described (#370).
 *
 * The file mechanism itself is [FileOffer] / [FileShare] / [FileStage] (#386): it stages a
 * copy, hashes it, serves it once over the tailnet and builds the turn. This object adds no
 * second transport. It only answers the questions the new entry points raise:
 *
 *  * **a clipboard string is a file, not pasted text.** Its name is generated from the clock
 *    (`clipboard-2026-11-02T14-31.txt`); the extension comes from the mime it will carry.
 *  * **an image on the clipboard is a real image file**, named from its mime
 *    (`clipboard-2026-11-02T14-31.png`), because the bytes are copied, not decoded.
 *  * **a mime says what extension a generated name gets** — one table, so a `.txt`, a `.pdf`
 *    and a `.png` cannot disagree with the type shown on the card.
 *  * **an unreadable share is refused in words.** Every "nothing" path has a line; none of
 *    them returns an empty name and hopes the caller notices.
 *
 * Pure JVM: no Android type appears here, so the naming rules, the mime table and the refusal
 * lines are proven by `ShareSurfaceTest`. The clipboard read, the picker and the intent
 * dispatch live in `MainActivity`; the confirmation sheet is `ShareSheet`.
 */
object ShareSurface {

    /** The generated names' prefixes; a name is `<prefix>-<stamp>.<ext>`. */
    const val CLIPBOARD = "clipboard"
    const val SHARED = "shared"
    const val LINK = "link"

    const val TEXT_MIME = "text/plain"
    const val BINARY_MIME = "application/octet-stream"

    /** The one line for a clipboard with neither text nor a readable URI. */
    const val EMPTY_CLIPBOARD = "the clipboard is empty · copy something first"

    /** A share intent that carried neither a stream nor text. */
    const val NOTHING_SHARED = "this share had nothing Vox could read · nothing was sent"

    /** The confirmation's promise, repeated on every draft. */
    const val NOTHING_SENT = "nothing has been sent yet"

    /** Where an item came from; the sheet says so so the user knows what they are confirming. */
    enum class Source(val label: String) {
        CLIPBOARD("from the clipboard"),
        PICKER("from this device"),
        SHARE("shared from another app"),
        LINK("a link"),
    }

    /** What the clipboard held, after the one decision that matters. */
    enum class ClipKind { TEXT, URI, EMPTY }

    /**
     * The plan for a clipboard item. [name] is the generated file name, [mime] what the copy
     * will carry, and [text] the body when the item is text (null for a URI — its bytes come
     * from the content resolver, not from us).
     */
    data class ClipPlan(
        val kind: ClipKind,
        val name: String,
        val mime: String,
        val text: String? = null,
        val line: String = "",
    )

    /**
     * Turn the clipboard into a file plan.
     *
     * A non-blank string is text, whatever it says — a URL, a path, a code block and an
     * ordinary sentence all become the same kind of artifact, with the same `.txt` extension.
     * That is deliberate: the unit of this interaction is the *file*, not the string's shape.
     * A URI (the clipboard's image/other content) keeps its bytes and takes an extension from
     * its mime. A clip with neither is refused with [EMPTY_CLIPBOARD].
     */
    fun clipboardPlan(
        text: String?,
        uriMime: String?,
        hasUri: Boolean,
        nowMs: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): ClipPlan {
        if (text != null && text.isNotBlank()) {
            return ClipPlan(
                kind = ClipKind.TEXT,
                name = textName(CLIPBOARD, nowMs, zone),
                mime = TEXT_MIME,
                text = text,
            )
        }
        if (hasUri) {
            val mime = uriMime?.takeIf { it.isNotBlank() } ?: BINARY_MIME
            return ClipPlan(
                kind = ClipKind.URI,
                name = generatedName(CLIPBOARD, mime, nowMs, zone),
                mime = mime,
            )
        }
        return ClipPlan(ClipKind.EMPTY, "", "", line = EMPTY_CLIPBOARD)
    }

    /** `clipboard-2026-11-02T14-31.txt` — colons are not legal in a file name. */
    fun textName(prefix: String, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): String =
        "$prefix-${stamp(nowMs, zone)}.txt"

    /** `clipboard-2026-11-02T14-31.png`, from the mime's extension. */
    fun generatedName(
        prefix: String,
        mime: String,
        nowMs: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): String = "$prefix-${stamp(nowMs, zone)}.${extensionFor(mime)}"

    /** A name for a stream whose provider did not give one. */
    fun sharedName(mime: String, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): String =
        generatedName(SHARED, mime, nowMs, zone)

    /** A name for a viewed web link, which travels as a small text file. */
    fun linkName(nowMs: Long, zone: TimeZone = TimeZone.getDefault()): String =
        textName(LINK, nowMs, zone)

    /** `2026-11-02T14-31` — sortable, single-line, and safe on every filesystem. */
    fun stamp(nowMs: Long, zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH-mm", Locale.US)
            .apply { timeZone = zone }
            .format(Date(nowMs))

    /**
     * The extension a mime gets when the app has to invent a name. The table is explicit for
     * the types a share sheet actually produces; anything else falls back to its subtype when
     * that is a short token, and to `bin` when it is not (`application/vnd…` is not a file
     * name). A named file from a provider keeps the provider's name — this is only for names
     * the app generates.
     */
    fun extensionFor(mime: String): String {
        val m = mime.substringBefore(';').trim().lowercase(Locale.US)
        if (m.isEmpty()) return "bin"
        EXTENSIONS[m]?.let { return it }
        if (m.endsWith("+json")) return "json"
        if (m.endsWith("+xml")) return "xml"
        val subtype = m.substringAfter('/', "")
        return if (subtype.matches(Regex("[a-z0-9]{1,8}")) && subtype != "octet-stream") subtype else "bin"
    }

    /** The type and size, as the confirmation card and the transcript card say them. */
    fun describe(file: FileOffer.File): String = "${file.mime} · ${FileOffer.size(file.bytes)}"

    /** `note.txt · text/plain · 13 bytes` — the card's one line. */
    fun previewLine(file: FileOffer.File): String = "${file.name} · ${describe(file)}"

    /** One file per share is the current rule; a multiple share says so instead of dropping. */
    fun multipleLine(count: Int): String =
        "one file at a time · you shared $count files; share them one by one"

    /** A VIEW of something the app cannot turn into a file. */
    fun unsupportedView(scheme: String?): String {
        val s = scheme?.trim()?.lowercase(Locale.US)
        return if (s.isNullOrEmpty()) {
            "this link has no scheme Vox can read · nothing was sent"
        } else {
            "Vox can open files and web links · this is a $s: link"
        }
    }

    private val EXTENSIONS = mapOf(
        "text/plain" to "txt",
        "text/markdown" to "md",
        "text/x-markdown" to "md",
        "text/html" to "html",
        "text/csv" to "csv",
        "text/xml" to "xml",
        "text/rtf" to "rtf",
        "application/json" to "json",
        "application/xml" to "xml",
        "application/pdf" to "pdf",
        "application/rtf" to "rtf",
        "application/zip" to "zip",
        "application/gzip" to "gz",
        "application/x-tar" to "tar",
        "application/msword" to "doc",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.ms-excel" to "xls",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "application/vnd.ms-powerpoint" to "ppt",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
        "image/png" to "png",
        "image/jpeg" to "jpg",
        "image/gif" to "gif",
        "image/webp" to "webp",
        "image/heic" to "heic",
        "image/heif" to "heif",
        "image/bmp" to "bmp",
        "image/svg+xml" to "svg",
        "audio/mpeg" to "mp3",
        "audio/mp4" to "m4a",
        "audio/ogg" to "ogg",
        "audio/wav" to "wav",
        "video/mp4" to "mp4",
        "video/webm" to "webm",
    )
}
