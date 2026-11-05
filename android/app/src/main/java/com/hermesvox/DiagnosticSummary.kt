package com.hermesvox

import android.content.SharedPreferences

/**
 * DiagnosticSummary — the switch that decides whether `/diagnostics` exists (#418).
 *
 * #417 put the report block behind the Presence row. The owner asked for a second door: a
 * command in the commands sheet, behind a toggle in the developer section of Settings,
 * **off by default**, turned on for someone who opts into Alpha.
 *
 * This object owns the preference and the two rules around it, and nothing else. It does not
 * build the block — [PresenceDiagnosticCollect] does, and both doors copy its bytes — and the
 * Presence row does not read this switch: the row is inside the Presence section already.
 *
 * The state is three-valued in practice, so it is stored as two facts:
 *  * [PREF] — whether the command exists now (absent reads as [DEFAULT_ON], off);
 *  * [PREF_CHOSEN] — that the *user* flipped it, which is what makes their choice outrank
 *    Alpha's. Without it, "off" could not be told from "never touched", and turning Alpha on
 *    again would overrule a deliberate off.
 *
 * Pure JVM except the thin `SharedPreferences` adapter.
 */
object DiagnosticSummary {

    /** The command as the sheet lists it and as a person types it. */
    const val COMMAND = "/diagnostics"

    /** The switch: does `/diagnostics` exist? */
    const val PREF = "diag_summary"

    /** Set when the user flipped it themselves — their choice then beats Alpha's. */
    const val PREF_CHOSEN = "diag_summary_chosen"

    /** Off by default: a copy command is a tool for a tester, not a row for everyone. */
    const val DEFAULT_ON = false

    /** What the switch reads and writes — narrow enough for an in-memory fake. */
    interface Store {
        fun contains(key: String): Boolean
        fun getBoolean(key: String, default: Boolean): Boolean
        fun putBoolean(key: String, value: Boolean)
    }

    private class PrefsStore(private val prefs: SharedPreferences) : Store {
        override fun contains(key: String) = prefs.contains(key)
        override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
        override fun putBoolean(key: String, value: Boolean) {
            prefs.edit().putBoolean(key, value).apply()
        }
    }

    fun store(prefs: SharedPreferences): Store = PrefsStore(prefs)

    /** Whether the command exists right now. */
    fun on(s: Store): Boolean = s.getBoolean(PREF, DEFAULT_ON)

    /** Whether the user has decided this themselves (either way). */
    fun chosen(s: Store): Boolean = s.contains(PREF_CHOSEN)

    /** The Settings switch. Turning it either way is the user's decision and is recorded as
     *  one, so a later Alpha-on leaves it alone. */
    fun setByUser(s: Store, on: Boolean) {
        s.putBoolean(PREF_CHOSEN, true)
        s.putBoolean(PREF, on)
    }

    /**
     * Alpha features were turned on. Someone who opts into alpha is a tester, and a tester
     * should be able to hand us a bug without hunting for a switch — so the command appears.
     *
     * One-way and never over the user: Alpha going off does not turn it off again, and a
     * switch the user set by hand ([chosen]) is left exactly as they set it.
     */
    fun alphaTurnedOn(s: Store) {
        if (chosen(s)) return
        s.putBoolean(PREF, true)
    }

    /**
     * What the catalogue may offer. A gated command exists only while its own switch is on;
     * everything else in the catalogue is untouched by this object.
     */
    fun gate(s: Store): CommandCatalog.Gate =
        CommandCatalog.Gate { c -> c.name != COMMAND || on(s) }

    /**
     * What `/diagnostics` puts on the clipboard: the same collector the Presence row calls,
     * with the same two inputs. The command is a door, not a second report — a second assembly
     * here would be the drift this has to rule out.
     */
    fun block(
        settings: PresenceDiagnostic.Settings,
        facts: PresenceDiagnosticCollect.Facts,
    ): String = PresenceDiagnosticCollect.block(settings, facts)

    /**
     * The command a typed (or dictated) word means, or null when the catalogue does not answer
     * to it. With the switch off this is null, so `/diagnostics` — like a name no build has
     * ever had — is ordinary conversation and never a silent copy. Only the word itself
     * matches: a sentence that happens to contain it is conversation either way.
     */
    fun commandFor(input: String, s: Store): CommandCatalog.Command? {
        val word = input.trim().lowercase().removePrefix("/")
        if (word != COMMAND.removePrefix("/")) return null
        return CommandCatalog.find(COMMAND, gate(s))
    }
}
