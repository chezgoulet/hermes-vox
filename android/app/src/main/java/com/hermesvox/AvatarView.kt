package com.hermesvox

import android.content.Context
import android.graphics.Bitmap
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
        const val COUNT = 320
        const val GLOW_PX = 64f
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
    private val cThink = 0xFFFFC24D.toInt(); private val cSpeak = 0xFF8B5CF6.toInt()
    private val cCyan = 0xFF2AC3DC.toInt(); private val cViolet = 0xFF8B5CF6.toInt();
    private val cWhite = 0xFFEAF7FF.toInt()
    // previewB motion themes: a held, cool blue for the waiting constellation (reads
    // as patience, not as work) and a near-white for the recoil flinch / tool shimmer.
    private val cWait = 0xFF5B8DEF.toInt()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glows = HashMap<Int, Bitmap>()
    private val mat = android.graphics.Matrix()
    private var centered = false
    // Idle appearance: a user-picked shape/theme ("aura" = default dispersed
    // breathing) + optional auto-cycle so the being stays alive between turns.
    private var idleTheme = "aura"
    private var cycleThemes = false
    private var cycleSec = 8f

    private val cx get() = width / 2f; private val cy get() = height / 2f
    private val R get() = (minOf(width, height) * 0.32f).coerceAtLeast(60f)

    // ---- 0.5.0-previewB: state-driven presence motion -----------------------
    // The being's motion is no longer chosen at the call site. MotionState decides
    // WHAT it is doing (a pure, unit-proven table) and renderParams turns that into
    // the drive values below; the per-frame generative render reads them. There is
    // NO second animation loop — this drives the one that already exists.
    private var motion = MotionState.Motion.IDLE
    private var params = MotionState.renderParams(MotionState.Motion.IDLE, 0f, 0f)
    // The motion the stall interrupted, so a resume restores it (M2: "back to the
    // prior motion"). Held here, not in the pure table, which has no memory.
    private var priorMotion = MotionState.Motion.IDLE
    private var stalled = false
    private var stallMs = 0L
    // The animation-clock instant the recoil began, so the flinch is a single gesture
    // with a beginning and an end instead of a loop.
    private var recoilAt = 0f
    // true when [params] describes the shape actually being rendered. False on the
    // legacy paths (preview(), setState, the Settings idle theme), which keep their
    // existing look untouched — the motion drive never retro-fits them.
    private var driven = false

    init {
        val r = Random(7)
        for (i in 0 until COUNT) {
            val ph = i / COUNT.toFloat()
            parts.add(P(
                cx + (r.nextFloat() - 0.5f) * 40f, cy + (r.nextFloat() - 0.5f) * 40f,
                0f, 0f, cIdle, 0.5f, 2f + r.nextFloat() * 3f, ph, 1f
            ))
        }
        // Pre-bake the glow for the finite state colors (no per-frame gradient alloc).
        for (c in intArrayOf(cIdle, cListen, cThink, cSpeak, cCyan, cWait, cWhite)) glows[c] = glowBitmap(c)
    }

    private fun glowBitmap(color: Int): Bitmap {
        val s = GLOW_PX.toInt()
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(s / 2f, s / 2f, s / 2f, color, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawCircle(s / 2f, s / 2f, s / 2f, p)
        return bmp
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

    /** LIVE tool-call hook: the being gathers into the work-state + the tool's
     *  motif, and ramps its energy with the workload. Re-seeds the generative
     *  shape so each call is distinct. */
    fun onTool(tool: String?, workload: Float) {
        this.tool = tool
        this.state = "thinking"
        this.workload = workload.coerceIn(0f, 1f)
        seed++
        invalidate()
    }

    fun setState(s: String) { state = s.lowercase() }
    fun setStateLevel(s: String, l: Float, w: Boolean) {
        state = s.lowercase(); amp = l.coerceIn(0f, 1f); workload = if (w) maxOf(workload, 0.4f) else 0f
    }
    fun setWorking(w: Boolean) { setPresent(state, tool, if (w) maxOf(workload, 0.5f) else 0f, amp) }
    fun pulseTool() { seed++; setPresent("thinking", tool, minOf(1f, workload + 0.35f), amp) }

    /** THE previewB entry point: render [m] — the motion MotionState decided — by
     *  driving the setters that already exist. TOOL/TOOL_RESULT go through onTool /
     *  pulseTool so the tool motif and its satisfied pulse keep their current
     *  behaviour (re-seeded geometry, energy ramp); everything else goes through
     *  setPresent. The motion's shape key is applied last so it is the single
     *  authority on the geometry regardless of which setter ran.
     *  [amp] must be the REAL voice level (playback-head RMS), not a placeholder. */
    fun applyMotion(m: MotionState.Motion, amp: Float, workload: Float, tool: String?) {
        if (m == MotionState.Motion.RECOIL && motion != MotionState.Motion.RECOIL) recoilAt = time
        motion = m
        params = MotionState.renderParams(m, workload, amp)
        when (m) {
            MotionState.Motion.TOOL -> onTool(tool, workload)
            MotionState.Motion.TOOL_RESULT -> { this.tool = tool; pulseTool() }
            else -> setPresent(params.shape, tool, workload, amp)
        }
        state = params.shape
        this.amp = amp.coerceIn(0f, 1f)
        invalidate()
    }

    /** Per-frame refresh of the drive values for the CURRENT motion — this is the amp
     *  lock's clock. It deliberately does NOT re-run the one-shot setters: onTool
     *  re-seeds the generative motif and pulseTool ramps the energy, and both belong to
     *  the signal EDGE. Called every frame, they would re-seed the shape ~33 times a
     *  second and saturate the workload. */
    fun driveMotion(amp: Float, workload: Float) {
        params = MotionState.renderParams(motion, workload, amp)
        this.amp = amp.coerceIn(0f, 1f)
        this.workload = workload.coerceIn(0f, 1f)
    }

    /** The stall lever (M2). A provider that has gone quiet is the one work state the
     *  being cannot infer from the controller's own state string — "thinking" keeps
     *  arriving while nothing at all is happening. While stalled the being holds the
     *  waiting constellation; on resume it returns to whatever it was doing.
     *  [ms] is how long the stall has run (drives how far the pattern spreads). */
    fun setStall(stalled: Boolean, ms: Long) {
        stallMs = if (stalled) ms else 0L
        if (stalled == this.stalled) { if (stalled) invalidate(); return }
        this.stalled = stalled
        if (stalled) {
            priorMotion = motion
            applyMotion(MotionState.Motion.STALL, amp, workload, tool)
        } else {
            applyMotion(priorMotion, amp, workload, tool)
        }
    }

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

    /** Idle shape/theme: "aura" (default dispersed breath) or one of SHAPES. */
    fun setIdleTheme(theme: String) { idleTheme = theme.lowercase(); invalidate() }
    /** Auto-advance the idle theme every cycleSec (false = stay on one). */
    fun setCycleThemes(cycle: Boolean) { cycleThemes = cycle; invalidate() }
    fun setCycleSec(sec: Float) { cycleSec = sec; invalidate() }

    // ---- Physics + draw ---------------------------------------------------

    private fun tick() {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1e9f).coerceIn(0.001f, 0.05f)
        lastNanos = now
        time += dt
        for (p in parts) {
            val tgt = targetOf(p)
            // Critically-damped spring toward the shape target (STABLE — the old
            // stiff=240 step was unstable and blew the particles off-screen).
            val stiff = 42f
            val damp = 2f * sqrt(stiff)          // ~12.9, critical damping
            p.vx += (tgt.first - p.x) * stiff * dt
            p.vy += (tgt.second - p.y) * stiff * dt
            p.vx /= (1f + damp * dt)
            p.vy /= (1f + damp * dt)
            p.x += p.vx * dt
            p.y += p.vy * dt
            // gentle per-particle drift for life (small, stable)
            p.x += sin(time * 1.2f + p.phase * 8f) * 4f * dt
            p.y += cos(time * 1.0f + p.phase * 7f) * 4f * dt
        }
    }

    private fun targetOf(p: P): Pair<Float, Float> = place(p, state, tool, time, workload, cx, cy, R, seed)

    /** The generative shape function. Returns (x, y) target for a particle. */
    private fun place(p: P, st: String, tk: String?, t: Float, wl: Float,
                      cx: Float, cy: Float, R: Float, seed: Int): Pair<Float, Float> {
        val ph = p.phase; val a = ph * 2f * PI.toFloat()
        // Idle theme override: when at rest, render the user-chosen shape instead
        // of the default aura. "Cycle" advances the theme over time. This reuses
        // the existing shape functions via a state map, so the geometry stays
        // generative; the avatar's `state` field remains idle so the COLOR keeps
        // the presence hue.
        if (st == "idle" || st == "settle" || st == "bloom" || st == "drift") {
            val th = if (cycleThemes) cyclingTheme(t) else idleTheme
            val mapped = themeShape(th)
            if (mapped != null) return place(p, mapped.first, mapped.second, t, wl, cx, cy, R, seed)
        }
        return when (st) {
            "listening" -> {   // BREATHING: a slow, low-amplitude, receptive field
                val open = 1f
                val rr = if (driven) params.radius else 1f
                val sp = if (driven) params.speed else 1f
                val r = R * rr * (0.62f + 0.34f * open) * (1f + 0.05f * sin(t * 2f * sp + ph * 6f))
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
                // previewB: params.radius/speed carry the REAL playback RMS, so the ring
                // ITSELF breathes with the syllable instead of only the mouth moving.
                // At amp=0 this is 0.72R — exactly the pre-previewB radius.
                val rr = if (driven) params.radius * 0.90f else 0.72f
                val sp = if (driven) params.speed else 1f
                val r = R * rr
                val x = cx + cos(a) * r; val y = cy + sin(a) * r
                // open a mouth aperture toward the bottom; pulse with amp
                val mouth = sin(ph * 6f + t * 12f * sp) * amp * R * 0.22f
                x to y + (if (sin(a) > 0.2f) mouth else 0f)
            }
            "gather" -> { // GATHERING (thinking): the field draws INWARD and orbits,
                // pulling tighter the harder the work is. params.orbit is negative here,
                // and the collapse breathes so it reads as concentration, not a ring.
                val arm = ph
                val ang = arm * 2f * PI.toFloat() + t * params.speed
                val draw = 0.5f + 0.5f * sin(t * 1.6f + arm * 3f)
                val r = R * params.radius * (0.22f + 0.70f * arm) * (1f + params.orbit * 0.45f * draw)
                cx + cos(ang) * r to cy + sin(ang) * r * 0.95f
            }
            "waiting" -> { // WAITING CONSTELLATION (provider stall): a HELD pattern that
                // drifts a few degrees a second. Seeded nodes stay put for the whole
                // stall, so it reads as one figure waiting — not as work, not as sleep.
                // The spread widens with the stall's age: a long wait visibly opens up.
                val n = 7
                val node = (ph * n).toInt().coerceIn(0, n - 1)
                val nf = node.toFloat()
                val na = (nf / n) * 2f * PI.toFloat() + hash(nf, seed, 5) * 1.7f
                val breath = 1f + 0.06f * sin(t * 0.6f + nf)
                val nr = R * params.radius * (0.45f + hash(nf, seed, 6) * 0.5f) * breath
                val spin = t * params.speed * 0.25f
                val spread = R * 0.05f * (1f + minOf(2f, stallMs / 6000f))
                val jx = (hash(ph, seed, 7) - 0.5f) * spread * 2f
                val jy = (hash(ph, seed, 8) - 0.5f) * spread * 2f
                cx + cos(na + spin) * nr + jx to cy + sin(na + spin) * nr + jy
            }
            "recoil" -> { // RECOIL (barge/hush): a fast outward flinch — it heard you.
                // Runs on its OWN clock from recoilAt, so this is one gesture with an
                // end, not a loop; the caller restores the prior motion after RECOIL_MS.
                val prog = ((time - recoilAt) * (1000f / MotionState.RECOIL_MS)).coerceIn(0f, 1f)
                val r = R * params.radius * (0.35f + 0.85f * prog)
                val ang = a + t * params.speed * 0.3f
                cx + cos(ang) * r to cy + sin(ang) * r
            }
            "drift" -> { // DRIFT (sustained rest): wider and slower than idle. Alive,
                // doing nothing. Reached only after the being has been quiet a while.
                val r = R * params.radius * (0.30f + 0.45f * hash(ph, seed, 9)) *
                        (1f + 0.20f * sin(t * params.speed * 1.3f + ph * 5f))
                val ang = ph * 2f * PI.toFloat() + t * params.speed * 0.5f
                cx + cos(ang) * r to cy + sin(ang) * r * 0.92f
            }
            "settle", "bloom" -> { // outward burst from the busy shape
                val p = frac(t * 0.7f)
                val rr = R * (0.2f + p * 0.9f)
                cx + cos(a) * rr to cy + sin(a) * rr
            }
            else -> { // at-rest: a soft, dispersed, breathing aura (NOT an eye)
                val r = R * (0.2f + 0.34f * hash(p.phase, seed, 2)) *
                        (1f + 0.16f * sin(t * 1.1f + p.phase * 9f))   // slow breath
                val ang = p.phase * 2f * PI.toFloat() + t * 0.28f     // gentle drift
                val jx = (hash(p.phase, seed, 3) - 0.5f) * R * 0.18f
                val jy = (hash(p.phase, seed, 4) - 0.5f) * R * 0.18f
                cx + cos(ang) * r + jx to cy + sin(ang) * r * 0.9f + jy
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
        // previewB: the motion's drive params own the colour/brightness for the frame,
        // but ONLY while they describe the shape actually on screen. Computed once per
        // frame, never per particle.
        driven = params.shape == state
        val base = if (driven) themeColor(params.theme) else stateColor(state)
        for (p in parts) {
            p.color = lerpColor(p.color, base, 0.06f)
            val ta = baseAlpha(state, p, wl)
            p.alpha = p.alpha + (ta - p.alpha) * 0.08f
            p.size = 3.2f + p.phase * 2.4f + wl * 2.0f +
                    (if (driven) (params.bright - 0.6f) * 3.0f else 0f)
            val r = p.size
            // single pre-made glow bitmap, drawn scaled — NO per-frame gradient alloc
            val glow = glowFor(p.color)
            fill.alpha = (p.alpha * 255).toInt()
            mat.reset()
            mat.setTranslate(p.x, p.y)
            mat.preScale((r * 5.5f) / GLOW_PX, (r * 5.5f) / GLOW_PX)
            mat.preTranslate(-GLOW_PX / 2f, -GLOW_PX / 2f)
            canvas.drawBitmap(glow, mat, fill)
            // bright core
            fill.color = withAlpha(Color.WHITE, (p.alpha * 255).toInt())
            canvas.drawCircle(p.x, p.y, r * 0.68f, fill)
        }
        fill.alpha = 255
        // always animate
        postInvalidateOnAnimation()
    }

    private fun glowFor(color: Int): Bitmap =
        glows.keys.minByOrNull { colDist(it, color) }?.let { glows[it] } ?: glows.values.firstOrNull()!!
    private fun colDist(a: Int, b: Int): Int {
        val dr = Color.red(a) - Color.red(b); val dg = Color.green(a) - Color.green(b); val db = Color.blue(a) - Color.blue(b)
        return dr * dr + dg * dg + db * db
    }

    /** previewB: a motion's colour theme (MotionState.renderParams) -> a baked glow.
     *  Deliberately reuses the SAME hues the states already had, so a motion-driven
     *  frame and the legacy path render the same being — only the motion is new. */
    private fun themeColor(theme: String): Int = when (theme) {
        "listen" -> cListen
        "think" -> cThink
        "speak" -> lerpColor(cViolet, cCyan, 0.4f + amp * 0.2f)
        "wait" -> cWait                                    // held, cool: patience
        "recoil" -> cWhite                                 // the flinch reads as a flash
        "result" -> lerpColor(cThink, cWhite, 0.55f)       // the satisfied shimmer
        else -> lerpColor(cIdle, cCyan, 0.3f + amp * 0.2f) // "presence"
    }

    private fun stateColor(st: String): Int = when (st) {
        "listening" -> cListen
        "thinking" -> cThink   // warm gold (the gold->violet workload lerp made salmon)
        "streaming" -> cCyan
        "speaking" -> lerpColor(cViolet, cCyan, 0.4f + amp * 0.2f)
        else -> lerpColor(cIdle, cCyan, 0.3f + amp * 0.2f)
    }
    private fun baseAlpha(st: String, p: P, wl: Float): Float {
        if (driven) {
            // Brightness is a drive param; the per-particle shimmer stays so the field
            // never flattens into a stencil, and rides the motion's own speed.
            val shimmer = 0.10f * sin(time * (2f + params.speed) + p.phase * 6f)
            return (params.bright + shimmer).coerceIn(0.15f, 1f)
        }
        return when (st) {
            "speaking" -> 0.68f + amp * 0.32f
            "thinking" -> 0.7f + wl * 0.25f
            "streaming" -> 0.72f + 0.3f * sin(time * 4f + p.phase * 6f)
            else -> 0.62f + 0.14f * sin(time * 2f + p.phase * 6f)
        }
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

    /** Idle theme -> (shape-state, tool) so the existing shape funcs render it.
     *  null = fall through to the default dispersed aura. */
    private fun themeShape(th: String): Pair<String, String?>? = when (th) {
        "iris" -> "listening" to null
        "vortex" -> "thinking" to null
        "waveform" -> "speaking" to null
        "scan" -> "thinking" to "web"
        "constellation" -> "thinking" to "memory"
        "bracket" -> "thinking" to "shell"
        else -> null   // "aura" / unknown -> default aura
    }

    /** The theme to show now when cycling (advances every cycleSec). */
    private fun cyclingTheme(t: Float): String {
        val list = listOf("aura", "iris", "vortex", "waveform", "scan", "constellation")
        val idx = ((t / cycleSec).toInt()).mod(list.size)
        return list[idx]
    }
}
