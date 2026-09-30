package com.hermesvox

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * One frame of the being, as the shader sees it. Written by AvatarView on the main thread,
 * copied by the render thread under [BeingThread]'s lock. Primitives only.
 */
class BeingFrame {
    var t = 0f; var archA = 0; var archB = 0; var mix = 1f
    var amp = 0f; var work = 0f; var breath = 0f; var spin = 0f; var speed = 0f
    var burst = 0f; var seed = 0f; var stall = 0f
    var pupilX = 0f; var pupilY = 0f; var blink = 1f
    var offX = 0f; var offY = 0f; var head = 1.5708f; var arm = 0f; var armPh = 0f
    var cx = 0f; var cy = 0f; var w = 1; var h = 1; var bodyR = 100f
    var px = 4f; var energy = 1f; var flicker = 1f; var bright = 0.6f; var size = 1f
    var core = 1f; var edge = 1f; var glow = 1f; var halo = 1f; var trails = 0f
    var baseR = 0.4f; var baseG = 0.7f; var baseB = 0.8f
    var accR = 0.7f; var accG = 0.9f; var accB = 1f

    fun copyFrom(o: BeingFrame) {
        t = o.t; archA = o.archA; archB = o.archB; mix = o.mix
        amp = o.amp; work = o.work; breath = o.breath; spin = o.spin; speed = o.speed
        burst = o.burst; seed = o.seed; stall = o.stall
        pupilX = o.pupilX; pupilY = o.pupilY; blink = o.blink
        offX = o.offX; offY = o.offY; head = o.head; arm = o.arm; armPh = o.armPh
        cx = o.cx; cy = o.cy; w = o.w; h = o.h; bodyR = o.bodyR
        px = o.px; energy = o.energy; flicker = o.flicker; bright = o.bright; size = o.size
        core = o.core; edge = o.edge; glow = o.glow; halo = o.halo; trails = o.trails
        baseR = o.baseR; baseG = o.baseG; baseB = o.baseB
        accR = o.accR; accG = o.accG; accB = o.accB
    }
}

/**
 * The being's GPU pipeline (OpenGL ES 3.0): thousands of additive point sprites whose positions
 * come from [BeingShaders], optional frame-feedback trails, a two-level bloom and a filmic
 * composite with an ambient halo and a vignette. Owned and driven by [BeingThread].
 */
class BeingRenderer {
    private var point = 0; private var fade = 0; private var bright = 0; private var blur = 0; private var comp = 0
    private var vao = 0; private var quadVao = 0
    private var w = 0; private var h = 0
    // scene ping-pong (trails), bloom levels at 1/4 and 1/8 resolution
    private val sceneTex = IntArray(2); private val sceneFbo = IntArray(2)
    private val bloomATex = IntArray(2); private val bloomAFbo = IntArray(2)
    private val bloomBTex = IntArray(2); private val bloomBFbo = IntArray(2)
    private var cur = 0
    private var halfFloat = true

    /**
     * ADAPTIVE QUALITY. The GPU is shared with the soul model, and some phones (and every
     * emulator, whose GPU is software) cannot afford the full pipeline: an over-budget render
     * thread starves the system's own frames until input stops landing (an ANR). [BeingThread]
     * times every frame and steps this down — fewer points, a smaller scene buffer, one bloom
     * level — until the being fits its budget. 0 = full.
     */
    var quality = 0
        set(v) { val q = v.coerceIn(0, 2); if (q != field) { field = q; val ww = w; val hh = h; w = 0; h = 0; resize(ww, hh) } }
    private val counts = intArrayOf(BeingShaders.PARTICLES, 3500, 1800)
    private val scales = floatArrayOf(1f, 0.75f, 0.5f)
    private var sw = 0; private var sh = 0   // scene buffer size (scaled)
    private val u = HashMap<String, Int>()

    fun init() {
        point = program(BeingShaders.VERTEX, BeingShaders.FRAGMENT_POINT)
        fade = program(BeingShaders.VERTEX_QUAD, BeingShaders.FRAGMENT_FADE)
        bright = program(BeingShaders.VERTEX_QUAD, BeingShaders.FRAGMENT_BRIGHT)
        blur = program(BeingShaders.VERTEX_QUAD, BeingShaders.FRAGMENT_BLUR)
        comp = program(BeingShaders.VERTEX_QUAD, BeingShaders.FRAGMENT_COMPOSITE)
        buildParticles()
        val a = IntArray(1); GLES30.glGenVertexArrays(1, a, 0); quadVao = a[0]
    }

