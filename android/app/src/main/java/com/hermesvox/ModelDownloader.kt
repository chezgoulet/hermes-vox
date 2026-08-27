package com.hermesvox

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/**
 * ModelDownloader — streams a blessed model from its CANONICAL UPSTREAM URL
 * (the k2-fsa sherpa-onnx model zoo by default) into app-private storage,
 * handles the upstream formats (zip / tar.bz2 / a bare onnx), and unpacks to
 * filesDir/models/<id>/. Supports cancel + progress.
 */
class ModelDownloader(private val context: Context) {

    interface Listener {
        fun onProgress(id: String, downloaded: Long, total: Long)
        fun onDone(id: String)
        fun onError(id: String, msg: String)
    }

    @Volatile private var cancelled = false
    private var activeId: String? = null

    fun download(spec: ModelSpec, listener: Listener) {
        thread {
            activeId = spec.id
            cancelled = false
            val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            var wl: android.os.PowerManager.WakeLock? = null
            try { wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "hermesvox:model-dl"); wl.acquire(30 * 60 * 1000L) } catch (_: Throwable) {}

            try {
                val err = doDownload(spec, listener)
                if (cancelled) listener.onError(spec.id, "cancelled")
                else if (err != null) listener.onError(spec.id, err)
                else listener.onDone(spec.id)
            } catch (e: Throwable) {
                if (!cancelled) listener.onError(spec.id, e.message ?: "download failed")
            } finally { activeId = null; try { wl?.release() } catch (_: Exception) {} }
        }
    }

    fun cancel() { cancelled = true }

    private fun doDownload(spec: ModelSpec, listener: Listener): String? {
        val base = ModelCatalog.source(context).trimEnd('/')
        val urlStr = if (spec.url.isNotBlank()) spec.url else "$base/${spec.file}"
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = true
        }
        conn.connect()
        if (conn.responseCode != 200) return "HTTP ${conn.responseCode} for $urlStr"
        val totalL: Long = conn.contentLengthLong
        if (totalL < 0) return "unknown-length"

        val tmp = File(context.filesDir, "${spec.id}.part")
        try {
            var dl = 0L
            FileOutputStream(tmp).use { out ->
                BufferedInputStream(conn.inputStream).use { inp ->
                    val buf = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        if (cancelled) break
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n); dl += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 250) { listener.onProgress(spec.id, dl, totalL); lastReport = now }
                    }
                    listener.onProgress(spec.id, dl, totalL)
                }
            }
            conn.disconnect()
            if (cancelled) { tmp.delete(); return "cancelled" }

            if (dl != totalL) { tmp.delete(); return "truncated download" }

            if (spec.sha256.isBlank()) { tmp.delete(); return "model has no pinned sha256" }
            val digest = sha256(tmp)
            if (!digest.equals(spec.sha256, true)) { tmp.delete(); return "sha256 mismatch" }

            val dir = ModelCatalog.modelDir(context, spec.id)
            dir.deleteRecursively(); dir.mkdirs()
            unpkg(tmp, dir, spec.file)
            return null
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        } finally {
            tmp.delete()
        }
    }

    // Handles zip, tar.bz2, and a bare onnx file.
    private fun unpkg(src: File, dir: File, fileName: String) {
        val name = fileName.lowercase()
        when {
            name.endsWith(".tar.bz2") -> untarBz2(src, dir)
            name.endsWith(".zip") -> unpkgZip(src, dir)
            else -> {  // bare onnx (e.g. silero_vad.onnx) — just place it
                File(dir, fileName).writeBytes(src.readBytes())
            }
        }
        // Canonical tarballs use a top-level dir (e.g. sherpa-onnx-whisper-tiny.en/);
        // hoist its contents up so the pipeline finds encoder.onnx at the model root.
        hoist(dir)
        normalizeNames(dir, dir.name)
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
                rename(".*-encoder\\.onnx", "encoder.onnx")
                rename(".*-decoder\\.onnx", "decoder.onnx")
                rename(".*-tokens\\.txt", "tokens.txt")
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
