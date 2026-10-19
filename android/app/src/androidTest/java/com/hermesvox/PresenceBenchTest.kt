@file:OptIn(com.google.ai.edge.litertlm.ExperimentalApi::class)

package com.hermesvox

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * PresenceBench — the on-device measurement for the presence model decision (docs/DECISIONS.md D4).
 *
 * NOT part of the app. It is an instrumented test so it runs on a phone against the same
 * pinned LiteRT-LM the app ships (0.16.1) and shares the app's model paths, but it is never
 * packaged into a release APK. `scripts/gate.sh` does not run it (it needs a device).
 *
 * What it measures, per model and per backend:
 *   - cold load:    Engine() + initialize() with an EMPTY compile-cache dir (fresh install)
 *   - warm load:    the same on a cache dir a previous init already populated
 *   - first token:  send -> first onMessage callback (wall clock) AND the runtime's own
 *                   BenchmarkInfo.timeToFirstTokenInSecond
 *   - decode:       the runtime's BenchmarkInfo.lastDecodeTokensPerSecond plus wall-clock
 *                   (last callback - first callback) over the callback chunk count
 *   - line time:    send -> onDone, for a 32-token-capped short line
 *   - peak RSS:     /proc/self/status VmHWM (whole process, so one process per run)
 *
 * Prompt shape is fixed and presence-shaped: a short system line + one user line, greedy
 * (topK=1, temperature=0), 32 output tokens.
 *
 * Usage (from the host, one process per model+backend so VmHWM is meaningful):
 *   adb shell am instrument -w \
 *     -e bench.models qwen -e bench.backends cpu -e bench.reps 3 \
 *     -e bench.dir /sdcard/Android/data/com.hermesvox/files/bench \
 *     com.hermesvox.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Results are appended (and fsynced) to <bench.dir>/results.jsonl, one JSON object per line.
 */
@RunWith(AndroidJUnit4::class)
class PresenceBenchTest {

