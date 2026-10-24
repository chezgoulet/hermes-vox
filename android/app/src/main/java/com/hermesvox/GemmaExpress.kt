package com.hermesvox

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File

/**
 * GemmaExpress — the ON-DEVICE LiteRT-LM expression layer (Gemma 4 E2B).
 *
 * Implements VoxExpress: loads the .litertlm model via LiteRT-LM's Engine +
 * renders a Hermes-pushed directive as the natural phone-call glue. IRONCLAD:
 * no tool interface, no authority — Gemma only EXPRESSES. Falls back to the
 * RoutedExpress stand-in if the model isn't installed or the runtime can't load
 * it (e.g. the x86_64 emulator) — the presence never breaks.
 */
class GemmaExpress(private val context: Context) : VoxExpress {

    private var llm: Engine? = null
    @Volatile private var loaded = false
    private val fallback = RoutedExpress()

    override val available: Boolean get() = loaded

    /** The KV cache this engine is built with (Phase 2 of the memory budget): the shipped
     *  8192 unless a debug pref names 4096 or 2048. `maxNumTokens` IS the cache, so this is
     *  read at load and cannot change under a running engine. */
    private val kvTokens: Int
        get() = MemoryBudget.kvTokens(
            context.getSharedPreferences("hv", Context.MODE_PRIVATE)
                .getInt(MemoryBudget.PREF_KV_TOKENS, MemoryBudget.KV_DEFAULT))

    private val modelFile get() = File(context.filesDir, "models/gemma-e2b/gemma-4-E2B-it.litertlm")

    /**
     * The soul prompt (ER Phases 2-4): the mirrored VOX.md, when a valid one
     * exists, IS the persona — the agent's own distilled identity (Contract +
     * Soul) wrapped by the voice-not-mind prelude. Without a mirror, the
     * built-in generic persona holds the line (ER degrades, never breaks).
     * Read per generation: a Resync takes effect without a reload.
     */
    private val persona: String
        get() = VoxSoul.soulPrompt(VoxMirror.read(context) ?: "")

    /** Load the on-device LiteRT-LM model (async, device/GPU). onReady(true) when loaded. */
    fun load(onReady: (Boolean) -> Unit) {
        // 0.6.2 field log (hermes-vox-merged: two "GemmaExpress loaded" lines 105ms
        // apart): onResume calls handleModeUi, and handleModeUi ran BEFORE the first
        // load's thread set loaded=true — so the availability check raced and the
        // model initialized TWICE (double memory on a phone, and a window where two
        // Engines could serve generations). Guard: one in-flight load; every caller
        // during it is chained onto the same completion.
        if (loaded) { onReady(true); return }
        synchronized(loadLock) {
            if (loading) { onReady(false); return }   // a chained caller gets the next onResume
            loading = true
        }
        kotlin.concurrent.thread {
            try {
                if (!modelFile.exists()) { loaded = false; onReady(false); return@thread }
                // 0.8/M3: speculative decoding (ExperimentalFlags.enableSpeculativeDecoding)
                // was enabled here and REMOVED after measurement. The model card documents
                // 1.3-1.8x decode on phone GPUs, but it is explicitly task-dependent, and
                // our workload is a ~10-token output against a ~600-token preface. Field
                // warm-up renders: 3874/2686 and 3486/2538 ms WITH it, versus 3285/2208,
                // 3332/2330 and 2800/2224 ms without — slower on both renders in both
                // sessions. For outputs this short the drafter costs more than it saves.
                // If a longer-form soul lane ever lands, re-measure before re-enabling.
                // 0.7.3 GPU-first. The express layer is the latency-critical one: the
                // soul's beat has to land inside a conversational pause, and the
                // documented phone-class difference is ~1.8s time-to-first-token on
                // CPU vs ~0.3s on GPU. Backend.GPU() needs the two
                // <uses-native-library> grants in the manifest (libOpenCL.so +
                // libvndksupport.so) — without them the vendor OpenCL cannot be
                // opened and initEngine() falls back to CPU. Never fail the layer for
                // a missing accelerator: a CPU presence beats no presence.
                val (e, backend) = initEngine()
                llm = e; activeBackend = backend
                // READY MEANS WARM. `available` used to flip true BEFORE the warm-up, so the
                // "Preparing your voice" pill cleared while the soul was still priming and
                // probing — three renders and ~4 s of spacing sleeps holding the engine lock —
                // and the call's greeting and first turn queued behind them (the field log's
                // "warm-up renders over turn 1"). The soul is available once it is primed.
                warmUp()
                loaded = true
                VoxLog.d("GemmaExpress loaded: $modelFile backend=$backend hears=$hears kv=$kvTokens (primed)")
                onReady(true)
                // The full-vs-minimal prefill probe is diagnostic and holds the engine for
                // seconds, so it runs only for a verbose-log field session, after ready.
                if (context.getSharedPreferences("hv", Context.MODE_PRIVATE).getBoolean("debug_log", false)) {
                    kotlin.concurrent.thread(isDaemon = true) { probePrefill() }
                }
            } catch (e: Throwable) {
                VoxLog.e("GemmaExpress load failed: ${e.message}")
                loaded = false; llm = null; loadFailed = true; onReady(false)
            } finally {
                synchronized(loadLock) { loading = false }
            }
        }
    }
    /** Which accelerator actually served the model ("gpu" / "cpu"). Diagnostic —
     *  this is what lets a field log PROVE whether the GPU path is live on a given
     *  device, instead of leaving it to inference from frame drops. */
    @Volatile var activeBackend: String = "none"
        private set

