package com.hermesvox.bench

import android.os.SystemClock
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.HomophoneReplacerConfig
import com.k2fsa.sherpa.onnx.OnlineCtcFstDecoderConfig
import com.k2fsa.sherpa.onnx.OnlineLMConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.locks.LockSupport

/**
 * StreamingSttBench — feeds corpus audio into a sherpa-onnx ONLINE (streaming)
 * recognizer in wall-clock pace and records everything the A/B needs:
 *
 *  - every partial snapshot with the audio time and wall time it appeared at, so
 *    partial latency vs ground-truth word onsets is computable off-device;
 *  - finalisation (endpoint) time and the final text;
 *  - decode CPU time, RTF, feed lag (proof the real-time feed kept up);
 *  - RSS and process CPU ticks, battery energy counter deltas.
 *
 * Feeding in real time is deliberate: a partial's latency is only meaningful if
 * the audio arrives at the rate a microphone would deliver it. When the device
 * cannot keep up the recorded feed lag says so, and the numbers are reported as
 * invalid rather than quietly optimistic.
 */
class StreamingSttBench(private val cfg: JSONObject) {

    companion object {
        const val SR = 16000
    }

    private val threads = cfg.optInt("threads", 4)
    private val chunkMs = cfg.optInt("chunk_ms", 20)
    private val realtime = cfg.optBoolean("realtime", true)
    private val tailSilenceMs = cfg.optInt("trailing_silence_ms", 500)
    private val maxTailMs = cfg.optInt("max_tail_ms", 3000)
    private val reps = cfg.optInt("reps", 1)

    private fun nowMs() = SystemClock.elapsedRealtimeNanos() / 1_000_000.0
    private fun nowNs() = SystemClock.elapsedRealtimeNanos()

    private fun recognizerConfig(model: JSONObject): OnlineRecognizerConfig =
        OnlineRecognizerConfig(
            FeatureConfig(SR, 80, 0f),
            OnlineModelConfig(
                OnlineTransducerModelConfig(
                    model.getString("encoder"),
                    model.getString("decoder"),
                    model.getString("joiner"),
                ),
                com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig(),
                com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig(),
                com.k2fsa.sherpa.onnx.OnlineNeMoCtcModelConfig(),
                com.k2fsa.sherpa.onnx.OnlineToneCtcModelConfig(),
                model.getString("tokens"),
                threads,
                false,                       // debug
                "cpu",                       // provider
                "",                          // modelType (zipformer transducer)
                "",                          // modelingUnit
                "",                          // bpeVocab
            ),
            OnlineLMConfig("", 0.0f),
            OnlineCtcFstDecoderConfig("", 0),
            HomophoneReplacerConfig("", "", ""),
            EndpointConfig(
                // sherpa's own defaults, with the trailing-silence threshold made a
                // parameter so both arms share one endpointing rule:
                //   rule1 after speech + tail, rule2 any silence + tail,
                //   rule3 a pause after 20 s of utterance (sherpa's default).
                EndpointRule(true, tailSilenceMs / 1000f, 0f),
                EndpointRule(false, tailSilenceMs / 1000f, 0f),
                EndpointRule(false, 0f, 20f),
            ),
            true,                            // enableEndpoint
            "greedy_search",
            4,
            "",
            0.0f,
            "",
            "",
            0.0f,
        )