    private val args = InstrumentationRegistry.getArguments()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Models live in the app's OWN external files dir: `adb push` can write there (the shell
     * user is in ext_data_rw) and the app can read it with no storage permission. The dir is
     * created by the app on first use, so it is app-owned rather than shell-owned.
     */
    private val benchDir: File by lazy {
        File(args.getString("bench.dir") ?: File(context.getExternalFilesDir(null), "bench").path)
    }

    /**
     * Results go to INTERNAL storage: /sdcard/Android/data is not writable by the app when the
     * directory was created by the shell, and an OOM-killed run must still leave its numbers
     * behind. Pulled with `adb exec-out run-as com.hermesvox cat files/bench-results/...`.
     */
    private val resultsFile: File by lazy {
        val f = File(args.getString("bench.results")
            ?: File(context.filesDir, "bench-results/results.jsonl").path)
        f.parentFile?.mkdirs()
        f
    }

    /** model id -> the .litertlm file name (the catalog's own artifact names). */
    private val catalog = mapOf(
        "qwen" to "Qwen3.5-0.8B_int8.litertlm",
        "smollm" to "SmolLM2_360M_instruct.litertlm",
        "gemma" to "gemma-4-E2B-it.litertlm",
    )

    @Test
    fun bench() {
        val models = (args.getString("bench.models") ?: "qwen").split(",").map { it.trim() }
        val backends = (args.getString("bench.backends") ?: "cpu").split(",").map { it.trim() }
        val reps = (args.getString("bench.reps") ?: "3").toInt()
        val shape = args.getString("bench.shape") ?: "presence"
        val initTimeoutS = (args.getString("bench.initTimeoutS") ?: "180").toLong()

        benchDir.mkdirs()
        val out = resultsFile

        for (id in models) for (backend in backends) {
            val name = catalog[id] ?: continue
            val modelFile = File(benchDir, name)
            if (!modelFile.exists()) {
                emit(out, "{\"model\":\"$id\",\"backend\":\"$backend\",\"error\":\"missing model file $name\"}")
                continue
            }
            runOne(out, id, modelFile, backend, shape, reps, initTimeoutS)
        }
    }

    private fun runOne(
        out: File,
        id: String,
        modelFile: File,
        backendName: String,
        shape: String,
        reps: Int,
        initTimeoutS: Long,
    ) {
        val backend = if (backendName == "gpu") Backend.GPU() else Backend.CPU()
        val cacheDir = File(benchDir, "cache-$id-$backendName")
        // COLD: no compile cache at all, which is what a fresh install has.
        cacheDir.deleteRecursively()
        cacheDir.mkdirs()

        val cold = initEngine(modelFile, backend, cacheDir, initTimeoutS)
        if (cold.engine == null) {
            emit(out, json(id, backendName, shape, rep = -1, cold = true,
                initMs = cold.ms, error = cold.error))
            return
        }
        emit(out, json(id, backendName, shape, rep = -1, cold = true, initMs = cold.ms,
            hwmKb = vmHwmKb()))
        try {
            generate(out, id, cold.engine, backendName, shape, reps, cold = true)
        } finally {
            try { cold.engine.close() } catch (_: Throwable) {}
        }

        // WARM: a second init on the cache the cold init populated — the second app launch.
        val warm = initEngine(modelFile, backend, cacheDir, initTimeoutS)
        if (warm.engine == null) {
            emit(out, json(id, backendName, shape, rep = -1, cold = false,
                initMs = warm.ms, error = warm.error))
            return
        }
        emit(out, json(id, backendName, shape, rep = -1, cold = false, initMs = warm.ms,
            hwmKb = vmHwmKb()))
        try {
            generate(out, id, warm.engine, backendName, shape, 1, cold = false)
        } finally {
            try { warm.engine.close() } catch (_: Throwable) {}
        }
        emit(out, json(id, backendName, shape, rep = -2, cold = false,
            hwmKb = vmHwmKb(), rssKb = vmRssKb()))
    }

    private class InitResult(val engine: Engine?, val ms: Long, val error: String?)

    /**
     * Initialize on a watcher thread. A GPU engine that cannot fit sometimes does not throw —
     * it stalls inside the native loader — so the host driver can time the whole instrumentation
     * out, and this returns a timeout record instead of hanging the test forever.
     */
    private fun initEngine(modelFile: File, backend: Backend, cacheDir: File, timeoutS: Long): InitResult {
        var engine: Engine? = null
        var error: String? = null
        val t0 = System.nanoTime()
        val t = Thread {
            try {
                val e = Engine(EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = backend,
                    // The presence layer never needs 32k: the persona is ~600 tokens and a
                    // render is capped at 256 output. 8k is what GemmaExpress asks for.
                    maxNumTokens = 8192,
                    cacheDir = cacheDir.path,
                ))
                e.initialize()
                engine = e
            } catch (t: Throwable) {
                error = "${t.javaClass.simpleName}: ${t.message}"
            }
        }
        t.isDaemon = true
        t.start()
        t.join(TimeUnit.SECONDS.toMillis(timeoutS))
        val ms = (System.nanoTime() - t0) / 1_000_000
        if (engine == null && error == null) error = "init timeout after ${timeoutS}s"
        return InitResult(engine, ms, error)
    }

    private fun generate(
        out: File,
        id: String,
        engine: Engine,
        backendName: String,
        shape: String,
        reps: Int,
        cold: Boolean,
    ) {
        val system = if (shape == "persona") LONG_PERSONA else SHORT_SYSTEM
        for (rep in 1..reps) {
            var conv: Conversation? = null
            try {
                val cfg = ConversationConfig(
                    systemInstruction = Contents.of(system),
                    // Greedy: one sample path, no sampler noise between models.
                    samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0),
                    maxOutputToken = 32,
                )
                val tConv = System.nanoTime()
                conv = engine.createConversation(cfg)
                val convMs = (System.nanoTime() - tConv) / 1_000_000

                val chunks = AtomicInteger(0)
                val firstAt = AtomicLong(0)
                val sb = StringBuilder()
                val done = CountDownLatch(1)
                var err: Throwable? = null
                val t0 = System.nanoTime()
                conv.sendMessageAsync(USER_LINE, object : MessageCallback {
                    override fun onMessage(m: Message) {
                        if (firstAt.compareAndSet(0, System.nanoTime())) {
                            // first chunk latency is wall-clock from the send
                        }
                        chunks.incrementAndGet()
                        for (c in m.contents.contents) {
                            if (c is com.google.ai.edge.litertlm.Content.Text) sb.append(c.text)
                        }
                    }
                    override fun onDone() { done.countDown() }
                    override fun onError(t: Throwable) { err = t; done.countDown() }
                })
                if (!done.await(120, TimeUnit.SECONDS)) {
                    try { conv.cancelProcess() } catch (_: Throwable) {}
                    emit(out, json(id, backendName, shape, rep, cold,
                        error = "generation timeout 120s", convMs = convMs))
                    return
                }
                val endAt = System.nanoTime()
                err?.let {
                    emit(out, json(id, backendName, shape, rep, cold,
                        error = "${it.javaClass.simpleName}: ${it.message}", convMs = convMs))
                    return
                }
                val ttftMs = (firstAt.get() - t0) / 1_000_000
                val totalMs = (endAt - t0) / 1_000_000
                val n = chunks.get()
                val decodeMs = (endAt - firstAt.get()) / 1_000_000
                val wallTokPerS = if (decodeMs > 0 && n > 1) (n - 1) * 1000.0 / decodeMs else 0.0
                val bi = try { conv.getBenchmarkInfo() } catch (_: Throwable) { null }
                val tokens = try { conv.getTokenCount() } catch (_: Throwable) { -1 }
                emit(out,
                    json(id, backendName, shape, rep, cold,
                        initMs = null, convMs = convMs, ttftMs = ttftMs, totalMs = totalMs,
                        chunks = n, wallTokPerS = wallTokPerS,
                        rtTtftMs = bi?.timeToFirstTokenInSecond?.times(1000.0),
                        rtDecodeTokPerS = bi?.lastDecodeTokensPerSecond,
                        rtDecodeTokens = bi?.lastDecodeTokenCount,
                        rtPrefillTokens = bi?.lastPrefillTokenCount,
                        rtPrefillTokPerS = bi?.lastPrefillTokensPerSecond,
                        tokens = tokens,
                        hwmKb = vmHwmKb(),
                        text = sb.toString().replace("\n", " ").take(160)))
            } catch (t: Throwable) {
                emit(out, json(id, backendName, shape, rep, cold,
                    error = "${t.javaClass.simpleName}: ${t.message}"))
            } finally {
                try { conv?.close() } catch (_: Throwable) {}
            }
        }
    }

    // ---- plumbing ----

    private fun json(
        model: String,
        backend: String,
        shape: String,
        rep: Int,
        cold: Boolean,
        initMs: Long? = null,
        convMs: Long? = null,
        ttftMs: Long? = null,
        totalMs: Long? = null,
        chunks: Int? = null,
        wallTokPerS: Double? = null,
        rtTtftMs: Double? = null,
        rtDecodeTokPerS: Double? = null,
        rtDecodeTokens: Int? = null,
        rtPrefillTokens: Int? = null,
        rtPrefillTokPerS: Double? = null,
        tokens: Int? = null,
        hwmKb: Int? = null,
        rssKb: Int? = null,
        text: String? = null,
        error: String? = null,
    ): String {
        val f = StringBuilder("{")
        fun kv(k: String, v: Any?, q: Boolean = false) {
            if (v == null) return
            if (f.length > 1) f.append(',')
            f.append('"').append(k).append("\":")
            if (q) f.append('"').append(v.toString().replace("\\", "\\\\").replace("\"", "\\\""))
                .append('"') else f.append(v)
        }
        kv("model", model, q = true); kv("backend", backend, q = true)
        kv("shape", shape, q = true); kv("rep", rep); kv("cold", cold)
        kv("init_ms", initMs); kv("conv_ms", convMs)
        kv("ttft_ms", ttftMs); kv("total_ms", totalMs); kv("chunks", chunks)
        kv("wall_tok_s", wallTokPerS); kv("rt_ttft_ms", rtTtftMs)
        kv("rt_decode_tok_s", rtDecodeTokPerS); kv("rt_decode_tokens", rtDecodeTokens)
        kv("rt_prefill_tokens", rtPrefillTokens); kv("rt_prefill_tok_s", rtPrefillTokPerS)
        kv("tokens", tokens)
        kv("hwm_kb", hwmKb); kv("rss_kb", rssKb)
        kv("text", text, q = true); kv("error", error, q = true)
        f.append('}')
        return f.toString()
    }

    private fun emit(out: File, line: String) {
        out.appendText(line + "\n")
        try {
            java.io.FileOutputStream(out, true).use { it.flush(); it.fd.sync() }
        } catch (_: Throwable) {}
    }

    private fun procStatus(key: String): Int {
        return try {
            File("/proc/self/status").readLines()
                .firstOrNull { it.startsWith("$key:") }
                ?.filter { it.isDigit() }?.toIntOrNull() ?: -1
        } catch (_: Throwable) { -1 }
    }

    private fun vmHwmKb() = procStatus("VmHWM")
    private fun vmRssKb() = procStatus("VmRSS")

    companion object {
        /** The presence shape: what GemmaExpress.MINIMAL_PERSONA is, plus one caller line. */
        const val SHORT_SYSTEM =
            "You are the voice of an agent on a phone call. Reply in one short spoken sentence. " +
                "Never say you are an AI or a model."

        const val USER_LINE =
            "Operator directive: intent=greeting. Content to render: the caller just picked up " +
                "the phone and said hello."

        /**
         * The app's real render shape: the ~600-token soul persona is prefilled on every
         * render in the current GemmaExpress path, and that prefill is where the field's
         * ~2.2 s warm render goes. Kept here so the cost of the preface is measurable.
         */
        val LONG_PERSONA: String = buildString {
            append("You are Hermes: the voice of an agent, speaking on a phone call. ")
            append("You are not the mind. The mind has already decided; you give it a voice. ")
            append("Reply in one short spoken sentence, under twenty words. ")
            append("Never say you are an AI, a model, or an assistant. Never describe yourself. ")
            append("Never offer to help with tasks. Keep the call moving. ")
            repeat(26) {
                append("Your manner is warm, dry, unhurried, and confident; you never fill silence ")
                append("with noise, and you never repeat a line you have already said on this call. ")
            }
        }
    }
}
