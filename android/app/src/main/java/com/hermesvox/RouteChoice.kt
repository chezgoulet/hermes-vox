package com.hermesvox

import android.content.Context
import android.content.SharedPreferences
import com.hermesvox.core.HermesSession
import com.hermesvox.core.StreamRoute

/**
 * RouteChoice — where the `/v1/runs` fallback lives between launches (#404 follow-up).
 *
 * A 404 from `POST /v1/runs` means one host's profile has no such route. It belongs to the
 * profile, not to the process: remember it under the profile's name, load it when that profile
 * is connected, and never probe it again on every turn. A profile that answered its admission
 * is never in this set, so it keeps riding `/v1/runs`.
 *
 * The value is a plain string set in `hv`: no secret, no migration, and a profile that was
 * removed leaves a harmless name behind.
 */
object RouteChoice {

    const val KEY_NO_RUNS = "route_no_runs_profiles"

    /** The key used before a profile has been seeded (one connection, no name yet). */
    const val DEFAULT_PROFILE = "default"

    fun profileName(get: (String) -> String): String =
        Profiles.activeName(get).ifBlank { DEFAULT_PROFILE }

    fun noRuns(prefs: SharedPreferences, profile: String): Boolean =
        prefs.getStringSet(KEY_NO_RUNS, emptySet()).orEmpty().contains(profile)

    fun markNoRuns(prefs: SharedPreferences, profile: String) {
        val names = prefs.getStringSet(KEY_NO_RUNS, emptySet()).orEmpty().toMutableSet()
        if (!names.add(profile)) return
        prefs.edit().putStringSet(KEY_NO_RUNS, names).apply()
    }

    /** Once per process: give the core the persisted answers and the app's log. */
    fun install(prefs: SharedPreferences) {
        StreamRoute.bindStore(
            load = { profile -> noRuns(prefs, profile) },
            save = { profile -> markNoRuns(prefs, profile) },
        )
        StreamRoute.log = { line -> VoxLog.d(line) }
    }

    /** Connect [session] to the active profile's route decision. */
    fun bind(session: HermesSession, prefs: SharedPreferences) {
        session.setProfile(profileName { prefs.getString(it, "").orEmpty() })
    }
}
