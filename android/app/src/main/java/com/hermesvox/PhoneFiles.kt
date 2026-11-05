package com.hermesvox

import java.io.File

/**
 * PhoneFiles — the rules of the `files` group, pure so they are proven without a phone.
 *
 * Three actions, all about what is **inside a folder the owner granted**: `file_list` says what is
 * there, `file_read` returns one file's text, `file_offer` hands one file to the agent through the
 * existing file bridge. A *name* is not a path: a name with `..`, an absolute path, a separator or
 * a URI is refused `bad_path` before any provider is asked, and a document that resolves outside
 * the granted tree is refused the same way afterwards. A *listing path* is a **relative**
 * subdirectory inside the same tree, and it is refused by the same rule when it is absolute or
 * climbs out.
 *
 * Listing is capable, not cautious: [MAX_DEPTH] and [MAX_LIST_LIMIT] bound one *request*, never
 * what the folder is allowed to contain. A folder with more entries than one page says
 * `has_more` and hands back `next_offset`; it is paged, never refused and never silently cut.
 * Listing reads directory metadata only — it never opens a file.
 *
 * ## The cap, and why a window is the answer
 *
 * [MAX_READ_BYTES] is **65 536 bytes (64 KiB) of text**, the default size of one `file_read`.
 * A file above it is not refused: `file_read` takes `offset` and `length` and returns that window
 * with `truncated: true`, the total `bytes` and the offset it actually started at, so a large file
 * is readable in pieces that each fit the mind's context. Without `offset`/`length` the whole-file
 * path applies, and a file over the cap then answers `too_big` that says to ask for a window.
 * `file_offer` has no such cap: it does not read the bytes into the answer, it stages them and
 * serves them, so it keeps [FileOffer.MAX_BYTES] (100 MB) from the bridge.
 *
 * The Android edge is [PhoneFileAccess]: the Storage Access Framework, a tree the owner picked with
 * the system picker, and no broad storage permission declared for it. All-files access would be a
 * separate opt-in on Android's own screen, and the owner's decision — see `docs/MCP-SURFACE.md`.
 */
object PhoneFiles {

    /** The most text one whole-file `file_read` will return. Larger files are read in windows. */
    const val MAX_READ_BYTES = 64 * 1024L

    /** A file name, not a document. Long enough for any real name, short enough to audit. */
    const val MAX_NAME_CHARS = 255

    /** One level is `what is in Downloads`; a deeper call says so. */
    const val DEFAULT_DEPTH = 1

    /** The deepest walk one call may ask for. Deeper is `invalid-arguments`, not a refusal to list. */
    const val MAX_DEPTH = 5

    /** How many entries one page returns when the caller does not say. */
    const val DEFAULT_LIST_LIMIT = 200

    /**
     * The most one page returns. This is **pagination, not a cap on the truth**: a larger folder is
     * walked in pages with `has_more` and `next_offset`, and the caller can ask for the rest.
     */
    const val MAX_LIST_LIMIT = 1000

    /** A name filter is a glob, not a document. */
    const val MAX_PATTERN_CHARS = 255

    /** A relative directory path inside the granted tree: deep enough, short enough to audit. */
    const val MAX_LIST_PATH_CHARS = 1024

    /** The two folder words the tool accepts. A granted folder's own name may differ. */
    val FOLDERS = listOf("Downloads", "Documents")

    /** The folder a call means when it omits `folder`. */
    const val DEFAULT_FOLDER = "Downloads"

    /**
     * Why [name] is not a file name this action will look up, or null when it is safe.
     *
     * The rule is strict on purpose: this is the one action that reads the owner's storage, so a
     * name that *could* be read as a path is refused rather than normalised. `..` is refused
     * wherever it appears (a file really called `notes..txt` is a casualty, and that is the
     * honest trade); an absolute path, a separator and a URI are refused because they describe
     * something other than one name inside the granted folder.
     */
    fun badPath(name: String): String? {
        val n = name
        return when {
            n.isBlank() -> "name is required and must be the file's name"
            n.length > MAX_NAME_CHARS ->
                "the name is longer than $MAX_NAME_CHARS characters"
            n.contains("..") ->
                "a name containing \"..\" is refused: a file is looked up inside the folder you " +
                    "granted, and nowhere else"
            n.startsWith("/") || n.startsWith("\\") || WINDOWS_ABSOLUTE.containsMatchIn(n) ->
                "an absolute path is refused: give the file's name inside the folder you granted"
            n.contains('/') || n.contains('\\') ->
                "a name with a path separator is refused: this action reads one named file, it " +
                    "does not walk folders"
            n.contains("://") ->
                "a URI is refused: give the file's name inside the folder you granted"
            n.any { it < ' ' } ->
                "a name with a control character is refused"
            else -> null
        }
    }

