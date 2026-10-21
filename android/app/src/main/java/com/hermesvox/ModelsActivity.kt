package com.hermesvox

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/**
 * ModelsActivity — the on-device model storefront. Lists the BLESSED model set
 * (the best-path defaults) grouped by the job each model does for the user
 * (#219): Hearing, Voice, Being, then any other kind in its own group.
 *
 * Every row states, in order: what it does (one plain sentence), its true decimal
 * MB download size, its state as words, and — where the licence is not
 * permissive — a licence badge whose tap opens the SAME acceptance dialog the
 * Download button uses. The state comes from [ModelDownloads.State] and
 * [ModelLicences] (the same sources the download path uses), mapped by the pure
 * [ModelRowState] so the wording is testable off-device.
 *
 * The model-source override lives in one collapsed Advanced disclosure at the
 * bottom: reachable, never the first thing seen. No destructive per-model action
 * exists in this build, so none is invented here.
 *
 * #114-denominator: REQUIRED == RECOMMENDED == ModelCatalog.required, the SAME
 * set the Settings badge counts, so every number on every screen agrees.
 */
class ModelsActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("hv", Context.MODE_PRIVATE) }
    private lateinit var list: LinearLayout
    private lateinit var reqStatus: TextView
    private lateinit var downloadAll: Button
    private lateinit var sourceField: EditText
    private lateinit var sourceStatus: TextView
    /** The screen only OBSERVES downloads (ModelDownloads owns them, in a foreground service),
     *  so leaving mid-download no longer kills it and coming back shows where it is. */
    private val observer = ModelDownloads.Observer { id, st ->
        ModelCatalog.blessed.firstOrNull { it.id == id }?.let { render(it, st) }
        if (st is ModelDownloads.State.Installed) updateHeaderStatus()
    }
    private val cards = mutableMapOf<String, CardUi>()
    // The required set = the recommended set (single convention, see header).
    private val required: List<ModelSpec> get() = ModelCatalog.required

    private class CardUi(
        val action: Button,
        val state: TextView,
        val progress: ProgressBar,
        val size: TextView,
        val licence: TextView,
        val recommended: TextView
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        if (prefs.getString("theme", "system") == "dark") androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_models)
        list = findViewById(R.id.m_list)
        reqStatus = findViewById(R.id.m_req_status)
        downloadAll = findViewById(R.id.m_download_all)

        findViewById<View>(R.id.m_back).setOnClickListener { finish() }
        downloadAll.setOnClickListener {
            // Installs the REQUIRED (recommended) set only — same set the header
            // counts and the Settings badge counts (#114-denominator).
            ModelCatalog.required.filter { !ModelCatalog.isInstalled(this, it.id) }
                .forEach { ModelDownloads.enqueue(this, it) }
        }

        findViewById<TextView>(R.id.m_subtitle)?.text =
            "Grouped by what each model does for you. ★ marks the recommended set — " +
                "install those first and you can make your first call. Everything else is optional."

        updateHeaderStatus()
        // #120-D: define the jargon once, in plain language, right where the
        // downloads happen (matches the onboarding step + main-screen coach
        // marks copy, so a term always carries the same meaning).
        findViewById<TextView>(R.id.m_how)?.text =
            "Hearing turns your speech into text and notices when you start or stop " +
            "talking. Voice speaks the replies. Being runs the optional on-device " +
            "presence layer. Downloads keep going if you leave this screen, and a " +
            "stopped download resumes where it left off."
        bindAdvanced()
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

    /** #219: shelve by job (Hearing, Voice, Being, other) instead of one flat list. */
    private fun buildCards() {
        list.removeAllViews()
        cards.clear()
        for (group in ModelShelf.groups(ModelCatalog.blessed)) {
            val header = layoutInflater.inflate(R.layout.model_group_header, list, false)
            header.findViewById<TextView>(R.id.mg_title).text = group.title
            header.findViewById<TextView>(R.id.mg_blurb).text = group.blurb
            list.addView(header)
            for (spec in group.specs) {
                val v = layoutInflater.inflate(R.layout.model_item, list, false)
                v.findViewById<TextView>(R.id.mi_name).text = spec.name
                // The catalogue's plain-language purpose, never its internal `desc`.
                v.findViewById<TextView>(R.id.mi_purpose).text =
                    spec.purpose.ifBlank { spec.desc }
                v.findViewById<TextView>(R.id.mi_size).text =
                    ModelRowState.sizeLabel(spec.sizeMB)
                cards[spec.id] = CardUi(
                    action = v.findViewById(R.id.mi_action),
                    state = v.findViewById(R.id.mi_state),
                    progress = v.findViewById(R.id.mi_progress),
                    size = v.findViewById(R.id.mi_size),
                    licence = v.findViewById(R.id.mi_licence),
                    recommended = v.findViewById(R.id.mi_recommended)
                )
                list.addView(v)
                render(spec, ModelDownloads.state(this, spec))
            }
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

    /** Start (or resume) [spec]. A licensed model first shows the licence dialog;
     *  the download only starts once the user accepts (ModelDownloader refuses
     *  without that acceptance). */
    private fun download(spec: ModelSpec) {
        if (spec.licence == null || ModelLicences.isAccepted(this, spec.id)) {
            ModelDownloads.enqueue(this, spec)
            return
        }
        showLicence(spec)
    }

    /**
     * The one licence acceptance flow: the Download button and the licence badge
     * both land here, so there is exactly one place that can accept a licence.
     */
    private fun showLicence(spec: ModelSpec) {
        val licence = spec.licence ?: return
        AlertDialog.Builder(this)
            .setTitle("${spec.name} — licence")
            .setMessage("This model is not open source. It is offered under the ${licence.name}, " +
                "which its licensor can revoke. It is downloaded straight from the upstream release " +
                "to this phone; Hermes Vox does not ship it.\n\n${licence.attribution}.\n\n" +
                "Read the model card (it links the full licence) before accepting:\n${licence.modelCardUrl}")
            .setPositiveButton("Accept & download") { _, _ ->
                ModelLicences.accept(this, spec)
                ModelDownloads.enqueue(this, spec)
                render(spec, ModelDownloads.state(this, spec))
            }
            .setNeutralButton("Model card") { _, _ ->
                try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(licence.modelCardUrl))) }
                catch (_: Throwable) { Toast.makeText(this, licence.modelCardUrl, Toast.LENGTH_LONG).show() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** The Advanced disclosure: collapsed by default, at the bottom of the list. */
    private fun bindAdvanced() {
        val row = findViewById<View>(R.id.m_advanced)
        val panel = findViewById<View>(R.id.m_adv_panel)
        val label = findViewById<TextView>(R.id.m_adv_state)
        row.contentDescription = getString(R.string.hv_models_advanced_show)
        row.setOnClickListener {
            val open = panel.visibility != View.VISIBLE
            panel.visibility = if (open) View.VISIBLE else View.GONE
            label.text = if (open) "hide ›" else "show ›"
            row.contentDescription = getString(
                if (open) R.string.hv_models_advanced_hide else R.string.hv_models_advanced_show)
        }

        sourceField = findViewById(R.id.m_source)
        sourceStatus = findViewById(R.id.m_source_status)
        loadSource()
        findViewById<Button>(R.id.m_source_save).setOnClickListener {
            val raw = sourceField.text?.toString().orEmpty()
            val resolved = ModelCatalog.resolveSource(raw)
            prefs.edit().putString(ModelCatalog.KEY_SOURCE, resolved).apply()
            sourceField.setText(resolved)
            loadSource()
            if (raw.isNotBlank() && raw.trim() != resolved) {
                Toast.makeText(this,
                    "Not a http:// or https:// address — using the default source.",
                    Toast.LENGTH_LONG).show()
            }
        }
        findViewById<Button>(R.id.m_source_reset).setOnClickListener {
            prefs.edit().putString(ModelCatalog.KEY_SOURCE, ModelCatalog.DEFAULT_SOURCE).apply()
            loadSource()
            Toast.makeText(this, "Using the default source.", Toast.LENGTH_SHORT).show()
        }
    }

    /** Shows the address downloads actually use ([ModelCatalog.source] applies the
     *  same fallback rule the downloader does), not necessarily the raw pref. */
    private fun loadSource() {
        val effective = ModelCatalog.source(this)
        sourceField.setText(effective)
        sourceStatus.text =
            if (effective == ModelCatalog.DEFAULT_SOURCE) "Using the default source."
            else "Using a custom source for new downloads."
    }

    /** One card, drawn from the pure [ModelRowState] mapping of the download state. */
    private fun render(spec: ModelSpec, st: ModelDownloads.State) {
        val c = cards[spec.id] ?: return
        val accepted = ModelLicences.isAccepted(this, spec.id)
        val row = ModelRowState.of(st, spec.licence, accepted)

        c.state.text = row.state
        c.state.setTextColor(toneColor(row.tone))

        c.progress.visibility =
            if (row.progress != null || row.indeterminate) View.VISIBLE else View.GONE
        c.progress.isIndeterminate = row.indeterminate
        if (row.progress != null) {
            c.progress.max = 100
            c.progress.progress = (row.progress * 100f).roundToInt().coerceIn(0, 100)
        } else if (!row.indeterminate) {
            c.progress.progress = 0
        }

        c.action.text = row.actionLabel
        c.action.contentDescription = "${row.actionLabel} ${spec.name}"
        c.action.isEnabled = row.action != ModelRowState.Action.NONE
        val onClick: View.OnClickListener? = when (row.action) {
            ModelRowState.Action.DOWNLOAD,
            ModelRowState.Action.REVIEW_LICENCE,
            ModelRowState.Action.RESUME -> View.OnClickListener { download(spec) }
            ModelRowState.Action.PAUSE,
            ModelRowState.Action.CANCEL -> View.OnClickListener { ModelDownloads.cancel(this, spec.id) }
            ModelRowState.Action.NONE -> null
        }
        c.action.setOnClickListener(onClick)

        val badge = ModelRowState.licenceBadge(spec.licence, accepted)
        c.licence.visibility = if (badge == null) View.GONE else View.VISIBLE
        if (badge != null) {
            c.licence.text = badge
            c.licence.contentDescription = "$badge — ${spec.name}"
            c.licence.setOnClickListener { showLicence(spec) }
        }

        c.recommended.visibility = if (spec.recommended) View.VISIBLE else View.GONE
        c.size.text = ModelRowState.sizeLabel(spec.sizeMB)
    }

    private fun toneColor(tone: ModelRowState.Tone): Int = ContextCompat.getColor(this, when (tone) {
        ModelRowState.Tone.OK -> R.color.hv_ok
        ModelRowState.Tone.WARN -> R.color.hv_warn
        ModelRowState.Tone.DANGER -> R.color.hv_danger
        ModelRowState.Tone.NEUTRAL -> R.color.hv_text_dim
    })
}
