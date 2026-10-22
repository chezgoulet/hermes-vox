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
 * The commands screen (#226): the app's own components on the app's own background, grouped
 * by what each command does, every row saying its effect in plain language, and a running
 * state on the row — queued, running, done, failed — instead of a toast.
 *
 * An inline command runs in place and the sheet stays open so its outcome can be read; a card
 * command (a chooser, a status card) closes the sheet and opens its own view. Commands run one
 * at a time; a second tap while one runs is queued, not dropped.
 *
 * [run] executes a command and calls back with whether it succeeded.
 */
class CommandsSheet(
    private val act: AppCompatActivity,
    /** #284: what the app knows about the moment — the screen leads with what fits it. */
    private val situation: () -> CommandCatalog.Situation,
    private val run: (CommandCatalog.Command, (Boolean) -> Unit) -> Unit,
) {
    private var dialog: Dialog? = null
    private val state = HashMap<String, CommandCatalog.RunState>()
    private val chips = HashMap<String, TextView>()
    private val queue = ArrayDeque<CommandCatalog.Command>()
    private var running: CommandCatalog.Command? = null

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(act, id)

    fun show() {
        if (dialog?.isShowing == true) return
        // A finished command's outcome belongs to that visit; the next opening starts clean.
        state.entries.removeIf { it.value == CommandCatalog.RunState.DONE || it.value == CommandCatalog.RunState.FAILED }
        val d = Dialog(act, R.style.Theme_HermesVox)
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.hv_bg))
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val header = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(act, null, 0, R.style.TextVox_Title).apply { text = "COMMANDS" },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(act, null, 0, R.style.HvRowAction).apply {
            text = "✕"; contentDescription = "Close commands"; textSize = 20f
            setPadding(dp(12), dp(6), dp(4), dp(6))
            setOnClickListener { d.dismiss() }
        })
        root.addView(header)
        // #284: a search that teaches — find a command by what you want to do.
        val search = android.widget.EditText(act).apply {
            hint = "What do you want to do?"
            setSingleLine()
            background = ContextCompat.getDrawable(act, R.drawable.bg_input)
            setTextColor(color(R.color.hv_text)); setHintTextColor(color(R.color.hv_text_dim))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            contentDescription = "Search commands by what you want to do"
        }
        root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(12) })
        val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        render(list, d, "")
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { render(list, d, s?.toString().orEmpty()) }
        })
        root.addView(ScrollView(act).apply { addView(list) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        d.setContentView(root)
        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        d.setOnDismissListener { chips.clear(); dialog = null }
        dialog = d
        d.show()
    }

    private fun header(text: String) = TextView(act, null, 0, R.style.TextVox_Tag).apply {
        this.text = text.uppercase()
        setPadding(0, dp(18), 0, dp(6))
    }

    /** The list: what fits now, then every group — or, while searching, the matches best first. */
    private fun render(list: LinearLayout, d: Dialog, query: String) {
        list.removeAllViews(); chips.clear()
        if (query.isBlank()) {
            val now = CommandCatalog.suggest(situation())
            if (now.isNotEmpty()) {
                list.addView(header("Fits now"))
                for (c in now) list.addView(row(c, d, chipKey = "now:" + c.name))
            }
            for ((group, cmds) in CommandCatalog.grouped()) {
                list.addView(header(group.label))
                for (c in cmds) list.addView(row(c, d))
            }
            return
        }
        val hits = CommandCatalog.search(query)
        list.addView(header(if (hits.isEmpty()) "No command for that — try asking the agent" else "Matches"))
        for (c in hits) list.addView(row(c, d))
    }

    private fun row(c: CommandCatalog.Command, d: Dialog, chipKey: String = c.name): View {
        val r = LinearLayout(act, null, 0, R.style.HvRow).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true
            background = ContextCompat.getDrawable(act, R.drawable.bg_surface)
        }
        val text = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        text.addView(TextView(act, null, 0, R.style.HvRowTitle).apply {
            this.text = c.name; typeface = Typeface.MONOSPACE
        })
        text.addView(TextView(act, null, 0, R.style.HvRowValue).apply { this.text = c.effect })
        r.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val chip = TextView(act, null, 0, R.style.HvRowAction).apply {
            background = ContextCompat.getDrawable(act, R.drawable.bg_chip)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        chips[chipKey] = chip
        paint(chipKey)
        r.addView(chip)
        r.contentDescription = "${c.name}. ${c.effect}"
        r.setOnClickListener { tap(c, d) }
        return LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(3), 0, dp(3))
            addView(r)
        }
    }

    private fun tap(c: CommandCatalog.Command, d: Dialog) {
        if (c.kind == CommandCatalog.Kind.CARD) { d.dismiss(); run(c) {}; return }
        if (running?.name == c.name || queue.any { it.name == c.name }) return
        if (running != null) { queue.addLast(c); set(c.name, CommandCatalog.RunState.QUEUED); return }
        start(c)
    }

    private fun start(c: CommandCatalog.Command) {
        running = c
        set(c.name, CommandCatalog.RunState.RUNNING)
        VoxLog.d("event=command name=${c.name} state=running")
        run(c) { ok ->
            act.runOnUiThread {
                set(c.name, if (ok) CommandCatalog.RunState.DONE else CommandCatalog.RunState.FAILED)
                VoxLog.d("event=command name=${c.name} state=${if (ok) "done" else "failed"}")
                running = null
                queue.removeFirstOrNull()?.let { start(it) }
            }
        }
    }

    private fun set(name: String, s: CommandCatalog.RunState) {
        state[name] = s; paint(name); paint("now:$name")
    }

    private fun paint(key: String) {
        val chip = chips[key] ?: return
        val s = state[key.removePrefix("now:")] ?: CommandCatalog.RunState.IDLE
        chip.text = s.label
        chip.visibility = if (s == CommandCatalog.RunState.IDLE) View.INVISIBLE else View.VISIBLE
        chip.setTextColor(when (s) {
            CommandCatalog.RunState.DONE -> 0xFF35D07F.toInt()
            CommandCatalog.RunState.FAILED -> 0xFFFF5B5B.toInt()
            else -> color(R.color.hv_cyan)
        })
    }
}
