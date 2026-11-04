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
 * ShareSheet — the chooser and the confirmation for #370.
 *
 * It does not send anything. It offers the two sources (the clipboard, any file), then shows
 * **one card** for the staged artifact — name, type, size and where it came from — with a
 * single SEND. A share that arrives from another app lands on that same card, so the rule
 * #327 established holds for every entry point: nothing leaves the phone until the user says
 * so.
 *
 * Once SEND is pressed the draft's ownership moves to [FileShare] and this sheet closes; the
 * offer's own progress is [FileOfferSheet]'s job, unchanged. Closing or backing out deletes
 * the staged copy and tells the activity to discard any copy still being read.
 *
 * Readable as a skin over [ShareSurface] (the naming/refusal rules) and [FileStage] (the
 * copy); both are tested off-device.
 */
class ShareSheet(
    private val act: AppCompatActivity,
    private val onClipboard: () -> Unit,
    private val onPick: () -> Unit,
    private val onSend: (FileStage.Staged) -> Unit,
    /** A draft or an in-flight copy was abandoned: the activity bumps its staging guard. */
    private val onCancel: () -> Unit = {},
) {
    sealed class State {
        data object Choose : State()
        data class Reading(val name: String) : State()
        data class Ready(val staged: FileStage.Staged, val source: ShareSurface.Source) : State()
        data class Refused(val line: String) : State()
    }

    private var dialog: Dialog? = null
    private var state: State = State.Choose

    /** The copy this sheet owns until SEND hands it to [FileShare]. */
    private var staged: FileStage.Staged? = null

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(act, id)
    private fun drawable(id: Int) = ContextCompat.getDrawable(act, id)

    fun isShowing(): Boolean = dialog?.isShowing == true

    /** The chooser: the clipboard, or any file. Nothing has been read yet. */
    fun show() {
        discardStaged()
        state = State.Choose
        paint()
    }

    /** A copy is running. One stated line, never a spinner. */
    fun showReading(name: String) {
        discardStaged()
        state = State.Reading(name)
        paint()
    }

    /** The staged artifact, waiting for confirmation. */
    fun showReady(staged: FileStage.Staged, source: ShareSurface.Source) {
        discardStaged()
        this.staged = staged
        state = State.Ready(staged, source)
        paint()
    }

    /** A stated refusal; the sheet stays usable (TRY AGAIN returns to the chooser). */
    fun refuse(line: String) {
        discardStaged()
        state = State.Refused(line)
        paint()
    }

    fun dismiss() {
        dialog?.dismiss()
    }

    private fun paint() {
        val d = dialog ?: VoxSheet.dialog(act).also { dialog = it }
        d.setContentView(build(state))
        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        if (d.isShowing) return
        d.setOnDismissListener {
            val was = state
            // Closing is never a send. A waiting card deletes its copy; a copy still being
            // read is abandoned through the activity's staging guard.
            if (was is State.Ready || was is State.Reading) {
                discardStaged()
                onCancel()
            }
            dialog = null
        }
        d.show()
    }

    private fun discardStaged() {
        val s = staged ?: return
        staged = null
        try {
            s.file.delete()
            s.file.parentFile?.takeIf { it.list()?.isEmpty() == true }?.delete()
        } catch (_: Throwable) {
        }
    }

    private fun build(state: State): View {
        val root = VoxSheet.root(act)
        VoxSheet.header(root, "SHARE", "Close share", { dialog?.dismiss() })

        when (state) {
            is State.Choose -> {
                root.addView(TextView(act, null, 0, R.style.HvRowValue).apply {
                    text = "send something to the agent · ${ShareSurface.NOTHING_SENT}"
                    contentDescription = text
                    setPadding(0, dp(10), 0, dp(14))
                })
                root.addView(sourceRow(
                    title = "CLIPBOARD",
                    effect = "What you copied, as a file",
                    description = "Share the clipboard as a file",
                ) { onClipboard() }, rowParams())
                root.addView(sourceRow(
                    title = "A FILE",
                    effect = "Pick any file on this device",
                    description = "Choose a file to share",
                ) { onPick() }, rowParams())
            }
            is State.Reading -> {
                root.addView(TextView(act, null, 0, R.style.HvRowValue).apply {
                    text = "reading ${state.name} · ${ShareSurface.NOTHING_SENT}"
                    contentDescription = text
                    setPadding(0, dp(10), 0, dp(10))
                })
            }
            is State.Ready -> {
                val file = state.staged.offer
                // The card: the name is the title, the type and size are the detail. The bytes
                // are never here.
                root.addView(TextView(act, null, 0, R.style.HvRowTitle).apply {
                    text = file.name
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setPadding(0, dp(12), 0, dp(2))
                })
                root.addView(TextView(act, null, 0, R.style.HvRowValue).apply {
                    text = ShareSurface.describe(file)
                    contentDescription = text
                })
                root.addView(TextView(act, null, 0, R.style.HvRowValue).apply {
                    text = "${state.source.label} · ${ShareSurface.NOTHING_SENT}"
                    contentDescription = text
                    alpha = 0.75f
                    setPadding(0, dp(8), 0, 0)
                })
            }
            is State.Refused -> {
                root.addView(TextView(act, null, 0, R.style.HvRowValue).apply {
                    text = state.line
                    contentDescription = text
                    setPadding(0, dp(10), 0, dp(10))
                })
            }
        }

        root.addView(View(act), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        val footer = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        footer.addView(ghost(if (state is State.Ready) "CANCEL" else "CLOSE", "Close without sending") {
            dialog?.dismiss()
        })
        when (state) {
            is State.Ready -> footer.addView(primary("SEND", "Send this file to the agent") {
                val ready = staged ?: return@primary
                // Ownership moves before the dialog closes, so the dismiss path cannot delete
                // a copy the flow has already started using.
                staged = null
                this.state = State.Choose
                onSend(ready)
                dialog?.dismiss()
            })
            is State.Refused -> footer.addView(primary("TRY AGAIN", "Try sharing again") {
                this.state = State.Choose
                paint()
            })
            is State.Choose, is State.Reading -> Unit
        }
        root.addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return root
    }

    private fun sourceRow(
        title: String,
        effect: String,
        description: String,
        onClick: () -> Unit,
    ): View = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        isFocusable = true
        contentDescription = description
        background = drawable(R.drawable.bg_command_row)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        addView(TextView(act, null, 0, R.style.HvRowTitle).apply {
            text = title
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        addView(TextView(act, null, 0, R.style.HvRowValue).apply {
            text = effect
            setTextColor(color(R.color.hv_text))
            setPadding(0, dp(4), 0, 0)
        })
        setOnClickListener { onClick() }
    }

    private fun rowParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = dp(8) }

    private fun ghost(text: String, description: String, onClick: () -> Unit): Button =
        Button(act, null, 0, R.style.HvGhostButton).apply {
            this.text = text
            setTextColor(color(R.color.hv_text))
            background = ContextCompat.getDrawable(act, R.drawable.bg_button_ghost)
            contentDescription = description
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setOnClickListener { onClick() }
        }

    private fun primary(text: String, description: String, onClick: () -> Unit): Button =
        Button(act, null, 0, R.style.HvGhostButton).apply {
            this.text = text
            setTextColor(color(R.color.hv_bg))
            background = ContextCompat.getDrawable(act, R.drawable.bg_button_primary)
            contentDescription = description
            setPadding(dp(22), dp(10), dp(22), dp(10))
            setOnClickListener { onClick() }
        }.also { b ->
            b.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(10) }
        }
}
