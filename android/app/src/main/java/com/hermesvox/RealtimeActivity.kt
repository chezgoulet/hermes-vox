package com.hermesvox

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.hermesvox.mobile.HermesSession
import go.Seq

/**
 * RealtimeActivity — the immersive front-of-house. Full-height avatar reacting
 * to the real streamed entity, live text, a stream console, and a voice turn
 * loop. HARDENED: if the on-device STT is unavailable (headless emulator /
 * missing Google speech service) it degrades gracefully — a notice + a
 * text-mode input — instead of crashing, and the entity still works.
 */
class RealtimeActivity : AppCompatActivity() {
    private lateinit var avatar: AvatarView
    private lateinit var crawl: CrawlView
    private lateinit var notice: TextView
    private lateinit var textMode: android.widget.LinearLayout
    private lateinit var input: EditText
    private var controller: VoiceController? = null
    private var replyBuf = ""
    private var session: HermesSession? = null
    @Volatile private var voiceUp = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_realtime)
        Seq.setContext(applicationContext)

        avatar = findViewById(R.id.rt_avatar)
        crawl = findViewById(R.id.rt_crawl); crawl.setRole("reply")
        notice = findViewById(R.id.rt_mode_notice)
        textMode = findViewById(R.id.rt_textmode)
        input = findViewById(R.id.rt_input)

        val url = intent.getStringExtra("url").orEmpty()
        val key = intent.getStringExtra("key").orEmpty()
        val model = intent.getStringExtra("model") ?: "hermes-agent"
        if (url.isNotBlank() && key.isNotBlank()) {
            session = HermesSession(url, key, model)
            VoxLog.d("// connected → the entity")
            startVoice()
        } else {
            enableTextMode("connect first (URL + key)")
        }

        findViewById<Button>(R.id.rt_back).setOnClickListener { finish(); overridePendingTransition(R.anim.fade_in, R.anim.fade_out) }
        findViewById<Button>(R.id.rt_send).setOnClickListener { sendText() }
        input.setOnEditorActionListener { _, _, _ -> sendText(); true }
        startAvatarLoop()
    }

    private fun startVoice() {
        val c = VoiceController(this, session!!)
        controller = c
        val duplex = getSharedPreferences("hv", android.content.Context.MODE_PRIVATE).getBoolean("duplex", true)
        val started = try { c.start(listener, duplex) } catch (_: Throwable) { false }
        if (!started) enableTextMode("voice unavailable here — type to the entity")
    }

    private fun sendText() {
        if (input.text.isBlank()) return
        val c = controller ?: VoiceController(this, session!!).also { controller = it }
        c.attachListeners(listener)
        c.sendText(input.text.toString())
        input.text.clear()
    }

    private fun enableTextMode(reason: String) {
        voiceUp = false
        notice.text = reason
        notice.visibility = android.view.View.VISIBLE
        textMode.visibility = android.view.View.VISIBLE
        append("// $reason")
    }

    private val listener = object : VoiceController.Listener {
        override fun onState(state: String) { runOnUiThread { avatar.setState(state) } }
        override fun onDelta(d: String) { runOnUiThread { replyBuf += d; crawl.setText(replyBuf) } }
        override fun onLog(line: String) { runOnUiThread { VoxLog.d(line); if (line.startsWith("◆ tool")) avatar.pulseTool() } }
        override fun onReply(finalText: String) { runOnUiThread { replyBuf = finalText; crawl.setText(replyBuf) } }
        override fun onError(msg: String) { runOnUiThread {
            append("// $msg")
            if (msg.contains("speech unavailable")) enableTextMode("voice unavailable here — type to the entity")
        } }
    }

    private fun append(line: String) { VoxLog.d(line) }

    private fun startAvatarLoop() {
        val tick = object : Runnable { override fun run() { avatar.invalidate(); avatar.postDelayed(this, 30) } }
        avatar.post(tick)
    }

    override fun onStop() { super.onStop(); controller?.stop() }
}
