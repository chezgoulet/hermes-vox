package com.hermesvox.core

import android.content.SharedPreferences

/**
 * An in-memory [SharedPreferences] for JVM tests.
 *
 * `MtlsConfig` reads its per-origin aliases from the real "hv" store on the device; on the
 * JVM there is no Context to ask for one. Handing the production code this store keeps
 * every decision it makes — origin key, save rule, failure reason — on the real path,
 * with only the backing map replaced.
 */
class FakeSharedPreferences : SharedPreferences {
    private val values = HashMap<String, Any?>()

    @Synchronized override fun getAll(): MutableMap<String, *> = HashMap(values)
    @Synchronized override fun getString(key: String, defValue: String?): String? =
        values[key] as? String ?: defValue
    @Synchronized override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST") (values[key] as? MutableSet<String>) ?: defValues
    @Synchronized override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
    @Synchronized override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
    @Synchronized override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
    @Synchronized override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    @Synchronized override fun contains(key: String): Boolean = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?,
    ) = Unit

    /** Writes straight into the map on commit/apply, since nothing observes changes here. */
    private inner class Editor : SharedPreferences.Editor {
        private val pending = HashMap<String, Any?>()
        private val removals = HashSet<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, value: MutableSet<String>?) = apply { pending[key] = value }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removals += key }
        override fun clear() = apply { clearAll = true }

        override fun commit(): Boolean { write(); return true }
        override fun apply() = write()

        private fun write() {
            synchronized(this@FakeSharedPreferences) {
                if (clearAll) values.clear()
                for (k in removals) values.remove(k)
                values.putAll(pending)
            }
            pending.clear()
            removals.clear()
            clearAll = false
        }
    }
}
