package com.hermesvox

import android.content.SharedPreferences
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The profile switcher and editor (#174): list → pick → done. Opened from the agent's
 * name on the main screen, where the active profile is shown.
 *
 * A profile the user types is saved only after `GET <base>/v1/models` answers 200, so a
 * profile the gateway does not serve says "not served" and a wrong key says "key rejected" —
 * at save time, not as a vague failure in the middle of a call.
 *
 * #384: the list comes from the agent, asked in conversation (SPEC-conversational-bridge §A).
 * [refreshFromHost] tries `GET <base>/v1/profiles` first (the #382 fast path) and otherwise
 * sends one non-streaming `POST <base>/v1/chat/completions` carrying [ProfilesAsk.ASK], on
 * the same bearer and never through the voice pipeline — the reply is not spoken and never
 * enters the call transcript. Only the assistant's text is read, and every name it names is
 * probed before it is saved: the list is a hint, the probe is the proof.
 *
 * The ask runs on connect and on an explicit refresh, is cached in between, and is refused
 * inside 60 s of the last one. Opening the switcher never asks — it renders the cache
 * instantly. A live call defers the ask until it ends, so a profile refresh can never
 * disturb a conversation. A profile the host stopped serving (or one whose probe fails) is
 * shown as unavailable, never deleted, because the key belongs to the user. Adding a profile
 * is not switching one: a refresh never ends a call, and a switch still does.
 */
class ProfileDialogs(
    private val activity: AppCompatActivity,
    private val prefs: SharedPreferences,
    private val onSwitch: (Profiles.Profile) -> Unit,
    private val onRegistryChanged: () -> Unit = {},
) {
    fun list(): List<Profiles.Profile> = Profiles.decode(prefs.getString(Profiles.PREF_LIST, "").orEmpty())
    fun activeName(): String = prefs.getString(Profiles.PREF_ACTIVE, "").orEmpty()
    private fun save(list: List<Profiles.Profile>) =
        prefs.edit().putString(Profiles.PREF_LIST, Profiles.encode(list)).apply()

    /** The last thing this host said. null = not asked yet, so say nothing about it. */
    @Volatile private var answer: ProfilesAsk.Result? = null

    /** When the last ask finished — the cache's clock, and the 60 s refresh guard. */
    @Volatile private var askedAt: Long = 0L

    /** The proven names from the last answer, in served order: what "Add" may offer. */
    @Volatile private var usable: List<HostProfiles.Served> = emptyList()

    /** Saved profiles this host did not prove — kept and marked, never deleted. */
    @Volatile private var gone: List<String> = emptyList()

    /** The partial-refresh line (`2 profiles added · 1 not reachable`), null when whole. */
    @Volatile private var partial: String? = null

    /** One question at a time: a connect-time ask and a refresh must not race. */
    @Volatile private var asking = false

    /** A refresh that arrived during a live call, remembered for when it ends. */
    @Volatile private var deferred: ProfilesAsk.AskKind? = null

    /** The switcher currently on screen, so an answer that lands late can still land visibly. */
    private var open: ProfileSheet? = null

    /** The first use: the connection the app already has becomes the first profile, so the
     *  user never re-enters it. Named after the agent — and it starts out owning the device-wide
     *  voice and look, which are, after all, what this phone is already doing. */
    private fun seeded(): List<Profiles.Profile> {
        val l = list()
        if (l.isNotEmpty() || prefs.getString(Profiles.K_URL, "").isNullOrBlank()) return l
        val name = prefs.getString(Profiles.K_AGENT, "").orEmpty().ifBlank { "Default" }.take(Profiles.NAME_MAX)
        val first = ProfileScope.adopt(
            Profiles.fromPrefs(name) { prefs.getString(it, "").orEmpty() },
            ScopedPrefs.reader(prefs),
        )
        save(listOf(first))
        prefs.edit().putString(Profiles.PREF_ACTIVE, first.name).apply()
        return listOf(first)
    }

    /** The switcher reads the cache instantly; it never asks. */
    fun showSwitcher() {
        if (refuseWhenOff()) return
        renderSwitcher()
    }

    /** Alpha features' Switching profiles set. One question, asked at every management door. */
    private fun switchingAllowed(): Boolean =
        AlphaGate.allows(activity, AlphaGate.FeatureSet.PROFILES)

    private fun refuseWhenOff(): Boolean {
        if (switchingAllowed()) return false
        toast("Switching profiles is off in Alpha features")
        return true
    }

    private fun renderSwitcher() {
        // One sheet at a time: a second tap on the command while it is open repaints it rather
        // than stacking a dialog the user cannot see behind the first.
        open?.takeIf { it.isShowing() }?.dismiss()
        val l = seeded()
        val active = activeName()
        val gone = this.gone
        val sheet = ProfileSheet(
            act = activity,
            rows = { l.map { p ->
                ProfileSheet.Row(p, p.name.equals(active, ignoreCase = true), p.name in gone)
            } },
            note = { message() },
            onSwitch = { p -> if (!p.name.equals(activeName(), ignoreCase = true)) onSwitch(p) },
            onAdd = { addProfile(list()) },
            onEdit = { pickToEdit(list()) },
            onRefresh = { refreshFromHost(ProfilesAsk.AskKind.REFRESH) },
            allowed = { switchingAllowed() },
        )
        open = sheet
        sheet.show()
    }

    /** The one line under the list: freshness, a partial refresh, or a stated failure. */
    private fun message(): String? {
        partial?.let { return it }
        val a = answer ?: return null
        if (a is ProfilesAsk.Result.Failed) return ProfilesAsk.line(a)
        return if (askedAt > 0L) ProfilesAsk.freshness(askedAt, System.currentTimeMillis()) else null
    }

    /**
     * #384: ask the host which profiles it serves, then fold the answer into the registry.
     *
     * [kind] is what the user did. [ProfilesAsk.decide] owns the policy — the switcher never
     * asks, a live call defers, a manual refresh inside 60 s is refused — and this method is
     * only the Android edge: the two HTTP calls and the probes. Runs off the UI thread.
     */
    fun refreshFromHost(kind: ProfilesAsk.AskKind = ProfilesAsk.AskKind.REFRESH) {
        val now = System.currentTimeMillis()
        when (ProfilesAsk.decide(kind, askedAt, now, MainActivity.callIsLive())) {
            ProfilesAsk.AskDecision.READ_CACHE -> return
            ProfilesAsk.AskDecision.REFUSE_RECENT -> {
                toast("Checked less than a minute ago")
                return
            }
            ProfilesAsk.AskDecision.DEFER -> {
                deferred = kind
                toast("Will check when the call ends")
                return
            }
            ProfilesAsk.AskDecision.NOW -> Unit
        }
        if (asking) return
        val base = prefs.getString(Profiles.K_URL, "").orEmpty().trim()
        if (base.isBlank()) return
        val stored = prefs.getString(Profiles.K_KEY, "").orEmpty()
        val key = GatewayKey.resolve(stored) { SecureStore.decrypt(it) }
        if (key.isBlank()) {
            // Nothing to authenticate with: the host was never asked, so it cannot be said
            // to have listed anything. Say the honest line and do not stamp the cache.
            settle(ProfilesAsk.Result.Failed(ProfilesAsk.Failure.NO_BLOCK, -1), base, stored, "", 0L,
                emptyMap(), -1, kind, stamp = false)
            return
        }
        asking = true
        val scope = SessionScope.normalize(prefs.getString(SessionScope.PREF, "").orEmpty())
        val model = prefs.getString(Profiles.K_MODEL, "").orEmpty()
        thread {
            try {
                val started = System.currentTimeMillis()
                // 1. The #382 route, opportunistically: one request, and the day Hermes ships it
                //    nothing here changes.
                val (fastCode, fastBody) = try { fetch(base, key, scope) } catch (_: Throwable) { -1 to "" }
                val fast = ProfilesAsk.fastPath(fastCode, fastBody)
                // 2. Otherwise the conversational ask: a plain chat turn, never the voice path.
                val result: ProfilesAsk.Result = fast ?: run {
                    val r = ask(base, key, model)
                    ProfilesAsk.fromChat(r.code, r.body, r.timedOut)
                }
                // 3. The list is a hint; the probe is the proof.
                val reachable = if (result is ProfilesAsk.Result.Named)
                    probeAll(ProfilesAsk.probes(base, result.served), key) else emptyMap()
                val elapsed = System.currentTimeMillis() - started
                activity.runOnUiThread {
                    asking = false
                    settle(result, base, stored, model, elapsed, reachable, fastCode, kind)
                }
            } catch (_: Throwable) {
                // An unexpected failure must not wedge every later ask on `asking`.
                activity.runOnUiThread { asking = false }
            }
        }
    }

    /** Run a refresh that was deferred by a live call, now that the call has ended. */
    fun runDeferredAsk() {
        val kind = deferred ?: return
        deferred = null
        refreshFromHost(kind)
    }

    /** Apply one answer on the UI thread: save what changed, log it, re-render if open. */
    private fun settle(
        result: ProfilesAsk.Result,
        base: String,
        stored: String,
        model: String,
        elapsedMs: Long,
        reachable: Map<String, Boolean>,
        fastCode: Int,
        kind: ProfilesAsk.AskKind,
        stamp: Boolean = true,
    ) {
        // The user may have switched hosts while the ask was out. That answer describes a
        // connection this screen is no longer on; using it would repaint the wrong host.
        if (base != prefs.getString(Profiles.K_URL, "").orEmpty().trim()) return
        val local = seeded()
        val before = answer
        var changed = false
        var added = 0
        var unreachable = 0
        var unavailable = 0
        when (result) {
            is ProfilesAsk.Result.Named -> {
                val applied = ProfilesAsk.settle(local, base, stored, result, model, reachable)
                added = applied.added
                unreachable = applied.unreachable.size
                unavailable = applied.merge.unavailable.size
                usable = applied.usable
                gone = applied.merge.unavailable
                partial = if (unreachable == 0) null else applied.line
                changed = applied.merge.changed
                if (changed) {
                    save(applied.merge.list)
                    if (!activity.isFinishing && !activity.isDestroyed) onRegistryChanged()
                }
            }
            is ProfilesAsk.Result.Failed -> {
                // Silence is not evidence that a profile is gone: the last proven view
                // (`gone`, `usable`) is kept, and only the line changes.
                partial = null
            }
        }
        answer = result
        if (stamp) askedAt = System.currentTimeMillis()
        VoxLog.d(
            "event=profiles-ask kind=${kind.name.lowercase()} " +
                "source=${sourceOf(result)} status=${statusOf(result)} ms=$elapsedMs fast=$fastCode " +
                "added=$added unavailable=$unavailable dropped=${droppedOf(result)}"
        )
        if (activity.isFinishing || activity.isDestroyed) return
        val shown = open
        // Only redraw when the list or the line would actually differ: an unchanged answer
        // must not make the switcher flicker under the user's finger.
        if (shown != null && shown.isShowing() && (changed || before != result)) {
            shown.dismiss()
            renderSwitcher()
        }
    }

    private fun sourceOf(a: ProfilesAsk.Result): String = when (a) {
        is ProfilesAsk.Result.Named -> when (a.source) {
            ProfilesAsk.Source.FAST_PATH -> "fast"
            ProfilesAsk.Source.CONVERSATION -> "chat"
        }
        is ProfilesAsk.Result.Failed -> "none"
    }

    private fun statusOf(a: ProfilesAsk.Result): String = when (a) {
        is ProfilesAsk.Result.Named -> "200"
        is ProfilesAsk.Result.Failed -> a.code.toString()
    }

    private fun droppedOf(a: ProfilesAsk.Result): Int =
        (a as? ProfilesAsk.Result.Named)?.dropped ?: 0

    /** Add ([editing] null) or edit a profile. The dialog stays open until the gateway
     *  accepts it, and says precisely why when it does not. */
    fun showEditor(editing: Profiles.Profile?) {
        if (refuseWhenOff()) return
        val view = activity.layoutInflater.inflate(R.layout.dialog_entity, null) as android.view.ViewGroup
        val name = EditText(activity).apply {
            hint = "Name (e.g. work)"; setSingleLine()
            setText(editing?.name.orEmpty())
        }
        view.addView(name, 0)
        val eurl = view.findViewById<EditText>(R.id.d_url)
        val emodel = view.findViewById<EditText>(R.id.d_model)
        val ekey = view.findViewById<EditText>(R.id.d_key)
        val escope = view.findViewById<EditText>(R.id.d_scope)
        eurl.hint = "https://host  or  https://host/p/<profile>"
        if (editing != null) {
            eurl.setText(editing.url); emodel.setText(editing.model); escope.setText(editing.scope)
            ekey.setText(SecureStore.decrypt(editing.keyEnc).orEmpty())
        } else emodel.setText("hermes-agent")
        val b = AlertDialog.Builder(activity)
            .setTitle(if (editing == null) "Add a profile" else "Edit ${editing.name}")
            .setView(view)
            .setPositiveButton("Check & save", null)
            .setNegativeButton("Cancel", null)
        if (editing != null) b.setNeutralButton("Delete", null)
        val dlg = b.create()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { confirmDelete(editing!!, dlg) }
            val ok = dlg.getButton(AlertDialog.BUTTON_POSITIVE)
            ok.setOnClickListener {
                val l = list()
                val n = name.text.toString().trim()
                val u = eurl.text.toString().trim()
                val k = ekey.text.toString().trim()
                Profiles.nameError(n, l, editing?.name)?.let { toast(it); return@setOnClickListener }
                if (u.isBlank() || k.isBlank()) { toast("Enter the address and the key"); return@setOnClickListener }
                SessionScope.validationError(escope.text.toString())?.let { toast(it); return@setOnClickListener }
                ok.isEnabled = false; ok.text = "Checking…"
                thread {
                    val verdict = Profiles.check(modelsStatus(u, k))
                    activity.runOnUiThread {
                        ok.isEnabled = true; ok.text = "Check & save"
                        if (verdict != Profiles.Check.OK) { toast(verdict.message); return@runOnUiThread }
                        val p = Profiles.Profile(
                            name = n, url = u, keyEnc = SecureStore.encrypt(k) ?: k,
                            model = emodel.text.toString().trim().ifEmpty { "hermes-agent" },
                            agentName = editing?.agentName.orEmpty(),
                            scope = SessionScope.normalize(escope.text.toString()),
                            provider = editing?.provider.orEmpty(),
                            // An edit changes the connection, never the profile's own voice and
                            // look: those belong to it and are not on this form. Editing the
                            // profile you are on keeps what is live — that *is* its own, and the
                            // saved copy must not revert a change made in Settings.
                            own = when {
                                editing == null -> emptyMap()
                                editing.name.equals(activeName(), ignoreCase = true) -> ScopedPrefs.capture(prefs)
                                else -> editing.own
                            },
                        )
                        save(Profiles.upsert(l, p, editing?.name ?: p.name))
                        onRegistryChanged()
                        dlg.dismiss()
                        val wasActive = editing != null && editing.name.equals(activeName(), ignoreCase = true)
                        // A new profile is offered for use at once; an edit of the active one re-dials.
                        if (wasActive || editing == null) onSwitch(p)
                    }
                }
            }
        }
        dlg.show()
    }

    /**
     * #384 — "+ Add a profile" when the host proved a list: pick a profile the host named
     * instead of typing its name, address and key. Only names that answered their probe are
     * offered. "Enter an address manually…" stays, because a saved connection may live on
     * another host.
     */
    private fun addProfile(l: List<Profiles.Profile>) {
        if (refuseWhenOff()) return
        val served = usable
        if (served.isEmpty()) { showEditor(null); return }
        val unsaved = served.filter { Profiles.find(l, it.name) == null }
        if (unsaved.isEmpty()) {
            toast("Every profile this host serves is already saved")
            return
        }
        val labels = unsaved.map { if (it.isDefault) "${it.name} (default)" else it.name } +
            "Enter an address manually…"
        AlertDialog.Builder(activity)
            .setTitle("Add a profile")
            .setItems(labels.toTypedArray()) { _, i ->
                if (i == unsaved.size) showEditor(null) else addServed(unsaved[i])
            }
            .setNegativeButton("Back") { _, _ -> showSwitcher() }
            .show()
    }

    /** #384: the host supplied the name, the address follows its addressing rule, and the
     *  key is the one already in this (the same) host's registry — nothing new is typed. */
    private fun addServed(s: HostProfiles.Served) {
        val base = prefs.getString(Profiles.K_URL, "").orEmpty().trim()
        val stored = prefs.getString(Profiles.K_KEY, "").orEmpty()
        if (base.isBlank() || stored.isBlank()) { toast("Connect to the host first"); return }
        val p = HostProfiles.entry(base, stored, s, prefs.getString(Profiles.K_MODEL, "").orEmpty())
        save(Profiles.upsert(list(), p))
        onRegistryChanged()
        onSwitch(p)   // a newly added profile is offered for use at once (ends a live call)
    }

    private fun pickToEdit(l: List<Profiles.Profile>) {
        if (refuseWhenOff()) return
        val gone = this.gone
        AlertDialog.Builder(activity)
            .setTitle("Edit a profile")
            .setItems(l.map { it.name + if (it.name in gone) " — unavailable" else "" }.toTypedArray()) { _, i ->
                showEditor(l[i])
            }
            .setNegativeButton("Back") { _, _ -> showSwitcher() }
            .show()
    }

    private fun confirmDelete(p: Profiles.Profile, editor: AlertDialog) {
        if (p.name.equals(activeName(), ignoreCase = true)) { toast("Switch to another profile before deleting this one"); return }
        AlertDialog.Builder(activity)
            .setTitle("Delete ${p.name}?")
            .setMessage("Its address and key are removed from this phone. Nothing changes on the gateway.")
            .setPositiveButton("Delete") { _, _ ->
                save(Profiles.remove(list(), p.name)); onRegistryChanged(); editor.dismiss()
            }
            .setNegativeButton("Keep", null)
            .show()
    }

    /** `GET <base>/v1/profiles` with this connection's own key: status and body, or -1. */
    private fun fetch(base: String, key: String, scope: String): Pair<Int, String> = try {
        val c = URL(HostProfiles.rootBase(base).trimEnd('/') + HostProfiles.PATH).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "GET"
            c.connectTimeout = HostProfiles.TIMEOUT_MS
            c.readTimeout = HostProfiles.TIMEOUT_MS
            c.useCaches = false
            c.setRequestProperty("Authorization", "Bearer $key")
            if (scope.isNotEmpty()) c.setRequestProperty(SessionScope.HEADER, scope)
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val body = stream?.use { readAtMost(it, HostProfiles.BODY_MAX) }.orEmpty()
            code to body
        } finally { c.disconnect() }
    } catch (_: Throwable) { -1 to "" }

    private data class Chat(val code: Int, val body: String, val timedOut: Boolean)

    /**
     * `POST <base>/v1/chat/completions`: the ask as a plain OpenAI-shaped turn, on the
     * bearer the app already holds, with a 45 s budget. Deliberately *not*
     * `VoiceController.sendText` — that speaks the reply and puts it in the call transcript,
     * which for a profile list is both wrong and noisy.
     *
     * `{base}` is the connection with any `/p/<profile>` suffix stripped: the ask goes to
     * the host, not to a profile. The session scope is deliberately not sent: this is not a
     * conversation, and it must not write into the user's memory scope.
     */
    private fun ask(base: String, key: String, model: String): Chat = try {
        val c = URL(HostProfiles.rootBase(base).trimEnd('/') + ProfilesAsk.PATH).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = ProfilesAsk.TIMEOUT_MS
            c.readTimeout = ProfilesAsk.TIMEOUT_MS
            c.useCaches = false
            c.doOutput = true
            c.setRequestProperty("Authorization", "Bearer $key")
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json")
            val body = ProfilesAsk.requestBody(model).toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.use { readAtMost(it, ProfilesAsk.BODY_MAX) }.orEmpty()
            Chat(code, text, false)
        } finally { c.disconnect() }
    } catch (_: SocketTimeoutException) {
        Chat(-1, "", true)
    } catch (_: Throwable) {
        Chat(-1, "", false)
    }

    /**
     * Run the probes: one `GET …/v1/models` per proven candidate, at most eight in flight,
     * each with a 10 s budget. A 200 is the only "usable".
     */
    private fun probeAll(probes: List<ProfilesAsk.Probe>, key: String): Map<String, Boolean> {
        if (probes.isEmpty()) return emptyMap()
        val out = ConcurrentHashMap<String, Boolean>()
        val pool = Executors.newFixedThreadPool(minOf(ProfilesAsk.PROBE_CONCURRENCY, probes.size))
        try {
            for (batch in ProfilesAsk.batches(probes)) {
                val futures = batch.map { p -> pool.submit { out[p.name] = ProfilesAsk.probeSucceeded(modelsStatus(p.url, key)) } }
                for (f in futures) {
                    try { f.get(ProfilesAsk.PROBE_TIMEOUT_MS + 2_000L, TimeUnit.MILLISECONDS) }
                    catch (_: Throwable) { /* the name stays absent = unavailable */ }
                }
            }
        } finally { pool.shutdownNow() }
        return out
    }

    private fun readAtMost(stream: java.io.InputStream, max: Int): String {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(4096)
        while (buf.size() < max) {
            val n = stream.read(chunk, 0, minOf(chunk.size, max - buf.size()))
            if (n < 0) break
            buf.write(chunk, 0, n)
        }
        return buf.toByteArray().toString(Charsets.UTF_8)
    }

    /** `GET <base>/v1/models` with the profile's own key: the status, or -1 for no answer. */
    private fun modelsStatus(base: String, key: String): Int =
        statusOfUrl(base.trimEnd('/') + "/v1/models", key)

    /** One probe at a full URL, with the probe budget. */
    private fun statusOfUrl(url: String, key: String): Int = try {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "GET"
            c.connectTimeout = ProfilesAsk.PROBE_TIMEOUT_MS
            c.readTimeout = ProfilesAsk.PROBE_TIMEOUT_MS
            c.useCaches = false
            c.setRequestProperty("Authorization", "Bearer $key")
            c.responseCode
        } finally { c.disconnect() }
    } catch (_: Throwable) { -1 }

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
}
