package com.hermesvox

import android.content.Context
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/** Remote-STT prefs (user-supplied, blank by default — audio never leaves the
 *  device until the user picks the remote backend AND saves a URL). */
internal const val KEY_STT_REMOTE_URL = "stt_remote_url"
internal const val KEY_STT_REMOTE_MODEL = "stt_remote_model"
internal const val KEY_STT_REMOTE_KEY = "stt_remote_key"
internal const val DEFAULT_REMOTE_MODEL = "whisper"

/** Pure URL guard shared by availability + request-building. Only a genuine
 *  `http(s)://` base is usable (mirrors ModelCatalog.resolveSource's scheme
 *  allowlist) — blank / scheme-less means "not configured", never an exception
 *  (the old "no protocol" crash). Trailing '/' is trimmed so path joins are clean. */
internal fun sttBaseUrl(input: String?): String {
    val v = input?.trim().orEmpty()
    return if (v.matches(Regex("^https?://.*", RegexOption.IGNORE_CASE))) v.trimEnd('/') else ""
}

/** Pure: the model-list probe targets, in try order. A base the user already
 *  typed WITH its api prefix (`.../v1` or `.../api/v1`) probes only `/models`
 *  (no double `/v1/v1`); otherwise try both conventional prefixes. */
internal fun sttProbeUrls(base: String): List<String> {
    if (base.isBlank()) return emptyList()
    return if (base.endsWith("/v1") || base.endsWith("/api/v1")) {
        listOf("$base/models")
    } else {
        listOf("$base/v1/models", "$base/api/v1/models")
    }
}

/** Pure: reduce a working probe URL back to its api prefix ("/v1", "/api/v1",
 *  or "" when the base already carried it) so transcription hits the SAME prefix
 *  the probe validated against. */
internal fun sttApiPrefix(base: String, okProbeUrl: String): String {
    if (!okProbeUrl.startsWith(base)) return "/v1"
    val suffix = okProbeUrl.substring(base.length)
    return if (suffix.endsWith("/models")) suffix.removeSuffix("/models") else "/v1"
}

/** Pure: the transcription POST target for a base + validated api prefix. Null
 *  when unconfigured — the blank-URL guard, so transcribe never builds a URL. */
internal fun sttTranscribeUrl(base: String, apiPrefix: String): String? {
    if (base.isBlank()) return null
    return if (apiPrefix.isEmpty()) "$base/audio/transcriptions" else "$base$apiPrefix/audio/transcriptions"
}

/** Pure (unit-testable): mono FloatArray (-1..1) + sample rate -> in-memory
 *  PCM16 little-endian WAV bytes. Clips to ±1.0 so a loud frame maps into the
 *  Int16 range instead of wrapping. */
internal fun wavPcm16(samples: FloatArray, sampleRate: Int): ByteArray {
    val dataSize = samples.size * 2
    val out = ByteArrayOutputStream(44 + dataSize)
    fun ascii(s: String) { out.write(s.toByteArray(Charsets.US_ASCII)) }
    fun i16(v: Int) { out.write(v and 0xFF); out.write((v ushr 8) and 0xFF) }
    fun i32(v: Int) { i16(v); i16(v ushr 16) }
    ascii("RIFF"); i32(36 + dataSize); ascii("WAVE")
    ascii("fmt "); i32(16); i16(1); i16(1); i32(sampleRate); i32(sampleRate * 2); i16(2); i16(16)
    ascii("data"); i32(dataSize)
    for (s in samples) {
        val v = s.coerceIn(-1f, 1f)
        val q = if (v >= 0f) (v * 32767f).toInt() else (v * 32768f).toInt()
        i16(q)
    }
    return out.toByteArray()
}

/** Pure (unit-testable): extract the trimmed `{"text"}` value from an
 *  OpenAI-compatible transcription response. Missing key, blank text, or
 *  unparseable input -> null (caller treats null as a failed call). */
internal fun parseSttText(body: String?): String? {
    if (body == null) return null
    val s = body.trim()
    if (s.isEmpty() || s[0] != '{') return null
    val key = "\"text\""
    var idx = s.indexOf(key, 1)
    while (idx >= 0) {
        var j = idx + key.length
        while (j < s.length && s[j].isWhitespace()) j++
        if (j < s.length && s[j] == ':') break
        idx = s.indexOf(key, idx + 1)
    }
    if (idx < 0) return null
    var j = s.indexOf(':', idx) + 1
    while (j < s.length && s[j].isWhitespace()) j++
    if (j >= s.length || s[j] != '"') return null
    val value = readJsonString(s, j + 1) ?: return null
    val text = value.trim()
    return if (text.isEmpty()) null else text
}

/** Decode a JSON string that starts at s[start] (first char after the opening
 *  quote). Returns null when unterminated or on a bad escape. */
private fun readJsonString(s: String, start: Int): String? {
    val sb = StringBuilder()
    var i = start
    while (i < s.length) {
        when (val c = s[i]) {
            '"' -> return sb.toString()
            '\\' -> {
                i++
                if (i >= s.length) return null
                when (val e = s[i]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (i + 4 >= s.length) return null
                        val code = s.substring(i + 1, i + 5).toIntOrNull(16) ?: return null
                        sb.append(code.toChar())
                        i += 4
                    }
                    else -> return null
                }
            }
            else -> sb.append(c)
        }
        i++
    }
    return null
}

