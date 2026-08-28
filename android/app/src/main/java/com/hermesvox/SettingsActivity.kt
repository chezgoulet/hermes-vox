package com.hermesvox

import android.content.Context
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat

/**
 * SettingsActivity — a real settings screen. Sections: Entity (endpoint/model/
 * key, new conversation), Voice pipeline (STT/TTS/voice, barge-in), Appearance
 * (theme), About. Values are stored as stable TOKENS (the logic reads tokens,
 * e.g. buildTts checks "kokoro"/"piper"); the UI renders human labels.
 */
class SettingsActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("hv", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (prefs.getString("theme", "system") == "dark") AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<android.view.View>(R.id.settings_back).setOnClickListener { finish(); overridePendingTransition(R.anim.fade_in, R.anim.fade_out) }
        bindEntity()
        bindFlows()
        bindAppearance()
        bindMicSettings()
        bindParticles()
        findViewById<TextView>(R.id.set_about_val).text = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Throwable) { "?" }
    }

    private fun bindEntity() {
        findViewById<LinearLayout>(R.id.row_reset).setOnClickListener {
            try {
                MainActivity.resetActiveConversation()
                Toast.makeText(this, "New conversation (context cleared)", Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {}
        }
        findViewById<LinearLayout>(R.id.row_entity).setOnClickListener {
            val view = layoutInflater.inflate(R.layout.dialog_entity, null)
            val eurl = view.findViewById<EditText>(R.id.d_url)
            val emodel = view.findViewById<EditText>(R.id.d_model)
            val ekey = view.findViewById<EditText>(R.id.d_key)
            eurl.setText(prefs.getString("url", ""))
            emodel.setText(prefs.getString("model", "hermes-agent"))
            ekey.setText(SecureStore.decrypt(prefs.getString("key", "").orEmpty()).orEmpty())
            AlertDialog.Builder(this)
                .setTitle("Entity")
                .setView(view)
                .setPositiveButton("Save") { _, _ ->
                    prefs.edit().putString("url", eurl.text.toString().trim())
                        .putString("model", emodel.text.toString().trim().ifEmpty { "hermes-agent" })
                        .putString("key", (SecureStore.encrypt(ekey.text.toString().trim()) ?: ekey.text.toString().trim())).apply()
                    refreshEntityVal()
                }
                .setNegativeButton("Cancel", null).show()
        }
        refreshEntityVal()
    }

    private fun refreshEntityVal() {
        val u = prefs.getString("url", "").orEmpty()
        findViewById<TextView>(R.id.set_entity_val).text =
            if (u.isBlank()) "—" else "$u · ${prefs.getString("model", "hermes-agent")}"
    }

    private fun bindFlows() {
        findViewById<LinearLayout>(R.id.row_mode).setOnClickListener {
            pick("Voice mode",
                arrayOf("Realtime", "Enhanced Realtime", "Walkie Talkie"),
                arrayOf(ModelCatalog.MODE_REALTIME, ModelCatalog.MODE_ENHANCED, ModelCatalog.MODE_WALKIE),
                ModelCatalog.KEY_VOICE_MODE, R.id.set_mode_val)
        }
        findViewById<LinearLayout>(R.id.row_stt).setOnClickListener {
            pick("Speech-to-text (backend)",
                arrayOf("On-device (offline)", "Platform (Google)"),
                arrayOf(ModelCatalog.BACKEND_ONDEVICE, ModelCatalog.BACKEND_PLATFORM),
                ModelCatalog.KEY_STT_BACKEND, R.id.set_stt_val)
        }
        findViewById<LinearLayout>(R.id.row_stt_model).setOnClickListener {
            val labels = ModelCatalog.sttModels.map { it.second }.toTypedArray()
            val tokens = ModelCatalog.sttModels.map { it.first }.toTypedArray()
            pick("STT model (on-device)", labels, tokens, ModelCatalog.KEY_STT_MODEL, R.id.set_stt_model_val)
        }
        findViewById<LinearLayout>(R.id.row_tts).setOnClickListener {
            pick("Text-to-speech",
                arrayOf("System (fallback)", "Kokoro", "Piper (on-device)"),
                arrayOf("system", "kokoro", "piper"), "tts", R.id.set_tts_val)
        }
        // Voice = the SYNTHESIS REGISTER (system/bright/deep). The old "warm"
        // option was removed — WarmTts is a no-op stub, so it never produced
        // audio (never offer an option that doesn't work). The engines now honor
        // this pref (see voiceRegister), so the picker really affects output.
        findViewById<LinearLayout>(R.id.row_voice).setOnClickListener {
            pick("Voice (register)", arrayOf("System", "Bright", "Deep"),
                arrayOf("system", "bright", "deep"), "voice", R.id.set_voice_val)
        }

        // Per sub-menu RESTORE DEFAULTS (via the central restoreDefaults helper).
        bindRestoreRow(R.id.row_mode_reset, GROUP_MODE, "Mode")
        bindRestoreRow(R.id.row_stt_reset, GROUP_STT, "STT")
        bindRestoreRow(R.id.row_tts_reset, GROUP_TTS, "TTS")
        bindRestoreRow(R.id.row_voice_reset, GROUP_VOICE, "Voice")
        val barge = findViewById<SwitchCompat>(R.id.set_bargein)
        barge.isChecked = prefs.getBoolean("duplex", true)
        barge.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("duplex", on).apply() }
        val speak = findViewById<SwitchCompat>(R.id.set_speak)
        speak.isChecked = prefs.getBoolean("speak_responses", true)
        speak.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("speak_responses", on).apply() }
        val devc = findViewById<SwitchCompat>(R.id.set_devconsole)
        devc.isChecked = prefs.getBoolean("dev_console", false)
        devc.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("dev_console", on).apply() }
        val ltr = findViewById<SwitchCompat>(R.id.set_logtranscripts)
        ltr.isChecked = prefs.getBoolean("log_transcripts", false)
        ltr.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("log_transcripts", on).apply() }
        findViewById<LinearLayout>(R.id.row_models).setOnClickListener {
            startActivity(android.content.Intent(this, ModelsActivity::class.java))
        }
        val installed = ModelCatalog.blessed.count { ModelCatalog.isInstalled(this, it.id) }
        findViewById<TextView>(R.id.set_models_val).text =
            "$installed/${ModelCatalog.blessed.size} installed · on-device, offline"

        refreshFlowVals()
    }

    private fun refreshFlowVals() {
        findViewById<TextView>(R.id.set_mode_val).text = modeLabel(prefs.getString(ModelCatalog.KEY_VOICE_MODE, ModelCatalog.MODE_REALTIME) ?: ModelCatalog.MODE_REALTIME)
        findViewById<TextView>(R.id.set_stt_val).text = sttBackendLabel(prefs.getString(ModelCatalog.KEY_STT_BACKEND, ModelCatalog.BACKEND_ONDEVICE) ?: ModelCatalog.BACKEND_ONDEVICE)
        val model = prefs.getString(ModelCatalog.KEY_STT_MODEL, ModelCatalog.DEFAULT_STT_MODEL) ?: ModelCatalog.DEFAULT_STT_MODEL
        findViewById<TextView>(R.id.set_stt_model_val).text = ModelCatalog.sttModels.firstOrNull { it.first == model }?.second ?: model
        findViewById<TextView>(R.id.set_tts_val).text = label("tts", "system")
        findViewById<TextView>(R.id.set_voice_val).text = label("voice", "system")
    }

    private fun sttBackendLabel(tok: String): String = when (tok) {
        ModelCatalog.BACKEND_PLATFORM -> "Platform (Google)"
        else -> "On-device (offline)"
    }
    private fun modeLabel(tok: String): String = when (tok) {
        ModelCatalog.MODE_ENHANCED -> "Enhanced Realtime"
        ModelCatalog.MODE_WALKIE -> "Walkie Talkie"
        else -> "Realtime"
    }

    private fun bindAppearance() {
        findViewById<LinearLayout>(R.id.row_theme).setOnClickListener {
            pick("Theme", arrayOf("System", "Dark", "Light"),
                arrayOf("system", "dark", "light"), "theme", R.id.set_theme_val) {
                applyTheme(prefs.getString("theme", "system")!!)
                recreate()
            }
        }
        findViewById<TextView>(R.id.set_theme_val).text = label("theme", "system")
        findViewById<LinearLayout>(R.id.row_layout).setOnClickListener {
            pick("Layout", arrayOf("Presence", "Conversation"),
                arrayOf("presence", "conversation"), "layout_mode", R.id.set_layout_val)
        }
        findViewById<TextView>(R.id.set_layout_val).text = label("layout_mode", "presence")
                findViewById<android.view.View>(R.id.row_test_conn)?.setOnClickListener {
            val u = prefs.getString("url", ""); val k = SecureStore.decrypt(prefs.getString("key", "").orEmpty()).orEmpty()
            val c = com.hermesvox.VoiceController(this, com.hermesvox.mobile.HermesSession(u ?: "", k ?: "", ""))
            val r = c.testConnection()
            findViewById<TextView>(R.id.set_test_val)?.text = "done"
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Connection test")
                .setMessage(r)
                .setPositiveButton("OK", null).show()
        }
        findViewById<LinearLayout>(R.id.row_debug).setOnClickListener {
            val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Throwable) { "?" }
            val crash = CrashLog.read(this)
            val full = try {
                val f = java.io.File(filesDir, "logs/hermes-vox.log")
                if (f.exists()) f.readText() else "no runtime log"
            } catch (_: Throwable) { "no runtime log" }
            // Full session log (this file is the WHOLE log since app start, so send the
            // whole thing — not a tail). Cap the inline dialog view only.
            val runtime = full.takeLast(60000).ifEmpty { "no runtime log" }
            val log = "=== Hermes Vox DEBUG — version $ver ===\n\n=== CRASH LOG ===\n$crash\n\n=== RUNTIME LOG (full) ===\n$runtime"
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Debug / logs")
                .setMessage(log)
                .setPositiveButton("Copy all") { _, _ ->
                    val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("vox-debug", log))
                    android.widget.Toast.makeText(this, "copied", android.widget.Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("Share full log") { _, _ ->
                    val f = java.io.File(filesDir, "logs/hermes-vox.log")
                    val uri = androidx.core.content.FileProvider.getUriForFile(this, packageName + ".fileprovider", f)
                    val i = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(android.content.Intent.EXTRA_STREAM, uri); addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(android.content.Intent.createChooser(i, "Send full Hermes Vox log"))
                }
                .setNegativeButton("Clear logs") { _, _ ->
                    CrashLog.clear(this)
                    try { java.io.File(filesDir, "logs/hermes-vox.log").delete() } catch (_: Throwable) {}
                    findViewById<TextView>(R.id.set_debug_val).text = "view"
                }
                .show()
        }
    }

    // ---- Mic / Speech tuning (exact-range SeekBars reading + writing the SAME
    // prefs the pipeline reads; defaults = shipped values; a restore-defaults
    // button resets all eight) -------------------------------------------
    private fun bindMicSettings() {
        bindMicToggle(R.id.set_mic_aec, "mic_aec", true)
        bindMicToggle(R.id.set_ns_extra, "ns_extra", true)
        bindMicToggle(R.id.set_partial_stt, "partial_stt", true)

        bindFloatSeekBar(R.id.set_seek_vad, R.id.set_mic_vad_val, "vad_threshold",
            0.1f, 0.9f, 0.05f, 0.5f) { "%.2f".format(it) }
        bindIntSeekBar(R.id.set_seek_silence, R.id.set_mic_silence_val, "vad_silence_ms",
            200, 2000, 50, 800) { "${it} ms" }
        bindIntSeekBar(R.id.set_seek_early, R.id.set_mic_early_val, "vad_early_silence_ms",
            150, 800, 50, 450) { "${it} ms" }
        bindIntSeekBar(R.id.set_seek_min, R.id.set_mic_min_speech_val, "vad_min_speech_ms",
            100, 1000, 50, 300) { "${it} ms" }
        bindIntSeekBar(R.id.set_seek_max, R.id.set_mic_max_val, "vad_max_ms",
            8000, 30000, 500, 15000) { "${it} ms" }

        findViewById<LinearLayout>(R.id.row_mic_reset).setOnClickListener {
            restoreDefaults(GROUP_MIC)
            Toast.makeText(this, "Mic / Speech defaults restored", Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindRestoreRow(rowId: Int, group: String, label: String) {
        findViewById<android.view.View>(rowId).setOnClickListener {
            restoreDefaults(group)
            Toast.makeText(this, "$label defaults restored", Toast.LENGTH_SHORT).show()
        }
    }

    private fun bindMicToggle(id: Int, key: String, def: Boolean) {
        findViewById<SwitchCompat>(id).apply {
            isChecked = prefs.getBoolean(key, def)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() }
        }
    }

    /** An int SeekBar snapped to [step] between [min]..[max]; writes the pref 1:1. */
    private fun bindIntSeekBar(seekId: Int, valId: Int, key: String,
                               min: Int, max: Int, step: Int, def: Int, fmt: (Int) -> String) {
        val seek = findViewById<SeekBar>(seekId)
        val steps = (max - min) / step
        seek.max = steps
        val tv = findViewById<TextView>(valId)
        seek.progress = snapInt(prefs.getInt(key, def).coerceIn(min, max), min, step)
        tv.text = fmt(min + seek.progress * step)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val v = min + p * step
                prefs.edit().putInt(key, v).apply()
                tv.text = fmt(v)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    /** A float SeekBar snapped to [step] between [min]..[max]; writes the pref 1:1. */
    private fun bindFloatSeekBar(seekId: Int, valId: Int, key: String,
                                 min: Float, max: Float, step: Float, def: Float, fmt: (Float) -> String) {
        val seek = findViewById<SeekBar>(seekId)
        val steps = ((max - min) / step).toInt()
        seek.max = steps
        val tv = findViewById<TextView>(valId)
        seek.progress = snapFloat(prefs.getFloat(key, def).coerceIn(min, max), min, step)
        tv.text = fmt(min + seek.progress * step)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val v = min + p * step
                prefs.edit().putFloat(key, v).apply()
                tv.text = fmt(v)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    private fun snapInt(v: Int, min: Int, step: Int): Int = ((v - min) / step).coerceAtLeast(0)
    private fun snapFloat(v: Float, min: Float, step: Float): Int =
        ((v - min) / step).toInt().coerceAtLeast(0)

    // ---- Particles / presence settings (moved off the raw avatar tap; tap = stop) ----
    private fun bindParticles() {
        val themeLabels = arrayOf("Aura", "Iris", "Vortex", "Waveform", "Scan", "Constellation")
        val themeVals = arrayOf("aura", "iris", "vortex", "waveform", "scan", "constellation")
        val theme = prefs.getString("particles_theme", "aura") ?: "aura"
        setStringVal(R.id.set_particle_theme_val, themeLabels, themeVals, theme)
        findViewById<LinearLayout>(R.id.row_particle_theme).setOnClickListener {
            micChoiceString("Presence shape / theme", themeLabels, themeVals, "particles_theme", R.id.set_particle_theme_val)
        }
        val cyc = findViewById<SwitchCompat>(R.id.set_particle_cycle)
        cyc.isChecked = prefs.getBoolean("particles_cycle", true)
        cyc.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("particles_cycle", on).apply() }
    }

    private fun setStringVal(valId: Int, labels: Array<String>, vals: Array<String>, cur: String) {
        val idx = vals.indexOfFirst { it == cur }.coerceAtLeast(0).coerceAtMost(vals.size - 1)
        findViewById<TextView>(valId).text = labels[idx]
    }
    private fun micChoiceString(title: String, labels: Array<String>, vals: Array<String>, key: String, valId: Int) {
        val cur = prefs.getString(key, vals.getOrElse(0) { "aura" }) ?: "aura"
        val idx = vals.indexOfFirst { it == cur }.coerceAtLeast(0).coerceAtMost(vals.size - 1)
        AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(labels, idx) { d, which ->
            prefs.edit().putString(key, vals[which]).apply()
            findViewById<TextView>(valId).text = labels[which]
            d.dismiss()
        }.show()
    }

    /** Store the TOKEN; render its human label. */
    private fun pick(title: String, labels: Array<String>, tokens: Array<String>, prefKey: String, valId: Int, onApplied: (() -> Unit)? = null) {
        val cur = prefs.getString(prefKey, "") ?: ""
        val idx = tokens.indexOfFirst { it == cur }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels, idx) { d, which ->
                prefs.edit().putString(prefKey, tokens[which]).apply()
                findViewById<TextView>(valId).text = labels[which]
                d.dismiss(); onApplied?.invoke()
            }
            .show()
    }

    private fun label(prefKey: String, default: String): String {
        val tok = prefs.getString(prefKey, default) ?: default
        return when (tok) {
            "on-device" -> "On-device"; "rx590" -> "RX 590"; "odroid" -> "Odroid"
            "system" -> "System"; "kokoro" -> "Kokoro"; "piper" -> "Piper"
            "warm" -> "Warm"; "bright" -> "Bright"; "deep" -> "Deep"
            "dark" -> "Dark"; "light" -> "Light"
            else -> tok
        }
    }

    /** Central restore-defaults: reset a sub-menu's prefs back to the SHIPPED
     *  defaults, then re-sync the visible controls for that group. */
    private fun restoreDefaults(group: String) {
        val e = prefs.edit()
        when (group) {
            GROUP_MIC -> e
                .putFloat("vad_threshold", 0.5f)
                .putInt("vad_silence_ms", 800)
                .putInt("vad_early_silence_ms", 450)
                .putInt("vad_min_speech_ms", 300)
                .putInt("vad_max_ms", 15000)
                .putBoolean("partial_stt", true)
                .putBoolean("mic_aec", true)
                .putBoolean("ns_extra", true)
            GROUP_STT -> e
                .putString(ModelCatalog.KEY_STT_BACKEND, ModelCatalog.BACKEND_ONDEVICE)
                .putString(ModelCatalog.KEY_STT_MODEL, ModelCatalog.DEFAULT_STT_MODEL)
            GROUP_TTS -> e.putString("tts", "system")
            GROUP_VOICE -> e.putString("voice", "system")
            GROUP_MODE -> e.putString(ModelCatalog.KEY_VOICE_MODE, ModelCatalog.MODE_REALTIME)
        }
        e.apply()
        when (group) {
            GROUP_MIC -> bindMicSettings()
            else -> refreshFlowVals()
        }
    }

    private fun applyTheme(mode: String) {
        when (mode) {
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    private companion object {
        const val GROUP_MIC = "mic"
        const val GROUP_STT = "stt"
        const val GROUP_TTS = "tts"
        const val GROUP_VOICE = "voice"
        const val GROUP_MODE = "mode"
    }
}
