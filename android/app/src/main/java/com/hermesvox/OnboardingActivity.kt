package com.hermesvox

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hermesvox.core.HermesSession
import kotlin.concurrent.thread
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// #222/D16: there is one normal operation — a hands-free open line (the
// walkie-talkie/PTT path was stripped in C2, 0.4.0). Presence is a layer on it,
// not a second mode, so first run describes the layer in one line instead of
// asking the user to choose between two.
// ---------------------------------------------------------------------------
const val PRESENCE_FIRST_RUN_LINE =
    "· Presence — the being, its voice and its asides on your phone. On by default; " +
        "turn it off in Settings → Presence."

/**
 * OnboardingActivity — first-run connection flow. The user enters the entity
 * endpoint + API key (secret, entered in-app, never committed) and hits
 * "Connect & verify": we make a REAL probe (Ping -> GET /v1/models) so a bad
 * URL/key is caught up front, never faked.
 *
 * The entity scope (optional, X-Hermes-Session-Key) is asked here too, because
 * first run is where a person is already deciding what this install is: on a
 * gateway shared by several people, that value is the only thing that keeps
 * their memories apart (see SessionScope). Blank is a valid answer and stays the
 * gateway's per-transcript default.
 *
 * #120-A: a true first run opens on a short "how this works" step (what the
 * being is, Presence as a layer, mic vs PTT) before the connection form, and
 * "Skip" now explains what it skips instead of proceeding silently.
 */
class OnboardingActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("hv", Context.MODE_PRIVATE) }
    private lateinit var url: EditText
    private lateinit var key: EditText
    private lateinit var model: EditText
    private lateinit var scope: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        val pre = getSharedPreferences("hv", Context.MODE_PRIVATE)
        if (pre.getString("theme", "system")!! == "dark") androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        // #130: the presence yields space when the text is large or the window is
        // short, so the button below the prose stays a short scroll away. The copy
        // and the action never shrink — only the decoration does.
        applyAvatarSize()

        url = findViewById(R.id.url)
        key = findViewById(R.id.key)
        model = findViewById(R.id.model)
        scope = findViewById(R.id.scope)
        model.setText(prefs.getString("model", "hermes-agent"))
        url.setText(prefs.getString("url", ""))
        key.setText(SecureStore.decrypt(prefs.getString("key", "").orEmpty()).orEmpty())
        // Re-entering onboarding on an existing install shows what is declared
        // (blank when nothing is), so the field is never a surprising blank.
        scope.setText(prefs.getString(SessionScope.PREF, "").orEmpty())

        // #120-A: returning users (endpoint already stored) go straight to the
        // connection form; a true first run sees the explainer step first.
        val firstRun = prefs.getString("url", "").orEmpty().isBlank()
        findViewById<android.view.View>(R.id.ob_step_how).visibility =
            if (firstRun) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<android.view.View>(R.id.ob_step_connect).visibility =
            if (firstRun) android.view.View.GONE else android.view.View.VISIBLE
        // #222/D16: one line about the layer, never a choice between two modes.
        findViewById<TextView>(R.id.ob_presence_line).text = PRESENCE_FIRST_RUN_LINE

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
        // #130: a fresh step starts at its top. If the explainer had to be scrolled to
        // reach its button, the form would otherwise open at the same offset with its
        // first field above the fold — ScrollView only re-clamps when the new content
        // fits, so the old offset survives a form that is still taller than the window.
        findViewById<android.widget.ScrollView>(R.id.ob_scroll).scrollTo(0, 0)
    }

    /**
     * #130: size the presence for this device. The column scrolls, so this is not
     * about reachability — it is about how far a first-time user has to scroll to
     * the one button that moves them forward, at a text scale where the prose can
     * easily be a full screen taller than the window.
     *
     * At the declared size the layout is left strictly alone, so "the default
     * screen is unchanged" is exactly true rather than approximately true (the
     * declaration itself is pinned to AVATAR_FULL_DP by OnboardingLayoutTest).
     */
    private fun applyAvatarSize() {
        findViewById<AvatarView>(R.id.ob_avatar).apply {
            setPortalShape(-1f)   // the being greets you through a portal
            // #275: on the light theme, as ink on the page rather than light in a black hole.
            val night = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
            setLightSurface(night != android.content.res.Configuration.UI_MODE_NIGHT_YES,
                androidx.core.content.ContextCompat.getColor(context, R.color.hv_bg))
        }
        val cfg = resources.configuration
        val dp = OnboardingLayout.avatarSizeDp(cfg.fontScale, cfg.screenHeightDp)
        if (dp == OnboardingLayout.AVATAR_FULL_DP) return
        val px = (dp * resources.displayMetrics.density).roundToInt()
        val avatar = findViewById<android.view.View>(R.id.ob_avatar)
        val lp = avatar.layoutParams
        lp.width = px
        lp.height = px
        avatar.layoutParams = lp
    }

    /** #120-A: tell the user what Skip actually skips before doing it. */
    private fun confirmSkip() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Skip for now?")
            .setMessage("You can explore first, but nothing works until you connect to your Hermes gateway AND download the voice models — you need both before you can talk. You can do either later from Settings (⚙).")
            .setPositiveButton("Skip anyway") { _, _ ->
                prefs.edit().putBoolean(FirstRunRoute.PREF_SKIPPED, true).apply()
                goMain()
            }
            .setNegativeButton("Keep setting up", null)
            .show()
    }

    private fun connectAndVerify() {
        val u = url.text.toString().trim()
        val k = key.text.toString().trim()
        val m = model.text.toString().trim().ifEmpty { "hermes-agent" }
        val sc = scope.text.toString()
        if (u.isBlank() || k.isBlank()) { Toast.makeText(this, "Enter the endpoint and API key", Toast.LENGTH_SHORT).show(); return }
        // Checked BEFORE the probe: a scope the gateway would reject (or one that
        // would have to be silently rewritten) must not be discovered mid-call —
        // and it must never be stored as a different identity than typed.
        val scopeIssue = SessionScope.validationError(sc)
        if (scopeIssue != null) { Toast.makeText(this, scopeIssue, Toast.LENGTH_LONG).show(); return }
        val btn = findViewById<Button>(R.id.connect); btn.isEnabled = false; btn.text = "Verifying…"
        thread {
            val ok = try {
                HermesSession(u, k, m).ping()
                true
            } catch (e: Throwable) { false }
            runOnUiThread {
                btn.isEnabled = true; btn.text = getString(R.string.hv_connect_verify)
                if (ok) {
                    prefs.edit().putString("url", u).putString("model", m).putString("key", (SecureStore.encrypt(k) ?: k))
                        .putString(SessionScope.PREF, SessionScope.normalize(sc)).apply()
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