    /** True between a load thread's start and its callback. The call-open path uses it so it
     *  never opens a second `event=presence-load` pair for a load it did not start — those
     *  `ms=` values are quoted in docs/BASELINE.md and must stay one per real load. */
    val isLoading: Boolean get() = loading

    /** A real load failure this session (no backend could start the model) — distinct from the
     *  `false` a caller gets while another load is already in flight. */
    @Volatile var loadFailed = false
        private set

    /**
     * 0.8/M2.1: render twice immediately after load, and LOG each duration.
     *
     * Two reasons, both from the 09-10 field log:
     *  1. The first render measured 4342ms — 8x the beat the instant lane needs. It
     *     was also the FIRST render after load, i.e. cold. Warming up moves that cost
     *     off the user's first turn and onto the load (where it is invisible), so a
     *     real render is never the cold one.
     *  2. G2 ("is the soul's render inside a conversational beat?") had exactly ONE
     *     sample, because in a quiet conversation the only render call site is the
     *     tool-call narration. A warm-up pair produces warm numbers with no tool call.
     *
     * These are logged EXPLICITLY, not fed into ErTelemetry.gemmaRender — warming the
     * engine is not the soul speaking, and it must not pollute the p50/p95 that
     * describes real presence. The 1.3s spacing clears ErGemmaGuard's 1.2s
     * repeat-spacing rail so the second render is a real second measurement.
     */
    private fun warmUp() {
        // Prime the soul's rolling conversation: this render pays the persona prefill ONCE, at
        // load, where the user already expects to wait. Every render after it is warm — and the
        // second, short render measures exactly that (the number the beat depends on).
        val prime = timed { generateWarm(PRIME_DIRECTIVE) }
        val warm = timed { generateWarm(PRIME_DIRECTIVE) }
        VoxLog.d("GemmaExpress warm-up primeMs=$prime express-probe warm=${warm}ms")
    }

    /**
     * 0.8/M3d — the prefill probe.
     *
     * The beat needs a SHORT line, fast. Our warm renders are ~2.2s, and the workaround that
     * suggests itself — pre-generate a set of lines and play them from cache — is an array of canned
     * responses. The maintainer named it correctly: that is the pattern this series spent its whole
     * length deleting, wearing better clothes. So before designing around it, measure what the
     * workaround assumes.
     *
     * The code says where the time should go: every render builds a FRESH Conversation, and the
     * persona is ~600 tokens against a ~10-token output — so the whole preface is re-prefilled on
     * every single render. If that dominates, a minimal persona renders the same directive far
     * faster, and the beat can be GENERATED — contextually right, never repeating — rather than
     * selected from a list.
     *
     * Two renders of a fixed trivial directive: full persona, then minimal. Output discarded, and
     * deliberately NOT fed to ErTelemetry.gemmaRender — this is a measurement, not the soul
     * speaking, and it must not pollute the p50/p95 that describes real presence. Runs at load,
     * inside the init gate, where the user already expects to wait.
     */
    private fun probePrefill() {
        val directive = "Operator directive: intent=greeting. Content to render: hello"
        val full = timed { generate(persona, directive) }
        // Separation, not the spacing rail: each measurement gets a settled GPU.
        try { Thread.sleep(ErGemmaGuard.MIN_RENDER_SPACING_MS + 150) } catch (_: InterruptedException) {}
        val minimal = timed { generate(MINIMAL_PERSONA, directive) }
        VoxLog.d("express-probe full=${full}ms minimal=${minimal}ms personaChars=${persona.length}")
    }

