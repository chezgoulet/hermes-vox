package com.hermesvox

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * #373: how one utterance ended. The engine names its own outcome, because a fallback
 * that dies has to be distinguishable from one that was deliberately cut — a barge or a
 * hang-up must not trigger an engine rebuild, and an engine that returns an error must.
 */
enum class TtsEnd {
    /** The whole utterance was spoken. */
    SPOKEN,
    /** The utterance was cut on purpose (stop / barge / hang-up / a newer utterance). */
    INTERRUPTED,
    /** The engine could not speak it. [TtsOutcome.code] carries the engine's own reason. */
    FAILED,
}

/** One utterance's result. [code] is the engine's own error (a TextToSpeech error code, a
 *  sherpa exception message, `timeout`) so a field log can settle the question without a
 *  device session. */
data class TtsOutcome(val end: TtsEnd, val code: String = "")

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

    /**
     * #373: speak, and say how it ended. The default keeps every existing engine (and
     * every test fake) working: it reports SPOKEN when [speak] returned. Engines that can
     * see a failure — the system engine's error codes, sherpa's exceptions — override it
     * so a dead engine is diagnosable instead of merely quiet.
     */
    fun speakReporting(text: String, onDone: (TtsOutcome) -> Unit) {
        speak(text) { onDone(TtsOutcome(TtsEnd.SPOKEN)) }
    }

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
    @Volatile private var reason: String = ""
    /** The last per-utterance failure the engine itself reported ("" = none). */
    @Volatile private var lastError: String = ""
    private var seq = 0
    /** The live utterance's finish, so stop() can report a deliberate cut instead of
     *  waiting for a callback the platform may never send. */
    @Volatile private var currentFinish: ((TtsOutcome) -> Unit)? = null
    override val name get() = "System"
    override val isWarm get() = false
    override val warmReason get() = reason.ifBlank { lastError }
    override fun init(onReady: (Boolean) -> Unit) {
        val (rate, pitch) = voiceRegister(currentVoiceRegister(context))
        try {
            currentFinish = null
            tts?.shutdown()
            tts = TextToSpeech(context) { code ->
                if (code != TextToSpeech.SUCCESS) {
                    // No engine installed/enabled for this user, or the engine refused to start.
                    // Name it: a call must not be reported as "models failed to load" for this.
                    // #346: `engines` distinguishes the two cases on Android 11+ — an empty list
                    // means the engine package is not visible to us (manifest query missing),
                    // not that no engine is installed.
                    val visible = describeEngines(tts?.engines?.map { it.name })
                    reason = "system TTS engine failed to initialise (code $code; engines=$visible)"
                    VoxLog.w("tts: system engine init failed code=$code engines=$visible -> text-only")
                    onReady(false)
                } else {
                    tts?.setPitch(pitch)
                    tts?.setSpeechRate(rate)
                    val lang = try { tts?.setLanguage(Locale.US) } catch (_: Throwable) { null }
                    if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
                        // A missing language pack looks exactly like a missing engine from the
                        // outside; keep them distinguishable in the log and the status line.
                        reason = "system TTS has no en-US voice (setLanguage=$lang)"
                        VoxLog.w("tts: system engine language unavailable code=$lang -> text-only")
                        onReady(false)
                    } else {
                        reason = ""
                        onReady(true)
                    }
                }
            }
        } catch (t: Throwable) {
            reason = "system TTS unavailable: ${t.message ?: t::class.java.simpleName}"
            VoxLog.w("tts: system engine threw ${t::class.java.simpleName} -> text-only")
            onReady(false)
        }
    }

    override fun speak(text: String, onDone: () -> Unit) = speakReporting(text) { onDone() }

    /**
     * #373: the system engine's completion is now an EVENT (UtteranceProgressListener),
     * not a single delayed `isSpeaking` poll.
     *
     * The poll was the silent-latch shape: it ran once, at a guessed instant, and if the
     * engine was still speaking then it never ran again — so the caller's completion
     * callback never arrived, `speaking`/`glueSpeaking` stayed true, and every later
     * utterance was refused by the controller's own gates. A watchdog still backs the
     * listener: an engine that goes away without a callback must end the utterance
     * (FAILED, code=timeout) instead of latching the call.
     */
    override fun speakReporting(text: String, onDone: (TtsOutcome) -> Unit) {
        // A new utterance flushes the old one (QUEUE_FLUSH below). End the old one here,
        // as INTERRUPTED, so its watchdog cannot later blame the engine for a silence the
        // new utterance caused.
        currentFinish?.invoke(TtsOutcome(TtsEnd.INTERRUPTED, "superseded"))
        val t = tts
        if (t == null) {
            lastError = "engine not initialised"
            main.post { onDone(TtsOutcome(TtsEnd.FAILED, "engine not initialised")) }
            return
        }
        val id = "hv" + (++seq)
        val done = AtomicBoolean(false)
        // ~90 ms/char is slower than speech (~60-70), +8 s: a watchdog, not a cue.
        val timeoutMs = text.length * 90L + 8000L
        var watchdog: Runnable? = null
        fun finish(out: TtsOutcome) {
            if (!done.compareAndSet(false, true)) return
            currentFinish = null
            watchdog?.let { main.removeCallbacks(it) }
            if (out.end == TtsEnd.FAILED) lastError = out.code
            // The callback lands on the main thread: the pre-#373 poll did, and the glue
            // path (startStreaming on completion) was written against that contract.
            main.post { onDone(out) }
        }
        val wd = Runnable {
            if (done.compareAndSet(false, true)) {
                currentFinish = null
                lastError = "no completion callback in ${timeoutMs}ms"
                VoxLog.w("event=tts-timeout engine=system chars=${text.length} afterMs=$timeoutMs")
                main.post { onDone(TtsOutcome(TtsEnd.FAILED, "timeout")) }
            }
        }
        watchdog = wd
        currentFinish = { out -> finish(out) }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == id) finish(TtsOutcome(TtsEnd.SPOKEN))
            }
            override fun onError(utteranceId: String?) {
                if (utteranceId == id) finish(TtsOutcome(TtsEnd.FAILED, "engine-error"))
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                if (utteranceId == id) finish(TtsOutcome(TtsEnd.FAILED, "error-$errorCode"))
            }
            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                if (utteranceId == id) finish(TtsOutcome(TtsEnd.INTERRUPTED, "stopped"))
            }
        })
        val rc = try {
            t.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        } catch (e: Throwable) {
            lastError = "speak threw ${e::class.java.simpleName}"
            VoxLog.w("tts: system speak threw ${e::class.java.simpleName}")
            TextToSpeech.ERROR
        }
        if (rc != TextToSpeech.SUCCESS) {
            // Previously ignored: a refused utterance was silent AND invisible.
            finish(TtsOutcome(TtsEnd.FAILED, "speak=$rc"))
            return
        }
        main.postDelayed(wd, timeoutMs)
    }

    override fun stop() {
        try { tts?.stop() } catch (_: Throwable) {}
        // A deliberate cut is not an engine failure: report it as INTERRUPTED at once, so
        // the watchdog cannot later blame the engine for a silence the caller asked for.
        currentFinish?.invoke(TtsOutcome(TtsEnd.INTERRUPTED, "stopped"))
    }
    override fun shutdown() {
        currentFinish?.invoke(TtsOutcome(TtsEnd.INTERRUPTED, "shutdown"))
        try { tts?.shutdown() } catch (_: Throwable) {}
    }
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

