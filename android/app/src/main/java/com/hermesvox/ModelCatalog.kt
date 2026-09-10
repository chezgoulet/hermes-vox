package com.hermesvox

import android.content.Context
import java.io.File

/**
 * ModelCatalog — the BLESSED on-device model set (the "best path" defaults).
 * Each entry is a self-contained ZIP the app downloads into app-private storage
 * (filesDir/models/<id>/), verifies by sha256, and unpacks. The pipeline prefers
 * these in order (silero VAD for barge-in, Piper for warm TTS, Whisper for
 * offline STT); the source host + which models to install are user-visible.
 *
 * Sovereign/local-first: model FILES come from an open-source store (the house
 * Thelio by default, adjustable in Settings); inference runs fully offline.
 */
data class ModelSpec(
    val id: String,
    val name: String,
    val kind: String,        // "vad" | "tts" | "stt" | "llm"
    val file: String,        // <source>/<file> (canonical artifact on DEFAULT_SOURCE)
    val sizeMB: Double,
    val desc: String,
    val blessed: Boolean,
    val order: Int,
    val sha256: String,
    val recommended: Boolean,
    val url: String = "",  // optional full canonical URL override (else <source>/<file>)
    val purpose: String = ""  // one-line plain-language "what this model is for" (model card, #16)
)

object ModelCatalog {
    const val KEY_SOURCE = "model_source"
    // The model source URL is user-entered (Settings -> Voice models). Generic —
    // no house-specific default. Leave empty so the user supplies their own.
    const val DEFAULT_SOURCE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"

    val blessed: List<ModelSpec> = listOf(
        // sizeMB = the REAL download size in decimal MB, from each artifact's
        // Content-Length. The whole table was systematically understated before
        // (Gemma by ~540 MB, whisper-small by ~96 MB) and ModelsActivity renders
        // this number directly, so it has to be the true cost of the download.
        ModelSpec("silero-vad", "Silero VAD", "vad", "silero_vad.onnx", 0.6,
            "Barge-in / wake trigger (replaces the RMS hack)", true, 1,
            "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6", true, "",
            "Hears when you start or stop speaking, so you can interrupt hands-free"),
        ModelSpec("piper-lessac", "Piper · en-US (LibriTTS-R, medium)", "tts", "vits-piper-en_US-libritts_r-medium.tar.bz2", 82.0,
            "Piper en-US canonical LibriTTS-R medium", true, 2,
            "10dc268f3e371696d721486123e2705a9fc1faa113491979fde4d88dba1f1b1c", true,
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-libritts_r-medium.tar.bz2",
            "Speaks Hermes' replies aloud in a natural voice — fully offline"),
        ModelSpec("whisper-tiny", "Whisper tiny.en", "stt", "sherpa-onnx-whisper-tiny.en.tar.bz2", 118.1,
            "Offline STT · fastest, lightest", true, 3,
            "2bd6cf965c8bb3e068ef9fa2191387ee63a9dfa2a4e37582a8109641c20005dd", false, "",
            "Turns your speech into text on-device — the fast, light option"),
        ModelSpec("whisper-base", "Whisper base.en", "stt", "sherpa-onnx-whisper-base.en.tar.bz2", 208.6,
            "Offline STT · blessed default (balanced)", true, 4,
            "475bc7052ce299c007f6d5d5407ba8601f819a2867f6eecee510ed17df581542", true, "",
            "Turns your speech into text on-device — the balanced default"),
        ModelSpec("whisper-small", "Whisper small.en", "stt", "sherpa-onnx-whisper-small.en.tar.bz2", 635.7,
            "Offline STT · best accuracy, heaviest", true, 5,
            "0cdba2b8aaab69e04847f3427cc9709574112e67913a1a84b7fec3a8729faa9a", false, "",
            "Turns your speech into text on-device — most accurate, heavier"),
        ModelSpec("gemma-e2b", "Gemma 4 E2B (presence)", "express", "gemma-4-E2B-it.litertlm", 2588.1,
            "On-device expression layer (Enhanced Realtime, alpha)", true, 6,
            "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c", false,
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            "Optional on-device presence layer for Enhanced Realtime (alpha) mode — runs on the GPU")
    )

