package com.hermesvox

import android.content.Context
import android.content.SharedPreferences

/**
 * AlphaGate — #402/D29: the single gate for everything unfinished.
 *
 * One master switch, [KEY_MASTER], **off by default**, at the very top of Settings. Behind it,
 * the feature sets marked alpha ([FeatureSet]), **Presence first**, each selected on its own so a
 * tester can take one without taking all. A set runs only when the master is on AND the set is
 * selected ([allows]); with the master off every alpha surface is dimmed and unreachable, and
 * nothing alpha runs — the runtime readers ([PresenceGate.enabled], the call's capture setup) ask
 * this object, not the feature's own preference alone.
 *
 * A set's own preferences keep their meaning *inside* the gate: Presence's switch, and the two
 * continuous-listening switches (which default **on**, D29), apply only once the set is allowed.
 *
 * Upgrades keep what was running ([migrate]): an install that already had Presence running (the
 * layer chosen and its model downloaded) or continuous capture switched on keeps it, with the
 * master on and that set selected. A fresh install gets the master off and nothing selected.
 *
 * Pure JVM except the thin `SharedPreferences` adapters.
 */
object AlphaGate {

    const val KEY_MASTER = "alpha_features"
    const val DEFAULT_MASTER = false

    /**
     * The sets marked alpha, in the order the panel lists them — Presence first (D29). A new
     * alpha feature joins here and inherits the gate. A set is off until selected.
     */
    enum class FeatureSet(val key: String, val label: String, val blurb: String) {
        PRESENCE(
            "alpha_set_presence", "Presence",
            "The being on the phone: it speaks its own short lines while your agent works, in " +
                "your agent's voice. Needs its on-device model (about 2.6 GB) and a phone with " +
                "8 GB of memory. While a call is live it holds about 3 GB of memory. Unfinished, " +
                "and honest about it.",
        ),
        CONTINUOUS_LISTENING(
            "alpha_set_continuous_listening", "Continuous listening",
            "Continuous capture keeps the microphone open and reads the recogniser every 100 ms; " +
                "semantic endpointing judges when you have finished instead of waiting out a " +
                "silence. Costs about 300 MB more memory during a call. Both switches live in " +
                "Hearing.",
        ),
        TURN_TAKING(
            "alpha_set_turn_taking", "Turn-taking",
            "The being keeps up with you mid-conversation: a soft acknowledgement while you talk " +
                "at length, a quick word to stop it told apart from a new question, and the " +
                "thread picked up after you cut in. Needs continuous listening for the " +
                "acknowledgements.",
        ),
        PHONE_TOOLS(
            "alpha_set_phone_tools", "Phone tools",
            "Lets your agent act on this phone — a timer, an alarm, Do Not Disturb, the volume. " +
                "Vox runs a small server on your tailnet with its own token, and your agent " +
                "reaches it over MCP. Off until you set it up.",
        ),
        QUIET_CALLS(
            "alpha_set_quiet_calls", "Quiet calls",
            "Silences notifications while a call is open, using your phone's Do Not Disturb with " +
                "your own exceptions, and puts things back when you hang up. Off until you turn " +
                "it on in Hearing; asks for Do Not Disturb access then.",
        ),
    }

    /** What the gate reads, narrow enough for an in-memory fake. */
    fun interface Reader {
        fun getBoolean(key: String, default: Boolean): Boolean
    }

    fun reader(prefs: SharedPreferences): Reader = Reader { k, d -> prefs.getBoolean(k, d) }

    fun masterOn(r: Reader): Boolean = r.getBoolean(KEY_MASTER, DEFAULT_MASTER)

    fun selected(r: Reader, set: FeatureSet): Boolean = r.getBoolean(set.key, false)

    /** The one question every alpha surface asks: may [set] run (and be reached) right now? */
    fun allows(r: Reader, set: FeatureSet): Boolean = masterOn(r) && selected(r, set)

    fun allows(prefs: SharedPreferences, set: FeatureSet): Boolean = allows(reader(prefs), set)

    fun allows(context: Context, set: FeatureSet): Boolean =
        allows(context.getSharedPreferences("hv", Context.MODE_PRIVATE), set)

    /** How many sets are selected — the summary line under the master switch. */
    fun selectedCount(r: Reader): Int = FeatureSet.entries.count { selected(r, it) }

    /** The migration's write surface. */
    interface Store : Reader {
        fun contains(key: String): Boolean
        fun getString(key: String): String?
        fun putBoolean(key: String, value: Boolean)
    }

    private class PrefsStore(private val prefs: SharedPreferences) : Store {
        override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
        override fun contains(key: String) = prefs.contains(key)
        override fun getString(key: String): String? = prefs.getString(key, null)
        override fun putBoolean(key: String, value: Boolean) {
            prefs.edit().putBoolean(key, value).apply()
        }
    }

    /**
     * One-shot, idempotent: once [KEY_MASTER] exists, nothing is touched again. Must run BEFORE
     * [PresenceGate.migrate], because "the Presence key already exists" is how an upgrade is told
     * from a fresh install (that migration writes the key on every first start).
     *
     * [presenceModelInstalled] is whether Presence could actually have been running: a layer that
     * was chosen but had no model was not running, so it is not carried into Alpha.
     */
    fun migrate(s: Store, presenceModelInstalled: Boolean) {
        if (s.contains(KEY_MASTER)) return
        val presenceWasOn = when {
            s.contains(PresenceGate.KEY_PRESENCE_ENABLED) ->
                s.getBoolean(PresenceGate.KEY_PRESENCE_ENABLED, PresenceGate.DEFAULT_ENABLED)
            s.contains(PresenceGate.LEGACY_KEY_VOICE_MODE) ->
                s.getString(PresenceGate.LEGACY_KEY_VOICE_MODE)?.trim()?.lowercase() == PresenceGate.LEGACY_ENHANCED
            else -> false   // a fresh install: nothing was running
        } && presenceModelInstalled
        // Continuous capture shipped off; only an explicit "on" was running.
        val continuousWasOn = s.contains(ContinuousCapture.KEY) && s.getBoolean(ContinuousCapture.KEY, false)
        if (presenceWasOn) s.putBoolean(FeatureSet.PRESENCE.key, true)
        if (continuousWasOn) s.putBoolean(FeatureSet.CONTINUOUS_LISTENING.key, true)
        s.putBoolean(KEY_MASTER, presenceWasOn || continuousWasOn)
    }

    fun migrate(prefs: SharedPreferences, presenceModelInstalled: Boolean) =
        migrate(PrefsStore(prefs), presenceModelInstalled)
}
