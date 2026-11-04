package com.hermesvox

import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
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

/**
 * The model switcher (`/models`): the gateway's catalogue as a fan-out — provider, then
 * category, then model — on the app's own ground, instead of a stock list of raw strings.
 *
 * A provider with few models opens straight onto them, under their category headers; one
 * with many opens onto its categories first ([ModelChoices.showsCategories]), so no level
 * is a tap that saves nothing. A search across every provider sits on the first level.
 *
 * Loading, failure and an empty catalogue are said inside the sheet, where the person is
 * looking — not as a toast behind it. [load] is called off the UI thread.
 */
class ModelSheet(
    private val act: AppCompatActivity,
    private val load: () -> String,
    /** The app's current model and provider prefs. */
    private val current: () -> Pair<String, String>,
    private val choose: (ModelChoices.Provider, ModelChoices.Model?) -> Unit,
) {
    private sealed class Level {
        object Loading : Level()
        object Failed : Level()
        object Providers : Level()
        data class Categories(val p: ModelChoices.Provider) : Level()
        data class Models(val p: ModelChoices.Provider, val only: ModelChoices.Category?) : Level()
    }

    private var dialog: Dialog? = null
    private var level: Level = Level.Loading
    private var providers: List<ModelChoices.Provider> = emptyList()
    private var query = ""
    private lateinit var title: TextView
    private var backMark: View? = null
    private lateinit var crumb: TextView
    private lateinit var search: EditText
    private lateinit var list: LinearLayout
    private val main = Handler(Looper.getMainLooper())

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(act, id)

    fun show() {
        if (dialog?.isShowing == true) return
        val d = VoxSheet.dialog(act)
        val root = VoxSheet.root(act)
        val head = VoxSheet.header(root, "MODELS", "Close models", { d.dismiss() }, back = "Back" to { up(); Unit })
        title = head.title
        backMark = head.back
        crumb = TextView(act, null, 0, R.style.HvRowValue).apply { setPadding(0, dp(6), 0, 0) }
        root.addView(crumb)
        search = EditText(act).apply {
            hint = "Find a model"
            setSingleLine()
            background = ContextCompat.getDrawable(act, R.drawable.bg_input)
            setTextColor(color(R.color.hv_text)); setHintTextColor(color(R.color.hv_text_dim))
            setPadding(dp(14), dp(10), dp(14), dp(10))
            contentDescription = "Find a model by name"
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) { query = s?.toString().orEmpty(); render(false) }
            })
        }
        root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(12) })
        list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(act).apply { addView(list); isFocusable = false },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        d.setContentView(root)
        d.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
        VoxSheet.keys(d) { up() }
        d.setOnDismissListener { dialog = null; main.removeCallbacksAndMessages(null) }
        dialog = d
        d.show()
        root.isFocusableInTouchMode = true
        root.requestFocus()
        fetch()
    }

    private fun fetch() {
        level = Level.Loading; render(false)
        val (m, p) = current()
        Thread {
            val parsed = try { ModelChoices.parse(load(), m, p) } catch (e: Throwable) {
                VoxLog.w("event=models-catalog-failed err=${e.message?.take(120)}"); null
            }
            main.post {
                if (dialog == null) return@post
                if (parsed == null) { level = Level.Failed; render(false); return@post }
                providers = parsed
                level = Level.Providers
                render(false)
            }
        }.start()
    }

    /** One step back inside the sheet; false when already at the first level. */
    private fun up(): Boolean {
        val next = when (val l = level) {
            is Level.Models -> if (l.only != null) Level.Categories(l.p) else Level.Providers
            is Level.Categories -> Level.Providers
            else -> return false
        }
        level = next; render(true, deeper = false)
        return true
    }

    private fun open(next: Level) { level = next; render(true, deeper = true) }

    private fun render(moved: Boolean, deeper: Boolean = true) {
        if (dialog == null) return
        list.removeAllViews()
        val l = level
        search.visibility = if (l == Level.Providers) View.VISIBLE else View.GONE
        // The way back is shown only where there is somewhere to go back to.
        backMark?.visibility = if (l is Level.Categories || l is Level.Models) View.VISIBLE else View.GONE
        title.text = when (l) {
            is Level.Categories -> l.p.name.uppercase()
            is Level.Models -> l.p.name.uppercase()
            else -> "MODELS"
        }
        crumb.text = when (l) {
            is Level.Models -> l.only?.let { "${it.label} — ${it.blurb}" } ?: ModelChoices.providerLine(l.p)
            is Level.Categories -> ModelChoices.providerLine(l.p)
            else -> "Switching starts a fresh thread"
        }
        when (l) {
            Level.Loading -> list.addView(note("Reading the gateway's models…"))
            Level.Failed -> {
                list.addView(note("The gateway did not send its models. Check the connection, then try again."))
                list.addView(row("Try again", null, null, drill = false) { fetch() })
            }
            Level.Providers -> providersLevel()
            is Level.Categories -> for ((c, ms) in ModelChoices.byCategory(l.p)) {
                list.addView(row(c.label, "${c.blurb} · ${ms.size}", if (ms.any { it.current }) "in use" else null, drill = true) {
                    open(Level.Models(l.p, c))
                })
            }
            is Level.Models -> modelsLevel(l.p, l.only)
        }
        if (moved) VoxSheet.enter(list, deeper)
    }

    private fun providersLevel() {
        if (query.isNotBlank()) {
            val hits = ModelChoices.search(providers, query)
            list.addView(VoxSheet.subhead(act, if (hits.isEmpty()) "No model by that name" else "Matches"))
            for ((p, m) in hits) list.addView(modelRow(p, m, withProvider = true))
            return
        }
        if (providers.isEmpty()) { list.addView(note("The gateway lists no models.")); return }
        for (p in providers) {
            val drill = p.models.isNotEmpty()
            // A provider with nothing to choose and no sign-in on the gateway is said, not
            // offered: choosing it would route every turn to a provider that cannot answer.
            if (!drill && p.signedIn == false) {
                list.addView(row(p.name, ModelChoices.providerLine(p), null, drill = false, enabled = false) {})
                continue
            }
            list.addView(row(p.name, ModelChoices.providerLine(p), if (p.current) "in use" else null, drill) {
                when {
                    !drill -> pick(p, null)   // the gateway lists no models: choose the provider itself
                    ModelChoices.showsCategories(p) -> open(Level.Categories(p))
                    else -> open(Level.Models(p, null))
                }
            })
            if (p.warning.isNotBlank()) list.addView(note(p.warning, warn = true))
        }
    }

    private fun modelsLevel(p: ModelChoices.Provider, only: ModelChoices.Category?) {
        val groups = ModelChoices.byCategory(p).filter { only == null || it.first == only }
        for ((c, ms) in groups) {
            if (only == null && groups.size > 1) list.addView(VoxSheet.subhead(act, c.label))
            for (m in ms) list.addView(modelRow(p, m, withProvider = false))
        }
    }

    private fun modelRow(p: ModelChoices.Provider, m: ModelChoices.Model, withProvider: Boolean): View {
        val detail = ModelChoices.detail(m, withCategory = withProvider)
        val line = if (withProvider) listOf(p.name, detail).filter { it.isNotBlank() }.joinToString(" · ") else detail
        return row(m.name, line.ifBlank { null }, if (m.current) "in use" else null, drill = false) { pick(p, m) }
    }

    private fun pick(p: ModelChoices.Provider, m: ModelChoices.Model?) {
        dialog?.dismiss()
        choose(p, m)
    }

    private fun note(text: String, warn: Boolean = false) = TextView(act, null, 0, R.style.HvRowValue).apply {
        this.text = text
        if (warn) setTextColor(color(R.color.hv_warn))
        setPadding(dp(4), dp(10), dp(4), dp(10))
    }

    /** A row in the commands sheet's grammar: title, one optional line, an optional state
     *  chip, and "›" when the tap goes one level deeper rather than choosing. */
    private fun row(title: String, line: String?, chip: String?, drill: Boolean,
                    enabled: Boolean = true, onTap: () -> Unit): View {
        val r = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true
            background = ContextCompat.getDrawable(act, R.drawable.bg_command_row)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            minimumHeight = dp(56)
            if (enabled) setOnClickListener { onTap() } else { isClickable = false; isFocusable = false; alpha = 0.55f }
            // One announcement per row, in reading order; "›" is said as "opens".
            contentDescription = listOfNotNull(title, line, chip, if (drill) "opens" else null).joinToString(", ")
        }
        val text = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        text.addView(TextView(act, null, 0, R.style.HvRowTitle).apply { this.text = title; maxLines = 2 })
        if (line != null) text.addView(TextView(act, null, 0, R.style.HvRowValue).apply { this.text = line })
        r.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (chip != null) r.addView(TextView(act, null, 0, R.style.HvRowAction).apply {
            this.text = chip
            background = ContextCompat.getDrawable(act, R.drawable.bg_chip)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { marginStart = dp(8) })
        if (drill) r.addView(TextView(act, null, 0, R.style.HvRowTitle).apply {
            this.text = "›"; setTextColor(color(R.color.hv_text_dim)); textSize = 22f
            setPadding(dp(10), 0, 0, 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        return LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(3), 0, dp(3))
            addView(r)
        }
    }
}
