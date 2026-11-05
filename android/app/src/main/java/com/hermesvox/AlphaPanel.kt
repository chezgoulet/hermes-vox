package com.hermesvox

import android.app.Dialog
import android.content.SharedPreferences
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

/**
 * #402/D29: the Alpha panel — the slide-out that lists the feature sets marked alpha, Presence
 * at the top, each selectable on its own. It opens when the master switch is turned on and from
 * the master row afterwards.
 *
 * It writes only the per-set selections ([AlphaGate.FeatureSet.key]). [advice] is the hardware
 * check's line for a set (#403) — shown, never acted on: the panel advises and the user selects.
 */
class AlphaPanel(
    private val act: AppCompatActivity,
    private val prefs: SharedPreferences,
    private val advice: (AlphaGate.FeatureSet) -> String?,
    private val onChange: () -> Unit,
) {
    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).toInt()
    private fun color(id: Int) = ContextCompat.getColor(act, id)

    private lateinit var list: LinearLayout

    /** Rebuild every row, so a dependency line appears or clears with the switch that caused it. */
    private fun renderRows() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        for (set in AlphaGate.FeatureSet.entries) list.addView(row(set))
    }

    fun show() {
        val d = Dialog(act, R.style.Theme_HermesVox)
        val root = VoxSheet.root(act)
        VoxSheet.header(root, "ALPHA FEATURES", "Close alpha features", { d.dismiss() })
        root.addView(TextView(act, null, 0, R.style.HvRowValue).apply {
            text = "Unfinished features, for testers. Each set stays off until you select it, " +
                "and turning Alpha features off stops all of them."
            setPadding(0, dp(8), 0, dp(12))
        })
        list = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        renderRows()
        root.addView(ScrollView(act).apply { addView(list) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        d.setContentView(root)
        d.window?.apply {
            val w = (act.resources.displayMetrics.widthPixels * 0.88f).toInt()
            setLayout(w, ViewGroup.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.END)
            setWindowAnimations(R.style.HvSlideFromEnd)
        }
        VoxSheet.keys(d)
        d.setOnDismissListener { onChange() }
        d.show()
    }

    private fun row(set: AlphaGate.FeatureSet): LinearLayout {
        val row = LinearLayout(act, null, 0, R.style.HvRow).apply {
            orientation = LinearLayout.HORIZONTAL
            background = ContextCompat.getDrawable(act, R.drawable.bg_surface)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
        }
        val reader = AlphaGate.reader(prefs)
        val text = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        text.addView(TextView(act, null, 0, R.style.HvRowTitle).apply { this.text = set.label })
        text.addView(TextView(act, null, 0, R.style.HvRowValue).apply { this.text = set.blurb })
        advice(set)?.let { line ->
            text.addView(TextView(act, null, 0, R.style.HvRowValue).apply {
                this.text = line
                setTextColor(color(R.color.hv_warn))
                setPadding(0, dp(6), 0, 0)
            })
        }
        // A dependency the switch cannot satisfy is stated here, in one line, before it is tried.
        AlphaGate.dependencyRefusal(reader, set)?.let { why ->
            val inert = AlphaGate.selected(reader, set)
            text.addView(TextView(act, null, 0, R.style.HvRowValue).apply {
                this.text = if (inert) "Inert — $why" else why
                setTextColor(color(R.color.hv_warn))
                setPadding(0, dp(6), 0, 0)
            })
        }
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        var adjusting = false
        row.addView(SwitchCompat(act).apply {
            contentDescription = set.label
            isChecked = AlphaGate.selected(reader, set)
            setOnCheckedChangeListener { button, on ->
                if (adjusting) return@setOnCheckedChangeListener
                // Refuse in the picker, not only in allows(): selecting it without Presence
                // would store a preference that can never run.
                val refusal = if (on) AlphaGate.dependencyRefusal(AlphaGate.reader(prefs), set) else null
                if (refusal != null) {
                    adjusting = true
                    button.isChecked = false
                    adjusting = false
                    renderRows()
                    return@setOnCheckedChangeListener
                }
                prefs.edit().putBoolean(set.key, on).apply()
                renderRows()
                onChange()
            }
        })
        return row
    }
}