    /** `C:\…` / `C:/…` — an absolute path in the shape a Windows-shaped name arrives in. */
    private val WINDOWS_ABSOLUTE = Regex("^[A-Za-z]:[\\\\/]")

    /**
     * Why [pattern] is not a name glob this action will apply, or null when it is usable.
     *
     * Any glob is a valid filter — `*` and `?` are the only wildcards — so the only refusals are a
     * pattern that is not a pattern at all: too long to be a name filter, or carrying a control
     * character. A pattern that matches nothing is not a refusal; it means no file matched.
     */
    fun badPattern(pattern: String): String? = when {
        pattern.length > MAX_PATTERN_CHARS ->
            "the pattern is longer than $MAX_PATTERN_CHARS characters"
        pattern.any { it < ' ' } ->
            "a pattern with a control character is refused"
        else -> null
    }

    /**
     * Why [path] is not a relative subdirectory inside the granted tree, or null when it is safe.
     *
     * `""` is the granted folder itself. A **relative** path may descend (`reports/2026`), because
     * listing is how the owner finds out what is there; it may not be absolute, climb (`..`), carry
     * a backslash or a URI, or contain an empty segment. The same shape is re-checked against the
     * provider's answer in [containedAt], so a path that lies about where it points is refused too.
     */
    fun badListPath(path: String): String? {
        val p = path
        return when {
            p.isEmpty() -> null
            p.length > MAX_LIST_PATH_CHARS ->
                "the path is longer than $MAX_LIST_PATH_CHARS characters"
            p.contains("..") ->
                "a path containing \"..\" is refused: listing walks inside the folder you granted, " +
                    "and nowhere else"
            p.startsWith("/") || p.startsWith("\\") || WINDOWS_ABSOLUTE.containsMatchIn(p) ->
                "an absolute path is refused: give a path relative to the folder you granted, or " +
                    "leave it out for the folder itself"
            p.contains('\\') ->
                "a backslash is refused: paths inside the granted folder use \"/\""
            p.contains("://") ->
                "a URI is refused: give a path relative to the folder you granted"
            p.any { it < ' ' } ->
                "a path with a control character is refused"
            p.split('/').any { it.isBlank() || it == "." } ->
                "a path with an empty or \".\" segment is refused"
            else -> null
        }
    }

    /**
     * The comparable form of a folder word: `Downloads` and `Download` are the same folder, and
     * the case the owner or the tool used does not decide whether a grant is found.
     */
    fun normalizeFolder(label: String?): String =
        (label?.trim().orEmpty().ifEmpty { DEFAULT_FOLDER }).lowercase().trimEnd('s')

    /**
     * True when [documentId] is exactly one child of [treeDocumentId] — nothing above it, nothing
     * nested below it, and no `..` in it.
     *
     * The Storage Access Framework identifies a document by id, not by path, so this is the
     * containment check that works for every provider: a provider that answers with an id outside
     * the granted tree (an escape, or its own idea of the tree) is refused.
     */
    fun contained(treeDocumentId: String, documentId: String): Boolean {
        val tree = treeDocumentId.trim().trimEnd('/')
        val doc = documentId.trim()
        if (tree.isEmpty() || doc.isEmpty()) return false
        val prefix = "$tree/"
        if (!doc.startsWith(prefix)) return false
        val rest = doc.removePrefix(prefix)
        return rest.isNotBlank() && !rest.contains('/') && !rest.contains("..")
    }

