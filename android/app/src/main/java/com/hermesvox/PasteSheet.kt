package com.hermesvox

import android.app.Dialog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * #327 — the paste sheet: paste → preview → send, three states and never a silent send.
 *
 * It opens from the same place the commands screen does (the action row) and leads with
 * what it is: PASTE, then one line saying what the clipboard holds — or that it is empty,
 * or that the text is longer than one turn can carry and what would be dropped. The
 * preview is only a look; the text itself leaves through SEND and nothing else.
 *
 * The decision (empty / preview / cap / dropped count) lives in [ClipboardPaste] and
 * [PasteFlow], both pure Kotlin and unit-tested; this class is only the Android skin.
 * It uses the app's own theme resources, so it is the same sheet in light and dark,
 * with the being on or off and the sound on or off.
 */
class PasteSheet(
    private val act: AppCompatActivity,
    private val flow: PasteFlow,
) {
    private var dialog: Dialog? = null

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(act, id)

    fun show() {
        if (dialog?.isShowing == true) return
        // The read is the first state. It cannot send: PasteFlow has no send path from here.
        flow.paste()

        val d = Dialog(act, R.style.Theme_HermesVox)
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.hv_bg))
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }

        val header = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(act, null, 0, R.style.TextVox_Title).apply { text = "PASTE" },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(act, null, 0, R.style.HvRowAction).apply {
            text = "✕"
            contentDescription = "Close paste"
            textSize = 20f
            setPadding(dp(12), dp(6), dp(4), dp(6))
            setOnClickListener { d.dismiss() }
        })
        root.addView(header)

        val state = flow.state
        // One line, whatever the answer: what is held, or why there is nothing to send.
        root.addView(TextView(act, null, 0, R.style.HvRowValue).apply {
            text = ClipboardPaste.line(state)
            contentDescription = text
            setPadding(0, dp(10), 0, dp(10))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (state is ClipboardPaste.State.Preview) {
            // A short look at what SEND will carry. The full text is never shown because
            // a paste can be enormous; the line above states the real size and any loss.
            val preview = TextView(act).apply {
                typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(color(R.color.hv_text))
                textSize = 13f
                setLineSpacing(dp(3).toFloat(), 1f)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = ContextCompat.getDrawable(act, R.drawable.bg_input)
                text = state.snippet
                contentDescription = "Paste preview: ${state.snippet}"
                setTextIsSelectable(true)
            }
            // The box hugs the short preview instead of pretending to be a page.
            root.addView(ScrollView(act).apply {
                addView(preview, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // Nothing to preview also means nothing else to say; either way the footer
        // stays on the floor of the sheet.
        root.addView(View(act), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val footer = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        footer.addView(Button(act, null, 0, R.style.HvGhostButton).apply {
            text = if (state is ClipboardPaste.State.Preview) "CANCEL" else "CLOSE"
            setTextColor(color(R.color.hv_text))
            background = ContextCompat.getDrawable(act, R.drawable.bg_button_ghost)
            contentDescription = "Close without sending"
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setOnClickListener { d.dismiss() }
        })
        if (state is ClipboardPaste.State.Preview) {
            footer.addView(Button(act, null, 0, R.style.HvGhostButton).apply {
                text = "SEND"
                setTextColor(color(R.color.hv_bg))
                background = ContextCompat.getDrawable(act, R.drawable.bg_button_primary)
                contentDescription = "Send the pasted text"
                setPadding(dp(22), dp(10), dp(22), dp(10))
                // The only call site of the send action. Dismiss first so the call screen
                // is visible while the turn starts, then send the previewed text.
                setOnClickListener {
                    val sent = flow.send()
                    if (sent) d.dismiss()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { leftMargin = dp(10) })
        }
        root.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        d.setContentView(root)
        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        // Back, ✕ and CANCEL all mean "not sent"; the flow forgets the preview either way.
        d.setOnDismissListener { flow.dismiss(); dialog = null }
        dialog = d
        d.show()
    }
}
