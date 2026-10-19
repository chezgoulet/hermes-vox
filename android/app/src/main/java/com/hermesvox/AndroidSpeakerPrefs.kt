package com.hermesvox

import android.content.SharedPreferences

/** [SpeakerPrefs] over the app's "hv" SharedPreferences. `contains` is what keeps
 *  "absent" distinct from a stored 0. */
class AndroidSpeakerPrefs(private val prefs: SharedPreferences) : SpeakerPrefs {
    override fun getInt(key: String): Int? =
        if (prefs.contains(key)) prefs.getInt(key, 0) else null

    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }
}