    /** Run every clip in the config, returning the result JSON. */
    fun run(energy: EnergyProbe, onProgress: (String) -> Unit): JSONObject {
        val clips = cfg.getJSONArray("clips")
        val result = JSONObject()
        result.put("run_id", cfg.getString("run_id"))
        result.put("model_id", cfg.getJSONObject("model").getString("id"))
        result.put("threads", threads)
        result.put("chunk_ms", chunkMs)
        result.put("realtime", realtime)
        result.put("trailing_silence_ms", tailSilenceMs)
        result.put("reps", reps)

        val rssBase = rssKb()
        val cpuBase = cpuTicks()
        energy.snapshot("before")

        val tLoad = nowNs()
        val rec = OnlineRecognizer(null, recognizerConfig(cfg.getJSONObject("model")))
        val loadMs = (nowNs() - tLoad) / 1e6
        result.put("load_ms", loadMs)
        result.put("rss_base_kb", rssBase)
        result.put("rss_after_load_kb", rssKb())
        onProgress("model loaded in ${"%.0f".format(loadMs)} ms")

        // Warm-up: one discarded pass so the measured clips do not carry first-call
        // graph/JIT cost. Cold start is already captured above in load_ms.
        if (cfg.optBoolean("warmup", true) && clips.length() > 0) {
            runClip(rec, clips.getJSONObject(0), record = false)
        }

        val arr = JSONArray()
        for (i in 0 until clips.length()) {
            val c = clips.getJSONObject(i)
            onProgress("clip ${i + 1}/${clips.length()}: ${c.getString("id")} ${c.getString("condition")}")
            for (rep in 0 until reps) {
                arr.put(runClip(rec, c, record = true).put("rep", rep))
            }
        }
        result.put("clips", arr)
        result.put("cpu_ticks_total", cpuTicks() - cpuBase)
        result.put("rss_peak_kb", peakRssKb())
        result.put("rss_end_kb", rssKb())
        energy.snapshot("after")
        result.put("energy", energy.toJson())
        rec.release()
        return result
    }

    private fun runClip(rec: OnlineRecognizer, clip: JSONObject, record: Boolean): JSONObject {
        val out = JSONObject()
        out.put("id", clip.getString("id"))
        out.put("condition", clip.getString("condition"))
        out.put("category", clip.optString("category"))
        out.put("reference", clip.optString("reference"))

        val wav = Wav.read(File(clip.getString("wav")))
        require(wav.sampleRate == SR) { "${clip.getString("id")}: ${wav.sampleRate} Hz, expected $SR" }
        val x = wav.samples
        val chunk = (SR * chunkMs / 1000).coerceAtLeast(160)
        val stream = rec.createStream()

        val partials = JSONArray()
        var lastText = ""
        var decodeMs = 0.0
        var lagSum = 0.0
        var lagMax = 0.0
        var lagN = 0
        val cpuStart = cpuTicks()
        val rssBefore = rssKb()
        val t0 = nowNs()
        var firstPartialWall = -1.0

        fun drainAndSnapshot(audioMs: Double) {
            var guard = 0
            while (rec.isReady(stream) && guard < 1000) {
                val d0 = nowNs()
                rec.decode(stream)
                decodeMs += (nowNs() - d0) / 1e6
                guard++
            }
            val text = rec.getResult(stream).text
            if (text != lastText) {
                lastText = text
                val w = (nowNs() - t0) / 1e6
                if (firstPartialWall < 0) firstPartialWall = w
                partials.put(JSONObject()
                    .put("a", audioMs)
                    .put("w", w)
                    .put("t", text)
                    .put("d", decodeMs))
            }
        }

        var fed = 0
        while (fed < x.size) {
            val n = minOf(chunk, x.size - fed)
            if (realtime) {
                val due = t0 + (fed.toLong() * 1_000_000_000L) / SR
                val lag = waitUntil(due)
                lagSum += lag; lagMax = maxOf(lagMax, lag); lagN++
            }
            stream.acceptWaveform(x.copyOfRange(fed, fed + n), SR)
            fed += n
            drainAndSnapshot(fed * 1000.0 / SR)
        }

        val clipEndWall = (nowNs() - t0) / 1e6
        val clipMs = x.size * 1000.0 / SR

        // Trailing silence, still in real time: this is how the app learns the turn
        // ended. The endpoint rule is identical for every arm (trailing_silence_ms),
        // so the difference between arms is the decode tail, not the rule.
        val silence = FloatArray(chunk)
        var tailMs = 0
        var endpointWall = -1.0
        val endpoints = JSONArray()
        val tailDecodeStart = decodeMs
        while (tailMs < maxTailMs) {
            if (realtime) waitUntil(t0 + ((x.size + SR.toLong() * tailMs / 1000) * 1_000_000_000L) / SR)
            stream.acceptWaveform(silence, SR)
            tailMs += chunkMs
            drainAndSnapshot(clipMs + tailMs)
            if (rec.isEndpoint(stream)) {
                endpointWall = (nowNs() - t0) / 1e6
                endpoints.put(endpointWall)
                break
            }
        }
        val finalText = rec.getResult(stream).text
        val finalWall = (nowNs() - t0) / 1e6
        stream.release()

        out.put("seconds", clipMs / 1000.0)
        out.put("partials", partials)
        out.put("final_text", finalText)
        out.put("clip_end_wall_ms", clipEndWall)
        out.put("endpoint_wall_ms", endpointWall)
        out.put("endpoints", endpoints)
        out.put("final_wall_ms", finalWall)
        out.put("tail_ms", tailMs)
        out.put("decode_ms", decodeMs)
        out.put("tail_decode_ms", decodeMs - tailDecodeStart)
        out.put("rtf", decodeMs / clipMs)
        out.put("feed_lag_mean_ms", if (lagN == 0) 0.0 else lagSum / lagN)
        out.put("feed_lag_max_ms", lagMax)
        out.put("first_partial_wall_ms", firstPartialWall)
        out.put("cpu_ticks", cpuTicks() - cpuStart)
        out.put("rss_before_kb", rssBefore)
        out.put("rss_kb", rssKb())
        if (!record) out.put("warmup", true)
        return out
    }

