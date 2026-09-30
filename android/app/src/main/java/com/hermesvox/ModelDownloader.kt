package com.hermesvox

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/**
 * ModelDownloader — the transfer engine behind [ModelDownloads]: streams a blessed model from
 * its CANONICAL UPSTREAM URL into app-private storage, verifies its pinned sha256, and unpacks
 * the upstream format (zip / tar.bz2 / a bare onnx) to filesDir/models/<id>/.
 *
 * RESUMABLE (DownloadResume): the partial file survives failures and cancels, beside a small
 * record of the artifact it belongs to (URL + pinned sha256). The next attempt asks for the
 * missing bytes with a Range header instead of starting over; a mid-stream error retries by
 * itself with backoff, resuming each time. The sha256 over the COMPLETE file is still the gate
 * to installing, so a bad resume costs a re-download, never a corrupt model. Blocking — it runs
 * on the download service's worker thread.
 */
class ModelDownloader(context: Context) {
    private val context = context.applicationContext

    /** Where the transfer is: fetching bytes, waiting for a network, hashing, or unpacking. */
    enum class Phase { DOWNLOAD, WAITING_NETWORK, VERIFY, UNPACK }

    /** True when the phone has a network that claims internet (the download can proceed). */
    private fun online(): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Throwable) { true }   // unknown: let the attempt decide

    private fun partFile(id: String) = File(File(context.filesDir, "downloads").apply { mkdirs() }, "$id.part")
    private fun metaFile(id: String) = File(partFile(id).path + ".meta")

    /** Bytes already on disk for this model's current artifact (0 when none or stale). */
    fun partialBytes(spec: ModelSpec): Long {
        val part = partFile(spec.id)
        if (!part.exists()) return 0L
        val meta = metaFile(spec.id).takeIf { it.exists() }?.readLines().orEmpty()
        return if (DownloadResume.sameArtifact(meta.getOrNull(0), meta.getOrNull(1), urlFor(spec), spec.sha256)) part.length() else 0L
    }

    /** Drop a partial (the user discards it, or the artifact proved bad). */
    fun discardPartial(id: String) { partFile(id).delete(); metaFile(id).delete() }

    private fun urlFor(spec: ModelSpec): String {
        val base = ModelCatalog.source(context).trimEnd('/')
        return if (spec.url.isNotBlank()) spec.url else "$base/${spec.file}"
    }

    /**
     * Fetch, verify and install [spec]. Returns null on success, "cancelled", or an error
     * message. [cancelled] is polled between reads; [progress] reports (phase, done, total).
     */
    fun run(spec: ModelSpec, cancelled: () -> Boolean, progress: (Phase, Long, Long) -> Unit): String? {
        if (spec.sha256.isBlank()) return "model has no pinned sha256"
        val url = urlFor(spec)
        val part = partFile(spec.id)
        if (partialBytes(spec) == 0L) { part.delete() }
        metaFile(spec.id).writeText("$url\n${spec.sha256}\n")

        var total = -1L
        var attempt = 0
        while (true) {
            if (cancelled()) return "cancelled"
            val r = try {
                transfer(url, part, cancelled) { done, t -> total = t; progress(Phase.DOWNLOAD, done, t) }
            } catch (e: java.io.IOException) {
                "stream: ${e.message ?: e.javaClass.simpleName}"
            }
            when {
                r == null -> break                                  // every byte is on disk
                r == "cancelled" -> return r                         // kept: resumes next time
                r.startsWith("fatal:") -> { discardPartial(spec.id); return r.removePrefix("fatal:") }
                r.startsWith("space:") -> return r.removePrefix("space:")
                // Offline is not a failure of this download: wait for a network without
                // spending retries (a tunnel, a lift, a train), then resume where it stopped.
                !online() -> {
                    VoxLog.w("event=model-dl-offline id=${spec.id} have=${part.length()} — waiting for a network")
                    progress(Phase.WAITING_NETWORK, part.length(), total)
                    while (!online()) { if (cancelled()) return "cancelled"; Thread.sleep(1000) }
                    VoxLog.d("event=model-dl-online id=${spec.id} — resuming")
                }
                ++attempt > DownloadResume.MAX_RETRIES -> return "$r — tap to resume from ${part.length() / 1048576} MB"
                else -> {
                    val wait = DownloadResume.backoffMs(attempt)
                    VoxLog.w("event=model-dl-retry id=${spec.id} attempt=$attempt waitMs=$wait have=${part.length()} err=$r")
                    val until = System.currentTimeMillis() + wait
                    while (System.currentTimeMillis() < until) { if (cancelled()) return "cancelled"; Thread.sleep(250) }
                }
            }
        }

        progress(Phase.VERIFY, 0, part.length())
        val digest = sha256(part)
        if (!digest.equals(spec.sha256, true)) {
            discardPartial(spec.id)   // a bad byte anywhere: only a clean fetch can fix it
            return "sha256 mismatch — the download was corrupt; it will start fresh"
        }

        progress(Phase.UNPACK, 0, part.length())
        val tmpDir = File(context.filesDir, spec.id + ".tmp")
        try {
            tmpDir.deleteRecursively(); tmpDir.mkdirs()
            unpkg(part, tmpDir, spec.file, spec.id)
            val dir = ModelCatalog.modelDir(context, spec.id)
            dir.deleteRecursively(); dir.parentFile?.mkdirs()
            if (!tmpDir.renameTo(dir)) return "unpack swap failed"
        } finally { tmpDir.deleteRecursively() }
        discardPartial(spec.id)
        return null
    }

    /** One HTTP attempt: resume [part] from its current size. Null when complete; otherwise a
     *  reason (prefixed "fatal:" when the partial must be discarded, "space:" when out of room). */
    private fun transfer(url: String, part: File, cancelled: () -> Boolean, progress: (Long, Long) -> Unit): String? {
        val have = if (part.exists()) part.length() else 0L
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = true
            if (have > 0) setRequestProperty("Range", "bytes=$have-")
        }
        try {
            conn.connect()
            val plan = DownloadResume.plan(have, conn.responseCode, conn.contentLengthLong, conn.getHeaderField("Content-Range"))
            VoxLog.d("event=model-dl-attempt have=$have code=${conn.responseCode} plan=$plan")
            val (offset, total) = when (plan) {
                is DownloadResume.Plan.Complete -> return null
                is DownloadResume.Plan.Fail -> return plan.reason
                is DownloadResume.Plan.Write -> plan.offset to plan.total
            }
            if (total <= 0) { part.delete(); return "server did not report the size; retrying fresh" }
            val free = part.parentFile?.usableSpace ?: Long.MAX_VALUE
            val need = DownloadResume.bytesNeeded(total, offset, isArchive = !url.endsWith(".onnx") && !url.endsWith(".litertlm"))
            if (free < need) return "space:not enough storage — need ${need / 1048576} MB free, have ${free / 1048576} MB"
            var done = offset
            FileOutputStream(part, offset > 0).use { out ->
                BufferedInputStream(conn.inputStream).use { inp ->
                    val buf = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        if (cancelled()) return "cancelled"
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); done += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 250) { progress(done, total); lastReport = now }
                    }
                }
            }
            progress(done, total)
            return if (done == total) null else "truncated at $done of $total"
        } finally { conn.disconnect() }
    }

    // Handles zip, tar.bz2, and a bare onnx file.
    private fun unpkg(src: File, dir: File, fileName: String, specId: String) {
        val name = fileName.lowercase()
        when {
            name.endsWith(".tar.bz2") -> untarBz2(src, dir)
            name.endsWith(".zip") -> unpkgZip(src, dir)
            else -> {  // bare onnx (e.g. silero_vad.onnx) — just place it
                src.copyTo(File(dir, fileName), overwrite = true)
            }
        }
        // Canonical tarballs use a top-level dir (e.g. sherpa-onnx-whisper-tiny.en/);
        // hoist its contents up so the pipeline finds encoder.onnx at the model root.
        hoist(dir)
        normalizeNames(dir, specId)
    }

    private fun untarBz2(src: File, dir: File) {
        BZip2CompressorInputStream(BufferedInputStream(src.inputStream())).use { bz ->
            TarArchiveInputStream(bz).use { tar ->
                val canon = dir.canonicalPath
                var e = tar.nextEntry
                while (e != null) {
                    val target = File(dir, e.name)
                    if (target.canonicalPath.startsWith(canon + File.separator)) {
                        if (e.isDirectory) target.mkdirs()
                        else {
                            target.parentFile?.mkdirs()
                            FileOutputStream(target).use { out ->
                                val buf = ByteArray(64 * 1024); var n: Int
                                while (tar.read(buf).also { n = it } > 0) out.write(buf, 0, n)
                            }
                        }
                    }
                    e = tar.nextEntry
                }
            }
        }
    }

    private fun unpkgZip(zip: File, dir: File) {
        val canon = dir.canonicalPath
        ZipInputStream(BufferedInputStream(zip.inputStream())).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val target = File(dir, e.name)
                if (!target.canonicalPath.startsWith(canon + File.separator)) {
                    zis.closeEntry(); e = zis.nextEntry; continue
                }
                target.parentFile?.mkdirs()
                if (!e.isDirectory) {
                    FileOutputStream(target).use { out ->
                        val buf = ByteArray(64 * 1024); var n: Int
                        while (zis.read(buf).also { n = it } > 0) out.write(buf, 0, n)
                    }
                }
                zis.closeEntry(); e = zis.nextEntry
            }
        }
    }

    // Lift a single wrapping directory's contents to the model root (canonical tarball layout).
    private fun hoist(dir: File) {
        val sub = dir.listFiles()?.filter { it.isDirectory }?.firstOrNull() ?: return
        // Only hoist when there is exactly one top-level dir (a wrapping folder), not a flat layout.
        if (dir.listFiles()?.count() ?: 0 != 1) return
        sub.listFiles()?.forEach { f ->
            val dest = File(dir, f.name)
            if (f.isDirectory) f.copyRecursively(dest, true) else f.copyTo(dest, true)
        }
        sub.deleteRecursively()
    }

    private fun normalizeNames(dir: File, specId: String) {
        val files = dir.listFiles() ?: return
        fun rename(pat: String, to: String) {
            val m = files.firstOrNull { it.name.matches(Regex(pat)) } ?: return
            if (!m.name.equals(to)) m.renameTo(File(dir, to))
        }
        when (specId) {
            "whisper-tiny", "whisper-base", "whisper-small" -> {
                rename(".*-encoder\\.int8\\.onnx", "encoder.int8.onnx")
                rename(".*-decoder\\.int8\\.onnx", "decoder.int8.onnx")
                rename(".*-encoder\\.onnx", "encoder.onnx")
                rename(".*-decoder\\.onnx", "decoder.onnx")
                rename(".*-tokens\\.txt", "tokens.txt")
            }
            "piper-lessac" -> {
                rename(".*\\.onnx", "model.onnx")
            }
        }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024); var n: Int
            while (ins.read(buf).also { n = it } > 0) md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
