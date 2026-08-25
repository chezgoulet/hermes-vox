package com.hermesvox

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
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
    private var session: HermesSession? = null
    private var controller: VoiceController? = null

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

        val prefs = getSharedPreferences("hv", Context.MODE_PRIVATE)
        url.setText(prefs.getString("url", "http://100.84.47.125:8642"))
        model.setText(prefs.getString("model", "hermes-agent"))
        key.setText(prefs.getString("key", ""))

        findViewById<Button>(R.id.connect).setOnClickListener { connect() }
        findViewById<Button>(R.id.send).setOnClickListener { send(input.text.toString()) }
        findViewById<Button>(R.id.mic).setOnClickListener { talk() }
        findViewById<Button>(R.id.clear).setOnClickListener {
            controller?.stop(); controller = null
            input.text.clear(); reply.text = ""; status.text = "Not connected"
        }

        if (key.text.isNotBlank()) connect()
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
        Toast.makeText(this, "Connected", Toast.LENGTH_SHORT).show()
    }

    // Text path: send a typed turn to the entity, display the reply.
    private fun send(text: String) {
        val s = session ?: run { status.text = "Connect first"; return }
        if (text.isBlank()) return
        reply.text = "…"
        thread {
            val r = try { s.turnStored(text) } catch (e: Throwable) { "hermes: ${e.message}" }
            runOnUiThread { reply.text = r }
        }
    }

    // Voice path: the VoiceController runs the warm voice turn + barge-in.
    private fun talk() {
        val s = session ?: run { status.text = "Connect first (enter your key)"; return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
            return
        }
        if (controller == null) controller = VoiceController(this, s)
        controller?.start(
            onListening = { runOnUiThread { status.text = "Listening…" } },
            onReply = { r -> runOnUiThread { reply.text = r; status.text = "Speaking…" } },
            onError = { e -> runOnUiThread {
                status.text = if (e.contains("interrupt")) "You interrupted" else e
            } }
        )
    }

    override fun onStop() { super.onStop(); controller?.stop() }
}
