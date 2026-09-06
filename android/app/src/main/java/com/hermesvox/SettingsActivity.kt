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
    // WS1: which nested sub-view is currently shown (null = the Settings home list).
    private var currentSection: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        if (prefs.getString("theme", "system") == "dark") AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Back button is section-aware: inside a sub-view it returns to the group
        // list; at the home list it finishes the Activity.
        findViewById<android.view.View>(R.id.settings_back).setOnClickListener {
            if (currentSection != null) { showGroupList() } else { finish(); overridePendingTransition(R.anim.fade_in, R.anim.fade_out) }
        }
        bindEntity()
        bindFlows()
        bindAppearance()
        bindMicSettings()
        bindParticles()
        bindGroups()
        bindSectionRestoreRows()
        findViewById<TextView>(R.id.set_about_val).text = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Throwable) { "?" }
    }

    /** WS1: open a nested sub-view (hide the group list, show the section container). */
    private fun showSection(section: String) {
        currentSection = section
        findViewById<android.view.View>(R.id.grp_list).visibility = android.view.View.GONE
        val ids = mapOf(
            SECTION_ENTITY to R.id.sec_entity, SECTION_SPEECH to R.id.sec_speech,
            SECTION_STT to R.id.sec_stt, SECTION_TTS to R.id.sec_tts,
            SECTION_MODELS to R.id.sec_models, SECTION_APPEARANCE to R.id.sec_appearance,
            SECTION_ABOUT to R.id.sec_about)
        ids.values.forEach { findViewById<android.view.View>(it).visibility = android.view.View.GONE }
        findViewById<android.view.View>(ids[section]!!).visibility = android.view.View.VISIBLE
        findViewById<android.widget.ScrollView>(R.id.settings_scroll).scrollTo(0, 0)
        findViewById<TextView>(R.id.set_title).text = when (section) {
            SECTION_ENTITY -> "Entity & Connection"; SECTION_SPEECH -> "Speech & Mic"
            SECTION_STT -> "STT & Transcription"; SECTION_TTS -> "TTS & Voice"
            SECTION_MODELS -> "Voice models"; SECTION_APPEARANCE -> "Appearance & Presence"
            else -> "About & Diagnostics"
        }
    }

    /** WS1: return to the Settings home (group list). */
    private fun showGroupList() {
        currentSection = null
        findViewById<android.view.View>(R.id.grp_list).visibility = android.view.View.VISIBLE
        val ids = listOf(R.id.sec_entity, R.id.sec_speech, R.id.sec_stt, R.id.sec_tts,
            R.id.sec_models, R.id.sec_appearance, R.id.sec_about)
        ids.forEach { findViewById<android.view.View>(it).visibility = android.view.View.GONE }
        findViewById<TextView>(R.id.set_title).text = "Settings"
    }

    private fun bindGroups() {
        findViewById<android.view.View>(R.id.row_grp_models)?.setOnClickListener { showSection(SECTION_MODELS) }
        findViewById<android.view.View>(R.id.row_grp_entity)?.setOnClickListener { showSection(SECTION_ENTITY) }
        findViewById<android.view.View>(R.id.row_grp_speech)?.setOnClickListener { showSection(SECTION_SPEECH) }
        findViewById<android.view.View>(R.id.row_grp_stt)?.setOnClickListener { showSection(SECTION_STT) }
        findViewById<android.view.View>(R.id.row_grp_tts)?.setOnClickListener { showSection(SECTION_TTS) }
        findViewById<android.view.View>(R.id.row_grp_appearance)?.setOnClickListener { showSection(SECTION_APPEARANCE) }
        findViewById<android.view.View>(R.id.row_grp_about)?.setOnClickListener { showSection(SECTION_ABOUT) }
    }

    private fun bindSectionRestoreRows() {
        bindRestoreRow(R.id.row_restore_entity, GROUP_ENTITY, "Entity & Connection")
        bindRestoreRow(R.id.row_restore_models, GROUP_MODELS, "Models")
        bindRestoreRow(R.id.row_restore_appearance, GROUP_APPEARANCE, "Appearance")
        bindRestoreRow(R.id.row_restore_about, GROUP_ABOUT, "About")
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
                arrayOf("On-device (offline)", "Platform (Google)", "Remote (server)"),
                arrayOf(ModelCatalog.BACKEND_ONDEVICE, ModelCatalog.BACKEND_PLATFORM, ModelCatalog.BACKEND_REMOTE),
                ModelCatalog.KEY_STT_BACKEND, R.id.set_stt_val) { refreshSttRemotePanel() }
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
        val dbl = findViewById<SwitchCompat>(R.id.set_debuglog)
        dbl.isChecked = prefs.getBoolean("debug_log", false)
        dbl.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("debug_log", on).apply(); VoxLog.setDebugFile(on) }
        findViewById<LinearLayout>(R.id.row_models).setOnClickListener {
            startActivity(android.content.Intent(this, ModelsActivity::class.java))
        }
        refreshModelsVal()
        bindSttRemote()

        refreshFlowVals()
    }

    private fun refreshFlowVals() {
        findViewById<TextView>(R.id.set_mode_val).text = modeLabel(prefs.getString(ModelCatalog.KEY_VOICE_MODE, ModelCatalog.MODE_REALTIME) ?: ModelCatalog.MODE_REALTIME)
        findViewById<TextView>(R.id.set_stt_val).text = sttBackendLabel(prefs.getString(ModelCatalog.KEY_STT_BACKEND, ModelCatalog.BACKEND_ONDEVICE) ?: ModelCatalog.BACKEND_ONDEVICE)
        val model = prefs.getString(ModelCatalog.KEY_STT_MODEL, ModelCatalog.DEFAULT_STT_MODEL) ?: ModelCatalog.DEFAULT_STT_MODEL
        findViewById<TextView>(R.id.set_stt_model_val).text = ModelCatalog.sttModels.firstOrNull { it.first == model }?.second ?: model
        findViewById<TextView>(R.id.set_tts_val).text = label("tts", "system")
        findViewById<TextView>(R.id.set_voice_val).text = label("voice", "system")
        refreshSttRemotePanel()
    }

    /** The install-progress caption for the Models section (group row + sub-view row). */
    private fun refreshModelsVal() {
        val installed = ModelCatalog.blessed.count { ModelCatalog.isInstalled(this, it.id) }
        val text = "$installed/${ModelCatalog.blessed.size} installed · needed for your voice"
        findViewById<TextView>(R.id.set_models_val)?.text = text
        findViewById<TextView>(R.id.set_models_grpval)?.text = text
    }

    // ---- Remote STT (server) backend: URL/model/key + connection test. This
    // panel is revealed ONLY when the STT backend picker says "remote"; its
    // fields write to the SAME prefs RemoteStt reads (stt_remote_*), and the key
    // goes through SecureStore — never plaintext in the prefs XML. ------
    private fun refreshSttRemotePanel() {
        val remote = prefs.getString(ModelCatalog.KEY_STT_BACKEND, "").orEmpty() == ModelCatalog.BACKEND_REMOTE
        findViewById<android.view.View>(R.id.remote_stt_panel).visibility =
            if (remote) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun bindSttRemote() {
        // Populate once BEFORE the watchers attach, so loading never writes back.
        loadSttRemoteFields()
        bindRemoteTextField(R.id.stt_remote_url, KEY_STT_REMOTE_URL) { it.trim() }
        bindRemoteTextField(R.id.stt_remote_model, KEY_STT_REMOTE_MODEL) { it.trim() }
        bindRemoteTextField(R.id.stt_remote_key, KEY_STT_REMOTE_KEY) { SecureStore.encrypt(it) ?: it }
        findViewById<LinearLayout>(R.id.row_test_stt_conn).setOnClickListener { testRemoteStt() }
        refreshSttRemotePanel()
    }

    private fun loadSttRemoteFields() {
        findViewById<EditText>(R.id.stt_remote_url).setText(prefs.getString(KEY_STT_REMOTE_URL, ""))
        findViewById<EditText>(R.id.stt_remote_model).setText(prefs.getString(KEY_STT_REMOTE_MODEL, DEFAULT_REMOTE_MODEL))
        findViewById<EditText>(R.id.stt_remote_key).setText(SecureStore.decrypt(prefs.getString(KEY_STT_REMOTE_KEY, null) ?: "").orEmpty())
    }

    /** Live-save a remote field on every user edit (same UX as the mic SeekBars);
     *  [toStored] maps the raw field text to its stored form. */
    private fun bindRemoteTextField(editId: Int, key: String, toStored: (String) -> String) {
        findViewById<EditText>(editId).addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                prefs.edit().putString(key, toStored(s?.toString().orEmpty())).apply()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    /** Persist the visible field state, then run the SAME init probe RemoteStt
     *  uses on pipeline start (async, non-throwing). Toast + dd-log the result. */
    private fun testRemoteStt() {
        val url = findViewById<EditText>(R.id.stt_remote_url).text.toString().trim()
        val model = findViewById<EditText>(R.id.stt_remote_model).text.toString().trim()
        val key = findViewById<EditText>(R.id.stt_remote_key).text.toString()
        prefs.edit()
            .putString(KEY_STT_REMOTE_URL, url)
            .putString(KEY_STT_REMOTE_MODEL, model)
            .putString(KEY_STT_REMOTE_KEY, SecureStore.encrypt(key) ?: key)
            .apply()
        if (sttBaseUrl(url).isBlank()) {
            VoxLog.dd("event=stt-remote-test ok=false reason=no-url")
            setRemoteTestResult(false)
            Toast.makeText(this, "Remote STT: enter a server URL first", Toast.LENGTH_LONG).show()
            return
        }
        setRemoteTestResult(null)
        RemoteStt(this).init { ok ->
            runOnUiThread {
                VoxLog.dd("event=stt-remote-test ok=$ok")
                setRemoteTestResult(ok)
                Toast.makeText(this,
                    if (ok) "Remote STT: connection OK"
                    else "Remote STT: unreachable — check URL / key",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setRemoteTestResult(state: Boolean?) {
        val tv = findViewById<TextView>(R.id.set_stt_test_val)
        tv.text = when (state) {
            true -> "ok"
            false -> "FAILED"
            null -> "…"
        }
        tv.setTextColor(when (state) {
            true -> 0xFF35D07F.toInt()
            false -> 0xFFFF5B5B.toInt()
            null -> androidx.core.content.ContextCompat.getColor(this, R.color.hv_cyan)
        })
    }

    private fun sttBackendLabel(tok: String): String = when (tok) {
        ModelCatalog.BACKEND_PLATFORM -> "Platform (Google)"
        ModelCatalog.BACKEND_REMOTE -> "Remote (server)"
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
            val c = com.hermesvox.VoiceController(this, com.hermesvox.mobile.HermesSession(
                prefs.getString("url", "").orEmpty(),
                SecureStore.decrypt(prefs.getString("key", "").orEmpty()).orEmpty(), ""))
            val msg = c.testConnectionHuman()
            findViewById<TextView>(R.id.set_test_val)?.let {
                val ok = msg.startsWith("Connected")
                it.text = if (ok) "ok" else "FAILED"
                it.setTextColor(if (ok) 0xFF35D07F.toInt() else 0xFFFF5B5B.toInt())
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(if (msg.startsWith("Connected")) "Connection OK" else "Connection issue")
                .setMessage(msg)
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
        bindMicToggle(R.id.set_tts_voice_usage, "tts_voice_usage", true)

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
                .putBoolean("tts_voice_usage", true)
            GROUP_STT -> e
                .putString(ModelCatalog.KEY_STT_BACKEND, ModelCatalog.BACKEND_ONDEVICE)
                .putString(ModelCatalog.KEY_STT_MODEL, ModelCatalog.DEFAULT_STT_MODEL)
                .putString(KEY_STT_REMOTE_URL, "")
                .putString(KEY_STT_REMOTE_MODEL, "")
                .putString(KEY_STT_REMOTE_KEY, "")
            GROUP_TTS -> e
                .putString("tts", "system")
                .putString("voice", "system")   // the TTS register now resets with the engine
            GROUP_VOICE -> e.putString("voice", "system")
            GROUP_MODE -> e.putString(ModelCatalog.KEY_VOICE_MODE, ModelCatalog.MODE_REALTIME)
            GROUP_ENTITY -> e
                .putString("model", "hermes-agent")
                .putString("provider", "")      // clear the per-request provider override
                .putString(ModelCatalog.KEY_VOICE_MODE, ModelCatalog.MODE_REALTIME)   // url/key untouched (identity)
            GROUP_APPEARANCE -> e
                .putString("theme", "system")
                .putString("layout_mode", "presence")
                .putString("particles_theme", "aura")
                .putBoolean("particles_cycle", true)
            GROUP_ABOUT -> e
                .putBoolean("dev_console", false)
                .putBoolean("log_transcripts", false)
                .putBoolean("debug_log", false)
            GROUP_MODELS -> e.putString(ModelCatalog.KEY_SOURCE, ModelCatalog.DEFAULT_SOURCE)   // source only; files untouched
        }
        e.apply()
        when (group) {
            GROUP_MIC -> bindMicSettings()
            GROUP_STT -> { loadSttRemoteFields(); refreshFlowVals() }
            GROUP_APPEARANCE -> bindParticles()
            GROUP_ENTITY -> { refreshEntityVal(); refreshFlowVals() }
            GROUP_ABOUT -> { refreshFlowVals(); VoxLog.setDebugFile(false) }
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
        const val GROUP_ENTITY = "entity"
        const val GROUP_APPEARANCE = "appearance"
        const val GROUP_ABOUT = "about"
        const val GROUP_MODELS = "models"

        const val SECTION_ENTITY = "entity"
        const val SECTION_SPEECH = "speech"
        const val SECTION_STT = "stt"
        const val SECTION_TTS = "tts"
        const val SECTION_MODELS = "models"
        const val SECTION_APPEARANCE = "appearance"
        const val SECTION_ABOUT = "about"
    }
}
