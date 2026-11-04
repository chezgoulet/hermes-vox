package com.hermesvox

import android.app.Activity
import android.content.SharedPreferences

/**
 * PhoneToolsOffer — the one-time first-run offer of the phone's setup sheet (#421).
 *
 * The offer belongs to the moment first run has *proved* a connection and Presence is the user's
 * chosen layer: an agent now exists, and the phone can be offered to it. It opens the same
 * [PhoneToolsSheet] Settings uses, so there is one flow and one set of words — never a second
 * setup path that can drift.
 *
 * "Once" is a promise: declining, closing, or accepting all record the offer, and the Settings row
 * stays as the way back in. [shouldOffer] is pure and tested without an Activity.
 */
object PhoneToolsOffer {

    /** Set once the offer has been shown, whatever the answer was. Never offered twice. */
    const val PREF_OFFERED = "phone_tools_offered"

    fun shouldOffer(connectionVerified: Boolean, presenceOn: Boolean, offered: Boolean): Boolean =
        connectionVerified && presenceOn && !offered

    /**
     * Show the sheet if this is the first eligible moment. [onDone] runs after the sheet closes (or
     * immediately when there is nothing to offer), so the caller's continuation is never skipped.
     */
    fun offerOnce(activity: Activity, prefs: SharedPreferences, onDone: () -> Unit) {
        val verified = prefs.getString("url", "").orEmpty().isNotBlank()
        if (!shouldOffer(verified, PresenceGate.chosen(prefs), prefs.getBoolean(PREF_OFFERED, false))) {
            onDone()
            return
        }
        if (activity.isFinishing) {
            onDone()
            return
        }
        prefs.edit().putBoolean(PREF_OFFERED, true).apply()
        if (activity !is androidx.appcompat.app.AppCompatActivity) {
            onDone()
            return
        }
        PhoneToolsSheet(
            activity,
            session = PhoneSetupStore.session(activity),
            firstRun = true,
            onDismiss = onDone,
        ).show()
    }
}
