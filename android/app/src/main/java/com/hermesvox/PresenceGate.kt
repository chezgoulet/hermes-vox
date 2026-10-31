package com.hermesvox

import android.content.Context
import android.content.SharedPreferences

/**
 * PresenceGate — D16/#222: Presence is a layer on one normal operation, not a mode.
 *
 * Two facts decide whether the layer runs, and they are deliberately different kinds of fact:
 *
 *  - **The user's choice**, [KEY_PRESENCE_ENABLED], default on. This is the only thing the
 *    Settings switch writes.
 *  - **What the phone can supply** — the model is downloaded AND able to start. When either is
 *    false the app is simply normal operation: no label, no pill, no half-state. The Settings
 *    presence row is the only place the absence is explained, because it is the only place a
 *    user can act on it.
 *
 * `voice_mode` is retired but migrated rather than dropped. [migrate] translates the old
 * tri-state once: a stored `realtime` becomes off, a stored `enhanced` becomes on, and an
 * install that never touched it gets on. A setting silently reversed is the failure this
 * exists to prevent, so the migration is one-shot and idempotent: once [KEY_PRESENCE_ENABLED]
 * exists, every later pass leaves it alone.
 *
 * Pure JVM except the two thin `SharedPreferences` adapters.
 */
object PresenceGate {

    const val KEY_PRESENCE_ENABLED = "presence_enabled"
    const val DEFAULT_ENABLED = true

    /** The retired key and the two values it could hold. Read only by [migrate]. */
    const val LEGACY_KEY_VOICE_MODE = "voice_mode"
    const val LEGACY_REALTIME = "realtime"
    const val LEGACY_ENHANCED = "enhanced"

    /** The whole migration surface, narrow enough for an in-memory fake. */
    interface Store {
        fun contains(key: String): Boolean
        fun getString(key: String): String?
        fun putBoolean(key: String, value: Boolean)
        fun remove(key: String)
    }

    private class SharedPreferencesStore(private val prefs: SharedPreferences) : Store {
        override fun contains(key: String): Boolean = prefs.contains(key)
        override fun getString(key: String): String? = prefs.getString(key, null)
        override fun putBoolean(key: String, value: Boolean) {
            prefs.edit().putBoolean(key, value).apply()
        }
        override fun remove(key: String) {
            prefs.edit().remove(key).apply()
        }
    }

    /** The old stored choice, translated to one boolean. An unknown/absent value means on. */
    fun migrated(legacy: String?): Boolean = when (legacy?.trim()?.lowercase()) {
        LEGACY_REALTIME -> false
        LEGACY_ENHANCED -> true
        else -> DEFAULT_ENABLED
    }

    /**
     * Run the one-shot migration. If the new key already exists this does nothing at all,
     * which is what makes a second app start (or a second caller) harmless. The legacy key is
     * removed only after the new value has been written.
     */
    fun migrate(store: Store) {
        if (store.contains(KEY_PRESENCE_ENABLED)) return
        val legacy = store.getString(LEGACY_KEY_VOICE_MODE)
        store.putBoolean(KEY_PRESENCE_ENABLED, migrated(legacy))
        store.remove(LEGACY_KEY_VOICE_MODE)
    }

    fun migrate(prefs: SharedPreferences) = migrate(SharedPreferencesStore(prefs))

    fun enabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_PRESENCE_ENABLED, DEFAULT_ENABLED)

    fun enabled(context: Context): Boolean =
        enabled(context.getSharedPreferences("hv", Context.MODE_PRIVATE))

    /**
     * The most recent verdict from the host that owns the engine: can the presence model
     * start on this device? Unknown (nothing tried yet) counts as yes — the load will tell
     * us, and refusing to try would be a policy, not a fact.
     */
    @Volatile
    var modelCanStart: Boolean = true

    /** What the user asked for, and what the phone can actually supply. */
    enum class State { ON, OFF, NO_MODEL, CANNOT_START }

    fun state(enabled: Boolean, installed: Boolean, canStart: Boolean): State = when {
        !enabled -> State.OFF
        !installed -> State.NO_MODEL
        !canStart -> State.CANNOT_START
        else -> State.ON
    }

    fun active(enabled: Boolean, installed: Boolean, canStart: Boolean): Boolean =
        state(enabled, installed, canStart) == State.ON
}
