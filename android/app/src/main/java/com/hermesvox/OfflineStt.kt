package com.hermesvox

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.HomophoneReplacerConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Abstraction over the STT leg so the pipeline can pick on-device Whisper, the
 *  house GPU, or the platform recognizer. transcribe() is BLOCKING — call it on
 *  a worker thread. */
interface VoxStt {
    val name: String
    val isAvailable: Boolean
    fun init(onReady: (Boolean) -> Unit)
    fun transcribe(samples: FloatArray, sampleRate: Int): String?
    fun shutdown()
}

/**
 * OfflineWhisperStt — on-device Whisper via sherpa-onnx, MODEL-SELECTABLE
 * (tiny / base / small, chosen in Settings; "whisper-base" is the blessed
 * default). Loads filesDir/models/<modelId>/ (encoder.onnx, decoder.onnx,
 * tokens.txt). Fully offline; isAvailable=false when the model isn't installed.
 */
class OfflineWhisperStt(private val context: Context, private val modelId: String = "whisper-base") : VoxStt {
    private var rec: OfflineRecognizer? = null
    override val name get() = "Whisper-$modelId"
    override val isAvailable get() = rec != null

    private val dir get() = File(context.filesDir, "models/$modelId")

    override fun init(onReady: (Boolean) -> Unit) {
        thread {
            try {
                val e = File(dir, "encoder.onnx"); val d = File(dir, "decoder.onnx"); val t = File(dir, "tokens.txt")
                if (!e.exists() || !d.exists() || !t.exists()) { onReady(false); return@thread }
                // sherpa-off-community shape (official kotlin-api + issue #2071):
                // tokens path + modelType belong on OfflineModelConfig, built via
                // the NO-ARG ctor + setters (NOT the full-arg ctor).
                val whisper = OfflineWhisperModelConfig(e.absolutePath, d.absolutePath, "en", "transcribe", 0, false, false)
                val modelCfg = OfflineModelConfig().apply {
                    this.whisper = whisper
                    this.tokens = t.absolutePath
                    this.modelType = "whisper"
                    this.numThreads = 1
                    this.provider = "cpu"
                }
                val feat = FeatureConfig(16000, 80, 0f)
                val cfg = OfflineRecognizerConfig(feat, modelCfg, HomophoneReplacerConfig("", "", ""), "greedy_search", 4, "", 0f, "", "", 0f)
                rec = OfflineRecognizer(null, cfg)   // assetManager=null: absolute-path model
                VoxLog.d("OfflineWhisperStt loaded: $modelId")
                onReady(true)
            } catch (e: Throwable) {
                VoxLog.e("OfflineWhisperStt($modelId) init failed: ${e.message}")
                onReady(false)
            }
        }
    }

    override fun transcribe(samples: FloatArray, sampleRate: Int): String? {
        val r = rec ?: return null
        return try {
            val s = r.createStream()
            val fed = if (sampleRate == 16000) samples else resample(samples, sampleRate, 16000)
            s.acceptWaveform(fed, 16000)
            r.decode(s)
            r.getResult(s).text
        } catch (e: Throwable) { VoxLog.e("whisper decode: ${e.message}"); null }
    }

    override fun shutdown() { rec?.release(); rec = null }

    companion object {
        /** PROOF HOOK: build a fresh STT, transcribe a WAV file, return text. */
        fun transcribeWave(context: Context, modelId: String, wavePath: String): String? {
            val stt = OfflineWhisperStt(context, modelId)
            var ready = false
            stt.init { ready = it }
            for (i in 0 until 60) { if (ready) break; Thread.sleep(100) }
            if (!ready) return null
            val samples = readWav16kMono(File(wavePath)) ?: return null
            return stt.transcribe(samples, 16000)
        }

        /** Read a 16-bit PCM mono 16kHz WAV into a FloatArray (-1..1). */
        fun readWav16kMono(f: File): FloatArray? {
            return try {
                val bytes = f.readBytes()
                var i = 12; var dataOff = -1; var dataLen = 0
                while (i + 8 <= bytes.size) {
                    val id = String(bytes, i, 4); val len = leInt(bytes, i + 4)
                    if (id == "data") { dataOff = i + 8; dataLen = len; break }
                    i += 8 + len + (len and 1)
                }
                if (dataOff < 0) return null
                val n = dataLen / 2; val out = FloatArray(n)
                for (j in 0 until n) {
                    val lo = bytes[dataOff + j * 2].toInt() and 0xFF
                    val hi = bytes[dataOff + j * 2 + 1].toInt()
                    out[j] = ((hi shl 8) or lo).toShort() / 32768f
                }
                out
            } catch (e: Throwable) { VoxLog.e("readWav: ${e.message}"); null }
        }
        private fun leInt(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o+1].toInt() and 0xFF) shl 8) or ((b[o+2].toInt() and 0xFF) shl 16) or ((b[o+3].toInt() and 0xFF) shl 24)
    }

    private fun resample(inp: FloatArray, fromHz: Int, toHz: Int): FloatArray {
        if (fromHz == toHz) return inp
        val ratio = toHz.toFloat() / fromHz; val nOut = (inp.size * ratio).toInt(); val out = FloatArray(nOut)
        for (i in 0 until nOut) { val idx = i / ratio; val i0 = idx.toInt(); val i1 = (i0 + 1).coerceAtMost(inp.size - 1); val fr = idx - i0; out[i] = inp[i0] * (1 - fr) + inp[i1] * fr }
        return out
    }
}

