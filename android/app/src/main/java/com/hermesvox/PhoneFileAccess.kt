package com.hermesvox

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.hermesvox.core.JArr
import com.hermesvox.core.JObj
import com.hermesvox.core.JStr
import com.hermesvox.core.Json
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * PhoneFileAccess — the Android edge of `file_list`, `file_read` and `file_offer`: the folder the
 * owner granted, what is directly inside it, one file named inside it, and nothing else.
 *
 * **The grant is the owner's, made in the system picker.** [intent] is `ACTION_OPEN_DOCUMENT_TREE`
 * (read-only — this app never writes into the tree); [accept] takes the persistable permission the
 * picker returned and remembers the tree by **its own id** plus its own display name. Without that
 * grant all three actions refuse `no_folder`, and there is no other way in: **the Storage Access
 * Framework is the deliberate default, and this app declares no broad storage permission for it.**
 * An all-files mode is a decision the owner could be offered later through Android's own
 * `MANAGE_EXTERNAL_STORAGE` screen; it is not a capability this file withholds on their behalf.
 *
 * **The folder really is a subfolder.** Android 11+ refuses to grant the `Download` directory
 * itself, so the picker opens there and the owner picks one of its subfolders (`Download/Work`).
 * That subfolder's label is what `tools/list` advertises as `folder`, and the id — not the label —
 * is how [trees] keys the grant, so a `Work` under Download and a `Work` under Documents are two
 * grants, not one.
 *
 * **A name is looked up, never walked.** The document id is `treeDocumentId + "/" + name`, which is
 * one child of the granted tree by construction; [PhoneFiles.contained] re-checks the id the
 * provider answers with, so a provider that maps the name outside the tree is refused `bad_path`
 * rather than followed. On the platform's primary shared volume the id also names a real path, and
 * [PhoneFiles.canonicalWithin] refuses a symlink that leaves the tree — the one escape a document id
 * cannot show. For a provider with no filesystem path (a cloud tree) the id check is the whole
 * check, which `docs/MCP-SURFACE.md` states rather than pretending otherwise.
 *
 * **Listing walks only inside the same grant**, one directory at a time, through the same id rule
 * ([PhoneFiles.containedAt] for the requested path, [PhoneFiles.contained] for every child) plus the
 * same canonical check. [list] reads directory metadata and never opens a file; the ordering,
 * pattern and paging are [PhoneFiles.list]. A folder with more entries than a page is paged, never
 * refused.
 *
 * `file_read` without a window copies at most [PhoneFiles.MAX_READ_BYTES] + 1 bytes and answers
 * `too_big` above it; with `offset`/`length` it reads exactly that window ([readWindow]) and says
 * `truncated` with the total. It never silently cuts. `file_offer` hands the document to [FileStage]
 * and [FileServer] — the same bridge the share sheet uses — so the agent fetches an artifact, not a
 * base64 blob in a tool result.
 */
object PhoneFileAccess {

    private const val PREFS = "hv"
    private const val KEY_TREES = "phone_file_trees"

    /** At most this many offers are held open at once; the oldest is closed to make room. */
    private const val MAX_OPEN_OFFERS = 4

    /**
     * The most bytes [countedSize] will stream to learn a file's size when the provider reports
     * neither [DocumentsContract.Document.COLUMN_SIZE] nor a descriptor length. Above this the size
     * is treated as unknowable rather than counted to the end on the main thread: a 500 MB log is
     * not something to read twice in a voice call.
     */
    private const val MAX_COUNT_BYTES = 8L * 1024 * 1024

    /**
     * One folder the owner granted. [id] is the tree's **own** document id (`primary:Download/Work`)
     * and it is the key: the grant is found by the id the owner picked, not by a word we invented.
     * [label] is the folder's own name, which is what a call may say.
     */
    data class Tree(val uri: String, val id: String, val label: String)