/**
 * RemoteStt — an OpenAI-compatible `POST {base}/.../audio/transcriptions` leg
 * (lemonade, whisper.cpp server, faster-whisper server, ...). Same VoxStt seam
 * as the on-device Whisper: BLOCKING transcribe on a worker thread, stateless
 * HTTP, zero new native deps. Audio bytes leave the device ONLY when the user
 * supplied a URL in Settings; if it's blank this is never available.
 *
 * PRIVACY: no transcript text is ever logged — success logs `textLen` only, and
 * failures log the error class/reason (never an echoed response body, which a
 * server could fill with the very words we just transcribed).
 */
class RemoteStt(private val context: Context) : VoxStt {
    private val prefs get() = context.getSharedPreferences("hv", Context.MODE_PRIVATE)
    private fun prefString(k: String, d: String) = prefs.getString(k, d) ?: d

    @Volatile private var probeOk = false
    @Volatile private var apiPrefix = "/v1"

    override val name get() = "remote"
    override val isAvailable get() = sttBaseUrl(prefString(KEY_STT_REMOTE_URL, "")).isNotBlank() && probeOk

    /** Optional Bearer key, decrypted on demand from SecureStore (never prefs-in-clear). */
    private fun apiKey(): String {
        val raw = (prefs.getString(KEY_STT_REMOTE_KEY, null) ?: "").trim()
        if (raw.isEmpty()) return ""
        return SecureStore.decrypt(raw) ?: ""
    }

    /** Async availability probe: GET {base}/v1/models then /api/v1/models, 3s
     *  timeouts; available = one returns HTTP 200. Never throws. */
    override fun init(onReady: (Boolean) -> Unit) {
        thread {
            var ok = false
            try {
                val base = sttBaseUrl(prefString(KEY_STT_REMOTE_URL, ""))
                val key = apiKey()
                for (p in sttProbeUrls(base)) {
                    var code = -1
                    try {
                        val c = URL(p).openConnection() as HttpURLConnection
                        try {
                            c.requestMethod = "GET"
                            c.connectTimeout = 3000
                            c.readTimeout = 3000
                            c.setRequestProperty("Accept", "application/json")
                            if (key.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $key")
                            code = c.responseCode
                            if (code == 200) { try { c.inputStream?.close() } catch (_: Throwable) {} }
                        } finally { c.disconnect() }
                    } catch (_: Throwable) { code = -1 }
                    if (code == 200) { apiPrefix = sttApiPrefix(base, p); ok = true; break }
                }
            } catch (_: Throwable) { ok = false }
            probeOk = ok
            VoxLog.d("event=stt-remote-init ok=$ok api=$apiPrefix")
            onReady(ok)
        }
    }

    /** Encode the utterance to in-memory mono PCM16 WAV, multipart-POST it, and
     *  parse `{"text"}`. Any failure -> null + a length-only/error log. */
    override fun transcribe(samples: FloatArray, sampleRate: Int): String? {
        val base = sttBaseUrl(prefString(KEY_STT_REMOTE_URL, ""))
        if (!probeOk || base.isBlank()) return null
        val endpoint = sttTranscribeUrl(base, apiPrefix) ?: return null
        val model = prefString(KEY_STT_REMOTE_MODEL, DEFAULT_REMOTE_MODEL).ifBlank { DEFAULT_REMOTE_MODEL }
        val key = apiKey()
        val boundary = "----hermesvox" + java.lang.Long.toHexString(System.nanoTime())
        val wav = try { wavPcm16(samples, sampleRate) } catch (e: Throwable) {
            VoxLog.w("event=stt-remote err=${e.message?.take(120)}"); return null
        }
        val body = multipartBody(model, wav, boundary)
        val t0 = android.os.SystemClock.uptimeMillis()
        return try {
            val c = URL(endpoint).openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"
                c.connectTimeout = 3000
                c.readTimeout = 3000
                c.doOutput = true
                c.setRequestProperty("Accept", "application/json")
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                if (key.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $key")
                c.outputStream.use { it.write(body) }
                val code = c.responseCode
                if (code !in 200..299) {
                    try { c.errorStream?.close() } catch (_: Throwable) {}   // drain + discard; NEVER log the error body (it can echo the transcript)
                    VoxLog.w("event=stt-remote err=http-$code")
                    return null
                }
                val resp = c.inputStream?.bufferedReader()?.use { it.readText() } ?: ""
                val t = parseSttText(resp) ?: run {
                    VoxLog.w("event=stt-remote err=empty-or-unparseable")
                    return null
                }
                val ms = android.os.SystemClock.uptimeMillis() - t0
                VoxLog.dd("event=stt-remote ms=$ms textLen=${t.length}")
                t
            } finally { c.disconnect() }
        } catch (e: Throwable) {
            VoxLog.w("event=stt-remote err=${e.message?.take(120)}")
            null
        }
    }

    private fun multipartBody(model: String, wav: ByteArray, boundary: String): ByteArray {
        val bos = ByteArrayOutputStream()
        fun utf8(s: String) { bos.write(s.toByteArray(Charsets.UTF_8)) }
        utf8("--$boundary\r\n")
        utf8("Content-Disposition: form-data; name=\"model\"\r\n\r\n")
        utf8("$model\r\n")
        utf8("--$boundary\r\n")
        utf8("Content-Disposition: form-data; name=\"file\"; filename=\"utterance.wav\"\r\n")
        utf8("Content-Type: audio/wav\r\n\r\n")
        bos.write(wav)
        utf8("\r\n--$boundary--\r\n")
        return bos.toByteArray()
    }

    /** Stateless HTTP — nothing to release. */
    override fun shutdown() {}
}
