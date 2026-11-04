package com.hermesvox

import android.content.SharedPreferences

/**
 * ScopedPrefs — the Android edge of [ProfileScope], and nothing else.
 *
 * The live `hv` prefs store scoped values in four different types (String, Boolean, Float, Int),
 * and a value read with the wrong getter throws `ClassCastException` — which on a phone is a
 * crash on the next Settings screen, not a compile error. So every read goes through
 * [SharedPreferences.getAll] and every write goes through the setter the key's [ProfileScope.Kind]
 * names. The registry rules (capture, adopt, migrate, what a switch writes) are pure and live in
 * [ProfileScope]; this file only moves bytes.
 */
object ScopedPrefs {

    /** The current value of [k], as the canonical string [ProfileScope] records, or null. */
    fun read(p: SharedPreferences, k: ProfileScope.Scoped): String? = try {
        when (k.kind) {
            ProfileScope.Kind.TEXT -> p.getString(k.key, null)
            ProfileScope.Kind.FLAG -> (p.all[k.key] as? Boolean)?.toString()
            ProfileScope.Kind.NUMBER -> (p.all[k.key] as? Number)?.toFloat()?.toString()
            ProfileScope.Kind.WHOLE -> (p.all[k.key] as? Number)?.toInt()?.toString()
        }
    } catch (_: Throwable) {
        null   // a value stored as another type is "no value", never a crash
    }

    /** The device-wide reader [ProfileScope] asks for. */
    fun reader(p: SharedPreferences): (ProfileScope.Scoped) -> String? = { k -> read(p, k) }

    /** The whole profile's own values, read from the live prefs (a change belongs to who was on). */
    fun capture(p: SharedPreferences): Map<String, String> = ProfileScope.capture(reader(p))

    /** Write [key] as the type it is read with; a key that is not scoped is plain text. */
    fun write(e: SharedPreferences.Editor, key: String, value: String) {
        when (ProfileScope.scoped(key)?.kind ?: ProfileScope.Kind.TEXT) {
            ProfileScope.Kind.TEXT -> e.putString(key, value)
            ProfileScope.Kind.FLAG -> e.putBoolean(key, value.equals("true", ignoreCase = true))
            ProfileScope.Kind.NUMBER -> value.toFloatOrNull()?.let { e.putFloat(key, it) } ?: Unit
            ProfileScope.Kind.WHOLE -> value.toIntOrNull()?.let { e.putInt(key, it) } ?: Unit
        }
    }

    /** Write a whole [ProfileScope.switchWrites] map. */
    fun write(e: SharedPreferences.Editor, values: Map<String, String>) {
        for ((k, v) in values) write(e, k, v)
    }
}
