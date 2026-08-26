package com.hermesvox

import android.content.Context
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import com.hermesvox.mobile.HermesSession

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
        findViewById<TextView>(R.id.set_about_val).text = "0.2.0"
    }

    private fun bindEntity() {
        findViewById<LinearLayout>(R.id.row_reset).setOnClickListener {
            try {
                val u = prefs.getString("url", "").orEmpty()
                val k = prefs.getString("key", "").orEmpty()
                val m = prefs.getString("model", "hermes-agent").orEmpty()
                if (u.isNotBlank() && k.isNotBlank()) HermesSession(u, k, m).resetConversation()
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
            ekey.setText(prefs.getString("key", ""))
            AlertDialog.Builder(this)
                .setTitle("Entity")
                .setView(view)
                .setPositiveButton("Save") { _, _ ->
                    prefs.edit().putString("url", eurl.text.toString().trim())
                        .putString("model", emodel.text.toString().trim().ifEmpty { "hermes-agent" })
                        .putString("key", ekey.text.toString().trim()).apply()
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
        findViewById<LinearLayout>(R.id.row_voice).setOnClickListener {
            pick("Voice", arrayOf("System", "Warm", "Bright", "Deep"),
                arrayOf("system", "warm", "bright", "deep"), "voice", R.id.set_voice_val)
        }
        val barge = findViewById<SwitchCompat>(R.id.set_bargein)
        barge.isChecked = prefs.getBoolean("duplex", true)
        barge.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("duplex", on).apply() }
        val speak = findViewById<SwitchCompat>(R.id.set_speak)
        speak.isChecked = prefs.getBoolean("speak_responses", true)
        speak.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("speak_responses", on).apply() }
        val devc = findViewById<SwitchCompat>(R.id.set_devconsole)
        devc.isChecked = prefs.getBoolean("dev_console", false)
        devc.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("dev_console", on).apply() }
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
        findViewById<LinearLayout>(R.id.row_debug).setOnClickListener {
            val crash = CrashLog.read(this)
            val runtime = try {
                val f = java.io.File(filesDir, "logs/hermes-vox.log")
                if (f.exists()) f.readText().takeLast(9000) else "no runtime log"
            } catch (_: Throwable) { "no runtime log" }
            val log = "=== CRASH LOG ===\n$crash\n\n=== RUNTIME LOG (tail) ===\n$runtime"
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Debug / logs")
                .setMessage(log)
                .setPositiveButton("Copy all") { _, _ ->
                    val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("vox-debug", log))
                    android.widget.Toast.makeText(this, "copied", android.widget.Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("Clear crash log") { _, _ -> CrashLog.clear(this); findViewById<TextView>(R.id.set_debug_val).text = "view" }
                .setNegativeButton("Close", null)
                .show()
        }
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

    private fun applyTheme(mode: String) {
        when (mode) {
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }
}
