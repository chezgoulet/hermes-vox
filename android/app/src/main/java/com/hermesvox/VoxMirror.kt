package com.hermesvox

import android.content.Context
import java.io.File
import java.security.MessageDigest

/**
 * VoxMirror — the device-side VOX.md mirror (ER Phase 3), one document PER PROFILE.
 *
 * PULL-ONLY: the app never writes identity upstream; each file here is a refetchable local
 * cache of the gateway-authoritative VOX.md. All I/O is private-filesDir (no external
 * storage, no backup surface — allowBackup is false app-wide).
 *
 * **The profile identity is [Profiles.PREF_ACTIVE] — the saved profile's NAME.** That is
 * the field the app already treats as "the active connection" (#174): the switcher marks
 * it ([ProfileDialogs.activeName]), [Profiles.headerLabel] shows it beside the agent's
 * name, and [MainActivity.switchProfile] writes it. It is the right key because it is the
 * *user's* identity for the connection: two profiles can share one gateway and one URL
 * (`https://host/p/<name>` with its own key + scope), so keying on the URL would merge two
 * different entities, while the name separates them. The rest of the connection prefs are
 * just where the active profile is applied.
 *
 * The name is a user/host string, so it is ENCODED into one filesystem-safe directory
 * component ([dirName]) — injectively, so two profiles can never share a file, and a name
 * like `../x` can never escape `vox/`.
 *
 * Layout: `filesDir/vox/<encoded profile>/VOX.md`. An install that has never saved a
 * profile uses the literal `@` bucket ([UNNAMED]), a component [dirName] cannot produce
 * for a real name. The pre-profile device-wide file `filesDir/vox/VOX.md` is migrated into
 * the active profile's slot exactly once, on first use with Presence on ([migrate]):
 * copied, verified, and only then removed.
 *
 * Everything but the Context adapters is pure JVM over a root [File], so the rules are
 * proven off-device.
 */
object VoxMirror {

    const val DIR = "vox"
    const val NAME = "VOX.md"

    /** The slot of an install with no active profile. `@` is never produced by [dirName]. */
    const val UNNAMED = "@"

    /** Longest readable directory component; anything longer folds into a hash. */
    private const val MAX_DIR_CHARS = 64

    private const val HEX = "0123456789abcdef"

    /**
     * The directory component a profile name gets.
     *
     * `[A-Za-z0-9_-]` passes through (the host's profile spec is
     * `[a-z0-9][a-z0-9_-]{0,63}`, so a real profile keeps its own readable name); every
     * other UTF-8 byte becomes `%XX`. That makes the mapping injective — a literal `%` is
     * always escaped — and it keeps separators, leading dots and dot-only components out of
     * the path. A name long enough to overrun a filesystem component folds to
     * `%<sha256 prefix>`, a shape a passthrough name can never take.
     */
    fun dirName(profile: String): String {
        val name = profile.trim()
        if (name.isEmpty()) return UNNAMED
        val out = StringBuilder(name.length)
        for (b in name.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (c < 0x80 && (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '-' || ch == '_'))
                out.append(ch)
            else out.append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
        }
        if (out.length <= MAX_DIR_CHARS) return out.toString()
        return "%" + MessageDigest.getInstance("SHA-256")
            .digest(name.toByteArray(Charsets.UTF_8))
            .take(16).joinToString("") { b -> "%02x".format(b) }
    }

    /** The mirror file for [profile] under [root]: `root/vox/<profile>/VOX.md`. */
    fun file(root: File, profile: String): File = File(File(File(root, DIR), dirName(profile)), NAME)

    /** The pre-profile, device-wide mirror (`root/vox/VOX.md`) — read only by [migrate]. */
    fun legacyFile(root: File): File = File(File(root, DIR), NAME)

    /** Read the mirrored document (null when absent/unreadable). */
    fun read(root: File, profile: String): String? = try {
        val f = file(root, profile)
        if (f.exists()) f.readText().ifBlank { null } else null
    } catch (_: Throwable) { null }

    /** Mirror a validated document into [profile]'s slot. Returns false when the write
     *  failed — the caller surfaces the honest state (never a silent success). */
    fun write(root: File, profile: String, document: String): Boolean = try {
        val f = file(root, profile)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "$NAME.tmp")
        tmp.writeText(document)
        if (!tmp.renameTo(f)) { f.writeText(document); tmp.delete() }
        true
    } catch (_: Throwable) { false }

    /** What [migrate] did, so a caller (and a test) can tell "kept" from "moved". */
    enum class Migration {
        /** Nothing to migrate: no legacy file (the normal case after the first run). */
        NONE,
        /** The legacy document now lives in the profile's slot; the legacy file is gone. */
        COPIED,
        /** The slot was already filled; nothing was written and nothing was deleted. */
        ALREADY,
    }

    /**
     * Move the pre-profile device-wide mirror into [profile]'s slot, ONCE.
     *
     * Safe by construction: the legacy bytes are READ first, written to the new slot (which
     * makes its own directory), and the legacy file is removed only after the new file
     * exists with the same text. A failed copy leaves the original exactly where it was;
     * an existing per-profile mirror is never overwritten (the legacy file is kept rather
     * than guessed away). Idempotent: with no legacy file, or with the slot already filled,
     * nothing is written.
     */
    fun migrate(root: File, profile: String): Migration {
        val legacy = legacyFile(root)
        if (!legacy.isFile) return Migration.NONE
        if (file(root, profile).isFile) return Migration.ALREADY
        val text = try { legacy.readText() } catch (_: Throwable) { return Migration.NONE }
        if (text.isBlank()) return Migration.NONE
        if (!write(root, profile, text)) return Migration.NONE
        val copied = try { file(root, profile).readText() } catch (_: Throwable) { null }
        if (copied != text) return Migration.NONE      // a partial copy is not a migration
        try { legacy.delete() } catch (_: Throwable) {}
        return Migration.COPIED
    }

    // ---- The active profile (thin adapters; the rules above stay pure) ----

    /** The name the app treats as the active profile ("" when none has been saved). */
    fun activeProfile(prefs: android.content.SharedPreferences): String =
        prefs.getString(Profiles.PREF_ACTIVE, "").orEmpty()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("hv", Context.MODE_PRIVATE)

    fun activeProfile(ctx: Context): String = activeProfile(prefs(ctx))

    /** The active profile's mirror file. */
    fun file(ctx: Context): File = file(ctx.filesDir, activeProfile(ctx))

    /** Read the active profile's document. */
    fun read(ctx: Context): String? = read(ctx.filesDir, activeProfile(ctx))

    /** Mirror a validated document into the ACTIVE profile's slot. */
    fun write(ctx: Context, document: String): Boolean = write(ctx.filesDir, activeProfile(ctx), document)

    /** Clear the active profile's mirror (the only local "forget"). */
    fun clear(ctx: Context) { try { file(ctx).delete() } catch (_: Throwable) {} }

    /** Migrate the old device-wide file into the active profile's slot (once). */
    fun migrate(ctx: Context): Migration = migrate(ctx.filesDir, activeProfile(ctx))
}
