package com.hermesvox

import android.content.Context
import android.content.SharedPreferences

/**
 * AlphaGate — #402/D29: the single gate for everything unfinished.
 *
 * One master switch, [KEY_MASTER], **off by default**, immediately above the alpha block at the
 * end of Settings. Behind it, the feature sets marked alpha ([FeatureSet]), **Presence first**,
 * each selected on its own so a tester can take one without taking all. A set runs only when the
 * master is on AND the set is selected ([allows]); with the master off every alpha surface is
 * dimmed and unreachable, and nothing alpha runs — the runtime readers ([PresenceGate.enabled],
 * the call's capture setup) ask this object, not the feature's own preference alone.
 *
 * A set's own preferences keep their meaning *inside* the gate: Presence's switch, and the two
 * continuous-listening switches (which default **on**, D29), apply only once the set is allowed.
 * Continuous listening is how the being hears, so it also requires the Presence set; Phone tools
 * is the mind acting on the phone and is independent of Presence (D38). Either can be taken
 * without the other.
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
                "your agent's voice. It may look at what the phone is doing; it does not act on " +
                "the phone. Needs its on-device model (about 2.6 GB) and a phone with 8 GB of " +
                "memory. While a call is live it holds about 3 GB of memory. Unfinished, and " +
                "honest about it. Independent of Phone tools.",
        ),
        CONTINUOUS_LISTENING(
            "alpha_set_continuous_listening", "Continuous listening",
            "Continuous capture keeps the microphone open and reads the recogniser every 100 ms; " +
                "semantic endpointing judges when you have finished instead of waiting out a " +
                "silence. Costs about 300 MB more memory during a call. Both switches live in " +
                "Hearing. Requires Presence: continuous listening is how the being hears.",
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
            "The mind acting on the phone: your agent can set a timer or an alarm, change Do Not " +
                "Disturb or the volume, through one tool. Vox runs a small server on your tailnet " +
                "with its own token, and your agent reaches it over MCP. Off until you set it up. " +
                "Independent of Presence.",
        ),
        WAKE_WORD(
            "alpha_set_wake_word", "Wake word",
            "Saying the wake phrase wakes the app hands-free. Enrolment records your takes of the " +
                "phrase, 15 seconds of you talking and 15 seconds of your room into an encrypted " +
                "store on this phone; nothing is trained yet and the detector is a preview. " +
                "Turning Alpha features off stops the wake word listening, not just its row.",
        ),
        DIGITAL_ASSISTANT(
            "alpha_set_digital_assistant", "Digital assistant",
            "Asks Android for the digital-assistant role, so the assistant gesture opens Vox " +
                "instead of another app. Android owns the role and the phone maker owns the " +
                "button, so the row asks and points at Android's own settings; it grants nothing " +
                "by itself and Vox keeps no listener until the call screen is open.",
        ),
    }

    /** What the gate reads, narrow enough for an in-memory fake. */
    fun interface Reader {
        fun getBoolean(key: String, default: Boolean): Boolean
    }

    fun reader(prefs: SharedPreferences): Reader = Reader { k, d -> prefs.getBoolean(k, d) }

    fun masterOn(r: Reader): Boolean = r.getBoolean(KEY_MASTER, DEFAULT_MASTER)

    /** The same question asked of prefs directly, for callers that hold them and not a Reader. */
    fun masterOn(prefs: SharedPreferences): Boolean = masterOn(reader(prefs))

    fun selected(r: Reader, set: FeatureSet): Boolean = r.getBoolean(set.key, false)

    /**
     * The one dependency between sets: continuous listening is how the being hears, so it
     * cannot run without Presence. Null means none.
     *
     * Phone tools has no entry here on purpose. The phone is the mind's capability and the being
     * is a voice; either can be taken without the other (D38).
     */
    fun dependencyRefusal(r: Reader, set: FeatureSet): String? =
        if (set == FeatureSet.CONTINUOUS_LISTENING && !selected(r, FeatureSet.PRESENCE))
            "Requires Presence: continuous listening is how the being hears."
        else null

    /**
     * The one question every alpha surface asks: may [set] run (and be reached) right now?
     *
     * The set's own selection is not enough for continuous listening — a stored preference from
     * an earlier build where Presence was off must be inert, not merely dimmed in the picker.
     */
    fun allows(r: Reader, set: FeatureSet): Boolean =
        masterOn(r) && selected(r, set) && dependencyRefusal(r, set) == null

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