    /** The particles' fixed identities: an index, four seeded randoms, a long-tailed size and
     *  brightness character (most are small and dim, a few large and hot). Deterministic. */
    private fun buildParticles() {
        val n = BeingShaders.PARTICLES
        val r = Random(7)
        // Stored in a SHUFFLED order: any prefix of the buffer is then a uniform sample of every
        // shape's parts (shapes group particles by id), so a lower quality level draws fewer
        // points without losing a lid, a ring or a limb.
        val order = (0 until n).shuffled(Random(11))
        val buf = ByteBuffer.allocateDirect(n * 7 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (k in 0 until n) {
            val i = order[k]
            buf.put(i.toFloat())
            buf.put(r.nextFloat()); buf.put(r.nextFloat()); buf.put(r.nextFloat()); buf.put(r.nextFloat())
            buf.put(Math.pow(r.nextDouble(), 2.2).toFloat())
            buf.put(0.45f + Math.pow(r.nextDouble(), 1.8).toFloat() * 0.85f)
        }
        buf.position(0)
        val ids = IntArray(1); GLES30.glGenVertexArrays(1, ids, 0); vao = ids[0]
        GLES30.glBindVertexArray(vao)
        val vb = IntArray(1); GLES30.glGenBuffers(1, vb, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vb[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, n * 7 * 4, buf, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 1, GLES30.GL_FLOAT, false, 28, 0)
        GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, 28, 4)
        GLES30.glEnableVertexAttribArray(2); GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, 28, 20)
        GLES30.glBindVertexArray(0)
    }

    fun resize(width: Int, height: Int) {
        if (width == w && height == h) return
        release()
        w = width; h = height
        sw = maxOf(1, (w * scales[quality]).toInt()); sh = maxOf(1, (h * scales[quality]).toInt())
        for (i in 0..1) {
            makeTarget(sw, sh, i, sceneTex, sceneFbo)
            makeTarget(maxOf(1, w / 4), maxOf(1, h / 4), i, bloomATex, bloomAFbo)
            makeTarget(maxOf(1, w / 8), maxOf(1, h / 8), i, bloomBTex, bloomBFbo)
        }
    }