/**
 * #373: build EXACTLY the engine [token] names, or null when this build cannot run it.
 * No substitution happens here — [TtsPolicy] decides what a missing engine means, and
 * the caller must be able to tell "asked for Piper, got Piper" from "asked for Piper,
 * got the system voice".
 */
fun ttsEngineFor(context: Context, token: String): VoxTts? = try {
    when (TtsPolicy.localEngineOf(token)) {
        "piper" -> SherpaTts(context, SherpaVoice.PIPER)
        "supertonic" -> SherpaTts(context, SherpaVoice.SUPERTONIC)
        // Kokoro's runtime is a stub (isWarm is always false); it can never speak.
        "kokoro" -> WarmTts(context).takeIf { it.isWarm }
        else -> SystemTts(context)
    }
} catch (_: Throwable) { null }

/** Builds the configured VoxTts, HONORING the `tts` pref (#31). `system` yields
 *  SystemTts, `piper` yields SherpaTts (when the Piper model is installed, else an
 *  honest fallback to System), and `kokoro` yields the Kokoro backend (WarmTts)
 *  when it can honestly produce audio, else an honest fallback to System. An
 *  unknown/blank token resolves to SystemTts — never a silent or ignore-the-pref
 *  engine.
 *
 *  This is the PREVIEW builder (Settings): the call path goes through
 *  [TtsEngineHost], which keeps the substitution's reason instead of discarding it. */
fun buildTts(context: Context, prefer: String): VoxTts {
    return try {
        val local = TtsPolicy.localEngineOf(prefer)
        val modelId = local?.let { TtsPolicy.modelIdOf(it) }
        // A local voice whose model is absent is not loaded at all (the load would only
        // fail slowly); the honest answer is the system engine.
        if (local != null && modelId != null && !ModelCatalog.isInstalled(context, modelId)) {
            VoxLog.d("tts: $local requested but model not installed -> system fallback")
            SystemTts(context)
        } else {
            ttsEngineFor(context, prefer) ?: SystemTts(context)
        }
    } catch (_: Throwable) { SystemTts(context) }
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

/**
 * A short, pure description of the TTS engines this app can actually see — used in the
 * failure log and the status line.
 *
 * `none-visible` on Android 11+ is the signature of a missing package-visibility query:
 * the engine is installed and other apps can speak, but this app cannot bind to it
 * (#346). `unavailable` means the engine list itself could not be read. Keeping this a
 * pure function is what makes the distinction testable without a device.
 */
internal fun describeEngines(names: List<String>?): String = when {
    names == null -> "unavailable"
    names.isEmpty() -> "none-visible"
    else -> names.joinToString(",")
}
