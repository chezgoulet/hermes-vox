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
    val file: String,        // <source>/<file>.zip
    val sizeMB: Double,
    val desc: String,
    val blessed: Boolean,
    val order: Int,
    val sha256: String,
    val recommended: Boolean
)

object ModelCatalog {
    const val KEY_SOURCE = "model_source"
    // House Thelio model store (tailnet). On the emulator, adb reverse maps
    // 127.0.0.1:8899 -> the emulator host, and the source can be overridden to
    // http://127.0.0.1:8899 for a no-tailnet local demo.
    const val DEFAULT_SOURCE = "http://100.68.43.34:8899"

    val blessed: List<ModelSpec> = listOf(
        ModelSpec("silero-vad", "Silero VAD", "vad", "silero-vad.zip", 0.5,
            "Barge-in / wake trigger (replaces the RMS hack)", true, 1,
            "2a886b4485cc092bccf0f4dc9604ff0bd9c654c04cab89f78bd020205620a7b2", true),
        ModelSpec("piper-lessac", "Piper · en-US (LibriTTS-R, medium)", "tts", "piper-lessac.zip", 78.0,
            "Warm on-device TTS (sherpa-onnx)", true, 2,
            "42b6d91ac52bee3ddcd7ee6fbaa9590778b915d11bd838f0aafc8c701485f001", true),
        ModelSpec("whisper-tiny", "Whisper tiny.en", "stt", "whisper-tiny.zip", 86.0,
            "Offline STT · fastest, lightest", true, 3,
            "b5cd001147d9933d148f8c701b3a984ab5f8dfc03dc7fe3fb885ca5526c0f3b3", false),
        ModelSpec("whisper-base", "Whisper base.en", "stt", "whisper-base.zip", 162.0,
            "Offline STT · blessed default (balanced)", true, 4,
            "1b9ce55b15fbf2f09893640a1dd1c1062f4963fa90b2d0f97a13eca2e0f9ab84", true),
        ModelSpec("whisper-small", "Whisper small.en", "stt", "whisper-small.zip", 540.0,
            "Offline STT · best accuracy, heaviest", true, 5,
            "b1549f51778a7d919b787883505e02c15501766e32e4ff8ad0572e92c2c5abe8", false),
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
    const val KEY_STT_HOUSE_URL = "stt_house_url"
    const val KEY_VOICE_MODE = "voice_mode"
    const val BACKEND_ONDEVICE = "on-device"
    const val BACKEND_HOUSE = "house"
    const val BACKEND_PLATFORM = "platform"
    const val MODE_REALTIME = "realtime"          // emulated call, local S2P + Hermes
    const val MODE_ENHANCED = "enhanced"          // + on-device Gemma presence layer
    const val MODE_WALKIE = "walkie"              // push-to-talk + keyboard

    fun source(context: Context): String =
        context.getSharedPreferences("hv", Context.MODE_PRIVATE).getString(KEY_SOURCE, DEFAULT_SOURCE) ?: DEFAULT_SOURCE

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
