package com.hermesvox

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hermesvox.mobile.HermesSession
import go.Seq
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var tts: TextToSpeech
    private lateinit var url: EditText
    private lateinit var key: EditText
    private lateinit var model: EditText
    private lateinit var input: EditText
    private lateinit var reply: TextView
    private lateinit var status: TextView
    private var session: HermesSession? = null
    private var ttsReady = false

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

        // Persist the connection fields locally (the key is the USER's, entered at
        // runtime — never baked into the build, never committed).
        val prefs = getSharedPreferences("hv", Context.MODE_PRIVATE)
        url.setText(prefs.getString("url", "http://100.84.47.125:8642"))
        model.setText(prefs.getString("model", "hermes-agent"))
        key.setText(prefs.getString("key", ""))

        findViewById<Button>(R.id.connect).setOnClickListener { connect() }
        findViewById<Button>(R.id.send).setOnClickListener { send(input.text.toString()) }
        findViewById<Button>(R.id.mic).setOnClickListener { startListening() }
        findViewById<Button>(R.id.clear).setOnClickListener { input.text.clear(); reply.text = "" }

        tts = TextToSpeech(this, this)
        if (key.text.isNotBlank()) connect() // auto-connect if a key is already saved
    }

    private fun connect() {
        val u = url.text.toString().trim()
        val k = key.text.toString().trim()
        val m = model.text.toString().trim().ifEmpty { "hermes-agent" }
        if (u.isBlank() || k.isBlank()) { status.text = "Enter the Hermes URL and API key"; return }

        // The entity connector (Go source of truth).
        session = HermesSession(u, k, m)
        getSharedPreferences("hv", Context.MODE_PRIVATE).edit()
            .putString("url", u).putString("model", m).putString("key", k).apply()
        status.text = "Connected → the entity"
        Toast.makeText(this, "Connected", Toast.LENGTH_SHORT).show()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) { tts.language = Locale.US; ttsReady = true }
    }

    private fun send(text: String) {
        val s = session
        if (s == null) { status.text = "Connect first (enter your key)"; return }
        if (text.isBlank()) return
        reply.text = "…"
        Thread {
            try {
                val r = s.turnStored(text)  // /v1/responses: the entity keeps context server-side // the entity replies
                runOnUiThread { reply.text = r; speak(r) }
            } catch (e: Throwable) {
                runOnUiThread { reply.text = "Hmm: ${e.message}" }
            }
        }.start()
    }

    private fun speak(text: String) {
        if (ttsReady && text.isNotBlank()) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hermesvox")
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
        try {
            startActivityForResult(intent, 200)
        } catch (e: Exception) {
            Toast.makeText(this, "Speech recognizer unavailable", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 200 && res == Activity.RESULT_OK) {
            val t = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!t.isNullOrBlank()) { input.setText(t); send(t) }
        }
    }
}
