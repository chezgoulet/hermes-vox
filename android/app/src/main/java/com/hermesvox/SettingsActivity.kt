package com.hermesvox

import android.content.Context
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

/**
 * SettingsActivity — a real settings screen. Home sections: Voice models,
 * Entity & Connection, Hearing, Transcription (STT), Voice, Presence,
 * Appearance & Layout, Being, About & Diagnostics. Values are stored
 * as stable TOKENS (the logic reads tokens, e.g. buildTts checks
 * "kokoro"/"piper"); the UI renders human labels. Presence is a layer on the one
 * normal operation (#222/D16), so it has a switch, not a mode selector.
 */
class SettingsActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("hv", Context.MODE_PRIVATE) }
    // WS1: which nested sub-view is currently shown (null = the Settings home list).
    private var currentSection: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        if (prefs.getString("theme", "system") == "dark") AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // Back button is section-aware: inside a sub-view it returns to the group
        // list; at the home list it finishes the Activity.
        findViewById<android.view.View>(R.id.settings_back).setOnClickListener {
            if (currentSection != null) { showGroupList() } else { finish(); overridePendingTransition(R.anim.fade_in, R.anim.fade_out) }
        }
        bindEntity()
        bindFlows()
        bindAppearance()
        bindMicSettings()
        bindJargonHelp()
        bindVisuals()
        bindSoundCues()
        bindGroups()
        bindAssistant()
        bindSectionRestoreRows()
        bindAppSkill()
        bindAlpha()
        applyHints()
        findViewById<TextView>(R.id.set_about_val).text = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Throwable) { "?" }
        openSectionFromIntent()
        applyDebugHarness()   // debug-only; inert in a release build
    }

    /** #224: one line under each row's title saying what it changes (SettingsHints). */
    private fun applyHints() {
        for (name in SettingsHints.LINED) {
            val id = resources.getIdentifier(name, "id", packageName)
            val row = findViewById<android.view.ViewGroup>(id) ?: continue
            val title = firstTitle(row) ?: continue
            applyHint(name, title)
        }
    }

    /** The profile these scoped rows apply to (per-profile pass). */
    private fun activeProfileName(): String =
        Profiles.activeName { prefs.getString(it, "").orEmpty() }

    /**
     * Put [SettingsHints]' line under a title that another refresh may have just rewritten, and
     * — for a row whose setting is the active profile's — say which profile that is, in the same
     * line. One screen, scoped: there is no second settings screen per profile.
     */
    private fun applyHint(rowName: String, title: TextView) {
        val line = SettingsHints.line(rowName, activeProfileName()) ?: return
        val dim = ContextCompat.getColor(this, R.color.hv_text_dim)
        // The head is the row's own label, never a hint stacked on a previous one.
        val head = title.text.toString().substringBefore('\n')
        val s = android.text.SpannableString("$head\n$line")
        s.setSpan(android.text.style.RelativeSizeSpan(0.82f), head.length + 1, s.length, 0)
        s.setSpan(android.text.style.ForegroundColorSpan(dim), head.length + 1, s.length, 0)
        title.text = s
    }

    private fun firstTitle(v: android.view.ViewGroup): TextView? {
        for (i in 0 until v.childCount) {
            val c = v.getChildAt(i)
            if (c is TextView && c !is android.widget.Button) {
                // The Speaker row's title carries an id because its refresh rewrites it.
                val namedTitle = c.id != android.view.View.NO_ID &&
                    runCatching { resources.getResourceEntryName(c.id).endsWith("_title") }.getOrDefault(false)
                if (c.id == android.view.View.NO_ID || namedTitle) return c
            }
            if (c is android.view.ViewGroup) firstTitle(c)?.let { return it }
        }
        return null
    }

    /** WS1: open a nested sub-view (hide the group list, show the section container). */
    /** A banner action may name the section it wants opened. */
    private fun openSectionFromIntent() {
        intent?.getStringExtra("section")
            // #402: a banner cannot open what the Alpha gate keeps unreachable.
            ?.takeUnless { it == SECTION_PRESENCE && !AlphaGate.allows(prefs, AlphaGate.FeatureSet.PRESENCE) }
            ?.let { showSection(it) }
    }

    private fun showSection(section: String) {
        // UI/UX pass: the model store is its own screen, and the home row is its door — one
        // step, not a section holding a single "open the store" row.
        if (section == SECTION_MODELS) { openModelsStore(); return }
        // Transcription folded into Hearing and the screen settings into Appearance: an old
        // link (a banner, the debug harness) still lands where those rows live now.
        val target = when (section) {
            SECTION_STT -> SECTION_SPEECH
            SECTION_APPEARANCE -> SECTION_VISUALS
            else -> section
        }
        currentSection = target
        findViewById<android.view.View>(R.id.grp_list).visibility = android.view.View.GONE
        SECTION_VIEWS.values.forEach { findViewById<android.view.View>(it).visibility = android.view.View.GONE }
        findViewById<android.view.View>(SECTION_VIEWS.getValue(target)).visibility = android.view.View.VISIBLE
        val scroll = findViewById<android.widget.ScrollView>(R.id.settings_scroll)
        scroll.scrollTo(0, 0)
        if (section == SECTION_STT) scroll.post {
            findViewById<android.view.View>(R.id.hdr_recognition)?.let { scroll.scrollTo(0, it.top) }
        }
        // #224: named by what the user is changing — and by the same words as the home row
        // that opened it, read from that row, so the two cannot drift apart.
        findViewById<TextView>(R.id.set_title).text = sectionTitle(target)
        syncPreview()   // #225: the being only renders while its own section is open
    }

    /** The home row's own label for [section] (its first line; the hint is not part of it). */
    private fun sectionTitle(section: String): String {
        val row = SECTION_HOME_ROWS[section] ?: return "Settings"
        return findViewById<android.view.ViewGroup>(row)?.let { firstTitle(it) }
            ?.text?.toString()?.substringBefore('\n') ?: "Settings"
    }

    /** WS1: return to the Settings home (group list). */
    private fun showGroupList() {
        currentSection = null
        findViewById<android.view.View>(R.id.grp_list).visibility = android.view.View.VISIBLE
        SECTION_VIEWS.values.forEach { findViewById<android.view.View>(it).visibility = android.view.View.GONE }
        findViewById<TextView>(R.id.set_title).text = "Settings"
        // Subtitles are a summary of what changed while inside a section.
        refreshModelsVal()
        refreshGrpVals()
        refreshAssistant()   // #329: the role can change in Android's settings while we are away
        syncPreview()   // #225: back on the home list, nothing of the being's is drawing
    }

    private fun bindGroups() {
        findViewById<android.view.View>(R.id.row_grp_models)?.setOnClickListener { openModelsStore() }
        findViewById<android.view.View>(R.id.row_grp_entity)?.setOnClickListener { showSection(SECTION_ENTITY) }
        findViewById<android.view.View>(R.id.row_grp_speech)?.setOnClickListener { showSection(SECTION_SPEECH) }
        findViewById<android.view.View>(R.id.row_grp_tts)?.setOnClickListener { showSection(SECTION_TTS) }
        findViewById<android.view.View>(R.id.row_grp_presence)?.setOnClickListener { showSection(SECTION_PRESENCE) }
        findViewById<android.view.View>(R.id.row_grp_wake)?.setOnClickListener {
            startActivity(android.content.Intent(this, WakeEnrolActivity::class.java))
        }
        findViewById<android.view.View>(R.id.row_grp_visuals)?.setOnClickListener { showSection(SECTION_VISUALS) }
        findViewById<android.view.View>(R.id.row_grp_assistant)?.setOnClickListener { showSection(SECTION_ASSISTANT) }
        findViewById<android.view.View>(R.id.row_grp_about)?.setOnClickListener { showSection(SECTION_ABOUT) }
    }

    // ---- #402/D29: the Alpha gate --------------------------------------------------------

    /** The master switch at the very top of Settings, off by default. Turning it on slides out
     *  the panel of feature sets; tapping the row reopens it. Turning it off dims everything
     *  alpha again — the selections are kept, so turning it back on restores the same sets. */
    private fun bindAlpha() {
        val sw = findViewById<SwitchCompat>(R.id.sw_alpha_master) ?: return
        sw.isChecked = AlphaGate.masterOn(AlphaGate.reader(prefs))
        sw.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(AlphaGate.KEY_MASTER, on).apply()
            refreshAlpha()
            if (on) alphaPanel()
        }
        findViewById<android.view.View>(R.id.row_grp_alpha)?.setOnClickListener {
            if (AlphaGate.masterOn(AlphaGate.reader(prefs))) alphaPanel() else sw.isChecked = true
        }
        refreshAlpha()
    }

    private fun alphaPanel() = AlphaPanel(this, prefs, ::alphaAdvice) { refreshAlpha() }.show()

    /** The hardware check's advice for a set, shown in the panel (#403). Null when there is none. */
    private fun alphaAdvice(set: AlphaGate.FeatureSet): String? = when (set) {
        AlphaGate.FeatureSet.PRESENCE -> HardwareCheck.advice(HardwareCheck.store(prefs))
        else -> null
    }

    /**
     * Dim and disable every alpha surface the gate does not allow — dimmed, not hidden, and a
     * disabled row takes no tap, so it is unreachable. The rows are the Presence group row and
     * the Hearing rows that only mean something with Presence or continuous listening.
     */
    private fun refreshAlpha() {
        val r = AlphaGate.reader(prefs)
        val master = AlphaGate.masterOn(r)
        findViewById<TextView>(R.id.set_alpha_grpval)?.text = if (!master)
            "Off · unfinished features stay out of the way"
        else {
            val n = AlphaGate.selectedCount(r)
            "On · $n of ${AlphaGate.FeatureSet.entries.size} selected — tap to choose"
        }
        val presence = AlphaGate.allows(r, AlphaGate.FeatureSet.PRESENCE)
        val listening = AlphaGate.allows(r, AlphaGate.FeatureSet.CONTINUOUS_LISTENING)
        gate(R.id.row_grp_presence, presence)
        gate(R.id.row_er_hears, presence)
        gate(R.id.row_mic_continuous, listening)
        gate(R.id.row_mic_semantic, listening)
        gate(R.id.row_backchannel, AlphaGate.allows(r, AlphaGate.FeatureSet.TURN_TAKING))
        gate(R.id.row_call_quiet, AlphaGate.allows(r, AlphaGate.FeatureSet.QUIET_CALLS))
        if (!presence && currentSection == SECTION_PRESENCE) showGroupList()
        refreshGrpVals()
    }

    /** #245: the acknowledgement rate — Off / Sparse / Normal, one tap to the next. Behind the
     *  turn-taking set; the row is dimmed and takes no tap without it. */
    private fun bindBackchannelRate() {
        val value = findViewById<TextView>(R.id.set_backchannel_val) ?: return
        fun show() { value.text = Backchannel.Rate.of(prefs.getString(Backchannel.KEY_RATE, null)).label }
        show()
        findViewById<android.view.View>(R.id.row_backchannel)?.setOnClickListener {
            val all = Backchannel.Rate.entries
            val now = Backchannel.Rate.of(prefs.getString(Backchannel.KEY_RATE, null))
            prefs.edit().putString(Backchannel.KEY_RATE, all[(now.ordinal + 1) % all.size].key).apply()
            show()
        }
    }

    /**
     * #325: off by default. Switching it on asks for Do Not Disturb access — the one moment the
     * request makes sense — and the status line says whether the call will actually be silenced.
     */
    private fun bindCallQuiet() {
        val sw = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.set_call_quiet) ?: return
        sw.isChecked = prefs.getBoolean(CallQuiet.KEY, CallQuiet.DEFAULT_ON)
        refreshCallQuiet()
        sw.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(CallQuiet.KEY, on).apply()
            if (on && !CallQuiet.granted(this)) {
                try { startActivity(CallQuiet.accessIntent()) } catch (_: Throwable) {}
            }
            refreshCallQuiet()
        }
    }

    private fun refreshCallQuiet() {
        val status = findViewById<TextView>(R.id.set_call_quiet_status) ?: return
        val on = prefs.getBoolean(CallQuiet.KEY, CallQuiet.DEFAULT_ON)
        status.text = when {
            !on -> "Off — notifications behave as they always do"
            CallQuiet.granted(this) -> "On — Do Not Disturb (your exceptions) while a call is open"
            else -> "Needs Do Not Disturb access — switch off and on to ask again"
        }
    }

    private fun gate(id: Int, allowed: Boolean) {
        val v = findViewById<android.view.View>(id) ?: return
        v.alpha = if (allowed) 1f else ALPHA_DIM
        setEnabledDeep(v, allowed)
    }

    private fun setEnabledDeep(v: android.view.View, on: Boolean) {
        v.isEnabled = on
        if (v is android.view.ViewGroup) for (i in 0 until v.childCount) setEnabledDeep(v.getChildAt(i), on)
    }

    /** #329: the assistant row opens a section, not a dialog — the state, the request, the
     *  way out, and the part Android does not control all need a place to be said. */
    private fun bindAssistant() {
        findViewById<android.view.View>(R.id.row_assistant_request)?.setOnClickListener { requestAssistantRole() }
        findViewById<android.view.View>(R.id.row_assistant_settings)?.setOnClickListener {
            startActivity(AssistantRole.systemSettingsIntent(this))
        }
        refreshAssistant()
    }

    /** Read the role from Android every time; a cached "yes" would outlive the user's change. */
    private fun refreshAssistant() {
        val state = AssistantRole.state(this)
        val summary = AssistantRole.summary(state, AssistantRole.otherHolderLabel(this))
        findViewById<TextView>(R.id.set_assistant_status)?.text = summary
        findViewById<TextView>(R.id.set_assistant_grpval)?.text = summary
        val request = findViewById<android.view.View>(R.id.row_assistant_request)
        request?.isEnabled = state != AssistantRole.State.HELD && state != AssistantRole.State.UNAVAILABLE
        request?.alpha = if (request?.isEnabled == true) 1f else 0.4f
        findViewById<TextView>(R.id.set_assistant_action)?.text =
            when (state) {
                AssistantRole.State.HELD -> "on"
                AssistantRole.State.UNAVAILABLE -> "unavailable"
                AssistantRole.State.OTHER -> "ask android"
            }
    }

    /**
     * The ONLY request path in the app, and it is a tap on one row: Android's own consent
     * dialog decides. Nothing is granted by this call, and nothing runs at first launch.
     */
    private fun requestAssistantRole() {
        val intent = AssistantRole.requestIntent(this)
        if (intent == null) {
            Toast.makeText(this, "This Android build has no assistant role to grant", Toast.LENGTH_LONG).show()
            return
        }
        startActivityForResult(intent, REQ_ASSISTANT)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_ASSISTANT) return
        refreshAssistant()
        if (AssistantRole.state(this) == AssistantRole.State.HELD) {
            Toast.makeText(this, "Vox is the phone's assistant", Toast.LENGTH_SHORT).show()
            return
        }
        // #329: the other assistant kept the role. Say so and hand over the screen that can
        // change it — a dialog that just disappeared would look like nothing happened.
        AlertDialog.Builder(this)
            .setTitle("Android kept the current assistant")
            .setMessage(
                "The assistant gesture still opens the other app. Only Android can change that: " +
                    "its digital assistant settings list every app that qualifies, Hermes Vox among them."
            )
            .setPositiveButton("Open Android settings") { _, _ ->
                startActivity(AssistantRole.systemSettingsIntent(this))
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    private fun bindSectionRestoreRows() {
        bindRestoreRow(R.id.row_restore_entity, GROUP_ENTITY, "Entity & Connection")
        // One section, one restore: Appearance resets the screen and the being together.
        bindRestoreRow(R.id.row_restore_visuals, GROUP_VISUALS, "Appearance")
        bindRestoreRow(R.id.row_restore_presence, GROUP_PRESENCE, "Presence")
        bindRestoreRow(R.id.row_restore_about, GROUP_ABOUT, "About & Diagnostics")
    }

    /** #285: put the app's own description on the agent. The install is the gateway's — the
     *  app asks the agent to do it, or hands over the exact command (AppSkillInstall). */
    private fun bindAppSkill() {
        findViewById<LinearLayout>(R.id.row_app_skill)?.setOnClickListener {
            val cmd = AppSkillInstall.command(prefs, versionName())
            AlertDialog.Builder(this)
                .setTitle("Teach your agent this app")
                .setMessage("Your agent can answer \"where is that setting?\" from this app's own description, " +
                    "once it is installed on your gateway.\n\nAsk the agent to install it, or run this on " +
                    "the machine running Hermes:\n\n$cmd\n\nThen ask: \"${AppSkill.CHECK_QUESTION}\"")
                .setPositiveButton("Ask the agent") { _, _ -> askAgentToInstall() }
                .setNeutralButton("Copy command") { _, _ -> AppSkillInstall.copy(this, cmd) }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    private fun askAgentToInstall() {
        val s = MainActivity.session ?: run {
            Toast.makeText(this, "Connect to your gateway first", Toast.LENGTH_LONG).show(); return
        }
        AppSkillInstall.ask(this, s, versionName(), AppSkill.profileOf(prefs.getString("url", "").orEmpty()))
    }

    private fun bindEntity() {
        // #113: "New conversation" wipes the live conversation (and ends any active
        // call) — destructive, so confirm first. It reads "clear", not "reset": only
        // the "Restore defaults (…)" rows use the word reset.
        findViewById<LinearLayout>(R.id.row_reset).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("New conversation")
                .setMessage("Start fresh? This clears the current conversation's context and ends any active call — it can't be undone.")
                .setPositiveButton("Clear") { _, _ ->
                    try {
                        MainActivity.resetActiveConversation()
                        Toast.makeText(this, "New conversation (context cleared)", Toast.LENGTH_SHORT).show()
                    } catch (_: Throwable) {}
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        findViewById<LinearLayout>(R.id.row_entity).setOnClickListener {
            val view = layoutInflater.inflate(R.layout.dialog_entity, null)
            val eurl = view.findViewById<EditText>(R.id.d_url)
            val emodel = view.findViewById<EditText>(R.id.d_model)
            val ekey = view.findViewById<EditText>(R.id.d_key)
            val escope = view.findViewById<EditText>(R.id.d_scope)
            // T7: `d_model` is the entity's name here, not the request route. It is a
            // read-only display refreshed from the host, so a typed name can never
            // contradict the host. The route stays `model` and is chosen in /models.
            emodel.hint = getString(R.string.hv_entity_hint)
            emodel.isFocusable = false
            emodel.isClickable = false
            emodel.isCursorVisible = false
            emodel.keyListener = null
            emodel.setText(storedEntityName())
            eurl.setText(prefs.getString("url", ""))
            ekey.setText(SecureStore.decrypt(prefs.getString("key", "").orEmpty()).orEmpty())
            escope.setText(prefs.getString(SessionScope.PREF, "").orEmpty())
            val dlg = AlertDialog.Builder(this)
                .setTitle("Entity")
                .setView(view)
                .setPositiveButton("Save", null)
                .setNegativeButton("Cancel", null)
                .create()
            // Validate on the button, not after dismissal: a scope the gateway
            // would reject must never be silently normalized into a DIFFERENT
            // identity (that is somebody else's memory), so the dialog stays open
            // and says why. Blank is always fine — it means "don't declare one".
            dlg.setOnShowListener {
                dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                    val reason = SessionScope.validationError(escope.text.toString())
                    if (reason != null) {
                        Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    // The display is not a setting: Save writes the connection, never
                    // the host-derived name back into the request route (`model`).
                    prefs.edit().putString("url", eurl.text.toString().trim())
                        .putString("key", (SecureStore.encrypt(ekey.text.toString().trim()) ?: ekey.text.toString().trim()))
                        .putString(SessionScope.PREF, SessionScope.normalize(escope.text.toString())).apply()
                    refreshEntityVal()
                    dlg.dismiss()
                }
            }
            dlg.show()
            // The same rule as onboarding: the host is the authority. Ask the probe
            // the connection flow already makes, and adopt the name it gives.
            refreshEntityName(dlg, view, eurl.text.toString().trim(), ekey.text.toString().trim())
        }
        refreshEntityVal()
        bindVoxRow()
        bindAgentModel()
    }

    /**
     * The agent's model, for this profile (per-profile pass). It used to be reachable only by
     * typing `/models` on the call screen; it belongs beside the connection, because which model
     * the agent thinks with is part of which agent you are talking to. The row opens the same
     * [ModelSheet] the command does — one switcher, two doors.
     */
    private fun bindAgentModel() {
        findViewById<LinearLayout>(R.id.row_agent_model)?.setOnClickListener { pickAgentModel() }
        refreshAgentModel()
    }

    /** The route, as words: "DeepSeek V4 Flash" rather than `deepseek-v4-flash`. */
    private fun refreshAgentModel() {
        findViewById<TextView>(R.id.set_agent_model_val)?.text =
            ModelChoices.displayName(prefs.getString("model", "").orEmpty().ifBlank { "hermes-agent" })
    }

    private fun pickAgentModel() {
        val s = MainActivity.session ?: run {
            Toast.makeText(this, "Connect to your gateway first", Toast.LENGTH_LONG).show(); return
        }
        ModelSheet(this,
            load = { s.modelOptions() },
            current = { prefs.getString("model", "").orEmpty() to prefs.getString("provider", "").orEmpty() },
        ) { p, m ->
            // Choose it on the live session so the NEXT turn uses it, and persist it so a switch,
            // a restart and the switcher's own line agree. This is this profile's model.
            val modelId = m?.id ?: p.slug
            prefs.edit().putString("model", modelId).putString("provider", p.slug).apply()
            val ok = try { s.setModel(modelId); s.setProvider(p.slug); true } catch (_: Throwable) { false }
            if (!ok) {
                Toast.makeText(this, "Couldn't switch the model", Toast.LENGTH_LONG).show()
            } else {
                s.resetConversation()
                LatencyStats.resetSessionTurns()
                refreshAgentModel()
                Toast.makeText(this, ModelChoices.switchedLine(p, m), Toast.LENGTH_SHORT).show()
            }
        }.show()
    }

    /** ER Phase 3: the VOX.md bridge row. Resync = PULL the gateway agent's
     *  voice-export over the same directive turn /compress uses, validate,
     *  mirror locally. Pull-only invariant: the device never pushes identity.
     *  Owner rule (a): with Presence off this whole surface is inert — the rows are
     *  not shown, and nothing is fetched, mirrored, prompted for or reported. */
    private fun bindVoxRow() {
        findViewById<LinearLayout>(R.id.row_vox).setOnClickListener { resyncVox() }
        // 0.6.2: view the mirror — what the on-device soul actually reads.
        findViewById<LinearLayout>(R.id.row_vox_view).setOnClickListener {
            if (!PresenceGate.enabled(prefs)) return@setOnClickListener
            val profile = VoxMirror.activeProfile(prefs)
            val doc = VoxMirror.read(this)
            AlertDialog.Builder(this)
                .setTitle(if (profile.isBlank()) "Mirrored VOX.md" else "Mirrored VOX.md · $profile")
                .setMessage(doc?.take(2000) ?: "Not mirrored yet — Resync VOX.md pulls it from the entity.")
                .setPositiveButton("OK", null).show()
        }
        bindErControls()
        refreshVoxRows()
    }

    /** Rule (a), on screen: the VOX.md rows exist only while Presence is on. Off hides
     *  them and reports nothing about the mirror (it is not deleted — turning Presence
     *  back on finds the active profile's document where it was). */
    private fun refreshVoxRows() {
        val on = PresenceGate.enabled(prefs)
        val v = if (on) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<android.view.View>(R.id.row_vox)?.visibility = v
        findViewById<android.view.View>(R.id.row_vox_view)?.visibility = v
        if (on) refreshVoxVal()
    }

    /** 0.6.2: the presence runtime controls — every tunable the presence layer reads,
     *  exposed. They matter when Presence runs; the layer's on/off switch is above. */
    private fun bindErControls() {
        val swPresence = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.sw_er_presence)
        val swBarge = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.sw_er_semantic_barge)
        val sbEcho = findViewById<SeekBar>(R.id.sb_er_echo)
        val tvEcho = findViewById<TextView>(R.id.set_er_echo_val)

        bindPresenceEnabled()
        // A2: how hard the mind thinks on a voice turn. The gateway takes it per request
        // (model_options.reasoning), so this needs no model change and no restart: the next
        // turn runs at the chosen effort. Low is the default because a spoken turn is waiting.
        val rowReasoning = findViewById<android.view.View>(R.id.row_er_reasoning)
        val tvReasoning = findViewById<TextView>(R.id.set_er_reasoning_val)
        fun reasoningLabel(token: String) = when (token) {
            "off" -> "Off"
            "medium" -> "Medium"
            "high" -> "High"
            else -> "Low"
        }
        val reasoningTokens = arrayOf("off", "low", "medium", "high")
        tvReasoning.text = reasoningLabel(prefs.getString(VoiceTurn.PREF_REASONING, "low") ?: "low")
        rowReasoning.setOnClickListener {
            val cur = prefs.getString(VoiceTurn.PREF_REASONING, "low") ?: "low"
            AlertDialog.Builder(this)
                .setTitle("Reasoning effort on a voice call")
                .setSingleChoiceItems(
                    arrayOf("Off (fastest)", "Low", "Medium", "High (deepest)"),
                    reasoningTokens.indexOf(cur).coerceAtLeast(0)
                ) { d, which ->
                    prefs.edit().putString(VoiceTurn.PREF_REASONING, reasoningTokens[which]).apply()
                    tvReasoning.text = reasoningLabel(reasoningTokens[which])
                    d.dismiss()
                    Toast.makeText(this, "Voice turns now reason at ${reasoningTokens[which]} effort", Toast.LENGTH_SHORT).show()
                }
                .show()
        }
        swPresence.isChecked = prefs.getBoolean("er_presence", true)
        swPresence.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("er_presence", checked).apply()
            Toast.makeText(this, if (checked) "Soul narration on" else "Soul narration off (ER stays wired, quieter)",
                Toast.LENGTH_SHORT).show()
        }
        swBarge.isChecked = prefs.getBoolean("er_semantic_barge", true)
        swBarge.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("er_semantic_barge", checked).apply()
            // The onboarding-explanation surface: what this toggle actually trades.
            Toast.makeText(this,
                if (checked) "Semantic barge on — \"take your time\" won't cancel the entity's work"
                else "Every interruption cancels the entity's work (pre-ER behavior)",
                Toast.LENGTH_LONG).show()
        }
        // The soul hears the caller's audio (Gemma's own audio encoder, on-device) and reads their
        // tone — the mood that steers the voice's delivery and the vibe the mind is told.
        val swHears = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.sw_er_hears)
        swHears.isChecked = prefs.getBoolean(SoulAudio.PREF, true)
        swHears.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(SoulAudio.PREF, checked).apply()
            Toast.makeText(this,
                if (checked) "The soul hears your tone — audio stays on this phone"
                else "The soul reads your words only",
                Toast.LENGTH_SHORT).show()
        }
        // 0.6.7 Tier 1: the narration register (silent / sounds / spoken).
        findViewById<LinearLayout>(R.id.row_er_voice).setOnClickListener {
            val modes = arrayOf("Silent (motion only)", "Sounds (natural mm/breath)", "Spoken (sentence fillers)")
            val tokens = arrayOf("silent", "sounds", "spoken")
            val cur = prefs.getString("er_presence_voice", "sounds") ?: "sounds"
            AlertDialog.Builder(this)
                .setTitle("Narration voice")
                .setSingleChoiceItems(modes, tokens.indexOf(cur).coerceAtLeast(0)) { d, which ->
                    prefs.edit().putString("er_presence_voice", tokens[which]).apply()
                    findViewById<TextView>(R.id.set_er_voice_val).text = modes[which].substringBefore(" (")
                    MainActivity.pushPresenceVoiceMode(tokens[which])
                    d.dismiss()
                    Toast.makeText(this, when (tokens[which]) {
                        "silent" -> "Silence + the being's motion — the most natural"
                        "sounds" -> "Natural sounds (mm, breath) on a separate voice path"
                        else -> "Spoken fillers — full sentences only"
                    }, Toast.LENGTH_LONG).show()
                }
                .setNegativeButton("Cancel", null).show()
        }
        findViewById<TextView>(R.id.set_er_voice_val).text =
            when (val m = prefs.getString("er_presence_voice", "sounds") ?: "sounds") {
                "silent" -> "Silent"; "spoken" -> "Spoken"; else -> "Sounds"
            }
        // "Presence while working": one register for how often the entity speaks while the mind
        // works — the mid-run aside interval and the filler cap (PresenceCadence). Replaces the raw
        // filler-density slider, which it migrates from until a register is chosen.
        val tvDensity = findViewById<TextView>(R.id.set_er_density_val)
        tvDensity.text = densityLabel(currentDensity())
        findViewById<LinearLayout>(R.id.row_er_density).setOnClickListener {
            val all = PresenceCadence.Density.entries
            AlertDialog.Builder(this)
                .setTitle("Presence while working")
                .setSingleChoiceItems(all.map { densityChoice(it) }.toTypedArray(), all.indexOf(currentDensity())) { d, which ->
                    prefs.edit().putString(PresenceCadence.PREF, all[which].key).apply()
                    tvDensity.text = densityLabel(all[which])
                    d.dismiss()
                }
                .setNegativeButton("Cancel", null).show()
        }
        // #417: the report block. Everything on the clipboard is a setting, a device fact or
        // a measured number — no transcript text, key, URL, hostname or path. Omitted metrics
        // stay omitted rather than being printed as an invented zero.
        findViewById<LinearLayout>(R.id.row_presence_diag).setOnClickListener {
            val block = PresenceDiagnosticCollect.collect(
                this, prefs, versionName(), currentDensity())
            try {
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("vox-diag", block))
                Toast.makeText(this,
                    "Diagnostic summary copied — safe to paste in a public report",
                    Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {
                Toast.makeText(this, "Could not copy the diagnostic summary",
                    Toast.LENGTH_SHORT).show()
            }
        }
        // Echo guard slider: 0-6 steps -> 0,300,500,700,1000,1500,2500ms (0 = off).
        val echoSteps = intArrayOf(0, 300, 500, 700, 1000, 1500, 2500)
        val curEcho = prefs.getFloat("er_echo_skip_ms", 700f).toInt()
        sbEcho.progress = echoSteps.indexOfFirst { it == curEcho }.coerceAtLeast(0)
        tvEcho.text = echoLabel(echoSteps[sbEcho.progress])
        sbEcho.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                tvEcho.text = echoLabel(echoSteps[p])
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {
                prefs.edit().putFloat("er_echo_skip_ms", echoSteps[sbEcho.progress].toFloat()).apply()
            }
        })
    }

    /** #222/D16: the one switch that means Presence on or off. The absence of the
     *  layer is reported here and nowhere else — this is the only place a user can act
     *  on it. */
    private fun bindPresenceEnabled() {
        val sw = findViewById<SwitchCompat>(R.id.sw_presence_enabled)
        sw.isChecked = PresenceGate.chosen(prefs)
        sw.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PresenceGate.KEY_PRESENCE_ENABLED, checked).apply()
            // Rule (a): the VOX.md workflow appears and disappears with this switch. Turning
            // it ON is also when the pre-profile device-wide mirror is migrated, so the
            // document the user already had is the one the active profile starts with.
            if (checked) VoxMirror.migrate(this)
            refreshVoxRows()
            refreshPresenceRow()
            if (!checked) return@setOnCheckedChangeListener
            if (!ModelCatalog.isInstalled(this, "gemma-e2b")) {
                AlertDialog.Builder(this)
                    .setTitle("Presence needs its model")
                    .setMessage("The being, its asides and its voice run on an on-device " +
                        "model. Download it in Voice models — Presence starts as soon as " +
                        "it is there.")
                    .setPositiveButton("Download") { _, _ -> openModelsStore() }
                    .setNegativeButton("Later", null)
                    .show()
            } else maybeBootstrapVox()
        }
        refreshPresenceRow()
    }

    private fun presenceState(): PresenceGate.State = PresenceGate.state(
        enabled = PresenceGate.enabled(prefs),
        installed = ModelCatalog.isInstalled(this, "gemma-e2b"),
        canStart = PresenceGate.modelCanStart,
    )

    /** The one-line explanation under the switch. Every branch names why, and the two
     *  the user can fix are tappable. */
    private fun refreshPresenceRow() {
        val tv = findViewById<TextView>(R.id.set_presence_status) ?: return
        tv.setOnClickListener(null)
        tv.isClickable = false
        tv.isFocusable = false
        tv.text = when (presenceState()) {
            PresenceGate.State.ON ->
                if (prefs.getBoolean("er_presence", true))
                    "On · " + PresenceCadence.resolve(
                        prefs.getString(PresenceCadence.PREF, null), null).key
                else "On · quiet"
            PresenceGate.State.OFF ->
                "Off — turn on to give the agent a body and asides"
            PresenceGate.State.NO_MODEL -> {
                tv.isClickable = true
                tv.isFocusable = true
                tv.setOnClickListener { openModelsStore() }
                "On, but the model is not downloaded — tap to download it in Voice models"
            }
            PresenceGate.State.CANNOT_START ->
                "On, but the model cannot start on this device — Presence stays off here"
        }
    }

    private fun currentDensity(): PresenceCadence.Density = PresenceCadence.resolve(
        prefs.getString(PresenceCadence.PREF, null),
        if (prefs.contains(PresenceCadence.LEGACY_PREF)) prefs.getInt(PresenceCadence.LEGACY_PREF, 2) else null)

    private fun densityLabel(d: PresenceCadence.Density): String = when (d) {
        PresenceCadence.Density.QUIET -> "Quiet"
        PresenceCadence.Density.CONVERSATIONAL -> "Conversational"
        PresenceCadence.Density.CHATTY -> "Chatty"
    }

    private fun densityChoice(d: PresenceCadence.Density): String = densityLabel(d) + when (d) {
        PresenceCadence.Density.QUIET -> " — a word now and then"
        PresenceCadence.Density.CONVERSATIONAL -> " — checks in every so often"
        PresenceCadence.Density.CHATTY -> " — keeps you company the whole time"
    }

    private fun echoLabel(ms: Int): String = if (ms == 0) "off" else "${ms}ms"

    /** 0.6.2: the semantic-barge toggle + presence toggle are read at the
     *  decision sites (VoiceController bargeIn / runStreamedTurn); see the
     *  prefs keys er_semantic_barge / er_presence there. */

    private fun refreshVoxVal() {
        val v = findViewById<TextView>(R.id.set_vox_val) ?: return
        // Rule (a): with Presence off the app does not report on VOX.md at all.
        if (!PresenceGate.enabled(prefs)) { v.text = ""; return }
        val profile = VoxMirror.activeProfile(prefs)
        v.text = when (val m = VoxSoul.mirrorStatus(VoxMirror.read(this))) {
            is VoxSoul.Mirror.Present ->
                "mirrored" + (if (m.name.isBlank()) "" else " · ${m.name}") +
                    (if (profile.isBlank()) "" else " · $profile")
            is VoxSoul.Mirror.Absent ->
                if (profile.isBlank()) "not mirrored" else "not mirrored for $profile — tap to pull it"
        }
    }

    /** ER Phase 3 bootstrap: first switch-on of Presence. When the ACTIVE PROFILE has no
     *  valid mirror, pull VOX.md right away (the resync flow) and surface the honest state
     *  if it fails — the soul "can't find its voice" is a visible condition, never a silent
     *  proceed. With Presence off this does not run at all (owner rule a). */
    private fun maybeBootstrapVox() {
        if (!PresenceGate.enabled(prefs)) return
        VoxMirror.migrate(this)
        if (VoxSoul.mirrorStatus(VoxMirror.read(this)) is VoxSoul.Mirror.Present) return
        val profile = VoxMirror.activeProfile(prefs)
        AlertDialog.Builder(this)
            .setTitle("Presence — meet your entity's voice")
            .setMessage(
                "Presence is the being on the phone: it speaks asides in your agent's " +
                    "own voice while the mind works. It needs VOX.md — your agent's " +
                    "voice-export, authored by the agent itself" +
                    (if (profile.isBlank()) "" else ", for the profile \"$profile\"") +
                    ".\n\nPull it from the gateway now?")
            .setPositiveButton("Pull VOX.md") { _, _ -> resyncVox() }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun resyncVox(retryNote: String? = null) {
        // Rule (a): every entry point is inert with Presence off. This is the last line of
        // defence — the row is hidden — and it means no dialog, no toast, no request.
        if (!PresenceGate.enabled(prefs)) return
        VoxMirror.migrate(this)
        // The profile is captured BEFORE the turn: the document belongs to the entity that
        // answered, even if the user switches while the ask is in flight.
        val profile = VoxMirror.activeProfile(prefs)
        val u = prefs.getString("url", "").orEmpty()
        val k = prefs.getString("key", "").orEmpty()
        if (u.isBlank() || GatewayKey.isMissing(k)) {
            AlertDialog.Builder(this)
                .setTitle("Resync VOX.md")
                .setMessage("Connect the entity first (Entity endpoint + API key), then resync.")
                .setPositiveButton("OK", null).show()
            return
        }
        val label = findViewById<TextView>(R.id.set_vox_val)
        label.text = "asking the entity…"
        // Off the UI thread: the directive turn is a real /v1/responses call
        // (turnStored blocks up to 120s) — same shape as /compress.
        kotlin.concurrent.thread {
            // M6: declared like the live session (scope + provider), not a bare one.
            val s = EntitySession.build(u, SecureStore.decrypt(k) ?: k, { prefs.getString(it, "").orEmpty() })
            // #220: one call does extract → repair → validate, so a document that arrives
            // inside a fence or behind a courtesy line is mirrored on the FIRST attempt
            // instead of costing the user a second button press. The retry below stays as
            // the fallback for a reply that really has no document in it. VoxSync.refetch is
            // the same path the first-call ask uses, so the two cannot drift.
            val a = VoxSync.refetch(filesDir, profile, presenceOn = PresenceGate.enabled(prefs),
                retryNote = retryNote, log = { VoxLog.er(it) },
                ask = { note -> VoxSync.ask(s, note, { VoxLog.er(it) }) })
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                refreshVoxVal()
                if (a.need == VoxSync.Need.OFF) return@runOnUiThread   // Presence went off mid-ask
                if (a.ready) {
                    Toast.makeText(this, "VOX.md synced from the entity", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                val reason = a.reason ?: "the sync did not complete"
                val replyEvidence = a.reply?.trim()
                    ?.replace(Regex("\\s+"), " ")
                    ?.take(240)
                    ?.ifBlank { "(reply was blank)" }
                    ?: "(no reply)"
                if (a.outcome is VoxSoul.Outcome.Rejected) {
                    AlertDialog.Builder(this)
                        .setTitle("VOX.md sync failed")
                        .setMessage(
                            if (a.reply.isNullOrBlank())
                                "The entity didn't answer (connection?). Check /health and try again."
                            else "The entity's reply wasn't a usable VOX.md: $reason. " +
                                "It said: \"$replyEvidence\". Its next attempt rides the same " +
                                "conversation, so it remembers the format — one more try usually " +
                                "lands it.")
                        .setPositiveButton("Try again") { _, _ -> resyncVox(retryNote = "Your last reply wasn't a usable VOX.md (reason: $reason). Reply with the VOX.md document itself and nothing else: start at '# Contract' and copy its six rules verbatim, then '# Soul' with every field filled in. No preamble, no commentary, no code fence.") }
                        .setNegativeButton("Later", null).show()
                } else {
                    // Valid document, unwritable file: say so rather than claim a sync.
                    AlertDialog.Builder(this)
                        .setTitle("VOX.md sync failed")
                        .setMessage("The entity's VOX.md was valid but could not be written to this phone (storage?). The mirror is unchanged — try again.")
                        .setPositiveButton("Try again") { _, _ -> resyncVox() }
                        .setNegativeButton("Later", null).show()
                }
            }
        }
    }

    private fun refreshEntityVal() {
        val u = prefs.getString("url", "").orEmpty()
        if (u.isBlank()) {
            findViewById<TextView>(R.id.set_entity_val).text = "—"
            return
        }
        // A declared scope is an identity, and an identity you cannot see is one
        // you cannot check — the VOX.md row surfaces its state, so this row
        // states whether a scope is in play (and which one). The entity's name is
        // the host's (T7); the model id is only the fallback until a verify names it.
        val scope = SessionScope.normalize(prefs.getString(SessionScope.PREF, "").orEmpty())
        val who = if (scope.isEmpty()) "no scope" else "scope · $scope"
        findViewById<TextView>(R.id.set_entity_val).text = "$u · ${storedEntityName()} · $who"
    }

    /** The host's adopted name, or the old `model` value until a verify gives one. */
    private fun storedEntityName(): String =
        prefs.getString(Profiles.K_AGENT, "").orEmpty().ifBlank {
            prefs.getString("model", "hermes-agent").orEmpty().ifBlank { "hermes-agent" }
        }

    /**
     * T7: refresh the dialog's entity name from the host, using the same
     * `GET /v1/models` probe the connection flow already makes — one request, on
     * open, off the UI thread. A 200 with `data[0].id` is adopted (and saved as
     * `agent_name`); a 200 with no id says [HostEntity.FALLBACK_LINE] and keeps the
     * stored name; a failed probe keeps it silently. Nothing is invented, and a
     * closed dialog is not written to.
     */
    private fun refreshEntityName(dlg: AlertDialog, view: android.view.View, url: String, key: String) {
        val field = view.findViewById<EditText>(R.id.d_model)
        val note = view.findViewById<TextView>(R.id.d_entity_note)
        val stored = storedEntityName()
        val u = url.trim()
        if (u.isBlank() || key.isBlank()) return
        note.text = "asking the host…"
        note.visibility = android.view.View.VISIBLE
        kotlin.concurrent.thread {
            val answer = try {
                val body = com.hermesvox.core.HermesSession(
                    u, key, prefs.getString("model", "hermes-agent").orEmpty().ifBlank { "hermes-agent" },
                ).ping()
                HostEntity.answer(200, body)
            } catch (_: Throwable) {
                HostEntity.Answer.Refused(-1, "no answer")
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || !dlg.isShowing) return@runOnUiThread
                when (answer) {
                    is HostEntity.Answer.Named -> {
                        field.setText(answer.name)
                        // Only a verified host name is adopted; an existing saved name
                        // is not rewritten by a probe that failed or said nothing.
                        prefs.edit().putString(Profiles.K_AGENT, answer.name).apply()
                        note.visibility = android.view.View.GONE
                    }
                    is HostEntity.Answer.Unnamed -> {
                        field.setText(HostEntity.name(answer, stored).orEmpty())
                        note.text = HostEntity.FALLBACK_LINE
                        note.visibility = android.view.View.VISIBLE
                    }
                    is HostEntity.Answer.Refused -> note.visibility = android.view.View.GONE
                }
            }
        }
    }

    private fun bindFlows() {
        findViewById<LinearLayout>(R.id.row_stt).setOnClickListener {
            pick("Speech-to-text (backend)",
                arrayOf("On-device (offline)", "Platform (Google)", "Remote (server)"),
                arrayOf(ModelCatalog.BACKEND_ONDEVICE, ModelCatalog.BACKEND_PLATFORM, ModelCatalog.BACKEND_REMOTE),
                ModelCatalog.KEY_STT_BACKEND, R.id.set_stt_val) { refreshSttRemotePanel() }
        }
        findViewById<LinearLayout>(R.id.row_stt_model).setOnClickListener { pickSttModel() }
        // 0.7.3: the speech-pipeline CPU budget. Stored as an INT (0 = Auto), so it
        // cannot ride the String-based pick() helper. Applies on the next voice-model
        // load — numThreads is baked into the recognizer/synth session at build.
        findViewById<LinearLayout>(R.id.row_stt_threads).setOnClickListener {
            val labels = arrayOf("Auto", "1 thread", "2 threads", "3 threads", "4 threads")
            val vals = intArrayOf(VoxThreads.AUTO, 1, 2, 3, 4)
            val cur = prefs.getInt(VoxThreads.PREF, VoxThreads.AUTO)
            AlertDialog.Builder(this)
                .setTitle("Voice CPU threads (STT + TTS)")
                .setSingleChoiceItems(labels, vals.indexOf(cur).coerceAtLeast(0)) { d, which ->
                    prefs.edit().putInt(VoxThreads.PREF, vals[which]).apply()
                    findViewById<TextView>(R.id.set_stt_threads_val).text = labels[which]
                    d.dismiss()
                    Toast.makeText(this, "Applies on the next voice-model load", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null).show()
        }
        findViewById<LinearLayout>(R.id.row_tts).setOnClickListener {
            pick("Text-to-speech",
                arrayOf("Supertonic (on-device, recommended)", "Piper (on-device, lighter)", "System (fallback)"),
                arrayOf("supertonic", "piper", "system"), "tts", R.id.set_tts_val) { syncSpeakerRow(); previewVoice() }
        }
        // Voice = the SYNTHESIS REGISTER (system/bright/deep). The old "warm"
        // option was removed — WarmTts is a no-op stub, so it never produced
        // audio (never offer an option that doesn't work). The engines now honor
        // this pref (see voiceRegister), so the picker really affects output.
        // The engine's built-in voice. The row follows the ENGINE selected by the
        // `tts` pref: Supertonic's ten measured voices (0-4 female F1-F5, 5-9 male
        // M1-M5) or Piper's curated eight plus a numeric id for the rest (LibriTTS-R
        // medium has hundreds — not a list anyone scrolls). Either way a choice
        // previews immediately and is stored per engine (VoiceSpeakers).
        findViewById<LinearLayout>(R.id.row_tts_speaker).setOnClickListener { pickSpeaker() }
        findViewById<LinearLayout>(R.id.row_voice).setOnClickListener {
            pick("Delivery", arrayOf("Natural", "Bright", "Deep"),
                arrayOf("system", "bright", "deep"), "voice", R.id.set_voice_val) { previewVoice() }
        }

        // Per sub-menu RESTORE DEFAULTS (via the central restoreDefaults helper).
        // #112 reset scopes: every setting belongs to exactly ONE group. Presence
        // resets with GROUP_PRESENCE (row_restore_presence) and the voice register
        // resets with GROUP_TTS (row_tts_reset, relabeled "TTS & Voice").
        bindRestoreRow(R.id.row_tts_reset, GROUP_TTS, "Voice")
        val barge = findViewById<SwitchCompat>(R.id.set_bargein)
        barge.isChecked = prefs.getBoolean("duplex", true)
        barge.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("duplex", on).apply() }
        val speak = findViewById<SwitchCompat>(R.id.set_speak)
        speak.isChecked = prefs.getBoolean("speak_responses", true)
        speak.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("speak_responses", on).apply() }
        val devc = findViewById<SwitchCompat>(R.id.set_devconsole)
        devc.isChecked = prefs.getBoolean("dev_console", false)
        devc.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("dev_console", on).apply() }
        val ltr = findViewById<SwitchCompat>(R.id.set_logtranscripts)
        ltr.isChecked = prefs.getBoolean("log_transcripts", false)
        ltr.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("log_transcripts", on).apply() }
        val dbl = findViewById<SwitchCompat>(R.id.set_debuglog)
        dbl.isChecked = prefs.getBoolean("debug_log", false)
        dbl.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("debug_log", on).apply(); VoxLog.setDebugFile(on) }
        // Memory-budget A/B (docs/BASELINE.md, "Memory"): DEV ONLY, and reachable only by a
        // long-press on the debug-log row in the developer section. Both settings default to
        // the shipped behaviour (8192-token cache, engine kept), so a phone that never opens
        // this dialog is byte-for-byte 0.9.1 in its memory behaviour.
        dbl.setOnLongClickListener { memoryBudgetDialog(); true }
        refreshModelsVal()
        bindSttRemote()

        refreshFlowVals()
        refreshGrpVals()   // #115: populate the Settings-home row subtitles
        bindAdvancedDisclosures()  // #118/#224: one collapsed disclosure per section
    }

    /** Dev-only: the presence memory knobs. All of them need no reload to be *stored*, and the
     *  KV size needs one to *exist* — which the dialog's first line says out loud so a
     *  measurement run is not attributed to the wrong arm.
     *
     *  **0.9.1 defect fixed here.** The old dialog called `setMessage()` and
     *  `setSingleChoiceItems()` on ONE builder, and AppCompat renders that pair as the message
     *  with NO rows: on the phone the three KV arms were unreachable, which is what blocked the
     *  KV measurement. The message is now a plain `TextView` inside a custom view and the arms
     *  are real `RadioButton` rows in a `RadioGroup`, so there is no list panel for the support
     *  library to lose them into.
     */
    private fun memoryBudgetDialog() {
        val kv = MemoryBudget.kvTokens(prefs.getInt(MemoryBudget.PREF_KV_TOKENS, MemoryBudget.KV_DEFAULT))
        val unloadOn = prefs.getBoolean(MemoryBudget.PREF_UNLOAD, false)
        var delayMin = MemoryBudget.unloadDelayMinutes(
            prefs.getInt(MemoryBudget.PREF_UNLOAD_DELAY, MemoryBudget.DELAY_DEFAULT))
        var unloadChecked = unloadOn

        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        box.addView(TextView(this).apply {
            text = "KV cache — the engine is rebuilt on the next presence load " +
                "(force-stop, or leave Settings and come back). Release policy is read at hangup."
            textSize = 13f
        })

        // The three arms, as rows that exist. The index into KV_CHOICES is tracked rather
        // than the view id, so the write below cannot read the wrong row.
        val group = android.widget.RadioGroup(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, pad / 2, 0, 0)
        }
        val radios = ArrayList<android.widget.RadioButton>(MemoryBudget.KV_CHOICES.size)
        MemoryBudget.KV_CHOICES.forEach { tokens ->
            val rb = android.widget.RadioButton(this).apply {
                id = android.view.View.generateViewId()
                text = MemoryBudget.kvLabel(tokens)
                isChecked = tokens == kv
                setPadding(0, pad / 4, 0, 0)
            }
            radios += rb
            group.addView(rb)
        }
        box.addView(group)

        val unloadSw = SwitchCompat(this).apply {
            text = MemoryBudget.unloadLabel(unloadChecked)
            isChecked = unloadChecked
            setPadding(0, pad / 2, 0, 0)
        }
        box.addView(unloadSw)

        val delayRow = TextView(this).apply {
            text = MemoryBudget.delayLabel(delayMin)
            textSize = 15f
            setPadding(0, pad, 0, 0)
        }
        box.addView(delayRow)

        // The three presence-cost arms (docs/PRESENCE-COST.md). Each is read at ENGINE LOAD,
        // so changing one needs a reload to exist — except the release, which its timer reads.
        // All three are off/absent by default: a phone with no pref behaves as 0.9.1.
        var hearsOn = MemoryBudget.hears(prefs.getBoolean(MemoryBudget.PREF_HEARS, true))
        val hearsSw = SwitchCompat(this).apply {
            text = MemoryBudget.hearsLabel(hearsOn)
            isChecked = hearsOn
            setPadding(0, pad / 2, 0, 0)
        }
        box.addView(hearsSw)
        hearsSw.setOnCheckedChangeListener { _, on -> hearsOn = on; hearsSw.text = MemoryBudget.hearsLabel(on) }

        var lazyOn = !MemoryBudget.lazyLoad(prefs.getBoolean(MemoryBudget.PREF_LAZY_LOAD, false))
        val lazySw = SwitchCompat(this).apply {
            text = MemoryBudget.lazyLabel(lazyOn)
            isChecked = lazyOn
            setPadding(0, pad / 2, 0, 0)
        }
        box.addView(lazySw)
        lazySw.setOnCheckedChangeListener { _, on -> lazyOn = on; lazySw.text = MemoryBudget.lazyLabel(on) }

        var openMin = MemoryBudget.idleOpenMinutes(
            prefs.getInt(MemoryBudget.PREF_IDLE_OPEN_MIN, 0))
        val openRow = TextView(this).apply {
            text = MemoryBudget.idleOpenLabel(openMin)
            textSize = 15f
            setPadding(0, pad, 0, 0)
        }
        box.addView(openRow)
        openRow.setOnClickListener {
            val choices = intArrayOf(0, 2, 5, 10, 30)
            val labels = choices.map { MemoryBudget.idleOpenLabel(it) }.toTypedArray()
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle("Release while open")
                .setSingleChoiceItems(labels, choices.indexOf(openMin).coerceAtLeast(0)) { dd, which ->
                    openMin = choices[which]
                    openRow.text = MemoryBudget.idleOpenLabel(openMin)
                    dd.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        // The two release policies are alternatives, not a stack: whichever is picked last
        // turns the other off, so the arm that is measured is the arm that is shown.
        unloadSw.setOnCheckedChangeListener { _, on ->
            unloadChecked = on
            unloadSw.text = MemoryBudget.unloadLabel(on)
            if (on && delayMin != 0) { delayMin = 0; delayRow.text = MemoryBudget.delayLabel(0) }
        }
        delayRow.setOnClickListener {
            val labels = MemoryBudget.DELAY_CHOICES.map { MemoryBudget.delayLabel(it) }.toTypedArray()
            AlertDialog.Builder(this@SettingsActivity)
                .setTitle("Idle release")
                .setSingleChoiceItems(labels, MemoryBudget.DELAY_CHOICES.indexOf(delayMin).coerceAtLeast(0)) { dd, which ->
                    delayMin = MemoryBudget.DELAY_CHOICES[which]
                    delayRow.text = MemoryBudget.delayLabel(delayMin)
                    if (delayMin > 0 && unloadChecked) {
                        unloadChecked = false
                        unloadSw.isChecked = false
                    }
                    dd.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        AlertDialog.Builder(this)
            .setTitle("Presence memory (dev)")
            .setView(box)
            .setPositiveButton("Apply") { _, _ ->
                val choice = MemoryBudget.KV_CHOICES[radios.indexOfFirst { it.isChecked }.coerceAtLeast(0)]
                prefs.edit()
                    .putInt(MemoryBudget.PREF_KV_TOKENS, choice)
                    .putBoolean(MemoryBudget.PREF_UNLOAD, unloadChecked && delayMin == 0)
                    .putInt(MemoryBudget.PREF_UNLOAD_DELAY, delayMin)
                    .putBoolean(MemoryBudget.PREF_HEARS, hearsOn)
                    .putBoolean(MemoryBudget.PREF_LAZY_LOAD, !lazyOn)
                    .putInt(MemoryBudget.PREF_IDLE_OPEN_MIN, openMin)
                    .apply()
                VoxLog.d("event=memory-budget kv=$choice unload=${unloadChecked && delayMin == 0} " +
                    "idleMin=$delayMin hears=$hearsOn loadAtLaunch=$lazyOn idleOpenMin=$openMin")
                Toast.makeText(this,
                    "KV $choice · ${MemoryBudget.delayLabel(delayMin)} · ${MemoryBudget.idleOpenLabel(openMin)} " +
                        "— KV, hearing and load-at-launch apply on the next presence load",
                    Toast.LENGTH_LONG).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** The STT model chooser: the shipped models, then the streaming arm. Picking the
     *  streaming arm sets [ModelCatalog.KEY_STT_STREAM_MODEL] and leaves the shipped
     *  [ModelCatalog.KEY_STT_MODEL] alone — it is the fallback while the streaming model is
     *  absent, downloading or will not load. Picking a shipped model clears the streaming one. */
    private fun pickSttModel() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val choices = ModelCatalog.sttModels + ModelCatalog.streamingSttModels.map { (id, label) ->
            id to ModelCatalog.streamingSttLabel(label, am.memoryClass, am.isLowRamDevice)
        }
        val stream = prefs.getString(ModelCatalog.KEY_STT_STREAM_MODEL, "").orEmpty()
        val cur = if (ModelCatalog.isStreamingStt(stream)) stream
            else prefs.getString(ModelCatalog.KEY_STT_MODEL, ModelCatalog.DEFAULT_STT_MODEL) ?: ModelCatalog.DEFAULT_STT_MODEL
        AlertDialog.Builder(this)
            .setTitle("STT model (on-device)")
            .setSingleChoiceItems(choices.map { it.second }.toTypedArray(), choices.indexOfFirst { it.first == cur }.coerceAtLeast(0)) { d, which ->
                val id = choices[which].first
                if (ModelCatalog.isStreamingStt(id)) {
                    prefs.edit().putString(ModelCatalog.KEY_STT_STREAM_MODEL, id).apply()
                    if (!ModelCatalog.isInstalled(this, id)) Toast.makeText(this,
                        "Download it in Voice models — until then ${sttModelLabel(prefs.getString(ModelCatalog.KEY_STT_MODEL, ModelCatalog.DEFAULT_STT_MODEL))} is used.",
                        Toast.LENGTH_LONG).show()
                } else {
                    prefs.edit().putString(ModelCatalog.KEY_STT_MODEL, id).remove(ModelCatalog.KEY_STT_STREAM_MODEL).apply()
                }
                findViewById<TextView>(R.id.set_stt_model_val).text = currentSttModelLabel()
                d.dismiss()
            }
            .show()
    }

    private fun sttModelLabel(id: String?): String =
        (ModelCatalog.sttModels + ModelCatalog.streamingSttModels).firstOrNull { it.first == id }?.second ?: id.orEmpty()

    /** The STT row's value: the streaming arm when selected, else the shipped model. */
    private fun currentSttModelLabel(): String {
        val stream = prefs.getString(ModelCatalog.KEY_STT_STREAM_MODEL, "").orEmpty()
        return if (ModelCatalog.isStreamingStt(stream)) sttModelLabel(stream)
            else sttModelLabel(prefs.getString(ModelCatalog.KEY_STT_MODEL, ModelCatalog.DEFAULT_STT_MODEL) ?: ModelCatalog.DEFAULT_STT_MODEL)
    }

    private fun refreshFlowVals() {
        findViewById<TextView>(R.id.set_stt_val).text = sttBackendLabel(prefs.getString(ModelCatalog.KEY_STT_BACKEND, ModelCatalog.BACKEND_ONDEVICE) ?: ModelCatalog.BACKEND_ONDEVICE)
        findViewById<TextView>(R.id.set_stt_model_val).text = currentSttModelLabel()
        findViewById<TextView>(R.id.set_stt_threads_val).text = VoxThreads.label(prefs.getInt(VoxThreads.PREF, VoxThreads.AUTO))
        findViewById<TextView>(R.id.set_tts_val).text = label("tts", ModelCatalog.defaultTts(this))
        syncSpeakerRow()   // title + value follow the selected engine
        findViewById<TextView>(R.id.set_voice_val).text = deliveryLabel()
        refreshSttRemotePanel()
    }

    /** #114-badge/#116: the install-progress caption for the Models row (Settings-home
     *  group row + the store row inside "Voice models"). Counts ONLY the REQUIRED
     *  subset of the blessed set so the home badge agrees with the Models screen's
     *  required set (the optional extras don't inflate the denominator). */
    private fun refreshModelsVal() {
        // #116: the required set is a first-run blocker, not a stat. When any of
        // it is missing the line carries the same ⚠ + warning colour as the main
        // screen's modelsMissingPill, names what is absent, and taps through to
        // the Voice-models store. The neutral line is kept only when complete.
        val required = ModelCatalog.required
        val missing = ModelCatalog.missingRequired(this)
        val installed = required.size - missing.size
        val complete = missing.isEmpty()
        val text = if (complete) {
            "$installed/${required.size} installed · needed for your voice"
        } else {
            val names = missing.joinToString(", ") { shortModelName(it) }
            if (missing.size == required.size) "⚠ No voice models installed — $names"
            else "⚠ ${missing.size} of ${required.size} voice model${if (missing.size == 1) "" else "s"} missing — $names"
        }
        val color = ContextCompat.getColor(this,
            if (complete) R.color.hv_text_dim else R.color.hv_warn)
        listOf(R.id.set_models_grpval).forEach { id ->
            findViewById<TextView>(id)?.let { v ->
                v.text = text
                v.setTextColor(color)
                // The row opens the store; the warning line says so too, for a reader.
                v.isClickable = !complete
                v.isFocusable = !complete
                v.contentDescription = if (complete) null else "$text. Opens Voice models."
                v.setOnClickListener(if (complete) null else android.view.View.OnClickListener { openModelsStore() })
            }
        }
    }

    /** Short catalogue name for a one-line "what is missing": drops the voice /
     *  speaker suffix that would make the Settings row unreadable. */
    private fun shortModelName(spec: ModelSpec): String =
        spec.name.substringBefore(" ·").substringBefore(" (")

    private fun openModelsStore() {
        startActivity(android.content.Intent(this, ModelsActivity::class.java))
    }

    override fun onResume() {
        super.onResume()
        refreshCallQuiet()   // #325: back from the Do Not Disturb access screen
        resumed = true
        // A download may have finished while the store was open; the Settings
        // warning must clear (or appear) the moment we come back.
        refreshModelsVal()
        refreshGrpVals()
        // The profile can have been switched elsewhere; a scoped row must name the one that is
        // active now, not the one that was active when this screen was built.
        applyHints()
        refreshAgentModel()
        // #225: re-read the look (a theme change recreates the screen; a pref may have moved
        // elsewhere) and restart the preview only if the Being section is the one on screen.
        bindBeingPreview()
        syncPreview()
        PhoneToolsSheet.refreshOpen()   // #421: a DND grant made in system settings shows at once
        refreshPhoneMcpVal()
    }

    /** The row's short state; "connected" is only ever the handshake, never the agent's reply. */
    private fun refreshPhoneMcpVal() {
        runCatching {
            findViewById<TextView>(R.id.set_phone_mcp_val).text =
                PhoneToolsView.rowValue(PhoneSetupStore.state(this))
        }
    }

    /** #225: the preview stops at onPause, not at onDestroy — a backgrounded Settings screen
     *  must not keep a render thread, a GL context or a 60 fps ticker alive. */
    override fun onPause() {
        super.onPause()
        resumed = false
        previewHandler.removeCallbacks(previewTick)
        findViewById<AvatarView>(R.id.set_being_preview)?.setPaused(true)
    }

    // #115: the Settings-home rows each carry a live-value subtitle so a user can see
    // state without drilling in. Populate all of them from prefs (mirrors
    // refreshFlowVals / refreshModelsVal). The ids were added by the wave-2 Settings
    // agent in activity_settings.xml; this is the Kotlin wiring half.
    private fun refreshGrpVals() {
        try {
            findViewById<TextView>(R.id.set_entity_grpval)?.text =
                connectionLine(prefs.getString("url", "").orEmpty())
            // "Mic & Barge-in" owns the input side only; Presence has its own row.
            findViewById<TextView>(R.id.set_speech_grpval)?.text =
                "Barge-in ${onOff(prefs.getBoolean("duplex", true))} · " +
                    sttBackendLabel(prefs.getString(ModelCatalog.KEY_STT_BACKEND, ModelCatalog.BACKEND_ONDEVICE) ?: ModelCatalog.BACKEND_ONDEVICE)
            findViewById<TextView>(R.id.set_tts_grpval)?.text = label("tts", ModelCatalog.defaultTts(this)) +
                " · cues ${onOff(prefs.getBoolean(CuePolicy.PREF_ENABLED, CuePolicy.DEFAULT_ENABLED))}"
            // #224/#222: Presence says whether it is on, and if it cannot be, why.
            refreshPresenceRow()
            refreshVoxRows()   // rule (a): the VOX.md rows follow the Presence switch
            findViewById<TextView>(R.id.set_presence_grpval)?.text =
                if (!AlphaGate.allows(prefs, AlphaGate.FeatureSet.PRESENCE))
                    "Alpha · select Presence in Alpha features at the top"
                else when (presenceState()) {
                PresenceGate.State.ON ->
                    if (prefs.getBoolean("er_presence", true))
                        "On · " + PresenceCadence.resolve(
                            prefs.getString(PresenceCadence.PREF, null), null).key
                    else "On · quiet"
                PresenceGate.State.OFF -> "Off"
                PresenceGate.State.NO_MODEL -> "On · model not downloaded"
                PresenceGate.State.CANNOT_START -> "On · model cannot start here"
            }
            // Counting files only — no decryption on the Settings home.
            findViewById<TextView>(R.id.set_wake_grpval)?.text =
                "${com.hermesvox.wake.KeystoreTakeCipher.store(this).count()} of " +
                "${com.hermesvox.wake.EnrolmentPrompts.takes} takes · not listening"
            findViewById<TextView>(R.id.set_visuals_grpval)?.text =
                label("theme", "system") + " · " + label("layout_mode", "presence") + " · " +
                    VisualStyle.of(prefs.getString(VisualStyle.KEY_CATEGORY, VisualStyle.DEFAULT) ?: VisualStyle.DEFAULT).label
            findViewById<TextView>(R.id.set_about_grpval)?.text =
                getString(R.string.app_name) + " " + versionName()
            // #329: the role's real state, read from Android (never remembered in a pref).
            findViewById<TextView>(R.id.set_assistant_grpval)?.text =
                AssistantRole.summary(AssistantRole.state(this), AssistantRole.otherHolderLabel(this))
        } catch (_: Throwable) {
            // never let a subtitle populate crash the settings screen
        }
    }

    private fun connectionLine(url: String): String =
        if (url.isBlank()) "not configured" else "connected"

    private fun onOff(on: Boolean): String = if (on) "on" else "off"

    // The About row's version subtitle. Uses PackageManager (no BuildConfig dependency —
    // the C0 release guard rejects buildConfigField key injection, and BuildConfig is
    // not reliably generated). Falls back to the label on any failure.
    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (_: Throwable) { "" }

    // #118/#224: the advanced panels (Hearing's timing tuning, Being's fine look,
    // Presence's behaviour controls) are collapsed by default, one disclosure each.
    private fun bindAdvancedDisclosures() {
        bindDisclosure(R.id.row_mic_advanced, R.id.adv_mic_panel, R.id.set_mic_adv_state)
        bindDisclosure(R.id.row_being_advanced, R.id.adv_being_panel, R.id.set_being_adv_state)
        bindDisclosure(R.id.row_presence_advanced, R.id.adv_presence_panel, R.id.set_presence_adv_state)
    }

    /** Wire one disclosure chip to the panel it reveals. */
    private fun bindDisclosure(rowId: Int, panelId: Int, stateId: Int) {
        try {
            val chip = findViewById<android.view.View>(rowId) ?: return
            val panel = findViewById<android.view.View>(panelId) ?: return
            val state = findViewById<TextView>(stateId) ?: return
            chip.setOnClickListener {
                val show = panel.visibility != android.view.View.VISIBLE
                panel.visibility = if (show) android.view.View.VISIBLE else android.view.View.GONE
                state.text = if (show) "hide" else "show ›"
            }
        } catch (_: Throwable) { /* never crash settings from a toggle */ }
    }

    // --- Remote STT (server) backend: URL/model/key + connection test. This
    // panel is revealed ONLY when the STT backend picker says "remote"; its
    // fields write to the SAME prefs RemoteStt reads (stt_remote_*), and the key
    // goes through SecureStore — never plaintext in the prefs XML. ------
    private fun refreshSttRemotePanel() {
        val remote = prefs.getString(ModelCatalog.KEY_STT_BACKEND, "").orEmpty() == ModelCatalog.BACKEND_REMOTE
        findViewById<android.view.View>(R.id.remote_stt_panel).visibility =
            if (remote) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun bindSttRemote() {
        // Populate once BEFORE the watchers attach, so loading never writes back.
        loadSttRemoteFields()
        bindRemoteTextField(R.id.stt_remote_url, KEY_STT_REMOTE_URL) { it.trim() }
        bindRemoteTextField(R.id.stt_remote_model, KEY_STT_REMOTE_MODEL) { it.trim() }
        bindRemoteTextField(R.id.stt_remote_key, KEY_STT_REMOTE_KEY) { SecureStore.encrypt(it) ?: it }
        findViewById<LinearLayout>(R.id.row_test_stt_conn).setOnClickListener { testRemoteStt() }
        refreshSttRemotePanel()
    }

    private fun loadSttRemoteFields() {
        findViewById<EditText>(R.id.stt_remote_url).setText(prefs.getString(KEY_STT_REMOTE_URL, ""))
        findViewById<EditText>(R.id.stt_remote_model).setText(prefs.getString(KEY_STT_REMOTE_MODEL, DEFAULT_REMOTE_MODEL))
        findViewById<EditText>(R.id.stt_remote_key).setText(SecureStore.decrypt(prefs.getString(KEY_STT_REMOTE_KEY, null) ?: "").orEmpty())
    }

    /** Live-save a remote field on every user edit (same UX as the mic SeekBars);
     *  [toStored] maps the raw field text to its stored form. */
    private fun bindRemoteTextField(editId: Int, key: String, toStored: (String) -> String) {
        findViewById<EditText>(editId).addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                prefs.edit().putString(key, toStored(s?.toString().orEmpty())).apply()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    /** Persist the visible field state, then run the SAME init probe RemoteStt
     *  uses on pipeline start (async, non-throwing). Toast + dd-log the result. */
    private fun testRemoteStt() {
        val url = findViewById<EditText>(R.id.stt_remote_url).text.toString().trim()
        val model = findViewById<EditText>(R.id.stt_remote_model).text.toString().trim()
        val key = findViewById<EditText>(R.id.stt_remote_key).text.toString()
        prefs.edit()
            .putString(KEY_STT_REMOTE_URL, url)
            .putString(KEY_STT_REMOTE_MODEL, model)
            .putString(KEY_STT_REMOTE_KEY, SecureStore.encrypt(key) ?: key)
            .apply()
        if (sttBaseUrl(url).isBlank()) {
            VoxLog.dd("event=stt-remote-test ok=false reason=no-url")
            setRemoteTestResult(false)
            Toast.makeText(this, "Remote STT: enter a server URL first", Toast.LENGTH_LONG).show()
            return
        }
        setRemoteTestResult(null)
        RemoteStt(this).init { ok ->
            runOnUiThread {
                VoxLog.dd("event=stt-remote-test ok=$ok")
                setRemoteTestResult(ok)
                Toast.makeText(this,
                    if (ok) "Remote STT: connection OK"
                    else "Remote STT: unreachable — check URL / key",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun setRemoteTestResult(state: Boolean?) {
        val tv = findViewById<TextView>(R.id.set_stt_test_val)
        tv.text = when (state) {
            true -> "ok"
            false -> "FAILED"
            null -> "…"
        }
        tv.setTextColor(when (state) {
            true -> androidx.core.content.ContextCompat.getColor(this, R.color.hv_verdict_ok)
            false -> androidx.core.content.ContextCompat.getColor(this, R.color.hv_verdict_fail)
            null -> androidx.core.content.ContextCompat.getColor(this, R.color.hv_cyan)
        })
    }

    private fun sttBackendLabel(tok: String): String = when (tok) {
        ModelCatalog.BACKEND_PLATFORM -> "Platform (Google)"
        ModelCatalog.BACKEND_REMOTE -> "Remote (server)"
        else -> "On-device (offline)"
    }
    private fun bindAppearance() {
        findViewById<LinearLayout>(R.id.row_theme).setOnClickListener {
            pick("Theme", arrayOf("System", "Dark", "Light"),
                arrayOf("system", "dark", "light"), "theme", R.id.set_theme_val) {
                applyTheme(prefs.getString("theme", "system")!!)
                recreate()
            }
        }
        findViewById<TextView>(R.id.set_theme_val).text = label("theme", "system")
        findViewById<LinearLayout>(R.id.row_layout).setOnClickListener {
            // "Presence" already names the being's visual body (Visuals). This
            // setting names the SCREEN arrangement, so its choices are Being
            // (the avatar fills the surface) and Transcript (readable log).
            pick("Screen layout", arrayOf("Being", "Transcript"),
                arrayOf("presence", "conversation"), "layout_mode", R.id.set_layout_val)
        }
        findViewById<TextView>(R.id.set_layout_val).text = label("layout_mode", "presence")
        bindKeepScreenOn()
        findViewById<android.view.View>(R.id.row_test_conn)?.setOnClickListener {
            val c = com.hermesvox.VoiceController(this, com.hermesvox.core.HermesSession(
                prefs.getString("url", "").orEmpty(),
                SecureStore.decrypt(prefs.getString("key", "").orEmpty()).orEmpty(), ""))
            // 0.5.1: the test runs OFF the UI thread. Run on it — as this handler used
            // to — and Android refuses the socket outright (NetworkOnMainThreadException,
            // no message), which is exactly the field log's `ping=false(unknown)
            // stream=false(unknown)`: a verdict for a test that never reached the network.
            findViewById<TextView>(R.id.set_test_val)?.let {
                it.text = "testing…"
                it.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.hv_overlay_text))
            }
            c.testConnectionAsync(true) { probe, msg ->
                val ok = probe == ConnectionPhase.Probe.OK
                findViewById<TextView>(R.id.set_test_val)?.let {
                    it.text = when (probe) {
                        ConnectionPhase.Probe.OK -> "ok"
                        ConnectionPhase.Probe.COLD -> "warming"
                        ConnectionPhase.Probe.AUTH -> "key"
                        else -> "FAILED"
                    }
                    it.setTextColor(when (probe) {
                        ConnectionPhase.Probe.OK -> androidx.core.content.ContextCompat.getColor(this, R.color.hv_verdict_ok)
                        ConnectionPhase.Probe.COLD -> androidx.core.content.ContextCompat.getColor(this, R.color.hv_verdict_cold)   // reachable, just not ready
                        else -> androidx.core.content.ContextCompat.getColor(this, R.color.hv_verdict_fail)
                    })
                }
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(when {
                        ok -> "Connection OK"
                        probe == ConnectionPhase.Probe.COLD -> "Gateway is warming up"
                        else -> "Connection issue"
                    })
                    .setMessage(msg)
                    .setPositiveButton("OK", null).show()
            }
        }
        findViewById<LinearLayout>(R.id.row_debug).setOnClickListener {
            val ver = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Throwable) { "?" }
            val crash = CrashLog.read(this)
            // K2 (C4): the runtime log is capped + rotated (single generation), so the
            // WHOLE session since app start now spans TWO files: hermes-vox.log.1 (the
            // prior full generation, older) + hermes-vox.log (current). mergedRuntimeLog
            // = .1 then current — the export/copy ships BOTH (the merged pair) so field
            // logs stay complete across the rotation seam. Cap only the inline dialog view.
            val full = mergedRuntimeLog()
            val runtime = full.takeLast(60000).ifEmpty { "no runtime log" }
            val log = "=== Hermes Vox DEBUG — version $ver ===\n\n=== CRASH LOG ===\n$crash\n\n=== RUNTIME LOG (full) ===\n$runtime"
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Debug / logs")
                .setMessage(log)
                .setPositiveButton("Copy all") { _, _ ->
                    val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("vox-debug", log))
                    android.widget.Toast.makeText(this, "copied", android.widget.Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("Share full log") { _, _ ->
                    // Share the MERGED pair (old .1 generation + current) as one file so a
                    // recipient sees the whole session, not just the post-rotation current.
                    val f = mergedLogFile()
                    val uri = androidx.core.content.FileProvider.getUriForFile(this, packageName + ".fileprovider", f)
                    val i = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(android.content.Intent.EXTRA_STREAM, uri); addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(android.content.Intent.createChooser(i, "Send full Hermes Vox log"))
                }
                .setNegativeButton("Clear logs") { _, _ ->
                    CrashLog.clear(this)
                    // K2: clear the whole rotated set — current, prior .1 generation, and any merge snapshot.
                    try { java.io.File(filesDir, "logs/hermes-vox.log").delete() } catch (_: Throwable) {}
                    try { java.io.File(filesDir, "logs/hermes-vox.log.1").delete() } catch (_: Throwable) {}
                    try { java.io.File(filesDir, "logs/hermes-vox-merged.log").delete() } catch (_: Throwable) {}
                    findViewById<TextView>(R.id.set_debug_val).text = "view"
                }
                .show()
        }
        // #421 steps 3–5: the phone as an MCP server. The setup path is a turn to the agent, not a
        // block the owner pastes; this row opens the one sheet that composes it, shows the agent's
        // reply, waits for the phone's own handshake, and keeps the manual config as a fallback.
        findViewById<LinearLayout>(R.id.row_phone_mcp).setOnClickListener {
            PhoneToolsSheet(this, session = PhoneSetupStore.session(this)).show()
        }
        refreshPhoneMcpVal()
    }

    /** K2 (0.5.0.3) screen-alive toggle: keep_screen_on (default ON since 0.8). The flag it
     *  controls is a plain WINDOW flag (FLAG_KEEP_SCREEN_ON) MainActivity arms at
     *  call start and clears at the call teardown — no permission, no WAKE_LOCK.
     *  Bound here + re-bound by restore-defaults (GROUP_VISUALS — Appearance). */
    private fun bindKeepScreenOn() {
        findViewById<SwitchCompat>(R.id.set_keep_screen_on).apply {
            isChecked = prefs.getBoolean(KEY_KEEP_SCREEN_ON, KEEP_SCREEN_ON_DEFAULT)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, on).apply() }
        }
    }

    /** K2: the full runtime session as text — the rotated prior generation
     *  (hermes-vox.log.1, older) concatenated with the current hermes-vox.log. */
    private fun mergedRuntimeLog(): String {
        val logs = java.io.File(filesDir, "logs")
        return try {
            val cur = java.io.File(logs, "hermes-vox.log")
            val gen = java.io.File(logs, "hermes-vox.log.1")
            val older = if (gen.exists()) gen.readText() else ""
            val current = if (cur.exists()) cur.readText() else ""
            if (older.isEmpty() && current.isEmpty()) "no runtime log" else older + current
        } catch (_: Throwable) { "no runtime log" }
    }

    /** K2: a single file holding the merged pair (.1 then current), written under the
     *  logs dir so the existing FileProvider path serves it. Falls back to the current
     *  file alone if the merge itself fails. */
    private fun mergedLogFile(): java.io.File {
        val logs = java.io.File(filesDir, "logs")
        val cur = java.io.File(logs, "hermes-vox.log")
        val merged = java.io.File(logs, "hermes-vox-merged.log")
        return try {
            val gen = java.io.File(logs, "hermes-vox.log.1")
            val older = if (gen.exists()) gen.readText() else ""
            val current = if (cur.exists()) cur.readText() else ""
            merged.writeText(older + current)
            merged
        } catch (_: Throwable) { cur }
    }

    // ---- #120 jargon help: a small "?" beside each abbreviation, opening the
    // same kind of dialog as every other explanation in Settings. The words stay
    // where the control is; nothing moves to a new screen. -------------------
    private fun bindJargonHelp() {
        bindHelp(R.id.help_bargein, "Barge-in",
            "Talking over the entity while it is replying. It stops at once and listens to " +
            "you — no button to press. With this off, a reply keeps going until you tap the " +
            "being or end the call.")
        bindHelp(R.id.help_aec, "AEC (echo cancellation)",
            "Acoustic echo cancellation. The phone listens to its own speaker and subtracts " +
            "that sound from the mic, so the entity does not hear itself and mistake its own " +
            "voice for yours. Leave it on unless a headset works better with it off.")
        bindHelp(R.id.help_continuous, "Continuous capture",
            "Normally the mic works a turn at a time and the recogniser hears it in chunks. " +
            "With this on, the mic stays open for the whole call and the recogniser follows " +
            "your words as you say them — the first word after a reply is never lost, and the " +
            "partial transcript is never more than a tenth of a second old. Costs battery: the " +
            "recogniser is reading all the time. Leave off unless you want that.")
        bindHelp(R.id.help_semantic, "Judge when you finish",
            "Ends your turn by judgement instead of a fixed pause: if the words so far read as " +
            "finished and have stopped changing, your turn ends sooner; if they trail off " +
            "(\"…and\", \"…because\") it waits longer. Needs Continuous capture on, because it " +
            "decides from a transcript that keeps up. Off: the fixed pause below.")
        bindHelp(R.id.help_vad, "VAD (voice activity detection)",
            "A tiny on-device model decides when audio is speech and when it is silence, so " +
            "the app knows when you started and stopped talking. The threshold below is how " +
            "sure it must be before a sound counts as speech.")
        bindHelp(R.id.help_semantic_barge, "Semantic barge-in",
            "With Presence on, some interruptions are not meant to cancel the work — " +
            "\"take your time\" or \"go on\" means keep going. This lets the entity tell those " +
            "apart from a real interruption. Off: every interruption cancels its work.")
    }

    /** Wire one inline `?` chip to the dialog it explains. */
    private fun bindHelp(id: Int, title: String, body: String) {
        findViewById<TextView>(id)?.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(body)
                .setPositiveButton("Got it", null)
                .show()
        }
    }

    // ---- Mic / barge-in tuning (exact-range SeekBars reading + writing the SAME
    // prefs the pipeline reads; defaults = shipped values; a restore-defaults
    // button resets all eight) -------------------------------------------
    private fun bindMicSettings() {
        bindMicToggle(R.id.set_mic_aec, "mic_aec", true)
        bindMicToggle(R.id.set_ns_extra, "ns_extra", true)
        bindMicToggle(R.id.set_partial_stt, "partial_stt", true)
        // #228 / #229: both default on (D29) and both sit behind the Alpha gate (#402) — the
        // rows are dimmed and unreachable until continuous listening is selected there.
        // Continuous capture keeps the mic open and reads the streaming recogniser every 100 ms;
        // the semantic endpointer is a judgement that only engages once those partials exist,
        // behind its own switch because the two fail apart.
        bindMicToggle(R.id.set_continuous_capture, ContinuousCapture.KEY, ContinuousCapture.DEFAULT_ON)
        bindMicToggle(R.id.set_semantic_endpoint, SemanticEndpoint.KEY, SemanticEndpoint.DEFAULT_ON)
        bindMicToggle(R.id.set_tts_voice_usage, "tts_voice_usage", false)   // D2: engine default OFF (field A/B)
        bindBackchannelRate()
        bindCallQuiet()

        bindFloatSeekBar(R.id.set_seek_vad, R.id.set_mic_vad_val, "vad_threshold",
            0.1f, 0.9f, 0.05f, 0.5f) { "%.2f".format(it) }
        bindIntSeekBar(R.id.set_seek_silence, R.id.set_mic_silence_val, "vad_silence_ms",
            200, 2000, 50, 800) { "${it} ms" }
        bindIntSeekBar(R.id.set_seek_early, R.id.set_mic_early_val, "vad_early_silence_ms",
            150, 800, 50, 450) { "${it} ms" }
        bindIntSeekBar(R.id.set_seek_min, R.id.set_mic_min_speech_val, "vad_min_speech_ms",
            100, 1000, 50, 300) { "${it} ms" }
        bindIntSeekBar(R.id.set_seek_max, R.id.set_mic_max_val, "vad_max_ms",
            8000, 30000, 500, 15000) { "${it} ms" }
        bindFloatSeekBar(R.id.set_seek_barge_rms, R.id.set_mic_barge_rms_val, "barge_rms_min",
            0.04f, 0.30f, 0.01f, 0.10f) { "%.2f".format(it) }
        bindIntSeekBar(R.id.set_seek_barge_grace, R.id.set_mic_barge_grace_val, "barge_grace_ms",
            0, 2000, 50, 500) { "${it} ms" }
        bindIntSeekBar(R.id.set_seek_barge_level, R.id.set_mic_barge_level_val, "barge_level_only_ms",
            0, 800, 50, BargeGate.DEFAULT_LEVEL_ONLY_MS.toInt()) { "${it} ms" }

        // Hearing holds the recogniser now, so its one restore covers both scopes.
        bindRestoreRow(R.id.row_mic_reset, GROUP_MIC, "Hearing", GROUP_STT)
    }

    /**
     * A section's restore row. It asks first: restoring cannot be undone, and the row is one
     * stray tap at the bottom of a list a thumb scrolls through (the same rule as the commands
     * sheet, where only the irreversible commands are drawn as risks).
     */
    private fun bindRestoreRow(rowId: Int, group: String, label: String, vararg more: String) {
        findViewById<android.view.View>(rowId).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Restore $label defaults?")
                .setMessage("Every setting in $label goes back to how the app shipped. This cannot be undone.")
                .setPositiveButton("Restore") { _, _ ->
                    restoreDefaults(group)
                    more.forEach { restoreDefaults(it) }
                    Toast.makeText(this, "$label defaults restored", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Keep", null)
                .show()
        }
    }

    private fun bindMicToggle(id: Int, key: String, def: Boolean) {
        findViewById<SwitchCompat>(id).apply {
            isChecked = prefs.getBoolean(key, def)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(key, on).apply() }
        }
    }

    // ---- #156 (D7): the being's sound cues ------------------------------------------
    // One toggle, one level, and ONE opt-in. The policy itself (ringer mode, DND, media
    // volume, system haptics, TTS suppression) is CuePolicy's, not this screen's: the
    // preview button just reports what that policy decided, because on a vibrate-only,
    // DND or muted phone the honest answer may be a tap or nothing at all.
    private var cuePlayer: SoundCuePlayer? = null

    private fun bindSoundCues() {
        findViewById<SwitchCompat>(R.id.set_sound_cues).apply {
            isChecked = prefs.getBoolean(CuePolicy.PREF_ENABLED, CuePolicy.DEFAULT_ENABLED)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(CuePolicy.PREF_ENABLED, on).apply()
            }
        }
        findViewById<SwitchCompat>(R.id.set_sound_cues_silent).apply {
            isChecked = prefs.getBoolean(CuePolicy.PREF_SILENT, CuePolicy.DEFAULT_SILENT)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(CuePolicy.PREF_SILENT, on).apply()
            }
        }
        bindIntSeekBar(R.id.set_seek_cue_level, R.id.set_cue_level_val, CuePolicy.PREF_LEVEL,
            0, 100, 10, CuePolicy.DEFAULT_LEVEL) { "$it%" }

        // #251: one ambient channel at a time — the cues, or Presence's asides.
        val scopeLabels = arrayOf("Only when Presence is off", "Always")
        val scopeTokens = arrayOf(CuePolicy.SCOPE_PRESENCE_OFF, CuePolicy.SCOPE_ALWAYS)
        val curScope = prefs.getString(CuePolicy.PREF_SCOPE, CuePolicy.DEFAULT_SCOPE)
        findViewById<TextView>(R.id.set_cue_scope_val).text = scopeLabels[scopeTokens.indexOf(curScope).coerceAtLeast(0)]
        findViewById<LinearLayout>(R.id.row_cue_scope).setOnClickListener {
            pick("Cues play", scopeLabels, scopeTokens, CuePolicy.PREF_SCOPE, R.id.set_cue_scope_val)
        }
        findViewById<SwitchCompat>(R.id.set_cue_ping).apply {
            isChecked = prefs.getBoolean(CuePolicy.PREF_PING, CuePolicy.DEFAULT_PING)
            setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean(CuePolicy.PREF_PING, on).apply() }
        }
        val idleLabels = arrayOf("Never", "After 1 min", "After 2 min", "After 5 min")
        val idleSecs = intArrayOf(0, 60, 120, 300)
        fun idleLabel() = idleLabels[idleSecs.indexOf(prefs.getInt(CuePolicy.PREF_IDLE_S, CuePolicy.DEFAULT_IDLE_S)).coerceAtLeast(0)]
        findViewById<TextView>(R.id.set_cue_idle_val).text = idleLabel()
        findViewById<LinearLayout>(R.id.row_cue_idle).setOnClickListener {
            val cur = idleSecs.indexOf(prefs.getInt(CuePolicy.PREF_IDLE_S, CuePolicy.DEFAULT_IDLE_S)).coerceAtLeast(0)
            AlertDialog.Builder(this)
                .setTitle("Check in after a silent call")
                .setSingleChoiceItems(idleLabels, cur) { d, w ->
                    prefs.edit().putInt(CuePolicy.PREF_IDLE_S, idleSecs[w]).apply()
                    findViewById<TextView>(R.id.set_cue_idle_val).text = idleLabels[w]
                    d.dismiss()
                }
                .show()
        }

        findViewById<TextView>(R.id.set_cue_preview).setOnClickListener {
            val d = cuePlayerOrCreate().play(CueSynth.Cue.REPLY_READY, preview = true)
            Toast.makeText(this, cuePreviewNote(d), Toast.LENGTH_SHORT).show()
        }
    }

    private fun cuePlayerOrCreate(): SoundCuePlayer =
        cuePlayer ?: SoundCuePlayer(this).also { cuePlayer = it }

    private fun cuePreviewNote(d: CuePolicy.Decision): String = when {
        d.action == CuePolicy.Action.AUDIO -> "Previewing the reply-ready cue"
        d.action == CuePolicy.Action.HAPTIC -> "Felt the cue (ringer on vibrate, or media volume is zero)"
        d.reason == "tts" -> "Silent while the entity is speaking"
        d.reason == "dnd" -> "Silent — Do Not Disturb is on"
        d.reason == "silent" -> "Silent ringer — enable the opt-in below to hear cues"
        d.reason == "off" -> "Silent — sound cues are off"
        else -> "Silent — no media volume and no haptics"
    }

    /** An int SeekBar snapped to [step] between [min]..[max]; writes the pref 1:1. */
    private fun bindIntSeekBar(seekId: Int, valId: Int, key: String,
                               min: Int, max: Int, step: Int, def: Int, fmt: (Int) -> String) {
        val seek = findViewById<SeekBar>(seekId)
        val steps = (max - min) / step
        seek.max = steps
        val tv = findViewById<TextView>(valId)
        seek.progress = snapInt(prefs.getInt(key, def).coerceIn(min, max), min, step)
        tv.text = fmt(min + seek.progress * step)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val v = min + p * step
                prefs.edit().putInt(key, v).apply()
                tv.text = fmt(v)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    /** A float SeekBar snapped to [step] between [min]..[max]; writes the pref 1:1. */
    private fun bindFloatSeekBar(seekId: Int, valId: Int, key: String,
                                 min: Float, max: Float, step: Float, def: Float, fmt: (Float) -> String) {
        val seek = findViewById<SeekBar>(seekId)
        val steps = ((max - min) / step).toInt()
        seek.max = steps
        val tv = findViewById<TextView>(valId)
        seek.progress = snapFloat(prefs.getFloat(key, def).coerceIn(min, max), min, step)
        tv.text = fmt(min + seek.progress * step)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val v = min + p * step
                prefs.edit().putFloat(key, v).apply()
                tv.text = fmt(v)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    private fun snapInt(v: Int, min: Int, step: Int): Int = ((v - min) / step).coerceAtLeast(0)
    private fun snapFloat(v: Float, min: Float, step: Float): Int =
        ((v - min) / step).toInt().coerceAtLeast(0)

    // ---- Visuals: the being's CATEGORY, its richness, and its idle shape ---------
    // The category (VisualStyle) is the 0.5.1 answer to "expose categories of visuals":
    // it is not a seventh shape label — it re-colours, re-lights and re-paces the being
    // in every state, and the two sliders let the user push whichever family they chose
    // calmer or wilder. 0.5.2 (A2) expands the table to 20 families and the picker lists
    // EVERY one because it is driven straight off VisualStyle.TOKENS/LABELS — no second,
    // truncated list to drift. A3 adds "cycle all categories" so the whole breadth can be
    // seen animating. The pre-existing shape/theme + cycle controls are preserved verbatim
    // into this group (same prefs, same wording).
    // ---- #225: the look controls, bound from ONE table ------------------------------
    // Each slider reads and writes exactly the pref its LookControls entry names, prints
    // "current · range", and carries a one-tap reset beside its title.
    private fun bindLookControls() {
        for (c in LookControls.ALL) bindLookControl(c)
    }

    private fun bindLookControl(c: LookControls.Control) {
        val seek = findViewById<SeekBar>(idOf(c.seek)) ?: return
        val tv = findViewById<TextView>(idOf(c.value)) ?: return
        val reset = findViewById<TextView>(idOf(LookControls.resetName(c.seek)))
        // Named `key` on purpose: the Settings pref-key audit (SettingsHintsTest) treats a
        // helper parameter as a parameter, so this table-driven binding adds no unowned key.
        val key = c.key
        seek.max = c.steps
        // The value line and the reset's state are one thing (the control's position), so
        // they are refreshed together — a reset can never leave a stale value on screen.
        val show: (Int) -> Unit = { p ->
            tv.text = c.labelAt(p)
            reset?.let {
                val atDefault = c.isDefault(p)
                it.alpha = if (atDefault) 0.35f else 1f
                it.isClickable = !atDefault
            }
        }
        seek.progress = c.progressOf(prefs.getFloat(key, c.default))
        show(seek.progress)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                prefs.edit().putFloat(key, c.at(p)).apply()
                show(p)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        reset?.setOnClickListener {
            prefs.edit().putFloat(key, c.default).apply()
            seek.progress = c.progressOf(c.default)
            show(seek.progress)
        }
    }

    /** A layout id by resource name — the table stores names so a test can hold it against
     *  the layout without this file restating every id twice. */
    private fun idOf(name: String): Int = resources.getIdentifier(name, "id", packageName)

    // ---- #225: the live preview ---------------------------------------------------------
    // The same AvatarView the main screen draws, fed by the same applyLookPrefs(), so a change
    // is judged where it is made. It walks through the states a call shows, so a look is seen
    // listening, working and speaking — not only at rest. It renders ONLY while the Being
    // section is on screen and the screen is resumed: leaving releases the render thread and
    // its GPU targets (AvatarView.setPaused), so nothing keeps drawing — or holding memory —
    // behind the Settings home, another section, or another app.
    private val previewStates = arrayOf("idle" to "resting", "listening" to "listening",
        "thinking" to "working", "speaking" to "speaking")
    private var previewAt = 0
    /** DEBUG harness only (#225 screenshots): hold one state instead of walking them. */
    private var previewHold: String? = null
    /** True between onResume and onPause; the preview may only run when this is set. */
    private var resumed = false
    private val previewHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val previewTick = object : Runnable {
        override fun run() {
            val v = findViewById<AvatarView>(R.id.set_being_preview) ?: return
            val (st, label) = previewStates[previewAt % previewStates.size]
            previewAt++
            showPreviewState(v, st, label)
            previewHandler.postDelayed(this, PREVIEW_STATE_MS)
        }
    }
    private val lookListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { p, _ ->
        // A pref written by a row above must show up in the preview at once — but never on a
        // screen that is in the background (onPause has already released the renderer).
        if (resumed) findViewById<AvatarView>(R.id.set_being_preview)?.applyLookPrefs(p)
    }
    private var previewBound = false

    /** Put the preview in [st] and say which state it is showing. */
    private fun showPreviewState(v: AvatarView, st: String, label: String) {
        v.setPresent(st, null, if (st == "thinking") 0.7f else 0.15f,
            if (st == "speaking") 0.55f else 0.2f)
        findViewById<TextView>(R.id.set_being_preview_state)?.text = label
    }

    private fun bindBeingPreview() {
        val v = findViewById<AvatarView>(R.id.set_being_preview) ?: return
        // #275: on the light theme the preview is ink on the page, exactly as the main screen —
        // setLightSurface snaps the family's authored light axes, so the preview cannot show a
        // dark panel in light mode.
        val night = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        v.setLightSurface(night != android.content.res.Configuration.UI_MODE_NIGHT_YES, ContextCompat.getColor(this, R.color.hv_bg))
        v.applyLookPrefs(prefs)
        if (!previewBound) {
            previewBound = true
            prefs.registerOnSharedPreferenceChangeListener(lookListener)
        }
    }

    /** Run the preview only while it is both on screen and in front; otherwise stop the state
     *  walk AND hand the GPU back (AvatarView.setPaused tears the render thread down). */
    private fun syncPreview() {
        val v = findViewById<AvatarView>(R.id.set_being_preview) ?: return
        val on = resumed && currentSection == SECTION_VISUALS
        previewHandler.removeCallbacks(previewTick)
        v.setPaused(!on)
        if (!on) return
        val hold = previewHold
        if (hold != null) {
            val label = previewStates.firstOrNull { it.first == hold }?.second ?: hold
            showPreviewState(v, hold, label)
        } else {
            previewHandler.post(previewTick)
        }
    }

    // ---- DEBUG harness (screenshots) ------------------------------------------------
    /** DEBUG-ONLY (#275/#225 convention): make a capture deterministic without taps.
     *  Extras: `section` (already honoured), `being_advanced` (open the fine-look panel),
     *  `preview_state` (hold the preview on idle|listening|thinking|speaking) and `scroll_to`
     *  (a row or seek-bar resource name to scroll the section to). Inert in a release build,
     *  because it is guarded by the same FLAG_DEBUGGABLE test MainActivity's harness uses. */
    private fun applyDebugHarness() {
        if ((applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) return
        intent?.getStringExtra("being_advanced")?.let { want ->
            if (want != "false") {
                findViewById<android.view.View>(R.id.adv_being_panel)?.visibility = android.view.View.VISIBLE
                findViewById<TextView>(R.id.set_being_adv_state)?.text = "hide"
            }
        }
        intent?.getStringExtra("preview_state")?.let { s ->
            previewHold = previewStates.firstOrNull { it.first == s.lowercase() }?.first
        }
        // showSection() already ran, so it started the walk; taking the hold now must take
        // effect on this same screen, not after the next section switch.
        if (previewHold != null) syncPreview()
        intent?.getStringExtra("scroll_to")?.let { name -> scrollToRow(name) }
    }

    /** DEBUG only: scroll so [name]'s row sits at the BOTTOM of the screen, which keeps the
     *  preview and the rows above it in frame — the control and (for the upper rows) the
     *  being it changes in one shot. Waits for the section's first layout: the offset is
     *  meaningless before it, and a scroll computed too early lands at the top. The row is
     *  nested (the fine-look panel is one child of the ScrollView), so the offset comes from
     *  screen coordinates, not from the row's own `bottom`. */
    private fun scrollToRow(name: String) {
        val row = findViewById<android.view.View>(idOf(name)) ?: return
        val scroll = findViewById<android.widget.ScrollView>(R.id.settings_scroll) ?: return
        val margin = (resources.displayMetrics.density * 16f).toInt()
        scroll.viewTreeObserver.addOnGlobalLayoutListener(
            object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    scroll.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    val r = IntArray(2); row.getLocationOnScreen(r)
                    val s = IntArray(2); scroll.getLocationOnScreen(s)
                    val rowBottomInContent = r[1] - s[1] + scroll.scrollY + row.height
                    scroll.scrollTo(0, (rowBottomInContent - scroll.height + margin).coerceAtLeast(0))
                }
            })
    }

    private fun bindVisuals() {
        val catVals = VisualStyle.TOKENS
        val catLabels = VisualStyle.LABELS
        val cat = prefs.getString(VisualStyle.KEY_CATEGORY, VisualStyle.DEFAULT) ?: VisualStyle.DEFAULT
        setStringVal(R.id.set_visual_category_val, catLabels, catVals, cat)
        findViewById<LinearLayout>(R.id.row_visual_category).setOnClickListener {
            micChoiceString("Visual category", catLabels, catVals,
                VisualStyle.KEY_CATEGORY, R.id.set_visual_category_val)
        }
        // A3 (0.5.2): cycle through EVERY category. Off = the fixed pick above renders; on =
        // the being inhabits each family in turn, crossfaded over ~6s apiece, so the whole
        // breadth animates. Written to the same "hv" prefs AvatarView reads inside
        // setVisualCategory, so it takes effect on the next resume with no new wiring. The
        // picker above still matters while cycling: it is the family the rotation starts from
        // and returns to when this is switched off.
        findViewById<SwitchCompat>(R.id.set_visual_cycle_all).apply {
            isChecked = prefs.getBoolean(VisualStyle.KEY_CYCLE_ALL, VisualStyle.DEFAULT_CYCLE_ALL)
            setOnCheckedChangeListener { _, on ->
                prefs.edit().putBoolean(VisualStyle.KEY_CYCLE_ALL, on).apply()
            }
        }
        // #225: the look's sliders are bound from LookControls.ALL — one table that names the
        // pref, the row, the bounds and the reading, so a slider can only write the pref it
        // says it writes. Energy and glow ride it too (see the table's own note).
        bindLookControls()
        bindBeingPreview()

        // video-statewire: WHICH SHAPE FIRES IN EACH ACTIVE STATE. Three pickers over the
        // SAME SHAPE vocabulary as the presence-shape picker below — so the user can put
        // jellyfish (A_VOICE), breath, sweep, octopus, etc. on any state — defaulting to
        // the Wave 1 semantic fits (soundwave / eye / radar) that match resolveArch's
        // stateArch table. Stored as tokens; defaults agree even when the pref is unset.
        bindStateShape(R.id.row_speak_shape, R.id.set_speak_shape_val, "Speaking shape",
            AvatarView.KEY_SHAPE_SPEAKING, AvatarView.DEFAULT_SHAPE_SPEAKING)
        bindStateShape(R.id.row_listen_shape, R.id.set_listen_shape_val, "Listening shape",
            AvatarView.KEY_SHAPE_LISTENING, AvatarView.DEFAULT_SHAPE_LISTENING)
        bindStateShape(R.id.row_think_shape, R.id.set_think_shape_val, "Thinking shape",
            AvatarView.KEY_SHAPE_THINKING, AvatarView.DEFAULT_SHAPE_THINKING)

        val themeLabels = AvatarView.SHAPE_LABELS
        val themeVals = AvatarView.SHAPE_TOKENS
        val theme = prefs.getString("particles_theme", "aura") ?: "aura"
        setStringVal(R.id.set_particle_theme_val, themeLabels, themeVals, theme)
        findViewById<LinearLayout>(R.id.row_particle_theme).setOnClickListener {
            micChoiceString("Presence shape / theme", themeLabels, themeVals, "particles_theme", R.id.set_particle_theme_val)
        }
        val cyc = findViewById<SwitchCompat>(R.id.set_particle_cycle)
        cyc.isChecked = prefs.getBoolean("particles_cycle", true)
        cyc.setOnCheckedChangeListener { _, on -> prefs.edit().putBoolean("particles_cycle", on).apply() }
    }

    private fun setStringVal(valId: Int, labels: Array<String>, vals: Array<String>, cur: String) {
        val idx = vals.indexOfFirst { it == cur }.coerceAtLeast(0).coerceAtMost(vals.size - 1)
        findViewById<TextView>(valId).text = labels[idx]
    }
    /** video-statewire: one per-state shape row (Speaking / Listening / Thinking shape).
     *  Renders the human label of the stored token, defaulting to [def] so an UNSET pref
     *  shows (and the picker highlights) the same default AvatarView's stateArch falls
     *  back to — the Settings display and the renderer can never disagree. */
    private fun bindStateShape(rowId: Int, valId: Int, title: String, key: String, def: String) {
        setStringVal(valId, AvatarView.SHAPE_LABELS, AvatarView.SHAPE_TOKENS,
            prefs.getString(key, def) ?: def)
        findViewById<LinearLayout>(rowId).setOnClickListener {
            micChoiceString(title, AvatarView.SHAPE_LABELS, AvatarView.SHAPE_TOKENS, key, valId, def)
        }
    }
    private fun micChoiceString(title: String, labels: Array<String>, vals: Array<String>, key: String, valId: Int,
                                def: String = vals.getOrElse(0) { "aura" }) {
        val cur = prefs.getString(key, def) ?: def
        val idx = vals.indexOfFirst { it == cur }.coerceAtLeast(0).coerceAtMost(vals.size - 1)
        AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(labels, idx) { d, which ->
            prefs.edit().putString(key, vals[which]).apply()
            findViewById<TextView>(valId).text = labels[which]
            d.dismiss()
        }.show()
    }

    /** Store the TOKEN; render its human label. */
    private fun pick(title: String, labels: Array<String>, tokens: Array<String>, prefKey: String, valId: Int, onApplied: (() -> Unit)? = null) {
        val cur = prefs.getString(prefKey, "") ?: ""
        val idx = tokens.indexOfFirst { it == cur }.coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setSingleChoiceItems(labels, idx) { d, which ->
                prefs.edit().putString(prefKey, tokens[which]).apply()
                findViewById<TextView>(valId).text = labels[which]
                d.dismiss(); onApplied?.invoke()
            }
            .show()
    }

    /** The voice you pick is the voice you hear: a short line in the chosen engine + speaker, so
     *  choosing between ten voices is a listening decision, not a guess from a label. The engine
     *  loads for this line only and is released when it finishes (or the next preview starts). */
    private var preview: VoxTts? = null
    private fun previewVoice() {
        preview?.let { try { it.stop(); it.shutdown() } catch (_: Throwable) {} }
        val t = buildTts(this, prefs.getString("tts", null) ?: ModelCatalog.defaultTts(this))
        preview = t
        t.init { ok ->
            if (!ok || preview !== t) return@init
            val t0 = android.os.SystemClock.uptimeMillis()
            t.speak("Hi — this is how I'll sound when we talk.") {
                VoxLog.d("event=voice-preview engine=${t.name} ms=${android.os.SystemClock.uptimeMillis() - t0}")
                if (preview === t) { try { t.shutdown() } catch (_: Throwable) {}; preview = null }
            }
        }
    }

    override fun onDestroy() {
        preview?.let { try { it.stop(); it.shutdown() } catch (_: Throwable) {} }
        preview = null
        previewHandler.removeCallbacksAndMessages(null)   // #225
        prefs.unregisterOnSharedPreferenceChangeListener(lookListener)
        // #156: a Settings cue preview owns an AudioTrack; free it with the screen.
        cuePlayer?.release()
        cuePlayer = null
        super.onDestroy()
    }

    /** The `voice` pref is the delivery register; its "system" token reads as "Natural" here so
     *  it is never confused with the System TTS engine one row up. */
    private fun deliveryLabel(): String = when (prefs.getString("voice", "system")) {
        "bright" -> "Bright"; "deep" -> "Deep"; else -> "Natural"
    }

    /** The engine token the Speaker row follows. */
    private fun ttsEngine(): String = prefs.getString("tts", null) ?: ModelCatalog.defaultTts(this)

    /** The on-device engine the row controls, or null for the system voice (which
     *  has no speaker choice — the row hides rather than offering a no-op). */
    private fun ttsVoiceEngine(): SherpaVoice? = when (ttsEngine()) {
        "supertonic" -> SherpaVoice.SUPERTONIC
        "piper" -> SherpaVoice.PIPER
        else -> null
    }

    private fun speakerStore(): SpeakerPrefs = AndroidSpeakerPrefs(prefs)

    private fun engineInstalled(engine: SherpaVoice): Boolean =
        ModelCatalog.isInstalled(this, engine.modelId)

    /** The Speaker row exists only for the engine it controls, and says so honestly
     *  when that engine's model is missing: an uninstalled voice cannot be previewed,
     *  so no choice is offered. */
    private fun syncSpeakerRow() {
        val row = findViewById<LinearLayout>(R.id.row_tts_speaker) ?: return
        val engine = ttsVoiceEngine()
        if (engine == null) { row.visibility = android.view.View.GONE; return }
        row.visibility = android.view.View.VISIBLE
        findViewById<TextView>(R.id.set_tts_speaker_title).text = when (engine) {
            SherpaVoice.SUPERTONIC -> "Speaker (Supertonic)"
            SherpaVoice.PIPER -> "Speaker (Piper)"
        }
        // #224: this row's title is rewritten whenever the engine changes; keep its
        // one-line statement attached to the new title.
        applyHint("row_tts_speaker", findViewById(R.id.set_tts_speaker_title))
        findViewById<TextView>(R.id.set_tts_speaker_val).text = speakerValue(engine)
    }

    private fun speakerValue(engine: SherpaVoice): String {
        if (!engineInstalled(engine)) return "model not installed"
        val id = VoiceSpeakers.resolve(speakerStore(), engine, VoiceSpeakers.speakerCount(speakerStore(), engine))
        return speakerLabel(engine, id)
    }

    private fun saveSpeaker(engine: SherpaVoice, id: Int) {
        VoiceSpeakers.save(speakerStore(), engine, id)
        findViewById<TextView>(R.id.set_tts_speaker_val).text = speakerLabel(engine, id)
        previewVoice()
    }

    /** The Speaker row's picker for the selected engine. */
    private fun pickSpeaker() {
        val engine = ttsVoiceEngine() ?: return
        if (!engineInstalled(engine)) {
            Toast.makeText(this,
                "${engine.displayName} model is not installed — install it in Voice models to choose a voice.",
                Toast.LENGTH_LONG).show()
            return
        }
        when (engine) {
            SherpaVoice.SUPERTONIC -> pickSupertonicSpeaker()
            SherpaVoice.PIPER -> pickPiperSpeaker()
        }
    }

    private fun pickSupertonicSpeaker() {
        val labels = (0 until 10).map { speakerLabel(SherpaVoice.SUPERTONIC, it) }.toTypedArray()
        val cur = VoiceSpeakers.resolve(speakerStore(), SherpaVoice.SUPERTONIC, 10)
        AlertDialog.Builder(this)
            .setTitle("Speaker (Supertonic)")
            .setSingleChoiceItems(labels, cur) { d, which ->
                d.dismiss()
                saveSpeaker(SherpaVoice.SUPERTONIC, which)
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** Piper has hundreds of audiobook speakers, so the dialog offers eight curated
     *  ids (verified distinct in the shipped model — see VoiceSpeakers.PIPER_CURATED)
     *  plus "Voice id…" for any other id in range. Each pick previews on selection. */
    private fun pickPiperSpeaker() {
        val count = VoiceSpeakers.speakerCount(speakerStore(), SherpaVoice.PIPER)
        val curId = VoiceSpeakers.resolve(speakerStore(), SherpaVoice.PIPER, count)
        val labels = VoiceSpeakers.PIPER_CURATED.map { "Voice ${it.second}" } +
            "Voice id… (0–${count - 1})"
        val checked = VoiceSpeakers.PIPER_CURATED.indexOfFirst { it.first == curId }
        AlertDialog.Builder(this)
            .setTitle("Speaker (Piper)")
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which ->
                d.dismiss()
                if (which < VoiceSpeakers.PIPER_CURATED.size) {
                    saveSpeaker(SherpaVoice.PIPER, VoiceSpeakers.PIPER_CURATED[which].first)
                } else {
                    askPiperVoiceId(count, curId)
                }
            }
            .setNegativeButton("Cancel", null).show()
    }

    /** Numeric entry for a Piper id outside the curated eight. Out-of-range values
     *  stay in the dialog with the real range — never stored and never previewed. */
    private fun askPiperVoiceId(count: Int, curId: Int) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(curId.toString())
            setSelection(text.length)
            hint = "0–${count - 1}"
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle("Voice id (Piper)")
            .setMessage("Piper has $count voices. Enter an id from 0 to ${count - 1}.")
            .setView(input)
            .setPositiveButton("Use", null)
            .setNegativeButton("Cancel", null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val id = input.text.toString().trim().toIntOrNull()
                when {
                    id == null -> Toast.makeText(this, "Enter a number from 0 to ${count - 1}.", Toast.LENGTH_SHORT).show()
                    id !in 0 until count ->
                        Toast.makeText(this, "Out of range — Piper has ids 0 to ${count - 1}.", Toast.LENGTH_SHORT).show()
                    else -> { dlg.dismiss(); saveSpeaker(SherpaVoice.PIPER, id) }
                }
            }
        }
        dlg.show()
    }

    /** The label for a built-in voice, per engine. Piper's curated ids carry their
     *  measured relative pitch; any other id is shown plainly ("Voice id 500"). */
    private fun speakerLabel(engine: SherpaVoice, id: Int): String = when (engine) {
        SherpaVoice.SUPERTONIC -> if (id < 5) "F${id + 1} · female" else "M${id - 4} · male"
        SherpaVoice.PIPER -> {
            val curated = VoiceSpeakers.PIPER_CURATED.firstOrNull { it.first == id }
            if (curated != null) "Voice ${curated.second}" else "Voice id $id"
        }
    }

    private fun label(prefKey: String, default: String): String {
        val tok = prefs.getString(prefKey, default) ?: default
        return when (tok) {
            "on-device" -> "On-device"
            "presence" -> "Being"; "conversation" -> "Transcript"
            "system" -> "System"; "kokoro" -> "System"; "piper" -> "Piper"; "supertonic" -> "Supertonic"
            "warm" -> "Warm"; "bright" -> "Bright"; "deep" -> "Deep"
            "dark" -> "Dark"; "light" -> "Light"
            else -> tok
        }
    }

    /** Central restore-defaults: reset a sub-menu's prefs back to the SHIPPED
     *  defaults, then re-sync the visible controls for that group. */
    private fun restoreDefaults(group: String) {
        val e = prefs.edit()
        when (group) {
            GROUP_MIC -> e
                .putFloat("vad_threshold", 0.5f)
                .putInt("vad_silence_ms", 800)
                .putInt("vad_early_silence_ms", 450)
                .putInt("vad_min_speech_ms", 300)
                .putInt("vad_max_ms", 15000)
                .putBoolean("partial_stt", true)
                // #228/#229: both default ON behind the Alpha gate (D29); restore puts them back
                // that way. Restoring never touches the gate itself.
                .putBoolean(ContinuousCapture.KEY, ContinuousCapture.DEFAULT_ON)
                .putBoolean(SemanticEndpoint.KEY, SemanticEndpoint.DEFAULT_ON)
                .putString(SemanticEndpoint.KEY_EAGERNESS, SemanticEndpoint.Eagerness.BALANCED.key)
                .putBoolean("mic_aec", true)
                .putBoolean("ns_extra", true)
                .putBoolean("tts_voice_usage", false)   // D2: echo-routing default OFF (field A/B 2026-09-06)
                .putFloat("barge_rms_min", 0.10f)
                .putInt("barge_grace_ms", 500)
                .putInt("barge_level_only_ms", BargeGate.DEFAULT_LEVEL_ONLY_MS.toInt())
            GROUP_STT -> e
                .putString(ModelCatalog.KEY_STT_BACKEND, ModelCatalog.BACKEND_ONDEVICE)
                .putString(ModelCatalog.KEY_STT_MODEL, ModelCatalog.DEFAULT_STT_MODEL)
                .remove(ModelCatalog.KEY_STT_STREAM_MODEL)   // the default: no streaming arm
                .putInt(VoxThreads.PREF, VoxThreads.AUTO)   // 0.7.3: the thread override folds into the STT scope
                .putString(KEY_STT_REMOTE_URL, "")
                .putString(KEY_STT_REMOTE_MODEL, "")
                .putString(KEY_STT_REMOTE_KEY, "")
            GROUP_TTS -> {
                // The speaker choice is per engine (VoiceSpeakers): clear BOTH engines'
                // ids + observed counts + the pre-#59 shared key.
                VoiceSpeakers.allKeys().forEach { e.remove(it) }
                e.remove("tts")   // unset = the best installed voice (ModelCatalog.defaultTts), not a forced System
                    .putString("voice", "system")   // #112: voice register folds into TTS (GROUP_VOICE branch removed)
                    .putBoolean("speak_responses", true)   // 0.6.5: the reply-speech toggle belongs to the TTS group's restore scope
                // UI/UX pass: the cues live in "Voice & sounds" now, and their restore scope with them.
                e.putBoolean(CuePolicy.PREF_ENABLED, CuePolicy.DEFAULT_ENABLED)
                    .putBoolean(CuePolicy.PREF_SILENT, CuePolicy.DEFAULT_SILENT)
                    .putInt(CuePolicy.PREF_LEVEL, CuePolicy.DEFAULT_LEVEL)
                    .putString(CuePolicy.PREF_SCOPE, CuePolicy.DEFAULT_SCOPE)
                    .putBoolean(CuePolicy.PREF_PING, CuePolicy.DEFAULT_PING)
                    .putInt(CuePolicy.PREF_IDLE_S, CuePolicy.DEFAULT_IDLE_S)
            }
            GROUP_ENTITY -> e
                .putString("model", "hermes-agent")
                .putString("provider", "")      // clear the per-request provider override
                // 0.6.4 field: Restore defaults was SILENTLY KILLING ER — these ER
                // keys were not in the restore list... but they WERE in the About
                // group's debug reset path and any future GROUP_ABOUT restore would
                // flip er_presence back on. The REAL 0.6.4 field bug is below in
                // refreshFlowVals: restoreDefaults(GROUP_ENTITY) ran refreshFlowVals
                // which did not re-read the ER switches. Now it does (bindErControls).
            // #224/#222: the Presence section's own restore scope (these keys lived under Entity).
            GROUP_PRESENCE -> e
                .putBoolean(PresenceGate.KEY_PRESENCE_ENABLED, PresenceGate.DEFAULT_ENABLED)
                .putBoolean("er_presence", true)
                .putBoolean("er_semantic_barge", true)
                .putString(PresenceCadence.PREF, PresenceCadence.DEFAULT.key)
                .putFloat("er_echo_skip_ms", 700f)
                .putString("er_presence_voice", "sounds")
            // UI/UX pass: Appearance is one section — the screen, then the being on it — so
            // its one restore covers both.
            GROUP_VISUALS -> e
                .putString("theme", "system")
                .putString("layout_mode", "presence")
                .putBoolean(KEY_KEEP_SCREEN_ON, KEEP_SCREEN_ON_DEFAULT)
                .putString(VisualStyle.KEY_CATEGORY, VisualStyle.DEFAULT)   // the light/cheap family
                .putBoolean(VisualStyle.KEY_CYCLE_ALL, VisualStyle.DEFAULT_CYCLE_ALL)   // A3: fixed, not cycling
                .putFloat(VisualStyle.KEY_ENERGY, VisualStyle.DEFAULT_ENERGY)
                .putFloat(VisualStyle.KEY_GLOW, VisualStyle.DEFAULT_GLOW)
                .putFloat(VisualStyle.KEY_DENSITY, VisualStyle.DEFAULT_DENSITY)   // #225
                .putFloat(VisualStyle.KEY_SPEED, VisualStyle.DEFAULT_SPEED)
                .putFloat(VisualStyle.KEY_TRAIL, VisualStyle.DEFAULT_TRAIL)
                .putFloat(VisualStyle.KEY_HUE, VisualStyle.DEFAULT_HUE)
                .putString("particles_theme", "aura")
                .putBoolean("particles_cycle", true)
                // video-statewire: remove the state-shape picks so they fall back to the
                // shipped defaults (soundwave/eye/radar) — unset == default, both agree.
                .remove(AvatarView.KEY_SHAPE_SPEAKING)
                .remove(AvatarView.KEY_SHAPE_LISTENING)
                .remove(AvatarView.KEY_SHAPE_THINKING)
            GROUP_ABOUT -> e
                .putBoolean("dev_console", false)
                .putBoolean("log_transcripts", false)
                .putBoolean("debug_log", false)
        }
        e.apply()
        when (group) {
            GROUP_MIC -> bindMicSettings()
            GROUP_STT -> { loadSttRemoteFields(); refreshFlowVals() }
            // The theme is applied, not just written: a restore that left the screen dark
            // under a "Follow the phone" label would be a lie until the next launch.
            GROUP_VISUALS -> { bindVisuals(); bindKeepScreenOn(); refreshFlowVals(); applyTheme("system") }
            GROUP_TTS -> { bindFlows(); bindSoundCues() }   // 0.6.5 speak toggle; the cue rows live here now
            GROUP_PRESENCE -> { refreshFlowVals(); bindErControls(); refreshGrpVals() }
            GROUP_ENTITY -> { refreshEntityVal(); refreshFlowVals(); bindErControls() }
            GROUP_ABOUT -> { refreshFlowVals(); VoxLog.setDebugFile(false) }
            else -> refreshFlowVals()
        }
    }

    private fun applyTheme(mode: String) {
        when (mode) {
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    private companion object {
        /** #402: how a gated alpha row is dimmed — visibly there, plainly not available. */
        const val ALPHA_DIM = 0.38f
        const val GROUP_MIC = "mic"
        const val GROUP_STT = "stt"
        const val GROUP_TTS = "tts"
        // #112: no GROUP_VOICE / GROUP_MODE — the voice register folds into GROUP_TTS
        // and Presence has its own GROUP_PRESENCE scope, so each setting has ONE reset scope.
        const val GROUP_ENTITY = "entity"
        const val GROUP_VISUALS = "visuals"
        const val GROUP_PRESENCE = "presence"
        /** #225: how long the preview holds each state before the next. */
        const val PREVIEW_STATE_MS = 2800L
        const val GROUP_ABOUT = "about"

        const val SECTION_ENTITY = "entity"
        const val SECTION_SPEECH = "speech"
        const val SECTION_STT = "stt"
        const val SECTION_TTS = "tts"
        const val SECTION_MODELS = "models"
        const val SECTION_APPEARANCE = "appearance"
        const val SECTION_VISUALS = "visuals"
        const val SECTION_ABOUT = "about"
        const val SECTION_PRESENCE = "presence"
        const val SECTION_ASSISTANT = "assistant"

        /** The section containers, by section. Transcription, Voice models and the old
         *  Appearance & Layout section have none of their own any more (see [showSection]). */
        val SECTION_VIEWS = mapOf(
            SECTION_ENTITY to R.id.sec_entity, SECTION_SPEECH to R.id.sec_speech,
            SECTION_TTS to R.id.sec_tts, SECTION_PRESENCE to R.id.sec_presence,
            SECTION_VISUALS to R.id.sec_visuals, SECTION_ASSISTANT to R.id.sec_assistant,
            SECTION_ABOUT to R.id.sec_about)

        /** The home row that opens each section; its label is the section's title. */
        val SECTION_HOME_ROWS = mapOf(
            SECTION_ENTITY to R.id.row_grp_entity, SECTION_SPEECH to R.id.row_grp_speech,
            SECTION_TTS to R.id.row_grp_tts, SECTION_PRESENCE to R.id.row_grp_presence,
            SECTION_VISUALS to R.id.row_grp_visuals, SECTION_ASSISTANT to R.id.row_grp_assistant,
            SECTION_ABOUT to R.id.row_grp_about)

        /** #329: the system's assistant-role dialog (distinct from REQ_MIC_CALL in MainActivity). */
        const val REQ_ASSISTANT = 102
    }
}
