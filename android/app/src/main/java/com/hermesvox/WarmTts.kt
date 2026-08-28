package com.hermesvox

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.io.File
import java.util.Locale

/**
 * VoxTts abstracts the text-to-speech leg so the pipeline can pick a warm,
 * low-latency on-device voice (Kokoro/Piper via sherpa-onnx) when the models
 * are present, and ALWAYS fall back to the Android system TTS.
 *
 * Local-first: no cloud keys. On-device models are user-sideloaded to
 * Context.filesDir/tts/ (documented in Settings). If missing/incompatible,
 * isWarm=false and the controller uses SystemTts without breaking a turn.
 */
interface VoxTts {
    val name: String
    val isWarm: Boolean            // true when a warm on-device voice is actually loaded
    val warmReason: String         // why warm audio is/in't available ("", "model missing", …)
    fun init(onReady: (Boolean) -> Unit)
    fun speak(text: String, onDone: () -> Unit)
    /** True when this engine can synth+play chunks sequentially (reply-streaming TTS). */
    val supportsStreaming: Boolean get() = false
    /** Blocking synth+play on the caller's thread; false if unsupported / not warm. */
    fun speakBlocking(text: String): Boolean = false
    fun stop()
    fun shutdown()
}

/** The Android system TTS — always available, the guaranteed fallback. */
class SystemTts(private val context: Context) : VoxTts {
    private var tts: TextToSpeech? = null
    private val main = Handler(Looper.getMainLooper())
    override val name get() = "System"
    override val isWarm get() = false
    override val warmReason get() = ""
    override fun init(onReady: (Boolean) -> Unit) {
        val (rate, pitch) = voiceRegister(currentVoiceRegister(context))
        try {
            tts?.shutdown()
            tts = TextToSpeech(context) { code ->
                if (code == TextToSpeech.SUCCESS) {
                    tts?.setPitch(pitch)
                    tts?.setSpeechRate(rate)
                    tts?.language = Locale.US
                }
                onReady(code == TextToSpeech.SUCCESS)
            }
        } catch (_: Throwable) { onReady(false) }
    }
    override fun speak(text: String, onDone: () -> Unit) {
        val t = tts ?: return onDone()
        // Post per-utterance completion via a polling check; QUEUE_FLUSH replaces prior speech.
        t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hv")
        scheduleDone(onDone, text.length)
    }
    private fun scheduleDone(onDone: () -> Unit, chars: Int) {
        val delay = (chars * 70L).coerceAtLeast(400L)   // ~70ms/char heuristic; not precise but non-blocking
        main.postDelayed({ if (tts?.isSpeaking == false) onDone() }, delay + 200)
    }
    override fun stop() { try { tts?.stop() } catch (_: Throwable) {} }
    override fun shutdown() { try { tts?.shutdown() } catch (_: Throwable) {} }
}

/**
 * WarmTts — Kokoro/Piper via sherpa-onnx, local-first. The on-device runtime
 * + model are OPTIONAL and user-sideloaded; when absent this reports isWarm=false
 * so the pipeline uses SystemTts (never a broken turn, never a cloud key).
 */
class WarmTts(private val context: Context) : VoxTts {
    override val name get() = "Kokoro"
    override val isWarm: Boolean get() = false  // set false until a verified sherpa-onnx runtime + model are shipped
    override val warmReason: String
        get() = "model not yet installed — sideload .onnx to filesDir/tts (see Settings)"
    override fun init(onReady: (Boolean) -> Unit) {
        // The software path: check for a sideloaded model dir so the fallback is
        // honest about what's present. If a later build ships the sherpa-onnx AAR,
        // initialize the runtime here.
        val dir = File(context.filesDir, "tts")
        val present = dir.exists() && dir.listFiles()?.isNotEmpty() == true
        if (present) {
            // Runtime not bundled yet → warm playback not achievable this build.
            onReady(false)
        } else {
            onReady(false)
        }
    }
    override fun speak(text: String, onDone: () -> Unit) { onDone() } // never reached while isWarm=false
    override fun stop() {}
    override fun shutdown() {}

    companion object {
        // Well-known local model names the app looks for under filesDir/tts.
        const val KOKORO = "kokoro-en.onnx"
        const val PIPER = "voice-en.onnx"
    }
}

/** Builds the configured VoxTts, HONORING the `tts` pref (#31). `system` yields
 *  SystemTts, `piper` yields SherpaTts (when the Piper model is installed, else an
 *  honest fallback to System), and `kokoro` yields the Kokoro backend (WarmTts)
 *  when it can honestly produce audio, else an honest fallback to System. An
 *  unknown/blank token resolves to SystemTts — never a silent or ignore-the-pref
 *  engine. */
fun buildTts(context: Context, prefer: String): VoxTts {
    return try {
        when (prefer) {
            "piper" -> if (ModelCatalog.isInstalled(context, "piper-lessac")) SherpaTts(context)
                else { VoxLog.d("tts: piper requested but model not installed -> system fallback"); SystemTts(context) }
            "kokoro" -> kokoroBackend(context)
            else -> SystemTts(context)   // "system" (and unknown tokens) -> system
        }
    } catch (_: Throwable) { SystemTts(context) }
}

/** The Kokoro backend: WarmTts. Its runtime is a stub (isWarm=false until a real
 *  kokoro model ships), so pick it only when it can actually produce audio and
 *  otherwise fall back to SystemTts — never a silent reply. */
private fun kokoroBackend(context: Context): VoxTts {
    val warm = WarmTts(context)
    return if (warm.isWarm) warm else {
        VoxLog.d("tts: kokoro runtime unavailable -> system fallback")
        SystemTts(context)
    }
}

/** The current `voice` register token (system/bright/deep). Default "system". */
fun currentVoiceRegister(context: Context): String =
    context.getSharedPreferences("hv", android.content.Context.MODE_PRIVATE).getString("voice", "system") ?: "system"

/** Map the `voice` pref to a synthesis register: how faster/higher (bright) or
 *  slower/lower (deep) the selected engine speaks vs the neutral register
 *  (system). Returns (speechRate/speed, pitch). The dead "warm" option was
 *  removed (WarmTts is a no-op stub, so it is not offered). Register 1.0/1.0
 *  keeps output identical to the shipped default when untouched. */
fun voiceRegister(pref: String): Pair<Float, Float> = when (pref) {
    "bright" -> 1.12f to 1.15f
    "deep" -> 0.88f to 0.85f
    else -> 1.0f to 1.0f   // system (default)
}