    // ---- pacing: park until the chunk is due, then spin the last 1.5 ms so the
    // recorded wall time is not quantised by the scheduler's sleep granularity.
    private fun waitUntil(dueNs: Long): Double {
        while (true) {
            val left = dueNs - nowNs()
            if (left <= 0) break
            if (left > 1_500_000) LockSupport.parkNanos(left - 1_000_000) else { /* spin */ }
        }
        val lateMs = (nowNs() - dueNs) / 1e6
        return if (lateMs > 0) lateMs else 0.0
    }

    // ---- process introspection -------------------------------------------------
    private fun procStatus(key: String): Long {
        return try {
            File("/proc/self/status").readLines()
                .firstOrNull { it.startsWith("$key:") }
                ?.let { it.substringAfter(':').trim().removeSuffix(" kB").trim().toLong() } ?: 0L
        } catch (_: Throwable) { 0L }
    }

    fun rssKb(): Long = procStatus("VmRSS")
    fun peakRssKb(): Long = procStatus("VmHWM")

    fun cpuTicks(): Long = try {
        val f = File("/proc/self/stat").readText().substringAfter(") ").split(" ")
        // fields after comm: state=0 ..., utime=11, stime=12 (0-based here)
        (f[11].toLong() + f[12].toLong())
    } catch (_: Throwable) { 0L }
}

/** Battery energy-counter probe: microjoules-ish nWh deltas across a run.
 *  Recorded for both arms over the same workload, so the comparison is fair even
 *  though the device was on charge during measurement (see docs/STT-AB.md). */
class EnergyProbe(private val ctx: android.content.Context) {
    private val bm = ctx.getSystemService(android.content.Context.BATTERY_SERVICE)
        as android.os.BatteryManager
    private val marks = HashMap<String, Map<String, Long>>()

    private fun read(): Map<String, Long> = mapOf(
        "energy_nwh" to try { bm.getLongProperty(android.os.BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER) } catch (_: Throwable) { Long.MIN_VALUE },
        "charge_uah" to try { bm.getLongProperty(android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) } catch (_: Throwable) { Long.MIN_VALUE },
        "current_ua" to try { bm.getLongProperty(android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) } catch (_: Throwable) { Long.MIN_VALUE },
        "pct" to try { bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY).toLong() } catch (_: Throwable) { -1L },
    )

    fun snapshot(tag: String) { marks[tag] = read() }

    fun toJson(): JSONObject {
        val before = marks["before"] ?: emptyMap()
        val after = marks["after"] ?: emptyMap()
        return JSONObject()
            .put("before", JSONObject(before))
            .put("after", JSONObject(after))
            .put("energy_nwh_delta", (after["energy_nwh"] ?: 0) - (before["energy_nwh"] ?: 0))
            .put("charge_uah_delta", (after["charge_uah"] ?: 0) - (before["charge_uah"] ?: 0))
    }
}
