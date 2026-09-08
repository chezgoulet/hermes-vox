package com.hermesvox

import android.content.Context
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * VoxLog — lightweight, LOCAL-FIRST logging + crash capture for real-device
 * debugging. Writes to logcat (tag "HermesVox") AND a rolling in-app log under
 * filesDir/logs/hermes-vox.log. Pull it with `adb pull` (debug) or export from
 * Settings. No cloud keys, no third-party SDK. On an uncaught crash it records
 * the stack trace to the same log before the process dies.
 *
 * K2 (C4): the active file is capped at 5MB — on exceed it rotates current ->
 * hermes-vox.log.1 (single generation; a stale .1 is overwritten) and appends
 * keep flowing into a fresh file, so a public app's log is bounded, never
 * unbounded. The decision is the pure rotationDecision(); the check is a cheap
 * file length run on open (init) + every ~50 writes, not per line. The export
 * path ships BOTH files (the merged pair) so field logs stay complete.
 */
object VoxLog {
    const val TAG = "HermesVox"
    private var file: File? = null
    // Single persistent writer held across appends (opened lazily in append mode,
    // closed only by rotation). Guards the reopen-per-line syscall churn the old
    // file?.appendText(...) did on every log line.
    private var writer: BufferedWriter? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    @Volatile private var debugFile = false

    // K2: size cap + check cadence for the active log's single-generation rotation.
    internal const val LOG_CAP_BYTES = 5L * 1024 * 1024
    private const val SIZE_CHECK_WRITES = 50
    @Volatile private var writesSinceCheck = 0
    private val rotationLock = Object()

    fun init(context: Context) {
        file = File(context.filesDir, "logs/hermes-vox.log")
        file?.parentFile?.mkdirs()
        debugFile = context.getSharedPreferences("hv", Context.MODE_PRIVATE).getBoolean("debug_log", false)
        rotateIfNeeded()   // K2: check on open — a previous run may have left an oversized file
        setUncaughtHandler()
    }

    fun setDebugFile(on: Boolean) { debugFile = on }

    fun d(msg: String) { Log.d(TAG, msg); append("D", msg) }
    fun w(msg: String) { Log.w(TAG, msg); append("W", msg) }
    fun e(msg: String) { Log.e(TAG, msg); append("E", msg) }
    fun e(tag: String, msg: String) { Log.e(tag, msg); append(tag, msg) }

    /** Debug-detail: logcat always, file only when the debug-file pref is on. */
    fun dd(msg: String) { Log.d(TAG, msg); if (debugFile) append("D", msg) }

    /** 0.6.4 field bug: "there was nothing in the logs at all" — the ER decision
     *  lines (er:intent, er-barge-verdict, er-arbiter) used dd(), so they were
     *  invisible in the exported log unless the debug-file toggle was on. The ER
     *  arc is DIAGNOSTIC, not transcript: it always reaches the file (the
     *  debug-file pref only adds the high-frequency probes). */
    fun er(msg: String) { Log.d(TAG, msg); append("E", msg) }

    /** Pure K2 decision: an active log ROTATES when its size EXCEEDS the cap
     *  (> cap), KEEPs otherwise (== cap is still inside the budget). Unit-tested. */
    internal fun rotationDecision(sizeBytes: Long, capBytes: Long): LogRotation =
        if (sizeBytes > capBytes) LogRotation.ROTATE else LogRotation.KEEP

    private fun append(level: String, msg: String) {
        try {
            val f = file
            if (f == null) return
            // All appends (main, capture, stream worker, TTS engine) serialize on the
            // rotationLock — same lock rotation uses — so lines never interleave and
            // rotation can never race a write.
            synchronized(rotationLock) {
                val w = writer
                    ?: BufferedWriter(OutputStreamWriter(FileOutputStream(f, true), Charsets.UTF_8))
                        .also { writer = it }
                w.write("${fmt.format(Date())} [$level] $msg\n")
                w.flush()   // per-line flush: keeps crash capture durable and the size check honest
                // K2: the size check runs every ~50 writes, not per line (cheap file length).
                if (++writesSinceCheck >= SIZE_CHECK_WRITES) {
                    writesSinceCheck = 0
                    rotateIfNeeded()
                }
            }
        } catch (_: Throwable) {}
    }

    /** K2: roll an oversized active log -> "<name>.1" (single generation, overwriting
     *  any prior .1) so the next append recreates a fresh current file. Errors are
     *  swallowed like the rest of VoxLog; a failed rename just rolls again next check. */
    private fun rotateIfNeeded() {
        val cur = file ?: return
        synchronized(rotationLock) {
            if (rotationDecision(cur.length(), LOG_CAP_BYTES) != LogRotation.ROTATE) return
            try {
                // Close the persistent writer BEFORE renaming: an open handle would
                // keep writing into the renamed .1 inode. The writer reopens lazily
                // (append mode) on the next append, recreating a fresh current file.
                val w = writer
                writer = null
                w?.close()
                val parent = cur.parentFile ?: return
                val gen = File(parent, cur.name + ".1")
                if (gen.exists()) gen.delete()
                cur.renameTo(gen)
            } catch (_: Throwable) {}
        }
    }

    /** Never let a crash vanish silently — log it, then die. */
    private fun setUncaughtHandler() {
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                append("CRASH", "thread=${t?.name} ${e.toString()}\n${e.stackTraceToString()}")
                Log.e(TAG, "CRASH thread=${t?.name} ${e.stackTraceToString()}")
            } catch (_: Throwable) {}
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}

/** K2: result of the pure log-rotation decision. */
internal enum class LogRotation { KEEP, ROTATE }
