package com.hermesvox

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.runBlocking  // still used to park the express() thread
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
                llm = e; loaded = true; activeBackend = backend
                VoxLog.d("GemmaExpress loaded: $modelFile backend=$backend")
                warmUp()
                onReady(true)
            } catch (e: Throwable) {
                VoxLog.e("GemmaExpress load failed: ${e.message}")
                loaded = false; llm = null; onReady(false)
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
        for (i in 1..2) {
            val t0 = System.currentTimeMillis()
            try { render("working", "", "calm") } catch (_: Throwable) {}
            VoxLog.d("GemmaExpress warm-up $i/2 ms=${System.currentTimeMillis() - t0}")
            if (i == 1) try { Thread.sleep(1300) } catch (_: InterruptedException) {}
        }
    }

    /** Build the engine, preferring the GPU. Returns the first backend that
     *  initializes; throws the CPU attempt's error if neither works (the caller
     *  then reports the layer unavailable, exactly as before). */
    private fun initEngine(): Pair<Engine, String> {
        val attempts: List<Pair<String, () -> Backend>> =
            listOf("gpu" to { Backend.GPU() }, "cpu" to { Backend.CPU() })
        var lastError: Throwable? = null
        for ((name, backend) in attempts) {
            try {
                val e = Engine(EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = backend(),
                    // 0.8/M3: maxNumTokens IS the kv-cache size (LiteRT-LM KDoc: "equivalent
                    // to the size of the kv-cache"). Left null it inherits the model's
                    // full 32k context, which is 4x more than this layer can ever use:
                    // the persona is ~600 tokens and a render is capped at 256 output.
                    // 8k keeps real headroom for a growing VOX.md and for bounded
                    // conversation reuse later, while cutting the cache 4x.
                    maxNumTokens = 8192,
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
                return e to name
            } catch (t: Throwable) {
                lastError = t
                VoxLog.e("GemmaExpress: $name backend init failed: ${t.message}")
            }
        }
        throw lastError ?: IllegalStateException("GemmaExpress: no backend available")
    }

    private val loadLock = Object()
    @Volatile private var loading = false

    override fun express(intent: String, content: String, tone: String): String {
        // 0.8/M2.2: rail 3 BEFORE the generation, not after. The spacing check used to
        // run on the produced text, so a too-soon request paid for a full generation
        // and then discarded it. Same outcome (the guard returned null -> the caller
        // fell back to the routed line), no wasted render.
        if (System.currentTimeMillis() - lastRenderAt < ErGemmaGuard.MIN_RENDER_SPACING_MS) {
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
            return render(intent, content, tone)
        } finally {
            // 0.8/M1: the soul's OWN latency is the number that decides whether the
            // instant lane is viable (gate G2). Measured on-device, never estimated.
            // A skipped request returns above, so it cannot pollute this ring.
            ErTelemetry.gemmaRender(System.currentTimeMillis() - t0)
        }
    }

    /** The render itself, timed by [express]. */
    private fun render(intent: String, content: String, tone: String): String {
        val engine = llm
        if (!loaded || engine == null) return fallback.express(intent, content, tone)
        val prompt = "Operator directive: intent=$intent. Content to render: $content"
        return try {
            runBlocking {
                engine.createConversation(ConversationConfig(
                    systemInstruction = Contents.of(persona),
                    // 0.8/M3: bounds a presence line, and sits ABOVE ErGemmaGuard's char
                    // cap so the guard stays the binding UX control and this is only the
                    // hard backstop against a runaway render. 256 tokens (~1.7x the char
                    // guard) leaves room for the soul to hold a short conversation rather
                    // than being cut off mid-thought.
                    maxOutputToken = 256,
                )).use { conv ->
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
                    conv.sendMessageAsync(prompt, object : com.google.ai.edge.litertlm.MessageCallback {
                        override fun onMessage(m: com.google.ai.edge.litertlm.Message) {
                            // The message's Contents may carry text and/or tool
                            // calls; the presence layer only renders TEXT parts.
                            for (c in m.contents.contents) {
                                if (c is com.google.ai.edge.litertlm.Content.Text) sb.append(c.text)
                            }
                        }
                        override fun onDone() { done.countDown() }
                        override fun onError(t: Throwable) { error = t; done.countDown() }
                    })
                    done.await(20, java.util.concurrent.TimeUnit.SECONDS)
                    error?.let { throw it }
                    sb.toString()
                }
            }.trim()
                // 0.6.3: the render rails — cap runaway output, enforce spacing.
                .let { ErGemmaGuard.checkRender(it, System.currentTimeMillis(), lastRenderAt) ?: "" }
                .ifBlank { fallback.express(intent, content, tone) }
                .also { lastRenderAt = System.currentTimeMillis() }
        } catch (e: Throwable) {
            VoxLog.e("GemmaExpress gen failed: ${e.message}")
            fallback.express(intent, content, tone)
        }
    }
    @Volatile private var lastRenderAt = 0L
}
