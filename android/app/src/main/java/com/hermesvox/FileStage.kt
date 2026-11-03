package com.hermesvox

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

/**
 * FileStage — the Android edge of the file offer: turn a content URI into a private copy with
 * a hash and a size, and delete it responsibly.
 *
 * The copy is the only thing that ever holds the bytes on our side. It is written to
 * `filesDir/file-offer/` (private to the app, no external storage, no permission needed),
 * under a random temp name — the *display* name stays in the turn and is never a path, so a
 * file called `../../foo` is just a name and cannot become a write location.
 *
 * The cap is enforced while copying, not after: a 4 GB share is refused with the size its
 * provider reported, or as "larger than 100 MB" when the provider reports none. Nothing is
 * ever truncated silently.
 *
 * All of this is plain JVM except the two content-resolver reads, which is why [copyLimited]
 * and [build] can be tested with byte arrays in `FileStageTest`.
 */
object FileStage {

    const val DIR = "file-offer"

    /** A staged file: the private copy plus everything the offer needs to describe it. */
    data class Staged(
        val file: File,
        val offer: FileOffer.File,
        /** The decoded body, only for a text file under the inline threshold. */
        val inlineText: String?,
    )

    /** What staging produced. Nothing is left behind on [Failed] or [TooBig]. */
    sealed class Result {
        data class Ok(val staged: Staged) : Result()

        /** The provider said (or the copy proved) the file is above the cap. */
        data class TooBig(val bytes: Long, val line: String) : Result()

        /** Unreadable share: no name, no stream, or a provider that went away mid-copy. */
        data class Failed(val line: String) : Result()
    }

    /** The private staging directory; created on demand. */
    fun dir(context: Context): File = File(context.filesDir, DIR).apply { mkdirs() }

    /**
     * Delete every leftover from a previous process. A staged copy is a *temporary* copy:
     * anything still there when a new offer starts belongs to a flow that died (a crash kills
     * no callbacks), so it is swept rather than served.
     */
    fun sweep(context: Context) {
        val d = dir(context)
        try {
            d.listFiles()?.forEach { it.deleteRecursively() }
        } catch (_: Throwable) {
        }
    }

    /** Stage a shared or picked URI. */
    fun stage(context: Context, uri: Uri, sizeHint: Long? = null): Result {
        val name = displayName(context, uri) ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: "file"
        val mime = context.contentResolver.getType(uri)?.takeIf { it.isNotBlank() }
            ?: "application/octet-stream"
        val known = sizeHint ?: queryLong(context, uri, OpenableColumns.SIZE)
        if (known != null && known > FileOffer.MAX_BYTES) return tooBig(known)
        return try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return Result.Failed("the file could not be read · nothing was sent")
            val out = newTarget(context) ?: return Result.Failed(OUT_OF_SPACE)
            input.use { src -> copyInto(src, out, name, mime) }
        } catch (_: Throwable) {
            Result.Failed("the file could not be read · nothing was sent")
        }
    }

    /**
     * Stage a file by path. Used by the debug harness (a script cannot drive a system picker);
     * the path must already be readable by the app, so it adds no permission.
     */
    fun stageFile(context: Context, src: File, mimeHint: String? = null): Result {
        if (!src.isFile) return Result.Failed("the file could not be read · nothing was sent")
        if (src.length() > FileOffer.MAX_BYTES) return tooBig(src.length())
        val mime = mimeHint ?: guessMime(src.name)
        return try {
            val out = newTarget(context) ?: return Result.Failed(OUT_OF_SPACE)
            src.inputStream().use { input -> copyInto(input, out, src.name, mime) }
        } catch (_: Throwable) {
            Result.Failed("the file could not be read · nothing was sent")
        }
    }

    /**
     * Copy one stream into a private temp file, hashing as it goes. The temp file is deleted
     * on every failure path, so a refusal leaves nothing behind.
     */
    private fun copyInto(
        input: InputStream,
        out: File,
        name: String,
        mime: String,
    ): Result {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = DigestOutputStream(out.outputStream(), digest).use { sink ->
                copyLimited(input, sink, FileOffer.MAX_BYTES)
            }
            Result.Ok(Staged(
                file = out,
                offer = FileOffer.File(
                    name = name,
                    mime = mime,
                    bytes = bytes,
                    sha256 = FileOffer.hex(digest.digest()),
                ),
                inlineText = if (FileOffer.isTextType(mime) && bytes < FileOffer.INLINE_BELOW) {
                    FileOffer.decodeUtf8(out.readBytes())
                } else {
                    null
                },
            ))
        } catch (_: TooBig) {
            out.delete()
            Result.TooBig(FileOffer.MAX_BYTES + 1, TOO_BIG_UNKNOWN)
        } catch (_: Throwable) {
            out.delete()
            Result.Failed("the file could not be read · nothing was sent")
        }
    }

    private fun newTarget(context: Context): File? = try {
        File.createTempFile("offer-", ".bin", dir(context))
    } catch (_: Throwable) {
        null
    }

    /** The refuser. The size is always in the line. */
    fun tooBig(bytes: Long): Result.TooBig = Result.TooBig(
        bytes,
        "this file is ${FileOffer.size(bytes)} · the most I can offer is ${FileOffer.size(FileOffer.MAX_BYTES)}",
    )

    /**
     * A provider that reports no size and streams past the cap: the true size was never
     * measured, so the line states the limit rather than inventing a number.
     */
    const val TOO_BIG_UNKNOWN =
        "this file is larger than 100 MB · the most I can offer is 100 MB"

    const val OUT_OF_SPACE = "the file could not be staged · nothing was sent"

    /** Thrown by [copyLimited] when the source passes the cap. */
    class TooBig : Exception()

    /**
     * Copy [input] to [out] until it ends, refusing past [cap]. Returns the byte count.
     * The caller sees a partial file only through the exception; it deletes it.
     */
    fun copyLimited(input: InputStream, out: OutputStream, cap: Long): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > cap) throw TooBig()
            out.write(buf, 0, n)
        }
        out.flush()
        return total
    }

    /** The share's display name, from the provider (never a filesystem path). */
    fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
    } catch (_: Throwable) {
        null
    }

    private fun queryLong(context: Context, uri: Uri, column: String): Long? = try {
        context.contentResolver.query(uri, arrayOf(column), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
    } catch (_: Throwable) {
        null
    }

    /** A conservative type from the extension; the provider's type wins when it has one. */
    fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "txt", "log", "md", "markdown" -> "text/plain"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "csv" -> "text/csv"
        "html", "htm" -> "text/html"
        "js", "mjs" -> "application/javascript"
        "yaml", "yml" -> "application/yaml"
        "sh" -> "application/x-sh"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "pdf" -> "application/pdf"
        "kt", "kts", "java", "py", "go", "rs", "c", "h", "rb", "ts" -> "text/plain"
        else -> "application/octet-stream"
    }
}
