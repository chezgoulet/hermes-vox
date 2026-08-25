package com.hermesvox

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * AvatarView is the visual entity. It reacts + moves with Hermes's workload and
 * state: idle = a calm breathing orb, listening = it perks and tracks the mic
 * level, thinking/working = a pulsing ring with a working shimmer (the agent is
 * doing work), speaking = a geometric "mouth" that animates with the speech.
 */
class AvatarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()

    private var t = 0f
    @Volatile private var level = 0f          // 0..1 audio/mic/speaker level
    @Volatile private var working = false     // the agent is doing work (tool-calling)
    @Volatile private var state = "idle"

    fun setState(s: String) { state = s.lowercase(); invalidate() }
    fun setLevel(l: Float) { level = l.coerceIn(0f, 1f); invalidate() }
    fun setWorking(w: Boolean) { working = w; invalidate() }
    fun setStateLevel(s: String, l: Float, w: Boolean) { state = s.lowercase(); level = l.coerceIn(0f, 1f); working = w; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        t += 0.018f
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) * 0.30f
        when (state) {
            "listening" -> drawListening(canvas, cx, cy, r)
            "thinking" -> drawThinking(canvas, cx, cy, r)
            "speaking" -> drawSpeaking(canvas, cx, cy, r)
            else -> drawIdle(canvas, cx, cy, r)
        }
    }

    // Calm breathing orb.
    private fun drawIdle(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val pulse = 1f + 0.04f * sin(t)
        val rr = r * pulse
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(90, 120, 180)
        canvas.drawCircle(cx, cy, rr, paint)
        paint.style = Paint.Style.STROKE
        paint.color = Color.argb(120, 255, 255, 255)
        ring.strokeWidth = 2f
        canvas.drawCircle(cx, cy, rr + 6f, ring)
    }

    // Peeks/attends with the mic level (the "hearing you" presence).
    private fun drawListening(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val perked = 1f + 0.12f * level
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(70, 150, 110)
        canvas.drawCircle(cx, cy - 4f * level, r * perked, paint)
        // two "eyes" that widen with the level
        canvas.drawCircle(cx - r * 0.32f, cy - r * 0.18f, r * (0.10f + 0.05f * level), paint)
        canvas.drawCircle(cx + r * 0.32f, cy - r * 0.18f, r * (0.10f + 0.05f * level), paint)
        // a soft outer ring that pulses with the voice
        paint.style = Paint.Style.STROKE
        paint.color = Color.argb(100 + (200 * level).toInt(), 160, 230, 180)
        ring.strokeWidth = 3f
        canvas.drawCircle(cx, cy, r * (1.25f + 0.05f * sin(t)), ring)
    }

    // The agent is reasoning / doing work: a pulsing ring + a working shimmer.
    private fun drawThinking(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(150, 110, 60)
        canvas.drawCircle(cx, cy, r * (0.8f + 0.05f * sin(t * 2f)), paint)
        // pulsing ring = effort
        paint.style = Paint.Style.STROKE
        paint.color = Color.argb(180, 230, 180, 90)
        ring.strokeWidth = 4f
        canvas.drawCircle(cx, cy, r * (1.2f + 0.15f * sin(t * 3f)), ring)
        if (working) {
            // a rotating "work" arc — the agent is calling tools
            canvas.save()
            canvas.rotate(t * 60f, cx, cy)
            ring.strokeWidth = 6f
            ring.color = Color.argb(220, 240, 200, 120)
            canvas.drawArc(cx - r * 0.55f, cy - r * 0.55f, cx + r * 0.55f, cy + r * 0.55f, 0f, 120f, false, ring)
            canvas.restore()
        }
    }

    // Speaking: a geometric "mouth" waveform animated with the speech level.
    private fun drawSpeaking(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(110, 90, 180)
        canvas.drawCircle(cx, cy, r * 0.9f, paint)
        // mouth bars pulse with level
        path.reset()
        val n = 7
        val bw = r * 0.10f
        val gap = r * 0.055f
        val startX = cx - (n * (bw + gap)) / 2f
        for (i in 0 until n) {
            val h = r * (0.12f + 0.65f * level * (0.4f + 0.6f * absSin(t * 6f + i)))
            path.addRect(startX + i * (bw + gap), cy - h / 2f, startX + i * (bw + gap) + bw, cy + h / 2f, Path.Direction.CW)
        }
        canvas.drawPath(path, paint)
    }

    private fun absSin(x: Float): Float = if (sin(x) >= 0) sin(x) else -sin(x)
}