    /**
     * True when [documentId] is exactly [relativePath] below [treeDocumentId] — the listing's
     * counterpart to [contained]. `""` means the granted root itself.
     *
     * The relative path is checked segment by segment (never empty, never `.`, never `..`), and the
     * provider's id has to be the tree id plus exactly that path. A provider that answers with an
     * id somewhere else — an escape, or its own idea of the tree — is refused before anything below
     * it is listed.
     */
    fun containedAt(
        treeDocumentId: String,
        documentId: String,
        relativePath: String,
    ): Boolean {
        val tree = treeDocumentId.trim().trimEnd('/')
        val doc = documentId.trim()
        val rel = relativePath.trim().trimEnd('/')
        if (tree.isEmpty() || doc.isEmpty()) return false
        if (rel.isEmpty()) return doc == tree
        val segments = rel.split('/')
        if (segments.any { it.isBlank() || it == "." || it.contains("..") }) return false
        return doc == "$tree/$rel"
    }

    /**
     * True when [target] canonicalises inside [root]. This is the check for the one case a
     * document id cannot see: a symlink inside the granted folder that points somewhere else.
     * Android's external-storage provider maps its ids onto real paths, so the caller passes both
     * canonical files and a link that leaves the tree answers false.
     *
     * A target or root that cannot be canonicalised (a provider with no path at all) is **not**
     * treated as contained by this function; callers fall back to [contained] on document ids and
     * say so in the doc.
     */
    fun canonicalWithin(root: File?, target: File?): Boolean {
        if (root == null || target == null) return false
        val r = try { root.canonicalFile } catch (_: Throwable) { return false }
        val t = try { target.canonicalFile } catch (_: Throwable) { return false }
        val rootPath = r.path.trimEnd(File.separatorChar)
        return t.path == rootPath || t.path.startsWith(rootPath + File.separator)
    }

    /**
     * The bytes as text, or null when they are not text. Strict UTF-8 (a malformed sequence is a
     * binary file mislabelled, not something to replace with `�`), and a NUL byte is binary
     * whatever the decoder thinks.
     */
    fun asText(bytes: ByteArray): String? = FileOffer.decodeUtf8(bytes)

    /**
     * The one line a whole-file read gives when the file is over the cap: the size when it is known,
     * and the way to read it anyway — a window, not a refusal to ever open the file.
     */
    fun tooBig(bytes: Long?): String {
        val cap = "64 KiB (${MAX_READ_BYTES} bytes) of text"
        return if (bytes != null && bytes > 0) {
            "that file is ${FileOffer.size(bytes)} · a whole-file read returns at most $cap, so ask " +
                "again with offset and length to read it in windows (bytes: $bytes)"
        } else {
            "that file is larger than $cap · ask again with offset and length to read it in windows"
        }
    }

    /** The sentence the file actions give when no folder has been granted yet. */
    const val NO_FOLDER =
        "no folder is granted to your agent yet; the owner gives one in Phone tools → Files " +
            "(the system's own folder picker), and these actions then list and read inside it only"

    // ---- listing: what is in the folder the owner granted ----

    /** One directory entry as the provider described it. The lister supplies the relative path. */
    data class Entry(
        val name: String,
        val dir: Boolean,
        /** Null for a directory, and null when the provider would not say for a file. */
        val bytes: Long? = null,
        /** Already local ISO-8601, or null when the provider will not say. */
        val modified: String? = null,
        /** The provider's type for a file when it offered one; null for a directory or unknown. */
        val mime: String? = null,
    )

    /** One listed entry: where it was found (relative to the granted root) and what it is. */
    data class Row(
        val name: String,
        val path: String,
        val dir: Boolean,
        val bytes: Long?,
        val modified: String?,
        val mime: String?,
    ) {
        /** `dir` or `file` — the only two kinds a listing has. */
        val kind: String get() = if (dir) "dir" else "file"
    }

    /**
     * One page of one listing. [nextOffset] is null exactly when [hasMore] is false, so a caller
     * never has to guess whether there is more; [count] is this page's size, not the folder's.
     */
    data class Listing(
        val folder: String,
        val path: String,
        val depth: Int,
        val count: Int,
        val hasMore: Boolean,
        val nextOffset: Int?,
        val entries: List<Row>,
    )

