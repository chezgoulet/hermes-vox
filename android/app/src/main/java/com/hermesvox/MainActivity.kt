package com.hermesvox

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hermesvox.mobile.HermesSession
import go.Seq
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var url: EditText
    private lateinit var key: EditText
    private lateinit var model: EditText
    private lateinit var input: EditText
    private lateinit var reply: TextView
    private lateinit var status: TextView
    private lateinit var avatar: AvatarView
    private lateinit var stream: TextView
    private var session: HermesSession? = null
    private var controller: VoiceController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply the persisted theme before the UI inflates (System/Dark/Light).
        val prefs = getSharedPreferences("hv", Context.MODE_PRIVATE)
        applyTheme(prefs.getString("theme", "system")!!)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Seq.setContext(applicationContext)

        url = findViewById(R.id.url)
        key = findViewById(R.id.key)
        model = findViewById(R.id.model)
        input = findViewById(R.id.input)
        reply = findViewById(R.id.reply)
        status = findViewById(R.id.status)
        avatar = findViewById(R.id.avatar)
        stream = findViewById(R.id.stream)

        // No pre-filled IP — the user enters the Hermes endpoint (release-safe).
        url.setText(prefs.getString("url", ""))
        model.setText(prefs.getString("model", "hermes-agent"))
        key.setText(prefs.getString("key", ""))

        findViewById<Button>(R.id.connect).setOnClickListener { connect() }
        findViewById<Button>(R.id.send).setOnClickListener { send(input.text.toString()) }
        findViewById<Button>(R.id.mic).setOnClickListener { talk() }
        findViewById<Button>(R.id.settings).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.realtime).setOnClickListener { openRealtime() }
        findViewById<Button>(R.id.clear).setOnClickListener {
            controller?.stop(); controller = null
            input.text.clear(); reply.text = ""; status.text = "Not connected"; setAvatar("idle"); stream.text = "// stream log"
        }
        startAvatarLoop()
    }

    private fun applyTheme(mode: String) {
        when (mode) {
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    private fun startAvatarLoop() {
        val tick = object : Runnable {
            override fun run() { avatar.invalidate(); avatar.postDelayed(this, 30) }
        }
        avatar.post(tick)
    }

    private fun setAvatar(s: String) {
        avatar.setStateLevel(s, if (s == "listening") 0.35f else if (s == "speaking") 0.7f else 0f, s == "thinking")
    }

    private fun appendStream(line: String) {
        val t = stream.text.toString()
        stream.text = ("$t\n$line").trim().takeLast(1200)
        stream.post { stream.scrollTo(0, stream.height) }
    }

    private fun connect() {
        val u = url.text.toString().trim()
        val k = key.text.toString().trim()
        val m = model.text.toString().trim().ifEmpty { "hermes-agent" }
        if (u.isBlank() || k.isBlank()) { status.text = "Enter the Hermes URL and API key"; return }
        session = HermesSession(u, k, m)
        getSharedPreferences("hv", Context.MODE_PRIVATE).edit()
            .putString("url", u).putString("model", m).putString("key", k).apply()
        status.text = "Connected → the entity"
        setAvatar("idle")
        appendStream("// connected → " + u)
        Toast.makeText(this, "Connected", Toast.LENGTH_SHORT).show()
    }

    private fun send(text: String) {
        val s = session ?: run { status.text = "Connect first"; return }
        if (text.isBlank()) return
        reply.text = "…"
        setAvatar("thinking")
        appendStream("// you → " + text)
        thread {
            val r = try {
                appendStream("// agent working…")
                s.turnStored(text)
            } catch (e: Throwable) { "hermes: ${e.message}" }
            runOnUiThread { reply.text = r; setAvatar("idle"); appendStream("// agent → " + r.take(90)) }
        }
    }

    private fun talk() {
        val s = session ?: run { status.text = "Connect first (enter your key)"; return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
            return
        }
        if (controller == null) controller = VoiceController(this, s)
        controller?.start(
            onListening = { runOnUiThread { status.text = "Listening…"; setAvatar("listening"); appendStream("// hear → listening") } },
            onReply = { r -> runOnUiThread { reply.text = r; status.text = "Speaking…"; setAvatar("speaking"); appendStream("// agent → " + r.take(90)) } },
            onError = { e -> runOnUiThread {
                status.text = if (e.contains("interrupt")) "You interrupted" else e
                setAvatar("idle"); appendStream("// $e")
            } }
        )
    }

    private fun openRealtime() {
        val u = url.text.toString().trim()
        val k = key.text.toString().trim()
        if (u.isBlank() || k.isBlank()) { status.text = "Connect first (enter the URL and key)"; return }
        startActivity(Intent(this, RealtimeActivity::class.java)
            .putExtra("url", u).putExtra("key", k).putExtra("model", model.text.toString().trim().ifEmpty { "hermes-agent" }))
    }

    private fun showSettings() {
        val prefs = getSharedPreferences("hv", Context.MODE_PRIVATE)
        val theme = prefs.getString("theme", "system")!!
        val stt = prefs.getString("stt", "on-device")!!
        val tts = prefs.getString("tts", "on-device")!!
        val voice = prefs.getString("voice", "system")!!
        val duplex = prefs.getBoolean("duplex", true)
        val items = arrayOf(
            "Theme: $theme",
            "Speech-to-text: $stt",
            "Text-to-speech: $tts",
            "Voice: $voice",
            "Barge-in (duplex): ${if (duplex) "on" else "off"}",
            "Entity: ${url.text} · ${model.text}"
        )
        AlertDialog.Builder(this)
            .setTitle("Hermes Vox settings")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> pickOne("Theme", arrayOf("system", "dark", "light"), "theme", prefs)
                    1 -> pickOne("Speech-to-text", arrayOf("on-device", "RX 590", "Odroid"), "stt", prefs)
                    2 -> pickOne("Text-to-speech", arrayOf("on-device", "Kokoro", "Piper", "RX 590", "Odroid"), "tts", prefs)
                    3 -> pickOne("Voice", arrayOf("system", "Warm", "Bright", "Deep"), "voice", prefs)
                    4 -> prefs.edit().putBoolean("duplex", !duplex).apply().also { showSettings() }
                }
            }
            .setNeutralButton("Close", null)
            .setNegativeButton("Apply theme") { _, _ -> recreate() } // re-apply theme on recreate
            .show()
    }

    private fun pickOne(title: String, options: Array<String>, prefKey: String, prefs: android.content.SharedPreferences) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(options, 0) { d, which ->
                prefs.edit().putString(prefKey, options[which]).apply()
                if (prefKey == "theme") { applyTheme(options[which]); recreate() }
                d.dismiss()
            }
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onStop() { super.onStop(); controller?.stop() }
}
