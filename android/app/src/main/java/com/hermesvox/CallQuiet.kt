package com.hermesvox

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.provider.Settings

/**
 * CallQuiet — #325: silence notifications while a call is open. A setting, **off by default**
 * (D29: the app does not decide on the user's behalf that a call outranks a message), and its
 * own switch alone: it is not an Alpha set and no master gate stands in front of it.
 *
 * What it does, scoped as agreed on the issue:
 *
 *  - **Only during a call**, not the whole time the app is open, so a forgotten session cannot
 *    hold the phone's notifications for hours.
 *  - It uses the phone's own Do Not Disturb in **priority** mode, so the user's exceptions
 *    (alarms, starred contacts, repeat callers — whatever they set) still come through.
 *  - It needs Do Not Disturb access, which only the user can grant in system settings. It is
 *    asked for when the setting is switched on, never at first run; without it the call simply
 *    is not silenced, and the Settings row says why.
 *  - **It restores exactly what it changed.** If Do Not Disturb was already on when the call
 *    began, it is left alone and not touched at the end. If the user changes the filter during
 *    the call, their choice wins and nothing is restored over it.
 *  - **A crash cannot strand it on.** The filter it replaced is written down before it is
 *    changed; [recover] (at app start) puts it back if no call is live.
 *
 * [plan]/[restoreTo] are the pure decisions; the rest is a thin adapter.
 */
object CallQuiet {

    const val KEY = "silence_notifications_in_call"
    const val DEFAULT_ON = false

    /** The filter this app set and the one it replaced; present only while a quiet call is live. */
    private const val KEY_SET = "call_quiet_set_filter"
    private const val KEY_PREVIOUS = "call_quiet_previous_filter"

    /** The filter a call switches to, given the one in force; null = leave it alone. */
    fun plan(enabled: Boolean, granted: Boolean, current: Int): Int? = when {
        !enabled || !granted -> null
        // Already quieter than "all": the user (or another app) chose it; never override it.
        current != NotificationManager.INTERRUPTION_FILTER_ALL -> null
        else -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
    }

    /** What to put back at the end of a call; null = leave it (nothing was set, or the user
     *  changed it since). */
    fun restoreTo(setByUs: Int?, previous: Int?, current: Int): Int? =
        if (setByUs == null || previous == null || current != setByUs) null else previous

    /** The feature's own switch, with no gate in front of it. */
    fun enabled(getBoolean: (String, Boolean) -> Boolean): Boolean =
        getBoolean(KEY, DEFAULT_ON)

    fun enabled(prefs: SharedPreferences): Boolean =
        enabled { key, default -> prefs.getBoolean(key, default) }

    fun granted(context: Context): Boolean = nm(context)?.isNotificationPolicyAccessGranted == true

    /** The system screen where the user grants Do Not Disturb access. */
    fun accessIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

    /** At call start. */
    fun begin(context: Context, prefs: SharedPreferences) {
        val nm = nm(context) ?: return
        val enabled = enabled(prefs)
        val granted = nm.isNotificationPolicyAccessGranted
        val current = nm.currentInterruptionFilter
        val target = plan(enabled, granted, current)
        if (enabled && !granted) VoxLog.w("event=call-quiet skip reason=no-dnd-access")
        if (target == null) return
        // Written BEFORE the change: a crash between the two must still be recoverable.
        prefs.edit().putInt(KEY_PREVIOUS, current).putInt(KEY_SET, target).commit()
        try {
            nm.setInterruptionFilter(target)
            VoxLog.d("event=call-quiet on from=$current to=$target")
        } catch (t: Throwable) {
            prefs.edit().remove(KEY_PREVIOUS).remove(KEY_SET).apply()
            VoxLog.w("event=call-quiet fail reason=${t.javaClass.simpleName}")
        }
    }

    /** At call end (and at [recover]). Restores only what this app changed. */
    fun end(context: Context, prefs: SharedPreferences) {
        if (!prefs.contains(KEY_SET)) return
        val setByUs = prefs.getInt(KEY_SET, -1)
        val previous = prefs.getInt(KEY_PREVIOUS, -1)
        prefs.edit().remove(KEY_SET).remove(KEY_PREVIOUS).apply()
        val nm = nm(context) ?: return
        val current = nm.currentInterruptionFilter
        val back = restoreTo(setByUs, previous, current)
        if (back == null) { VoxLog.d("event=call-quiet off restored=false current=$current"); return }
        try {
            if (nm.isNotificationPolicyAccessGranted) nm.setInterruptionFilter(back)
            VoxLog.d("event=call-quiet off restored=true to=$back")
        } catch (t: Throwable) {
            VoxLog.w("event=call-quiet restore-fail reason=${t.javaClass.simpleName}")
        }
    }

    /** At app start: a quiet call the process did not live to end is ended now. */
    fun recover(context: Context, prefs: SharedPreferences) = end(context, prefs)

    private fun nm(context: Context): NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
}
