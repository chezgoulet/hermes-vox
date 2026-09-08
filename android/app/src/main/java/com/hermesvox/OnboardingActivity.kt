package com.hermesvox

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hermesvox.mobile.HermesSession
import kotlin.concurrent.thread

// ---------------------------------------------------------------------------
// #120-B: canonical one-line voice-mode comparison. There are exactly TWO voice
// modes in this build (the walkie-talkie/PTT path was stripped in C2, 0.4.0 —
// both modes are hands-free open lines). These strings are the single source
// for the onboarding step and the Settings picker (agent A/C may reuse them
// for per-option subtitles).
// ---------------------------------------------------------------------------
fun voiceModeCompare(mode: String?): String = when (mode) {
    ModelCatalog.MODE_REALTIME -> "hands-free, always listening"
    ModelCatalog.MODE_ENHANCED -> "adds a phone-call persona"
    else -> "hands-free, always listening"
}

/** #120-B: full "· name — what it means" line for a mode. */
fun voiceModeLine(mode: String?): String = when (mode) {
    ModelCatalog.MODE_ENHANCED -> "· Enhanced Realtime (alpha) — " + voiceModeCompare(mode)
    else -> "· Realtime — " + voiceModeCompare(mode)
}

/**
 * OnboardingActivity — first-run connection flow. The user enters the entity
 * endpoint + API key (secret, entered in-app, never committed) and hits
 * "Connect & verify": we make a REAL probe (Ping -> GET /v1/models) so a bad
 * URL/key is caught up front, never faked.
 *
 * #120-A: a true first run opens on a short "how this works" step (what the
 * being is, the two voice modes, mic vs PTT) before the connection form, and
 * "Skip" now explains what it skips instead of proceeding silently.
 */
class OnboardingActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("hv", Context.MODE_PRIVATE) }
    private lateinit var url: EditText
    private lateinit var key: EditText
    private lateinit var model: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        val pre = getSharedPreferences("hv", Context.MODE_PRIVATE)
        if (pre.getString("theme", "system")!! == "dark") androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        url = findViewById(R.id.url)
        key = findViewById(R.id.key)
        model = findViewById(R.id.model)
        model.setText(prefs.getString("model", "hermes-agent"))
        url.setText(prefs.getString("url", ""))
        key.setText(SecureStore.decrypt(prefs.getString("key", "").orEmpty()).orEmpty())

        // #120-A: returning users (endpoint already stored) go straight to the
        // connection form; a true first run sees the explainer step first.
        val firstRun = prefs.getString("url", "").orEmpty().isBlank()
        findViewById<android.view.View>(R.id.ob_step_how).visibility =
            if (firstRun) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<android.view.View>(R.id.ob_step_connect).visibility =
            if (firstRun) android.view.View.GONE else android.view.View.VISIBLE
        // #120-B: fill the per-mode comparison lines from the shared copy.
        findViewById<TextView>(R.id.ob_mode_realtime).text =
            voiceModeLine(ModelCatalog.MODE_REALTIME)
        findViewById<TextView>(R.id.ob_mode_enhanced).text =
            voiceModeLine(ModelCatalog.MODE_ENHANCED)

        findViewById<Button>(R.id.ob_got_it).setOnClickListener { showConnectStep() }
        findViewById<Button>(R.id.connect).setOnClickListener { connectAndVerify() }
        // #120-A: Skip is no longer a silent dead end — both Skip affordances
        // (explainer step + form) explain what they skip first.
        val skipAction = android.view.View.OnClickListener { confirmSkip() }
        findViewById<TextView>(R.id.skip).setOnClickListener(skipAction)
        findViewById<TextView>(R.id.ob_skip_how)?.setOnClickListener(skipAction)
    }

    /** #120-A: reveal the connection form (the explainer step is done). */
    private fun showConnectStep() {
        findViewById<android.view.View>(R.id.ob_step_how).visibility = android.view.View.GONE
        findViewById<android.view.View>(R.id.ob_step_connect).visibility = android.view.View.VISIBLE
    }

    /** #120-A: tell the user what Skip actually skips before doing it. */
    private fun confirmSkip() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Skip for now?")
            .setMessage("You can explore first, but nothing works until you connect to your Hermes gateway AND download the voice models — you need both before you can talk. You can do either later from Settings (⚙).")
            .setPositiveButton("Skip anyway") { _, _ -> goMain() }
            .setNegativeButton("Keep setting up", null)
            .show()
    }

    private fun connectAndVerify() {
        val u = url.text.toString().trim()
        val k = key.text.toString().trim()
        val m = model.text.toString().trim().ifEmpty { "hermes-agent" }
        if (u.isBlank() || k.isBlank()) { Toast.makeText(this, "Enter the endpoint and API key", Toast.LENGTH_SHORT).show(); return }
        val btn = findViewById<Button>(R.id.connect); btn.isEnabled = false; btn.text = "Verifying…"
        thread {
            val ok = try {
                HermesSession(u, k, m).ping()
                true
            } catch (e: Throwable) { false }
            runOnUiThread {
                btn.isEnabled = true; btn.text = getString(R.string.hv_connect_verify)
                if (ok) {
                    prefs.edit().putString("url", u).putString("model", m).putString("key", (SecureStore.encrypt(k) ?: k)).apply()
                    Toast.makeText(this, "Connected → the entity", Toast.LENGTH_SHORT).show()
                    goMain()
                } else {
                    Toast.makeText(this, "Could not reach the entity — check URL + key", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun goMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