    /** The outcome of a listing request, before it becomes a tool result. */
    sealed class ListResult {
        data class Ok(val listing: Listing) : ListResult()

        /** The granted folder has no directory at the requested relative path. */
        data object MissingPath : ListResult()

        /** A path, a name or a symlink resolved outside the granted tree. Nothing was listed. */
        data object Outside : ListResult()

        /** The provider failed or would not answer; [detail] says so and nothing is invented. */
        data class Failed(val detail: String) : ListResult()
    }

    /** The outcome of reading one directory's direct children. */
    sealed class Dir {
        data class Found(val entries: List<Entry>) : Dir()

        /** No such directory any more (a race with a delete, or a stale path). */
        data object Missing : Dir()

        /** The provider answered with something outside the tree, or a name that is a path. */
        data object Outside : Dir()

        /** The provider failed or would not answer. */
        data class Failed(val detail: String) : Dir()
    }

    /**
     * One directory's direct children by its path relative to the granted root (`""` = the root).
     *
     * Deliberately narrow: a listing can ask **which names are here and what their metadata says**,
     * and nothing else. There is no method that opens a file, so no implementation of this
     * interface — and no walk built on it — can read the contents of anything it lists.
     */
    fun interface Reader {
        fun children(path: String): Dir
    }

    /** Directories first, then files, each alphabetical (case-insensitively) and stable. */
    private val ORDER: Comparator<Entry> =
        compareBy<Entry>({ if (it.dir) 0 else 1 }, { it.name.lowercase() }, { it.name })

    /**
     * True when [pattern] matches [name], case-insensitively. A glob, not a regex: `*` is any run
     * of characters and `?` is exactly one; everything else is literal. Applied to **files only**,
     * so a directory stays visible while its matching files are shown.
     */
    fun matches(pattern: String?, name: String): Boolean {
        if (pattern.isNullOrEmpty()) return true
        val rx = StringBuilder("^")
        for (c in pattern) {
            when (c) {
                '*' -> rx.append(".*")
                '?' -> rx.append('.')
                else -> rx.append(Regex.escape(c.toString()))
            }
        }
        rx.append('$')
        return Regex(rx.toString(), RegexOption.IGNORE_CASE).matches(name)
    }

    /**
     * One page of a listing, walked through [reader].
     *
     * **What is enumerated, then what is shown.** The walk visits [path] and, while there is depth
     * left, each subdirectory below it; at every level directories come before files and each group
     * is alphabetical, so the order is stable and a page boundary never moves. [pattern] then hides
     * files whose name does not match, and directories are never hidden. The full ordered set is
     * what `offset` and `limit` page over, which is why `has_more` and `next_offset` exist rather
     * than a large folder being refused: a page is a window on the truth, never a claim about the
     * folder's size.
     *
     * Depth 1 lists the requested directory's entries; depth 2 also lists each subdirectory's, and
     * so on. Every entry carries the relative [Row.path] it was found at, so a deep listing is
     * unambiguous.
     *
     * A subdirectory that has vanished between the parent's listing and its own read is skipped (a
     * directory we no longer have is not an entry); a provider that **fails** while a page is being
     * built is [ListResult.Failed], because a page with a hole in it would be a quiet lie.
     */
    fun list(
        folder: String,
        path: String,
        depth: Int,
        pattern: String?,
        offset: Int,
        limit: Int,
        reader: Reader,
    ): ListResult {
        val levels = depth.coerceIn(1, MAX_DEPTH)
        val rows = ArrayList<Row>()
        var failure: String? = null
        var outside = false
        var missingRoot = false

        fun visit(rel: String, remaining: Int): Boolean {
            val children = when (val dir = reader.children(rel)) {
                is Dir.Found -> dir.entries
                // A subdirectory that vanished between the parent's listing and this read is a
                // race, not a refusal; the requested directory itself not existing is a missing
                // path, and the caller is told exactly that.
                Dir.Missing -> { if (rel == path) missingRoot = true; return true }
                Dir.Outside -> { outside = true; return false }
                is Dir.Failed -> { failure = dir.detail; return false }
            }
            for (e in children.sortedWith(ORDER)) {
                // A provider that calls a child "../secret" is describing something outside the
                // tree under a name that is a path. Refuse it rather than list a lie.
                if (badPath(e.name) != null) { outside = true; return false }
                val childPath = if (rel.isEmpty()) e.name else "$rel/${e.name}"
                if (e.dir || matches(pattern, e.name)) {
                    rows.add(
                        Row(
                            name = e.name,
                            path = childPath,
                            dir = e.dir,
                            bytes = if (e.dir) null else e.bytes,
                            modified = e.modified,
                            mime = if (e.dir) null else e.mime,
                        )
                    )
                }
                if (e.dir && remaining > 1 && !visit(childPath, remaining - 1)) return false
            }
            return true
        }

        val walked = visit(path, levels)
        if (!walked) {
            return when {
                outside -> ListResult.Outside
                failure != null -> ListResult.Failed(failure!!)
                else -> ListResult.MissingPath
            }
        }
        if (missingRoot) return ListResult.MissingPath

        val total = rows.size
        val from = offset.coerceIn(0, total)
        val to = (from + limit.coerceIn(1, MAX_LIST_LIMIT)).coerceAtMost(total)
        val page = rows.subList(from, to)
        val more = to < total
        return ListResult.Ok(
            Listing(
                folder = folder,
                path = path,
                depth = levels,
                count = page.size,
                hasMore = more,
                nextOffset = if (more) to else null,
                entries = ArrayList(page),
            )
        )
    }

