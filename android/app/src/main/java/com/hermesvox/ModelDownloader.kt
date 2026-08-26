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

/**
 * ModelDownloader — streams a blessed model ZIP into app-private storage
 * (no cloud APIs, no third-party SDK; HttpURLConnection + built-in zip), verifies
 * the sha256, and unpacks to filesDir/models/<id>/. Supports cancel + progress.
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
            // Keep the CPU/network alive so a large download survives Doze /
            // backgrounding (the app is often backgrounded during a download).
            val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            val wl = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "hermesvox:model-dl")
            wl.acquire(30 * 60 * 1000L)
            try {
                val err = doDownload(spec, listener)
                if (cancelled) listener.onError(spec.id, "cancelled")
                else if (err != null) listener.onError(spec.id, err)
                else listener.onDone(spec.id)
            } catch (e: Throwable) {
                if (!cancelled) listener.onError(spec.id, e.message ?: "download failed")
            } finally { activeId = null; try { wl.release() } catch (_: Exception) {} }
        }
    }

    fun cancel() { cancelled = true }

    private fun doDownload(spec: ModelSpec, listener: Listener): String? {
        val base = ModelCatalog.source(context).trimEnd('/')
        val conn = (URL("$base/${spec.file}").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 30000; instanceFollowRedirects = true
        }
        conn.connect()
        if (conn.responseCode != 200) return "HTTP ${conn.responseCode}"
        val total: Int = conn.contentLength
        val totalL: Long = if (total > 0) total.toLong() else 0L

        val tmp = File(context.filesDir, "${spec.id}.zip.part")
        FileOutputStream(tmp).use { out ->
            BufferedInputStream(conn.inputStream).use { inp ->
                val buf = ByteArray(64 * 1024)
                var dl = 0L
                while (true) {
                    if (cancelled) break
                    val n = inp.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n); dl += n
                    val shown: Long = if (total > 0) totalL else dl
                    listener.onProgress(spec.id, dl, shown)
                }
            }
        }
        conn.disconnect()
        if (cancelled) { tmp.delete(); return "cancelled" }

        val digest = sha256(tmp)
        if (!digest.equals(spec.sha256, true)) { tmp.delete(); return "sha256 mismatch (corrupt download)" }

        val dir = ModelCatalog.modelDir(context, spec.id)
        dir.deleteRecursively(); dir.mkdirs()
        unpkg(tmp, dir)
        tmp.delete()
        return null
    }

    private fun unpkg(zip: File, dir: File) {
        val canon = dir.canonicalPath
        ZipInputStream(BufferedInputStream(zip.inputStream())).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val target = File(dir, e.name)
                // zip-slip guard: never let an entry escape the model dir
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

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024); var n: Int
            while (ins.read(buf).also { n = it } > 0) md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
