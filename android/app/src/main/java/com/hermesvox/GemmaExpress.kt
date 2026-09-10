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

    /** Build the engine, preferring the GPU. Returns the first backend that
     *  initializes; throws the CPU attempt's error if neither works (the caller
     *  then reports the layer unavailable, exactly as before). */
    private fun initEngine(): Pair<Engine, String> {
        val attempts: List<Pair<String, () -> Backend>> =
            listOf("gpu" to { Backend.GPU() }, "cpu" to { Backend.CPU() })
        var lastError: Throwable? = null
        for ((name, backend) in attempts) {
            try {
                val e = Engine(EngineConfig(modelPath = modelFile.absolutePath, backend = backend()))
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
        val engine = llm
        if (!loaded || engine == null) return fallback.express(intent, content, tone)
        val prompt = "Operator directive: intent=$intent. Content to render: $content"
        return try {
            runBlocking {
                engine.createConversation(ConversationConfig(systemInstruction = Contents.of(persona))).use { conv ->
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
