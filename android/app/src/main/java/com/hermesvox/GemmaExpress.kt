package com.hermesvox

import android.content.Context
import java.io.File

/**
 * GemmaExpress — the ON-DEVICE LiteRT-LM backend for the phone-call presence
 * layer (Gemma 4 E2B, litertlm). Implements VoxExpress: it loads the on-device
 * model + renders a Hermes-pushed directive as natural conversational glue.
 *
 * IRONCLAD (per the architecture contract):
 *  - Gemma NEVER runs the agentic stream: no tool interface is exposed here,
 *    no function-calling surfaced, no authority. It only EXPRESSES.
 *  - If the model isn't installed/loadable, it gracefully falls back to the
 *    RoutedExpress stand-in (the phone-call conversation still works) — the
 *    presence never breaks.
 *
 * The LiteRT-LM native runtime + a real device (Tensor NPU) is where the model
 * actually runs fast; this is the integration seam. The on-device run/verify is
 * a device step (the x86_64 emulator is not LiteRT-LM's runtime).
 */
class GemmaExpress(private val context: Context) : VoxExpress {

    private var loaded = false
    private var fallback = RoutedExpress()

    override val available: Boolean get() = loaded

    private val modelDir get() = File(context.filesDir, "models/gemma-e2b")
    private val modelFile get() = File(modelDir, "gemma-4-E2B-it.litertlm")

    /** Load the on-device LiteRT-LM model (async). onReady(true) when loaded. */
    fun load(onReady: (Boolean) -> Unit) {
        if (modelFile.exists()) {
            // TODO(device): init the LiteRT-LM runtime + load the .litertlm
            //   model here via the Google AI Edge / litert-lm C API + JNI.
            //   On a Tensor NPU this is the fast path. For now mark loaded so
            //   the orchestration wiring can exercise the seam.
            loaded = true
            VoxLog.d("GemmaExpress loaded: $modelFile")
            onReady(true)
        } else {
            loaded = false
            VoxLog.d("GemmaExpress not installed (no $modelFile) — using RoutedExpress")
            onReady(false)
        }
    }

    override fun express(intent: String, content: String, tone: String): String {
        return if (loaded) {
            // TODO(device): call the loaded LiteRT-LM Gemma with the persona +
            //   the pushed directive; return its rendered expression.
            fallback.express(intent, content, tone)
        } else {
            fallback.express(intent, content, tone)
        }
    }
}
