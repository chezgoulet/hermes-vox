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

/**
 * OnboardingActivity — first-run connection flow. The user enters the entity
 * endpoint + API key (secret, entered in-app, never committed) and hits
 * "Connect & verify": we make a REAL probe (Ping -> GET /v1/models) so a bad
 * URL/key is caught up front, never faked.
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
        key.setText(prefs.getString("key", ""))

        findViewById<Button>(R.id.connect).setOnClickListener { connectAndVerify() }
        findViewById<TextView>(R.id.skip).setOnClickListener { goMain() }
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
                    prefs.edit().putString("url", u).putString("model", m).putString("key", k).apply()
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