    /** A render target; half-float where the GPU can render to it (hot cores stay above 1.0
     *  until the tone curve), else 8-bit. */
    private fun makeTarget(tw: Int, th: Int, i: Int, tex: IntArray, fbo: IntArray) {
        val t = IntArray(1); GLES30.glGenTextures(1, t, 0); tex[i] = t[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[i])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val f = IntArray(1); GLES30.glGenFramebuffers(1, f, 0); fbo[i] = f[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[i])
        if (halfFloat) {
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, tw, th, 0, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex[i], 0)
            if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
                halfFloat = false
                VoxLog.d("being: half-float targets unsupported — 8-bit")
            }
        }
        if (!halfFloat) {
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, tw, th, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, tex[i], 0)
        }
        GLES30.glClearColor(0f, 0f, 0f, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    fun draw(f: BeingFrame) {
        if (w == 0 || h == 0) return
        val prev = cur; cur = 1 - cur
        // 1. the scene: last frame x decay (trails, when the category wants them), then the swarm
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFbo[cur])
        GLES30.glViewport(0, 0, sw, sh)
        GLES30.glClearColor(0f, 0f, 0f, 1f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glBindVertexArray(quadVao)
        if (f.trails > 0f) {
            GLES30.glUseProgram(fade)
            tex(fade, "uTex", 0, sceneTex[prev]); f1(fade, "uGain", f.trails)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        }
        GLES30.glEnable(GLES30.GL_BLEND); GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        GLES30.glUseProgram(point)
        setPointUniforms(f)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_POINTS, 0, counts[quality])
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(quadVao)

        // 2. bloom: bright-pass to 1/4, blur twice; down to 1/8, blur twice (a wide soft glow)
        pass(bright, bloomAFbo[0], w / 4, h / 4) { tex(bright, "uTex", 0, sceneTex[cur]); f2(bright, "uTexel", 1f / w, 1f / h) }
        repeat(2) {
            pass(blur, bloomAFbo[1], w / 4, h / 4) { tex(blur, "uTex", 0, bloomATex[0]); f2(blur, "uDir", 4f / w, 0f) }
            pass(blur, bloomAFbo[0], w / 4, h / 4) { tex(blur, "uTex", 0, bloomATex[1]); f2(blur, "uDir", 0f, 4f / h) }
        }
        if (quality < 2) pass(bright, bloomBFbo[0], w / 8, h / 8) { tex(bright, "uTex", 0, bloomATex[0]); f2(bright, "uTexel", 4f / w, 4f / h) }
        if (quality < 2) repeat(2) {
            pass(blur, bloomBFbo[1], w / 8, h / 8) { tex(blur, "uTex", 0, bloomBTex[0]); f2(blur, "uDir", 8f / w, 0f) }
            pass(blur, bloomBFbo[0], w / 8, h / 8) { tex(blur, "uTex", 0, bloomBTex[1]); f2(blur, "uDir", 0f, 8f / h) }
        }

        // 3. composite to the window
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, w, h)
        GLES30.glUseProgram(comp)
        tex(comp, "uScene", 0, sceneTex[cur]); tex(comp, "uBloomA", 1, bloomATex[0]); tex(comp, "uBloomB", 2, bloomBTex[0])
        f1(comp, "uGlow", f.glow); f1(comp, "uHalo", f.halo)
        f3(comp, "uBase", f.baseR, f.baseG, f.baseB)
        f2(comp, "uCenterUv", f.cx / w, 1f - f.cy / h)
        f2(comp, "uHaloR", f.bodyR * 1.7f / w, f.bodyR * 1.7f / h)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    private fun setPointUniforms(f: BeingFrame) {
        val p = point
        f1(p, "uT", f.t); f1(p, "uMix", f.mix); i1(p, "uA", f.archA); i1(p, "uB", f.archB)
        f1(p, "uAmp", f.amp); f1(p, "uWork", f.work); f1(p, "uBreath", f.breath); f1(p, "uSpin", f.spin)
        f1(p, "uSpeed", f.speed); f1(p, "uBurst", f.burst); f1(p, "uSeed", f.seed); f1(p, "uStall", f.stall)
        // The being's centre, y flipped: GL's origin is bottom-left, the view's top-left.
        f2(p, "uCenter", f.cx, h - f.cy); f2(p, "uRes", w.toFloat(), h.toFloat())
        // Fewer points each carry more of the figure; a smaller buffer needs smaller points.
        val q = quality
        GLES30.glUniform1f(loc(p, "uPx"), f.px * scales[q] * (if (q == 0) 1f else if (q == 1) 1.2f else 1.45f))
        GLES30.glUniform1f(loc(p, "uBright"), f.bright * BeingShaders.PARTICLES / counts[q])
        f2(p, "uPupil", f.pupilX, -f.pupilY); f1(p, "uBlink", f.blink)
        f2(p, "uOff", f.offX / f.bodyR, -f.offY / f.bodyR); f1(p, "uHead", -f.head)
        f1(p, "uArm", f.arm); f1(p, "uArmPh", f.armPh)
        f1(p, "uBodyR", f.bodyR); f1(p, "uEnergy", f.energy); f1(p, "uFlicker", f.flicker)
        f1(p, "uSize", f.size)
        f3(p, "uBase", f.baseR, f.baseG, f.baseB); f3(p, "uAcc", f.accR, f.accG, f.accB)
        f1(p, "uCore", f.core); f1(p, "uEdge", f.edge)
    }

    private inline fun pass(prog: Int, fbo: Int, pw: Int, ph: Int, setup: () -> Unit) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, maxOf(1, pw), maxOf(1, ph))
        GLES30.glUseProgram(prog)
        setup()
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    private fun loc(p: Int, n: String): Int = u.getOrPut("$p/$n") { GLES30.glGetUniformLocation(p, n) }
    private fun f1(p: Int, n: String, v: Float) = GLES30.glUniform1f(loc(p, n), v)
    private fun f2(p: Int, n: String, a: Float, b: Float) = GLES30.glUniform2f(loc(p, n), a, b)
    private fun f3(p: Int, n: String, a: Float, b: Float, c: Float) = GLES30.glUniform3f(loc(p, n), a, b, c)
    private fun i1(p: Int, n: String, v: Int) = GLES30.glUniform1i(loc(p, n), v)
    private fun tex(p: Int, n: String, unit: Int, t: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t)
        GLES30.glUniform1i(loc(p, n), unit)
    }

    private fun program(vs: String, fs: String): Int {
        val v = shader(GLES30.GL_VERTEX_SHADER, vs); val f = shader(GLES30.GL_FRAGMENT_SHADER, fs)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f); GLES30.glLinkProgram(p)
        val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("being: link failed: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
        val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("being: compile failed: " + GLES30.glGetShaderInfoLog(s))
        return s
    }

    private fun release() {
        if (w == 0) return
        GLES30.glDeleteTextures(2, sceneTex, 0); GLES30.glDeleteFramebuffers(2, sceneFbo, 0)
        GLES30.glDeleteTextures(2, bloomATex, 0); GLES30.glDeleteFramebuffers(2, bloomAFbo, 0)
        GLES30.glDeleteTextures(2, bloomBTex, 0); GLES30.glDeleteFramebuffers(2, bloomBFbo, 0)
    }
}

