package com.hermesvox

import android.content.Intent
import android.os.Bundle

/**
 * DebugHarnessActivity — the debug harness, in the debug source set (M3, 0.9.1 review).
 *
 * It exists only in a debuggable build, and the debug manifest protects it with
 * `android.permission.DUMP`: `adb shell` holds that permission, a co-installed app does not.
 * The activity IS the call screen (it extends [MainActivity]); the extras drive the same
 * views and the same session, through the protected seams MainActivity exposes — no second
 * implementation, no shipped surface.
 *
 * Why this is a separate activity and not an extra branch in MainActivity: MainActivity is
 * exported for the launcher, the share targets and the assistant role. Any app can start it
 * with arbitrary extras, so a harness that reads extras there is a harness any app can drive.
 * The permission boundary has to be the component, not a runtime check inside a component
 * that is already open to everyone. Release builds contain neither this class nor its
 * manifest entry.
 */
class DebugHarnessActivity : MainActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Defer past onCreate so the views and the session are initialized (a turn needs both).
        mainHandler.postDelayed({ handleHarness(intent) }, 800L)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleHarness(intent)
    }

    /** The debug harness. M3 (0.9.1 review): this lives in the debug source set only, and
     *  the manifest declares the activity with `android:permission="android.permission.DUMP"`
     *  — a permission the shell holds and third-party apps cannot. It is therefore reachable
     *  by `adb shell am start` and by nothing else; `MainActivity` no longer handles any of
     *  these extras, so a co-installed app cannot drive a turn, stage a file, or read the key.
     *
     *  Extras: url, model, key, scope, text. `scope` is applied when PRESENT — an explicit
     *  empty string clears the declared scope, which is how a script exercises the undeclared
     *  path; url/model/key stay blank-means-ignore. */
    private fun handleHarness(intent: android.content.Intent?) {
        if (intent == null) return
        val u = intent.getStringExtra("url"); val k = intent.getStringExtra("key")
        val m = intent.getStringExtra("model"); val text = intent.getStringExtra("text")
        val sc = intent.getStringExtra("scope")
        if (!u.isNullOrBlank()) prefs.edit().putString("url", u).putString("model", m ?: "hermes-agent").apply()
        if (!k.isNullOrBlank()) prefs.edit().putString("key", (SecureStore.encrypt(k) ?: k)).apply()
        // The SAME rule the UI uses, so a script cannot store a scope the gateway
        // would reject, nor one silently rewritten into a different identity.
        if (sc != null) prefs.edit().putString(SessionScope.PREF, SessionScope.normalize(sc)).apply()
        if (text != null) { connectFromPrefs(); send(text) } else if (!u.isNullOrBlank() && !k.isNullOrBlank()) { connectFromPrefs() }
        // #376: the approval arm — a run the phone places whose ONLY purpose is to make
        // Hermes ask for a sign-off, so the card can be exercised at all. Debug-only by
        // construction, and never a shipped surface: acceptance runs arm it with
        // `--es arm approval` (see
        // docs/APPROVALS-ACCEPTANCE.md). `arm_prompt` overrides the default command.
        intent.getStringExtra("arm")?.let { what ->
            if (what.equals("approval", ignoreCase = true)) {
                connectFromPrefs()
                armApproval(intent.getStringExtra("arm_prompt"))
            }
        }
        // #327: the device matrix needs the clipboard in a known state and the paste
        // sheet open, which no shell command can do on this Android. Debug-only by
        // construction, and never logged.
        intent.getStringExtra("clipboard")?.let { body ->
            getSystemService(android.content.ClipboardManager::class.java)
                ?.setPrimaryClip(android.content.ClipData.newPlainText("paste-test", body))
        }
        // The same, from a file: a 5,000-character multi-line paste does not survive
        // a shell argument cleanly. Debug-only, read once, never logged.
        intent.getStringExtra("clipboard_file")?.let { path ->
            val body = runCatching { java.io.File(path).readText() }.getOrNull()
            if (body != null) getSystemService(android.content.ClipboardManager::class.java)
                ?.setPrimaryClip(android.content.ClipData.newPlainText("paste-test", body))
        }
        if (intent.getBooleanExtra("clipboard_empty", false)) {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            if (android.os.Build.VERSION.SDK_INT >= 28) cm?.clearPrimaryClip()
            else cm?.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
        }
        // #370: the share sheet, with the clipboard in whatever state the harness set above.
        // `open_paste` is kept as an alias so the old device-matrix scripts still land here.
        if (intent.getBooleanExtra("open_share", false) || intent.getBooleanExtra("open_paste", false)) {
            showShare()
        }
        // §B: the file offer, driven from a script. A system picker cannot be driven by adb,
        // so the harness stages a path the app can already read (push it with `run-as`).
        // Debug-only by construction. It stops on the confirmation card like every other
        // entry point.
        intent.getStringExtra("offer_file")?.let { path ->
            mainHandler.post {
                if (missingKeyPrompt()) return@post
                val src = java.io.File(path)
                if (!src.isFile) { setStatus("no such file", true); return@post }
                stageDraft(src.name, ShareSurface.Source.PICKER) { FileStage.stageFile(this, src) }
            }
        }
        // #156: the device matrix needs to PUT the phone into each state and then fire
        // a cue. There is no shell command for the ringer mode on this build, so the
        // debug harness sets it through the same API the policy reads (MODIFY_AUDIO_
        // SETTINGS + DND access for SILENT). Order matters and is deliberate: state
        // first, the TTS-suppression witness next, and the cue LAST, all on the main
        // thread, so one intent is one deterministic test. Debug-only by construction: this
        // class and this activity exist only in a debug build.
        intent.getStringExtra("ringer")?.let { mode ->
            val am = getSystemService(android.media.AudioManager::class.java)
            runCatching {
                am?.ringerMode = when (mode.lowercase()) {
                    "silent" -> android.media.AudioManager.RINGER_MODE_SILENT
                    "vibrate" -> android.media.AudioManager.RINGER_MODE_VIBRATE
                    else -> android.media.AudioManager.RINGER_MODE_NORMAL
                }
            }
            VoxLog.d("event=harness ringer=$mode now=${am?.ringerMode}")
        }
        if (intent.hasExtra("cue_voice")) {
            SoundCuePlayer.setVoiceActive(intent.getBooleanExtra("cue_voice", false))
        }
        intent.getStringExtra("cue")?.let { name ->
            val cue = runCatching { CueSynth.Cue.valueOf(name.uppercase()) }.getOrNull()
            if (cue != null) cues?.play(cue, preview = intent.getBooleanExtra("cue_preview", false))
        }
        // #275: the screenshot pass needs the being in a named state and family without a
        // gateway, a mic or a model download. Debug-only, like everything above. Extras:
        // `category` (a VisualStyle token), `being` (listening|thinking|speaking|stalled).
        // The status line is set to what is on screen so the capture is self-labelling.
        intent.getStringExtra("category")?.let { cat ->
            prefs.edit().putString(VisualStyle.KEY_CATEGORY, VisualStyle.of(cat).token).apply()
            applyParticlePrefs()
        }
        intent.getStringExtra("being")?.let { want ->
            // Past onCreate (the harness is posted 800 ms in), the call screen's avatar exists.
            val b = want.lowercase()
            val st = if (b == "stalled") "waiting" else b
            val work = if (st == "thinking") 0.7f else 0.15f
            val a = if (st == "speaking") 0.55f else 0.2f
            if (b == "stalled") {
                avatar.setPresent("thinking", null, work, a)
                avatar.setStall(true, 12_000L)
            } else {
                avatar.setStall(false, 0L)
                avatar.setPresent(st, null, work, a)
            }
            val cat = prefs.getString(VisualStyle.KEY_CATEGORY, VisualStyle.DEFAULT)
            setStatus("$st · ${VisualStyle.labelOf(cat)}", true)
        }
        // #226: the screenshot pass needs the commands sheet itself, on a screen where
        // nothing else is in the way. Debug-only, like everything above. Extra: `sheet`.
        if (intent.getBooleanExtra("sheet", false)) mainHandler.postDelayed({ showCommands() }, 600L)
        // #225: the capture pass needs the Being section with its fine-look panel open, which
        // no shell command can reach — SettingsActivity is not exported, and tapping through a
        // screenshot run is not reproducible. Debug-only, like everything above. Extras:
        // `settings` (the section to open: visuals…) plus the extras SettingsActivity's own
        // harness reads (`being_advanced`, `preview_state`, `scroll_to`), forwarded verbatim.
        intent.getStringExtra("settings")?.let { section ->
            startActivity(android.content.Intent(this, SettingsActivity::class.java).apply {
                if (section != "home") putExtra("section", section)
                for (k in arrayOf("being_advanced", "preview_state", "scroll_to")) {
                    intent.getStringExtra(k)?.let { putExtra(k, it) }
                }
            })
            // One app screen at a time: leaving Main's own being running behind Settings would
            // put a second renderer in the process and blur what a measurement is measuring.
            finish()
        }
}
}
