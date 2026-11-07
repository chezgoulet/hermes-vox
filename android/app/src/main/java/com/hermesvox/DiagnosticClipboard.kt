package com.hermesvox

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast

/**
 * DiagnosticClipboard — the one copy of the report block (#417).
 *
 * The block has one door left: the Presence row in Settings. (#418 opened a second door, a
 * command and its switch; both are gone, see D29.) A door that assembled its
 * own block would drift the moment the collector gained a line, so the text comes from one
 * place — [PresenceDiagnosticCollect.collect], the collector #417 shipped — and the door only
 * decides *when* to copy, never *what*.
 *
 * The clipboard label and the two outcomes are here too, so the words a person reads after
 * the tap are the block's own.
 */
object DiagnosticClipboard {

    /** The clipboard label — the block's own marker, so a pasted report is self-describing. */
    const val LABEL = PresenceDiagnostic.MARKER

    const val COPIED = "Diagnostic summary copied — safe to paste in a public report"
    const val FAILED = "Could not copy the diagnostic summary"

    /** The exact text the door puts on the clipboard. */
    fun block(activity: Activity, prefs: SharedPreferences): String =
        PresenceDiagnosticCollect.collect(activity, prefs)

    /**
     * Copies the block and says so. Returns whether it landed, so a command can report
     * done / failed on its own row instead of a toast that contradicts it.
     */
    fun copy(activity: Activity, prefs: SharedPreferences): Boolean {
        val text = try { block(activity, prefs) } catch (_: Throwable) { "" }
        if (text.isBlank()) {
            Toast.makeText(activity, FAILED, Toast.LENGTH_SHORT).show()
            return false
        }
        return try {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(LABEL, text))
            Toast.makeText(activity, COPIED, Toast.LENGTH_SHORT).show()
            true
        } catch (_: Throwable) {
            Toast.makeText(activity, FAILED, Toast.LENGTH_SHORT).show()
            false
        }
    }
}
