package com.hermesvox

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * AvatarView — the being of Hermes Vox (0.5.0.2: the emergent luminous swarm).
 *
 * WHAT CHANGED, AND WHY THE OLD ONE READ AS AI-GENERATED
 * ------------------------------------------------------
 * The previous renderer computed a target (x,y) for every particle from a closed-form
 * trig/hash expression and eased the particle toward it. That is "dots on a curve":
 * the formula IS the picture, so the swarm traced a spiral, a ring, a bracket. Four
 * tells followed from that single choice —
 *
 *   1. no physical coupling, so it was never a coherent body;
 *   2. mathematically even, so the light was uniform instead of clustering;
 *   3. one globally-lerped hue with no falloff, so it was a flat disc, not light;
 *   4. state changes tweened one curve into another, so transitions snapped.
 *
 * This renderer inverts it. Particles are NO LONGER placed on a formula. Each one is a
 * physics body inside a shared, moving FIELD:
 *
 *   a soft spring toward a volumetric body region  (gives the silhouette)
 * + a curl-ish flow field sampled from a sine LUT   (gives EMERGENT advection —
 *                                                    neighbours move together, so the
 *                                                    swarm reads as one substance)
 * + a per-state bias (updraft, infall, outward)     (gives the body its character)
 * + a smooth per-particle tremor                    (gives life at rest)
 *
 * The shape functions below are therefore TARGET REGIONS, not outlines. A particle is
 * never AT its target — the flow field and tremor keep displacing it while the spring
 * keeps pulling it back, so the silhouette is something the swarm DOES, continuously,
 * rather than something it traces. That is the whole difference between an inhabitant
 * and a visualization.
 *
 * LIGHT
 * -----
 * Every sprite is drawn with PorterDuff ADD, so overlapping light SUMS: where the swarm
 * is dense it is genuinely brighter, for free, with no density bookkeeping. Sprites are
 * baked with a white-hot centre falling off through the saturated hue to transparent, so
 * each point is a small lamp rather than a dot. Behind it all one large soft halo gives
 * the body its ambient bloom and its bright-core/dark-edge read; a vignette closes the
 * edges. Home radii are core-biased (pow > 1) so the middle is denser — organic light
 * clusters instead of a uniform disc.
 *
 * TRANSITIONS
 * -----------
 * Nothing cuts. The drive values (radius / energy / brightness / inward bias) and the
 * palette are EASED per frame, and because particles carry velocity a new body region
 * makes them overshoot and swirl through the in-between before settling — a flame
 * changing shape, not a geometric dissolve.
 *
 * THE STATIC <-> ENERGETIC DIAL
 * -----------------------------
 * Two scalars per archetype own the whole spectrum: [springK] (how tightly a particle is
 * bound to the body) and [flowGain] (how hard the shared field shoves it). Tight spring
 * + weak flow = near-still and coherent (WAITING, LISTENING, IDLE). Loose spring +
 * violent flow = turbulent and explosive (FLAME, BURST). The still states are what make
 * the energetic ones land, so they are tuned deliberately still — but never frozen: the
 * tremor and the flicker keep running at every energy level.
 *
 * CONTRACT (unchanged — see KEEP-list): MotionState.kt is untouched. Every public entry
 * MainActivity / RealtimeActivity / SettingsActivity calls keeps its signature and its
 * semantics; only the rendering and the shape-function internals are new.
 */
class AvatarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    /**
     * One inhabitant of the swarm: a physics body with a FIXED identity (its slot in the
     * body, its size / brightness / flicker character) and a MUTABLE state (where it is
     * right now, how fast, how bright).
     *
     * All 320 are allocated once in [init]. The hot loop only ever writes primitives into
     * them — no Pair, no lambda, no boxing, no Random. The old renderer allocated a
     * Pair<Float,Float> per particle per frame (9,600 objects/s) plus a minByOrNull
     * closure per particle; both are gone.
     */
    private class P {
        // ---- live state (written every frame) ----
        var x = 0f; var y = 0f
        var vx = 0f; var vy = 0f
        var alpha = 0.4f
        var size = 3f
        // ---- fixed identity (written once, in init) ----
        /** Low-discrepancy slot 0..1: this particle's place in the body. */
        var u = 0f
        /** Home unit vector. Precomputed so rotating the whole body costs 4 mults and
         *  ZERO trig per particle per frame. */
        var hcos = 1f; var hsin = 0f
        /** Spiral-arm unit vector (the gyre's 2-turn arm), same trick. */
        var gcos = 1f; var gsin = 0f
        /** Home radius factor, CORE-BIASED: dense middle, sparse rim. With additive
         *  blending this is what makes the centre brighter — no density math needed. */
        var hr = 0.5f
        /** Fixed seeded offset. Breaks the rotational symmetry so the body is never a
         *  perfect figure. */
        var jx = 0f; var jy = 0f
        /** Size character and brightness character, both long-tailed: most particles are
         *  small and dim, a few are large and hot. Uniform variance reads as generated. */
        var sz = 1f
        var bri = 1f
        /** Flicker / tremor phase. */
        var fl = 0f
        /** A hot ember: small and very bright. Micro-detail among the glow. */
        var spark = false
        /** Carries the accent hue instead of the base, so the body is two-tone, never one
         *  flat colour. */
        var accent = false
    }

    companion object {
        const val COUNT = 320
        const val GLOW_PX = 64f

        /** Kept for API parity (Settings lists these as the presence themes). */
        val SHAPES = listOf("iris", "listening", "vortex", "scan", "bracket",
            "constellation", "lumen", "waveform", "bloom")

        private const val TAU = (PI * 2).toFloat()
        private const val SPIRAL_TURNS = 2.0f

        // --- sine LUT: the flow field and every oscillator sample this instead of calling
        // --- sin()/cos(). 4096 entries = 16KB, allocated once. ~0.09 degree resolution,
        // --- far finer than a soft additive sprite can reveal.
        private const val LUT_N = 4096
        private const val LUT_MASK = LUT_N - 1
        private const val LUT_Q = LUT_N / 4                 // pi/2 in index units
        private const val LUT_SCALE = LUT_N / TAU           // radians -> index

        private const val HALO_PX = 128f
        private const val GLOW_SPAN = 7.2f                  // sprite diameter / core size
        private const val CACHE_CAP = 16
        /** How far back the Comet category's trail sprite sits, in seconds of the
         *  particle's own velocity. ~2 frames at 30fps. */
        private const val TRAIL_DT = 0.066f

        // --- body archetypes. One per thing the being can be seen doing.
        private const val A_ORB = 0        // at rest: a dispersed breathing cloud
        private const val A_BREATH = 1     // LISTENING: receptive, the stillest thing
        private const val A_FLAME = 2      // THINKING/gather: a flame that licks upward
        private const val A_GYRE = 3       // vortex: a swirling gyre (tool, no motif)
        private const val A_VOICE = 4      // SPEAKING: a jellyfish bell + trailing strands
        private const val A_HELD = 5       // STALL: a held constellation, near-static
        private const val A_BURST = 6      // RECOIL: an explosive one-shot flinch
        private const val A_SWEEP = 7      // tool web/search: a radar sweep
        private const val A_FORGE = 8      // tool shell: a bracket forge, sparking
        private const val A_NODES = 9      // tool memory: a living constellation
        private const val A_RIBBON = 10    // tool file / streaming: a serpentine ribbon
        private const val A_INFALL = 11    // tool download: light falling into the core
        private const val A_BLOOM = 12     // SETTLE: an outward bloom relaxing home
    }

    private val parts = ArrayList<P>(COUNT)
    private var state = "idle"; private var tool: String? = null
    private var workload = 0f; private var amp = 0f
    private var time = 0f; private var lastNanos = 0L
    private var seed = 1

    // ---- palette. Every state gets its OWN two-tone character; this is not a house
    // ---- style applied ten times. Base = the body's light, accent = the ~30% of
    // ---- particles that carry the second hue, so overlaps blend instead of flattening.
    private val cIdle = 0xFF5FA8C4.toInt();   private val cIdleHi = 0xFFA6E6F5.toInt()
    private val cListen = 0xFF3ED598.toInt(); private val cListenHi = 0xFFB9F6DE.toInt()
    private val cThink = 0xFFFFB43D.toInt();  private val cEmber = 0xFFFF6A2E.toInt()
    private val cSpeak = 0xFF9B6BFF.toInt();  private val cSpeakHi = 0xFFE2CCFF.toInt()
    private val cCyan = 0xFF2AC3DC.toInt();   private val cCyanHi = 0xFFA9F1FF.toInt()
    private val cWhite = 0xFFF3FBFF.toInt();  private val cFlash = 0xFFFFDCA8.toInt()
    // A held, cool blue for the waiting constellation: reads as patience, not as work.
    private val cWait = 0xFF4E7FE8.toInt();   private val cWaitHi = 0xFF93B7FF.toInt()

    // ---- paints. Allocated once; the xfermodes are the whole lighting model.
    /** ADDITIVE: overlapping light sums. This single flag is why the swarm glows. */
    private val add = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    /** The recoil shock ring — one stroke, only during the flinch. */
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG)
    /** The vignette — SRC_OVER, darkens the edges so the core reads as the source. */
    private val vig = Paint(Paint.ANTI_ALIAS_FLAG)

    // ---- sprite caches, keyed by the QUANTIZED colour. Colours are eased continuously,
    // ---- so a transition bakes a handful of sprites and then every lookup hits. Bounded,
    // ---- and never consulted inside the particle loop (base/accent are resolved to two
    // ---- Bitmap references once per frame).
    private val glowCache = HashMap<Int, Bitmap>(CACHE_CAP * 2)
    private val haloCache = HashMap<Int, Bitmap>(CACHE_CAP * 2)

    // ---- reused scratch. One RectF for every sprite blit, so the draw loop allocates
    // ---- nothing at all.
    private val dst = RectF()

    // ---- constellation node buffers. The 7 (or n) node centres are built ONCE per frame
    // ---- into these arrays and read by index per particle: O(1), no trig in the loop.
    private val nodeX = FloatArray(16)
    private val nodeY = FloatArray(16)
    private var nodeN = 7

    private var centered = false
    private var vigShader: RadialGradient? = null

    // Idle appearance: a user-picked shape/theme ("aura" = default dispersed breathing)
    // + optional auto-cycle so the being stays alive between turns.
    private var idleTheme = "aura"
    private var cycleThemes = false
    private var cycleSec = 8f

    // ---- 0.5.1: the VISUAL CATEGORY (Settings -> Visuals). Orthogonal to the idle
    // ---- theme above: the theme picks the idle SHAPE, the category picks what the
    // ---- being is made OF — palette family, light model, mass and motion character,
    // ---- in every state. See VisualStyle for the axes and why this is not a seventh
    // ---- theme label. The default is an exact identity, so an untouched install
    // ---- renders bit-for-bit what 0.5.0.3 rendered.
    private var vsty = VisualStyle.of(VisualStyle.DEFAULT)
    private var visEnergy = VisualStyle.DEFAULT_ENERGY
    private var visGlow = VisualStyle.DEFAULT_GLOW

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
    // legacy paths (preview(), setState, the Settings idle theme), which resolve their
    // drive values from [resolveDrive]'s own table instead.
    private var driven = false

    // ---- eased drive values. THESE are the flowing transition: a new motion glides the
    // ---- body's radius / energy / brightness / inward bias over ~300ms instead of
    // ---- cutting to a new curve.
    private var sRadius = 0.9f; private var sSpeed = 0.45f
    private var sBright = 0.62f; private var sOrbit = 0f
    private var curBase = cIdle; private var curAcc = cIdleHi
    private var tBase = cIdle; private var tAcc = cIdleHi

    // ---- resolved per-frame body state
    private var arch = A_ORB
    private var bodyR = 100f
    private var spin = 0f; private var spinCS = 1f; private var spinSN = 0f
    private var breath = 0f
    private var seedPh = 0f
    private var burstProg = 0f
    private var haloX = 0f; private var haloY = 0f
    private var haloW = 0f; private var haloH = 0f

    // ---- physics character for the frame (the static<->energetic dial)
    private var springK = 26f; private var flowGain = 6.5f
    private var tremor = 3.2f; private var biasX = 0f; private var biasY = 0f
    private var dampF = 0.9f; private var vmax2 = 1e9f

    // ---- phase accumulators. INTEGRATED and wrapped, never computed as time*rate:
    // ---- an unbounded clock loses float precision (after ~a day the tremor would go
    // ---- steppy), and integrating means a change of energy BENDS the phase instead of
    // ---- jumping it. Each costs one add and one compare per frame.
    private var phBreath = 0f; private var phHarm = 0f; private var phFlick = 0f
    private var phTremA = 0f;  private var phTremB = 0f; private var phFlow = 0f
    private var phRise = 0f;   private var phSweep = 0f; private var phVoice = 0f
    private var phBloom = 0f
    // Every oscillator gets its OWN accumulator rather than being derived as
    // (someWrappedPhase * rate). Multiplying a phase that wraps at TAU by a NON-integer
    // makes the argument jump by a non-multiple of TAU at the wrap — a visible pop every
    // few seconds. One add + one compare each per frame is far cheaper than that bug.
    private var phFlow2 = 0f;  private var phFlame = 0f;  private var phStrand = 0f
    private var phRibbon = 0f; private var phNode = 0f

    // ---- field output scratch (two floats; returning a Pair here was the old
    // ---- per-particle allocation)
    private var ftx = 0f; private var fty = 0f

    private val sinLut = FloatArray(LUT_N) { sin(it * (TAU / LUT_N)) }

    init {
        val r = Random(7)
        val golden = 0.6180339887f
        for (i in 0 until COUNT) {
            val p = P()
            // Golden-angle slot. A low-discrepancy sequence, so the body never bands into
            // the concentric rings i/COUNT produces. First "not mathematically even" move.
            val u = (i * golden) % 1f
            p.u = u
            val a = u * TAU
            p.hcos = cos(a); p.hsin = sin(a)
            val g = u * SPIRAL_TURNS * TAU + 0.6f
            p.gcos = cos(g); p.gsin = sin(g)
            // Core-biased radius: pow(>1) piles particles toward the middle.
            p.hr = r.nextFloat().pow(1.55f)
            p.jx = (r.nextFloat() - 0.5f) * 0.22f
            p.jy = (r.nextFloat() - 0.5f) * 0.22f
            p.sz = 0.55f + r.nextFloat().pow(2.1f) * 1.80f
            p.bri = 0.42f + r.nextFloat().pow(1.8f) * 0.88f
            p.fl = r.nextFloat() * TAU
            p.spark = r.nextFloat() < 0.14f
            p.accent = r.nextFloat() < 0.30f
            parts.add(p)
        }
        add.color = Color.WHITE                 // sprites carry their own hue; only the
        add.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)   // alpha is modulated
        ring.style = Paint.Style.STROKE
        ring.xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }

    // ---- sprite baking ------------------------------------------------------

    /**
     * A particle sprite: a white-hot centre falling off through the saturated hue to
     * nothing. One draw call per particle therefore gives BOTH the specular point and the
     * glow — the old renderer needed a sprite plus a separate core circle (640 blits/frame;
     * this is 320).
     */
    private fun glowBitmap(color: Int, px: Float, soft: Boolean): Bitmap {
        val s = px.toInt()
        val h = s / 2f
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // The category owns the LIGHT MODEL: coreHeat is how white-hot the specular
        // centre burns, edge is how tight the falloff is. Both are baked into the
        // cached sprite, so a hard 1-bit point and a wide atmospheric bloom cost the
        // same per frame as the default (one blit); only the bake differs, and the
        // bake happens on a category/colour change, never in the loop.
        val heat = ((if (soft) 0.25f else 0.88f) * vsty.coreHeat).coerceIn(0f, 1f)
        val hot = lerpColor(color, Color.WHITE, heat)
        val e = vsty.edge
        val cols: IntArray; val stops: FloatArray
        if (soft) {
            // The body's ambient bloom: very soft, so it reads as light in the air.
            cols = intArrayOf(withAlpha(hot, 150), withAlpha(color, 74),
                withAlpha(color, 26), Color.TRANSPARENT)
            stops = floatArrayOf(0f, stop(0.26f, e), stop(0.58f, e), 1f)
        } else {
            cols = intArrayOf(withAlpha(hot, 255), withAlpha(color, 214),
                withAlpha(color, 92), withAlpha(color, 22), Color.TRANSPARENT)
            stops = floatArrayOf(0f, stop(0.16f, e), stop(0.38f, e), stop(0.66f, e), 1f)
        }
        p.shader = RadialGradient(h, h, h, cols, stops, Shader.TileMode.CLAMP)
        c.drawCircle(h, h, h, p)
        return bmp
    }

    /** Quantize to 4 bits/channel: the eased colour converges to a handful of keys, so the
     *  cache stays small and every steady-state lookup hits. A 1/16 hue step is invisible
     *  through a soft additive sprite. */
    private fun qKey(color: Int): Int = (color and 0xF0F0F0) or 0xFF000000.toInt()

    private fun glowFor(color: Int): Bitmap {
        val k = qKey(color)
        glowCache[k]?.let { return it }
        if (glowCache.size >= CACHE_CAP) glowCache.clear()
        return glowBitmap(k, GLOW_PX, false).also { glowCache[k] = it }
    }

    private fun haloFor(color: Int): Bitmap {
        val k = qKey(color)
        haloCache[k]?.let { return it }
        if (haloCache.size >= CACHE_CAP) haloCache.clear()
        return glowBitmap(k, HALO_PX, true).also { haloCache[k] = it }
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

    /** 0.5.1: the visual category — the painterly family the whole being is rendered
     *  in (VisualStyle.TOKENS). Changing it re-bakes the sprites, so the caches are
     *  dropped HERE, once, off the hot path; the frame loop never learns that a
     *  category exists beyond two scalars. */
    fun setVisualCategory(token: String) {
        val next = VisualStyle.of(token)
        if (next.token == vsty.token) return
        vsty = next
        glowCache.clear(); haloCache.clear()
        invalidate()
    }
    /** User motion-energy scale (Settings slider): multiplies the category's own
     *  flow/tremor character, so a family can be pushed calmer or wilder. */
    fun setVisualEnergy(v: Float) { visEnergy = VisualStyle.energy(v); invalidate() }
    /** User glow scale (Settings slider): multiplies the ambient bloom. */
    fun setVisualGlow(v: Float) { visGlow = VisualStyle.glow(v); invalidate() }
    /** The category currently rendering (Settings/preview read-back). */
    fun visualCategory(): String = vsty.token

    // ---- LUT oscillators ----------------------------------------------------

    private fun lutIdx(v: Float): Int {
        val f = v * LUT_SCALE
        // Float->int SATURATES, so an unbounded argument would eventually pin the phase.
        // Wrapping past that point keeps it correct. Unreachable in a real session (the
        // phase accumulators below are already wrapped); two compares, and it means a
        // long-lived view can never degrade into a frozen image.
        return (if (f < 1.5e9f && f > -1.5e9f) f.toInt() else (f % LUT_N).toInt()) and LUT_MASK
    }
    private fun fsin(v: Float): Float = sinLut[lutIdx(v)]
    private fun fcos(v: Float): Float = sinLut[(lutIdx(v) + LUT_Q) and LUT_MASK]

    // ---- frame preparation --------------------------------------------------

    /**
     * Resolve the legacy (non-MotionState) paths onto the same drive values. [driven] is
     * false for preview() / setState() / the Settings idle theme, which never went through
     * applyMotion — they get equivalent numbers from here so ONE renderer serves both.
     * Writes four floats; allocates nothing.
     */
    private fun resolveDrive() {
        if (driven) {
            eRadius = params.radius; eSpeed = params.speed
            eBright = params.bright; eOrbit = params.orbit; eTheme = params.theme
            return
        }
        when (state) {
            "listening" -> { eRadius = 0.98f; eSpeed = 0.55f; eBright = 0.60f; eOrbit = 0f; eTheme = "listen" }
            "gather" -> { eRadius = 0.78f; eSpeed = 1.15f + workload * 1.9f; eBright = 0.74f + workload * 0.22f; eOrbit = -0.55f; eTheme = "think" }
            "thinking" -> { eRadius = 0.92f; eSpeed = 1.4f + workload * 2.2f; eBright = 0.78f + workload * 0.20f; eOrbit = -0.20f; eTheme = "think" }
            "speaking" -> { eRadius = 0.80f + amp * 0.34f; eSpeed = 1.0f + amp * 2.6f; eBright = 0.66f + amp * 0.34f; eOrbit = amp * 0.30f; eTheme = "speak" }
            "streaming" -> { eRadius = 0.96f; eSpeed = 1.10f; eBright = 0.72f; eOrbit = 0.10f; eTheme = "stream" }
            "waiting" -> { eRadius = 1.06f; eSpeed = 0.30f; eBright = 0.52f; eOrbit = 0.08f; eTheme = "wait" }
            "recoil" -> { eRadius = 1.30f; eSpeed = 3.6f; eBright = 0.95f; eOrbit = 0.85f; eTheme = "recoil" }
            "settle", "bloom" -> { eRadius = 0.95f; eSpeed = 0.85f; eBright = 0.68f; eOrbit = 0.25f; eTheme = "presence" }
            "drift" -> { eRadius = 1.10f; eSpeed = 0.26f; eBright = 0.56f; eOrbit = 0.05f; eTheme = "presence" }
            else -> { eRadius = 0.90f; eSpeed = 0.45f; eBright = 0.62f; eOrbit = 0f; eTheme = "presence" }
        }
    }
    private var eRadius = 0.9f; private var eSpeed = 0.45f
    private var eBright = 0.62f; private var eOrbit = 0f; private var eTheme = "presence"

    /** The palette for a theme: base + accent. Each state is itself. */
    private fun resolvePalette(theme: String) {
        when (theme) {
            "listen" -> { tBase = cListen; tAcc = cListenHi }
            "think"  -> { tBase = cThink;  tAcc = cEmber }
            "speak"  -> { tBase = lerpColor(cSpeak, cCyan, 0.30f + amp * 0.40f); tAcc = cSpeakHi }
            "wait"   -> { tBase = cWait;   tAcc = cWaitHi }
            "recoil" -> { tBase = cWhite;  tAcc = cFlash }
            "result" -> { tBase = lerpColor(cThink, cWhite, 0.55f); tAcc = cWhite }
            "stream" -> { tBase = cCyan;   tAcc = cCyanHi }
            else     -> { tBase = lerpColor(cIdle, cCyan, 0.26f + amp * 0.22f); tAcc = cIdleHi }
        }
        // The visual category re-colours the AUTHORED state palette rather than
        // replacing it: listening still reads as listening, it is just made of a
        // different substance. Two calls per frame, never per particle — and for the
        // default category both are the identity.
        tBase = VisualStyle.shade(tBase, vsty, false)
        tAcc = VisualStyle.shade(tAcc, vsty, true)
    }

    /** Which body the swarm is forming right now. Every MotionState.Motion lands on one,
     *  and the tool motifs are reached through "thinking" + [tool]. */
    private fun resolveArch(): Int {
        val st = state
        // At rest the user's chosen presence theme owns the figure (Settings -> Particles).
        if (st == "idle" || st == "settle" || st == "bloom" || st == "drift") {
            val th = if (cycleThemes) cyclingTheme(time) else idleTheme
            themeArch(th)?.let { return it }
        }
        return when (st) {
            "recoil" -> A_BURST
            "waiting" -> A_HELD
            "speaking" -> A_VOICE
            "gather" -> A_FLAME
            "listening" -> A_BREATH
            "settle", "bloom" -> A_BLOOM
            "streaming" -> A_RIBBON
            "thinking" -> when (tool) {
                "web", "search" -> A_SWEEP
                "shell" -> A_FORGE
                "memory" -> A_NODES
                "file" -> A_RIBBON
                "download", "model" -> A_INFALL
                else -> A_GYRE
            }
            else -> A_ORB     // "idle", "drift", "aura", anything unknown
        }
    }

    /** Idle theme -> archetype. null = fall through to the default dispersed cloud. */
    private fun themeArch(th: String): Int? = when (th) {
        "iris" -> A_BREATH
        "vortex" -> A_GYRE
        "waveform" -> A_VOICE
        "scan" -> A_SWEEP
        "constellation" -> A_NODES
        "bracket" -> A_FORGE
        else -> null          // "aura" / unknown -> A_ORB
    }

    /** A const array, not listOf(): this is read every idle frame and must not allocate. */
    private val cycleList = arrayOf("aura", "iris", "vortex", "waveform", "scan", "constellation")
    private fun cyclingTheme(t: Float): String = cycleList[((t / cycleSec).toInt()).mod(cycleList.size)]

    /** Advance every oscillator. Wrapped, so precision never decays over a long session. */
    private fun advancePhases(dt: Float) {
        spin += dt * sSpeed * 0.85f * spinMul
        if (spin >= TAU) spin -= TAU
        spinCS = cos(spin); spinSN = sin(spin)

        val flowRate = 0.55f + sSpeed * 0.50f
        val voiceRate = 5.5f + sSpeed * 2.5f
        phBreath = wrapTau(phBreath + dt * 1.05f)
        phHarm = wrapTau(phHarm + dt * 2.63f)
        phFlick = wrapTau(phFlick + dt * 3.10f)
        phTremA = wrapTau(phTremA + dt * 7.30f)
        phTremB = wrapTau(phTremB + dt * 6.10f)
        phFlow = wrapTau(phFlow + dt * flowRate)
        phFlow2 = wrapTau(phFlow2 + dt * flowRate * 0.63f)   // counter-rotates the field
        phFlame = wrapTau(phFlame + dt * sSpeed * 2.10f)
        phStrand = wrapTau(phStrand + dt * voiceRate * 0.80f)
        phRibbon = wrapTau(phRibbon + dt * flowRate * 1.60f)
        phSweep = wrapTau(phSweep + dt * sSpeed * 1.40f)
        phVoice = wrapTau(phVoice + dt * voiceRate)
        phBloom = wrapTau(phBloom + dt * 0.70f)
        // The constellation's own slow rotation: the held figure drifts a few degrees a
        // second, the memory motif considerably faster.
        phNode = wrapTau(phNode + dt * sSpeed * 0.85f * (if (arch == A_HELD) 0.25f else 0.90f))
        // Travel phase, wrapped to [0,1): the flame's rise, the ribbon's run, the infall.
        phRise += dt * sSpeed * 0.20f
        if (phRise >= 1f) phRise -= 1f
    }
    private fun wrapTau(v: Float): Float = if (v >= TAU) v - TAU else v

    /**
     * The per-frame setup: ease the drive, pick the body, tune the physics, resolve the
     * two sprite colours. ALL of it once per frame — the particle loop reads only scalars.
     */
    private fun prepareFrame(dt: Float) {
        driven = params.shape == state
        resolveDrive()

        val k = (dt * 3.6f).coerceIn(0f, 1f)
        sRadius += (eRadius - sRadius) * k
        sSpeed += (eSpeed - sSpeed) * k
        sBright += (eBright - sBright) * k
        sOrbit += (eOrbit - sOrbit) * k

        arch = resolveArch()

        bodyR = R * sRadius
        burstProg = if (arch == A_BURST)
            ((time - recoilAt) * (1000f / MotionState.RECOIL_MS)).coerceIn(0f, 1f) else 0f
        // The largest circle that fits the field. The being's light is contained by it, so
        // a violent transition can never throw a particle off-screen (the failure mode the
        // pre-facelift integrator had). Device-adaptive: it is the real view, not a guess.
        safeR = minOf(minOf(cx, width - cx), minOf(cy, height - cy)) * 0.94f
        safeR2 = safeR * safeR

        // tunePhysics BEFORE advancePhases: it owns spinMul, the per-body rotation rate.
        tunePhysics()
        advancePhases(dt)
        breath = fsin(phBreath)
        // onTool / pulseTool re-seed: a new seed rotates the whole figure, so every tool
        // call is visibly a DIFFERENT creature rather than the same one resumed.
        seedPh = (seed * 1.37f) % TAU

        resolvePalette(eTheme)
        val ck = (dt * 2.6f).coerceIn(0f, 1f)
        curBase = lerpColor(curBase, tBase, ck)
        curAcc = lerpColor(curAcc, tAcc, ck)

        buildNodesIfNeeded()

        // The halo follows the body's mass, and is stretched for the bodies that are not
        // round — the flame's bloom is taller than it is wide.
        haloX = cx; haloY = cy
        val g = bodyR * (1.55f + 0.10f * breath)
        haloW = g; haloH = g
        when (arch) {
            A_FLAME -> { haloY = cy + bodyR * 0.10f; haloW = bodyR * 1.15f; haloH = bodyR * 1.95f }
            A_RIBBON -> { haloW = bodyR * 2.05f; haloH = bodyR * 0.95f }
            A_INFALL -> { haloW = bodyR * 1.05f; haloH = bodyR * 1.85f }
            A_VOICE -> { haloW = bodyR * 1.45f; haloH = bodyR * 1.55f }
            A_BURST -> { val e = 1f + burstProg * 0.9f; haloW = g * e; haloH = g * e }
            else -> {}
        }
    }

    /**
     * THE STATIC <-> ENERGETIC DIAL. [springK] binds a particle to the body; [flowGain]
     * lets the shared field tear it loose. Their ratio is the whole feel of a state, and
     * it is set here in one place so the spectrum can be re-tuned without touching a
     * single shape function.
     */
    private fun tunePhysics() {
        biasX = 0f; biasY = 0f
        when (arch) {
            // Near-still: the being is receptive, not busy. The stillest thing it does —
            // the body rotation is cut to 0.40 so listening barely turns over.
            A_BREATH -> { springK = 30f; flowGain = 4.5f; tremor = 2.0f; spinMul = 0.40f }
            // Held: patience. Almost frozen, but the tremor keeps it alive — a still
            // state that is completely motionless reads as a crashed app.
            A_HELD -> { springK = 36f; flowGain = 2.2f; tremor = 1.2f; spinMul = 0.15f }
            // At rest: a slow dispersed cloud.
            A_ORB -> { springK = 26f; flowGain = 6.5f; tremor = 3.2f; spinMul = 0.60f }
            A_BLOOM -> { springK = 24f; flowGain = 9.0f; tremor = 3.5f; spinMul = 0.50f }
            // Energetic: fire. Strong updraft, violent turbulence — the flow field does
            // the work, so the flame flickers differently every time.
            A_FLAME -> { springK = 30f; flowGain = 26f; tremor = 9.0f; spinMul = 1.00f; biasY = -bodyR * 1.55f }
            A_GYRE -> { springK = 27f; flowGain = 17f; tremor = 6.0f; spinMul = 1.60f }
            // Voice-coupled: silent = a still bell; loud = the field tears it apart.
            // This is the amp lock, expressed as physics rather than as a scale factor.
            A_VOICE -> { springK = 33f; flowGain = 7f + amp * 34f; tremor = 3f + amp * 11f; spinMul = 0.80f }
            // Explosive: the flinch. Fastest, brightest, briefest.
            A_BURST -> { springK = 48f; flowGain = 34f; tremor = 16f; spinMul = 1.20f }
            A_SWEEP -> { springK = 30f; flowGain = 14f; tremor = 5.0f; spinMul = 1.00f }
            // A looser spring than the other tool motifs: the sparks have to actually fly,
            // or the bracket reads as a stencil.
            A_FORGE -> { springK = 26f; flowGain = 22f; tremor = 17f; spinMul = 0.70f }
            A_NODES -> { springK = 32f; flowGain = 8.0f; tremor = 4.0f; spinMul = 0.90f }
            A_RIBBON -> { springK = 30f; flowGain = 13f; tremor = 6.0f; spinMul = 0.50f }
            A_INFALL -> { springK = 28f; flowGain = 10f; tremor = 5.0f; spinMul = 0.60f; biasY = bodyR * 1.25f }
            else -> { springK = 26f; flowGain = 6.5f; tremor = 3.2f; spinMul = 0.60f }
        }
        // The category's motion character (x the user's energy slider). It scales the
        // two forces that fight the spring, so a family is calmer or wilder in EVERY
        // state including the still ones — and the spring itself is untouched, so the
        // silhouette a state is trying to make never dissolves. Two multiplies per
        // frame; the spin is pulled only part-way so a low-energy body still turns.
        val eg = vsty.energy * visEnergy
        flowGain *= eg
        tremor *= eg
        spinMul *= (0.60f + 0.40f * eg)
        // UNDER-damped on purpose (0.9 of critical): the swirl has to survive, or the flow
        // field's contribution is dissipated before it can look like anything. Still far
        // inside the stability limit (dt <= 0.05, sqrt(48) ~ 6.9, so dt*omega ~ 0.35).
        dampF = 1f / (1f + 0.9f * sqrt(springK) * dtFrame)
        // Velocity ceiling. bodyR*6/s is already faster than the flinch needs to travel
        // (0.9R in 350ms ~ 2.6R/s); a looser ceiling lets a state change overshoot the
        // field, which is exactly how particles used to end up off-screen.
        val vm = bodyR * 6f
        vmax2 = vm * vm
    }
    private var dtFrame = 0.016f
    private var spinMul = 0.6f
    private var safeR = 200f; private var safeR2 = 40000f

    /** Constellation centres, built ONCE per frame into reused buffers. */
    private fun buildNodesIfNeeded() {
        if (arch != A_HELD && arch != A_NODES) return
        val n = if (arch == A_HELD) 7 else ((seed % 6) + 5)
        nodeN = n
        val spread = if (arch == A_HELD)
            R * 0.055f * (1f + minOf(2f, stallMs / 6000f))   // a long wait visibly opens up
        else R * 0.075f
        val inv = TAU / n
        for (i in 0 until n) {
            val f = i.toFloat()
            val a = f * inv + hash(f, seed, 5) * 1.7f + phNode + seedPh
            val rr = bodyR * (0.46f + hash(f, seed, 6) * 0.50f) *
                    (1f + 0.06f * fsin(phBreath + f))
            nodeX[i] = cx + fcos(a) * rr
            nodeY[i] = cy + fsin(a) * rr * 0.94f
        }
        nodeSpread = spread
    }
    private var nodeSpread = 20f

    // ---- the body field -----------------------------------------------------

    /**
     * Where the swarm is TRYING to be. A volumetric region, never an outline: each
     * particle has its own place inside the volume (from [P.u], [P.hr], [P.hcos]), and the
     * flow field plus tremor keep it wandering within that volume, so the silhouette is
     * emergent. Writes [ftx]/[fty] — no Pair, no allocation.
     */
    private fun field(p: P) {
        val br = bodyR
        when (arch) {
            A_ORB -> {
                // A dispersed breathing cloud. Core-biased hr + the fixed jx/jy offsets
                // mean it is denser in the middle and lopsided — never a even disc.
                val rr = br * (0.20f + 0.86f * p.hr) *
                        (1f + 0.085f * breath + 0.035f * fsin(phHarm + p.fl))
                ftx = cx + (p.hcos * spinCS - p.hsin * spinSN) * rr + p.jx * br
                fty = cy + (p.hcos * spinSN + p.hsin * spinCS) * rr * 0.92f + p.jy * br
            }
            A_BREATH -> {
                // LISTENING: a rounder, tighter shell on a slow ~6s inhale. Receptive.
                val inhale = 1f + 0.085f * breath
                val rr = br * (0.15f + 0.80f * p.hr) * inhale
                ftx = cx + (p.hcos * spinCS - p.hsin * spinSN) * rr + p.jx * br * 0.5f
                fty = cy + (p.hcos * spinSN + p.hsin * spinCS) * rr * 0.96f + p.jy * br * 0.5f
            }
            A_FLAME -> {
                // THINKING / gather: FIRE. phRise makes every particle climb from the base
                // to the tip and wrap, so the flame is a continuous renewal rather than a
                // static teardrop. sOrbit < 0 pinches it inward — concentration.
                val h = p.u + phRise
                val hh = if (h >= 1f) h - 1f else h          // one compare, no floor
                val taper = 1f - hh * 0.78f
                val pinch = 1f + sOrbit * 0.40f * (0.5f + 0.5f * breath)
                val sway = fsin(phFlame + hh * 6.0f + p.fl) * (0.05f + 0.24f * hh)
                val spread = br * 0.50f * taper * (0.22f + p.hr) * pinch
                ftx = cx + p.hcos * spread + sway * br
                fty = cy + br * 0.60f - hh * br * 1.60f + p.hsin * br * 0.14f * taper
            }
            A_GYRE -> {
                // A log-spiral gyre. gcos/gsin carry the 2-turn arm angle, precomputed, so
                // the rotation is 4 mults. Effort ramps the spin through sSpeed.
                val rr = br * (0.14f + 0.82f * p.u) * (1f + 0.06f * fsin(phHarm + p.u * 9f))
                ftx = cx + (p.gcos * spinCS - p.gsin * spinSN) * rr
                fty = cy + (p.gcos * spinSN + p.gsin * spinCS) * rr * 0.96f
            }
            A_VOICE -> {
                // SPEAKING: a JELLYFISH. The bell pulses with the real RMS; ~28% of the
                // swarm (the outermost hr) trails below it as strands that whip harder the
                // louder the voice. Silent = a still bell. Loud = it dances.
                if (p.hr < 0.72f) {
                    val pulse = 1f + 0.20f * amp * fsin(phVoice + p.fl)
                    val rr = br * (0.44f + 0.56f * (p.hr / 0.72f)) * pulse
                    ftx = cx + (p.hcos * spinCS - p.hsin * spinSN) * rr
                    fty = cy + (p.hcos * spinSN + p.hsin * spinCS) * rr * 0.78f - br * 0.12f
                } else {
                    val s = (p.hr - 0.72f) / 0.28f
                    val wave = fsin(phStrand + p.u * 9f) * br * 0.17f * (0.3f + s)
                    ftx = cx + p.hcos * br * 0.52f + wave
                    fty = cy + br * 0.16f + s * br * (0.52f + amp * 0.55f)
                }
            }
            A_HELD -> {
                // STALL: the held constellation. Seven seeded nodes that stay put for the
                // whole stall — one figure WAITING, not work and not sleep. Each node is a
                // small luminous cluster, dense in its own middle.
                val n = (p.u * nodeN).toInt().coerceAtMost(nodeN - 1)
                val sp = nodeSpread * (0.35f + p.hr * 1.5f)
                ftx = nodeX[n] + p.hcos * sp
                fty = nodeY[n] + p.hsin * sp
            }
            A_NODES -> {
                // tool memory: the same constellation idea, but alive — more nodes, faster
                // spin, and each node breathes.
                val n = (p.u * nodeN).toInt().coerceAtMost(nodeN - 1)
                val sp = nodeSpread * (0.30f + p.hr * 1.7f) * (1f + 0.10f * breath)
                ftx = nodeX[n] + p.hcos * sp
                fty = nodeY[n] + p.hsin * sp
            }
            A_BURST -> {
                // RECOIL: a single outward gesture on its own clock. Fast-out,
                // decelerating, so it has a beginning and an end instead of looping.
                val ease = 1f - (1f - burstProg) * (1f - burstProg)
                val rr = br * (0.30f + 0.62f * ease) * (0.82f + p.hr * 0.24f)
                ftx = cx + (p.hcos * spinCS - p.hsin * spinSN) * rr
                // Squashed vertically: the field is wider than it is tall, so a round
                // flinch would clip top and bottom before it clipped the sides.
                fty = cy + (p.hcos * spinSN + p.hsin * spinCS) * rr * 0.88f
            }
            A_SWEEP -> {
                // tool web/search: a radar sweep. Particles fan behind the arm and the
                // radius runs outward, so it reads as scanning, not as a ring.
                val ang = p.u * TAU * 0.55f + phSweep + seedPh
                val rr = br * (0.24f + 0.76f * frac(p.u * 3f + phRise))
                ftx = cx + fcos(ang) * rr
                fty = cy + fsin(ang) * rr * 0.90f
            }
            A_FORGE -> {
                // tool shell: a bracket forge — two bars of light with a sparking core
                // between them. The bars SHIMMER along their length and the core churns
                // and turns, so the frame is a terminal being written into rather than a
                // static outline; the high tremor and loose spring are the sparks.
                val seg = (p.u * 4f).toInt()
                val f = frac(p.u * 4f)
                val shim = fsin(f * 11f + phFlame) * br * 0.045f
                when (seg) {
                    0 -> { ftx = cx - br * 0.86f + shim; fty = cy - br + f * 2f * br }
                    1 -> { ftx = cx + br * 0.86f - shim; fty = cy - br + f * 2f * br }
                    2 -> { ftx = cx - br * 0.86f + f * 1.72f * br; fty = cy - br * 0.92f + shim }
                    else -> {
                        val churn = 1f + 0.34f * fsin(phFlame + p.fl)
                        val rr = br * 0.30f * (0.35f + p.hr) * churn
                        ftx = cx + (p.hcos * spinCS - p.hsin * spinSN) * rr
                        fty = cy + (p.hcos * spinSN + p.hsin * spinCS) * rr
                    }
                }
            }
            A_RIBBON -> {
                // tool file / streaming: a serpentine ribbon of light running left to
                // right, tapering as it goes — the reply being written.
                val s = frac(p.u + phRise)
                ftx = cx - br * 0.95f + s * br * 1.9f
                fty = cy + fsin(s * 7f + phRibbon) * br * 0.42f * (1f - s * 0.35f) +
                        p.hsin * br * 0.07f
            }
            A_INFALL -> {
                // tool download: light falling into the core through a narrowing funnel.
                val f = p.u + phRise
                val ff = if (f >= 1f) f - 1f else f
                val w = br * 0.58f * (1f - ff * 0.62f)
                ftx = cx + p.hcos * w
                fty = cy - br * 1.05f + ff * br * 1.55f
            }
            else -> {
                // A_BLOOM (SETTLE): breathes outward and back, relaxing toward the rest
                // state. Never a repeating burst — a single slow exhalation.
                val b = 0.5f - 0.5f * fcos(phBloom)
                val rr = br * (0.24f + 0.86f * b) * (0.45f + p.hr * 0.75f)
                ftx = cx + (p.hcos * spinCS - p.hsin * spinSN) * rr + p.jx * br
                fty = cy + (p.hcos * spinSN + p.hsin * spinCS) * rr * 0.93f + p.jy * br
            }
        }
    }

    // ---- physics + draw -----------------------------------------------------

    private fun tick(dt: Float) {
        time += dt
        dtFrame = dt
        prepareFrame(dt)

        val invR = 1f / bodyR
        // Frame scalars hoisted into locals: the loop below touches no property but the
        // particle's own, which keeps it register-friendly.
        val flA = phFlow
        val flB = phFlow2
        val trA = phTremA; val trB = phTremB
        val damp = dampF
        val sk = springK; val fg = flowGain; val tm = tremor
        val bx = biasX; val by = biasY
        val vm2 = vmax2
        val cxf = cx; val cyf = cy
        val sr = safeR; val sr2 = safeR2
        // The category's two per-particle scalars, hoisted like every other frame
        // constant: the loop gains exactly two multiplies against values it was
        // already computing, and no branch.
        val fk = 0.17f * vsty.flicker
        val szk = vsty.size

        for (i in 0 until COUNT) {
            val p = parts[i]

            // 1. where the body wants this particle
            field(p)

            // 2. soft spring toward it. SOFT is the point: the particle never arrives, so
            //    it is never ON a curve.
            var ax = (ftx - p.x) * sk
            var ay = (fty - p.y) * sk

            // 3. the flow field. A cheap curl-ish swirl (the acceleration is taken
            //    PERPENDICULAR to the field gradient, so it advects instead of
            //    compressing). Because every particle samples the SAME field, neighbours
            //    move together — this is the physical coupling the old renderer lacked,
            //    and it is why the swarm reads as one substance rather than 320 points.
            val nx = (p.x - cxf) * invR
            val ny = (p.y - cyf) * invR
            val f1 = fsin(ny * 2.7f + flA) + 0.55f * fcos(nx * 1.9f - flB)
            val f2 = fcos(nx * 2.7f - flA) + 0.55f * fsin(ny * 1.9f + flB)
            ax -= f2 * fg
            ay += f1 * fg

            // 4. the body's own bias (updraft for fire, infall for download)
            ax += bx; ay += by

            // 5. tremor: smooth per-particle jitter from the LUT, so there is no Random
            //    call and no allocation in the hot loop, and the motion is a shimmer
            //    rather than white noise.
            ax += fsin(trA + p.fl * 3.1f) * tm
            ay += fcos(trB + p.fl * 2.3f) * tm

            // 5b. soft containment. The being's light never leaves its own field: past
            //     [safeR] a restoring force bends it back. Off the common path (it only
            //     fires when a violent transition has actually carried a particle out),
            //     so the sqrt costs nothing in the steady state — and it is the reason a
            //     flinch can be explosive without clipping against the view edge.
            val ddx = p.x - cxf; val ddy = p.y - cyf
            val d2 = ddx * ddx + ddy * ddy
            if (d2 > sr2) {
                val d = sqrt(d2)
                val push = (d - sr) * 46f
                ax -= ddx / d * push
                ay -= ddy / d * push
            }

            // 6. integrate (semi-implicit Euler), under-damped, velocity-clamped.
            p.vx = (p.vx + ax * dt) * damp
            p.vy = (p.vy + ay * dt) * damp
            val s2 = p.vx * p.vx + p.vy * p.vy
            if (s2 > vm2) {                       // rare: only on a violent transition
                val sc = sqrt(vm2 / s2)
                p.vx *= sc; p.vy *= sc
            }
            p.x += p.vx * dt
            p.y += p.vy * dt

            // 7. brightness + size. The flicker is per-particle and never stops, so a
            //    still state is never a frozen blob.
            val flick = 1f + fk * fsin(phFlick + p.fl * 3.1f) * (0.4f + p.bri)
            val boost = when (arch) {
                A_BURST -> (1f - burstProg) * (1f - burstProg) * 0.8f + 0.55f
                A_VOICE -> 0.74f + amp * 0.44f
                A_FLAME -> 0.82f + workload * 0.28f + (1f - p.u) * 0.10f
                else -> 1f
            }
            // Embers run hot: a small sprite at saturated alpha, so the swarm has
            // specular points instead of a uniform mush.
            val ta = (sBright * p.bri * flick * boost * (if (p.spark) 1.55f else 1f))
                .coerceIn(0.05f, 1f)
            p.alpha += (ta - p.alpha) * 0.14f
            p.size = (if (p.spark) 1.5f else 3.1f) * p.sz * szk *
                    (1f + workload * 0.22f + (sBright - 0.6f) * 0.45f)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        vigShader = null
        if (w > 0 && h > 0 && !centered) seedField()
    }

    /** Start as a loose cloud at the centre and let the swarm FIND its body — the first
     *  half-second is the being gathering itself, which is the right read on launch. */
    private fun seedField() {
        val r = Random(7)
        val spread = minOf(width, height) * 0.22f
        for (i in 0 until COUNT) {
            val p = parts[i]
            p.x = cx + (r.nextFloat() - 0.5f) * spread
            p.y = cy + (r.nextFloat() - 0.5f) * spread
            p.vx = 0f; p.vy = 0f
        }
        centered = true
    }

    override fun onDraw(canvas: Canvas) {
        if (!centered && width > 0 && height > 0) seedField()
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1e9f).coerceIn(0.001f, 0.05f)
        lastNanos = now
        tick(dt)

        // Two sprite references resolved ONCE per frame — the particle loop below does no
        // cache lookup, no colour math and no allocation at all.
        val glowBas = glowFor(curBase)
        val glowAcc = glowFor(curAcc)

        // 1. the body's ambient bloom: one soft sprite behind everything. This is the
        //    bright core / dark falloff that makes it read as LIGHT in the air.
        val halo = haloFor(curBase)
        dst.set(haloX - haloW, haloY - haloH, haloX + haloW, haloY + haloH)
        add.alpha = ((0.16f + sBright * 0.34f) * vsty.halo * visGlow * 255f)
            .toInt().coerceIn(0, 255)
        canvas.drawBitmap(halo, null, dst, add)

        // 2. the swarm. ADDITIVE, so where particles overlap the light sums and the dense
        //    middle of the body glows hotter than its edges — density becomes brightness
        //    for free.
        // The ONE category axis that is not free: a trail sprite behind each particle
        // doubles the blit count, so it is read once into a local, it is opt-in, and
        // its Settings label says "heavier". Every other category draws exactly what
        // the default draws.
        val trails = vsty.trails
        for (i in 0 until COUNT) {
            val p = parts[i]
            val g = if (p.accent) glowAcc else glowBas
            val h = p.size * (if (p.spark) GLOW_SPAN * 0.62f else GLOW_SPAN) * 0.5f
            if (trails) {
                // Where it WAS one trail-step ago, dimmer and smaller: the particle
                // drags its own recent past. Uses the velocity the integrator already
                // produced, so there is no per-particle history to store.
                val th = h * 0.70f
                val tx = p.x - p.vx * TRAIL_DT
                val ty = p.y - p.vy * TRAIL_DT
                dst.set(tx - th, ty - th, tx + th, ty + th)
                add.alpha = (p.alpha * 0.42f * 255f).toInt().coerceIn(0, 255)
                canvas.drawBitmap(g, null, dst, add)
            }
            dst.set(p.x - h, p.y - h, p.x + h, p.y + h)
            add.alpha = (p.alpha * 255f).toInt().coerceIn(0, 255)
            canvas.drawBitmap(g, null, dst, add)
        }

        // 3. the recoil shock ring — one stroke, only during the flinch.
        if (arch == A_BURST && burstProg < 1f) {
            val fade = (1f - burstProg) * (1f - burstProg)
            ring.color = curBase
            ring.alpha = (fade * 190f).toInt().coerceIn(0, 255)
            ring.strokeWidth = bodyR * 0.07f * fade + 1f
            canvas.drawCircle(cx, cy, bodyR * (0.40f + 1.55f * burstProg), ring)
        }

        // 4. vignette: closes the edges so the core reads as the source of the light.
        //    The shader is built once per size change, never per frame.
        var vs = vigShader
        if (vs == null) {
            vs = RadialGradient(cx, cy, maxOf(width, height) * 0.62f,
                intArrayOf(Color.TRANSPARENT, Color.TRANSPARENT, 0x59000000),
                floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP)
            vigShader = vs; vig.shader = vs
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), vig)

        // always animate
        postInvalidateOnAnimation()
    }

    // ---- small pure helpers (no allocation) ---------------------------------

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
    private fun withAlpha(c: Int, a: Int): Int =
        Color.argb(a.coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))
    private fun frac(x: Float): Float = x - floor(x)
    /** A gradient stop scaled by the category's [VisualStyle.Style.edge], kept
     *  strictly inside (0,1) so the stop array stays monotonic for any edge value. */
    private fun stop(v: Float, e: Float): Float = (v * e).coerceIn(0.01f, 0.98f)
}
