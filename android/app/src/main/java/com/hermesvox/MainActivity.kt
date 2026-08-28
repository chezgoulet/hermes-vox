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
    private val prefs by lazy { getSharedPreferences("hv", Context.MODE_PRIVATE) }
    private var replyBuf = ""
    private var sseBuf = "// stream log — watch the agent work"
    private var toolCount = 0
    private lateinit var conversation: android.widget.ScrollView
    private lateinit var convoText: android.widget.TextView
    private var convoBuf = ""
    private val express: VoxExpress = GemmaExpress(this)
    private val orch = VoiceOrchestrator(express)

    override fun onNewIntent(intent: android.content.Intent?) {
        super.onNewIntent(intent); setIntent(intent); handleDebugHarness(intent)
    }

    /** DEBUG-ONLY harness entry (inert when !isDebuggable). Lets the emulator
     *  stress script configure the session + drive a text turn without fighting
     *  onboarding/IME. NOT reachable in release (debuggable=false), so it does
     *  NOT reopen the #1 intent-injection surface. */
    private fun handleDebugHarness(intent: android.content.Intent?) {
        if (intent == null) return
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        val u = intent.getStringExtra("url"); val k = intent.getStringExtra("key")
        val m = intent.getStringExtra("model"); val text = intent.getStringExtra("text")
        if (!u.isNullOrBlank()) prefs.edit().putString("url", u).putString("model", m ?: "hermes-agent").apply()
        if (!k.isNullOrBlank()) prefs.edit().putString("key", (SecureStore.encrypt(k) ?: k)).apply()
        if (text != null) { connectFromPrefs(); send(text) } else if (!u.isNullOrBlank() && !k.isNullOrBlank()) { connectFromPrefs() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme(prefs.getString("theme", "system")!!)
        super.onCreate(savedInstanceState)
        // defer past onCreate so the Activity views/session are initialized (harness turn)
        mainHandler.postDelayed({ handleDebugHarness(intent) }, 800L)
        active = this
        setContentView(R.layout.activity_main)
        Seq.setContext(applicationContext)
        VoxLog.init(applicationContext)
        CrashLog.init(applicationContext)
        maybeRunWhisperProbe()

        status = findViewById(R.id.status)
        status.visibility = android.view.View.GONE
        agentName = findViewById(R.id.agent_name)
        input = findViewById(R.id.input)
        reply = findViewById(R.id.reply_crawl); reply.setRole("reply")
        stream = findViewById(R.id.stream); stream.setRole("sse")
        avatar = findViewById(R.id.avatar)
        conversation = findViewById(R.id.conversation)
        convoText = findViewById(R.id.convo_text)
        handleModeUi()
        updateStreamVisibility()
        // Tap the presence = STOP (hush): interrupt the reply + cancel the stream
        // and settle to idle/return to listening. The shape/theme selection now
        // lives in Settings (Particles), not on the raw tap.
        avatar.setOnClickListener {
            liveController?.hush()
            avatar.setState("idle")
            setStatus(getString(R.string.hv_connected), false)
        }

        // First run → onboarding (no stored endpoint/key yet).
        if (prefs.getString("url", "").orEmpty().isBlank() || storedKey().isBlank()) {
            openOnboarding(); return
        }

        // Warming splash: created BEFORE the connection flow so autoOpenLine can
        // toggle it. (Originally created after connectFromPrefs, so on a device where
        // the mic permission + models are present autoOpenLine read warming.visibility
        // before it was initialized -> UninitializedPropertyAccessException -> the app
        // would not open at all. Cold-launch regression was 0.3.8.)
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

        connectFromPrefs()
        resumeLiveCallIfAny()
        wireButtons()
        startAvatarLoop()
        applyParticlePrefs()
        stageEntrance()
    }

    private fun openOnboarding() {
        startActivity(Intent(this, OnboardingActivity::class.java))
        finish()
    }

    private var warmRetries = 0
    @Volatile private var callLive = false
    private var callSeconds = 0
    private val callHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val callTicker = object : Runnable {
        override fun run() {
            if (!callLive) return
            callSeconds = ((android.os.SystemClock.elapsedRealtime() - callStartedAt) / 1000L).toInt()
            val mm = callSeconds / 60; val ss = callSeconds % 60
            findViewById<android.widget.TextView>(R.id.call_timer)?.text = String.format("%02d:%02d", mm, ss)
            callHandler.postDelayed(this, 1000)
        }
    }

    // Hands-free Realtime / Enhanced Realtime: auto-open the voice line (VAD +
    // barge-in) once the session is live + mic permission granted. Walkie is
    // explicit (hold to talk), so it's skipped. idempotent (lineOpen).
    /** Start the real-time call: warm + open the continuous voice line and set the
     *  live-call UI (red hang-up button + running timer). The call PERSISTS across
     *  app-close / screen-off (the mic-type foreground service + loop keep running). */
    private fun startCall() {
        val s = session ?: run { setStatus("Connect first", true); return }
        if (callLive) return
        s.resetConversation()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (!callStartPending) {
                callStartPending = true
                val rationale = ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)
                setStatus(if (rationale) "Mic needs to be enabled to start the call" else "Mic permission needed to start the call", true)
                val needNotif = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                // Mirror the working talk() path (lines 333-344): RECORD_AUDIO always
                // requested for the mic-type foreground service; POST_NOTIFICATIONS only
                // when missing (a fg-service notification renders for Android 13+).
                ActivityCompat.requestPermissions(this,
                    listOfNotNull(
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.POST_NOTIFICATIONS.takeIf { needNotif }
                    ).toTypedArray(), REQ_MIC_CALL)
            }
            return
        }
        val c = liveController ?: VoiceController(applicationContext, s).also { liveController = it }
        c.attachListeners(listener)
        if (!ModelCatalog.isInstalled(this, ModelCatalog.DEFAULT_STT_MODEL)) {
            setStatus("Voice model not installed — Settings > Voice models", true); return
        }
        if (!c.isWarm()) {
            if (warmRetries++ % 10 == 0) VoxLog.d("warm-wait retry=${warmRetries} ${c.warmDiagnostics()}")
            if (warmRetries < 180) {
                if (::warming.isInitialized) warming.visibility = android.view.View.VISIBLE
                setStatus("Warming up\u2026", true)
                mainHandler.postDelayed({ if (!isFinishing) startCall() }, 500)
                return
            }
            warmRetries = 0
            if (::warming.isInitialized) warming.visibility = android.view.View.GONE
            VoxLog.e("warm: models never loaded after ~90s (${c.warmDiagnostics()})")
            setStatus("Voice models failed to load", true)
            return
        }
        warmRetries = 0
        if (::warming.isInitialized) warming.visibility = android.view.View.GONE
        // MIC-TYPE FOREGROUND SERVICE keeps the process + the loop alive after the app
        // is closed / the screen is off, so a live call persists. It does NOT own a
        // second liveController (single-owner). Started BEFORE the loop so no onStop can
        // land in a half-started window (callLive is true before the loop opens).
        try { VoiceService.start(this) } catch (_: Exception) {}
        acquireVoiceWake()
        callStartedAt = android.os.SystemClock.elapsedRealtime()
        callLive = true; callSeconds = 0
        c.continuous = true
        c.start(listener, prefs.getBoolean("duplex", true) && modeIsRealtime())
        enterCallUi()
        setStatus("On call", false)
    }

    /** Hang up: stop the voice line + the foreground service, reset the UI. */
    private fun endCall() {
        callLive = false
        callHandler.removeCallbacks(callTicker)
        liveController?.stop(); liveController = null
        stopVoiceWake()
        VoiceService.stop(this)
        exitCallUi()
        setStatus(getString(R.string.hv_connected), false)
        avatar.setState("idle")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC_CALL) {
            callStartPending = false
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startCall()   // mic granted -> the call proceeds (warm + open the line)
            } else {
                setStatus("Mic permission denied — tap call to retry", true)
            }
        } else if (requestCode == 100) {
            // talk() path: previously it just returned; now proceed on grant so a
            // single tap grants + opens the walkie line.
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                talk()
            } else {
                setStatus("Mic permission denied", true)
            }
        }
    }

    /** If a call is still live (started, not hung up), re-arm the loop on resume. */
    private fun resumeLiveCallIfAny() {
        val c = liveController
        if (c == null || callLive || callStartedAt == 0L) return
        // The loop may have died while backgrounded (the teardown window / a stopped
        // turn). Re-arm it rather than only reflecting isListening(); c.start is
        // idempotent, so a loop that is still running is left untouched.
        c.continuous = true
        c.start(listener, prefs.getBoolean("duplex", true) && modeIsRealtime())
        callLive = true
        callSeconds = ((android.os.SystemClock.elapsedRealtime() - callStartedAt) / 1000L).toInt().coerceAtLeast(0)
        enterCallUi()
        setStatus("On call", false)
    }

    private fun enterCallUi() {
        findViewById<android.widget.TextView>(R.id.call_timer)?.text = String.format("%02d:%02d", callSeconds / 60, callSeconds % 60)
        findViewById<android.widget.TextView>(R.id.call_timer)?.visibility = android.view.View.VISIBLE
        callHandler.postDelayed(callTicker, 1000)
        updateCallButton()
        setCallTone(true)
    }
    private fun exitCallUi() {
        callHandler.removeCallbacks(callTicker)
        findViewById<android.widget.TextView>(R.id.call_timer)?.visibility = android.view.View.GONE
        updateCallButton()
        setCallTone(false)
    }

    private fun updateCallButton() {
        val b = findViewById<Button>(R.id.call) ?: return
        if (callLive) { b.text = "\u2706"; b.setTextColor(0xFFFF5B5B.toInt()); b.contentDescription = "Hang up call" }
        else { b.text = "\u2706"; b.setTextColor(0xFF35D07F.toInt()); b.contentDescription = "Start call" }
    }
    private fun setStatus(text: String, show: Boolean) {
        status.text = text
        status.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun setCallTone(live: Boolean) {
        try { status.setTextColor(if (live) 0xFF35D07F.toInt() else 0xFFD6F4FF.toInt()) } catch (_: Throwable) {}
    }
    private fun stopVoiceWake() {
        try { voiceWake?.release() } catch (_: Exception) {}
        voiceWake = null
    }
    private fun modeIsEnhanced() =
        (prefs.getString(ModelCatalog.KEY_VOICE_MODE, ModelCatalog.MODE_REALTIME) ?: ModelCatalog.MODE_REALTIME) == ModelCatalog.MODE_ENHANCED

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

    // App-scoped call start time so a live call's timer survives activity recreation.
    companion object {
        // Single canonical HermesSession: MainActivity, the live controller, and
        // Settings all share THIS instance so a reset reaches the same conversation.
        @Volatile var session: HermesSession? = null
        @Volatile private var sesUrl: String? = null
        @Volatile private var sesKey: String? = null
        @Volatile private var sesModel: String? = null
        @Volatile private var active: MainActivity? = null
        @Volatile var callStartedAt = 0L
        @Volatile var liveController: VoiceController? = null
        // #61: dedicated call-start permission request code + pending flag so the
        // warm-retry path never re-requests while the dialog is up / after denial.
        const val REQ_MIC_CALL = 101
        @Volatile var callStartPending = false

        /** Reset the canonical session's conversation and clear the display/reply
         *  buffers. Reaches the LIVE session (MainActivity + live controller), so a
         *  Settings "New conversation" clears the same context an active call sees. */
        fun resetActiveConversation() {
            session?.resetConversation()
            liveController?.stop()
            liveController = null
            active?.clearConversationUi()
        }
    }

    private fun clearConversationUi() {
        input.text.clear()
        replyBuf = ""
        reply.setText("")
        convoBuf = ""
        convoText.setText("")
        avatar.setState("idle")
        setStatus(getString(R.string.hv_connected), false)
    }

    private fun connectFromPrefs() {
        val u = prefs.getString("url", "").orEmpty()
        val k = storedKey()
        val m = prefs.getString("model", "hermes-agent").orEmpty()
        if (u.isBlank() || k.isBlank()) return
        if (session == null || sesUrl != u || sesKey != k || sesModel != m) {
            session = HermesSession(u, k, m)
            sesUrl = u; sesKey = k; sesModel = m
        }
        setStatus(getString(R.string.hv_connected), false)
        // The header shows the agent's name (the Hermes profile name, or the name
        // entered in onboarding) — center-top, with the status pill beneath.
        agentName.text = prefs.getString("agent_name", "").orEmpty().ifBlank { m }.uppercase()
        appendStream("// connected → $u")
        // No auto-open: real-time/enhanced starts ONLY on the call button. A live call
        // (surviving an app-close) is detected on create and resumed below.
    }

    private fun wireButtons() {
        findViewById<Button>(R.id.send).setOnClickListener { send(input.text.toString()) }
        findViewById<Button>(R.id.mic).setOnTouchListener { v, ev ->
            if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) { talk(); v.isPressed = true }
            else if (ev.actionMasked == android.view.MotionEvent.ACTION_UP) { v.isPressed = false; liveController?.commitUtterance() }
            else if (ev.actionMasked == android.view.MotionEvent.ACTION_CANCEL) { v.isPressed = false; liveController?.commitUtterance() }
            true
        }
        findViewById<Button>(R.id.settings).setOnClickListener { openSettings() }
        findViewById<Button>(R.id.realtime).setOnClickListener { toggleRealtimeMode() }
        findViewById<Button>(R.id.call).setOnClickListener { if (callLive) endCall() else startCall() }
        findViewById<Button>(R.id.commands).setOnClickListener { showCommands() }
        input.setOnEditorActionListener { _, _, _ -> send(input.text.toString()); true }
        findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.walkie_voice_toggle)
            .setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("speak_responses", on).apply() }
    }

    private fun modeIsRealtime() = (prefs.getString(ModelCatalog.KEY_VOICE_MODE, ModelCatalog.MODE_REALTIME) ?: ModelCatalog.MODE_REALTIME) != ModelCatalog.MODE_WALKIE

    private fun send(text: String) {
        val s = session ?: run { setStatus("Connect first", true); return }
        if (text.isBlank()) return
        input.text.clear()
        appendConvo("You: $text")
        val c = liveController ?: VoiceController(applicationContext, s).also {
            liveController = it
            s.resetConversation()
        }
        c.attachListeners(listener)
        c.sendText(text)
    }

    private fun talk() {
        val s = session ?: run { setStatus("Connect first", true); return }
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
        // (microphone type; START_STICKY). The activity liveController drives the UI.
        VoiceService.start(this)
        val c = liveController ?: VoiceController(applicationContext, s).also {
            liveController = it
            s.resetConversation()
        }
        val duplex = prefs.getBoolean("duplex", true) && modeIsRealtime()
        c.continuous = false   // Walkie PTT: one turn, then stop until the next push
        c.start(listener, duplex)
    }

    private val listener = object : VoiceController.Listener {
        override fun onState(state: String) {
            runOnUiThread {
                if (state != "thinking") toolCount = 0   // a new turn begins
                setStatus(when (state) {
                    "listening" -> "Listening…"
                    "thinking" -> "The entity is working…"
                    "speaking" -> "Speaking…"
                    else -> getString(R.string.hv_connected)
                }, false)
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
                        setStatus(glue, false)
                        // Narration split: real-time signals (quiet/visual); only enhanced
                        // voices the mid-work chatter (Gemma presence).
                        if (modeIsEnhanced()) liveController?.speakGlue(glue)
                    }
                }
            } else if (line.startsWith("◆ tool · ")) {
                avatar.pulseTool()   // a tool RESULT landed — brief work pulse
            }
        } }
        override fun onReply(finalText: String) { runOnUiThread { replyBuf = finalText; reply.setText(replyBuf); appendConvo("Agent: $finalText") } }
        override fun onError(msg: String) { runOnUiThread {
            setStatus(if (msg.contains("interrupt")) "You interrupted" else msg, !msg.contains("interrupt"))
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
                    liveController?.stop(); liveController = null
                    session?.resetConversation()
                    input.text.clear(); replyBuf = ""; reply.setText("")
                    avatar.setState("idle"); setStatus(getString(R.string.hv_connected), false)
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
        val realtimeLike = !walkie
        findViewById<android.view.View>(R.id.call).visibility = if (realtimeLike) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<android.view.View>(R.id.realtime).visibility = if (walkie) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<android.widget.Button>(R.id.realtime).text = "Realtime"
        updateCallButton()
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
    override fun onResume() { super.onResume(); runOnUiThread { updateStreamVisibility(); handleModeUi(); applyParticlePrefs(); resumeLiveCallIfAny() } }

    private fun handleModeUi() {
        applyLayoutMode(); applyVoiceMode()
        val mode = prefs.getString(ModelCatalog.KEY_VOICE_MODE, ModelCatalog.MODE_REALTIME) ?: ModelCatalog.MODE_REALTIME
        if (mode == ModelCatalog.MODE_ENHANCED) {
            val g = express as? GemmaExpress
            if (g != null && !g.available) g.load {}   // load the on-device model once
        }
    }

    // Presence appearance: idle shape/theme + auto-cycle (Settings -> Particles).
    private fun applyParticlePrefs() {
        val theme = prefs.getString("particles_theme", "aura") ?: "aura"
        avatar.setIdleTheme(theme)
        avatar.setCycleThemes(prefs.getBoolean("particles_cycle", true))
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
            runOnUiThread { setStatus("whisper probe($model): ${text ?: "no transcript"}", true) }
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
        // A live call persists (foreground service keeps the loop + process alive), so we
        // do NOT stop the liveController here. Stop only when there's no active call.
        if (!callLive) {
            liveController?.stop()
            liveController = null   // #19: stopped controller is terminal (exec shut down) — never re-arm it
            VoiceService.stop(this)
        }
    }

    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }
}
