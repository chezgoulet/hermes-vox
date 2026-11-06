package com.hermesvox

import android.app.Dialog
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * The commands screen (#226): the app's own components on the app's own ground — the OLED
 * black and the authored light page alike — grouped by what each command does, every row
 * stating its effect and, when it has one, its cost or risk, with a running state where the
 * command was invoked.
 *
 * An inline command runs in place and the sheet stays open so its outcome can be read; a
 * card command (a chooser, a status card) closes the sheet and opens its own view — its row
 * shows no state at all rather than a spinner it cannot back, and no "opens a card" line
 * either: the tap says where it went. Inline commands run one at a time; a second tap while
 * one runs is queued, not dropped.
 *
 * Each row leads with its [VerbMark] — the still frame of the motion the being makes for
 * that kind of command (#227) — so the screen is themed by what each command does, in the
 * being's own vocabulary, not by decoration. The sheet stands on [VoxSheet].
 *
 * Two groups, one row per command (#499): a command that fits the moment says
 * [CommandCatalog.FITS_NOW] in its own row's state slot, and its row is the only place it
 * appears.
 *
 * The sheet lists the groups and nothing else (#500): there is no search field above them.
 * A person who wants a command reads the group they would act on; the catalogue's keywords
 * stay in [CommandCatalog] for whoever asks it, but the screen is not a filter box.
 *
 * The state is words, never motion or a percentage — the same rule as the operations line
 * (#281), which owns what "still working" means. [CommandCatalog.stateText] is the grammar.
 *
 * [run] executes a command and calls back with whether it succeeded.
 */
class CommandsSheet(
    private val act: AppCompatActivity,
    /** #284: what the app knows about the moment — the row that fits it says so in place. */
    private val situation: () -> CommandCatalog.Situation,
    /** #418: which optional commands exist right now (the sheet and its states ask the same
     *  gate, so a command with its switch off is simply not there). */
    private val gate: CommandCatalog.Gate,
    private val run: (CommandCatalog.Command, (Boolean) -> Unit) -> Unit,
) {
    private var dialog: Dialog? = null
    private val state = HashMap<String, CommandCatalog.RunState>()
    private val startedAt = HashMap<String, Long>()
    private val chips = HashMap<String, TextView>()
    /** The commands that fit the moment, by name — marked on their own rows, never repeated. */
    private val fits = HashSet<String>()
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
        val d = VoxSheet.dialog(act)
        val root = VoxSheet.root(act)
        // The header carries the same entry you tapped: the chosen glyph, drawn live in
        // the theme's ink. The title below it is still the words "COMMANDS", so the sheet
        // says what it is whatever the glyph is called.
        VoxSheet.header(root, "COMMANDS", "Close commands", { d.dismiss() },
            lead = CommandMark(act).apply { icon = CommandGlyph.current(act) })
        // #500: no search field. The groups are the whole navigation, so the list starts
        // where the header ends and nothing raises the keyboard over it.
        val list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        render(list, d)
        val scroller = ScrollView(act).apply { addView(list); isFocusable = false }
        root.addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        d.setContentView(root)
        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        // Keyboard: Escape and Back both close, and the sheet opens with focus on the sheet
        // itself, so a keyboard user can tab straight into the rows.
        VoxSheet.keys(d)
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

    // A screen reader can jump between groups instead of walking every row.
    private fun header(text: String) = VoxSheet.subhead(act, text)

    /** The list: the share row, then every group in screen order. */
    private fun render(list: LinearLayout, d: Dialog) {
        list.removeAllViews(); chips.clear(); fits.clear()
        // #370: the share row leads the screen. It is an artifact action, not a command,
        // so it sits outside the catalogue's groups but uses the same row grammar.
        list.addView(row(CommandCatalog.SHARE, d))
        // #499: what fits the moment is a mark on the row it belongs to. The screen had
        // a "Fits now" section above the groups, so `/reconnect` and `/health` appeared
        // twice — once there and once under The agent. Two groups, one row per command.
        fits += CommandCatalog.suggest(situation(), gate).map { it.name }
        for ((group, cmds) in CommandCatalog.grouped(gate = gate)) {
            list.addView(header(group.label))
            for (c in cmds) list.addView(row(c, d))
        }
    }

    private fun row(c: CommandCatalog.Command, d: Dialog): View {
        val r = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true
            background = drawable(R.drawable.bg_command_row)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            minimumHeight = dp(56)
        }
        // The command's kind, drawn as the being draws it; amber only when something is lost.
        // A command whose own drawing says it better (#3: /new and the share row) keeps the
        // same size, the same tint and the same stroke — only the geometry is its own.
        val tint = color(if (CommandCatalog.tint(c) == CommandCatalog.Tint.RISK) R.color.hv_warn else R.color.hv_cyan)
        val mark: View = c.glyph?.let { GlyphMark(act, Marks.shapes(it), tint) }
            ?: VerbMark(act, CommandMotion.verbFor(c.category), tint)
        r.addView(mark, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(14) })
        val words = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        words.addView(TextView(act, null, 0, R.style.HvRowTitle).apply {
            text = c.name
            // The share row is the one row that is not a typed command: plain type, not the
            // terminal's monospace, so it does not read as `/share`.
            typeface = if (c == CommandCatalog.SHARE) Typeface.DEFAULT_BOLD else Typeface.MONOSPACE
        })
        words.addView(TextView(act, null, 0, R.style.HvRowValue).apply {
            text = c.effect; setTextColor(color(R.color.hv_text))
        })
        // What it loses, only when it loses something. Amber means irreversible (the thing is
        // gone afterwards); a loss the command accepts by design is quiet, so the colour
        // stays a fact about the command rather than a mood over the whole screen.
        c.cost?.let { cost ->
            words.addView(TextView(act, null, 0, R.style.HvRowValue).apply {
                text = cost.text
                setTextColor(color(if (cost.risk) R.color.hv_warn else R.color.hv_text_dim))
            })
        }
        r.addView(words, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // The state slot — a command that runs here reports on it, and a command that fits
        // the moment says so in the same slot: one row, one thing to say about itself.
        if (CommandCatalog.reportsOnRow(c) || c.name in fits) {
            val chip = TextView(act, null, 0, R.style.HvRowAction).apply {
                setPadding(dp(10), dp(4), dp(10), dp(4))
                background = drawable(R.drawable.bg_chip)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            chips[c.name] = chip
            r.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginStart = dp(8) })
            paint(c.name)
        }
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
            if (running?.name == c.name) paint(c.name)
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
        state[name] = s; paint(name)
    }

    private fun paint(name: String) {
        val chip = chips[name] ?: return
        val c = CommandCatalog.find(name, gate) ?: return
        val s = state[name] ?: CommandCatalog.RunState.IDLE
        val elapsed = if (s == CommandCatalog.RunState.RUNNING)
            SystemClock.elapsedRealtime() - (startedAt[name] ?: SystemClock.elapsedRealtime()) else 0L
        // The state slot says one thing: what the command is doing, or — while it is idle —
        // that it fits the moment. Never both, and never a state a card command cannot see.
        val said = CommandCatalog.stateText(c, s, elapsed)
        chip.text = if (s == CommandCatalog.RunState.IDLE && name in fits) CommandCatalog.FITS_NOW else said
        // Nothing to say takes no room from the words beside it.
        chip.visibility = if (chip.text.isEmpty()) View.GONE else View.VISIBLE
        chip.setTextColor(when (s) {
            CommandCatalog.RunState.DONE -> color(R.color.hv_cmd_done)
            CommandCatalog.RunState.FAILED -> color(R.color.hv_cmd_failed)
            CommandCatalog.RunState.QUEUED -> color(R.color.hv_text_dim)
            else -> color(R.color.hv_cyan)
        })
    }
}
