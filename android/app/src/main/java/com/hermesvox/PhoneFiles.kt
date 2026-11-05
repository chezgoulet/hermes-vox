package com.hermesvox

import java.io.File

/**
 * PhoneFiles — the rules of the `files` group, pure so they are proven without a phone.
 *
 * Two actions, both about **one file the owner named inside a folder the owner granted**:
 * `file_read` returns its text, `file_offer` hands it to the agent through the existing file
 * bridge. Neither ever walks a directory. The name is not a path: a name with `..`, an absolute
 * path, a separator or a URI is refused `bad_path` before any provider is asked, and a document
 * that resolves outside the granted tree is refused the same way afterwards.
 *
 * ## The cap
 *
 * [MAX_READ_BYTES] is **65 536 bytes (64 KiB) of text**, and a file above it is **refused, not
 * truncated**. The reasoning: a `file_read` result is pasted into the mind's context, so its whole
 * point is that the mind can use the text in one turn; 64 KiB is already roughly sixteen thousand
 * tokens — a long script, a full note, a day of a log — while a larger file would either crowd out
 * the conversation or have to be silently cut, which would make the answer a lie about the file.
 * `file_offer` has no such cap: it does not read the bytes into the answer, it stages them and
 * serves them, so it keeps [FileOffer.MAX_BYTES] (100 MB) from the bridge.
 *
 * The Android edge is [PhoneFileAccess]: Storage Access Framework only, no broad storage
 * permission, no `MANAGE_EXTERNAL_STORAGE`, and a tree the owner picked with the system picker.
 */
object PhoneFiles {

    /** The most text `file_read` will return. Above it, the call is refused — never truncated. */
    const val MAX_READ_BYTES = 64 * 1024L

    /** A file name, not a document. Long enough for any real name, short enough to audit. */
    const val MAX_NAME_CHARS = 255

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

    /** The one line a refused read gives: the size when it is known, and the cap either way. */
    fun tooBig(bytes: Long?): String {
        val cap = "64 KiB (${MAX_READ_BYTES} bytes) of text"
        return if (bytes != null && bytes > 0) {
            "that file is ${FileOffer.size(bytes)} · the most file_read returns is $cap, and a " +
                "larger file is refused rather than cut short"
        } else {
            "that file is larger than $cap · the most file_read returns is $cap, and a larger " +
                "file is refused rather than cut short"
        }
    }

    /** The sentence both actions give when no folder has been granted yet. */
    const val NO_FOLDER =
        "no folder is granted to your agent yet; the owner gives one in Phone tools → Files " +
            "(the system's own folder picker), and these actions then read inside it only"
}
