package com.hermesvox

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CrashLog — a local, no-cloud uncaught-exception logger. Writes the crash stack
 * to filesDir/vox_crash.log so it can be shared back (Settings -> Debug). Android
 * 11+ silently relaunches on a crash (no dialog), so without this we'd have no
 * crash text from a real device.
 */
object CrashLog {
    private const val FILE = "vox_crash.log"
    private fun file(c: Context) = File(c.filesDir, FILE)

    fun init(context: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try { append(context, thread, e) } catch (_: Throwable) {}
            prev?.uncaughtException(thread, e) ?: run {
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(1)
            }
        }
    }

    private fun append(context: Context, thread: Thread, e: Throwable) {
        val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
        val header = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) +
            " thread=" + thread.name + " " + e.javaClass.name + ": " + e.message + "\n" +
            sw.toString() + "\n"
        val f = file(context)
        val old = if (f.exists()) f.readText().takeLast(6000) else ""
        f.writeText((old + header).takeLast(9000))
    }

    fun read(context: Context): String = file(context).takeIf { it.exists() }?.readText() ?: "no crash logged"
    fun clear(context: Context) { file(context).delete() }
}
