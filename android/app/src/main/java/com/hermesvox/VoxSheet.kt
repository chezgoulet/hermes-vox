package com.hermesvox

import android.app.Dialog
import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat

/**
 * VoxSheet — the one scaffold every full-screen sheet stands on: the app's ground, a title
 * in the display face, a close mark, an optional step back, Escape/Back handling, and the
 * one entry motion sheets use (the Alpha panel's slide from the edge, [R.style.HvSlideFromEnd]).
 *
 * The commands sheet, the model switcher, the Alpha panel, Share and File each built this
 * header by hand, and the copies had drifted: two titles were not accessibility headings,
 * two close marks could not take keyboard focus. One scaffold, one behaviour.
 */
object VoxSheet {

    private fun dp(c: Context, v: Int) = (v * c.resources.displayMetrics.density).toInt()

    /** A full-screen sheet dialog on the app's own theme, entering with the sheet motion. */
    fun dialog(c: Context, fullWidth: Boolean = true): Dialog = Dialog(c, R.style.Theme_HermesVox).apply {
        window?.setWindowAnimations(R.style.HvSlideFromEnd)
        if (fullWidth) window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    /** The sheet's ground: a vertical column on hv_bg with the sheet margin. */
    fun root(c: Context): LinearLayout = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(ContextCompat.getColor(c, R.color.hv_bg))
        setPadding(dp(c, 18), dp(c, 18), dp(c, 18), dp(c, 18))
    }

    /**
     * The header row: an optional "←" (a step back inside the sheet), the title, and "✕".
     * The title is an accessibility heading; both marks take focus and say what they do.
     * Returns the title view so a sheet that changes level can retitle it.
     */
    fun header(
        root: LinearLayout,
        title: String,
        closeLabel: String,
        onClose: () -> Unit,
        back: Pair<String, () -> Unit>? = null,
    ): TextView {
        val c = root.context
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (back != null) row.addView(mark(c, "←", back.first, back.second).apply {
            setPadding(dp(c, 4), dp(c, 6), dp(c, 12), dp(c, 6))
        })
        val t = TextView(c, null, 0, R.style.TextVox_Title).apply {
            text = title
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            ViewCompat.setAccessibilityHeading(this, true)
        }
        row.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(mark(c, "✕", closeLabel, onClose))
        root.addView(row)
        return t
    }

    private fun mark(c: Context, glyph: String, label: String, onTap: () -> Unit) =
        TextView(c, null, 0, R.style.HvRowAction).apply {
            text = glyph; contentDescription = label; textSize = 20f
            isFocusable = true; isClickable = true
            minWidth = dp(c, 44); minHeight = dp(c, 44); gravity = Gravity.CENTER
            setPadding(dp(c, 12), dp(c, 6), dp(c, 4), dp(c, 6))
            setOnClickListener { onTap() }
        }

    /** A group header inside a sheet: the tag face, and a heading a screen reader can jump to. */
    fun subhead(c: Context, text: String): TextView = TextView(c, null, 0, R.style.TextVox_Tag).apply {
        this.text = text
        setPadding(0, dp(c, 18), 0, dp(c, 6))
        ViewCompat.setAccessibilityHeading(this, true)
    }

    /** Escape and Back: [onBack] first (a step back inside the sheet), else close. */
    fun keys(d: Dialog, onBack: () -> Boolean = { false }) {
        d.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_BACK)) {
                if (!onBack()) d.dismiss(); true
            } else false
        }
    }

    /** A step deeper inside a sheet uses the sheet's own motion; a step back fades in. */
    fun enter(v: View, deeper: Boolean) {
        v.startAnimation(android.view.animation.AnimationUtils.loadAnimation(v.context,
            if (deeper) R.anim.slide_in_end else R.anim.fade_in))
    }
}
