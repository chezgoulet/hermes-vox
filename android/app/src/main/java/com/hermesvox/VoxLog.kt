package com.hermesvox

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * VoxLog — lightweight, LOCAL-FIRST logging + crash capture for real-device
 * debugging. Writes to logcat (tag "HermesVox") AND a rolling in-app log under
 * filesDir/logs/hermes-vox.log. Pull it with `adb pull` (debug) or export from
 * Settings. No cloud keys, no third-party SDK. On an uncaught crash it records
 * the stack trace to the same log before the process dies.
 */
object VoxLog {
    const val TAG = "HermesVox"
    private var file: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    @Volatile private var debugFile = false

    fun init(context: Context) {
        file = File(context.filesDir, "logs/hermes-vox.log")
        file?.parentFile?.mkdirs()
        debugFile = context.getSharedPreferences("hv", Context.MODE_PRIVATE).getBoolean("debug_log", false)
        setUncaughtHandler()
    }

    fun setDebugFile(on: Boolean) { debugFile = on }

    fun d(msg: String) { Log.d(TAG, msg); append("D", msg) }
    fun w(msg: String) { Log.w(TAG, msg); append("W", msg) }
    fun e(msg: String) { Log.e(TAG, msg); append("E", msg) }
    fun e(tag: String, msg: String) { Log.e(tag, msg); append(tag, msg) }

    /** Debug-detail: logcat always, file only when the debug-file pref is on. */
    fun dd(msg: String) { Log.d(TAG, msg); if (debugFile) append("D", msg) }

    private fun append(level: String, msg: String) {
        try {
            file?.appendText("${fmt.format(Date())} [$level] $msg\n")
        } catch (_: Throwable) {}
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
