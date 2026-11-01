package com.hermesvox

import android.app.Dialog
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat

/**
 * The commands screen (#226): the app's own components on the app's own ground — the OLED
 * black and the authored light page alike — grouped by what each command does, every row
 * stating its effect and, when it has one, its cost or risk, with a running state where the
 * command was invoked.
 *
 * An inline command runs in place and the sheet stays open so its outcome can be read; a
 * card command (a chooser, a status card) closes the sheet and opens its own view, so its row
 * says [CommandCatalog.IN_CARD] instead of faking a spinner. Inline commands run one at a
 * time; a second tap while one runs is queued, not dropped.
 *
 * The state is words, never motion or a percentage — the same rule as the operations line
 * (#281), which owns what "still working" means. [CommandCatalog.stateText] is the grammar.
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
    private val startedAt = HashMap<String, Long>()
    private val chips = HashMap<String, TextView>()
    private val queue = ArrayDeque<CommandCatalog.Command>()
    private var running: CommandCatalog.Command? = null
    private val handler = Handler(Looper.getMainLooper())

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(act, id)
    private fun drawable(id: Int) = ContextCompat.getDrawable(act, id)

    fun show() {
        if (dialog?.isShowing == true) return
        // What you were doing before the sheet: closing it must put you back there (#226).
        val cameFrom = act.currentFocus
        // A finished command's outcome belongs to that visit; the next opening starts clean.
        // A command still running stays "running" — it really is.
        state.entries.removeIf { it.value != CommandCatalog.RunState.RUNNING }
        val d = Dialog(act, R.style.Theme_HermesVox)
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.hv_bg))
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        val header = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(act, null, 0, R.style.TextVox_Title).apply {
            text = "COMMANDS"
            ViewCompat.setAccessibilityHeading(this, true)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(TextView(act, null, 0, R.style.HvRowAction).apply {
            text = "✕"; contentDescription = "Close commands"; textSize = 20f
            isFocusable = true; isClickable = true
            setPadding(dp(12), dp(6), dp(4), dp(6))
            setOnClickListener { d.dismiss() }
        })
        root.addView(header)
        // #284: a search that teaches — find a command by what you want to do.
        val search = EditText(act).apply {
            hint = "What do you want to do?"
            setSingleLine()
            background = drawable(R.drawable.bg_input)
            setTextColor(color(R.color.hv_text)); setHintTextColor(color(R.color.hv_text_dim))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            contentDescription = "Search commands by what you want to do"
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(12) })
        val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        render(list, d, "")
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { render(list, d, s?.toString().orEmpty()) }
        })
        val scroller = ScrollView(act).apply { addView(list); isFocusable = false }
        root.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        d.setContentView(root)
        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        // Keyboard: Escape and Back both close, and the sheet opens with focus on the sheet
        // itself — not in the search box, which would raise the IME over the list.
        d.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_BACK)) {
                d.dismiss(); true
            } else false
        }
        search.setOnEditorActionListener { _, _, _ -> focusFirstRow(list); true }
        d.setOnDismissListener {
            handler.removeCallbacksAndMessages(null)
            // A queued command belongs to the visit that queued it: closing the sheet must not
            // run commands nobody is looking at.
            queue.clear()
            chips.clear(); dialog = null
            val back = cameFrom?.takeIf { it.isAttachedToWindow && it.isShown }
                ?: act.findViewById<View>(R.id.commands)
            back?.requestFocus()
        }
        dialog = d
        d.show()
        root.isFocusableInTouchMode = true
        root.requestFocus()
    }

    /** The first row, so a keyboard user lands on the list, not in the text field. */
    private fun focusFirstRow(list: LinearLayout) {
        for (i in 0 until list.childCount) {
            val v = list.getChildAt(i)
            if (v.isFocusable && v.isShown) { v.requestFocus(); return }
            if (v is ViewGroup) for (j in 0 until v.childCount) {
                val c = v.getChildAt(j)
                if (c.isFocusable && c.isShown) { c.requestFocus(); return }
            }
        }
    }

    private fun header(text: String) = TextView(act, null, 0, R.style.TextVox_Tag).apply {
        this.text = text.uppercase()
        setPadding(0, dp(18), 0, dp(6))
        // A screen reader can jump between groups instead of walking every row.
        ViewCompat.setAccessibilityHeading(this, true)
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
        val r = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true; isFocusable = true
            background = drawable(R.drawable.bg_command_row)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val top = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(act, null, 0, R.style.HvRowTitle).apply {
            text = c.name; typeface = Typeface.MONOSPACE
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // The state slot. For a command that cannot report here, it says where to look.
        val chip = TextView(act, null, 0, R.style.HvRowAction).apply {
            setPadding(dp(10), dp(4), dp(10), dp(4))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        chips[chipKey] = chip
        top.addView(chip)
        r.addView(top)
        r.addView(TextView(act, null, 0, R.style.HvRowValue).apply {
            text = c.effect; setTextColor(color(R.color.hv_text))
            setPadding(0, dp(4), 0, 0)
        })
        // What it spends or risks, only when the command has one. Amber means irreversible
        // (the thing is gone afterwards); a spend the command can undo is quiet, so the
        // colour stays a fact about the command rather than a mood over the whole screen.
        c.cost?.let { cost ->
            r.addView(TextView(act, null, 0, R.style.HvRowValue).apply {
                text = cost.text
                setTextColor(color(if (cost.risk) R.color.hv_warn else R.color.hv_text_dim))
                setPadding(0, dp(2), 0, 0)
            })
        }
        paint(chipKey)
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
        if (running != null) {
            queue.addLast(c)
            set(c.name, CommandCatalog.RunState.QUEUED)
            VoxLog.d("event=command name=${c.name} state=queued behind=${running?.name}")
            return
        }
        start(c)
    }

    private fun start(c: CommandCatalog.Command) {
        running = c
        startedAt[c.name] = SystemClock.elapsedRealtime()
        set(c.name, CommandCatalog.RunState.RUNNING)
        VoxLog.d("event=command name=${c.name} state=running")
        // #281's first move, at the same moment: the word changes to "still working", and it
        // never claims how far along the command is.
        handler.postDelayed({
            if (running?.name == c.name) { paint(c.name); paint("now:" + c.name) }
        }, Operations.LONG_MS + 100)
        run(c) { ok ->
            act.runOnUiThread {
                startedAt.remove(c.name)
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
        val name = key.removePrefix("now:")
        val c = CommandCatalog.find(name) ?: return
        val s = state[name] ?: CommandCatalog.RunState.IDLE
        val elapsed = if (s == CommandCatalog.RunState.RUNNING)
            SystemClock.elapsedRealtime() - (startedAt[name] ?: SystemClock.elapsedRealtime()) else 0L
        chip.text = CommandCatalog.stateText(c, s, elapsed)
        chip.visibility = when {
            !CommandCatalog.reportsOnRow(c) -> View.VISIBLE   // always says where its state is
            s == CommandCatalog.RunState.IDLE -> View.INVISIBLE
            else -> View.VISIBLE
        }
        // "opens a card" is information about the row, not a live state — nothing to announce.
        if (!CommandCatalog.reportsOnRow(c)) {
            chip.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
            chip.background = null
            // Quiet information, not a state chip: sentence case, smaller, dim.
            chip.isAllCaps = false
            chip.textSize = 12f
            chip.setTextColor(color(R.color.hv_text_dim))
            return
        }
        chip.background = drawable(R.drawable.bg_chip)
        chip.setTextColor(when (s) {
            CommandCatalog.RunState.DONE -> color(R.color.hv_cmd_done)
            CommandCatalog.RunState.FAILED -> color(R.color.hv_cmd_failed)
            CommandCatalog.RunState.QUEUED -> color(R.color.hv_text_dim)
            else -> color(R.color.hv_cyan)
        })
    }
}
