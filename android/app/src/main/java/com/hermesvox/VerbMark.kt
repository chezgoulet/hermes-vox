package com.hermesvox

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * VerbMark — a command's kind, drawn as the still frame of the motion the being makes for it
 * ([CommandMotion.Verb], #227). The commands sheet puts one at the head of every row, so a row
 * says what kind of thing it does before it is read: sweep looks across, pulse chooses,
 * converge condenses, scatter clears, ring reaches out, settle informs.
 *
 * Still, never animated: the row's state is words (#226/#281); the motion belongs to the
 * being. The mark is a non-text glyph, so its floor is 3:1 against the row it sits on
 * (`LightModeContrastTest`), in the tint the row gives it.
 */
class VerbMark(context: Context, private val verb: CommandMotion.Verb, tint: Int) : View(context) {

    private val d = context.resources.displayMetrics.density
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * d; strokeCap = Paint.Cap.ROUND; color = tint
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = tint }
    private val arc = RectF()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - stroke.strokeWidth
        fun circle(rr: Float, a: Int = 255) { stroke.alpha = a; c.drawCircle(cx, cy, rr, stroke); stroke.alpha = 255 }
        fun dot(x: Float, y: Float, rr: Float, a: Int = 255) { fill.alpha = a; c.drawCircle(x, y, rr, fill); fill.alpha = 255 }
        fun ray(deg: Double, from: Float, to: Float) {
            val t = Math.toRadians(deg)
            c.drawLine(cx + from * cos(t).toFloat(), cy + from * sin(t).toFloat(),
                cx + to * cos(t).toFloat(), cy + to * sin(t).toFloat(), stroke)
        }
        when (verb) {
            CommandMotion.Verb.SWEEP -> {          // a radar: the ring, the beam, its wake
                circle(r, 110)
                arc.set(cx - r, cy - r, cx + r, cy + r)
                fill.alpha = 70; c.drawArc(arc, -100f, 55f, true, fill); fill.alpha = 255
                ray(-45.0, 0f, r)
                dot(cx, cy, 1.6f * d)
            }
            CommandMotion.Verb.PULSE -> {          // a nucleus: a beat at the centre
                dot(cx, cy, r * 0.3f)
                circle(r * 0.62f)
                circle(r, 90)
            }
            CommandMotion.Verb.CONVERGE -> {       // draw inward: four strokes to a point
                for (a in listOf(45.0, 135.0, 225.0, 315.0)) ray(a, r * 0.42f, r)
                dot(cx, cy, r * 0.2f)
            }
            CommandMotion.Verb.SCATTER -> {        // spin out and clear: dots leaving the centre
                val pts = listOf(0.0 to 0.35f, 60.0 to 0.65f, 120.0 to 0.95f, 180.0 to 0.5f, 240.0 to 0.8f, 300.0 to 0.95f)
                for ((a, k) in pts) {
                    val t = Math.toRadians(a)
                    dot(cx + r * k * cos(t).toFloat(), cy + r * k * sin(t).toFloat(), d * (1.2f + 1.6f * k), (255 * (1.1f - k * 0.6f)).toInt().coerceAtMost(255))
                }
            }
            CommandMotion.Verb.RING -> {           // ripples outward from a source
                dot(cx, cy, r * 0.16f)
                circle(r * 0.5f)
                circle(r, 120)
            }
            CommandMotion.Verb.SETTLE -> {         // come to rest: a point on a level line
                val y = cy + r * 0.45f
                c.drawLine(cx - r, y, cx + r, y, stroke)
                dot(cx, y - r * 0.32f - stroke.strokeWidth / 2f, r * 0.32f)
            }
        }
    }
}
