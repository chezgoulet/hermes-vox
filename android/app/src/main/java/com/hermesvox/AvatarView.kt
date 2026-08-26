package com.hermesvox

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * AvatarView — the generative particle-being of Hermes Vox ("a wisp of the
 * House"). A field of light-points that = the presence. By default they form a
 * soft iris aperture (the gaze); they REARRANGE into shapes that express the
 * agent's work — reacting to state, workload, and the tool being called.
 *
 * Each particle is a physics point that springs toward a target emitted by the
 * active Shape. Shapes are GENERATIVE (parametric functions of time, workload,
 * and a per-call seed), so no two states ever look identical — this is the
 * generative-UI layer Christopher asked for.
 */
class AvatarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private data class P(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        var color: Int, var alpha: Float, var size: Float, val phase: Float,
        var bright: Float
    )

    companion object {
        const val COUNT = 240
        val SHAPES = listOf("iris", "listening", "vortex", "scan", "bracket",
            "constellation", "lumen", "waveform", "bloom")
    }

    private val parts = ArrayList<P>(COUNT)
    private var state = "idle"; private var tool: String? = null
    private var workload = 0f; private var amp = 0f
    private var time = 0f; private var lastNanos = 0L
    private var seed = 1
    private val rnd = Random(seed)
    private val cIdle = 0xFF6FB7C9.toInt(); private val cListen = 0xFF34D399.toInt()
    private val cThink = 0xFFFBBF24.toInt(); private val cSpeak = 0xFF8B5CF6.toInt()
    private val cCyan = 0xFF2AC3DC.toInt(); private val cViolet = 0xFF8B5CF6.toInt();
    private val cWhite = 0xFFEAF7FF.toInt()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private var glowShader: Shader? = null
    private var centered = false

    private val cx get() = width / 2f; private val cy get() = height / 2f
    private val R get() = (minOf(width, height) * 0.32f).coerceAtLeast(60f)

    init {
        val r = Random(7)
        for (i in 0 until COUNT) {
            val ph = i / COUNT.toFloat()
            parts.add(P(
                cx + (r.nextFloat() - 0.5f) * 40f, cy + (r.nextFloat() - 0.5f) * 40f,
                0f, 0f, cIdle, 0.5f, 2f + r.nextFloat() * 3f, ph, 1f
            ))
        }
    }

    // ---- Public API (work-aware) -----------------------------------------

    /** Principal entry: state idle|listening|thinking|streaming|speaking.
     *  tool e.g. "shell","web","file","memory","download","tts" (null = none).
     *  workload 0..1 (effort intensity). amp 0..1 (voice amplitude). */
    fun setPresent(state: String, tool: String?, workload: Float, amp: Float) {
        this.state = state.lowercase()
        this.tool = tool
        this.workload = workload.coerceIn(0f, 1f)
        this.amp = amp.coerceIn(0f, 1f)
    }

    fun setState(s: String) { state = s.lowercase() }
    fun setStateLevel(s: String, l: Float, w: Boolean) {
        state = s.lowercase(); amp = l.coerceIn(0f, 1f); workload = if (w) maxOf(workload, 0.4f) else 0f
    }
    fun setWorking(w: Boolean) { setPresent(state, tool, if (w) maxOf(workload, 0.5f) else 0f, amp) }
    fun pulseTool() { seed++; setPresent("thinking", tool, minOf(1f, workload + 0.35f), amp) }

    /** Preview/checkpoint hook: force an explicit shape + state for screenshots. */
    fun preview(name: String) {
        val n = name.lowercase()
        val (st, tk) = when (n) {
            "listening" -> "listening" to null
            "vortex" -> "thinking" to null
            "scan" -> "thinking" to "web"      // search/scan motif
            "bracket" -> "thinking" to "shell" // terminal motif
            "constellation" -> "thinking" to "memory"
            "lumen" -> "streaming" to null
            "waveform" -> "speaking" to null
            "bloom" -> "settle" to null
            else -> "idle" to null
        }
        state = st; tool = tk; workload = if (st == "thinking") 0.7f else 0.15f; amp = 0.4f
        invalidate()
    }

    // ---- Physics + draw ---------------------------------------------------

    private fun tick() {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1e9f).coerceIn(0.001f, 0.05f)
        lastNanos = now
        time += dt
        for (p in parts) {
            val tgt = targetOf(p)
            val stiff = 120f
            p.vx += -(p.x - tgt.first) * stiff * dt * 0.5f
            p.vy += -(p.y - tgt.second) * stiff * dt * 0.5f
            p.vx *= (1f - 2.4f * dt); p.vy *= (1f - 2.4f * dt)
            val bob = sin(time * 1.4f + p.phase * 8f) * 6f / sqrt(1f + workload * 3f)
            p.x += p.vx * dt * 60f + bob * dt * 30f
            p.y += p.vy * dt * 60f + cos(time * 1.1f + p.phase * 7f) * 4f * dt * 30f
        }
    }

    private fun targetOf(p: P): Pair<Float, Float> = place(p, state, tool, time, workload, cx, cy, R, seed)

    /** The generative shape function. Returns (x, y) target for a particle. */
    private fun place(p: P, st: String, tk: String?, t: Float, wl: Float,
                      cx: Float, cy: Float, R: Float, seed: Int): Pair<Float, Float> {
        val ph = p.phase; val a = ph * 2f * PI.toFloat()
        return when (st) {
            "listening" -> {
                val open = 1f
                val r = R * (0.62f + 0.34f * open) * (1f + 0.05f * sin(t * 2f + ph * 6f))
                cx + cos(a) * r to cy + sin(a) * r * 0.9f
            }
            "thinking" -> when (tk) {
                "web", "search" -> { // radial scan: sweeping arcs
                    val speed = 1.6f + wl * 2.2f
                    val band = (ph * 3f + wl * 2f)
                    val rr = R * (0.5f + 0.5f * frac(ph * 3f + t * 0.4f * speed))
                    val ang = a * 0.6f + t * speed
                    cx + cos(ang + band) * rr to cy + sin(ang + band) * rr
                }
                "shell" -> { // terminal bracket: a sharp [ ] frame
                    val seg = (ph * 12f).toInt(); val fx = frac(ph * 4f)
                    val jx = hash(ph, seed, 0) * R * 0.4f - R * 0.2f
                    val jy = hash(ph, seed, 1) * R * 0.4f - R * 0.2f
                    when {
                        seg < 2 -> cx - R * 0.9f to cy - R + fx * 2f * R
                        seg < 4 -> cx - R * 0.9f + fx * R * 1.8f to cy - R
                        seg < 6 -> cx - R * 0.9f + fx * R * 1.8f to cy + R
                        seg < 8 -> cx + R * 0.9f to cy - R + fx * 2f * R
                        seg < 10 -> cx - R * 0.65f + fx * R * 1.3f to cy + (jy)
                        else -> cx + jx to cy + jy   // blinking cursor cluster
                    }
                }
                "memory" -> { // constellation: seeded nodes + threads
                    val n = (seed % 8) + 3
                    val node = (ph * n).toInt().coerceIn(0, n - 1)
                    val na = (node / n.toFloat()) * 2f * PI.toFloat() + (seed % 10) * 0.3f
                    val nr = R * 0.7f * (0.6f + (node % 3) * 0.2f)
                    cx + cos(na) * nr to cy + sin(na) * nr
                }
                "file" -> { // fold: a serpentine write-line
                    val fx = frac(ph * 2f + t * 0.35f)
                    cx - R * 0.9f + fx * R * 1.8f to cy + sin(fx * 6f) * R * 0.4f
                }
                else -> { // vortex: a genuine spiraling gyre (log-spiral arm, rotating)
                    val arm = p.phase                       // 0..1 along the arm
                    val ang = arm * 2f * 2f * PI.toFloat()  // ~2 turns
                    val r = R * (0.16f + 0.74f * arm) * (1f + 0.06f * sin(t * 4f + arm * 9f))
                    val spin = t * (2f + wl * 5f)           // effort ramps the spin
                    cx + cos(ang + spin) * r to cy + sin(ang + spin) * r * 0.96f
                }
            }
            "streaming" -> { // lumen: a light-streamer trailing the reply
                val fx = frac(ph * 2f + t * 0.6f)
                cx - R * 0.8f + fx * R * 2.2f to cy + sin(fx * 9f + t * 2f) * R * 0.5f * (1f - fx * 0.4f)
            }
            "speaking" -> { // waveform mouth that pulses with the voice
                val r = R * 0.72f
                val x = cx + cos(a) * r; val y = cy + sin(a) * r
                // open a mouth aperture toward the bottom; pulse with amp
                val mouth = sin(ph * 6f + t * 12f) * amp * R * 0.22f
                x to y + (if (sin(a) > 0.2f) mouth else 0f)
            }
            "settle", "bloom" -> { // outward burst from the busy shape
                val p = frac(t * 0.7f)
                val rr = R * (0.2f + p * 0.9f)
                cx + cos(a) * rr to cy + sin(a) * rr
            }
            else -> { // iris: default soft aperture (the gaze)
                val open = 1f - 0.3f * wl
                val ring = R * 0.7f
                val inner = R * 0.28f
                // form an annulus; particles near the center form the aperture rim
                val r = if (frac(p.phase * 2f) < 0.82f) {
                    ring * (0.94f + 0.06f * sin(t * 1.4f + ph * 6f))
                } else inner * open
                cx + cos(a) * r to cy + sin(a) * r
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!centered && width > 0 && height > 0) {
            val r = java.util.Random(7)
            for (p in parts) {
                p.x = cx + (r.nextFloat() - 0.5f) * minOf(width, height) * 0.25f
                p.y = cy + (r.nextFloat() - 0.5f) * minOf(width, height) * 0.25f
            }
            centered = true
        }
        tick()
        val t = time; val wl = workload
        val base = stateColor(state)
        for (p in parts) {
            // ease color toward the state hue (drift particles for context)
            p.color = lerpColor(p.color, base, 0.06f)
            val ta = baseAlpha(state, p, wl)
            p.alpha = p.alpha + (ta - p.alpha) * 0.08f
            p.size = 2.6f + p.phase * 2.0f + wl * 1.6f
            // glowing dot
            val r = p.size
            glowShader = RadialGradient(p.x, p.y, r * 4.5f,
                intArrayOf(withAlpha(p.color, (p.alpha * 255).toInt()),
                    withAlpha(p.color, 0)), null, Shader.TileMode.CLAMP)
            fill.shader = glowShader
            canvas.drawCircle(p.x, p.y, r * 4.5f, fill)
            fill.shader = null
            // bright core
            fill.color = withAlpha(Color.WHITE, (p.alpha * 255).toInt())
            canvas.drawCircle(p.x, p.y, r * 0.62f, fill)
        }
        // always animate
        postInvalidateOnAnimation()
    }

    private fun stateColor(st: String): Int = when (st) {
        "listening" -> cListen
        "thinking" -> if (workload > 0.55f) lerpColor(cThink, cViolet, 0.4f) else cThink
        "streaming" -> cCyan
        "speaking" -> lerpColor(cViolet, cCyan, 0.4f + amp * 0.2f)
        else -> lerpColor(cIdle, cCyan, 0.3f + amp * 0.2f)
    }
    private fun baseAlpha(st: String, p: P, wl: Float): Float = when (st) {
        "speaking" -> 0.6f + amp * 0.4f
        "thinking" -> 0.6f + wl * 0.32f
        "streaming" -> 0.62f + 0.3f * sin(time * 4f + p.phase * 6f)
        else -> 0.55f + 0.12f * sin(time * 2f + p.phase * 6f)
    }
    private fun lerpColor(a: Int, b: Int, f: Float): Int {
        val ff = f.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) * (1 - ff) + Color.red(b) * ff).toInt(),
            (Color.green(a) * (1 - ff) + Color.green(b) * ff).toInt(),
            (Color.blue(a) * (1 - ff) + Color.blue(b) * ff).toInt()
        )
    }
    private fun hash(a: Float, b: Int, k: Int): Float {
        val x = a * 127.1f + b * 311.7f + k * 74.7f
        return frac(x * 1000f)  // deterministic 0..1
    }
    private fun withAlpha(c: Int, a: Int): Int = Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))
    private fun frac(x: Float): Float = x - Math.floor(x.toDouble()).toFloat()
}
