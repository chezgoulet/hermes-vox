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
        bindVisuals()
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
            SECTION_VISUALS to R.id.sec_visuals, SECTION_ABOUT to R.id.sec_about)
        ids.values.forEach { findViewById<android.view.View>(it).visibility = android.view.View.GONE }
        findViewById<android.view.View>(ids[section]!!).visibility = android.view.View.VISIBLE
        findViewById<android.widget.ScrollView>(R.id.settings_scroll).scrollTo(0, 0)
        findViewById<TextView>(R.id.set_title).text = when (section) {
            SECTION_ENTITY -> "Entity & Connection"; SECTION_SPEECH -> "Speech & Mic"
            SECTION_STT -> "STT & Transcription"; SECTION_TTS -> "TTS & Voice"
            SECTION_MODELS -> "Voice models"; SECTION_APPEARANCE -> "Appearance & Presence"
            SECTION_VISUALS -> "Visuals"
            else -> "About & Diagnostics"
        }
    }

    /** WS1: return to the Settings home (group list). */
    private fun showGroupList() {
        currentSection = null
        findViewById<android.view.View>(R.id.grp_list).visibility = android.view.View.VISIBLE
        val ids = listOf(R.id.sec_entity, R.id.sec_speech, R.id.sec_stt, R.id.sec_tts,
            R.id.sec_models, R.id.sec_appearance, R.id.sec_visuals, R.id.sec_about)
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
        findViewById<android.view.View>(R.id.row_grp_visuals)?.setOnClickListener { showSection(SECTION_VISUALS) }
        findViewById<android.view.View>(R.id.row_grp_about)?.setOnClickListener { showSection(SECTION_ABOUT) }
    }

    private fun bindSectionRestoreRows() {
        bindRestoreRow(R.id.row_restore_entity, GROUP_ENTITY, "Entity & Connection")
        bindRestoreRow(R.id.row_restore_models, GROUP_MODELS, "Models")
        bindRestoreRow(R.id.row_restore_appearance, GROUP_APPEARANCE, "Appearance")
        bindRestoreRow(R.id.row_restore_visuals, GROUP_VISUALS, "Visuals")
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
                arrayOf("Realtime", "Enhanced Realtime"),
                arrayOf(ModelCatalog.MODE_REALTIME, ModelCatalog.MODE_ENHANCED),
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
        else -> "Realtime"   // a stale stored token reads as Realtime (never crashes)
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
        bindKeepScreenOn()
        findViewById<android.view.View>(R.id.row_test_conn)?.setOnClickListener {
            val c = com.hermesvox.VoiceController(this, com.hermesvox.mobile.HermesSession(
                prefs.getString("url", "").orEmpty(),
                SecureStore.decrypt(prefs.getString("key", "").orEmpty()).orEmpty(), ""))
            // 0.5.1: the test runs OFF the UI thread. Run on it — as this handler used
            // to — and Android refuses the socket outright (NetworkOnMainThreadException,
            // no message), which is exactly the field log's `ping=false(unknown)
            // stream=false(unknown)`: a verdict for a test that never reached the network.
            findViewById<TextView>(R.id.set_test_val)?.let {
                it.text = "testing…"
                it.setTextColor(0xFFD6F4FF.toInt())
            }
            c.testConnectionAsync(true) { probe, msg ->
                val ok = probe == ConnectionPhase.Probe.OK
                findViewById<TextView>(R.id.set_test_val)?.let {
                    it.text = when (probe) {
                        ConnectionPhase.Probe.OK -> "ok"
                        ConnectionPhase.Probe.COLD -> "warming"
                        ConnectionPhase.Probe.AUTH -> "key"
                        else -> "FAILED"
                    }
                    it.setTextColor(when (probe) {
                        ConnectionPhase.Probe.OK -> 0xFF35D07F.toInt()
                        ConnectionPhase.Probe.COLD -> 0xFFFFB43D.toInt()   // reachable, just not ready
                        else -> 0xFFFF5B5B.toInt()
                    })
                }
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(when {
                        ok -> "Connection OK"
                        probe == ConnectionPhase.Probe.COLD -> "Gateway is warming up"
                        else -> "Connection issue"
                    })
                    .setMessage(msg)
                    .setPositiveButton("OK", null).show()
            }
        }
        findViewById<LinearLayout>(R.id.row_debug).setOnClickListener {
            val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Throwable) { "?" }
            val crash = CrashLog.read(this)
            // K2 (C4): the runtime log is capped + rotated (single generation), so the
            // WHOLE session since app start now spans TWO files: hermes-vox.log.1 (the
            // prior full generation, older) + hermes-vox.log (current). mergedRuntimeLog
            // = .1 then current — the export/copy ships BOTH (the merged pair) so field
            // logs stay complete across the rotation seam. Cap only the inline dialog view.
            val full = mergedRuntimeLog()
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
                    // Share the MERGED pair (old .1 generation + current) as one file so a
                    // recipient sees the whole session, not just the post-rotation current.
                    val f = mergedLogFile()
                    val uri = androidx.core.content.FileProvider.getUriForFile(this, packageName + ".fileprovider", f)
                    val i = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(android.content.Intent.EXTRA_STREAM, uri); addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(android.content.Intent.createChooser(i, "Send full Hermes Vox log"))
                }
                .setNegativeButton("Clear logs") { _, _ ->
                    CrashLog.clear(this)
                    // K2: clear the whole rotated set — current, prior .1 generation, and any merge snapshot.
                    try { java.io.File(filesDir, "logs/hermes-vox.log").delete() } catch (_: Throwable) {}
                    try { java.io.File(filesDir, "logs/hermes-vox.log.1").delete() } catch (_: Throwable) {}
                    try { java.io.File(filesDir, "logs/hermes-vox-merged.log").delete() } catch (_: Throwable) {}
                    findViewById<TextView>(R.id.set_debug_val).text = "view"
                }
                .show()
        }
    }

    /** K2 (0.5.0.3) screen-alive toggle: keep_screen_on (default OFF). The flag it
     *  controls is a plain WINDOW flag (FLAG_KEEP_SCREEN_ON) MainActivity arms at
     *  call start and clears at the call teardown — no permission, no WAKE_LOCK.
     *  Bound here + re-bound by restore-defaults (GROUP_APPEARANCE). */
    private fun bindKeepScreenOn() {
        findViewById<SwitchCompat>(R.id.set_keep_screen_on).apply {
            isChecked = prefs.getBoolean("keep_screen_on", false)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("keep_screen_on", on).apply() }
        }
    }

    /** K2: the full runtime session as text — the rotated prior generation
     *  (hermes-vox.log.1, older) concatenated with the current hermes-vox.log. */
    private fun mergedRuntimeLog(): String {
        val logs = java.io.File(filesDir, "logs")
        return try {
            val cur = java.io.File(logs, "hermes-vox.log")
            val gen = java.io.File(logs, "hermes-vox.log.1")
            val older = if (gen.exists()) gen.readText() else ""
            val current = if (cur.exists()) cur.readText() else ""
            if (older.isEmpty() && current.isEmpty()) "no runtime log" else older + current
        } catch (_: Throwable) { "no runtime log" }
    }

    /** K2: a single file holding the merged pair (.1 then current), written under the
     *  logs dir so the existing FileProvider path serves it. Falls back to the current
     *  file alone if the merge itself fails. */
    private fun mergedLogFile(): java.io.File {
        val logs = java.io.File(filesDir, "logs")
        val cur = java.io.File(logs, "hermes-vox.log")
        val merged = java.io.File(logs, "hermes-vox-merged.log")
        return try {
            val gen = java.io.File(logs, "hermes-vox.log.1")
            val older = if (gen.exists()) gen.readText() else ""
            val current = if (cur.exists()) cur.readText() else ""
            merged.writeText(older + current)
            merged
        } catch (_: Throwable) { cur }
    }

    // ---- Mic / Speech tuning (exact-range SeekBars reading + writing the SAME
    // prefs the pipeline reads; defaults = shipped values; a restore-defaults
    // button resets all eight) -------------------------------------------
    private fun bindMicSettings() {
        bindMicToggle(R.id.set_mic_aec, "mic_aec", true)
        bindMicToggle(R.id.set_ns_extra, "ns_extra", true)
        bindMicToggle(R.id.set_partial_stt, "partial_stt", true)
        bindMicToggle(R.id.set_tts_voice_usage, "tts_voice_usage", false)   // D2: engine default OFF (field A/B)

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
        bindFloatSeekBar(R.id.set_seek_barge_rms, R.id.set_mic_barge_rms_val, "barge_rms_min",
            0.04f, 0.30f, 0.01f, 0.10f) { "%.2f".format(it) }
        bindIntSeekBar(R.id.set_seek_barge_grace, R.id.set_mic_barge_grace_val, "barge_grace_ms",
            0, 2000, 50, 500) { "${it} ms" }
        bindIntSeekBar(R.id.set_seek_barge_level, R.id.set_mic_barge_level_val, "barge_level_only_ms",
            0, 800, 50, BargeGate.DEFAULT_LEVEL_ONLY_MS.toInt()) { "${it} ms" }

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

    // ---- Visuals: the being's CATEGORY, its richness, and its idle shape ---------
    // The category (VisualStyle) is the 0.5.1 answer to "expose categories of visuals":
    // it is not a seventh shape label — it re-colours, re-lights and re-paces the being
    // in every state, and the two sliders let the user push whichever family they chose
    // calmer or wilder. 0.5.2 (A2) expands the table to 20 families and the picker lists
    // EVERY one because it is driven straight off VisualStyle.TOKENS/LABELS — no second,
    // truncated list to drift. A3 adds "cycle all categories" so the whole breadth can be
    // seen animating. The pre-existing shape/theme + cycle controls are preserved verbatim
    // into this group (same prefs, same wording).
    private fun bindVisuals() {
        val catVals = VisualStyle.TOKENS
        val catLabels = VisualStyle.LABELS
        val cat = prefs.getString(VisualStyle.KEY_CATEGORY, VisualStyle.DEFAULT) ?: VisualStyle.DEFAULT
        setStringVal(R.id.set_visual_category_val, catLabels, catVals, cat)
        findViewById<LinearLayout>(R.id.row_visual_category).setOnClickListener {
            micChoiceString("Visual category", catLabels, catVals,
                VisualStyle.KEY_CATEGORY, R.id.set_visual_category_val)
        }
        // A3 (0.5.2): cycle through EVERY category. Off = the fixed pick above renders; on =
        // the being inhabits each family in turn, crossfaded over ~6s apiece, so the whole
        // breadth animates. Written to the same "hv" prefs AvatarView reads inside
        // setVisualCategory, so it takes effect on the next resume with no new wiring. The
        // picker above still matters while cycling: it is the family the rotation starts from
        // and returns to when this is switched off.
        findViewById<SwitchCompat>(R.id.set_visual_cycle_all).apply {
            isChecked = prefs.getBoolean(VisualStyle.KEY_CYCLE_ALL, VisualStyle.DEFAULT_CYCLE_ALL)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(VisualStyle.KEY_CYCLE_ALL, on).apply()
            }
        }
        bindFloatSeekBar(R.id.set_seek_visual_energy, R.id.set_visual_energy_val, VisualStyle.KEY_ENERGY,
            VisualStyle.ENERGY_MIN, VisualStyle.ENERGY_MAX, VisualStyle.ENERGY_STEP,
            VisualStyle.DEFAULT_ENERGY) { "%.1f×".format(it) }
        bindFloatSeekBar(R.id.set_seek_visual_glow, R.id.set_visual_glow_val, VisualStyle.KEY_GLOW,
            VisualStyle.GLOW_MIN, VisualStyle.GLOW_MAX, VisualStyle.GLOW_STEP,
            VisualStyle.DEFAULT_GLOW) { "%.1f×".format(it) }

        // video-statewire: WHICH SHAPE FIRES IN EACH ACTIVE STATE. Three pickers over the
        // SAME SHAPE vocabulary as the presence-shape picker below — so the user can put
        // jellyfish (A_VOICE), breath, sweep, octopus, etc. on any state — defaulting to
        // the Wave 1 semantic fits (soundwave / eye / radar) that match resolveArch's
        // stateArch table. Stored as tokens; defaults agree even when the pref is unset.
        bindStateShape(R.id.row_speak_shape, R.id.set_speak_shape_val, "Speaking shape",
            AvatarView.KEY_SHAPE_SPEAKING, AvatarView.DEFAULT_SHAPE_SPEAKING)
        bindStateShape(R.id.row_listen_shape, R.id.set_listen_shape_val, "Listening shape",
            AvatarView.KEY_SHAPE_LISTENING, AvatarView.DEFAULT_SHAPE_LISTENING)
        bindStateShape(R.id.row_think_shape, R.id.set_think_shape_val, "Thinking shape",
            AvatarView.KEY_SHAPE_THINKING, AvatarView.DEFAULT_SHAPE_THINKING)

        val themeLabels = AvatarView.SHAPE_LABELS
        val themeVals = AvatarView.SHAPE_TOKENS
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
    /** video-statewire: one per-state shape row (Speaking / Listening / Thinking shape).
     *  Renders the human label of the stored token, defaulting to [def] so an UNSET pref
     *  shows (and the picker highlights) the same default AvatarView's stateArch falls
     *  back to — the Settings display and the renderer can never disagree. */
    private fun bindStateShape(rowId: Int, valId: Int, title: String, key: String, def: String) {
        setStringVal(valId, AvatarView.SHAPE_LABELS, AvatarView.SHAPE_TOKENS,
            prefs.getString(key, def) ?: def)
        findViewById<LinearLayout>(rowId).setOnClickListener {
            micChoiceString(title, AvatarView.SHAPE_LABELS, AvatarView.SHAPE_TOKENS, key, valId, def)
        }
    }
    private fun micChoiceString(title: String, labels: Array<String>, vals: Array<String>, key: String, valId: Int,
                                def: String = vals.getOrElse(0) { "aura" }) {
        val cur = prefs.getString(key, def) ?: def
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
                .putBoolean("tts_voice_usage", false)   // D2: echo-routing default OFF (field A/B 2026-09-06)
                .putFloat("barge_rms_min", 0.10f)
                .putInt("barge_grace_ms", 500)
                .putInt("barge_level_only_ms", BargeGate.DEFAULT_LEVEL_ONLY_MS.toInt())
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
                .putBoolean("keep_screen_on", false)   // K2 (0.5.0.3): screen-alive default OFF
            GROUP_VISUALS -> e
                .putString(VisualStyle.KEY_CATEGORY, VisualStyle.DEFAULT)   // the light/cheap family
                .putBoolean(VisualStyle.KEY_CYCLE_ALL, VisualStyle.DEFAULT_CYCLE_ALL)   // A3: fixed, not cycling
                .putFloat(VisualStyle.KEY_ENERGY, VisualStyle.DEFAULT_ENERGY)
                .putFloat(VisualStyle.KEY_GLOW, VisualStyle.DEFAULT_GLOW)
                .putString("particles_theme", "aura")
                .putBoolean("particles_cycle", true)
                // video-statewire: remove the state-shape picks so they fall back to the
                // shipped defaults (soundwave/eye/radar) — unset == default, both agree.
                .remove(AvatarView.KEY_SHAPE_SPEAKING)
                .remove(AvatarView.KEY_SHAPE_LISTENING)
                .remove(AvatarView.KEY_SHAPE_THINKING)
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
            GROUP_APPEARANCE -> bindKeepScreenOn()
            GROUP_VISUALS -> bindVisuals()
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
        const val GROUP_VISUALS = "visuals"
        const val GROUP_ABOUT = "about"
        const val GROUP_MODELS = "models"

        const val SECTION_ENTITY = "entity"
        const val SECTION_SPEECH = "speech"
        const val SECTION_STT = "stt"
        const val SECTION_TTS = "tts"
        const val SECTION_MODELS = "models"
        const val SECTION_APPEARANCE = "appearance"
        const val SECTION_VISUALS = "visuals"
        const val SECTION_ABOUT = "about"
    }
}
