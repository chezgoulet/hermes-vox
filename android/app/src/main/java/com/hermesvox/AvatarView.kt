package com.hermesvox

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * AvatarView is the visual entity. A polished sci-fi orb that reacts + moves
 * with the REAL agent state fed by the streamed turn (state transitions,
 * incremental speech, tool-progress pulses). States:
 *   idle     — calm breathing orb with a soft cyan glow
 *   listening— attends, expands, a green ring reacts to the mic
 *   thinking — the agent is working: a rotating energy arc (brighter/faster
 *              when a tool is actually running), a work shimmer
 *   speaking — a geometric waveform "mouth" + violet glow, pulsing to the voice
 * Tool-progress is a dedicated flash ([pulseTool]) — the SSE function_call feed.
 */
class AvatarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val orb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()
    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shaderCache: Shader? = null
    private var shaderColor = 0

    private var t = 0f
    @Volatile private var level = 0f            // 0..1 (mic/voice energy)
    @Volatile private var working = false       // a tool is actually running
    @Volatile private var state = "idle"
    @Volatile private var stateBlend = 1f       // eased transition between states
    @Volatile private var targetColor = Color.rgb(0, 229, 255)
    @Volatile private var color = targetColor
    @Volatile private var toolPulse = 0f        // decays after each tool call
    private val max = kotlin.math.max(0f, 1f)

    private val idle = Color.rgb(0, 229, 255)
    private val listen = Color.rgb(52, 211, 153)
    private val think = Color.rgb(251, 191, 36)
    private val speak = Color.rgb(139, 92, 246)

    fun setState(s: String) { state = s.lowercase(); invalidate() }
    fun setLevel(l: Float) { level = l.coerceIn(0f, 1f); invalidate() }
    fun setWorking(w: Boolean) { working = w; invalidate() }
    fun setStateLevel(s: String, l: Float, w: Boolean) { state = s.lowercase(); level = l.coerceIn(0f, 1f); working = w; invalidate() }

    /** Flash when a tool call arrives (feed from SSE function_call items). */
    fun pulseTool() { toolPulse = 1f; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        t += 0.02f
        stateBlend = (stateBlend + 0.06f).coerceAtMost(1f)
        toolPulse = (toolPulse - 0.03f).coerceAtLeast(0f)

        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) * 0.30f

        targetColor = when (state) {
            "listening" -> listen
            "thinking" -> think
            "speaking" -> speak
            else -> idle
        }
        color = lerpColor(color, targetColor, 0.12f)

        drawGlow(canvas, cx, cy, r)
        when (state) {
            "listening" -> drawListening(canvas, cx, cy, r)
            "thinking" -> drawThinking(canvas, cx, cy, r)
            "speaking" -> drawSpeaking(canvas, cx, cy, r)
            else -> drawIdle(canvas, cx, cy, r)
        }
    }

    private fun drawGlow(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        // A soft radial aura that swells with energy/tool activity.
        val pulseR = r * (1.6f + 0.18f * sin(t * 1.6f) + 0.6f * toolPulse + 0.35f * level)
        val a = ((120 + 90 * level + 150 * toolPulse).toInt()).coerceAtMost(220)
        if (shaderColor != color || shaderCache == null) {
            shaderColor = color
            shaderCache = RadialGradient(cx, cy, pulseR, color, 0x00000000, Shader.TileMode.CLAMP)
        }
        shaderPaint.shader = shaderCache
        shaderPaint.alpha = a
        canvas.drawCircle(cx, cy, pulseR, shaderPaint)
        shaderPaint.shader = null
    }

    // Calm breathing orb.
    private fun drawIdle(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val pulse = 1f + 0.045f * sin(t)
        orb.style = Paint.Style.FILL
        orb.color = color
        orb.shader = LinearGradient(cx - r, cy - r, cx + r, cy + r,
            withAlpha(color, 255), withAlpha(color, 170), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r * pulse, orb)
        orb.shader = null
        ring.strokeWidth = 2f
        ring.color = withAlpha(color, 110)
        canvas.drawCircle(cx, cy, r * pulse + 6f, ring)
        // tiny inner pulse
        orb.style = Paint.Style.FILL
        orb.color = withAlpha(Color.WHITE, (12 + 10 * sin(t)).toInt())
        canvas.drawCircle(cx, cy, r * pulse * 0.5f, orb)
    }

    // Attends / listens: expands with the mic level, green ring reacts.
    private fun drawListening(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val perked = 1f + 0.14f * level
        orb.style = Paint.Style.FILL
        orb.color = color
        canvas.drawCircle(cx, cy - 6f * level, r * perked, orb)
        // two "eyes" that widen with the level
        canvas.drawCircle(cx - r * 0.34f, cy - r * 0.16f, r * (0.11f + 0.07f * level), orb)
        canvas.drawCircle(cx + r * 0.34f, cy - r * 0.16f, r * (0.11f + 0.07f * level), orb)
        // soft outer ring pulsing with the voice
        ring.strokeWidth = 3f
        ring.color = withAlpha(color, (110 + 160 * level).toInt())
        canvas.drawCircle(cx, cy, r * (1.3f + 0.06f * sin(t)), ring)
    }

    // The agent is reasoning / doing work: a rotating energy arc + shimmer.
    private fun drawThinking(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val speed = if (working) 4f else 1.5f
        val beat = 1f + 0.05f * sin(t * 2f)
        orb.style = Paint.Style.FILL
        orb.color = withAlpha(color, 200)
        canvas.drawCircle(cx, cy, r * 0.82f * beat, orb)
        // pulsing ring = effort
        ring.strokeWidth = 4f
        ring.color = withAlpha(color, 180)
        canvas.drawCircle(cx, cy, r * (1.22f + 0.15f * sin(t * 3f)), ring)
        // rotating energy arc
        canvas.save()
        canvas.rotate(t * 60f * speed, cx, cy)
        ring.strokeWidth = 6f
        ring.color = withAlpha(color, 220)
        val span = if (working || toolPulse > 0f) 200f else 120f
        canvas.drawArc(cx - r * 0.6f, cy - r * 0.6f, cx + r * 0.6f, cy + r * 0.6f, 0f, span, false, ring)
        canvas.restore()
        if (working) {
            // chatter shimmer — the agent is calling tools
            orb.color = withAlpha(Color.WHITE, (30 + 30 * toolPulse).toInt())
            val n = 3
            for (i in 0 until n) {
                val a = t * 2f + i * (Math.PI * 2 / n).toFloat()
                canvas.drawCircle(cx + cos(a) * r * 0.7f, cy + sin(a) * r * 0.7f, r * 0.05f, orb)
            }
        }
    }

    // Speaking: a geometric waveform "mouth", violet glow pulsing to the voice.
    private fun drawSpeaking(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        orb.style = Paint.Style.FILL
        orb.color = withAlpha(color, 220)
        canvas.drawCircle(cx, cy, r * 0.9f, orb)
        path.reset()
        val n = 7
        val bw = r * 0.10f
        val gap = r * 0.055f
        val startX = cx - (n * (bw + gap)) / 2f
        val v = (level * 0.7f + 0.3f).coerceAtLeast(0.15f)
        for (i in 0 until n) {
            val h = r * (0.12f + 0.7f * v * (0.4f + 0.6f * absSin(t * 6f + i)))
            path.addRect(startX + i * (bw + gap), cy - h / 2f, startX + i * (bw + gap) + bw, cy + h / 2f, Path.Direction.CW)
        }
        canvas.drawPath(path, orb)
    }

    private fun absSin(x: Float): Float = if (sin(x) >= 0) sin(x) else -sin(x)

    private fun withAlpha(c: Int, a: Int): Int = Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

    private fun lerpColor(a: Int, b: Int, f: Float): Int =
        Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * f).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * f).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f).toInt()
        )
}
