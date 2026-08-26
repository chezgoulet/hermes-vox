package com.hermesvox

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
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
    private lateinit var warming: android.widget.TextView
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var agentName: TextView
    private lateinit var input: EditText
    private lateinit var reply: CrawlView
    private lateinit var stream: CrawlView
    private lateinit var avatar: AvatarView
    private var session: HermesSession? = null
    private var controller: VoiceController? = null
    private val prefs by lazy { getSharedPreferences("hv", Context.MODE_PRIVATE) }
    private var prevIdx = 0
    private var replyBuf = ""
    private var sseBuf = "// stream log — watch the agent work"
    private var toolCount = 0
    private lateinit var conversation: android.widget.ScrollView
    private lateinit var convoText: android.widget.TextView
    private var convoBuf = ""
    private val express: VoxExpress = GemmaExpress(this)
    private val orch = VoiceOrchestrator(express)
    private var lineOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme(prefs.getString("theme", "system")!!)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Seq.setContext(applicationContext)
        VoxLog.init(applicationContext)
        CrashLog.init(applicationContext)
        maybeRunWhisperProbe()

        status = findViewById(R.id.status)
        agentName = findViewById(R.id.agent_name)
        input = findViewById(R.id.input)
        reply = findViewById(R.id.reply_crawl); reply.setRole("reply")
        stream = findViewById(R.id.stream); stream.setRole("sse")
        avatar = findViewById(R.id.avatar)
        conversation = findViewById(R.id.conversation)
        convoText = findViewById(R.id.convo_text)
        handleModeUi()
        updateStreamVisibility()
        // Tap the presence to cycle through its states/shapes (dev affordance +
        // a delightful easter egg). Cleared on a real turn.
        avatar.setOnClickListener {
            prevIdx = (prevIdx + 1) % AvatarView.SHAPES.size
            val n = AvatarView.SHAPES[prevIdx]
            avatar.preview(n)
            status.text = "presence: $n"
        }

        // First run → onboarding (no stored endpoint/key yet).
        if (prefs.getString("url", "").orEmpty().isBlank() || storedKey().isBlank()) {
            openOnboarding(); return
        }

        intentExtras()
        connectFromPrefs()
        wireButtons()
        startAvatarLoop()
        // Warming splash: covers the whole screen until the voice models are loaded,
        // so the user clearly sees the app is waiting (Christopher: can't tell if warm).
        warming = android.widget.TextView(this).apply {
            text = "Preparing your voice\u2026"
            textSize = 18f
            setTextColor(0xFFD6F4FF.toInt())
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(0xFF06070B.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        }
        (findViewById<android.view.View>(android.R.id.content) as android.view.ViewGroup)
            .addView(warming, 0)
        stageEntrance()
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
            prefs.edit().putString("url", u).putString("model", m ?: "hermes-agent").putString("key", (SecureStore.encrypt(k) ?: k)).apply()
        }
        if (!say.isNullOrBlank()) { intent.removeExtra("say"); autoSend = say }
    }
    private var autoSend: String? = null

    // Hands-free Realtime / Enhanced Realtime: auto-open the voice line (VAD +
    // barge-in) once the session is live + mic permission granted. Walkie is
    // explicit (hold to talk), so it's skipped. idempotent (lineOpen).
    private fun autoOpenLine() {
        val mode = prefs.getString(ModelCatalog.KEY_VOICE_MODE, ModelCatalog.MODE_REALTIME) ?: ModelCatalog.MODE_REALTIME
        if (mode == ModelCatalog.MODE_WALKIE) return
        val s = session ?: return
        if (lineOpen) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        lineOpen = true
        // Start the mic foreground service so the process + voice line survive
        // Android backgrounding / process killing (always-on).
        try { VoiceService.start(this) } catch (_: Exception) {}
        acquireVoiceWake()
        val c = controller ?: VoiceController(this, s).also { controller = it }
        c.attachListeners(listener)
        if (lineOpen) return
        val sttInstalled = ModelCatalog.isInstalled(this, ModelCatalog.DEFAULT_STT_MODEL)
        if (sttInstalled && !c.isWarm()) {
            // Models still loading: show the splash, re-check, don't open the mic.
            warming.visibility = android.view.View.VISIBLE
            status.text = "Warming up\u2026"
            mainHandler.postDelayed({ if (!isFinishing) autoOpenLine() }, 500)
            return
        }
        warming.visibility = android.view.View.GONE
        c.start(listener, prefs.getBoolean("duplex", true) && modeIsRealtime())
    }

    // Hold a wake lock while the hands-free line is open so it isn't Dozed.
    private var voiceWake: android.os.PowerManager.WakeLock? = null
    private fun acquireVoiceWake() {
        if (voiceWake?.isHeld == true) return
        try {
            val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            voiceWake = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "hermesvox:voice")
            voiceWake?.acquire()
        } catch (_: Exception) {}
    }

    // The entity API key is encrypted at rest (Keystore); legacy plaintext decrypts as-is.
    private fun storedKey() = SecureStore.decrypt(prefs.getString("key", "").orEmpty()).orEmpty()

    private fun connectFromPrefs() {
        val u = prefs.getString("url", "").orEmpty()
        val k = storedKey()
        val m = prefs.getString("model", "hermes-agent").orEmpty()
        if (u.isBlank() || k.isBlank()) return
        session = HermesSession(u, k, m)
        status.text = getString(R.string.hv_connected)
        // The header shows the agent's name (the Hermes profile name, or the name
        // entered in onboarding) — center-top, with the status pill beneath.
        agentName.text = prefs.getString("agent_name", "").orEmpty().ifBlank { m }.uppercase()
        appendStream("// connected → $u")
        // Auto-send a routed turn (E2E proof path).
        autoSend?.let { send(it) }
        autoOpenLine()
    }

    private fun wireButtons() {
        findViewById<Button>(R.id.send).setOnClickListener { send(input.text.toString()) }
        findViewById<Button>(R.id.mic).setOnTouchListener { v, ev ->
            if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) { talk(); v.isPressed = true }
            else if (ev.actionMasked == android.view.MotionEvent.ACTION_UP) { v.isPressed = false; controller?.commitUtterance() }
            else if (ev.actionMasked == android.view.MotionEvent.ACTION_CANCEL) { v.isPressed = false; controller?.commitUtterance() }
            true
        }
        findViewById<Button>(R.id.settings).setOnClickListener { openSettings() }
        findViewById<Button>(R.id.realtime).setOnClickListener { toggleRealtimeMode() }
        findViewById<Button>(R.id.commands).setOnClickListener { showCommands() }
        input.setOnEditorActionListener { _, _, _ -> send(input.text.toString()); true }
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.walkie_voice_toggle)
            .setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("speak_responses", on).apply() }
    }

    private fun modeIsRealtime() = (prefs.getString(ModelCatalog.KEY_VOICE_MODE, ModelCatalog.MODE_REALTIME) ?: ModelCatalog.MODE_REALTIME) != ModelCatalog.MODE_WALKIE

    private fun send(text: String) {
        val s = session ?: run { status.text = "Connect first"; return }
        if (text.isBlank()) return
        input.text.clear()
        appendConvo("You: $text")
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
        val duplex = prefs.getBoolean("duplex", true) && modeIsRealtime()
        c.start(listener, duplex)
    }

    private val listener = object : VoiceController.Listener {
        override fun onState(state: String) {
            runOnUiThread {
                if (state != "thinking") toolCount = 0   // a new turn begins
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
            replyBuf += text; reply.setText(replyBuf)
        } }
        override fun onLog(line: String) { runOnUiThread {
            appendStream(line)
            if (line.startsWith("◆ tool: ")) {
                // a tool was CALLED — the being gathers into the tool's motif + ramps
                toolCount++
                val nm = line.removePrefix("◆ tool: ").substringBefore('{').substringBefore(' ').trim()
                avatar.onTool(mapTool(nm), minOf(1f, toolCount * 0.3f))
                // phone-call presence: Gemma narrates the work (Hermes preempts on the real reply)
                if (prefs.getBoolean("presence", true)) {
                    orch.onWorkNarration()?.let { glue ->
                        status.text = glue
                        controller?.speakGlue(glue)
                    }
                }
            } else if (line.startsWith("◆ tool · ")) {
                avatar.pulseTool()   // a tool RESULT landed — brief work pulse
            }
        } }
        override fun onReply(finalText: String) { runOnUiThread { replyBuf = finalText; reply.setText(replyBuf); appendConvo("Agent: $finalText") } }
        override fun onError(msg: String) { runOnUiThread {
            status.text = if (msg.contains("interrupt")) "You interrupted" else msg
            avatar.setState("idle"); appendStream("// $msg")
        } }
    }

    // SSE tool name -> being shape motif (null = default vortex/compile gyre).
    private fun mapTool(name: String): String? = when {
        name.contains("terminal") || name.contains("shell") || name.contains("exec") -> "shell"
        name.contains("web") || name.contains("search") || name.contains("extract") -> "web"
        name.contains("file") || name.contains("write") || name.contains("read") || name.contains("search_files") -> "file"
        name.contains("memory") || name.contains("recall") || name.contains("ragamuffin") -> "memory"
        name.contains("download") || name.contains("model") -> "download"
        else -> null
    }

    private fun openSettings() { startActivity(Intent(this, SettingsActivity::class.java)); overridePendingTransition(R.anim.slide_in, R.anim.fade_out) }

    /** Expose the Hermes instance's /commands. For the MVP a curated set; the
     *  live command list is a follow-up (query the gateway). */
    private fun showCommands() {
        val cmds = arrayOf(
            "/clear", "/reset", "/status", "/models", "/help")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Commands")
            .setItems(cmds) { _, w ->
                val cmd = cmds[w]
                if (cmd == "/clear" || cmd == "/reset") {
                    controller?.stop(); controller = null
                    session?.resetConversation()
                    input.text.clear(); replyBuf = ""; reply.setText("")
                    avatar.setState("idle"); status.text = getString(R.string.hv_connected)
                } else if (cmd != "/help") {
                    send(cmd)   // let Hermes handle the command
                }
            }
            .show()
    }

    // Bottom "Realtime" button = toggle the Voice-mode (Realtime/Enhanced <-> Walkie),
    // keeping it consistent with the Settings Voice-mode toggle (not a separate screen).
    private fun toggleRealtimeMode() {
        val mode = prefs.getString(ModelCatalog.KEY_VOICE_MODE, ModelCatalog.MODE_REALTIME) ?: ModelCatalog.MODE_REALTIME
        val next = if (mode == ModelCatalog.MODE_WALKIE) ModelCatalog.MODE_REALTIME else ModelCatalog.MODE_WALKIE
        prefs.edit().putString(ModelCatalog.KEY_VOICE_MODE, next).apply()
        applyVoiceMode(); handleModeUi()
    }

    // Presence (default) vs Conversation — user-facing Fork-2 customization
    // (a11y: Conversation gives a persistent, readable transcript instead of the
    // fading crawl). Persisted via Settings layout_mode (presence|conversation).
    private fun applyLayoutMode() {
        val conv = prefs.getString("layout_mode", "presence") == "conversation"
        conversation.visibility = if (conv) View.VISIBLE else View.GONE
        reply.visibility = if (conv) View.GONE else View.VISIBLE
        if (conv) { convoText.text = convoBuf; conversation.post { conversation.scrollTo(0, conversation.bottom) } }
    }
    private fun appendConvo(line: String) {
        convoBuf = (convoBuf + "\n" + line).trim().takeLast(4000)
        if (conversation.visibility == View.VISIBLE) { convoText.text = convoBuf; conversation.post { conversation.scrollTo(0, conversation.bottom) } }
    }

    // Voice mode: Realtime / Enhanced Realtime (hands-free open line + keyboard)
    // vs Walkie Talkie (PTT + SEND buttons). Per Christopher, 2026-08-26.
    private fun applyVoiceMode() {
        val mode = prefs.getString(ModelCatalog.KEY_VOICE_MODE, ModelCatalog.MODE_REALTIME) ?: ModelCatalog.MODE_REALTIME
        val walkie = mode == ModelCatalog.MODE_WALKIE
        findViewById<View>(R.id.mic).visibility = if (walkie) View.VISIBLE else View.GONE
        findViewById<View>(R.id.send).visibility = if (walkie) View.VISIBLE else View.GONE
        input.visibility = if (walkie) View.VISIBLE else View.GONE
        // bottom "Realtime" button reflects the *other* mode (tapping switches to it)
        findViewById<android.widget.Button>(R.id.realtime).text = if (walkie) "Realtime" else "Walkie"
        // inline voice toggle (walkie): speak responses on/off
        findViewById<View>(R.id.walkie_voice_toggle)?.visibility = if (walkie) View.VISIBLE else View.GONE
        val vs = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.walkie_voice_toggle)
        if (vs != null) { vs.isChecked = prefs.getBoolean("speak_responses", true) }
    }

    private fun appendStream(line: String) {
        if (stream.visibility == View.GONE) return   // dev console off
        sseBuf = (sseBuf + "\n" + line).trim().takeLast(1600)
        stream.setText(sseBuf)
    }

    // Staged entrance: the crawl/controls rise staggered for that alive,
    // "materializing presence" feel. The being is NOT alpha-gated here — it
    // self-animates and must never be hidden by the staging.
    private fun stageEntrance() {
        val rows = listOf<View>(findViewById(R.id.crawl_area), input,
            findViewById(R.id.settings))
        rows.forEachIndexed { i, v ->
            v.alpha = 0f; v.translationY = dp(24f)
            v.animate().alpha(1f).translationY(0f)
                .setStartDelay(140L + i * 90L)
                .setDuration(480L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }
    private fun dp(v: Float) = v * resources.displayMetrics.density + 0.5f

    // The raw SSE console is a dev drawer — hidden unless "Developer console"
    // is toggled in Settings. Keeps the main screen clean + presence-first.
    private fun updateStreamVisibility() {
        stream.visibility = if (prefs.getBoolean("dev_console", false)) View.VISIBLE else View.GONE
    }
    override fun onResume() { super.onResume(); runOnUiThread { updateStreamVisibility(); handleModeUi(); autoOpenLine() } }

    private fun handleModeUi() {
        applyLayoutMode(); applyVoiceMode()
        val mode = prefs.getString(ModelCatalog.KEY_VOICE_MODE, ModelCatalog.MODE_REALTIME) ?: ModelCatalog.MODE_REALTIME
        if (mode == ModelCatalog.MODE_ENHANCED) {
            val g = express as? GemmaExpress
            if (g != null && !g.available) g.load {}   // load the on-device model once
        }
    }

    private fun startAvatarLoop() {
        val tick = object : Runnable {
            override fun run() { avatar.invalidate(); avatar.postDelayed(this, 30) }
        }
        avatar.post(tick)
    }

    /** If a probe.wav is present in the selected STT model dir, transcribe it (proof hook). */
    private fun maybeRunWhisperProbe() {
        val model = prefs.getString(ModelCatalog.KEY_STT_MODEL, ModelCatalog.DEFAULT_STT_MODEL) ?: ModelCatalog.DEFAULT_STT_MODEL
        val probe = java.io.File(filesDir, "models/$model/probe.wav")
        if (!probe.exists()) return
        Thread {
            val text = OfflineWhisperStt.transcribeWave(this, model, probe.absolutePath)
            VoxLog.d("WHISPER PROBE($model) transcript=<$text>")
            runOnUiThread { status.text = "whisper probe($model): ${text ?: "no transcript"}" }
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
