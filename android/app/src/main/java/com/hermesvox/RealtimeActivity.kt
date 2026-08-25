package com.hermesvox

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.hermesvox.mobile.HermesSession
import go.Seq

// RealtimeActivity is the immersive view: ONLY the avatar (reacting) + the streamed
// calls + the live text. It runs the warm voice turn (STT -> entity -> TTS + barge-in)
// and logs the agent's activity to the stream log.
class RealtimeActivity : AppCompatActivity() {
    private lateinit var avatar: AvatarView
    private lateinit var stream: TextView
    private lateinit var text: TextView
    private var controller: VoiceController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_realtime)
        Seq.setContext(applicationContext)
        avatar = findViewById(R.id.rt_avatar)
        stream = findViewById(R.id.rt_stream)
        text = findViewById(R.id.rt_text)

        val url = intent.getStringExtra("url").orEmpty()
        val key = intent.getStringExtra("key").orEmpty()
        val model = intent.getStringExtra("model") ?: "hermes-agent"
        if (url.isNotBlank() && key.isNotBlank()) {
            val session = HermesSession(url, key, model)
            controller = VoiceController(this, session)
            append("// connected → the entity")
            startVoiceLoop()
        } else {
            append("// connect first (URL + key)")
        }

        findViewById<Button>(R.id.rt_back).setOnClickListener { finish() }
        startAvatarLoop()
    }

    private fun startVoiceLoop() {
        controller?.start(
            onListening = { runOnUiThread { append("// hear → listening"); avatar.setState("listening") } },
            onReply = { r -> runOnUiThread {
                text.text = r
                append("// agent → " + r.take(90))
                avatar.setState("speaking")
            } },
            onError = { e -> runOnUiThread {
                append("// $e")
                avatar.setState("idle")
            } }
        )
    }

    // The stream log: the agent's calls/activity in the background.
    private fun append(line: String) {
        val t = stream.text.toString()
        stream.text = ("$t\n$line").trim().takeLast(1200)
        stream.post { stream.scrollTo(0, stream.height) }
    }

    private fun startAvatarLoop() {
        val tick = object : Runnable {
            override fun run() { avatar.invalidate(); avatar.postDelayed(this, 30) }
        }
        avatar.post(tick)
    }

    override fun onStop() { super.onStop(); controller?.stop() }
}
