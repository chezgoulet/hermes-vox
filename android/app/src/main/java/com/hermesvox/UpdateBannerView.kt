package com.hermesvox

import android.app.Activity
import android.content.SharedPreferences
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The what's-new banner: shown once on the first launch of a new version, never on
 * a fresh install, never twice for the same version.
 *
 * It exists because a changelog buried in Settings cannot warn anyone about a
 * breaking change before they hit it. The banner is the one place the app can say
 * "this release changed something you must act on" at the moment the app is
 * opened after the update.
 */
internal object UpdateBannerView {

    fun maybeShow(
        activity: Activity,
        root: View,
        prefs: SharedPreferences,
        currentVersion: Int,
        onAlert: (ReleaseAlert) -> Unit,
    ) {
        val banner = root.findViewById<View>(R.id.update_banner) ?: return
        val seen = prefs.getInt(ReleaseNotes.PREF_LAST_SEEN, 0)

        // A fresh install is not an upgrade: record the version and stay quiet.
        if (seen == 0) {
            prefs.edit().putInt(ReleaseNotes.PREF_LAST_SEEN, currentVersion).apply()
            return
        }

        val note = ReleaseNotes.forVersion(currentVersion)
        if (!ReleaseNotes.shouldShow(seen, currentVersion, note != null) || note == null) return

        root.findViewById<TextView>(R.id.update_title).text = note.title
        root.findViewById<TextView>(R.id.update_body).text =
            (listOf("What's new in ${note.versionName}:") + note.bullets.map { "• $it" }).joinToString("\n")

        val actions = root.findViewById<LinearLayout>(R.id.update_actions)
        actions.removeAllViews()
        for (alert in note.alerts) {
            val b = Button(activity).apply {
                text = alert.label
                isAllCaps = false
                setOnClickListener {
                    dismiss(activity, banner, prefs, currentVersion)
                    onAlert(alert)
                }
            }
            actions.addView(
                b,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = (8 * activity.resources.displayMetrics.density).toInt() },
            )
        }
        actions.visibility = if (note.alerts.isEmpty()) View.GONE else View.VISIBLE

        root.findViewById<TextView>(R.id.update_dismiss).setOnClickListener {
            dismiss(activity, banner, prefs, currentVersion)
        }
        banner.visibility = View.VISIBLE
    }

    private fun dismiss(
        activity: Activity,
        banner: View,
        prefs: SharedPreferences,
        currentVersion: Int,
    ) {
        prefs.edit().putInt(ReleaseNotes.PREF_LAST_SEEN, currentVersion).apply()
        banner.visibility = View.GONE
        // Keep the recording honest for anything else that reads the version later.
        activity.window.decorView.requestLayout()
    }
}
