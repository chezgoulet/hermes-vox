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
 * Sovereign/local-first: model FILES come from an open-source store (the upstream
 * k2-fsa release by default, adjustable in Settings); inference runs fully offline.
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
    val purpose: String = "",  // one-line plain-language "what this model is for" (model card, #16)
    val licence: ModelLicence? = null,  // non-null = the download requires accepting this licence first
    /** The upstream model card this row's licence claim comes from ("" = none published). */
    val cardUrl: String = ""
)

/**
 * A model licence that is not a permissive open-source one, so the user has to accept it
 * before the weights are fetched (ModelLicences holds the acceptance, ModelDownloader
 * refuses without it). The weights are only ever downloaded at runtime, never bundled.
 */
data class ModelLicence(
    val name: String,         // the licence's own title, shown on the download screen
    val attribution: String,  // the notice the licensor asks for, shown verbatim
    val modelCardUrl: String  // upstream model card (it links the licence text)
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
            "Piper en-US canonical LibriTTS-R medium · the recommended voice: most consistent in the field", true, 11,
            "10dc268f3e371696d721486123e2705a9fc1faa113491979fde4d88dba1f1b1c", true,
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-en_US-libritts_r-medium.tar.bz2",
            "Speaks Hermes' replies aloud in a natural voice, fully offline — the recommended voice"),
        // French Piper voices (#477, PierreCsn, re-landed against this tree). The licences are
        // the ones their MODEL_CARDs state, fetched from the rhasspy/piper-voices repository —
        // Tom is AGPLv3, not CC0. Each desc carries the licence and the card URL so the claim
        // can be checked without leaving the catalogue; NOTICE carries the attribution.
        ModelSpec("piper-siwis", "Piper · fr-FR (Siwis, medium)", "tts", "vits-piper-fr_FR-siwis-medium.tar.bz2", 67.2,
            "Piper fr-FR Siwis medium · French voice; model licence CC BY 4.0 · model card: https://huggingface.co/rhasspy/piper-voices/blob/main/fr/fr_FR/siwis/medium/MODEL_CARD",
            true, 12,
            "375909aa30842b3a4efa10b1beb1d761af792960ae6873b4d53889f96c66195b", false,
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-fr_FR-siwis-medium.tar.bz2",
            "Speaks French in the Siwis voice — natural, fully offline",
            cardUrl = "https://huggingface.co/rhasspy/piper-voices/blob/main/fr/fr_FR/siwis/medium/MODEL_CARD"),
        ModelSpec("piper-tom", "Piper · fr-FR (Tom, medium)", "tts", "vits-piper-fr_FR-tom-medium.tar.bz2", 67.2,
            "Piper fr-FR Tom medium · French voice; model licence AGPLv3 · model card: https://huggingface.co/rhasspy/piper-voices/blob/main/fr/fr_FR/tom/medium/MODEL_CARD",
            true, 13,
            "3d8258ef8466b5d5f2cc46c06e1415d3d01c9cbaeed8b6d5b3d152073672472c", false,
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-fr_FR-tom-medium.tar.bz2",
            "Speaks French in the Tom voice — an alternative, fully offline",
            cardUrl = "https://huggingface.co/rhasspy/piper-voices/blob/main/fr/fr_FR/tom/medium/MODEL_CARD"),
        ModelSpec("piper-upmc", "Piper · fr-FR (UPMC, medium)", "tts", "vits-piper-fr_FR-upmc-medium.tar.bz2", 80.4,
            "Piper fr-FR UPMC medium · two speakers (Jessica, Pierre); model licence CC BY-SA 4.0 · model card: https://huggingface.co/rhasspy/piper-voices/blob/main/fr/fr_FR/upmc/medium/MODEL_CARD",
            true, 14,
            "e9830a331a16f6cc5ef3116a287065e015d3495c3f56b974889a266da7f89a7f", false,
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-fr_FR-upmc-medium.tar.bz2",
            "Speaks French in two voices (Jessica and Pierre) — fully offline",
            cardUrl = "https://huggingface.co/rhasspy/piper-voices/blob/main/fr/fr_FR/upmc/medium/MODEL_CARD"),
        ModelSpec("supertonic", "Supertonic · 10 voices (44.1 kHz)", "tts", "sherpa-onnx-supertonic-tts-int8-2026-03-06.tar.bz2", 84.7,
            "On-device neural voice · experimental: may drop words; under investigation (model: OpenRAIL-M)", true, 2,
            "8c74359f63edd5045d47747f65331f0f6dbcbc91d7e898dd756d631295fe3259", false,
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-supertonic-tts-int8-2026-03-06.tar.bz2",
            "Speaks Hermes' replies in ten voices, fully offline — experimental: may drop words"),
        ModelSpec("whisper-tiny", "Whisper tiny.en", "stt", "sherpa-onnx-whisper-tiny.en.tar.bz2", 118.1,
            "Offline STT · English only, fastest, lightest", true, 3,
            "2bd6cf965c8bb3e068ef9fa2191387ee63a9dfa2a4e37582a8109641c20005dd", false, "",
            "Turns your speech into text on-device — the fast, light option"),
        // The multilingual Whisper exports (#477): the same sherpa-onnx packaging without the
        // `.en` suffix. They understand French (and ~90 other languages) — the `.en` models
        // above do not, which is why the spoken-language row only appears for these.
        ModelSpec("whisper-tiny-multi", "Whisper tiny (multilingual)", "stt", "sherpa-onnx-whisper-tiny.tar.bz2", 116.2,
            "Offline STT · multilingual, fastest and lightest", true, 4,
            "c46116994e539aa165266d96b325252728429c12535eb9d8b6a2b10f129e66b1", false, "",
            "Turns multilingual speech into text on-device — fast, understands French"),
        ModelSpec("whisper-base", "Whisper base.en", "stt", "sherpa-onnx-whisper-base.en.tar.bz2", 208.6,
            "Offline STT · English only, balanced", true, 5,
            "475bc7052ce299c007f6d5d5407ba8601f819a2867f6eecee510ed17df581542", true, "",
            "Turns your speech into text on-device — the balanced default"),
        ModelSpec("whisper-base-multi", "Whisper base (multilingual)", "stt", "sherpa-onnx-whisper-base.tar.bz2", 207.6,
            "Offline STT · multilingual, balanced", true, 6,
            "911b2083efd7c0dca2ac3b358b75222660dc09fb716d64fbfc417ba6c99ff3de", false, "",
            "Turns multilingual speech into text on-device — balanced accuracy and speed"),
        ModelSpec("whisper-small", "Whisper small.en", "stt", "sherpa-onnx-whisper-small.en.tar.bz2", 635.7,
            "Offline STT · English only, best accuracy, heaviest", true, 7,
            "0cdba2b8aaab69e04847f3427cc9709574112e67913a1a84b7fec3a8729faa9a", false, "",
            "Turns your speech into text on-device — most accurate, heavier"),
        ModelSpec("whisper-small-multi", "Whisper small (multilingual)", "stt", "sherpa-onnx-whisper-small.tar.bz2", 639.4,
            "Offline STT · multilingual, best accuracy, heaviest", true, 8,
            "486a46afbb7ba798507190ffe02fea2dd726049af212e774537efac6afb210a6", false, "",
            "Turns multilingual speech into text on-device — most accurate, heavier download"),
        // Parakeet-TDT 0.6B v2 (NVIDIA, CC-BY-4.0), the sherpa-onnx int8 packaging;
        // sha256 of the upstream asr-models release artifact. Opt-in: on the sttbench
        // corpus it beats whisper-base.en on accuracy AND speed (host CPU: WER 3.4% vs
        // 5.6%, RTF 0.08 vs 0.19, noisy clips 4% vs 32%, no hallucinated text on
        // non-speech) but it is a 482 MB download and a larger resident model, so the
        // default stays whisper-base until it is measured on a phone.
        ModelSpec("parakeet-v2", "Parakeet TDT 0.6B v2 (int8)", "stt", "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2", 482.5,
            "Offline STT · most accurate + fastest, largest download (English)", true, 9,
            "157c157bc51155e03e37d2466522a3a737dd9c72bb25f36eb18912964161e1ad", false, "",
            "Turns your speech into text on-device — most accurate, larger download"),
        // Nemotron Speech Streaming 0.6B (NVIDIA), the k2-fsa int8 ONNX export with a 160 ms
        // chunk baked into the encoder. The streaming A/B (docs/STT-AB.md) measured it at
        // 11.9% WER on accented speech against 38.8% for the streaming zipformer, at ~900 MB
        // peak RSS and RTF ~0.7 on the test phone. Opt-in, never the default. Size and sha256
        // are those of the release artifact as fetched by tools/sttbench-stream/run_ab.py
        // (463,945,198 bytes). The NVIDIA Open Model License is revocable and the tarball
        // ships no licence file, so the download is gated on accepting it.
        ModelSpec(NEMOTRON_STREAM_ID, "Nemotron Speech Streaming 0.6B (160 ms, int8)", "stt",
            "sherpa-onnx-nemotron-speech-streaming-en-0.6b-160ms-int8-2026-04-25.tar.bz2", 463.9,
            "Streaming STT · best on accented speech, heaviest (English, NVIDIA licence)", true, 10,
            "0ae73a41cd51599dc7cac9ac083d9d35de53d762ca45923505fde47a3751814b", false, "",
            "Turns your speech into text on-device — best with accents; needs a lot of memory",
            ModelLicence("NVIDIA Open Model License Agreement",
                "Licensed by NVIDIA Corporation under the NVIDIA Open Model License",
                "https://huggingface.co/nvidia/nemotron-speech-streaming-en-0.6b")),
        ModelSpec("gemma-e2b", "Gemma 4 E2B (presence)", "express", "gemma-4-E2B-it.litertlm", 2588.1,
            "On-device expression layer for Presence", true, 15,
            "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c", false,
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            "Optional on-device presence layer — the being, its asides and its voice; runs on the GPU")
    )

    // #114-denominator: REQUIRED and RECOMMENDED are THE SAME SET, everywhere.
    // The Settings home badge, the Models header, the "install all" button and
    // the Main-screen gate all count blessed.filter { it.recommended } so the
    // number a user sees in Settings is the number this screen shows.
    val required: List<ModelSpec> get() = blessed.filter { it.recommended }

    /** Required models that are not yet installed (app-private model dirs). */
    fun missingRequired(context: Context): List<ModelSpec> = required.filter { !isInstalled(context, it.id) }

    /**
     * #559: the order an unset `tts` pref resolves in. Piper first — it was the consistent
     * voice in the field (Pixel 9, one ordinary call) while Supertonic dropped words; the
     * cause (chunk boundaries or the model) is not yet separated, so Supertonic stays
     * selectable but is no longer what a fresh install speaks with. Each pair is
     * (catalogue model id, `tts` pref token).
     */
    val TTS_DEFAULT_ORDER: List<Pair<String, String>> = listOf(
        "piper-lessac" to "piper",
        "supertonic" to "supertonic",
        "piper-siwis" to "piper-siwis",
        "piper-tom" to "piper-tom",
        "piper-upmc" to "piper-upmc",
    )

    /** The TTS engine token used when the user has not chosen one: the best installed
     *  on-device voice in [TTS_DEFAULT_ORDER] (Piper, then Supertonic), else the system
     *  voice. A stored choice never reaches this — it is only the unset default. */
    fun defaultTts(context: Context): String = defaultTtsOf { isInstalled(context, it) }

    /** [defaultTts] without a Context, so the order is testable. */
    fun defaultTtsOf(installed: (String) -> Boolean): String =
        TTS_DEFAULT_ORDER.firstOrNull { installed(it.first) }?.second ?: "system"

    /** The on-device STT model -> model-id map (Settings STT model picker). */
    val sttModels = listOf(
        "whisper-tiny" to "Whisper tiny.en",
        "whisper-tiny-multi" to "Whisper tiny (multilingual)",
        "whisper-base" to "Whisper base.en",
        "whisper-base-multi" to "Whisper base (multilingual)",
        "whisper-small" to "Whisper small.en",
        "whisper-small-multi" to "Whisper small (multilingual)",
        "parakeet-v2" to "Parakeet TDT 0.6B v2"
    )
    /** Blessed default STT model id. */
    const val DEFAULT_STT_MODEL = "whisper-base"

    /** The streaming (OnlineRecognizer) STT arm. */
    const val NEMOTRON_STREAM_ID = "nemotron-stream-160ms"

    /** Streaming STT models -> label. Kept apart from [sttModels]: choosing one is stored
     *  under [KEY_STT_STREAM_MODEL] and leaves [KEY_STT_MODEL] alone, so the shipped model
     *  stays selected underneath as the fallback. */
    val streamingSttModels = listOf(NEMOTRON_STREAM_ID to "Nemotron streaming 0.6B")

    /** The selected streaming STT model id; "" (the default) = none, use [KEY_STT_MODEL]. */
    const val KEY_STT_STREAM_MODEL = "stt_stream_model"

    fun isStreamingStt(modelId: String?): Boolean = streamingSttModels.any { it.first == modelId }

    /** Below this ActivityManager.memoryClass (MB) the streaming arm's ~900 MB resident
     *  model is a real risk, so the chooser says so instead of hiding the option. */
    const val SMALL_MEMORY_CLASS_MB = 256

    /** The chooser label for a streaming model: plain, or with the memory trade-off spelled
     *  out on a device that reports a small heap class (or is a low-RAM device). */
    fun streamingSttLabel(label: String, memoryClassMb: Int, lowRam: Boolean): String =
        if (lowRam || memoryClassMb in 1 until SMALL_MEMORY_CLASS_MB)
            "$label — needs ~900 MB RAM; may be slow or close other apps on this phone"
        else label

    /** Decode language for a MULTILINGUAL Whisper model ("" = auto-detect). The
     *  shipped .en models ignore it (they only transcribe English). */
    const val KEY_STT_LANGUAGE = "stt_language"

    /** The STT models decoded by sherpa's NeMo transducer path (not Whisper). */
    fun isTransducerStt(modelId: String): Boolean = modelId.startsWith("parakeet-")

    /** English-only Whisper checkpoints (the upstream ".en" exports). */
    private val ENGLISH_ONLY_WHISPER = setOf("whisper-tiny", "whisper-base", "whisper-small")

    /** The Whisper checkpoints that decode more than English (the multilingual exports). */
    private val MULTILINGUAL_WHISPER = setOf(
        "whisper-tiny-multi", "whisper-base-multi", "whisper-small-multi")

    /**
     * Can this recogniser decode a chosen spoken language? Only the multilingual Whisper
     * exports can: the `.en` checkpoints, Parakeet and the streaming Nemotron hear English
     * regardless of the pref, so the Settings language row is hidden for them
     * ([SttLanguage.rowVisible]) rather than offering a control that does nothing.
     */
    fun isMultilingualStt(modelId: String?): Boolean = modelId?.trim() in MULTILINGUAL_WHISPER

    /** The language a Whisper model is decoded with: always "en" for the .en
     *  models (passing anything else is meaningless to them), else the user's
     *  `stt_language` pref, trimmed/lowercased ("" = let Whisper detect it). */
    fun whisperLanguage(modelId: String, pref: String?): String =
        if (modelId in ENGLISH_ONLY_WHISPER) "en" else pref.orEmpty().trim().lowercase()

    const val KEY_STT_BACKEND = "stt_backend"
    const val KEY_STT_MODEL = "stt_model"
    const val BACKEND_ONDEVICE = "on-device"
    const val BACKEND_PLATFORM = "platform"
    const val BACKEND_REMOTE = "remote"

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
        val markers = when {
            // int8 or fp32 weights (OfflineWhisperStt prefers int8 and prunes fp32 once int8 is in use).
            id.startsWith("whisper-") -> return File(d, "tokens.txt").exists() &&
                listOf("encoder", "decoder").all { p ->
                    File(d, "$p.onnx").exists() || File(d, "$p.int8.onnx").exists() ||
                        d.listFiles()?.any { it.name.endsWith("-$p.int8.onnx") } == true
                }
            id == "parakeet-v2" -> listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
            id == NEMOTRON_STREAM_ID -> STREAMING_TRANSDUCER_FILES
            id == "silero-vad" -> listOf("silero_vad.onnx")
            // Every Piper voice — en-US and the fr-FR set — unpacks one model.onnx + tokens.
            id.startsWith("piper-") -> listOf("model.onnx", "tokens.txt")
            id == "supertonic" -> listOf("duration_predictor.int8.onnx", "text_encoder.int8.onnx",
                "vector_estimator.int8.onnx", "vocoder.int8.onnx", "tts.json", "unicode_indexer.bin", "voice.bin")
            id == "gemma-e2b" -> listOf("gemma-4-E2B-it.litertlm")
            else -> return d.exists() && (d.listFiles()?.isNotEmpty() == true)
        }
        return markers.all { File(d, it).exists() }
    }

    /** The blessed default for a pipeline kind; null if not yet downloaded. */
    fun defaultModel(kind: String): ModelSpec? = blessed
        .firstOrNull { it.kind == kind && it.blessed }
        ?.takeIf { true }
}
