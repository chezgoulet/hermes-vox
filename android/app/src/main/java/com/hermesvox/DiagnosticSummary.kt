package com.hermesvox

import android.content.SharedPreferences

/**
 * #417: the diagnostic summary switch — the `vox-diag` block's one gate.
 *
 * The block itself ([PresenceDiagnostic]) is safe to publish: no transcript, key, URL or
 * hostname. Turning it into a command a person can reach is still a choice, so it has a switch
 * in Settings → About & Diagnostics beside the other logging switches, **off by default**, and
 * a command (`/diagnostics`) that exists only while the switch is on. A command that cannot run
 * is not listed.
 *
 * Nothing enables it on the person's behalf — not a first run, not another switch: the row says
 * what the block is for and what it does not carry, and the tap is the consent. The 0.9.1
 * default on a fresh install is off, which is what [DEFAULT_ON] (and the gate's default) state.
 *
 * Pure JVM except the thin `SharedPreferences` adapters, so the default and the gate are
 * unit-tested.
 */
object DiagnosticSummary {

    /** The switch in Settings → About & Diagnostics. */
    const val PREF = "debug_diag_summary"
    const val DEFAULT_ON = false

    /** The command the switch adds to the commands sheet, under "The agent". */
    const val COMMAND = "/diagnostics"

    /** What the command says when it copied the block: one line, no dialog. */
    const val COPIED = "Diagnostic summary copied"
    const val COPY_FAILED = "Could not copy the diagnostic summary"

    /** The gate [COMMAND] sits behind, as the command catalogue sees it. */
    enum class Gate(val pref: String, val default: Boolean = DEFAULT_ON) {
        /**
         * #417: the command is only on the sheet while this switch is on — the same principle
         * as the permission spine's schema following the grants: what cannot run is not listed.
         */
        DIAGNOSTIC_SUMMARY(DiagnosticSummary.PREF, DEFAULT_ON);

        fun open(reader: (String, Boolean) -> Boolean): Boolean = reader(pref, default)
    }

    /** What the switch reads, narrow enough for an in-memory fake. */
    fun interface Reader {
        fun getBoolean(key: String, default: Boolean): Boolean
    }

    fun enabled(r: Reader): Boolean = r.getBoolean(PREF, DEFAULT_ON)

    fun reader(prefs: SharedPreferences): Reader = Reader { k, d -> prefs.getBoolean(k, d) }

    fun enabled(prefs: SharedPreferences): Boolean = enabled(reader(prefs))

    /** The switch's tap. It writes the pref and nothing else: there is no second key. */
    fun set(prefs: SharedPreferences, on: Boolean) {
        prefs.edit().putBoolean(PREF, on).apply()
    }
}
