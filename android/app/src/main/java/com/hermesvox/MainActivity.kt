package com.hermesvox

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.hermesvox.mobile.HermesSession
import go.Seq

/**
 * MainActivity — the front-of-house surface. Hosts the avatar, the live reply,
 * the stream console, and the hands-free voice call. Runs the entity via
 * VoiceController (streamed SSE turns) and renders the entity's real work.
 * First run routes to OnboardingActivity; subsequent launches auto-connect.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var warming: android.widget.TextView
    // #6/#12: voice models are a first-run REQUIREMENT (nothing works without
    // them). modelsGate = the blocking empty-state overlay shown when NONE of
    // the required set is installed; modelsWarnShown tracks the tappable
    // warning pill shown when it is only partially installed.
    private var modelsGate: android.widget.LinearLayout? = null
    private var modelsWarnShown = false
    // #120-C: the one-time first-run coach overlay (dismissed -> seen pref).
    private var coachOverlay: android.view.View? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var agentName: TextView
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

    // ---- 0.5.0-previewA: the speech-locked transcript reveal (display only) ----
    // The crawl used to be painted straight from onDelta, i.e. at the speed the SSE
    // stream ARRIVES (a whole reply in ~1s) while Piper speaks it over 8-15s. While the
    // state is "speaking" this short-lived loop repaints instead from the controller's
    // speech cursor — the AudioTrack playback head — so the bright/dim boundary is the
    // word the entity is actually saying, and the tail you can see is visibly unsaid.
    private var revealActive = false
    private var revealStartedAt = 0L
    private var revealEpoch = 0
    private val revealTask = object : Runnable {
        override fun run() {
            if (!revealActive) return
            val c = liveController
            if (c != null) {
                val spoken = c.speechCursor()
                if (spoken >= 0) {
                    val txt = c.transcriptText()
                    // Blank = nothing has reached the engine yet (one-shot synth in
                    // flight): leave whatever is on screen rather than flashing empty.
                    if (txt.isNotBlank()) reply.setText(txt, spoken)
                } else {
                    reply.setText(replyBuf)   // system TTS: no sample accounting, no lock
                }
            }
            mainHandler.postDelayed(this, REVEAL_TICK_MS)
        }
    }

    private fun startReveal() {
        revealEpoch++                       // cancels any pending minRevealMs stop
        if (revealActive) return
        revealActive = true
        revealStartedAt = android.os.SystemClock.uptimeMillis()
        mainHandler.post(revealTask)
    }

    /** Stop revealing. minRevealMs: a very short reply would otherwise engage and tear
     *  the loop down inside a frame or two (plain -> dim -> plain stutter), so the loop
     *  is held for that floor before it hands the surface back. */
    private fun stopReveal() {
        if (!revealActive) return
        val held = android.os.SystemClock.uptimeMillis() - revealStartedAt
        if (held < MIN_REVEAL_MS) {
            val e = revealEpoch
            mainHandler.postDelayed({ if (e == revealEpoch) stopReveal() }, MIN_REVEAL_MS - held)
            return
        }
        revealActive = false
        mainHandler.removeCallbacks(revealTask)
        val c = liveController
        if (c != null && c.speechFrozen()) {
            // Cut (hush/barge/stop): the boundary stays exactly where the voice stopped —
            // the unspoken tail is left dim and is NEVER auto-completed.
            val txt = c.transcriptText()
            if (txt.isNotBlank()) reply.setText(txt, c.speechCursor())
        } else {
            reply.setText(replyBuf)   // spoken through to the end -> the full reply, plainly
        }
    }

    // ---- 0.5.0-previewB: the presence-motion scheduler -----------------------
    // MotionState decides WHAT the being is doing; this is the only place the live
    // signals (state, deltas, tools, cuts, the real voice level, provider silence)
    // are turned into that decision. No new animation loop: the existing avatar tick
    // calls motionTick(), which refreshes the drive params on the frame clock.
    private var motion = MotionState.Motion.IDLE
    private var recoilPrior = MotionState.Motion.IDLE   // what the flinch interrupted
    private var recoilUntil = 0L                        // the RECOIL one-shot window
    private var lastSignalAt = 0L                       // last real stream/voice activity
    private var lastVoiceAt = 0L                        // last tick with audible audio
    private var motionStalled = false
    private var motionTool: String? = null
    // A signal that arrived DURING the flinch. bargeIn() posts onState("listening")
    // within a few ms of the cut — well inside the 350ms window — so dropping it
    // would strand the being in the motion it was interrupted out of.
    private var recoilPending: MotionState.Signal? = null

    /** Run one signal through the pure table and render the result. The RECOIL window
     *  is held HERE, not in the table: a pure rule has no clock, so the caller owns the
     *  ~350ms one-shot and the return to whatever the user interrupted. */
    private fun feed(sig: MotionState.Signal) {
        val now = android.os.SystemClock.uptimeMillis()
        // Nothing but another cut may speak over the flinch while it is running — but
        // the signal is HELD, not lost, and lands the moment the window is spent.
        if (motion == MotionState.Motion.RECOIL && now < recoilUntil &&
            sig != MotionState.Signal.BARGE) { recoilPending = sig; return }
        if (sig == MotionState.Signal.BARGE) {
            if (motion != MotionState.Motion.RECOIL) recoilPrior = motion
            recoilUntil = now + MotionState.RECOIL_MS
            recoilPending = null
        }
        motion = MotionState.transition(motion, sig)
        renderMotion()
    }

    /** Any evidence the turn is alive: an SSE delta, a console line, a state change,
     *  audible audio. Resets the stall watch and lifts a stall that is showing. */
    private fun markActivity() {
        lastSignalAt = android.os.SystemClock.uptimeMillis()
        if (motionStalled) setMotionStall(false, 0L)
    }

    /** The stall lever. The avatar holds the prior motion and renders the waiting
     *  constellation; the table keeps MainActivity's own motion in step so the
     *  repeating ambient "thinking" signals cannot quietly clear the wait. */
    private fun setMotionStall(on: Boolean, idleMs: Long) {
        if (on == motionStalled) { if (on) avatar.setStall(true, idleMs); return }
        // Nothing in flight -> a stall is meaningless; the table is the judge.
        if (on && MotionState.transition(motion, MotionState.Signal.STALL_ON) != MotionState.Motion.STALL) return
        motionStalled = on
        avatar.setStall(on, idleMs)   // captures/restores the prior motion first
        feed(if (on) MotionState.Signal.STALL_ON else MotionState.Signal.RESUME)
    }

    private fun renderMotion() =
        avatar.applyMotion(motion, liveController?.speechLevel() ?: 0f, motionWorkload(), motionTool)

    /** Effort intensity: how deep into tool work this turn has gone. */
    private fun motionWorkload(): Float = minOf(1f, toolCount * 0.3f)

    /** Called from the avatar's existing frame tick — the ONLY clock in the motion
     *  layer. Expires the recoil, watches for provider silence, and re-reads the real
     *  voice level so the speaking motion tracks the syllable rather than a constant. */
    private fun motionTick() {
        val now = android.os.SystemClock.uptimeMillis()
        val level = liveController?.speechLevel() ?: 0f
        if (motion == MotionState.Motion.RECOIL) {
            if (now < recoilUntil) { avatar.driveMotion(level, motionWorkload()); return }
            motion = recoilPrior              // the one-shot is spent: back to the work
            val held = recoilPending          // ...and whatever it interrupted lands now
            recoilPending = null
            if (held != null) feed(held) else renderMotion()
        }
        // The streamed path never emits state "speaking" (only the one-shot speak()
        // does), so the voice is detected the way the 0.5.0-A reveal detects it: real
        // audio on the track. A short hold bridges the synth gaps between phrases.
        if (level > 0f) { lastVoiceAt = now; markActivity() }
        val voicing = lastVoiceAt > 0L && now - lastVoiceAt < VOICE_HOLD_MS
        if (voicing && motion != MotionState.Motion.SPEAKING) feed(MotionState.Signal.SPEAK)
        val idle = now - lastSignalAt
        // Presence-level stall watch. Deliberately NOT armed while the voice is
        // audible: a long reply streams in ~1s and is then SPOKEN for 10-15s with no
        // further SSE traffic, which is not a stall — that is the being working.
        val watching = !voicing && (motion == MotionState.Motion.THINKING ||
                motion == MotionState.Motion.TOOL || motion == MotionState.Motion.TOOL_RESULT)
        if (motionStalled) avatar.setStall(true, idle)          // deepen the wait as it runs
        else if (watching && lastSignalAt > 0L && idle >= MotionState.STALL_MS) setMotionStall(true, idle)
        else if (!voicing && idle >= QUIET_MS) {
            // Nothing at all for this long: whatever the being was doing is over. A hush
            // that never re-listened, or a dropped turn, would otherwise hold its last
            // motion forever — come to rest first, then widen into the drift.
            if (motion != MotionState.Motion.IDLE && motion != MotionState.Motion.DRIFT) feed(MotionState.Signal.REST)
            else if (motion != MotionState.Motion.DRIFT) feed(MotionState.Signal.QUIET)
        }
        // The per-frame refresh: drive values only. The one-shot setters (onTool's
        // re-seed, pulseTool's ramp) belong to the edges above, never to the frame.
        avatar.driveMotion(level, motionWorkload())
    }

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
            feed(MotionState.Signal.BARGE)   // previewB: it heard you — recoil, then settle
            setStatus(getString(R.string.hv_connected), false)
        }

        // First run → onboarding (no stored endpoint yet).
        if (prefs.getString("url", "").orEmpty().isBlank()) {
            openOnboarding(); return
        }
        // C0: endpoint set but no user-entered key -> main screen shows the clear
        // Settings prompt instead of silently connecting with no key (there is no
        // baked fallback). connectFromPrefs/send/talk/startCall re-surface it.
        if (GatewayKey.isMissing(storedKey())) setStatus(GatewayKey.MISSING_KEY_PROMPT, true)

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
        refreshModelsGate()   // #6/#12: first-run empty state / warning pill
        wireButtons()
        startAvatarLoop()
        applyParticlePrefs()
        stageEntrance()
        // #120-C: first-run coach marks once the entrance has staged. Self-gated
        // (seen pref / models gate / live call) inside maybeShowCoachMarks().
        mainHandler.postDelayed({ maybeShowCoachMarks() }, 1000L)
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

    /** Start the real-time call: warm + open the continuous voice line and set the
     *  live-call UI (red hang-up button + running timer). The call PERSISTS across
     *  app-close / screen-off (the mic-type foreground service + loop keep running). */
    private fun startCall() {
        if (missingKeyPrompt()) return   // C0: empty key -> clear Settings prompt
        val s = session ?: run { setStatus("Connect first", true); return }
        if (callLive) return
        s.resetConversation()
        LatencyStats.resetSessionTurns()   // C3: a new call is a fresh session (session_turns counts from here)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (!callStartPending) {
                callStartPending = true
                val rationale = ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)
                setStatus(if (rationale) "Mic needs to be enabled to start the call" else "Mic permission needed to start the call", true)
                val needNotif = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
                // RECORD_AUDIO is always requested for the mic-type foreground service;
                // POST_NOTIFICATIONS only when missing (a fg-service notification renders
                // for Android 13+).
                ActivityCompat.requestPermissions(this,
                    listOfNotNull(
                        Manifest.permission.RECORD_AUDIO,
                        Manifest.permission.POST_NOTIFICATIONS.takeIf { needNotif }
                    ).toTypedArray(), REQ_MIC_CALL)
            }
            return
        }
        openVoiceLine(s)
    }

    /** Warm-gated "open the voice line" composition — shared by a fresh call start
     *  and the C3 route-change rebuild (both are start()/stop() compositions; no
     *  new engine code). The caller has already done the reset/permission checks. */
    private fun openVoiceLine(s: HermesSession) {
        val c = liveController ?: VoiceController(applicationContext, s).also { liveController = it }
        c.attachListeners(listener)
        if (!ModelCatalog.isInstalled(this, ModelCatalog.DEFAULT_STT_MODEL)) {
            // #12: this used to be a dead label ("...Settings > Voice models").
            // It is now a tappable warning pill that opens the Voice-models
            // download screen (ModelsActivity) — the actual fix, not a name.
            modelsMissingPill("Voice model not installed — tap to download")
            return
        }
        if (!c.isWarm()) {
            if (warmRetries++ % 10 == 0) VoxLog.d("warm-wait retry=${warmRetries} ${c.warmDiagnostics()}")
            if (warmRetries < 180) {
                if (::warming.isInitialized) warming.visibility = android.view.View.VISIBLE
                // LOCAL pipeline load — this and only this is "Warming up".
                warmingNow = true
                showPhase()
                mainHandler.postDelayed({ if (!isFinishing) openVoiceLine(s) }, 500)
                return
            }
            warmRetries = 0
            warmingNow = false
            if (::warming.isInitialized) warming.visibility = android.view.View.GONE
            VoxLog.e("warm: models never loaded after ~90s (${c.warmDiagnostics()})")
            setStatus("Voice models failed to load", true)
            return
        }
        // Warmth just completed. THIS is the moment the gateway may honestly be
        // tested (B2c): re-dial now, so the pill moves Warming up -> Dialing ->
        // Connected instead of sitting on a single sticky word. The line opens
        // immediately underneath — the dial reports, it does not gate.
        val wasWarming = warmingNow
        warmingNow = false
        warmRetries = 0
        if (wasWarming) dialGateway()
        if (::warming.isInitialized) warming.visibility = android.view.View.GONE
        // MIC-TYPE FOREGROUND SERVICE keeps the process + the loop alive after the app
        // is closed / the screen is off, so a live call persists. It does NOT own a
        // second liveController (single-owner). Started BEFORE the loop so no onStop can
        // land in a half-started window (callLive is true before the loop opens).
        VoiceService.start(this)
        acquireVoiceWake()
        // C3 H1: request audio focus the moment the wake lock is acquired (same
        // lifecycle); abandon runs beside every wake-lock release below. Focus is
        // process-lifetime once granted, so a rebuild/route change re-entry is a no-op.
        acquireVoiceFocus()
        // C3 H2: route changes (BT/USB/wired headset) mid-call rebuild capture+playback
        // so both halves re-attach to the new default route. Registered per call open.
        registerRouteCallback()
        callStartedAt = android.os.SystemClock.elapsedRealtime()
        callLive = true; callSeconds = 0
        c.setVoiceChannelOpen(true)   // the voice channel is open -> replies may speak
        c.start(listener, prefs.getBoolean("duplex", true))
        enterCallUi()
        setStatus("On call", false)
    }

    /** Hang up: stop the voice line + the foreground service, reset the UI. */
    private fun endCall() {
        VoxLog.d("event=call-end callLive=$callLive")
        callLive = false
        mainHandler.removeCallbacks(routeRebuildTask)
        callHandler.removeCallbacks(callTicker)
        liveController?.setVoiceChannelOpen(false)
        liveController?.stop(); liveController = null
        stopVoiceWake()
        releaseVoiceFocus()        // C3: abandon symmetrically with the wake-lock release
        unregisterRouteCallback()  // C3: route callback lives exactly as long as the call
        VoiceService.stop(this)
        exitCallUi()
        setStatus(getString(R.string.hv_connected), false)
        feed(MotionState.Signal.REST)
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
        }
    }

    /** If a call is still live (started, not hung up), re-arm the loop on resume. */
    private fun resumeLiveCallIfAny() {
        val c = liveController
        if (c == null || callLive || callStartedAt == 0L) return
        // The loop may have died while backgrounded (the teardown window / a stopped
        // turn). Re-arm it rather than only reflecting isListening(); c.start is
        // idempotent, so a loop that is still running is left untouched.
        c.setVoiceChannelOpen(true)
        c.start(listener, prefs.getBoolean("duplex", true))
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
        // A real phase/status replaces the models-warning pill: drop its tap
        // handler and restore the tag color so phase pills never read as
        // warnings or navigate to the model store by accident (#12/#6).
        if (modelsWarnShown) {
            modelsWarnShown = false
            status.setOnClickListener(null)
            status.setTextColor(ContextCompat.getColor(this, R.color.hv_cyan))
        }
    }

    // -----------------------------------------------------------------------
    // #6 / #12 — the voice-model gate. Voice models are a first-run REQUIREMENT
    // (nothing works without them), so an incomplete set is never shown as
    // neutral info. Zero required installed -> a blocking EMPTY STATE with a
    // "Download voice models" CTA; partially installed -> a tappable warning
    // pill. Both open ModelsActivity — the Voice-models download screen
    // ("Settings > Voice models" used to be a dead label; ModelsActivity IS
    // that destination, and SettingsActivity cannot be deep-linked into).
    // -----------------------------------------------------------------------
    private fun refreshModelsGate() {
        // Onboarding / missing-key problems own their own surfaces; a live call
        // must never be covered by the gate.
        if (callLive || !endpointSet() || GatewayKey.isMissing(storedKey())) {
            modelsGate?.visibility = android.view.View.GONE
            return
        }
        val missing = ModelCatalog.missingRequired(this)
        val total = ModelCatalog.required.size
        if (missing.isEmpty()) {
            modelsGate?.visibility = android.view.View.GONE
            modelsWarnReset()
            return
        }
        if (missing.size == total) showModelsEmptyState()   // zero installed -> blocking CTA
        else modelsMissingPill("⚠ ${missing.size} of $total voice models not installed — tap to download")
    }

    /** Tappable WARNING pill — the replacement for the dead
     *  "Voice model not installed — Settings > Voice models" label (#12). */
    private fun modelsMissingPill(msg: String) {
        if (!::status.isInitialized) return
        modelsWarnShown = true
        status.text = msg
        status.setTextColor(ContextCompat.getColor(this, R.color.hv_warn))
        status.visibility = View.VISIBLE
        status.setOnClickListener { openModelsStore() }
    }

    private fun modelsWarnReset() {
        // setStatus handles clearing the tap handler + restoring the tag color.
        if (modelsWarnShown && ::status.isInitialized) setStatus(getString(R.string.hv_connected), false)
    }

    private fun openModelsStore() {
        startActivity(Intent(this, ModelsActivity::class.java))
    }

    private fun showModelsEmptyState() {
        var gate = modelsGate
        if (gate == null) {
            gate = buildModelsEmptyState()
            modelsGate = gate
            // Index 0 puts it above the warming splash, so the download CTA is
            // the topmost first-run surface.
            (findViewById<android.view.View>(android.R.id.content) as android.view.ViewGroup)
                .addView(gate, 0)
        }
        gate.visibility = View.VISIBLE
    }

    private fun buildModelsEmptyState(): android.widget.LinearLayout {
        val d = resources.displayMetrics.density
        val need = ModelCatalog.required.size
        val body = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding((28 * d).toInt(), 0, (28 * d).toInt(), 0)
        }
        body.addView(android.widget.TextView(this).apply {
            text = "▼"
            textSize = 34f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.hv_cyan))
            gravity = android.view.Gravity.CENTER
        })
        body.addView(android.widget.TextView(this).apply {
            text = "Download the voice models"
            textSize = 22f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(0xFFD6F4FF.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, (14 * d).toInt(), 0, 0)
        })
        body.addView(android.widget.TextView(this).apply {
            text = "Nothing works until Hermes can hear and speak. " +
                "Your offline voice needs $need small models downloaded once — " +
                "after that every conversation runs on-device, no cloud."
            textSize = 15f
            setTextColor(0xFF9FB3C9.toInt())
            gravity = android.view.Gravity.CENTER
            setPadding(0, (10 * d).toInt(), 0, 0)
        })
        body.addView(Button(this).apply {
            text = "Download voice models"
            isAllCaps = false
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.hv_bg))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_button_primary)
            setOnClickListener { openModelsStore() }
            val pad = (18 * d).toInt()
            setPadding(pad, (10 * d).toInt(), pad, (10 * d).toInt())
            layoutParams = android.view.ViewGroup.MarginLayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (22 * d).toInt() }
        })
        body.addView(Button(this).apply {
            text = "Not now"
            isAllCaps = false
            setTextColor(0xFF9FB3C9.toInt())
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_button_ghost)
            setOnClickListener {
                modelsGate?.visibility = View.GONE
                // Deferred, not a dead end: keep a tappable path to the store.
                modelsMissingPill("⚠ $need voice models needed — tap to download")
            }
            val pad = (16 * d).toInt()
            setPadding(pad, (6 * d).toInt(), pad, (6 * d).toInt())
            layoutParams = android.view.ViewGroup.MarginLayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (10 * d).toInt() }
        })
        return android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(0xFF06070B.toInt())
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            addView(body)
        }
    }

    // -----------------------------------------------------------------------
    // #120-C: one-time coach marks. The main screen is chrome-less, so the
    // first launch overlays what the being's body means, what barge-in is, and
    // where the controls live. Dismissing sets the "seen" pref — it never
    // shows again (a Settings replay could reuse PREF_COACH_SEEN later).
    // NOTE: this build is hands-free in EVERY mode — the walkie-talkie/PTT
    // path was stripped in C2 (0.4.0) — so the overlay says there is no
    // push-to-talk button instead of pointing at one.
    // -----------------------------------------------------------------------
    private fun maybeShowCoachMarks() {
        if (coachOverlay != null) return
        if (prefs.getBoolean(PREF_COACH_SEEN, false)) return
        if (callLive) return
        if (!endpointSet() || GatewayKey.isMissing(storedKey())) return
        // The models empty state is the first-run CTA when nothing is
        // installed (#6/#12) — coach marks wait until it isn't blocking.
        if (modelsGate?.visibility == View.VISIBLE) return
        val overlay = buildCoachOverlay()
        coachOverlay = overlay
        (findViewById<android.view.View>(android.R.id.content) as android.view.ViewGroup)
            .addView(overlay, 0)
        overlay.bringToFront()
    }

    private fun dismissCoachMarks() {
        coachOverlay?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        coachOverlay = null
        prefs.edit().putBoolean(PREF_COACH_SEEN, true).apply()
    }

    private fun buildCoachOverlay(): android.view.View {
        val d = resources.displayMetrics.density
        val body = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding((30 * d).toInt(), (34 * d).toInt(), (30 * d).toInt(), (30 * d).toInt())
        }
        body.addView(android.widget.TextView(this).apply {
            text = "Say hello to Hermes"
            textSize = 23f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(0xFFD6F4FF.toInt())
            gravity = android.view.Gravity.CENTER
        })
        body.addView(coachRow("THE BEING",
            "Hermes is your agent, given a body. Watch its motion: it breathes while it listens, gathers (draws inward, brightens) while it thinks or works, and lights up as it speaks."))
        body.addView(coachRow("BARGE-IN",
            "Interrupt hands-free: just start talking over a reply and it stops to listen — no button needed. Tap the being to hush it any time."))
        body.addView(coachRow("THE CONTROLS",
            "✆ starts and ends your hands-free call. There is no push-to-talk button in Vox — every voice mode is hands-free, so you just talk. ⚙ is Settings, / is the command menu."))
        body.addView(Button(this).apply {
            text = "Got it"
            isAllCaps = false
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.hv_bg))
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_button_primary)
            setOnClickListener { dismissCoachMarks() }
            val pad = (26 * d).toInt()
            setPadding(pad, (10 * d).toInt(), pad, (10 * d).toInt())
            layoutParams = android.view.ViewGroup.MarginLayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = (22 * d).toInt() }
        })
        return android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            // Near-opaque scrim: the being stays faintly visible underneath.
            setBackgroundColor(0xF206070B.toInt())
            // Swallow taps on the scrim so they never reach the avatar/call
            // buttons underneath; dismissal is the explicit "Got it" button.
            isClickable = true
            setOnClickListener { }
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            addView(body)
        }
    }

    /** One coach-mark row: bold colored lead, then the plain-language body. */
    private fun coachRow(lead: String, body: String): android.widget.TextView {
        val d = resources.displayMetrics.density
        val s = android.text.SpannableString("$lead — $body")
        s.setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, lead.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(android.text.style.ForegroundColorSpan(0xFF8FD8F5.toInt()), 0, lead.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return android.widget.TextView(this).apply {
            text = s
            textSize = 15f
            setTextColor(0xFFC7D6E6.toInt())
            setLineSpacing(3f, 1f)
            setPadding(0, (14 * d).toInt(), 0, 0)
        }
    }

    // ---- 0.5.1 Part B: the status pill reports the REAL phase ------------------
    // "Warming up" used to be the only pre-connected word the pill knew, and it was
    // sticky: it covered the local pipeline load AND every network wait, so a cold
    // gateway and a loading STT model looked identical and neither ever resolved.
    // Now the phase is derived (ConnectionPhase — pure, unit-proven) from three facts
    // this Activity actually knows, and the pill follows Warming up -> Dialing ->
    // Connected because those are three different things.
    @Volatile private var probe = ConnectionPhase.Probe.NOT_TESTED
    @Volatile private var probeInFlight = false
    /** True only while [openVoiceLine] is waiting on the LOCAL pipeline. While it is
     *  set, no gateway verdict may be shown — that is the field bug, structurally. */
    private var warmingNow = false

    private fun endpointSet() = prefs.getString("url", "").orEmpty().isNotBlank()

    /** Render the pill for the current phase. */
    private fun showPhase() {
        val phase = ConnectionPhase.resolve(endpointSet(), !GatewayKey.isMissing(storedKey()),
            !warmingNow, probe)
        // A live call owns the pill ("On call"); only a real problem interrupts it.
        if (callLive) when (phase) {
            ConnectionPhase.Phase.CONNECTED, ConnectionPhase.Phase.DIALING,
            ConnectionPhase.Phase.WARMING -> return
            else -> {}
        }
        // C0 keeps its own, longer prompt — it tells the user where to go.
        if (phase == ConnectionPhase.Phase.NEEDS_KEY) { setStatus(GatewayKey.MISSING_KEY_PROMPT, true); return }
        setStatus(ConnectionPhase.pill(phase), ConnectionPhase.shows(phase))
    }

    /** DIALING: reach out to the configured gateway and let the answer move the pill.
     *  Ping-only by default — opening the app should not fire a real model turn just
     *  to colour a pill. Runs off the UI thread (VoiceController.testConnectionAsync);
     *  running it ON the UI thread is what made the field test report
     *  `ping=false(unknown)` for a request that never left the device. */
    private fun dialGateway(includeStream: Boolean = false) {
        if (!endpointSet() || GatewayKey.isMissing(storedKey())) { showPhase(); return }
        if (probeInFlight) return
        val s = session ?: run { showPhase(); return }
        probeInFlight = true
        probe = ConnectionPhase.Probe.IN_FLIGHT
        showPhase()
        val c = liveController ?: VoiceController(applicationContext, s)
        c.testConnectionAsync(includeStream) { p, _ ->
            probeInFlight = false
            probe = p
            if (!isFinishing) showPhase()
        }
    }

    private fun setCallTone(live: Boolean) {
        try { status.setTextColor(if (live) 0xFF35D07F.toInt() else 0xFFD6F4FF.toInt()) } catch (_: Throwable) {}
    }
    private fun stopVoiceWake() {
        if (voiceWake != null) VoxLog.d("event=wake released")
        try { voiceWake?.release() } catch (_: Exception) {}
        voiceWake = null
        // K2 (0.5.0.3): the screen-alive window flag dies with the wake. EVERY call
        // teardown runs this (endCall / newSession / resetActiveConversation /
        // onStop-no-call), so the flag can never outlive the call — no leak.
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
            VoxLog.d("event=wake acquired")
        } catch (e: Exception) { VoxLog.e("event=wake-acquire-failed err=${e.message}") }
        // K2 (0.5.0.3) screen-alive toggle (Settings → Appearance, keep_screen_on,
        // default OFF): FLAG_KEEP_SCREEN_ON is a WINDOW flag — no permission, NOT a
        // WAKE_LOCK. Armed here, at call start beside the wake acquisition; released
        // unconditionally in stopVoiceWake (the same teardown as focus/wake).
        if (prefs.getBoolean("keep_screen_on", false)) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            VoxLog.d("event=screen-alive armed")
        }
    }

    /** K2: re-apply the screen-alive flag from the CURRENT pref + call state. Runs on
     *  resume (after resumeLiveCallIfAny), so flipping the toggle in Settings mid-call
     *  lands the moment the call surface is back — on OR off. The controller gate keeps
     *  a stale callLive (e.g. after /new) from re-arming a flag with no live line. */
    private fun applyKeepScreenOn() {
        val on = callLive && liveController != null && prefs.getBoolean("keep_screen_on", false)
        if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---------------------------------------------------------------------------
    // C3 H1 — AUDIO FOCUS. FOCUS OWNER = MainActivity, deliberately NOT
    // VoiceController: audio focus is a CALL-scoped resource. It is requested here,
    // at call start, next to the wake-lock acquisition, and abandoned in the exact
    // paths that release the wake lock today (endCall / newSession /
    // resetActiveConversation / onStop-no-call), so request and abandon can never
    // drift out of step. VoiceController.start()/stop() are the audio-engine warm +
    // teardown hooks, not call boundaries (the warm-retry loop and /clear also call
    // them), so tying focus there would leak or drop it out of sync with the wake
    // lock — and the >30s-loss hang-up must run the call state machine (endCall),
    // which only MainActivity owns. The controller stays the single audio owner for
    // the MIC/Speaker; focus just tells it when to pause (pauseForFocusLoss) and
    // resume (resumeFromFocusLoss) via the ONE silenceAll cancel path.
    // ---------------------------------------------------------------------------
    private var focusListener: android.media.AudioManager.OnAudioFocusChangeListener? = null
    private var focusRequest: android.media.AudioFocusRequest? = null
    @Volatile private var focusHeld = false
    @Volatile private var focusPauseActive = false
    private var focusLostAtMs = 0L
    private val C3_FOCUS_RESUME_MS = 30_000L   // >30s of focus loss -> hang up, no zombie mic

    private fun acquireVoiceFocus() {
        if (focusHeld) return
        try {
            if (focusListener == null) {
                focusListener = android.media.AudioManager.OnAudioFocusChangeListener { change ->
                    runOnUiThread { onAudioFocusChange(change) }
                }
            }
            val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            val attrs = android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val res: Int
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(focusListener!!)
                    .build()
                focusRequest = req
                res = am.requestAudioFocus(req)
            } else {
                // Pre-O: no AudioFocusRequest — request through the same listener on the
                // voice-call stream (the SPEECH content-type attribution is an O+ concept).
                res = am.requestAudioFocus(focusListener, android.media.AudioManager.STREAM_VOICE_CALL, android.media.AudioManager.AUDIOFOCUS_GAIN)
            }
            focusHeld = res == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            focusPauseActive = false
            focusLostAtMs = 0L
            VoxLog.d("event=focus-acquire result=${if (focusHeld) "granted" else "denied"}")
        } catch (e: Exception) { VoxLog.e("event=focus-acquire-failed err=${e.message}") }
    }

    private fun releaseVoiceFocus() {
        if (!focusHeld && focusRequest == null && focusListener == null) return
        try {
            val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val req = focusRequest
                if (req != null) am.abandonAudioFocusRequest(req) else focusListener?.let { am.abandonAudioFocus(it) }
            } else {
                focusListener?.let { am.abandonAudioFocus(it) }
            }
            if (focusHeld) VoxLog.d("event=focus released")
        } catch (_: Exception) {}
        focusRequest = null
        focusListener = null
        focusHeld = false
        focusPauseActive = false
        focusLostAtMs = 0L
    }

    /** C3 H1 focus-loss/resume dispatch. LOSS and LOSS_TRANSIENT silence the current
     *  reply + pause listening (the interrupt is treated like a fresh-turn window on
     *  GAIN, never an auto-resumed reply). CAN_DUCK is treated as a transient loss too:
     *  TTS ducking is unavailable on the single-track writer, so silence beats garble
     *  (logged distinctly). The foreground service is NOT stopped — one tap re-accepts.
     *  GAIN after >30s of loss ends the call through the existing hang-up path instead
     *  of re-arming a stale mic. */
    private fun onAudioFocusChange(change: Int) {
        when (change) {
            android.media.AudioManager.AUDIOFOCUS_LOSS,
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (!callLive) return   // late loss after the call already ended
                focusPauseActive = true
                focusLostAtMs = android.os.SystemClock.elapsedRealtime()
                val kind = if (change == android.media.AudioManager.AUDIOFOCUS_LOSS) "loss" else "transient"
                VoxLog.d("event=focus-change state=loss kind=$kind")
                liveController?.pauseForFocusLoss()
            }
            android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (!callLive) return
                focusPauseActive = true
                focusLostAtMs = android.os.SystemClock.elapsedRealtime()
                VoxLog.d("event=focus-change state=loss kind=can-duck")
                liveController?.pauseForFocusLoss()
            }
            android.media.AudioManager.AUDIOFOCUS_GAIN -> {
                if (!focusPauseActive) return   // spurious GAIN (fresh request): nothing to resume
                focusPauseActive = false
                val lostMs = if (focusLostAtMs > 0L) android.os.SystemClock.elapsedRealtime() - focusLostAtMs else 0L
                focusLostAtMs = 0L
                if (lostMs > C3_FOCUS_RESUME_MS) {
                    VoxLog.d("event=focus-change state=resume action=hangup reason=over-${C3_FOCUS_RESUME_MS}ms lostMs=$lostMs")
                    endCall()
                } else {
                    VoxLog.d("event=focus-change state=resume lostMs=$lostMs")
                    liveController?.resumeFromFocusLoss()
                }
            }
        }
    }

    // ---------------------------------------------------------------------------
    // C3 H2 — AUDIO DEVICE ROUTE CHANGES. Registered per call open, unregistered on
    // stop (the same wake/focus release paths). When a BT/USB/wired headset appears
    // or disappears mid-call, capture + playback re-attach to the new default route
    // with a fresh-call composition: stop the old controller -> 250ms -> openVoiceLine
    // (start). No SCO routing is added (out of scope): VOICE_COMMUNICATION capture
    // follows whatever route the platform keeps.
    // ---------------------------------------------------------------------------
    @Volatile private var routeCallbackRegistered = false
    @Volatile private var routeRebuildScheduled = false
    private val routeRebuildTask = object : Runnable {
        override fun run() {
            routeRebuildScheduled = false
            val s = session ?: return
            val old = liveController ?: return
            if (!callLive) return
            VoxLog.d("event=audio-route action=stop-old-line")
            old.setVoiceChannelOpen(false)
            old.stop()
            liveController = null
            // 250ms: let the old AudioRecord/AudioTrack fully detach before the fresh
            // composition re-opens (both halves re-init against the new default route).
            mainHandler.postDelayed({
                if (!callLive) return@postDelayed
                VoxLog.d("event=audio-route action=start-new-line")
                openVoiceLine(s)
            }, 250L)
        }
    }
    private val audioDeviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>?) { onRouteDevicesChanged(added) }
        override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>?) { onRouteDevicesChanged(removed) }
    }

    /** Route callback fires for ANY device change; only the headset-family matters.
     *  A BT headset commonly reports A2DP first (SCO appears only when the platform
     *  opens it for VOICE_COMMUNICATION), so A2DP is included alongside SCO —
     *  otherwise the common connect/disconnect case would never trigger a rebuild. */
    private fun onRouteDevicesChanged(devices: Array<out android.media.AudioDeviceInfo>?) {
        if (!callLive || routeRebuildScheduled) return   // a rebuild is already queued
        val types = devices?.mapNotNull { deviceTypeName(it.type) }?.distinct().orEmpty()
        if (types.isEmpty()) return
        VoxLog.d("event=audio-route devices=${types.joinToString("+")}")
        routeRebuildScheduled = true
        mainHandler.post(routeRebuildTask)
    }

    private fun deviceTypeName(t: Int): String? = when (t) {
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
        android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        else -> null
    }

    private fun registerRouteCallback() {
        if (routeCallbackRegistered) return
        try {
            val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            am.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
            routeCallbackRegistered = true
            VoxLog.d("event=audio-route registered")
        } catch (e: Exception) { VoxLog.e("event=audio-route-register-failed err=${e.message}") }
    }

    private fun unregisterRouteCallback() {
        if (!routeCallbackRegistered) return
        try {
            val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            am.unregisterAudioDeviceCallback(audioDeviceCallback)
            routeCallbackRegistered = false
            VoxLog.d("event=audio-route unregistered")
        } catch (e: Exception) { VoxLog.e("event=audio-route-unregister-failed err=${e.message}") }
    }

    // The entity API key is encrypted at rest (Keystore); legacy plaintext
    // decrypts as-is. C0: user-entered ONLY — GatewayKey.resolve never falls back
    // to a baked/default key; blank or undecryptable storage resolves to "".
    private fun storedKey() = GatewayKey.resolve(prefs.getString("key", "").orEmpty(), SecureStore::decrypt)

    // C0: no stored user key while an endpoint is set -> the connector must not
    // start and the user must add their key in Settings. Surfaces the clear
    // user-facing prompt (wording only — no new UI) instead of a silent no-op.
    private fun missingKeyPrompt(): Boolean {
        if (session != null) return false
        if (!GatewayKey.isMissing(storedKey())) return false
        setStatus(GatewayKey.MISSING_KEY_PROMPT, true)
        return true
    }

    // App-scoped call start time so a live call's timer survives activity recreation.
    companion object {
        // 0.5.0-A reveal pacing (see revealTask).
        const val REVEAL_TICK_MS = 80L    // ~12fps: fast enough to read as continuous, cheap
        const val MIN_REVEAL_MS = 220L    // floor on how briefly the reveal may be engaged

        // 0.5.0-previewB presence motion.
        const val VOICE_HOLD_MS = 400L    // audible-audio hold across inter-phrase synth gaps
        const val QUIET_MS = 12000L       // rest this long -> widen from IDLE into DRIFT

        // Single canonical HermesSession: MainActivity, the live controller, and
        // Settings all share THIS instance so a reset reaches the same conversation.
        @Volatile var session: HermesSession? = null
        @Volatile private var sesUrl: String? = null
        @Volatile private var sesKey: String? = null
        @Volatile private var sesModel: String? = null
        @Volatile private var sesProvider: String? = null
        @Volatile private var active: MainActivity? = null
        @Volatile var callStartedAt = 0L
        @Volatile var liveController: VoiceController? = null
        // #61: dedicated call-start permission request code + pending flag so the
        // warm-retry path never re-requests while the dialog is up / after denial.
        const val REQ_MIC_CALL = 101
        // #120-C: "seen" pref for the one-time first-run coach marks.
        const val PREF_COACH_SEEN = "coach_marks_seen"
        @Volatile var callStartPending = false

        /** Reset the canonical session's conversation and clear the display/reply
         *  buffers. Reaches the LIVE session (MainActivity + live controller), so a
         *  Settings "New conversation" clears the same context an active call sees. */
        fun resetActiveConversation() {
            session?.resetConversation()
            LatencyStats.resetSessionTurns()   // C3: a reset conversation is a fresh session
            liveController?.setVoiceChannelOpen(false)
            liveController?.stop()
            liveController = null
            active?.stopVoiceWake()   // #13: conversation reset ends the call -> release the wake lock
            active?.releaseVoiceFocus()
            active?.unregisterRouteCallback()
            active?.clearConversationUi()
        }
    }

    private fun clearConversationUi() {
        revealActive = false; mainHandler.removeCallbacks(revealTask)
        replyBuf = ""
        reply.setText("")
        convoBuf = ""
        convoText.setText("")
        feed(MotionState.Signal.REST)
        setStatus(getString(R.string.hv_connected), false)
    }

    private fun connectFromPrefs() {
        val u = prefs.getString("url", "").orEmpty()
        val k = storedKey()
        val m = prefs.getString("model", "hermes-agent").orEmpty()
        val p = prefs.getString("provider", "").orEmpty()
        if (u.isBlank()) return
        // C0: no user-entered key -> surface the Settings prompt (never connect
        // with an empty auth / baked fallback).
        if (GatewayKey.isMissing(k)) { setStatus(GatewayKey.MISSING_KEY_PROMPT, true); return }
        if (session == null || sesUrl != u || sesKey != k || sesModel != m || sesProvider != p) {
            session = HermesSession(u, k, m)
            // The provider is a per-request override the Go /v1/responses client sends
            // (the blessed LIGHT PATH) — set it after construction so every connector
            // (stream/chat/runs) forwards the chosen gateway backend.
            session?.setProvider(p)
            sesUrl = u; sesKey = k; sesModel = m; sesProvider = p
        }
        // The pill used to assert "Connected" the instant a session OBJECT existed —
        // before a single byte had been sent. Now it says Dialing and waits for the
        // gateway to actually answer.
        dialGateway()
        // The header shows the agent's name (the Hermes profile name, or the name
        // entered in onboarding) — center-top, with the status pill beneath.
        agentName.text = prefs.getString("agent_name", "").orEmpty().ifBlank { m }.uppercase()
        appendStream("// connected → $u")
        // No auto-open: real-time/enhanced starts ONLY on the call button. A live call
        // (surviving an app-close) is detected on create and resumed below.
    }

    private fun wireButtons() {
        findViewById<Button>(R.id.settings).setOnClickListener { openSettings() }
        findViewById<Button>(R.id.call).setOnClickListener { if (callLive) endCall() else startCall() }
        findViewById<Button>(R.id.commands).setOnClickListener { showCommands() }
    }

    private fun send(text: String) {
        if (missingKeyPrompt()) return   // C0: empty key -> clear Settings prompt
        val s = session ?: run { setStatus("Connect first", true); return }
        if (text.isBlank()) return
        // K2: typing /compress (or /compact) is the same native command as picking it
        // from the commands sheet — status feedback + the gateway's answer in a card,
        // instead of the word disappearing into the turn as ordinary conversation.
        if (CompressCommand.matches(text)) { compressContext(); return }
        appendConvo("You: $text")
        val c = liveController ?: VoiceController(applicationContext, s).also {
            liveController = it
            s.resetConversation()
            LatencyStats.resetSessionTurns()   // C3: first-use controller = fresh session
        }
        c.attachListeners(listener)
        c.sendText(text)
    }

    private val listener = object : VoiceController.Listener {
        override fun onState(state: String) {
            runOnUiThread {
                if (state != "thinking") toolCount = 0   // a new turn begins
                // K3 (0.5.0.3): scope replyBuf to the turn. "thinking" fires exactly
                // once per turn (runStreamedTurn, beside armTranscript which resets the
                // cursor space), but replyBuf had NO matching reset — it carried the
                // previous turn's final text into the next turn's pre-voice window,
                // where the reveal loop's cursor==-1 fallback paints it plainly. A slow
                // upstream stretches that window to the whole stall, which is the field
                // flag: "the app pushes the last message text to the screen when the
                // upstream is slow." The fallback now shows only THIS turn's composed
                // text (the arguably-correct "you see what it'll say" case, unchanged).
                if (state == "thinking") replyBuf = ""
                // 0.5.1: the controller's own "warming" state is the LOCAL pipeline
                // loading, and it now says so on the pill instead of falling through
                // to a silent "Connected" it has not earned yet.
                warmingNow = state == "warming"
                setStatus(when (state) {
                    "listening" -> "Listening…"
                    "thinking" -> "The entity is working…"
                    "speaking" -> "Speaking…"
                    "warming" -> ConnectionPhase.pill(ConnectionPhase.Phase.WARMING)
                    else -> getString(R.string.hv_connected)
                }, state == "warming")
                // previewB: the state is a SIGNAL now, not a shape + a made-up level.
                // The level it used to pass (0.6 for "speaking") was a placeholder;
                // renderMotion reads the real playback RMS instead.
                markActivity()
                feed(when (state) {
                    "listening" -> MotionState.Signal.LISTEN
                    "thinking", "streaming" -> MotionState.Signal.THINK
                    "speaking" -> MotionState.Signal.SPEAK
                    else -> MotionState.Signal.REST
                })
                // The reveal owns the crawl for the whole turn. NOTE: the streamed path
                // (the one that raced ahead) never emits "speaking" — only the one-shot
                // speak() does — so the loop is armed from "thinking" and the controller's
                // cursor decides when the lock engages: it reports -1 (paint plainly, as
                // before) until the first phrase is actually handed to the voice.
                if (state == "thinking" || state == "speaking") startReveal() else stopReveal()
            }
        }
        override fun onDelta(text: String) { runOnUiThread {
            // Keep the full composition; paint it only while the reveal is NOT running.
            // Once the voice starts, the crawl is painted by the speech cursor instead —
            // appending here is what raced 3-4 sentences ahead of the audio.
            replyBuf += text
            markActivity()   // previewB: a delta is proof the provider is alive
            if (!revealActive) reply.setText(replyBuf)
        } }
        override fun onLog(line: String) { runOnUiThread {
            appendStream(line)
            // previewB: the presence markers are read BEFORE markActivity() — a stall
            // notice is the report of silence, not evidence against it.
            when {
                line.startsWith(VoiceController.STREAM_STALL) -> {
                    setMotionStall(true, android.os.SystemClock.uptimeMillis() - lastSignalAt)
                    return@runOnUiThread
                }
                line.startsWith(VoiceController.STREAM_RESUME) -> { markActivity(); return@runOnUiThread }
                line == VoiceController.CUT_BARGE || line == VoiceController.CUT_HUSH -> {
                    // Every cut path (mic barge / hush / stop) reaches the display here,
                    // so the recoil is driven by the one signal all of them share.
                    markActivity(); feed(MotionState.Signal.BARGE)
                    return@runOnUiThread
                }
            }
            markActivity()
            if (line.startsWith("◆ tool: ")) {
                // a tool was CALLED — the being gathers into the tool's motif + ramps
                toolCount++
                val nm = line.removePrefix("◆ tool: ").substringBefore('{').substringBefore(' ').trim()
                motionTool = mapTool(nm)
                feed(MotionState.Signal.TOOL_CALL)   // -> avatar.onTool (existing motif)
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
                feed(MotionState.Signal.TOOL_DONE)   // -> avatar.pulseTool (satisfied shimmer)
            }
        } }
        override fun onReply(finalText: String) { runOnUiThread {
            // The final full text still lands here; it is only PAINTED once the voice has
            // finished with the surface (the stream completes seconds before the audio
            // does — setting it here is the clobber that erased the reveal mid-sentence).
            replyBuf = finalText
            markActivity()
            feed(MotionState.Signal.RETIRE)   // previewB: a natural end settles, never recoils
            if (!revealActive) reply.setText(replyBuf)
            appendConvo("Agent: $finalText")
        } }
        override fun onError(msg: String) { runOnUiThread {
            setStatus(if (msg.contains("interrupt")) "You interrupted" else msg, !msg.contains("interrupt"))
            feed(MotionState.Signal.REST); appendStream("// $msg")
        } }
    }

    // SSE tool name -> being shape motif (null = default vortex/compile gyre).
    private fun mapTool(name: String): String? = when {
        name.contains("terminal") || name.contains("shell") || name.contains("exec") -> "shell"
        name.contains("web") || name.contains("search") || name.contains("extract") || name.contains("fetch") -> "web"
        name.contains("delegate") || name.contains("spawn") || name.contains("run_agent") || name.contains("agent") -> "agent"
        name.contains("video_analyze") || name.contains("image") || name.contains("vision") || name.contains("eye") -> "vision"
        name.contains("file") || name.contains("write") || name.contains("read") || name.contains("search_files") -> "file"
        name.contains("memory") || name.contains("recall") || name.contains("ragamuffin") -> "memory"
        name.contains("download") || name.contains("model") -> "download"
        else -> null
    }

    private fun openSettings() { startActivity(Intent(this, SettingsActivity::class.java)); overridePendingTransition(R.anim.slide_in, R.anim.fade_out) }

    /** Expose the Hermes instance's /commands. For the MVP a curated set; the
     *  live command list is a follow-up (query the gateway). WS4b: the /models,
     *  /health, /new, /reconnect commands open NATIVE mini-UIs fed by real gateway
     *  data — the agent only does conversation, never command-UI strings. */
    private fun showCommands() {
        val cmds = arrayOf(
            "/models", "/health", "/compress", "/new", "/reconnect", "/clear", "/reset", "/status", "/help")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Commands")
            .setItems(cmds) { _, w ->
                when (cmds[w]) {
                    "/models"    -> showModelChooser()
                    "/health",
                    "/status"    -> showHealthCard()
                    CompressCommand.NAME,
                    CompressCommand.ALIAS -> compressContext()
                    "/new"       -> newSession()
                    "/reconnect" -> reconnect()
                    "/clear",
                    "/reset" -> {
                        liveController?.stop(); liveController = null
                        session?.resetConversation()
                        LatencyStats.resetSessionTurns()   // C3: cleared conversation = fresh session
                        revealActive = false; mainHandler.removeCallbacks(revealTask)
                        replyBuf = ""; reply.setText("")
                        feed(MotionState.Signal.REST); setStatus(getString(R.string.hv_connected), false)
                    }
                    else         -> showHelpCard()   // /help
                }
            }
            .show()
    }

    /** /models — native chooser from the REAL gateway catalog (GET /api/model/options).
     *  Selecting a model sets the app model+provider prefs that the Go /v1/responses
     *  client sends per request (the blessed LIGHT PATH) — the gateway overrides the
     *  entity's inference backend, while the entity's memory/skills/context stay. */
    private fun showModelChooser() {
        val s = session ?: run { setStatus("Connect first", true); return }
        setStatus("Loading gateway models…", true)
        kotlin.concurrent.thread {
            val raw = try { s.modelOptions() } catch (e: Throwable) { null }
            runOnUiThread {
                setStatus(getString(R.string.hv_connected), false)
                if (raw.isNullOrBlank()) { toast("Couldn't reach the model catalog"); return@runOnUiThread }
                val providers = org.json.JSONObject(raw).optJSONArray("providers") ?: return@runOnUiThread
                val labels = mutableListOf<String>(); val ids = mutableListOf<String>(); val provs = mutableListOf<String>()
                for (p in 0 until providers.length()) {
                    val prov = providers.optJSONObject(p) ?: continue
                    val pname = prov.optString("name").ifEmpty { prov.optString("slug") }
                    val cur = prov.optBoolean("is_current", false)
                    val auth = prov.optString("source", "")
                    val models = prov.optJSONArray("models")
                    if (models == null || models.length() == 0) {
                        labels += ((if (cur) "* " else "") + pname + " - " + prov.optInt("total_models", 0) + " models")
                        ids += prov.optString("slug"); provs += prov.optString("slug")
                    } else {
                        for (m in 0 until models.length()) {
                            // The live gateway /api/model/options returns model IDs as plain
                            // STRINGS (e.g. "minimax-m3", "kimi-k3"); other builds may return
                            // model OBJECTS ({id,slug,name}). Read both shapes so the chooser
                            // renders the real models instead of silently dropping them (the
                            // old optJSONObject-only path yielded an empty list for every
                            // configured provider).
                            val entry = models.opt(m)
                            val mLabel: String
                            val mId: String
                            if (entry is String) { mLabel = entry; mId = entry }
                            else if (entry is org.json.JSONObject) {
                                mLabel = entry.optString("name").ifEmpty { entry.optString("id").ifEmpty { entry.optString("slug") } }
                                mId = entry.optString("id").ifEmpty { entry.optString("slug").ifEmpty { entry.optString("name") } }
                            } else continue
                            labels += ((if (cur) "* " else "") + pname + " - " + mLabel + (if (auth.isNotBlank()) " ($auth)" else ""))
                            ids += mId; provs += prov.optString("slug")
                        }
                    }
                }
                if (labels.isEmpty()) { toast("No models in catalog"); return@runOnUiThread }
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Gateway models (choose your agent's brain)")
                    .setSingleChoiceItems(labels.toTypedArray(), 0) { d, which ->
                        d.dismiss()
                        val modelId = ids[which]; val prov = provs[which]
                        // Persist the choice so it survives restart; then set it on the
                        // LIVE session so the next turn sends provider+model per request.
                        prefs.edit().putString("model", modelId).putString("provider", prov).apply()
                        val ok = try {
                            s.setModel(modelId); s.setProvider(prov); true
                        } catch (e: Throwable) { false }
                        runOnUiThread {
                            toast(if (ok) "Model set -> $prov/$modelId" else "Couldn't set the model")
                            s.resetConversation()   // re-init the session under the new backend
                            LatencyStats.resetSessionTurns()   // C3: fresh backend = fresh session
                            sesModel = modelId; sesProvider = prov
                            agentName.text = prefs.getString("agent_name", "").orEmpty().ifBlank { modelId }.uppercase()
                        }
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        }
    }

    /** /health — native health card (GET /v1/health). */
    private fun showHealthCard() {
        val s = session ?: run { setStatus("Connect first", true); return }
        kotlin.concurrent.thread {
            val raw = try { s.gatewayHealth() } catch (e: Throwable) { null }
            runOnUiThread {
                val card = if (raw.isNullOrBlank()) "Health: unreachable\n(debug: check network + address)"
                            else try {
                                val o = org.json.JSONObject(raw)
                                "Status: " + o.optString("status", "?") +
                                "\nVersion: " + o.optString("version", o.optString("service_version", "?"))
                            } catch (e: Throwable) { "Health: ok\n$raw" }
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Agent health").setMessage(card).setPositiveButton("OK", null).show()
            }
        }
    }

    /**
     * /compress (K2, 0.5.2) — ask the GATEWAY to compact this conversation's context
     * so a long session can keep going. Christopher's ask, verbatim: "We need to
     * expose a compress command to the user through this app."
     *
     * This is gateway-side compaction, not an app-side trim: [CompressCommand.DIRECTIVE]
     * goes out over `turnStored`, the NON-streaming /v1/responses call that rides the
     * SAME server-side chain as the voice turns (previous_response_id, and the reply's
     * id is chained back), so the entity compacts the session the user is actually in.
     * /clear stays what it always was — the local transcript, nothing more.
     *
     * Same shape as the other native commands: status pill while it runs, native card
     * with the gateway's answer, off the UI thread (turnStored blocks up to 120s).
     */
    private fun compressContext() {
        if (missingKeyPrompt()) return   // C0: empty key -> the clear Settings prompt
        val s = session ?: run { setStatus("Connect first", true); return }
        setStatus(CompressCommand.RUNNING, true)
        kotlin.concurrent.thread {
            val reply = try { s.turnStored(CompressCommand.DIRECTIVE) } catch (e: Throwable) {
                VoxLog.w("event=compress-failed err=${e.message?.take(120)}"); null
            }
            val ok = !reply.isNullOrBlank()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                setStatus(if (ok) CompressCommand.DONE else CompressCommand.FAILED, true)
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(CompressCommand.TITLE)
                    .setMessage(CompressCommand.card(reply))
                    .setPositiveButton("OK", null).show()
            }
        }
    }

    /** /new — reset the app session chain + DELETE /v1/responses/{id} + fresh state.
     *  The server-side response id must be captured on the bg thread BEFORE the
     *  client chain is dropped (resetConversation clears lastID), so the delete and
     *  the reset run on the SAME thread, in order. */
    private fun newSession() {
        val s = session
        if (s != null) {
            kotlin.concurrent.thread {
                try { s.deleteLastResponse() } catch (_: Throwable) {}   // uses s.lastID before the drop
                s.resetConversation()   // then drop the client chain + lastID
                LatencyStats.resetSessionTurns()   // C3: a new session restarts the turn count
            }
        }
        liveController?.setVoiceChannelOpen(false)
        liveController?.stop(); liveController = null
        stopVoiceWake()
        releaseVoiceFocus()        // C3: abandon symmetrically with the wake-lock release
        unregisterRouteCallback()  // C3: no line -> no route rebuilds
        clearConversationUi()
        setStatus("New session started", true)
    }

    /** /reconnect — re-ping /v1/health (via gateway) + re-init the session. */
    private fun reconnect() {
        setStatus("Reconnecting…", true)
        kotlin.concurrent.thread {
            val u = prefs.getString("url", "").orEmpty()
            val k = storedKey()
            val m = prefs.getString("model", "hermes-agent").orEmpty()
            val ok = try { com.hermesvox.mobile.HermesSession(u, k, m).gatewayHealth(); true } catch (e: Throwable) { false }
            runOnUiThread {
                resetActiveConversation()
                connectFromPrefs()   // re-init session from prefs (incl. the provider override)
                setStatus(if (ok) "Reconnected" else "Reconnect failed — check network + address", true)
            }
        }
    }

    private fun showHelpCard() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Commands")
            .setMessage("/models - choose the gateway model (provider + model)\n" +
                "/health - agent health (status + version)\n" +
                "/new - reset the conversation + server chain\n" +
                "/reconnect - re-ping the gateway\n" +
                CompressCommand.HELP_LINE + "\n" +
                "/clear /reset - clear the local conversation\n" +
                "/status - agent health")
            .setPositiveButton("OK", null).show()
    }

    private fun toast(msg: String) = android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

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

    private fun appendStream(line: String) {
        if (stream.visibility == View.GONE) return   // dev console off
        sseBuf = (sseBuf + "\n" + line).trim().takeLast(1600)
        stream.setText(sseBuf)
    }

    // Staged entrance: the crawl/controls rise staggered for that alive,
    // "materializing presence" feel. The being is NOT alpha-gated here — it
    // self-animates and must never be hidden by the staging.
    private fun stageEntrance() {
        val rows = listOf<View>(findViewById(R.id.crawl_area),
            findViewById(R.id.call), findViewById(R.id.settings))
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
    override fun onResume() { super.onResume(); runOnUiThread { updateStreamVisibility(); handleModeUi(); applyParticlePrefs(); resumeLiveCallIfAny(); applyKeepScreenOn(); refreshModelsGate(); maybeShowCoachMarks() } }

    // Voice mode: Realtime vs Enhanced Realtime — both share ONE hands-free open
    // line (VAD + barge-in); Enhanced adds the on-device Gemma presence layer.
    private fun handleModeUi() {
        applyLayoutMode()
        val mode = prefs.getString(ModelCatalog.KEY_VOICE_MODE, ModelCatalog.MODE_REALTIME) ?: ModelCatalog.MODE_REALTIME
        if (mode == ModelCatalog.MODE_ENHANCED) {
            val g = express as? GemmaExpress
            if (g != null && !g.available) g.load {}   // load the on-device model once
        }
    }

    // Presence appearance: the visual CATEGORY (what the being is made of, in every
    // state) + the idle shape/theme + auto-cycle. Settings -> Visuals. This stays the
    // one controller: Settings only writes prefs, the feed applies them.
    private fun applyParticlePrefs() {
        val theme = prefs.getString("particles_theme", "aura") ?: "aura"
        avatar.setIdleTheme(theme)
        avatar.setCycleThemes(prefs.getBoolean("particles_cycle", true))
        // video-statewire: the active-state shape picks, fed the SAME way as the idle
        // theme (cached in AvatarView.applyStateShapes, never read per frame). Defaults
        // are the Wave 1 semantic fits and agree with the Settings pickers.
        avatar.applyStateShapes(
            prefs.getString(AvatarView.KEY_SHAPE_SPEAKING, AvatarView.DEFAULT_SHAPE_SPEAKING)
                ?: AvatarView.DEFAULT_SHAPE_SPEAKING,
            prefs.getString(AvatarView.KEY_SHAPE_LISTENING, AvatarView.DEFAULT_SHAPE_LISTENING)
                ?: AvatarView.DEFAULT_SHAPE_LISTENING,
            prefs.getString(AvatarView.KEY_SHAPE_THINKING, AvatarView.DEFAULT_SHAPE_THINKING)
                ?: AvatarView.DEFAULT_SHAPE_THINKING,
        )
        avatar.setVisualCategory(prefs.getString(VisualStyle.KEY_CATEGORY, VisualStyle.DEFAULT)
            ?: VisualStyle.DEFAULT)
        avatar.setVisualEnergy(prefs.getFloat(VisualStyle.KEY_ENERGY, VisualStyle.DEFAULT_ENERGY))
        avatar.setVisualGlow(prefs.getFloat(VisualStyle.KEY_GLOW, VisualStyle.DEFAULT_GLOW))
    }

    private fun startAvatarLoop() {
        lastSignalAt = android.os.SystemClock.uptimeMillis()
        val tick = object : Runnable {
            // previewB rides THIS clock — no parallel animation loop. It is posted on
            // the avatar, so it dies with the view exactly as it always has.
            override fun run() { motionTick(); avatar.invalidate(); avatar.postDelayed(this, 30) }
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
            VoxLog.d("event=activity-stop reason=no-live-call controller-stopped")
            liveController?.setVoiceChannelOpen(false)
            liveController?.stop()
            liveController = null   // #19: stopped controller is terminal (exec shut down) — never re-arm it
            stopVoiceWake()         // #13: no active call -> drop the wake lock (no Doze hold)
            releaseVoiceFocus()     // C3: abandon symmetrically with the wake-lock release
            unregisterRouteCallback()
            VoiceService.stop(this)
        }
    }

    override fun onDestroy() {
        revealActive = false
        mainHandler.removeCallbacks(revealTask)   // the loop reposts itself — never outlive the view
        if (active === this) active = null
        super.onDestroy()
    }
}
