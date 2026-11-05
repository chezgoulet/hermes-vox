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
 * PhoneFileAccess — the Android edge of `file_read` and `file_offer`: the folder the owner granted,
 * the one file a call names inside it, and nothing else.
 *
 * **The grant is the owner's, made in the system picker.** [intent] is `ACTION_OPEN_DOCUMENT_TREE`
 * (read-only — this app never writes into the tree); [accept] takes the persistable permission the
 * picker returned and remembers the tree's own display name. Without that grant both actions refuse
 * `no_folder`, and there is no other way in: **the Storage Access Framework is the deliberate
 * default, and this app declares no broad storage permission for it.** An all-files mode is a
 * decision the owner could be offered later through Android's own `MANAGE_EXTERNAL_STORAGE` screen;
 * it is not a capability this file withholds on their behalf.
 *
 * **A name is looked up, never walked.** The document id is `treeDocumentId + "/" + name`, which is
 * one child of the granted tree by construction; [PhoneFiles.contained] re-checks the id the
 * provider answers with, so a provider that maps the name outside the tree is refused `bad_path`
 * rather than followed. On the platform's primary shared volume the id also names a real path, and
 * [PhoneFiles.canonicalWithin] refuses a symlink that leaves the tree — the one escape a document id
 * cannot show. For a provider with no filesystem path (a cloud tree) the id check is the whole
 * check, which `docs/MCP-SURFACE.md` states rather than pretending otherwise.
 *
 * `file_read` copies at most [PhoneFiles.MAX_READ_BYTES] + 1 bytes and refuses a larger file; it
 * never truncates. `file_offer` hands the document to [FileStage] and [FileServer] — the same bridge
 * the share sheet uses — so the agent fetches an artifact, not a base64 blob in a tool result.
 */
object PhoneFileAccess {

    private const val PREFS = "hv"
    private const val KEY_TREES = "phone_file_trees"

    /** At most this many offers are held open at once; the oldest is closed to make room. */
    private const val MAX_OPEN_OFFERS = 4

    /** One folder the owner granted. [label] is the folder's own name, never a path we invented. */
    data class Tree(val uri: String, val label: String)

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
            out.add(Tree(uri, label.ifEmpty { labelOf(Uri.parse(uri)) }))
        }
        return out
    }

    /** The picker intent. Read-only and persistable: the grant has to outlive the sheet. */
    fun intent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
    }

    /**
     * Take the picker's answer: persist the read grant, remember the folder's own name, and return
     * it so the sheet can say which folder it got. Null when nothing was picked or Android refused
     * to make the grant persistable — in which case nothing is stored and the actions still refuse
     * `no_folder`.
     */
    fun accept(context: Context, data: Intent?): Tree? {
        val uri = data?.data ?: return null
        val label = labelOf(uri)
        return try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            val next = ArrayList(trees(context))
            // The same folder picked again replaces its old grant, which is released so the
            // provider is not left holding two.
            next.firstOrNull { PhoneFiles.normalizeFolder(it.label) == PhoneFiles.normalizeFolder(label) }
                ?.let { old ->
                    next.remove(old)
                    runCatching {
                        context.contentResolver.releasePersistableUriPermission(
                            Uri.parse(old.uri),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                }
            next.add(Tree(uri.toString(), label))
            save(context, next)
            VoxLog.d("event=phone-files folder=$label")
            Tree(uri.toString(), label)
        } catch (t: Throwable) {
            VoxLog.w("event=phone-files grant-failed reason=${t.javaClass.simpleName}")
            null
        }
    }

    private fun save(context: Context, trees: List<Tree>) {
        val encoded = Json.encode(trees.map { linkedMapOf("uri" to it.uri, "label" to it.label) })
        prefs(context).edit().putString(KEY_TREES, encoded).apply()
    }

    /** The tree's own display name, or its last id segment when the provider will not say. */
    private fun labelOf(uri: Uri): String {
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull().orEmpty()
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

    // ---- one document, inside the tree ----

    private fun document(context: Context, tree: Tree, name: String): Lookup {
        val treeUri = try {
            Uri.parse(tree.uri)
        } catch (_: Throwable) {
            return Lookup.Failed("the granted folder is unreadable · grant it again in Phone tools")
        }
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return Lookup.Failed("the granted folder is unreadable · grant it again in Phone tools")
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
