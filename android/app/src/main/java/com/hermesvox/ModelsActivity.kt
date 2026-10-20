package com.hermesvox

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * ModelsActivity — the on-device model storefront. Lists the BLESSED model set
 * (the best-path defaults), each with a size, a "★ Recommended" badge, a
 * one-line plain-language purpose, a download-with-progress / cancel /
 * installed state, and a configurable source host. "Download recommended
 * (blessed)" fetches the whole default set.
 * Sovereign: the models are FILES from an open-source store (the upstream release by
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
    /** The screen only OBSERVES downloads (ModelDownloads owns them, in a foreground service),
     *  so leaving mid-download no longer kills it and coming back shows where it is. */
    private val observer = ModelDownloads.Observer { id, st ->
        ModelCatalog.blessed.firstOrNull { it.id == id }?.let { render(it, st) }
        if (st is ModelDownloads.State.Installed) updateHeaderStatus()
    }
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
            ModelCatalog.required.filter { !ModelCatalog.isInstalled(this, it.id) }
                .forEach { ModelDownloads.enqueue(this, it) }
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
            "replies (TTS · Supertonic) — all on-device. Gemma below is optional: it " +
            "powers Enhanced Realtime (alpha) phone-call presence. Downloads keep going " +
            "if you leave this screen, and a stopped download resumes where it left off."
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
            list.addView(v)
            render(spec, ModelDownloads.state(this, spec))
        }
    }

    override fun onStart() {
        super.onStart()
        ModelDownloads.observe(observer)
        // Re-sync on return: a download that progressed (or finished) while we were away.
        for (spec in ModelCatalog.blessed) render(spec, ModelDownloads.state(this, spec))
        updateHeaderStatus()
    }

    override fun onStop() {
        ModelDownloads.unobserve(observer)
        super.onStop()
    }

    /** Start (or resume) [spec]. A model under a non-OSS licence first shows the licence —
     *  its name, the licensor's attribution, a link to the model card — and starts only once
     *  the user accepts; the acceptance is kept per model (ModelLicences), and ModelDownloader
     *  refuses to fetch without it. */
    private fun download(spec: ModelSpec) {
        val licence = spec.licence
        if (licence == null || ModelLicences.isAccepted(this, spec.id)) { ModelDownloads.enqueue(this, spec); return }
        AlertDialog.Builder(this)
            .setTitle("${spec.name} — licence")
            .setMessage("This model is not open source. It is offered under the ${licence.name}, " +
                "which its licensor can revoke. It is downloaded straight from the upstream release " +
                "to this phone; Hermes Vox does not ship it.\n\n${licence.attribution}.\n\n" +
                "Read the model card (it links the full licence) before accepting:\n${licence.modelCardUrl}")
            .setPositiveButton("Accept & download") { _, _ ->
                ModelLicences.accept(this, spec)
                ModelDownloads.enqueue(this, spec)
            }
            .setNeutralButton("Model card") { _, _ ->
                try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(licence.modelCardUrl))) }
                catch (_: Throwable) { android.widget.Toast.makeText(this, licence.modelCardUrl, android.widget.Toast.LENGTH_LONG).show() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** One card, drawn from the download state. Every state says what tapping will do. */
    private fun render(spec: ModelSpec, st: ModelDownloads.State) {
        val c = cards[spec.id] ?: return
        val dim = ContextCompat.getColor(this, R.color.hv_text_dim)
        c.action.isEnabled = true
        when (st) {
            ModelDownloads.State.Installed -> {
                c.progress.visibility = android.view.View.GONE
                c.state.text = "✓ Installed"
                c.state.setTextColor(ContextCompat.getColor(this, R.color.hv_ok))
                c.action.text = "Installed"
                c.action.isEnabled = false
            }
            ModelDownloads.State.Queued -> {
                c.progress.visibility = android.view.View.VISIBLE
                c.progress.isIndeterminate = true
                c.state.text = "Waiting — downloads run one at a time"
                c.state.setTextColor(dim)
                c.action.text = "Cancel"
                c.action.setOnClickListener { ModelDownloads.cancel(this, spec.id) }
            }
            is ModelDownloads.State.Running -> {
                c.progress.visibility = android.view.View.VISIBLE
                c.state.setTextColor(dim)
                c.action.text = "Pause"
                c.action.setOnClickListener { ModelDownloads.cancel(this, spec.id) }
                when (st.phase) {
                    ModelDownloader.Phase.DOWNLOAD -> {
                        c.progress.isIndeterminate = st.total <= 0
                        // KB so a >2 GB download doesn't overflow Int (Gemma is 2.6 GB).
                        c.progress.max = (st.total / 1024).toInt().coerceAtLeast(1)
                        c.progress.progress = (st.done / 1024).toInt().coerceIn(0, c.progress.max)
                        c.state.text = ModelProgress.fetching(st.done, st.total)
                    }
                    ModelDownloader.Phase.WAITING_NETWORK -> {
                        c.progress.isIndeterminate = true
                        c.state.text = ModelProgress.waiting(st.done)
                    }
                    // Verifying and unpacking are short and not pausable (the bytes are all
                    // here): the button says what is happening instead of offering a no-op.
                    ModelDownloader.Phase.VERIFY -> {
                        c.progress.isIndeterminate = true; c.state.text = ModelProgress.verifying()
                        c.action.text = "Verifying…"; c.action.isEnabled = false
                    }
                    ModelDownloader.Phase.UNPACK -> {
                        c.progress.isIndeterminate = true; c.state.text = ModelProgress.unpacking()
                        c.action.text = "Unpacking…"; c.action.isEnabled = false
                    }
                }
            }
            is ModelDownloads.State.Paused -> {
                c.progress.visibility = android.view.View.VISIBLE
                c.progress.isIndeterminate = false
                c.progress.max = 100
                c.progress.progress = if (st.total > 0) (st.done * 100 / st.total).coerceIn(0, 99).toInt() else 0
                c.state.text = ModelProgress.paused(st.done, st.reason)
                c.state.setTextColor(ContextCompat.getColor(this, if (st.reason != null) R.color.hv_danger else R.color.hv_warn))
                c.action.text = "Resume"
                c.action.setOnClickListener { download(spec) }
            }
            ModelDownloads.State.Idle -> {
                c.progress.visibility = android.view.View.GONE
                // #6: a missing REQUIRED model reads as blocking (warning color + "required"
                // copy); an optional model stays neutral informational.
                c.state.text = if (spec.recommended) "⚠ Required — not installed" else "Not installed"
                c.state.setTextColor(ContextCompat.getColor(this, if (spec.recommended) R.color.hv_warn else R.color.hv_text_dim))
                c.action.text = "Download"
                c.action.setOnClickListener { download(spec) }
            }
        }
    }
}
