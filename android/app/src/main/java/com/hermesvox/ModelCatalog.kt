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
        ModelSpec("whisper-tiny", "Whisper (tiny.en)", "stt", "whisper-tiny.zip", 86.0,
            "Offline speech-to-text (replaces Google STT)", true, 3,
            "b5cd001147d9933d148f8c701b3a984ab5f8dfc03dc7fe3fb885ca5526c0f3b3", true)
    )

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
