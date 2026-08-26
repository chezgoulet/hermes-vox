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

/**
 * ModelsActivity — the on-device model storefront. Lists the BLESSED model set
 * (the best-path defaults), each with a size, a "★ Recommended" badge, a
 * download-with-progress / cancel / installed state, and a configurable source
 * host. "Download recommended (blessed)" fetches the whole default set.
 * Sovereign: the models are FILES from an open-source store (the house Thelio by
 * default); inference runs fully offline. No cloud APIs, no keys.
 */
class ModelsActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("hv", Context.MODE_PRIVATE) }
    private lateinit var list: LinearLayout
    private lateinit var source: EditText
    private val downloader = ModelDownloader(this)
    private val cards = mutableMapOf<String, CardUi>()

    private class CardUi(val action: Button, val state: TextView, val progress: ProgressBar, val size: TextView)

    override fun onCreate(savedInstanceState: Bundle?) {
        if (prefs.getString("theme", "system") == "dark") androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_models)
        list = findViewById(R.id.m_list)
        source = findViewById(R.id.m_source)
        source.setText(ModelCatalog.source(this))

        findViewById<android.view.View>(R.id.m_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.m_apply_source).setOnClickListener {
            prefs.edit().putString(ModelCatalog.KEY_SOURCE, source.text.toString().trim()).apply()
            Toast.makeText(this, "Source set", Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.m_download_all).setOnClickListener {
            ModelCatalog.blessed.filter { it.recommended }.forEach { if (!ModelCatalog.isInstalled(this, it.id)) start(it) }
        }

        buildCards()
    }

    private fun buildCards() {
        list.removeAllViews()
        for (spec in ModelCatalog.blessed) {
            val v = layoutInflater.inflate(R.layout.model_item, list, false)
            v.findViewById<TextView>(R.id.mi_name).text = spec.name
            v.findViewById<TextView>(R.id.mi_desc).text = spec.desc
            v.findViewById<TextView>(R.id.mi_size).text = "${spec.kind.uppercase()} · ${"%.1f".format(spec.sizeMB)} MB"
            v.findViewById<TextView>(R.id.mi_badge).visibility =
                if (spec.recommended) android.view.View.VISIBLE else android.view.View.GONE
            val action = v.findViewById<Button>(R.id.mi_action)
            val state = v.findViewById<TextView>(R.id.mi_state)
            val progress = v.findViewById<ProgressBar>(R.id.mi_progress)
            cards[spec.id] = CardUi(action, state, progress, v.findViewById(R.id.mi_size))
            action.setOnClickListener { start(spec) }
            list.addView(v)
            refreshCard(spec.id)
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
        downloader.download(spec, object : ModelDownloader.Listener {
            override fun onProgress(id: String, downloaded: Long, total: Long) {
                runOnUiThread {
                    c.progress.max = total.toInt().coerceAtLeast(1)
                    c.progress.progress = downloaded.toInt()
                    c.state.text = "Downloading ${downloaded / 1024} / ${total / 1024} KB"
                }
            }
            override fun onDone(id: String) { runOnUiThread { finishInstall(id) } }
            override fun onError(id: String, msg: String) {
                runOnUiThread {
                    c.progress.visibility = android.view.View.GONE
                    c.state.text = "Error: $msg"
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
        c.action.text = "Installed"
        c.action.isEnabled = false
    }

    // Sets the card's UI from the installed state. Does NOT call finishInstall
    // (that was the StackOverflow recursion: finishInstall -> refreshCard ->
    // finishInstall -> ...).
    private fun refreshCard(id: String) {
        val c = cards[id] ?: return
        if (ModelCatalog.isInstalled(this, id)) {
            c.state.text = "✓ Installed"
            c.action.text = "Installed"
            c.action.isEnabled = false
        } else {
            c.state.text = "Not installed"
            c.action.text = "Download"
            c.action.isEnabled = true
        }
    }
}
