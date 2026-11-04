package com.hermesvox

import android.app.Dialog
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * ProfileSheet — the profile switcher, in the commands sheet's own body (#174, per-profile pass).
 *
 * It used to be a stock list dialog opened by tapping the agent's name; nothing said the name
 * was tappable, and nothing in it said what a choice would change. Now it is a sheet on
 * [VoxSheet] — the scaffold Alpha, Share and File already stand on — reached from
 * **Profiles** in the commands sheet, where a person goes to change the agent.
 *
 * Each row is the agent you would be talking to: its name, and under it what it is — the agent
 * name, the model it thinks with and the voice it speaks in ([ProfileScope.detail]) — so the
 * choice is informed without opening Settings. The active one carries the same **IN USE** chip
 * the model switcher uses, and the rows are the same row grammar (title, one line, a chip).
 *
 * Adding is a row of its own, not a fake list item: a profile is a real object here, and the
 * management rows (add, edit, refresh) sit under their own header so they cannot be mistaken
 * for a profile.
 *
 * Switching is the caller's job ([ProfileDialogs] → `MainActivity.switchProfile`): a switch
 * ends a live call before the new session is built, and this sheet closes on the tap.
 */
class ProfileSheet(
    private val act: AppCompatActivity,
    /** The rows to draw, in registry order. Re-read on [refresh] so a late answer lands here. */
    private val rows: () -> List<Row>,
    /** The one line under the title: freshness, a partial refresh or a stated failure. */
    private val note: () -> String?,
    private val onSwitch: (Profiles.Profile) -> Unit,
    private val onAdd: () -> Unit,
    private val onEdit: () -> Unit,
    private val onRefresh: () -> Unit,
) {
    /** One profile as the sheet shows it. */
    data class Row(
        val profile: Profiles.Profile,
        val active: Boolean,
        /** Saved, but this host did not prove it: kept, marked, still switchable. */
        val unavailable: Boolean = false,
    )

    private var dialog: Dialog? = null
    private var list: LinearLayout? = null
    private var subtitle: TextView? = null

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(act, id)
    private fun drawable(id: Int) = ContextCompat.getDrawable(act, id)

    fun isShowing(): Boolean = dialog?.isShowing == true

    fun show() {
        if (dialog?.isShowing == true) { refresh(); return }
        val d = VoxSheet.dialog(act)
        val root = VoxSheet.root(act)
        VoxSheet.header(root, "PROFILES", "Close profiles", { d.dismiss() })
        val sub = TextView(act, null, 0, R.style.HvRowValue).apply {
            setPadding(0, dp(6), 0, 0)
            setTextColor(color(R.color.hv_text_dim))
        }
        root.addView(sub)
        subtitle = sub
        val body = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(act).apply { addView(body); isFocusable = false },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        d.setContentView(root)
        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        VoxSheet.keys(d)
        d.setOnDismissListener { dialog = null; list = null; subtitle = null }
        dialog = d
        list = body
        paint()
        d.show()
        root.isFocusableInTouchMode = true
        root.requestFocus()
    }

    /** Redraw in place — a profile answer that lands while the sheet is open repaints it. */
    fun refresh() { if (isShowing()) paint() }

    fun dismiss() { dialog?.dismiss() }

    private fun paint() {
        val body = list ?: return
        body.removeAllViews()
        subtitle?.text = note() ?: DEFAULT_NOTE

        for (r in rows()) body.addView(profileRow(r))
        body.addView(VoxSheet.subhead(act, "Manage"))
        body.addView(actionRow("Add a profile", "One this host serves, or an address you type") {
            dismiss(); onAdd()
        })
        body.addView(actionRow("Edit a profile", "Change its name, address, key or scope") {
            dismiss(); onEdit()
        })
        // The refresh keeps the sheet open: its answer is folded in and repainted here, where the
        // person is looking, instead of behind a closed dialog.
        body.addView(actionRow("Refresh from host", "Ask the agent which profiles it serves", close = false) {
            onRefresh()
        })
    }

    private fun profileRow(r: ProfileSheet.Row): View {
        val p = r.profile
        val detail = listOf(ProfileScope.detail(p), if (r.unavailable) "unavailable" else "")
            .filter { it.isNotBlank() }.joinToString(" · ")
        return row(
            title = p.name,
            detail = detail,
            chip = if (r.active) "in use" else null,
            // One announcement per row, in reading order: the tab stops nowhere invisible.
            description = listOf(
                p.name, detail, if (r.active) "in use" else null,
                if (r.active) "the profile you are talking to" else "switch to this profile",
            ).filterNotNull().joinToString(", "),
            dim = r.unavailable,
        ) {
            if (r.active) { dismiss(); return@row }
            dismiss()
            onSwitch(p)
        }
    }

    private fun actionRow(title: String, line: String, close: Boolean = true, onTap: () -> Unit): View =
        row(title = title, detail = line, chip = null, description = "$title, $line", dim = false,
            onTap = { if (close) dismiss(); onTap() })

    /** One row in the commands/model sheets' grammar: title, one line, an optional chip. */
    private fun row(
        title: String,
        detail: String,
        chip: String?,
        description: String,
        dim: Boolean,
        onTap: () -> Unit,
    ): View {
        val r = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true
            background = drawable(R.drawable.bg_command_row)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            minimumHeight = dp(56)
            contentDescription = description
            if (dim) alpha = 0.6f
            setOnClickListener { onTap() }
        }
        val text = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        text.addView(TextView(act, null, 0, R.style.HvRowTitle).apply {
            this.text = title
            // A profile is a name, not a typed command: bold, not the terminal's monospace.
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
        })
        text.addView(TextView(act, null, 0, R.style.HvRowValue).apply {
            this.text = detail
            setTextColor(color(if (dim) R.color.hv_text_dim else R.color.hv_text))
        })
        r.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (chip != null) r.addView(TextView(act, null, 0, R.style.HvRowAction).apply {
            this.text = chip
            background = drawable(R.drawable.bg_chip)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO   // said in the row's own description
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = dp(8) })
        return LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(3), 0, dp(3))
            addView(r)
        }
    }

    private companion object {
        /** What the sheet says when the host has nothing to report. */
        const val DEFAULT_NOTE = "One at a time — switching starts a fresh conversation"
    }
}