/**
 * The being's render thread: owns an EGL (OpenGL ES 3) context on the TextureView's surface
 * and draws whenever AvatarView publishes a new frame. Rendering never touches the main thread.
 */
class BeingThread(private val surface: SurfaceTexture) : Thread("being-render") {
    private val lock = Object()
    private val pending = BeingFrame()
    private val drawing = BeingFrame()
    private var hasFrame = false
    @Volatile private var quit = false
    @Volatile private var width = 1; @Volatile private var height = 1

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    fun publish(f: BeingFrame) = synchronized(lock) { pending.copyFrom(f); hasFrame = true; lock.notifyAll() }
    fun resize(w: Int, h: Int) { width = w; height = h }
    fun finish() { quit = true; synchronized(lock) { lock.notifyAll() } }

    override fun run() {
        try {
            if (!setupEgl()) return
            val r = BeingRenderer()
            r.init()
            while (!quit) {
                synchronized(lock) {
                    while (!hasFrame && !quit) lock.wait(250)
                    if (quit) return@synchronized
                    drawing.copyFrom(pending); hasFrame = false
                }
                if (quit) break
                r.resize(width, height)
                val t0 = System.nanoTime()
                r.draw(drawing)
                // BACK-PRESSURE: wait for the GPU to finish this frame before taking the next.
                // GL calls return at once and queue work, so without this a slow GPU (every
                // emulator; a busy phone) falls further behind each frame until the system's
                // own frames starve and input stops landing. The wait is also the only honest
                // measure of what a frame costs, which is what the quality steps act on.
                val fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
                GLES30.glClientWaitSync(fence, GLES30.GL_SYNC_FLUSH_COMMANDS_BIT, 200_000_000L)
                GLES30.glDeleteSync(fence)
                adapt(r, (System.nanoTime() - t0) / 1e6f)
                EGL14.eglSwapBuffers(display, eglSurface)
            }
        } catch (t: Throwable) {
            VoxLog.e("being: render thread failed: ${t.message}")
        } finally {
            teardownEgl()
        }
    }

    // Frame-time EMA; a sustained overrun steps quality down (never back up in a session —
    // oscillating quality reads as a flicker, and a device that overran once will again).
    private var ema = 0f; private var frames = 0
    private fun adapt(r: BeingRenderer, ms: Float) {
        ema = if (frames == 0) ms else ema * 0.9f + ms * 0.1f
        frames++
        // A catastrophically slow frame steps down at once; a merely heavy one needs a trend.
        if (r.quality < 2 && (ms > 80f || (frames > 20 && ema > 26f))) {
            r.quality = r.quality + 1
            VoxLog.d("being: frame ${"%.1f".format(ema)} ms over budget -> quality ${r.quality}")
            frames = 0
        }
    }

    private fun setupEgl(): Boolean {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) { VoxLog.e("being: eglInitialize failed"); return false }
        val attrs = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_NONE)
        val configs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
        if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, n, 0) || n[0] == 0) { VoxLog.e("being: no ES3 config"); return false }
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (context == EGL14.EGL_NO_CONTEXT || eglSurface == EGL14.EGL_NO_SURFACE) { VoxLog.e("being: EGL context/surface failed"); return false }
        return EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)
    }

    private fun teardownEgl() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
        display = EGL14.EGL_NO_DISPLAY
    }
}