    private data class Doc(
        val uri: Uri,
        val displayName: String,
        val size: Long?,
        val mime: String?,
    )

    private sealed class Lookup {
        data class Found(val doc: Doc) : Lookup()
        data object Missing : Lookup()
        data object Outside : Lookup()
        data class Failed(val detail: String) : Lookup()
    }

    /** The trees the owner has granted, in the order they were granted. */
    fun trees(context: Context): List<Tree> {
        val stored = prefs(context).getString(KEY_TREES, null) ?: return emptyList()
        val root = try {
            Json.parse(stored)
        } catch (_: Throwable) {
            return emptyList()
        }
        val items = (root as? JArr)?.items ?: return emptyList()
        val out = ArrayList<Tree>(items.size)
        for (item in items) {
            val o = item as? JObj ?: continue
            val uri = (o["uri"] as? JStr)?.value?.trim().orEmpty()
            val label = (o["label"] as? JStr)?.value?.trim().orEmpty()
            if (uri.isEmpty()) continue
            // The id is derived from the URI when it is not stored (a grant from before the tree
            // was keyed by its own id); the URI is the truth either way.
            val id = (o["id"] as? JStr)?.value?.trim().orEmpty()
                .ifEmpty { idOf(Uri.parse(uri)) }
            out.add(Tree(uri, id, label.ifEmpty { labelOf(Uri.parse(uri)) }))
        }
        return out
    }

