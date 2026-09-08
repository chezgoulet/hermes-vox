package com.hermesvox

import android.content.Context
import java.io.File

/**
 * VoxMirror — the device-side VOX.md mirror (ER Phase 3). PULL-ONLY: the app
 * never writes identity upstream; this file is a refetchable local cache of
 * the gateway-authoritative VOX.md. All I/O is private-filesDir (no external
 * storage, no backup surface — allowBackup is false app-wide).
 */
object VoxMirror {

    /** The mirror file: filesDir/vox/VOX.md. */
    fun file(ctx: Context): File = File(ctx.filesDir, "vox/VOX.md")

    /** Read the mirrored document (null when absent/unreadable). */
    fun read(ctx: Context): String? = try {
        val f = file(ctx)
        if (f.exists()) f.readText().ifBlank { null } else null
    } catch (_: Throwable) { null }

    /** Mirror a validated document. Returns false when the write failed —
     *  the caller surfaces the honest state (never a silent success). */
    fun write(ctx: Context, document: String): Boolean = try {
        val f = file(ctx)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "VOX.md.tmp")
        tmp.writeText(document)
        if (!tmp.renameTo(f)) { f.writeText(document); tmp.delete() }
        true
    } catch (_: Throwable) { false }

    /** Clear the mirror (the only local "forget" — used on Restore defaults). */
    fun clear(ctx: Context) { try { file(ctx).delete() } catch (_: Throwable) {} }
}