    // #114-denominator: REQUIRED and RECOMMENDED are THE SAME SET, everywhere.
    // The Settings home badge, the Models header, the "install all" button and
    // the Main-screen gate all count blessed.filter { it.recommended } so the
    // number a user sees in Settings is the number this screen shows.
    val required: List<ModelSpec> get() = blessed.filter { it.recommended }

    /** Required models that are not yet installed (app-private model dirs). */
    fun missingRequired(context: Context): List<ModelSpec> = required.filter { !isInstalled(context, it.id) }

    /** The on-device STT model -> model-id map (Settings STT model picker). */
    val sttModels = listOf(
        "whisper-tiny" to "Whisper tiny.en",
        "whisper-base" to "Whisper base.en",
        "whisper-small" to "Whisper small.en"
    )
    /** Blessed default STT model id. */
    const val DEFAULT_STT_MODEL = "whisper-base"

    const val KEY_STT_BACKEND = "stt_backend"
    const val KEY_STT_MODEL = "stt_model"
    const val KEY_VOICE_MODE = "voice_mode"
    const val BACKEND_ONDEVICE = "on-device"
    const val BACKEND_PLATFORM = "platform"
    const val BACKEND_REMOTE = "remote"
    const val MODE_REALTIME = "realtime"          // emulated call, local S2P + Hermes
    const val MODE_ENHANCED = "enhanced"          // + on-device Gemma presence layer

    /**
     * Pure resolver for a model-source override. Blank/null input falls back to
     * [DEFAULT_SOURCE]. Any input that does not match the scheme `^https?://`
     * (case-insensitive) is rejected (a scheme-less `host:8899` or `host.lan`
     * would otherwise reach `URL()` and throw "no protocol"/"unknown protocol");
     * such values also fall back to [DEFAULT_SOURCE]. Otherwise the trimmed
     * input is returned as-is.
     */
    fun resolveSource(input: String?): String {
        if (input.isNullOrBlank()) return DEFAULT_SOURCE
        val v = input.trim()
        return if (v.matches(Regex("^https?://.*", RegexOption.IGNORE_CASE))) v else DEFAULT_SOURCE
    }

    fun source(context: Context): String {
        // Enforce the canonical-upstream decision: a BLANK/missing override falls back to
        // the k2-fsa web upstream, so the downloader never builds a scheme-less "/file"
        // URL (the "no protocol" error). Only a genuine non-blank custom source overrides.
        val v = context.getSharedPreferences("hv", Context.MODE_PRIVATE).getString(KEY_SOURCE, DEFAULT_SOURCE)
        return resolveSource(v)
    }

    // custom model-store URI is a roadmap feature (field kept for later)

    fun modelDir(context: Context, id: String) = File(context.filesDir, "models/$id")

    fun isInstalled(context: Context, id: String): Boolean {
        val d = modelDir(context, id)
        val markers = when (id) {
            "whisper-tiny", "whisper-base", "whisper-small" -> listOf("encoder.onnx", "decoder.onnx", "tokens.txt")
            "silero-vad" -> listOf("silero_vad.onnx")
            "piper-lessac" -> listOf("model.onnx", "tokens.txt")
            "gemma-e2b" -> listOf("gemma-4-E2B-it.litertlm")
            else -> return d.exists() && (d.listFiles()?.isNotEmpty() == true)
        }
        return markers.all { File(d, it).exists() }
    }

    /** The blessed default for a pipeline kind; null if not yet downloaded. */
    fun defaultModel(kind: String): ModelSpec? = blessed
        .firstOrNull { it.kind == kind && it.blessed }
        ?.takeIf { true }
}
