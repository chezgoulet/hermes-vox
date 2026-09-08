package com.hermesvox

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.runBlocking
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
    private var loaded = false
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
        kotlin.concurrent.thread {
            try {
                if (!modelFile.exists()) { loaded = false; onReady(false); return@thread }
                val e = Engine(EngineConfig(modelPath = modelFile.absolutePath, backend = Backend.CPU()))
                e.initialize()
                llm = e; loaded = true
                VoxLog.d("GemmaExpress loaded: $modelFile")
                onReady(true)
            } catch (e: Throwable) {
                VoxLog.e("GemmaExpress load failed: ${e.message}")
                loaded = false; llm = null; onReady(false)
            }
        }
    }

    override fun express(intent: String, content: String, tone: String): String {
        val engine = llm
        if (!loaded || engine == null) return fallback.express(intent, content, tone)
        val prompt = "Operator directive: intent=$intent. Content to render: $content"
        return try {
            runBlocking {
                engine.createConversation(ConversationConfig(systemInstruction = Contents.of(persona))).use { conv ->
                    buildString { conv.sendMessageAsync(prompt).collect { append(it) } }
                }
            }.trim().ifBlank { fallback.express(intent, content, tone) }
        } catch (e: Throwable) {
            VoxLog.e("GemmaExpress gen failed: ${e.message}")
            fallback.express(intent, content, tone)
        }
    }
}
