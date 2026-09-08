package com.hermesvox

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * ModelsActivity — the on-device model storefront. Lists the BLESSED model set
 * (the best-path defaults), each with a size, a "★ Recommended" badge, a
 * one-line plain-language purpose, a download-with-progress / cancel /
 * installed state, and a configurable source host. "Download recommended
 * (blessed)" fetches the whole default set.
 * Sovereign: the models are FILES from an open-source store (the house Thelio by
 * default); inference runs fully offline. No cloud APIs, no keys.
 *
 * #114-denominator: REQUIRED == RECOMMENDED == ModelCatalog.required, the SAME
 * set the Settings badge counts, so every number on every screen agrees. The
 * header status below ("N of M required installed") is derived from that one
 * set and flips to a BLOCKING warning while the offline voice is incomplete (#6).
 */
class ModelsActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("hv", Context.MODE_PRIVATE) }
    private lateinit var list: LinearLayout
    private lateinit var reqStatus: TextView
    private lateinit var downloadAll: Button
    private val downloader = ModelDownloader(this)
    private val cards = mutableMapOf<String, CardUi>()
    // The required set = the recommended set (single convention, see header).
    private val required: List<ModelSpec> get() = ModelCatalog.required

    private class CardUi(val action: Button, val state: TextView, val progress: ProgressBar, val size: TextView)

    override fun onCreate(savedInstanceState: Bundle?) {
        if (prefs.getString("theme", "system") == "dark") androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_models)
        list = findViewById(R.id.m_list)
        reqStatus = findViewById(R.id.m_req_status)
        downloadAll = findViewById(R.id.m_download_all)

        findViewById<android.view.View>(R.id.m_back).setOnClickListener { finish() }
        downloadAll.setOnClickListener {
            // Installs the REQUIRED (recommended) set only — same set the header
            // counts and the Settings badge counts (#114-denominator).
            ModelCatalog.required.filter { !ModelCatalog.isInstalled(this, it.id) }.forEach { start(it) }
        }

        // Make the REQUIRED set explicit — list the recommended models that
        // power the offline voice so the first-run flow is obvious. The count
        // (required.size) is the denominator everywhere on this screen.
        findViewById<TextView>(R.id.m_subtitle)?.text =
            "Required (${required.size} of ${ModelCatalog.blessed.size}) — these power your offline voice: " +
            required.joinToString(", ") { it.name } +
            ". The rest below are optional extras."

        updateHeaderStatus()
        // #120-D: define the jargon once, in plain language, right where the
        // downloads happen (matches the onboarding step + main-screen coach
        // marks copy, so a term always carries the same meaning).
        findViewById<TextView>(R.id.m_how)?.text =
            "The required set is your offline voice: hearing you (STT · Whisper), " +
            "knowing when you start and stop talking (VAD · Silero), and speaking " +
            "replies (TTS · Piper) — all on-device. Gemma below is optional: it " +
            "powers Enhanced Realtime's phone-call presence."
        buildCards()
    }

    /** #114 + #6: one blocking/satisfied header status derived from the SAME
     *  required set the Settings badge counts. Incomplete = WARNING treatment
     *  ("setup required", warning color + glyph), not neutral gray. */
    private fun updateHeaderStatus() {
        val installed = required.count { ModelCatalog.isInstalled(this, it.id) }
        val need = required.size
        val complete = installed == need
        reqStatus.text = if (complete)
            "✓ Voice ready — $need/$need required models installed"
        else
            "⚠ Setup required — $installed/$need required voice models installed"
        reqStatus.setTextColor(ContextCompat.getColor(this,
            if (complete) R.color.hv_ok else R.color.hv_warn))
        downloadAll.isEnabled = !complete
        downloadAll.text = if (complete) "All required voice models installed ✓"
            else "Install the ${need - installed} missing required voice model${if (need - installed == 1) "" else "s"}"
    }

    private fun buildCards() {
        list.removeAllViews()
        // Render the RECOMMENDED models first (the "needed now" ones on top),
        // keeping the same cards map keyed by spec.id so refreshCard/start stay correct.
        for (spec in ModelCatalog.blessed.sortedBy { if (it.recommended) 0 else 1 }) {
            val v = layoutInflater.inflate(R.layout.model_item, list, false)
            v.findViewById<TextView>(R.id.mi_name).text = spec.name
            v.findViewById<TextView>(R.id.mi_desc).text = spec.desc
            // #16: each card carries the model's plain-language purpose.
            v.findViewById<TextView>(R.id.mi_purpose).text =
                spec.purpose.ifBlank { spec.desc }
            v.findViewById<TextView>(R.id.mi_size).text = "${spec.kind.uppercase()} · ${
                "%.1f".format(spec.sizeMB)} MB"
            v.findViewById<TextView>(R.id.mi_badge).visibility =
                if (spec.recommended) android.view.View.VISIBLE else android.view.View.GONE
            val action = v.findViewById<Button>(R.id.mi_action)
            val state = v.findViewById<TextView>(R.id.mi_state)
            val progress = v.findViewById<ProgressBar>(R.id.mi_progress)
            cards[spec.id] = CardUi(action, state, progress, v.findViewById(R.id.mi_size))
            action.setOnClickListener { start(spec) }
            list.addView(v)
            refreshCard(spec)
        }
    }

    private fun start(spec: ModelSpec) {
        if (ModelCatalog.isInstalled(this, spec.id)) { finishInstall(spec.id); return }
        val c = cards[spec.id] ?: return
        c.action.text = "Cancel"
        c.action.setOnClickListener { downloader.cancel() }
        c.progress.visibility = android.view.View.VISIBLE
        c.progress.progress = 0
        c.state.text = "Downloading…"
        c.state.setTextColor(ContextCompat.getColor(this, R.color.hv_text_dim))
        downloader.download(spec, object : ModelDownloader.Listener {
            override fun onProgress(id: String, downloaded: Long, total: Long) {
                runOnUiThread {
                    // Use KB so a >2 GB download doesn't overflow Int (the Gemma
                    // zip is 2.2 GB; total.toInt()/downloaded.toInt() overflows).
                    c.progress.max = (total / 1024).toInt().coerceAtLeast(1)
                    c.progress.progress = (downloaded / 1024).toInt().coerceIn(0, c.progress.max)
                    // #16: progress reads in MB + %, not raw KB.
                    val pct = if (total > 0) downloaded * 100 / total else 0L
                    c.state.text = "Downloading… $pct% (" +
                        "%.1f".format(downloaded / 1048576.0) + " / " +
                        "%.1f".format(total / 1048576.0) + " MB)"
                }
            }
            override fun onDone(id: String) { runOnUiThread { finishInstall(id) } }
            override fun onError(id: String, msg: String) {
                runOnUiThread {
                    c.progress.visibility = android.view.View.GONE
                    c.state.text = "Error: $msg"
                    c.state.setTextColor(ContextCompat.getColor(this@ModelsActivity, R.color.hv_danger))
                    c.action.text = "Retry"
                    c.action.setOnClickListener { start(spec) }
                    Toast.makeText(this@ModelsActivity, "$id: $msg", Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    private fun finishInstall(id: String) {
        val c = cards[id] ?: return
        c.progress.visibility = android.view.View.GONE
        c.state.text = "✓ Installed"
        c.state.setTextColor(ContextCompat.getColor(this, R.color.hv_ok))
        c.action.text = "Installed"
        c.action.isEnabled = false
        updateHeaderStatus()   // the last required model flips the header to "Voice ready"
    }

    // Sets the card's UI from the installed state. Does NOT call finishInstall
    // (that was the StackOverflow recursion: finishInstall -> refreshCard ->
    // finishInstall -> ...).
    private fun refreshCard(spec: ModelSpec) {
        val c = cards[spec.id] ?: return
        if (ModelCatalog.isInstalled(this, spec.id)) {
            c.state.text = "✓ Installed"
            c.state.setTextColor(ContextCompat.getColor(this, R.color.hv_ok))
            c.action.text = "Installed"
            c.action.isEnabled = false
        } else {
            // #6: a missing REQUIRED model reads as blocking (warning color +
            // "required" copy); an optional model stays neutral informational.
            c.state.text = if (spec.recommended) "⚠ Required — not installed"
                else "Not installed"
            c.state.setTextColor(ContextCompat.getColor(this,
                if (spec.recommended) R.color.hv_warn else R.color.hv_text_dim))
            c.action.text = "Download"
            c.action.isEnabled = true
        }
    }
}