/**
 * HouseStt — STT on the house box (Thelio lemonade, Whisper-Large-v3-Turbo on
 * GPU): max accuracy + speed when the tailnet is up. The app records mic ->
 * serializes a 16k mono WAV -> POSTs multipart to /v1/audio/transcriptions.
 * Falls back gracefully when the house isn't reachable (returns null).
 */
class HouseStt(private val context: Context) : VoxStt {
    private val prefs get() = context.getSharedPreferences("hv", Context.MODE_PRIVATE)
    override val name get() = "House (GPU)"
    // placeholder; real availability checked per-call (needs the tailnet).
    private var _ready = false
    override val isAvailable get() = _ready

    private fun houseUrl(): String =
        prefs.getString("stt_house_url", "http://100.68.43.34:13305") ?: "http://100.68.43.34:13305"

    override fun init(onReady: (Boolean) -> Unit) {
        // Lazy ping the house endpoint; if reachable mark ready.
        thread {
            try {
                val c = URL(houseUrl() + "/v1/models").openConnection() as HttpURLConnection
                c.connectTimeout = 8000; c.readTimeout = 8000
                c.connect()
                _ready = c.responseCode == 200
                c.disconnect()
            } catch (_: Throwable) { _ready = false }
            onReady(_ready)
        }
    }

    override fun transcribe(samples: FloatArray, sampleRate: Int): String? {
        return try {
            val wav = wav16k(samples, sampleRate)
            val boundary = "----HVBoundary${System.currentTimeMillis()}"
            val conn = URL(houseUrl() + "/v1/audio/transcriptions").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"; conn.connectTimeout = 20000; conn.readTimeout = 60000
            conn.doOutput = true; conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            val body = buildMultipart(boundary, wav)
            conn.outputStream.use { it.write(body) }
            if (conn.responseCode != 200) return null
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            JSONObject(resp).optString("text").ifBlank { null }
        } catch (e: Throwable) { VoxLog.e("house stt: ${e.message}"); null }
    }

    override fun shutdown() {}

    private fun wav16k(samples: FloatArray, sr: Int): ByteArray {
        val rate = 16000
        val data = if (sr == rate) samples else resampleLin(samples, sr, rate)
        val pcm = ByteArray(data.size * 2)
        for (i in data.indices) { val s = (data[i].coerceIn(-1f, 1f) * 32767).toInt(); pcm[i*2] = (s and 0xFF).toByte(); pcm[i*2+1] = ((s shr 8) and 0xFF).toByte() }
        val baos = ByteArrayOutputStream(); val ds = DataOutputStream(baos)
        ds.writeBytes("RIFF"); ds.writeInt(36 + pcm.size); ds.writeBytes("WAVE")
        ds.writeBytes("fmt "); ds.writeInt(16); ds.writeShort(1); ds.writeShort(1)
        ds.writeInt(rate); ds.writeInt(rate * 2); ds.writeShort(2); ds.writeShort(16)
        ds.writeBytes("data"); ds.writeInt(pcm.size)
        ds.flush()
        return baos.toByteArray() + pcm
    }

    private fun buildMultipart(boundary: String, wav: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream(); val ds = DataOutputStream(baos)
        ds.writeBytes("--$boundary\r\n")
        ds.writeBytes("Content-Disposition: form-data; name=\"model\"\r\n\r\nwhisper-large-v3-turbo\r\n")
        ds.writeBytes("--$boundary\r\n")
        ds.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"clip.wav\"\r\n")
        ds.writeBytes("Content-Type: audio/wav\r\n\r\n")
        ds.write(wav)
        ds.writeBytes("\r\n--$boundary--\r\n")
        ds.flush()
        return baos.toByteArray()
    }

    private fun resampleLin(inp: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to) return inp
        val ratio = to.toFloat() / from; val n = (inp.size * ratio).toInt(); val o = FloatArray(n)
        for (i in 0 until n) { val idx = i / ratio; val i0 = idx.toInt(); val i1 = (i0+1).coerceAtMost(inp.size-1); val fr = idx-i0; o[i] = inp[i0]*(1-fr)+inp[i1]*fr }
        return o
    }
}

/**
 * SileroVadGate — on-device voice-activity detection for the barge-in / wake
 * trigger. Replaces the RMS threshold when the model is installed.
 */
class SileroVadGate(private val context: Context) {
    private var vad: Vad? = null
    val isAvailable get() = vad != null
    val dir get() = File(context.filesDir, "models/silero-vad")

    fun init(onReady: (Boolean) -> Unit) {
        thread {
            try {
                val m = File(dir, "silero_vad.onnx")
                if (!m.exists()) { onReady(false); return@thread }
                val sil = SileroVadModelConfig(m.absolutePath, 0.5f, 0.5f, 0.25f, 512, 20f)
                val cfg = VadModelConfig(sileroVadModelConfig = sil, sampleRate = 16000, numThreads = 1, provider = "cpu")
                vad = Vad(null, cfg)
                VoxLog.d("SileroVadGate loaded: silero-vad")
                onReady(true)
            } catch (e: Throwable) { VoxLog.e("SileroVadGate init failed: ${e.message}"); onReady(false) }
        }
    }

    fun feed(samples: FloatArray): Boolean {
        val v = vad ?: return false
        return try { v.acceptWaveform(samples); v.isSpeechDetected() } catch (_: Throwable) { false }
    }

    fun shutdown() { vad?.release(); vad = null }
}