    /**
     * The picker intent. Read-only and persistable: the grant has to outlive the sheet.
     *
     * It opens on the **`Download`** folder. That folder itself cannot be granted on Android 11+ —
     * the picker answers "Can't use this folder" — so the owner picks a **subfolder** of it
     * (`Download/Work`), and that subfolder's own name is what the tool advertises.
     */
    fun intent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
        // Best effort: a device or picker that does not honour the initial URI still shows its own
        // last location, and the grant works the same.
        runCatching {
            putExtra(
                DocumentsContract.EXTRA_INITIAL_URI,
                DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE, "primary:Download"),
            )
        }
    }

    /** The external-storage documents provider: the one whose ids are `primary:…` paths. */
    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    /**
     * Take the picker's answer: persist the read grant, remember the folder's own name, and return
     * it so the sheet can say which folder it got. Null when nothing was picked or Android refused
     * to make the grant persistable — in which case nothing is stored and the actions still refuse
     * `no_folder`.
     */
    fun accept(context: Context, data: Intent?): Tree? {
        val uri = data?.data ?: return null
        val label = labelOf(uri)
        val id = idOf(uri)
        if (id.isEmpty()) return null
        return try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            val next = ArrayList(trees(context))
            // The same tree picked again replaces its old grant, which is released so the provider
            // is not left holding two. The **tree id** is the identity: two folders can share a
            // label (`Work` under Download and under Documents), and they are different grants.
            next.firstOrNull { it.id == id }
                ?.let { old ->
                    next.remove(old)
                    runCatching {
                        context.contentResolver.releasePersistableUriPermission(
                            Uri.parse(old.uri),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
            next.add(Tree(uri.toString(), id, label))
            save(context, next)
            VoxLog.d("event=phone-files folder=$label tree=$id")
            Tree(uri.toString(), id, label)
        } catch (t: Throwable) {
            VoxLog.w("event=phone-files grant-failed reason=${t.javaClass.simpleName}")
            null
        }
    }

    private fun save(context: Context, trees: List<Tree>) {
        val encoded = Json.encode(
            trees.map { linkedMapOf("uri" to it.uri, "id" to it.id, "label" to it.label) }
        )
        prefs(context).edit().putString(KEY_TREES, encoded).apply()
    }

    /** The tree's own document id — the key a grant is stored and found by. */
    private fun idOf(uri: Uri): String =
        runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull().orEmpty()

    /** The tree's own display name, or its last id segment when the provider will not say. */
    private fun labelOf(uri: Uri): String {
        val id = idOf(uri)
        return id.substringAfterLast(':').substringAfterLast('/').ifBlank { "folder" }
    }

    /** The text of [name] inside [tree], or the reason it was not read. */
    fun read(context: Context, tree: Tree, name: String): PhoneEngine.PhonePlatform.FileText {
        val doc = when (val found = document(context, tree, name)) {
            is Lookup.Found -> found.doc
            Lookup.Missing -> return PhoneEngine.PhonePlatform.FileText.Missing
            Lookup.Outside -> return PhoneEngine.PhonePlatform.FileText.Outside
            is Lookup.Failed -> return PhoneEngine.PhonePlatform.FileText.Failed(found.detail)
        }
        if (doc.size != null && doc.size > PhoneFiles.MAX_READ_BYTES) {
            return PhoneEngine.PhonePlatform.FileText.TooBig(doc.size)
        }
        val bytes = try {
            val input = context.contentResolver.openInputStream(doc.uri)
                ?: return PhoneEngine.PhonePlatform.FileText.Failed(
                    "the folder would not open that file · nothing was read",
                )
            input.use { src ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8 * 1024)
                var total = 0L
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    total += n
                    // One byte past the cap is all that is needed to know it is over it.
                    if (total > PhoneFiles.MAX_READ_BYTES) {
                        return PhoneEngine.PhonePlatform.FileText.TooBig(null)
                    }
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } catch (t: Throwable) {
            VoxLog.w("event=phone-files read-failed reason=${t.javaClass.simpleName}")
            return PhoneEngine.PhonePlatform.FileText.Failed("the file could not be read · nothing was read")
        }
        val text = PhoneFiles.asText(bytes)
            ?: return PhoneEngine.PhonePlatform.FileText.NotText
        return PhoneEngine.PhonePlatform.FileText.Found(doc.displayName, bytes.size.toLong(), text)
    }

    /**
     * Stage [name] inside [tree] and serve it once over the tailnet, the same way the share sheet
     * does. The result carries the capability URL, the size and the hash — never the bytes.
     */
    fun offer(context: Context, tree: Tree, name: String): PhoneEngine.PhonePlatform.FileHandoff {
        val doc = when (val found = document(context, tree, name)) {
            is Lookup.Found -> found.doc
            Lookup.Missing -> return PhoneEngine.PhonePlatform.FileHandoff.Missing
            Lookup.Outside -> return PhoneEngine.PhonePlatform.FileHandoff.Outside
            is Lookup.Failed -> return PhoneEngine.PhonePlatform.FileHandoff.Unavailable(found.detail)
        }
        val staged = when (val result = FileStage.stage(context, doc.uri, doc.size)) {
            is FileStage.Result.Ok -> result.staged
            is FileStage.Result.TooBig ->
                return PhoneEngine.PhonePlatform.FileHandoff.TooBig(result.bytes)
            is FileStage.Result.Failed ->
                return PhoneEngine.PhonePlatform.FileHandoff.Unavailable(result.line)
        }
        val host = Tailnet.address()?.hostAddress
        if (host.isNullOrBlank()) {
            staged.file.delete()
            return PhoneEngine.PhonePlatform.FileHandoff.Unavailable(
                "Tailscale is not connected, so there is no address your agent could fetch from · " +
                    "nothing was offered",
            )
        }
        return try {
            val bind = java.net.InetAddress.getByName(host)
            val token = FileOffer.token()
            val expiresAtMs = System.currentTimeMillis() + FileOffer.EXPIRY_MS
            val expiresAtSec = FileOffer.expiresAt(System.currentTimeMillis())
            val serverRef = java.util.concurrent.atomic.AtomicReference<FileServer?>(null)
            val server = FileServer.start(
                bind = bind,
                token = token,
                file = staged.file,
                bytes = staged.offer.bytes,
                mime = staged.offer.mime,
                sha256 = staged.offer.sha256,
                expiresAtMs = expiresAtMs,
                log = { line -> VoxLog.d(line) },
                onTransfer = {
                    // The agent has the bytes; the private copy has done its job.
                    staged.file.delete()
                    serverRef.get()?.let { closeLater(it) }
                },
            )
            serverRef.set(server)
            hold(server)
            scheduleOnDaemon(FileOffer.EXPIRY_MS) {
                staged.file.delete()
                closeLater(server)
            }
            PhoneEngine.PhonePlatform.FileHandoff.Ready(
                name = staged.offer.name,
                mime = staged.offer.mime,
                bytes = staged.offer.bytes,
                sha256 = staged.offer.sha256,
                url = FileOffer.url(bind.hostAddress ?: host, server.port, token),
                expiresAtSec = expiresAtSec,
            )
        } catch (t: Throwable) {
            staged.file.delete()
            VoxLog.w("event=phone-files offer-failed reason=${t.javaClass.simpleName}")
            PhoneEngine.PhonePlatform.FileHandoff.Unavailable(
                "the file could not be offered: this phone could not start its file server · " +
                    "nothing was sent",
            )
        }
    }

    /**
     * One window of [name] inside [tree]: [length] bytes from [offset], snapped **inward** to
     * character boundaries, with the file's **total** size so the result can say whether it is a
     * window.
     *
     * This is the answer for a file above [PhoneFiles.MAX_READ_BYTES]: the caller asks for a piece
     * small enough to use, and keeps asking until it has what it needs. Nothing is cut silently and
     * nothing is refused for its size. A window that ends before the file does, or starts after its
     * beginning, is not the whole file — the engine reports `truncated: true` for exactly that.
     */
    fun readWindow(
        context: Context,
        tree: Tree,
        name: String,
        offset: Long,
        length: Long,
    ): PhoneEngine.PhonePlatform.FileText {
        val doc = when (val found = document(context, tree, name)) {
            is Lookup.Found -> found.doc
            Lookup.Missing -> return PhoneEngine.PhonePlatform.FileText.Missing
            Lookup.Outside -> return PhoneEngine.PhonePlatform.FileText.Outside
            is Lookup.Failed -> return PhoneEngine.PhonePlatform.FileText.Failed(found.detail)
        }
        val total = doc.size ?: sizeOf(context, doc.uri) ?: return PhoneEngine.PhonePlatform
            .FileText.Failed("the file's size could not be read · nothing was read")
        val start = offset.coerceIn(0, total)
        if (start >= total) {
            // Reading at or past the end is an empty window, not a failure: the caller asked for a
            // place the file does not have, and the total says where it does end.
            return PhoneEngine.PhonePlatform.FileText.Window(
                doc.displayName, total, start, 0, "",
            )
        }
        // Second line of defence: the tool refuses a `length` above the cap, and a caller that got
        // here some other way is still held to it. A window is never larger than one whole read.
        val want = length.coerceAtLeast(1)
            .coerceAtMost(PhoneFiles.MAX_READ_BYTES)
            .coerceAtMost(total - start)
        // Three bytes of slack on each side, so the inward snap in [PhoneFiles.window] has a whole
        // character to find on either side of the cut. The text itself never extends past `want`.
        val from = (start - 3).coerceAtLeast(0)
        val slice = readRange(context, doc.uri, from, (start - from) + want + 3)
            ?: return PhoneEngine.PhonePlatform.FileText.Failed(
                "the file could not be read · nothing was read",
            )
        val window = PhoneFiles.window(slice, from, start, want)
            ?: return PhoneEngine.PhonePlatform.FileText.NotText
        return PhoneEngine.PhonePlatform.FileText.Window(
            name = doc.displayName,
            totalBytes = total,
            offset = window.offset,
            windowBytes = window.bytes.toLong(),
            text = window.text,
        )
    }

    /**
     * What is inside [path] in [tree], to [depth], filtered by [pattern] and paged by
     * [offset]/[limit]. The rule is [PhoneFiles.list]; this is the provider edge, and it only ever
     * asks **which children a directory has and what their metadata says** — [children] has no way
     * to open a file, and nothing here does.
     *
     * The requested path is checked against the tree id before the provider is asked
     * ([PhoneFiles.containedAt]), and every child is checked again after
     * ([PhoneFiles.contained] and, on the primary volume, [PhoneFiles.canonicalWithin]) so a name
     * that is a path, a foreign document id, or a symlink out of the tree is `bad_path`.
     */
    fun list(
        context: Context,
        tree: Tree,
        path: String,
        depth: Int,
        pattern: String?,
        offset: Int,
        limit: Int,
    ): PhoneFiles.ListResult {
        val treeUri = try {
            Uri.parse(tree.uri)
        } catch (_: Throwable) {
            return PhoneFiles.ListResult.Failed(
                "the granted folder is unreadable · grant it again in Phone tools",
            )
        }
        val treeId = tree.id.ifEmpty {
            runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull().orEmpty()
        }
        if (treeId.isEmpty()) {
            return PhoneFiles.ListResult.Failed(
                "the granted folder is unreadable · grant it again in Phone tools",
            )
        }
        // The requested subdirectory is one relative path inside the tree, and nothing else.
        val startId = if (path.isEmpty()) treeId else "$treeId/$path"
        if (!PhoneFiles.containedAt(treeId, startId, path)) return PhoneFiles.ListResult.Outside

        val root = primaryPath(treeId)
        val reader = PhoneFiles.Reader { rel ->
            val dirId = if (rel.isEmpty()) treeId else "$treeId/$rel"
            if (!PhoneFiles.containedAt(treeId, dirId, rel)) PhoneFiles.Dir.Outside
            else children(context, treeUri, dirId, root)
        }
        return PhoneFiles.list(tree.label, path, depth, pattern, offset, limit, reader)
    }

    /**
     * The direct children of [dirId]: id, name, size, type and modified time — metadata only.
     *
     * `query` is the only call here, and it is a `DocumentsContract` child query: it lists a
     * directory, it does not read a document. Each child's id must be exactly one segment below
     * [dirId], and on the primary shared volume its canonical path must stay inside [root]; anything
     * else is [PhoneFiles.Dir.Outside] and the listing stops rather than leaking a name.
     */
    private fun children(
        context: Context,
        treeUri: Uri,
        dirId: String,
        root: File?,
    ): PhoneFiles.Dir {
        val uri = try {
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirId)
        } catch (_: Throwable) {
            return PhoneFiles.Dir.Failed(
                "the granted folder is unreadable · grant it again in Phone tools",
            )
        }
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val cursor = try {
            context.contentResolver.query(uri, columns, null, null, null)
        } catch (t: Throwable) {
            VoxLog.w("event=phone-files list-failed reason=${t.javaClass.simpleName}")
            return PhoneFiles.Dir.Failed(
                "the folder would not say what is in it · nothing was listed",
            )
        } ?: return PhoneFiles.Dir.Missing

        return try {
            cursor.use { c ->
                val entries = ArrayList<PhoneFiles.Entry>(c.count.coerceAtLeast(0))
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: return PhoneFiles.Dir.Outside
                    val name = c.getString(1)?.trim().orEmpty()
                    if (name.isEmpty()) continue
                    if (!PhoneFiles.contained(dirId, id)) return PhoneFiles.Dir.Outside
                    val target = primaryPath(id)
                    if (root != null && target != null && !PhoneFiles.canonicalWithin(root, target)) {
                        return PhoneFiles.Dir.Outside
                    }
                    val mime = c.getString(2)
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val bytes = if (isDir || c.isNull(3)) null else c.getLong(3)
                    val modifiedMs = if (c.isNull(4)) null else c.getLong(4)
                    entries.add(
                        PhoneFiles.Entry(
                            name = name,
                            dir = isDir,
                            bytes = bytes,
                            modified = modifiedMs?.let { ms ->
                                PhoneEngine.localIso(
                                    ms,
                                    java.util.TimeZone.getDefault().getOffset(ms) / 60_000,
                                )
                            },
                            mime = if (isDir) null else mime,
                        )
                    )
                }
                PhoneFiles.Dir.Found(entries)
            }
        } catch (t: Throwable) {
            VoxLog.w("event=phone-files list-failed reason=${t.javaClass.simpleName}")
            PhoneFiles.Dir.Failed(
                "the folder would not say what is in it · nothing was listed",
            )
        }
    }

    // ---- bytes, never for a listing ----

    /** Exactly [count] bytes from [from], or null when the provider failed. */
    private fun readRange(context: Context, uri: Uri, from: Long, count: Long): ByteArray? = try {
        context.contentResolver.openInputStream(uri)?.use { src ->
            var left = from
            val buf = ByteArray(8 * 1024)
            while (left > 0) {
                val skipped = src.skip(left)
                if (skipped > 0) {
                    left -= skipped
                    continue
                }
                val n = src.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) return@use null
                left -= n
            }
            val out = ByteArrayOutputStream(minOf(count, 64 * 1024L).toInt().coerceAtLeast(16))
            var remaining = count
            while (remaining > 0) {
                val n = src.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                remaining -= n
            }
            out.toByteArray()
        }
    } catch (t: Throwable) {
        VoxLog.w("event=phone-files window-failed reason=${t.javaClass.simpleName}")
        null
    }

    /**
     * The file's whole size, asked of the provider before anything is read.
     *
     * A provider that hides [DocumentsContract.Document.COLUMN_SIZE] usually still knows the size of
     * the file it is serving, and both a descriptor's declared length and its `statSize` answer it
     * without moving a byte. Only when neither does is the stream counted — and then only up to
     * [MAX_COUNT_BYTES], because counting a large file on the calling (main) thread is the thing
     * this function exists to avoid. Above that the size is not knowable cheaply, and null says so.
     */
    private fun sizeOf(context: Context, uri: Uri): Long? =
        declaredSize(context, uri) ?: countBytes(context, uri, MAX_COUNT_BYTES)

    /** The size the provider declares for [uri], or null when it will not say. */
    private fun declaredSize(context: Context, uri: Uri): Long? {
        val declared = try {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        } catch (_: Throwable) {
            -1L
        }
        if (declared >= 0) return declared
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }
                ?.takeIf { it >= 0 }
        } catch (_: Throwable) {
            null
        }
    }

    /** The file's size by counting it, giving up (null) once it is above [cap]. */
    private fun countBytes(context: Context, uri: Uri, cap: Long): Long? = try {
        context.contentResolver.openInputStream(uri)?.use { src ->
            val buf = ByteArray(8 * 1024)
            countedSize(cap) { src.read(buf) }
        }
    } catch (t: Throwable) {
        VoxLog.w("event=phone-files size-failed reason=${t.javaClass.simpleName}")
        null
    }

    /**
     * Count what [step] yields until it ends or the count passes [cap]; null above the cap.
     *
     * Pure so the bound is a tested property rather than a comment: a stream far larger than the
     * cap is abandoned after `cap` bytes, never read to its end.
     */
    internal fun countedSize(cap: Long, step: () -> Int): Long? {
        var total = 0L
        while (true) {
            val n = step()
            if (n < 0) return total
            total += n
            if (total > cap) return null
        }
    }

    // ---- one document, inside the tree ----

    private fun document(context: Context, tree: Tree, name: String): Lookup {
        val treeUri = try {
            Uri.parse(tree.uri)
        } catch (_: Throwable) {
            return Lookup.Failed("the granted folder is unreadable · grant it again in Phone tools")
        }
        val treeId = tree.id.ifEmpty {
            runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull().orEmpty()
        }
        if (treeId.isEmpty()) {
            return Lookup.Failed("the granted folder is unreadable · grant it again in Phone tools")
        }
        val docId = "$treeId/$name"
        // Belt and braces: the id is built from a name already checked, and it is re-checked here.
        if (!PhoneFiles.contained(treeId, docId)) return Lookup.Outside
        val docUri = try {
            DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        } catch (_: Throwable) {
            return Lookup.Missing
        }
        val row = query(context, docUri, docId, name) ?: return Lookup.Missing

        val returnedId = row.first
        if (!PhoneFiles.contained(treeId, returnedId)) return Lookup.Outside
        // The one escape an id cannot show: on the primary shared volume the ids name real paths,
        // so a symlink inside the granted folder is canonicalised and refused when it leaves it.
        val root = primaryPath(treeId)
        val target = primaryPath(returnedId)
        if (root != null && target != null && !PhoneFiles.canonicalWithin(root, target)) {
            return Lookup.Outside
        }
        return Lookup.Found(Doc(docUri, row.second, row.third, row.fourth))
    }

    /**
     * The document's own row: id, display name, size, mime. `document_id` is asked for so a
     * provider that would map the name somewhere else is caught, and the query is retried without
     * it when a provider rejects that column rather than failing the read.
     */
    private fun query(
        context: Context,
        docUri: Uri,
        askedId: String,
        name: String,
    ): Four<String, String, Long?, String?>? {
        val withId = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val withoutId = withId.drop(1).toTypedArray()
        for (projection in listOf(withId, withoutId)) {
            try {
                return context.contentResolver.query(docUri, projection, null, null, null)?.use { c ->
                    if (!c.moveToFirst()) return@use null
                    val offset = if (projection.size == 4) 1 else 0
                    val returnedId = if (offset == 1) c.getString(0) ?: askedId else askedId
                    val shown = c.getString(offset) ?: name
                    val size = if (c.isNull(offset + 1)) null else c.getLong(offset + 1)
                    val mime = c.getString(offset + 2)
                    Four(returnedId, shown, size, mime)
                }
            } catch (t: IllegalArgumentException) {
                // The provider does not expose document ids; the URI we built is the containment.
                continue
            } catch (t: Throwable) {
                VoxLog.w("event=phone-files query-failed reason=${t.javaClass.simpleName}")
                return null
            }
        }
        return null
    }

    private data class Four<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    /** `primary:Download` → the real folder on the primary shared volume; null for any other tree. */
    private fun primaryPath(documentId: String): File? {
        val prefix = "primary:"
        if (!documentId.startsWith(prefix)) return null
        val rest = documentId.removePrefix(prefix)
        if (rest.isEmpty()) return null
        return try {
            File(Environment.getExternalStorageDirectory(), rest)
        } catch (_: Throwable) {
            null
        }
    }

    // ---- open offers ----

    private val openOffers = ArrayList<FileServer>()

    private fun hold(server: FileServer) {
        synchronized(openOffers) {
            openOffers.add(server)
            while (openOffers.size > MAX_OPEN_OFFERS) {
                val oldest = openOffers.removeAt(0)
                runCatching { oldest.close() }
            }
        }
    }

    private fun closeLater(server: FileServer) {
        synchronized(openOffers) { openOffers.remove(server) }
        runCatching { server.close() }
    }

    /** A daemon thread, so a pending expiry never keeps the process alive. */
    private fun scheduleOnDaemon(delayMs: Long, action: () -> Unit) {
        Thread({
            try { Thread.sleep(delayMs) } catch (_: InterruptedException) { return@Thread }
            try { action() } catch (_: Throwable) { }
        }, "phone-file-offer-expiry").apply { isDaemon = true }.start()
    }

    private fun prefs(context: Context) =
        (context.applicationContext ?: context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
