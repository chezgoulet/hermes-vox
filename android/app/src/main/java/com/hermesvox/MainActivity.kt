package com.hermesvox

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
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
    private var session: HermesSession? = null
    private var controller: VoiceController? = null
    private var pendingAvatarState = "idle"

    override fun onCreate(savedInstanceState: Bundle?) {
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

        val prefs = getSharedPreferences("hv", Context.MODE_PRIVATE)
        url.setText(prefs.getString("url", "http://100.84.47.125:8642"))
        model.setText(prefs.getString("model", "hermes-agent"))
        key.setText(prefs.getString("key", ""))

        findViewById<Button>(R.id.connect).setOnClickListener { connect() }
        findViewById<Button>(R.id.send).setOnClickListener { send(input.text.toString()) }
        findViewById<Button>(R.id.mic).setOnClickListener { talk() }
        findViewById<Button>(R.id.settings).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.clear).setOnClickListener {
            controller?.stop(); controller = null
            input.text.clear(); reply.text = ""; status.text = "Not connected"; setAvatar("idle")
        }

        startAvatarLoop()

        if (key.text.isNotBlank()) connect()
    }

    // The avatar breathes/reacts on a tick (the Canvas animates via invalidate).
    private fun startAvatarLoop() {
        val tick = object : Runnable {
            override fun run() {
                avatar.invalidate()
                avatar.postDelayed(this, 30)
            }
        }
        avatar.post(tick)
    }

    private fun setAvatar(s: String) {
        avatar.apply {
            setStateLevel(s, if (s == "listening") 0.35f else if (s == "speaking") 0.7f else 0f, s == "thinking")
            // live level comes from the voice controller on a real device
            invalidate()
        }
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
        Toast.makeText(this, "Connected", Toast.LENGTH_SHORT).show()
    }

    private fun send(text: String) {
        val s = session ?: run { status.text = "Connect first"; return }
        if (text.isBlank()) return
        reply.text = "…"
        setAvatar("thinking")
        thread {
            val r = try { s.turnStored(text) } catch (e: Throwable) { "hermes: ${e.message}" }
            runOnUiThread { reply.text = r; setAvatar("idle") }
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
            onListening = { runOnUiThread { status.text = "Listening…"; setAvatar("listening") } },
            onReply = { r -> runOnUiThread { reply.text = r; status.text = "Speaking…"; setAvatar("speaking") } },
            onError = { e -> runOnUiThread {
                status.text = if (e.contains("interrupt")) "You interrupted" else e
                setAvatar("idle")
            } }
        )
    }

    // Expose the configurable attributes (the pipeline backends + voice + thresholds).
    private fun showSettings() {
        val prefs = getSharedPreferences("hv", Context.MODE_PRIVATE)
        val stt = prefs.getString("stt", "on-device")!!
        val tts = prefs.getString("tts", "on-device")!!
        val voice = prefs.getString("voice", "system")!!
        val duplex = prefs.getBoolean("duplex", true)
        val items = arrayOf(
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
                    0 -> pickOne("Speech-to-text", arrayOf("on-device", "RX 590", "Odroid"), "stt", prefs)
                    1 -> pickOne("Text-to-speech", arrayOf("on-device", "Kokoro", "Piper", "RX 590", "Odroid"), "tts", prefs)
                    2 -> pickOne("Voice", arrayOf("system", "Warm", "Bright", "Deep"), "voice", prefs)
                    3 -> prefs.edit().putBoolean("duplex", !duplex).apply().also { showSettings() }
                }
            }
            .setNeutralButton("Close", null)
            .show()
    }

    private fun pickOne(title: String, options: Array<String>, prefKey: String, prefs: android.content.SharedPreferences) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(options, 0) { d, which ->
                prefs.edit().putString(prefKey, options[which]).apply()
                d.dismiss()
            }
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onStop() { super.onStop(); controller?.stop() }
}
