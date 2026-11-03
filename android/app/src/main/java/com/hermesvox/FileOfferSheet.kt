package com.hermesvox

import android.app.Dialog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * FileOfferSheet — the file half's only screen (SPEC-conversational-bridge §B).
 *
 * It says one thing at a time, in the app's existing voice:
 *
 *  * `sharing meeting-notes.md · waiting for the agent to collect it`
 *  * `collected · meeting-notes.md (8.1 KB) · ~/.hermes/inbox/meeting-notes.md`
 *  * `the agent did not collect the file · tap to try again`
 *  * `the agent received a different file than we sent · nothing was kept`
 *
 * The decisions are in [FileOffer] and [FileShare]; this class is the Android skin, like
 * [ShareSheet]. It never sends anything itself: the only calls it makes are the picker and
 * [FileShare]'s actions, and every line it shows came from a state the flow produced.
 */
class FileOfferSheet(
    private val act: AppCompatActivity,
    private val share: FileShare,
    private val onPick: () -> Unit,
) {
    private var dialog: Dialog? = null
    private var open = false

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(act, id)

    fun show() {
        open = true
        paint(share.state)
    }

    /** Repaint for a new state. Ignored when the sheet is closed (the state is re-read on show). */
    fun render(state: FileShare.State) {
        if (!open) return
        paint(state)
    }

    private fun paint(state: FileShare.State) {
        val d = dialog ?: Dialog(act, R.style.Theme_HermesVox).also { dialog = it }
        d.setContentView(build(state))
        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        if (d.isShowing) return
        d.setOnDismissListener {
            // Closing is never a send. An offer that is still running is cancelled here,
            // which stops the server and deletes the staged copy; a copy still being read is
            // cancelled too, and its result is discarded (MainActivity's staging guard).
            open = false
            dialog = null
            if (share.state is FileShare.State.Offering ||
                share.state is FileShare.State.Unreachable ||
                share.state is FileShare.State.Reading
            ) {
                share.cancel()
            }
        }
        d.show()
    }

    private fun build(state: FileShare.State): View {
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.hv_bg))
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val header = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(TextView(act, null, 0, R.style.TextVox_Title).apply { text = "FILE" },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(act, null, 0, R.style.HvRowAction).apply {
            text = "✕"
            contentDescription = "Close file offer"
            textSize = 20f
            setPadding(dp(12), dp(6), dp(4), dp(6))
            setOnClickListener { dialog?.dismiss() }
        })
        root.addView(header)

        root.addView(TextView(act, null, 0, R.style.HvRowValue).apply {
            text = line(state)
            contentDescription = text
            setPadding(0, dp(10), 0, dp(10))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (state is FileShare.State.Offering) {
            root.addView(TextView(act, null, 0, R.style.HvRowValue).apply {
                text = "one fetch, then the link dies · nothing was uploaded to the gateway"
                contentDescription = text
                alpha = 0.7f
                setPadding(0, 0, 0, dp(10))
            })
        }

        root.addView(View(act), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val footer = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        footer.addView(Button(act, null, 0, R.style.HvGhostButton).apply {
            text = if (state is FileShare.State.Offering) "CANCEL" else "CLOSE"
            setTextColor(color(R.color.hv_text))
            background = ContextCompat.getDrawable(act, R.drawable.bg_button_ghost)
            contentDescription = "Close without sending"
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setOnClickListener { dialog?.dismiss() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val primary = when (state) {
            is FileShare.State.Unreachable -> if (state.inlineTurn != null) "SEND INLINE" else null
            is FileShare.State.Idle, is FileShare.State.Expired,
            is FileShare.State.Refused, is FileShare.State.Collected -> "PICK A FILE"
            is FileShare.State.Offering, is FileShare.State.Inline,
            is FileShare.State.Mismatch, is FileShare.State.Reading -> null
        }
        if (primary != null) {
            footer.addView(Button(act, null, 0, R.style.HvGhostButton).apply {
                text = primary
                setTextColor(color(R.color.hv_bg))
                background = ContextCompat.getDrawable(act, R.drawable.bg_button_primary)
                contentDescription = if (primary == "SEND INLINE") "Send the file inline" else "Pick a file"
                setPadding(dp(22), dp(10), dp(22), dp(10))
                setOnClickListener {
                    if (primary == "SEND INLINE") share.sendInline()
                    else {
                        // The picker is the activity's job; a pick REPLACES this state.
                        onPick()
                    }
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { leftMargin = dp(10) })
        }
        root.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return root
    }

    /** One line, always. There is no spinner and no empty state. */
    private fun line(state: FileShare.State): String = when (state) {
        is FileShare.State.Idle ->
            "offer a file to the agent · it fetches from this phone, nothing is uploaded"
        is FileShare.State.Reading -> state.line
        is FileShare.State.Offering -> state.line
        is FileShare.State.Inline -> state.line
        is FileShare.State.Collected -> state.line
        is FileShare.State.Expired -> state.line
        is FileShare.State.Mismatch -> state.line
        is FileShare.State.Refused -> state.line
        is FileShare.State.Unreachable -> state.line
    }
}
