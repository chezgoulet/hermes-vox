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
    val url: String = ""   // optional full canonical URL override (else <source>/<file>)
)

object ModelCatalog {
    const val KEY_SOURCE = "model_source"
    // The model source URL is user-entered (Settings -> Voice models). Generic —
    // no house-specific default. Leave empty so the user supplies their own.
    const val DEFAULT_SOURCE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models"

    val blessed: List<ModelSpec> = listOf(
        ModelSpec("silero-vad", "Silero VAD", "vad", "silero_vad.onnx", 0.5,
            "Barge-in / wake trigger (replaces the RMS hack)", true, 1,
            "", true),
        ModelSpec("piper-lessac", "Piper · en-US (LibriTTS-R, medium)", "tts", "", 78.0,
            "Warm on-device TTS (sherpa-onnx)", true, 2,
            "", true,
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/piper/piper-en_US-libritts_r-medium.tar.bz2"),
        ModelSpec("whisper-tiny", "Whisper tiny.en", "stt", "sherpa-onnx-whisper-tiny.en.tar.bz2", 86.0,
            "Offline STT · fastest, lightest", true, 3,
            "", false),
        ModelSpec("whisper-base", "Whisper base.en", "stt", "sherpa-onnx-whisper-base.en.tar.bz2", 162.0,
            "Offline STT · blessed default (balanced)", true, 4,
            "", true),
        ModelSpec("whisper-small", "Whisper small.en", "stt", "sherpa-onnx-whisper-small.en.tar.bz2", 540.0,
            "Offline STT · best accuracy, heaviest", true, 5,
            "", false),
        ModelSpec("gemma-e2b", "Gemma 4 E2B (presence)", "express", "gemma-e2b.zip", 2050.0,
            "On-device expression layer (Enhanced Realtime)", true, 6,
            "877db5533f7ddb7f0438e3fa4cedc49dedebd4c4f66f22e5295cee351e75aadc", false)
    )

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
    const val MODE_REALTIME = "realtime"          // emulated call, local S2P + Hermes
    const val MODE_ENHANCED = "enhanced"          // + on-device Gemma presence layer
    const val MODE_WALKIE = "walkie"              // push-to-talk + keyboard

    fun source(context: Context): String =
        context.getSharedPreferences("hv", Context.MODE_PRIVATE).getString(KEY_SOURCE, DEFAULT_SOURCE) ?: DEFAULT_SOURCE

    // custom model-store URI is a roadmap feature (field kept for later)

    fun modelDir(context: Context, id: String) = File(context.filesDir, "models/$id")

    fun isInstalled(context: Context, id: String): Boolean {
        val d = modelDir(context, id)
        return d.exists() && d.listFiles()?.isNotEmpty() == true
    }

    /** The blessed default for a pipeline kind; null if not yet downloaded. */
    fun defaultModel(kind: String): ModelSpec? = blessed
        .firstOrNull { it.kind == kind && it.blessed }
        ?.takeIf { true }
}
