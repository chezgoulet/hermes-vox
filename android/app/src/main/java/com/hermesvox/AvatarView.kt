package com.hermesvox

import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.TextureView
import android.view.View
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.sin

/**
 * AvatarView — the being of Hermes Vox (0.8: the GPU being).
 *
 * WHY IT WAS REBUILT
 * ------------------
 * The 0.5-0.7 being was 320 soft sprites on springs, blitted by the CPU canvas. With that few
 * points a shape can only be implied, never drawn: a ring, a lid, a link or a tentacle dissolved
 * into drifting dots, and most of the twenty shapes did not read as their names (the facelift
 * audit's contact sheet: flame a blob, radar fragments, sphere a flat cloud). The limit was the
 * medium, so the medium changed.
 *
 * HOW IT WORKS NOW
 * ----------------
 * The rendering is OpenGL ES 3 on its own thread ([BeingThread] / [BeingRenderer]); the shapes
 * are GLSL ([BeingShaders]). Each of 6,000 particles is stateless: its position is computed in
 * the vertex shader each frame from its identity and its shape's exact geometry — curves,
 * surfaces, 3D rotation with depth shading — so a shape is precise and every one can carry fine
 * structure. A shape change is a staggered per-particle morph with a swirl through the middle.
 * Light is additive, then bloomed at two scales, then tone-mapped (hot cores roll off instead of
 * clipping), with an ambient halo and a vignette; categories that ask for trails get real
 * frame-feedback motion trails.
 *
 * WHAT STAYED
 * -----------
 * This class is still the being's BRAIN, unchanged in contract: state + tool -> shape
 * (resolveArch, with the user's per-state picks), the authored per-state palettes shaded by the
 * visual category (VisualStyle) and eased so nothing cuts, the drive values from MotionState,
 * the recoil and stall levers, and the per-shape state machines that need memory (the eye's
 * saccades and blinks, the octopus's wander/fixate/move-on). Every public entry MainActivity,
 * OnboardingActivity and SettingsActivity call keeps its signature. Each vsync (capped at
 * 60 fps) it publishes one small [BeingFrame]; it never draws on the main thread.
 */
class AvatarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    /**
     * Clips the being's black surface to a shape. Invisible on the OLED-black theme; on the
     * light theme it turns the surface into a portal (circle) or a rounded window.
     * [cornerDp] < 0 = a circle; otherwise a rounded rectangle with that radius.
     */
    fun setPortalShape(cornerDp: Float) {
        val r = cornerDp * resources.displayMetrics.density
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                if (cornerDp < 0f) outline.setOval(0, 0, view.width, view.height)
                else outline.setRoundRect(0, 0, view.width, view.height, r)
            }
        }
        clipToOutline = true
    }

    companion object {
        /** Kept for API parity (older call sites list these as the presence themes). */
        val SHAPES = listOf("iris", "listening", "vortex", "scan", "bracket",
            "constellation", "lumen", "waveform", "bloom",
            "soundwave", "arc", "nucleus", "eye", "water", "radar", "octopus", "sphere")

        // The SHAPE vocabulary: the single source of truth for every shape picker in Settings
        // (the presence-shape picker and the three state pickers). themeArch() maps the same
        // tokens, so the pickers and the rendered shapes can never drift apart. Tokens are
        // persisted in prefs, so an old token keeps its meaning even where its label improved
        // ("waveform" is the jellyfish, "arc" the lightning, "water" the ripples...).
        val SHAPE_LABELS = arrayOf("Aura", "Iris", "Vortex (galaxy)", "Jellyfish", "Globe scan", "Constellation",
            "Terminal", "Flame", "Ribbon", "Black hole", "Bloom", "Soundwave", "Lightning", "Nucleus",
            "Eye", "Ripples", "Radar", "Octopus", "Sphere",
            "Helix", "Knot", "Aurora", "Harmonograph", "Tesseract", "Mandala", "Butterfly", "Hourglass")
        val SHAPE_TOKENS = arrayOf("aura", "iris", "vortex", "waveform", "scan", "constellation",
            "bracket", "flame", "ribbon", "infall", "bloom", "soundwave", "arc", "nucleus",
            "eye", "water", "radar", "octopus", "sphere",
            "helix", "knot", "aurora", "harmonograph", "tesseract", "mandala", "butterfly", "hourglass")

        /** Per-state shape tokens (Settings -> Visuals). An ACTIVE state renders as the shape the
         *  user picked for it, defaulting to the semantic fits below. */
        const val KEY_SHAPE_SPEAKING = "visual_shape_speaking"
        const val KEY_SHAPE_LISTENING = "visual_shape_listening"
        const val KEY_SHAPE_THINKING = "visual_shape_thinking"
        const val DEFAULT_SHAPE_SPEAKING = "soundwave"
        const val DEFAULT_SHAPE_LISTENING = "eye"
        const val DEFAULT_SHAPE_THINKING = "radar"

        private const val TAU = (PI * 2).toFloat()
        /** Frame budget: 60 fps on any display (a 120 Hz panel draws every other vsync). */
        private const val FRAME_NS = 16_600_000L
        /** A morph's length; the recoil flinch morphs far faster. */
        private const val MORPH_SEC = 1.1f
        private const val MORPH_FAST_SEC = 0.28f

        // ---- archetypes: ids shared with BeingShaders' shape() switch. Never renumber.
        private const val A_ORB = 0        // at rest: a breathing nebula
        private const val A_BREATH = 1     // LISTENING: an iris
        private const val A_FLAME = 2      // THINKING/gather: a flame
        private const val A_GYRE = 3       // vortex: a spiral galaxy (tool, no motif)
        private const val A_VOICE = 4      // the jellyfish ("waveform" token)
        private const val A_HELD = 5       // STALL / waiting: an hourglass
        private const val A_BURST = 6      // RECOIL: a shockwave
        private const val A_SWEEP = 7      // tool web/search: a globe under a scan band
        private const val A_FORGE = 8      // tool shell: a terminal typing
        private const val A_NODES = 9      // tool memory: a constellation
        private const val A_RIBBON = 10    // tool file / streaming: a twisting ribbon
        private const val A_INFALL = 11    // tool download: a black hole
        private const val A_BLOOM = 12     // SETTLE: a flower
        private const val A_WAVE = 13      // SPEAKING: a soundwave spectrum
        private const val A_ARC = 14       // lightning
        private const val A_NUCLEUS = 15   // an atom
        private const val A_SEEKER = 16    // an eye
        private const val A_BORE = 17      // ripples
        private const val A_RADAR = 18     // radar
        private const val A_TAKU = 19      // an octopus
        private const val A_SPHERE = 20    // a lit sphere
        private const val A_HELIX = 21
        private const val A_KNOT = 22
        private const val A_AURORA = 23
        private const val A_HARMONO = 24
        private const val A_TESSERACT = 25
        private const val A_MANDALA = 26
        private const val A_BUTTERFLY = 27
    }

    // ---- render plumbing ---------------------------------------------------------------------
    private var thread: BeingThread? = null
    private val frame = BeingFrame()
    private var lastFrameNs = 0L
    private var lastNanos = 0L
    private var running = false
    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val now = System.nanoTime()
            if (now - lastFrameNs >= FRAME_NS - 2_000_000L) { lastFrameNs = now; renderFrame(now) }
            postOnAnimation(this)
        }
    }

    init {
        surfaceTextureListener = this
        isOpaque = true
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        thread = BeingThread(st).also { it.resize(w, h); it.start() }
        running = true
        lastNanos = 0L
        postOnAnimation(ticker)
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { thread?.resize(w, h) }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        running = false
        removeCallbacks(ticker)
        thread?.let { it.finish(); try { it.join(500) } catch (_: InterruptedException) {} }
        thread = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    /** Render only while the being can be seen: a backgrounded app (or a hidden onboarding
     *  step) must not keep a 60 fps GPU pipeline running and draining the battery. */
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        val want = isVisible && thread != null
        if (want == running) return
        running = want
        removeCallbacks(ticker)
        if (want) { lastNanos = 0L; postOnAnimation(ticker) }
    }

    // ---- brain state ------------------------------------------------------------------------
    private var state = "idle"; private var tool: String? = null
    private var workload = 0f; private var amp = 0f
    private var time = 0f
    private var seed = 1

    // Every state gets its OWN two-tone character (base + accent).
    private val cIdle = 0xFF5FA8C4.toInt();   private val cIdleHi = 0xFFA6E6F5.toInt()
    private val cListen = 0xFF3ED598.toInt(); private val cListenHi = 0xFFB9F6DE.toInt()
    private val cThink = 0xFFFFB43D.toInt();  private val cEmber = 0xFFFF6A2E.toInt()
    private val cSpeak = 0xFF9B6BFF.toInt();  private val cSpeakHi = 0xFFE2CCFF.toInt()
    private val cCyan = 0xFF2AC3DC.toInt();   private val cCyanHi = 0xFFA9F1FF.toInt()
    private val cWhite = 0xFFF3FBFF.toInt();  private val cFlash = 0xFFFFDCA8.toInt()
    private val cWait = 0xFF4E7FE8.toInt();   private val cWaitHi = 0xFF93B7FF.toInt()

    // Idle appearance: the user's shape + optional auto-cycle.
    private var idleTheme = "aura"
    private var cycleThemes = false
    private var cycleSec = 8f

    // The three ACTIVE-state shape picks, cached (fed by MainActivity.applyParticlePrefs).
    private var stateShapeSpeaking = DEFAULT_SHAPE_SPEAKING
    private var stateShapeListening = DEFAULT_SHAPE_LISTENING
    private var stateShapeThinking = DEFAULT_SHAPE_THINKING

    // The visual category (Settings -> Visuals) and its eased render-style.
    private var vsty = VisualStyle.of(VisualStyle.DEFAULT)
    private var visEnergy = VisualStyle.DEFAULT_ENERGY
    private var visGlow = VisualStyle.DEFAULT_GLOW
    private var userCat = VisualStyle.DEFAULT
    private var cycleAll = false
    private var cycleIdx = 0
    private var cycleAccum = 0f
    private var rTintAmt = 0f;  private var rSat = 1f;     private var rAccentTurn = 0f
    private var rCoreHeat = 1f; private var rEdge = 1f
    private var rHalo = 1f;     private var rSize = 1f;    private var rFlicker = 1f
    private var rEnergy = 1f;   private var rTint = 0
    private var rTrails = 0f

    // MotionState drive
    private var motion = MotionState.Motion.IDLE
    private var params = MotionState.renderParams(MotionState.Motion.IDLE, 0f, 0f)
    private var priorMotion = MotionState.Motion.IDLE
    private var stalled = false
    private var stallMs = 0L
    private var recoilAt = 0f
    private var driven = false

    // eased drive values + palette
    private var sRadius = 0.9f; private var sSpeed = 0.45f
    private var sBright = 0.62f
    private var curBase = cIdle; private var curAcc = cIdleHi
    private var tBase = cIdle; private var tAcc = cIdleHi
    private var eRadius = 0.9f; private var eSpeed = 0.45f
    private var eBright = 0.62f; private var eTheme = "presence"

    // shape morph: from -> to by mix; a change that arrives mid-morph waits its turn
    private var archFrom = A_ORB; private var archTo = A_ORB; private var morph = 1f
    private var archPending = -1

    // phases
    private var spin = 0f
    private var phBreath = 0f

    // eye: pupil + blink
    private var pupX = 0f; private var pupY = 0f
    private var blinkEnv = 1f

    // octopus transport state machine (WANDER / FIXATE / MOVE-ON)
    private val T_PH_WANDER = 0
    private val T_PH_FIXATE = 1
    private val T_PH_MOVEO = 2
    private var takuPhase = T_PH_WANDER
    private var takuT = 0f
    private var ox = 0f; private var oy = 0f
    private var tox = 0f; private var toy = 0f
    private var hAng = -PI.toFloat() / 2f; private var thAng = -PI.toFloat() / 2f
    private var armPh = 0f; private var armBoost = 0.15f
    private var lastWand = -1
    private var lastFixateB = -100

    private val cx get() = width / 2f; private val cy get() = height / 2f
    private val R get() = (minOf(width, height) * 0.32f).coerceAtLeast(60f)

    // ---- public API (unchanged contract) ---------------------------------------------------

    fun setPresent(state: String, tool: String?, workload: Float, amp: Float) {
        this.state = state.lowercase()
        this.tool = tool
        this.workload = workload.coerceIn(0f, 1f)
        this.amp = amp.coerceIn(0f, 1f)
    }

    /** LIVE tool-call hook: the being takes the tool's motif and ramps with the workload.
     *  Re-seeds the generative shape so each call is distinct. */
    fun onTool(tool: String?, workload: Float) {
        this.tool = tool
        this.state = "thinking"
        this.workload = workload.coerceIn(0f, 1f)
        seed++
    }

    fun setState(s: String) { state = s.lowercase() }
    fun setStateLevel(s: String, l: Float, w: Boolean) {
        state = s.lowercase(); amp = l.coerceIn(0f, 1f); workload = if (w) maxOf(workload, 0.4f) else 0f
    }
    fun setWorking(w: Boolean) { setPresent(state, tool, if (w) maxOf(workload, 0.5f) else 0f, amp) }
    fun pulseTool() { seed++; setPresent("thinking", tool, minOf(1f, workload + 0.35f), amp) }

    /** Render the motion MotionState decided. [amp] is the REAL voice level. */
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
    }

    /** Per-frame refresh of the CURRENT motion's drive values (the amp lock's clock). */
    fun driveMotion(amp: Float, workload: Float) {
        params = MotionState.renderParams(motion, workload, amp)
        this.amp = amp.coerceIn(0f, 1f)
        this.workload = workload.coerceIn(0f, 1f)
    }

    /** The stall lever: hold the waiting shape; on resume return to the prior motion. */
    fun setStall(stalled: Boolean, ms: Long) {
        stallMs = if (stalled) ms else 0L
        if (stalled == this.stalled) return
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
        val (st, tk) = when (name.lowercase()) {
            "listening" -> "listening" to null
            "vortex" -> "thinking" to null
            "scan" -> "thinking" to "web"
            "bracket" -> "thinking" to "shell"
            "constellation" -> "thinking" to "memory"
            "lumen" -> "streaming" to null
            "waveform" -> "speaking" to null
            "bloom" -> "settle" to null
            else -> "idle" to null
        }
        state = st; tool = tk; workload = if (st == "thinking") 0.7f else 0.15f; amp = 0.4f
    }

    /** Idle shape/theme: "aura" (default) or one of SHAPE_TOKENS. */
    fun setIdleTheme(theme: String) { idleTheme = theme.lowercase() }
    /** Auto-advance the idle theme every cycleSec (false = stay on one). */
    fun setCycleThemes(cycle: Boolean) { cycleThemes = cycle }
    fun setCycleSec(sec: Float) { cycleSec = sec }

    /** Feed the three ACTIVE-state shape tokens. Blank keeps the previous pick. */
    fun applyStateShapes(speaking: String?, listening: String?, thinking: String?) {
        if (!speaking.isNullOrBlank()) stateShapeSpeaking = speaking.lowercase()
        if (!listening.isNullOrBlank()) stateShapeListening = listening.lowercase()
        if (!thinking.isNullOrBlank()) stateShapeThinking = thinking.lowercase()
    }

    /** The visual category (VisualStyle.TOKENS); also picks up the cycle-all toggle. */
    fun setVisualCategory(token: String) {
        userCat = token
        val cyc = context.getSharedPreferences(VisualStyle.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(VisualStyle.KEY_CYCLE_ALL, VisualStyle.DEFAULT_CYCLE_ALL)
        setCycleAllCategories(cyc)
        if (!cycleAll) vsty = VisualStyle.of(token)
    }

    /** Cycle through every category on a timer, starting from the user's pick. */
    fun setCycleAllCategories(on: Boolean) {
        if (on == cycleAll) return
        cycleAll = on
        cycleAccum = 0f
        if (on) cycleIdx = VisualStyle.indexOf(userCat) else vsty = VisualStyle.of(userCat)
    }

    fun setVisualEnergy(v: Float) { visEnergy = VisualStyle.energy(v) }
    fun setVisualGlow(v: Float) { visGlow = VisualStyle.glow(v) }
    fun visualCategory(): String = vsty.token
    fun cyclingAllCategories(): Boolean = cycleAll

    // ---- the frame ------------------------------------------------------------------------

    private fun renderFrame(now: Long) {
        val t = thread ?: return
        if (width == 0 || height == 0) return
        val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1e9f).coerceIn(0.001f, 0.05f)
        lastNanos = now
        time += dt
        if (time > 3600f) time -= 3600f          // bounded clock: shader trig stays precise

        advanceCycle(dt)
        easeStyle(dt)
        driven = params.shape == state
        resolveDrive()
        val k = (dt * 3.6f).coerceIn(0f, 1f)
        sRadius += (eRadius - sRadius) * k
        sSpeed += (eSpeed - sSpeed) * k
        sBright += (eBright - sBright) * k

        advanceMorph(resolveArch(), dt)

        spin = (spin + dt * sSpeed * 0.85f) % TAU
        phBreath = (phBreath + dt * 1.05f) % TAU
        if (archTo == A_SEEKER || archFrom == A_SEEKER) eyeTick(dt)
        if (archTo == A_TAKU || archFrom == A_TAKU) takuTick(dt)

        resolvePalette(eTheme)
        val ck = (dt * 2.6f).coerceIn(0f, 1f)
        curBase = lerpColor(curBase, tBase, ck)
        curAcc = lerpColor(curAcc, tAcc, ck)

        val f = frame
        f.t = time; f.archA = archFrom; f.archB = archTo; f.mix = morph
        f.amp = amp; f.work = workload; f.breath = sin(phBreath); f.spin = spin; f.speed = sSpeed
        f.burst = if (archTo == A_BURST) ((time - recoilAt) * (1000f / MotionState.RECOIL_MS)).coerceIn(0f, 1f) else 1f
        f.seed = (seed * 1.37f) % TAU
        f.stall = (stallMs / 12000f).coerceIn(0f, 1f)
        f.pupilX = pupX; f.pupilY = pupY; f.blink = blinkEnv
        f.offX = ox; f.offY = oy; f.head = hAng; f.arm = armBoost; f.armPh = armPh
        f.w = width; f.h = height; f.cx = cx; f.cy = cy; f.bodyR = R * sRadius
        f.px = resources.displayMetrics.density * 2.2f
        f.energy = rEnergy * visEnergy * (1f + workload * 0.8f)
        f.flicker = rFlicker
        f.bright = sBright * 1.35f
        f.size = rSize
        f.core = rCoreHeat; f.edge = rEdge
        f.glow = rHalo * visGlow
        f.halo = (0.45f + sBright * 0.8f) * rHalo * visGlow
        f.trails = rTrails
        f.baseR = Color.red(curBase) / 255f; f.baseG = Color.green(curBase) / 255f; f.baseB = Color.blue(curBase) / 255f
        f.accR = Color.red(curAcc) / 255f; f.accG = Color.green(curAcc) / 255f; f.accB = Color.blue(curAcc) / 255f
        t.publish(f)
    }

    /** The morph: a new shape starts only once the current morph has landed, so a burst of
     *  state changes never tears a figure between three shapes. The recoil flinch is fast. */
    private fun advanceMorph(want: Int, dt: Float) {
        if (want != archTo && want != archPending) archPending = want
        if (morph >= 1f) {
            archFrom = archTo
            if (archPending >= 0 && archPending != archTo) {
                archTo = archPending; morph = 0f
            }
            archPending = -1
        }
        if (morph < 1f) {
            val len = if (archTo == A_BURST || archFrom == A_BURST) MORPH_FAST_SEC else MORPH_SEC
            morph = (morph + dt / len).coerceAtMost(1f)
        }
    }

    private fun resolveDrive() {
        if (driven) {
            eRadius = params.radius; eSpeed = params.speed
            eBright = params.bright; eTheme = params.theme
            return
        }
        when (state) {
            "listening" -> { eRadius = 0.98f; eSpeed = 0.55f; eBright = 0.60f; eTheme = "listen" }
            "gather" -> { eRadius = 0.78f; eSpeed = 1.15f + workload * 1.9f; eBright = 0.74f + workload * 0.22f; eTheme = "think" }
            "thinking" -> { eRadius = 0.92f; eSpeed = 1.4f + workload * 2.2f; eBright = 0.78f + workload * 0.20f; eTheme = "think" }
            "speaking" -> { eRadius = 0.80f + amp * 0.34f; eSpeed = 1.0f + amp * 2.6f; eBright = 0.66f + amp * 0.34f; eTheme = "speak" }
            "streaming" -> { eRadius = 0.96f; eSpeed = 1.10f; eBright = 0.72f; eTheme = "stream" }
            "waiting" -> { eRadius = 1.06f; eSpeed = 0.30f; eBright = 0.52f; eTheme = "wait" }
            "recoil" -> { eRadius = 1.30f; eSpeed = 3.6f; eBright = 0.95f; eTheme = "recoil" }
            "settle", "bloom" -> { eRadius = 0.95f; eSpeed = 0.85f; eBright = 0.68f; eTheme = "presence" }
            "drift" -> { eRadius = 1.10f; eSpeed = 0.26f; eBright = 0.56f; eTheme = "presence" }
            else -> { eRadius = 0.90f; eSpeed = 0.45f; eBright = 0.62f; eTheme = "presence" }
        }
    }

    /** The palette for a theme (base + accent), re-coloured by the eased visual category. */
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
        tBase = VisualStyle.shade(tBase, rTintAmt, rTint, rSat, rAccentTurn, false)
        tAcc = VisualStyle.shade(tAcc, rTintAmt, rTint, rSat, rAccentTurn, true)
    }

    /** Which shape the being forms right now. */
    private fun resolveArch(): Int {
        val st = state
        if (st == "idle" || st == "settle" || st == "bloom" || st == "drift") {
            val th = if (cycleThemes) cyclingTheme(time) else idleTheme
            themeArch(th)?.let { return it }
        }
        return when (st) {
            "recoil" -> A_BURST
            "waiting" -> A_HELD
            "speaking" -> stateArch("speaking", tool) ?: A_VOICE
            "gather" -> A_FLAME
            "listening" -> stateArch("listening", tool) ?: A_BREATH
            "settle", "bloom" -> A_BLOOM
            "streaming" -> A_RIBBON
            "thinking" -> when (tool) {
                "web", "search" -> stateArch("thinking", tool) ?: A_SWEEP
                "shell" -> A_FORGE
                "memory" -> A_NODES
                "file" -> A_RIBBON
                "download", "model" -> A_INFALL
                else -> A_GYRE
            }
            else -> A_ORB
        }
    }

    /** STATE -> SHAPE: the user's pick for an active state, defaulting to the semantic fit. */
    private fun stateArch(state: String, tool: String?): Int? {
        val fallback = when (state) {
            "speaking" -> A_WAVE
            "listening" -> A_SEEKER
            "thinking" -> if (tool == "web" || tool == "search") A_RADAR else null
            else -> null
        } ?: return null
        val tok = when (state) {
            "speaking" -> stateShapeSpeaking
            "listening" -> stateShapeListening
            else -> stateShapeThinking
        }
        if (tok.isBlank()) return fallback
        return themeArch(tok) ?: fallback
    }

    /** Shape token -> archetype. null = the default nebula. */
    private fun themeArch(th: String): Int? = when (th) {
        "aura" -> A_ORB
        "iris" -> A_BREATH
        "vortex" -> A_GYRE
        "waveform" -> A_VOICE
        "scan" -> A_SWEEP
        "constellation" -> A_NODES
        "bracket" -> A_FORGE
        "flame" -> A_FLAME
        "ribbon" -> A_RIBBON
        "infall" -> A_INFALL
        "bloom" -> A_BLOOM
        "soundwave" -> A_WAVE
        "arc" -> A_ARC
        "nucleus" -> A_NUCLEUS
        "eye" -> A_SEEKER
        "water" -> A_BORE
        "radar" -> A_RADAR
        "octopus" -> A_TAKU
        "sphere" -> A_SPHERE
        "helix" -> A_HELIX
        "knot" -> A_KNOT
        "aurora" -> A_AURORA
        "harmonograph" -> A_HARMONO
        "tesseract" -> A_TESSERACT
        "mandala" -> A_MANDALA
        "butterfly" -> A_BUTTERFLY
        "hourglass" -> A_HELD
        else -> null
    }

    private fun cyclingTheme(t: Float): String = SHAPE_TOKENS[((t / cycleSec).toInt()).mod(SHAPE_TOKENS.size)]

    private fun advanceCycle(dt: Float) {
        if (!cycleAll) return
        cycleAccum += dt
        if (cycleAccum < VisualStyle.CYCLE_ALL_SEC) return
        cycleAccum -= VisualStyle.CYCLE_ALL_SEC
        cycleIdx = (cycleIdx + 1).mod(VisualStyle.TOKENS.size)
        vsty = VisualStyle.of(VisualStyle.TOKENS[cycleIdx])
    }

    /** Ease the render-style toward the target category so a change flows, never snaps. */
    private fun easeStyle(dt: Float) {
        val k = (dt * 2.4f).coerceIn(0f, 1f)
        rTintAmt += (vsty.tintAmt - rTintAmt) * k
        rSat += (vsty.sat - rSat) * k
        rAccentTurn += (vsty.accentTurn - rAccentTurn) * k
        rCoreHeat += (vsty.coreHeat - rCoreHeat) * k
        rEdge += (vsty.edge - rEdge) * k
        rHalo += (vsty.halo - rHalo) * k
        rSize += (vsty.size - rSize) * k
        rFlicker += (vsty.flicker - rFlicker) * k
        rEnergy += (vsty.energy - rEnergy) * k
        rTint = lerpColor(rTint, vsty.tint, k)
        // Trails are real frame feedback now, so they ease like everything else.
        rTrails += ((if (vsty.trails) 0.78f else 0f) - rTrails) * k
    }

    /** The eye rests at centre and makes small, brief saccades, and blinks on an uneven beat. */
    private fun eyeTick(dt: Float) {
        val saccPer = 3.2f + 0.9f * hash((time * 0.23f).toInt().toFloat(), 5, 41)
        val t = frac(time / saccPer)
        val cidx = (time / saccPer).toInt().toFloat()
        val k = (dt * 10f).coerceIn(0f, 1f)
        if (t < 0.28f) {
            val env = sin(t / 0.28f * PI.toFloat())
            val tx = (hash(cidx, 7, 71) - 0.5f) * 2f * 0.30f * env
            val ty = (hash(cidx, 9, 73) - 0.5f) * 2f * 0.30f * env
            pupX += (tx - pupX) * k; pupY += (ty - pupY) * k
        } else {
            pupX += (0f - pupX) * k; pupY += (0f - pupY) * k
        }
        val per = 3.1f + 1.7f * hash((time * 0.18f).toInt().toFloat(), 5, 19)
        val bg = frac(time / per)
        blinkEnv = if (bg < 0.06f) 1f - sin(bg / 0.06f * PI.toFloat()) else 1f
    }

    /** The octopus: wander on a curved drift, occasionally fixate on something and work its
     *  arms at it, then commit to a turn away. The body glides; the arms propel. */
    private fun takuTick(dt: Float) {
        val bodyR = R * sRadius
        val g = thAng - hAng
        val dg = if (g > PI) g - TAU else if (g < -PI) g + TAU else g
        hAng += dg * (dt * 2.2f).coerceIn(0f, 1f)
        ox += (tox - ox) * (dt * 0.55f).coerceIn(0f, 1f)
        oy += (toy - oy) * (dt * 0.55f).coerceIn(0f, 1f)
        val lim = bodyR * 0.45f
        ox = ox.coerceIn(-lim, lim)
        oy = oy.coerceIn(-lim * 0.6f, lim * 0.6f)
        val turn = minOf(1f, kotlin.math.abs(dg) * 0.45f)
        val wantB = if (takuPhase == T_PH_FIXATE) 1.6f else 0.15f + 1.2f * turn
        armBoost += (wantB - armBoost) * (dt * 2.6f).coerceIn(0f, 1f)
        armPh = (armPh + dt * (2.2f + 1.3f * armBoost)) % TAU
        when (takuPhase) {
            T_PH_WANDER -> {
                val b = (time * 0.23f).toInt()
                if (b != lastWand) {
                    lastWand = b
                    tox = (hash(b.toFloat(), 0, 23) - 0.5f) * 2f * bodyR * 0.45f
                    toy = (hash(b.toFloat(), 2, 31) - 0.5f) * 2f * bodyR * 0.3f
                    thAng = atan2(toy - oy, tox - ox)
                    if (b - lastFixateB >= 2 && hash(b.toFloat(), 5, 37) > 0.62f) {
                        takuPhase = T_PH_FIXATE; takuT = 0f; lastFixateB = b
                        val fx = (hash(b.toFloat(), 1, 41) - 0.5f) * 2f
                        val fy = (hash(b.toFloat(), 3, 43) - 0.5f) * 1.6f
                        tox = ox; toy = oy
                        thAng = atan2(fy * bodyR * 0.9f - oy, fx * bodyR * 1.1f - ox)
                    }
                }
            }
            T_PH_FIXATE -> {
                takuT += dt
                if (takuT > 2.1f) {
                    takuPhase = T_PH_MOVEO; takuT = 0f
                    thAng = hAng + PI.toFloat()
                    val b = (time * 0.23f).toInt()
                    tox = (hash(b.toFloat(), 0, 23) - 0.5f) * 2f * bodyR * 0.45f
                    toy = (hash(b.toFloat(), 2, 31) - 0.5f) * 2f * bodyR * 0.3f
                }
            }
            else -> {
                takuT += dt
                if (takuT > 0.8f) { takuPhase = T_PH_WANDER; takuT = 0f; lastWand = -1 }
            }
        }
    }

    // ---- small pure helpers ------------------------------------------------------------------

    private fun lerpColor(a: Int, b: Int, f: Float): Int {
        val ff = f.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(a) * (1 - ff) + Color.red(b) * ff).toInt(),
            (Color.green(a) * (1 - ff) + Color.green(b) * ff).toInt(),
            (Color.blue(a) * (1 - ff) + Color.blue(b) * ff).toInt()
        )
    }
    private fun hash(a: Float, b: Int, k: Int): Float = frac((a * 127.1f + b * 311.7f + k * 74.7f) * 1000f)
    private fun frac(x: Float): Float = x - floor(x)
}
