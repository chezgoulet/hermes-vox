package com.hermesvox

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.widget.Toast

/**
 * DiagnosticClipboard — the one copy of the report block (#417, #418).
 *
 * The block has two doors: the Presence row in Settings and the `/diagnostics` command. Two
 * doors that each assembled their own block would drift the moment one of them gained a line,
 * and the field would be told two different stories about the same phone. So the text comes
 * from one place — [PresenceDiagnosticCollect.collect], the collector #417 shipped — and the
 * door only decides *when* to copy, never *what*.
 *
 * The clipboard label and the two outcomes are here too, for the same reason: the words a
 * person reads after the tap are the same whichever door they used.
 */
object DiagnosticClipboard {

    /** The clipboard label — the block's own marker, so a pasted report is self-describing. */
    const val LABEL = PresenceDiagnostic.MARKER

    const val COPIED = "Diagnostic summary copied — safe to paste in a public report"
    const val FAILED = "Could not copy the diagnostic summary"

    /** The exact text both doors put on the clipboard. */
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
