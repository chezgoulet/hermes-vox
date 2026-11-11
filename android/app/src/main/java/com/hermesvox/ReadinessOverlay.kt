package com.hermesvox

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat

/**
 * The call readiness gate's surface (see [CallReadiness]): the being assembling itself over
 * the call screen while the four legs report.
 *
 * It is the app's own being — an [AvatarView] with the user's look — not a spinner: its
 * particles start as a sparse nebula and converge as legs arrive ([CallReadiness.assembly]:
 * density rises, the constellation resolves, and at green it takes the listening shape). Each
 * completed leg adds one structural element around it ([Marks]): a crown for the mind, two
 * side arcs for the ears, an arc beneath for the voice, and a full ring for the rest of it.
 * One short line at a time, in the being's own voice.
 *
 * Frame budget: only one being renders. The host pauses the main screen's being while this is
 * up, so this AvatarView's renderer is the one GL pipeline running and its own
 * `being: frame … over budget -> quality N` step-down applies unchanged. [Marks] is a Canvas
 * view that redraws only when a leg changes and during a short fade, never per frame.
 */
class ReadinessOverlay(context: Context) : FrameLayout(context) {

    val being = AvatarView(context)
    private val marks = Marks(context)
    private val line = TextView(context)
    private val detail = TextView(context)
    val talkAnyway = TextView(context)
    val close = TextView(context)
    private var lastView: CallReadiness.View? = null
    private var userDensity = VisualStyle.DEFAULT_DENSITY

    init {
        val d = resources.displayMetrics.density
        setBackgroundColor(ContextCompat.getColor(context, R.color.hv_bg))
        isClickable = true; isFocusable = true   // the call screen underneath takes no touches
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        addView(being, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(marks, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((28 * d).toInt(), 0, (28 * d).toInt(), (56 * d).toInt())
        }
        line.apply {
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.hv_text))
            letterSpacing = 0.02f
        }
        detail.apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.hv_text_dim))
            setPadding(0, (8 * d).toInt(), 0, 0)
            visibility = View.GONE
        }
        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, (20 * d).toInt(), 0, 0)
        }
        for ((b, label, color) in listOf(
            Triple(talkAnyway, "Talk anyway", R.color.hv_cyan),
            Triple(close, "Close", R.color.hv_text_dim))) {
            b.text = label
            b.textSize = 15f
            b.isAllCaps = true
            b.letterSpacing = 0.08f
            b.setTextColor(ContextCompat.getColor(context, color))
            b.setPadding((18 * d).toInt(), (14 * d).toInt(), (18 * d).toInt(), (14 * d).toInt())
            b.minHeight = (48 * d).toInt()   // a real touch target
            b.isClickable = true; b.isFocusable = true
            b.visibility = View.GONE
            buttons.addView(b)
        }
        bottom.addView(line); bottom.addView(detail); bottom.addView(buttons)
        addView(bottom, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
    }

    /** The user's look, then the gate's own starting point: the being begins as the diffuse
     *  nebula (no cycling through idle themes while it assembles). */
    fun applyLook(prefs: android.content.SharedPreferences, light: Boolean) {
        being.applyLookPrefs(prefs)
        being.setLightSurface(light, ContextCompat.getColor(context, R.color.hv_bg))
        being.setCycleThemes(false)
        being.setIdleTheme("aura")
        userDensity = VisualStyle.density(prefs.getFloat(VisualStyle.KEY_DENSITY, VisualStyle.DEFAULT_DENSITY))
    }

    fun render(v: CallReadiness.View, waitedMs: Long) {
        val a = CallReadiness.assembly(v)
        being.setPresent(a.state, a.tool, a.workload, if (v.phase == CallReadiness.Phase.GREEN) 0.35f else 0.1f)
        being.setVisualDensity(userDensity * a.density)
        being.setStall(a.stalled, waitedMs)
        line.text = v.line
        line.setTextColor(ContextCompat.getColor(context, when (v.phase) {
            CallReadiness.Phase.GREEN -> R.color.hv_ok
            CallReadiness.Phase.FAILED -> R.color.hv_warn
            else -> R.color.hv_text
        }))
        // The second line only when there is something to act on: what Talk anyway leaves out.
        val note = if (v.talkAnyway && v.missing.isNotBlank()) "Talk anyway starts without it: ${v.missing}" else ""
        detail.text = note
        detail.visibility = if (note.isBlank()) View.GONE else View.VISIBLE
        talkAnyway.visibility = if (v.talkAnyway) View.VISIBLE else View.GONE
        close.visibility = if (v.phase == CallReadiness.Phase.FAILED || v.phase == CallReadiness.Phase.STALLED)
            View.VISIBLE else View.GONE
        if (lastView?.ready != v.ready || lastView?.phase != v.phase || lastView?.legs != v.legs) marks.show(v)
        lastView = v
    }

    /**
     * The structural elements: one per completed leg, drawn around the being. Absent legs (Presence
     * off) are never drawn. A newly completed element fades in over [FADE_MS]; otherwise the view
     * is static and costs nothing per frame.
     */
    private class Marks(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val oval = RectF()
        private var ready: Set<CallReadiness.Leg> = emptySet()
        private var fresh: Set<CallReadiness.Leg> = emptySet()
        private var phase = CallReadiness.Phase.ASSEMBLING
        private var fadeStart = 0L
        private val accent = ContextCompat.getColor(context, R.color.hv_cyan)
        private val green = ContextCompat.getColor(context, R.color.hv_ok)

        fun show(v: CallReadiness.View) {
            fresh = v.ready - ready
            ready = v.ready
            phase = v.phase
            fadeStart = android.os.SystemClock.uptimeMillis()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (ready.isEmpty()) return
            val d = resources.displayMetrics.density
            val cx = width / 2f; val cy = height / 2f
            // AvatarView's body radius is 0.32 of the short side; the elements sit just outside it.
            val r = minOf(width, height) * 0.40f
            val fade = ((android.os.SystemClock.uptimeMillis() - fadeStart) / FADE_MS.toFloat()).coerceIn(0f, 1f)
            paint.color = if (phase == CallReadiness.Phase.GREEN) green else accent
            paint.strokeWidth = 2f * d
            for (leg in ready) {
                paint.alpha = ((if (leg in fresh) fade else 1f) * 200).toInt()
                when (leg) {
                    // The mind: a crown above.
                    CallReadiness.Leg.MIND -> arc(canvas, cx, cy, r * 1.06f, 245f, 50f)
                    // Hearing: two arcs, one each side.
                    CallReadiness.Leg.HEARING -> {
                        arc(canvas, cx, cy, r, 160f, 40f); arc(canvas, cx, cy, r, -20f, 40f)
                    }
                    // Voice: an arc beneath.
                    CallReadiness.Leg.VOICE -> arc(canvas, cx, cy, r * 1.06f, 65f, 50f)
                    // The rest of it: a faint full ring that closes the figure.
                    CallReadiness.Leg.PRESENCE -> {
                        val a = paint.alpha
                        paint.alpha = (a * 0.45f).toInt(); paint.strokeWidth = 1.2f * d
                        arc(canvas, cx, cy, r * 1.16f, 0f, 360f)
                        paint.alpha = a; paint.strokeWidth = 2f * d
                    }
                }
            }
            if (fade < 1f) postInvalidateOnAnimation()
        }

        private fun arc(c: Canvas, cx: Float, cy: Float, r: Float, start: Float, sweep: Float) {
            oval.set(cx - r, cy - r, cx + r, cy + r)
            c.drawArc(oval, start, sweep, false, paint)
        }

        companion object { const val FADE_MS = 420L }
    }
}