    // ---- a window of a file ----

    /** The text one window actually yielded, and where it really begins and ends. */
    data class TextWindow(
        /** The absolute byte offset of [text]'s first byte — never before the requested start. */
        val offset: Long,
        /** Bytes covered by [text]; at most the requested length, never a byte more. */
        val bytes: Int,
        val text: String,
    )

    /**
     * The window `[start, start + want)` of a file, chosen **inward**: [bytes] was read from
     * absolute offset [from], and the result is trimmed to the first character boundary at or after
     * [start] and the last boundary at or before `start + want`.
     *
     * A window is cut at byte offsets the caller chose, so the cut may fall inside a character.
     * Snapping **inward** is what makes stitching honest: an agent that asks for `[0, 4)` and then
     * `[4, 8)` gets four bytes and four bytes, with no character duplicated at the seam. Snapping
     * outward (keeping the slack bytes on either side) would repeat up to three characters at every
     * seam while reporting an offset the text does not start at.
     *
     * The three bytes of slack the caller reads on each side are what makes the inward snap
     * possible: a UTF-8 character is at most four bytes, so a boundary within three bytes of each
     * cut is always inside the buffer. A malformed sequence inside the window is still not text
     * (unlike [asText], which decides that for a whole file); a NUL byte anywhere in the buffer is
     * binary whatever the decoder thinks.
     */
    fun window(bytes: ByteArray, from: Long, start: Long, want: Long): TextWindow? {
        if (bytes.any { it == 0.toByte() }) return null
        val begin = (start - from).coerceIn(0L, bytes.size.toLong()).toInt()
        val end = (begin.toLong() + want.coerceAtLeast(0)).coerceAtMost(bytes.size.toLong()).toInt()
        // First boundary at or after the requested start: a character never begins on a
        // continuation byte, so skip the ones that continue the character before the cut.
        var a = begin
        while (a < end && continuation(bytes[a])) a++
        // Last boundary at or before the requested end: if the cut landed inside a character,
        // back up to that character's first byte and leave the whole character out. At the end of
        // the buffer there is nothing to trim against: that is where the file stops.
        var b = end
        while (b > a && b < bytes.size && continuation(bytes[b])) b--
        val text = strictUtf8(bytes, a, b - a) ?: return null
        return TextWindow(from + a, b - a, text)
    }

    /** True for a UTF-8 continuation byte (`10xxxxxx`): such a byte never begins a character. */
    private fun continuation(b: Byte): Boolean = (b.toInt() and 0xC0) == 0x80

    /** Strict UTF-8 for exactly [len] bytes at [off]; null when they are not. */
    private fun strictUtf8(bytes: ByteArray, off: Int, len: Int): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes, off, len))
            .toString()
    } catch (_: Throwable) {
        null
    }
}
