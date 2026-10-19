package com.hermesvox.bench

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import org.json.JSONObject
import java.io.File

/**
 * Entry point for the streaming-STT A/B (docs/STT-AB.md).
 *
 *   adb shell am start -n com.hermesvox.bench/.SttBenchActivity \
 *       -e config /sdcard/Android/data/com.hermesvox.bench/files/runs/<run>.json
 *
 * The config lists the model files, the thread count, the chunk size and every
 * clip; the result is written next to it as <run_id>.json and, when finished,
 * <run_id>.done (the host polls for the marker, then pulls the JSON).
 */
class SttBenchActivity : Activity() {

    companion object {
        /** Set when the activity is backgrounded mid-run. A run that lost the
         *  foreground is not a valid measurement (the scheduler throttles it and
         *  another app may be competing for the same cores), so the host refuses
         *  to score it. */
        @Volatile var interrupted = false
        @Volatile var running = false
    }

    private lateinit var status: TextView
    private var wake: PowerManager.WakeLock? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status = TextView(this).apply {
            textSize = 13f
            gravity = Gravity.START
            setPadding(32, 64, 32, 32)
            text = "starting…"
        }
        setContentView(status)

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "voxbench:run").apply {
            setReferenceCounted(false)
            acquire(30 * 60 * 1000L)
        }

        val configPath = intent?.getStringExtra("config")
            ?: File(getExternalFilesDir(null), "config.json").absolutePath
        val cfgFile = File(configPath)
        if (!cfgFile.exists()) {
            finishWith("config not found: $configPath")
            return
        }

        Thread {
            try {
                val cfg = JSONObject(cfgFile.readText())
                val runId = cfg.getString("run_id")
                val outDir = File(cfgFile.parentFile, "results").apply { mkdirs() }
                val bench = StreamingSttBench(cfg)
                val energy = EnergyProbe(this)
                interrupted = false
                running = true
                val result = bench.run(energy) { msg -> runOnUiThread { status.text = msg } }
                running = false
                result.put("interrupted", interrupted)
                result.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                result.put("sdk", Build.VERSION.SDK_INT)
                result.put("started_at", System.currentTimeMillis())
                val out = File(outDir, "$runId.json")
                out.writeText(result.toString(2))
                File(outDir, "$runId.done").writeText("${result.getJSONArray("clips").length()} clips\n")
                runOnUiThread { status.text = "done: ${out.absolutePath}" }
            } catch (t: Throwable) {
                val runId = runCatching { JSONObject(cfgFile.readText()).getString("run_id") }.getOrNull() ?: "error"
                val outDir = File(cfgFile.parentFile, "results").apply { mkdirs() }
                File(outDir, "$runId.error").writeText(t.stackTraceToString())
                runOnUiThread { status.text = "failed: ${t.message}" }
            } finally {
                runCatching { wake?.release() }
            }
        }.start()
    }

    private fun finishWith(msg: String) {
        status.text = msg
        runCatching { wake?.release() }
    }

    override fun onPause() {
        if (running) interrupted = true
        super.onPause()
    }

    override fun onDestroy() {
        runCatching { if (wake?.isHeld == true) wake?.release() }
        super.onDestroy()
    }
}