    private inline fun timed(f: () -> String): Long {
        val t0 = System.currentTimeMillis()
        try { f() } catch (_: Throwable) {}
        return System.currentTimeMillis() - t0
    }

    /** Build the engine, preferring the GPU. Returns the first backend that
     *  initializes; throws the CPU attempt's error if neither works (the caller
     *  then reports the layer unavailable, exactly as before). */
    private fun initEngine(): Pair<Engine, String> {
        // Each accelerator is tried WITH the audio encoder first (the soul hears the caller's
        // tone), then text-only: a model file or device without a working audio path must cost
        // the soul its ears, never its voice.
        val attempts: List<Triple<String, () -> Backend, Boolean>> = listOf(
            Triple("gpu", { Backend.GPU() }, true), Triple("gpu", { Backend.GPU() }, false),
            Triple("cpu", { Backend.CPU() }, true), Triple("cpu", { Backend.CPU() }, false))
        var lastError: Throwable? = null
        for ((name, backend, withAudio) in attempts) {
            try {
                val e = Engine(EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = backend(),
                    // The audio encoder runs on the CPU: it encodes a few seconds once per turn,
                    // and keeping it off the GPU leaves the GPU to the language model.
                    audioBackend = if (withAudio) Backend.CPU() else null,
                    // 0.8/M3: maxNumTokens IS the kv-cache size (LiteRT-LM KDoc: "equivalent
                    // to the size of the kv-cache"). Left null it inherits the model's
                    // full 32k context, which is 4x more than this layer can ever use:
                    // the persona is ~600 tokens and a render is capped at 256 output.
                    // 8k keeps real headroom for a growing VOX.md and for bounded
                    // conversation reuse later, while cutting the cache 4x.
                    // Phase 2 (memory budget): 8192 is the shipped default; 4096/2048 are
                    // reachable only through the debug pref, and the value is logged so a
                    // run can be attributed to an arm without trusting the settings screen.
                    maxNumTokens = kvTokens,
                    // 0.8/M2.1: LiteRT-LM's docs call this out ("Pick a writable dir.
                    // This can improve 2nd load time.") and its published benchmarks are
                    // cache-enabled — we were paying the uncached first-load path on
                    // every single load. The 09-10 field log showed ~38s from pipeline
                    // start to `GemmaExpress loaded … backend=gpu` (CPU builds in the
                    // same log: ~3s), which is a candidate explanation.
                    cacheDir = context.cacheDir.path,
                ))
                e.initialize()
                if (name == "cpu" && lastError != null) {
                    VoxLog.e("GemmaExpress: GPU unavailable (${lastError?.message}) — running on CPU")
                }
                hears = withAudio
                return e to name
            } catch (t: Throwable) {
                lastError = t
                VoxLog.e("GemmaExpress: $name backend (audio=$withAudio) init failed: ${t.message}")
            }
        }
        throw lastError ?: IllegalStateException("GemmaExpress: no backend available")
    }

    private val loadLock = Object()
    @Volatile private var loading = false

    /**
     * Phase 4: release the Engine. This is the only code path in the app that returns the
     * presence layer's memory — measured on a Pixel 7 at ~2.2 GB of MemAvailable between
     * "resident" and "released" (docs/BASELINE.md). It is NOT on by default: [requestUnload]
     * consults [MemoryBudget.release], so a phone with no pref behaves exactly as 0.9.1
     * (load at resume, keep forever). The trade is the reload: 5.6–7.7 s measured cold, the
     * next call paying it inside the warm gate.
     *
     * Ordering: flip [loaded] false FIRST so a render that arrives during the teardown falls
     * back to the routed line instead of touching a closed engine, then close the rolling
     * conversation under [genLock] (the same lock a render holds), then the engine.
     */
    fun unload(reason: String) {
        synchronized(loadLock) { if (loading) { VoxLog.d("event=presence-unload reason=$reason skipped=loading"); return } }
        if (!loaded && llm == null) return
        // A release disarms any pending idle release: there is nothing left to release, and a
        // stale arm would log a second `presence-unload` after the next load.
        idleArmed = false
        try { idleHandler.removeCallbacks(idleTask) } catch (_: Throwable) {}
        loaded = false
        val freed = try {
            synchronized(genLock) {
                closeSoul()
                val e = llm
                llm = null
                try { e?.close() } catch (t: Throwable) { VoxLog.e("GemmaExpress unload: engine close failed (${t.message})") }
                e != null
            }
        } catch (t: Throwable) {
            VoxLog.e("GemmaExpress unload failed: ${t.message}"); false
        }
        loadFailed = false   // a released engine is not a device that cannot run one
        activeBackend = "none"
        val mi = android.app.ActivityManager.MemoryInfo().also {
            context.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(it)
        }
        VoxLog.d("event=presence-unload reason=$reason released=$freed availMb=${mi.availMem / 1048576}")
    }

    /**
     * Phase 5 — the DELAYED arm (docs/BASELINE.md, "Memory"). Called at call end and when the
     * mode leaves Enhanced; [MemoryBudget.release] decides what actually happens:
     *
     *  - [MemoryBudget.Release.KEEP]        — shipped default, nothing at all;
     *  - [MemoryBudget.Release.ON_HANGUP]   — release now (the arm that charges 7.7 s to every
     *    redial, measured in Phase 4);
     *  - [MemoryBudget.Release.AFTER_DELAY] — arm a timer for N minutes and release then. This is
     *    the arm a phone left alone needs: the memory comes back while it sits in a pocket, and a
     *    redial inside the window still finds the engine warm ([cancelIdleUnload] on call start).
     *
     * The timer is read at the trigger, not per render, so changing the pref takes effect at the
     * next hangup without a reload.
     */
    fun requestUnload(trigger: String) {
        val prefs = context.getSharedPreferences("hv", Context.MODE_PRIVATE)
        val delayMin = prefs.getInt(MemoryBudget.PREF_UNLOAD_DELAY, MemoryBudget.DELAY_DEFAULT)
        when (MemoryBudget.release(prefs.getBoolean(MemoryBudget.PREF_UNLOAD, false), delayMin)) {
            MemoryBudget.Release.KEEP -> cancelIdleUnload("policy-keep")
            MemoryBudget.Release.ON_HANGUP -> {
                cancelIdleUnload("policy-on-hangup")
                unloadAsync(trigger)
            }
            MemoryBudget.Release.AFTER_DELAY -> armIdleUnload(trigger, MemoryBudget.unloadDelayMinutes(delayMin))
        }
    }

    /** Main-thread handler for the idle timer: a call can start from the UI, and the timer must
     *  be cancelled by the same thread discipline that posted it. */
    private val idleHandler by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }

    @Volatile private var idleArmed = false

    /** Fires at the end of the delay. The log line the field run greps for is
     *  `event=presence-unload reason=idle` — emitted by [unload] itself, so a delayed release
     *  and an immediate one are told apart by their reason, not by two log shapes. */
    private val idleTask = Runnable {
        idleArmed = false
        VoxLog.d("event=presence-unload-due reason=idle")
        unloadAsync("idle")
    }

    private fun armIdleUnload(trigger: String, minutes: Int) {
        idleHandler.removeCallbacks(idleTask)
        idleArmed = true
        VoxLog.d("event=presence-unload-arm trigger=$trigger minutes=$minutes")
        idleHandler.postDelayed(idleTask, MemoryBudget.unloadDelayMs(minutes))
    }

    /** A call starting, or a load being asked for, beats the clock: the delayed arm exists so
     *  that it never charges the reload to a redial, so anything that wants the engine cancels
     *  the pending release. Logged, because "the release never happened" must be explainable
     *  from the field log rather than look like a timer that silently died. */
    fun cancelIdleUnload(reason: String) {
        if (!idleArmed) return
        idleArmed = false
        idleHandler.removeCallbacks(idleTask)
        VoxLog.d("event=presence-unload-cancel reason=$reason")
    }

    /** Never on the caller's thread: endCall() runs on main, and closing the Engine frees
     *  ~2 GB of GPU/native memory — that is a stall, not an operation. */
    private fun unloadAsync(reason: String) {
        kotlin.concurrent.thread(isDaemon = true, name = "presence-unload") { unload(reason) }
    }

    /** True when the engine came up with its audio encoder; cleared for the session if a heard
     *  render fails, so one bad audio path degrades to text-only rather than failing every turn. */
    @Volatile var hears = false
        private set

    override fun express(intent: String, content: String, tone: String): String =
        expressHeard(intent, content, tone, null)

    override fun expressHeard(intent: String, content: String, tone: String, audio: ByteArray?): String {
        // 0.8/M2.2: rail 3 BEFORE the generation, not after. The spacing check used to
        // run on the produced text, so a too-soon request paid for a full generation
        // and then discarded it. Same outcome (the guard returned null -> the caller
        // fell back to the routed line), no wasted render.
        // A turn decision is exempt: it is one per caller utterance by construction, and the
        // rail used to drop it outright when a narration had rendered a moment earlier — the
        // caller's turn then got no soul decision and no beat at all.
        val spaced = intent != VoiceOrchestrator.INTENT_SOUL_TURN
        if (spaced && System.currentTimeMillis() - lastRenderAt < ErGemmaGuard.MIN_RENDER_SPACING_MS) {
            // 0.8/M3c: return SILENCE, not the stand-in. The field caught this: the rail
            // correctly skipped a regeneration and then SPEAKED the RoutedExpress fallback
            // ("Just a sec — let me look that up."), because the old return was the fallback —
            // a guard meant to reduce noise produced a canned sentence. Callers treat a blank
            // return as "nothing to say", which is what a spacing skip actually means.
            VoxLog.d("event=er-render-skip reason=spacing")
            return ""
        }
        val t0 = System.currentTimeMillis()
        try {
            return render(intent, content, tone, spaced, if (hears) audio else null, t0)
        } finally {
            // 0.8/M1: the soul's OWN latency is the number that decides whether the
            // instant lane is viable (gate G2). Measured on-device, never estimated.
            // A skipped request returns above, so it cannot pollute this ring.
            ErTelemetry.gemmaRender(System.currentTimeMillis() - t0)
        }
    }

    /** The render itself, timed by [express]. */
    private fun render(intent: String, content: String, tone: String, spaced: Boolean = true, audio: ByteArray? = null, reqStartMs: Long = 0L): String {
        if (!loaded || llm == null) return fallback.express(intent, content, tone)   // soul intents -> "" (RoutedExpress)
        // The soul intents carry a complete directive (ErSoulTurn); anything else is the legacy
        // operator-directive shape.
        val prompt = if (intent in VoiceOrchestrator.SOUL_INTENTS) content
            else "Operator directive: intent=$intent. Content to render: $content"
        return try {
            generateWarmHeard(prompt, audio, reqStartMs, intent)
                // 0.6.3: the render rails — cap runaway output, enforce spacing.
                .let { ErGemmaGuard.checkRender(it, System.currentTimeMillis(), if (spaced) lastRenderAt else 0L) ?: "" }
                // The soul intents are parsed by the caller: blank means "nothing", never the
                // canned stand-in pretending to be the soul.
                .ifBlank { if (intent in VoiceOrchestrator.SOUL_INTENTS) "" else fallback.express(intent, content, tone) }
                .also { lastRenderAt = System.currentTimeMillis() }
        } catch (e: Throwable) {
            VoxLog.e("GemmaExpress gen failed: ${e.message}")
            if (intent in VoiceOrchestrator.SOUL_INTENTS) "" else fallback.express(intent, content, tone)
        }
    }

    // ---- The rolling soul conversation ----
    //
    // Every render used to build a FRESH Conversation, so the ~600-token persona was re-prefilled
    // on every call against a ~10-token output — which is where the ~2.2 s warm render went. The
    // 0.8 notes concluded "no reset, so no reuse"; reuse does not need a reset. The soul keeps ONE
    // conversation: the persona is prefilled once (at load, by the prime), and each render only
    // prefills its own directive. Its history is a feature, not a cost: the soul sees what it has
    // already said this session, which is exactly what stops a stuck-record repeat, and it hears
    // the caller's recent turns, which is contextual awareness for free. The KV cache is bounded,
    // so the conversation ROTATES before it fills (SoulBudget) and whenever the persona changes
    // (a VOX.md resync).

    private val genLock = Any()
    private var soulConv: Conversation? = null
    private var soulConvPersona: String? = null
    @Volatile private var cancelRequested = false

    /** A heard render; if the audio path fails, drop the soul's ears for the session and render
     *  the same directive text-only — the turn still gets its decision. */
    private fun generateWarmHeard(prompt: String, audio: ByteArray?, reqStartMs: Long = 0L, tag: String = ""): String {
        if (audio == null) return generateWarm(prompt, null, reqStartMs, tag)
        val t0 = System.currentTimeMillis()
        return try {
            generateWarm(prompt, audio, reqStartMs, tag).also {
                VoxLog.d("event=soul-heard audioBytes=${audio.size} ms=${System.currentTimeMillis() - t0}")
            }
        } catch (e: Throwable) {
            hears = false
            VoxLog.e("GemmaExpress: heard render failed (${e.message}) — the soul reads words only this session")
            generateWarm(prompt, null, reqStartMs, tag)
        }
    }

    /** Render [prompt] on the warm soul conversation, creating or rotating it as needed. */
    private fun generateWarm(prompt: String, audio: ByteArray? = null, reqStartMs: Long = 0L, tag: String = ""): String {
        val engine = llm ?: return ""
        val p = persona
        synchronized(genLock) {
            cancelRequested = false
            var conv = soulConv
            val tokens = try { conv?.getTokenCount() ?: 0 } catch (_: Throwable) { SoulBudget.MAX_TOKENS }
            val alive = try { conv?.isAlive == true } catch (_: Throwable) { false }
            if (conv == null || !alive || soulConvPersona != p || MemoryBudget.mustRotate(tokens, kvTokens)) {
                if (conv != null) VoxLog.d("event=soul-conv-rotate tokens=$tokens alive=$alive personaChanged=${soulConvPersona != p}")
                closeSoul()
                conv = engine.createConversation(ConversationConfig(
                    systemInstruction = Contents.of(p),
                    maxOutputToken = 256,
                ))
                soulConv = conv; soulConvPersona = p
            }
            return try {
                val out = send(conv!!, prompt, audio, reqStartMs, tag)
                if (cancelRequested) "" else out
            } catch (t: Throwable) {
                closeSoul()   // a failed conversation is never reused
                throw t
            }
        }
    }

    private fun closeSoul() {
        try { soulConv?.close() } catch (_: Throwable) {}
        soulConv = null; soulConvPersona = null
    }

    /** Stop the render in flight (a turn decision is pre-empting a narration). Its output is
     *  discarded — a cancelled narration must never be spoken half-formed. */
    override fun cancelInFlight() {
        cancelRequested = true
        try { soulConv?.cancelProcess() } catch (_: Throwable) {}
    }

    /**
     * The raw generation on a given persona: build a conversation, send, collect. No rails and no
     * state — [render] owns those, and the prefill probe needs to measure the generation alone.
     */
    private fun generate(personaOverride: String, prompt: String): String {
        val engine = llm ?: return ""
        return synchronized(genLock) {
            engine.createConversation(ConversationConfig(
                systemInstruction = Contents.of(personaOverride),
                    // 0.8/M3: bounds a presence line, and sits ABOVE ErGemmaGuard's char
                    // cap so the guard stays the binding UX control and this is only the
                    // hard backstop against a runaway render. 256 tokens (~1.7x the char
                    // guard) leaves room for the soul to hold a short conversation rather
                    // than being cut off mid-thought.
                    maxOutputToken = 256,
                )).use { conv -> send(conv, prompt) }
        }
    }

    /** One message on [conv], collected via the callback API. [audio] (a WAV) rides the same
     *  message ahead of the text, so the model hears the caller before it reads the directive. */
    private fun send(conv: Conversation, prompt: String, audio: ByteArray? = null, reqStartMs: Long = 0L, tag: String = ""): String {
        run {
                    // 0.6.6: the CALLBACK API, not the Flow API. The Flow overload's
                    // onDone closes the ProducerScope channel
                    // (SendChannel.close$default) — a method reference that does
                    // not resolve in the R8-processed APK (the field crash:
                    // NoSuchMethodError inside litertlm's own callback on the
                    // FIRST tool-call turn of a call). The callback overload
                    // builds no Flow/channel at all: onMessage appends, onDone
                    // latches, onError records. Same data path, no version
                    // coupling to the coroutines binary.
                    val sb = StringBuilder()
                    val done = java.util.concurrent.CountDownLatch(1)
                    var error: Throwable? = null
                    // Phase 1 (#209 gap 2): the presence render's FIRST TOKEN, on the same
                    // VoxLog stream as event=turn so the two can be put side by side. Until now
                    // only the whole render was timed (`event=er-soul ms=`), which is why
                    // PRESENCE-BENCH's 289 ms first-token baseline could never be compared to
                    // a field row. Logged once per request; a warm-up/probe render (no start
                    // passed) does not log, exactly as it does not feed ErTelemetry.
                    var firstTokenLogged = false
                    val cb = object : com.google.ai.edge.litertlm.MessageCallback {
                        override fun onMessage(m: com.google.ai.edge.litertlm.Message) {
                            // The message's Contents may carry text and/or tool
                            // calls; the presence layer only renders TEXT parts.
                            for (c in m.contents.contents) {
                                if (c is com.google.ai.edge.litertlm.Content.Text) {
                                    if (!firstTokenLogged && reqStartMs > 0L && c.text.isNotEmpty()) {
                                        firstTokenLogged = true
                                        VoxLog.d("event=er-soul-first-token ms=${System.currentTimeMillis() - reqStartMs} intent=$tag")
                                    }
                                    sb.append(c.text)
                                }
                            }
                        }
                        override fun onDone() { done.countDown() }
                        override fun onError(t: Throwable) { error = t; done.countDown() }
                    }
                    if (audio != null) conv.sendMessageAsync(Contents.of(Content.AudioBytes(audio), Content.Text(prompt)), cb)
                    else conv.sendMessageAsync(prompt, cb)
                    if (!done.await(20, java.util.concurrent.TimeUnit.SECONDS)) {
                        try { conv.cancelProcess() } catch (_: Throwable) {}
                        throw IllegalStateException("soul render timed out")
                    }
                    error?.let { throw it }
                    return sb.toString().trim()
                }
    }
    @Volatile private var lastRenderAt = 0L

    companion object {
        /**
         * The fast-lane persona: used by the prefill probe and nothing else. A few dozen characters
         * instead of a full VOX.md — the same voice-not-mind instruction, stripped to its bones. If
         * the probe confirms the prefill split, this is the seed of the real fast lane, and the
         * reason the beat can be generated rather than pre-authored.
         *
         * Kept short ON PURPOSE. Fattening it would silently void the comparison it exists to make,
         * so a test pins its length.
         */
        val MINIMAL_PERSONA =
            "You are the voice of an agent on a phone call. Reply in one short spoken sentence."

        /** Primes the rolling conversation at load (and times the warm render in the probe).
         *  Phrased as part of the story so it is harmless history for the soul. */
        val PRIME_DIRECTIVE =
            "(The phone voice client has started. No call is connected yet. Reply with exactly " +
                ErSoulTurn.ESCALATE + ".)"

        /** Above this, the probe's full-vs-minimal comparison stops measuring anything. */
        const val MAX_MINIMAL_PERSONA_CHARS = 150
    }
}
