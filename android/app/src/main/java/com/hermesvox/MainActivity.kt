package com.hermesvox

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hermesvox.mobile.HermesSession
import go.Seq

/**
 * MainActivity — the front-of-house surface. Hosts the avatar, the live reply,
 * the stream console, and the voice/text input. Runs the entity via
 * VoiceController (streamed SSE turns) and renders the entity's real work.
 * First run routes to OnboardingActivity; subsequent launches auto-connect.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var reply: TextView
    private lateinit var stream: TextView
    private lateinit var avatar: AvatarView
    private var session: HermesSession? = null
    private var controller: VoiceController? = null
    private val prefs by lazy { getSharedPreferences("hv", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme(prefs.getString("theme", "system")!!)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Seq.setContext(applicationContext)
        VoxLog.init(applicationContext)
        maybeRunWhisperProbe()

        status = findViewById(R.id.status)
        input = findViewById(R.id.input)
        reply = findViewById(R.id.reply)
        stream = findViewById(R.id.stream)
        avatar = findViewById(R.id.avatar)

        // First run → onboarding (no stored endpoint/key yet).
        if (prefs.getString("url", "").orEmpty().isBlank() || prefs.getString("key", "").orEmpty().isBlank()) {
            openOnboarding(); return
        }

        intentExtras()
        connectFromPrefs()
        wireButtons()
        startAvatarLoop()
    }

    private fun openOnboarding() {
        startActivity(Intent(this, OnboardingActivity::class.java))
        finish()
    }

    private fun intentExtras() {
        // adb/E2E deep-link: connect + send without touching the UI by hand.
        val u = intent.getStringExtra("url"); val k = intent.getStringExtra("key")
        val m = intent.getStringExtra("model"); val say = intent.getStringExtra("say")
        if (!u.isNullOrBlank() && !k.isNullOrBlank()) {
            prefs.edit().putString("url", u).putString("model", m ?: "hermes-agent").putString("key", k).apply()
        }
        if (!say.isNullOrBlank()) { intent.removeExtra("say"); autoSend = say }
    }
    private var autoSend: String? = null

    private fun connectFromPrefs() {
        val u = prefs.getString("url", "").orEmpty()
        val k = prefs.getString("key", "").orEmpty()
        val m = prefs.getString("model", "hermes-agent").orEmpty()
        if (u.isBlank() || k.isBlank()) return
        session = HermesSession(u, k, m)
        status.text = getString(R.string.hv_connected)
        appendStream("// connected → $u")
        // Auto-send a routed turn (E2E proof path).
        autoSend?.let { send(it) }
    }

    private fun wireButtons() {
        findViewById<Button>(R.id.send).setOnClickListener { send(input.text.toString()) }
        findViewById<Button>(R.id.mic).setOnClickListener { talk() }
        findViewById<Button>(R.id.settings).setOnClickListener { openSettings() }
        findViewById<Button>(R.id.realtime).setOnClickListener { openRealtime() }
        findViewById<Button>(R.id.clear).setOnClickListener {
            controller?.stop(); controller = null
            session?.resetConversation()
            input.text.clear(); reply.text = ""; status.text = getString(R.string.hv_not_connected)
            avatar.setState("idle"); stream.text = "// stream log — watch the agent work"
        }
        input.setOnEditorActionListener { _, _, _ -> send(input.text.toString()); true }
    }

    private fun send(text: String) {
        val s = session ?: run { status.text = "Connect first"; return }
        if (text.isBlank()) return
        input.text.clear()
        val c = controller ?: VoiceController(this, s).also { controller = it }
        c.attachListeners(listener)
        c.sendText(text)
    }

    private fun talk() {
        val s = session ?: run { status.text = "Connect first"; return }
        val needAudio = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        val needNotif = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needAudio || needNotif) {
            ActivityCompat.requestPermissions(this,
                listOfNotNull(
                    Manifest.permission.RECORD_AUDIO.takeIf { needAudio },
                    Manifest.permission.POST_NOTIFICATIONS.takeIf { needNotif }
                ).toTypedArray(), 100)
            return
        }
        // The voice pipeline runs in a foreground service so it survives backgrounding
        // (microphone type; START_STICKY). The activity controller drives the UI.
        VoiceService.start(this)
        val c = controller ?: VoiceController(this, s).also { controller = it }
        val duplex = prefs.getBoolean("duplex", true)
        c.start(listener, duplex)
    }

    private val listener = object : VoiceController.Listener {
        override fun onState(state: String) {
            runOnUiThread {
                status.text = when (state) {
                    "listening" -> "Listening…"
                    "thinking" -> "The entity is working…"
                    "speaking" -> "Speaking…"
                    else -> getString(R.string.hv_connected)
                }
                avatar.setStateLevel(state,
                    if (state == "listening") 0.4f else if (state == "speaking") 0.6f else 0f,
                    state == "thinking")
            }
        }
        override fun onDelta(text: String) { runOnUiThread {
            reply.append(text)
            stream.post { stream.scrollTo(0, stream.bottom) }
        } }
        override fun onLog(line: String) { runOnUiThread {
            appendStream(line)
            if (line.startsWith("◆ tool")) avatar.pulseTool()
        } }
        override fun onReply(finalText: String) { runOnUiThread { reply.setText(finalText) } }
        override fun onError(msg: String) { runOnUiThread {
            status.text = if (msg.contains("interrupt")) "You interrupted" else msg
            avatar.setState("idle"); appendStream("// $msg")
        } }
    }

    private fun openSettings() { startActivity(Intent(this, SettingsActivity::class.java)); overridePendingTransition(R.anim.slide_in, R.anim.fade_out) }
    private fun openRealtime() {
        val u = prefs.getString("url", "").orEmpty(); val k = prefs.getString("key", "").orEmpty()
        if (u.isBlank() || k.isBlank()) { status.text = "Connect first"; return }
        startActivity(Intent(this, RealtimeActivity::class.java)
            .putExtra("url", u).putExtra("key", k).putExtra("model", prefs.getString("model", "hermes-agent"))); overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
    }

    private fun appendStream(line: String) {
        val t = stream.text.toString()
        stream.text = ("$t\n$line").trim().takeLast(1600)
        stream.post { stream.scrollTo(0, stream.height) }
    }

    private fun startAvatarLoop() {
        val tick = object : Runnable {
            override fun run() { avatar.invalidate(); avatar.postDelayed(this, 30) }
        }
        avatar.post(tick)
    }

    /** If a probe.wav is present in the whisper model dir, transcribe it (proof hook). */
    private fun maybeRunWhisperProbe() {
        val probe = java.io.File(filesDir, "models/whisper-tiny/probe.wav")
        if (!probe.exists()) return
        Thread {
            val text = OfflineWhisperStt.transcribeWave(this, probe.absolutePath)
            VoxLog.d("WHISPER PROBE transcript=<$text>")
            runOnUiThread { status.text = "whisper probe: ${text ?: "no transcript"}" }
        }.start()
    }

    private fun applyTheme(mode: String) {
        when (mode) {
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    override fun onStop() {
        super.onStop()
        controller?.stop()
        VoiceService.stop(this)
    }
}
