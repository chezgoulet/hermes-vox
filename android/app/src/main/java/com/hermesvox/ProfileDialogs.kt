package com.hermesvox

import android.content.SharedPreferences
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * The profile switcher and editor (#174): list → pick → done. Opened from the agent's
 * name on the main screen, where the active profile is shown.
 *
 * A profile the user types is saved only after `GET <base>/v1/models` answers 200, so a
 * profile the gateway does not serve says "not served" and a wrong key says "key rejected" —
 * at save time, not as a vague failure in the middle of a call. A profile the host listed is
 * saved on the host's own word (#371): it just answered with its name, and re-probing it
 * would be a second round-trip for nothing. Switching is the caller's ([onSwitch]): it ends
 * any live call and starts a new conversation.
 *
 * #371: the list itself now comes from the host. [refreshFromHost] asks
 * `GET <base>/v1/profiles` on connect and every time the switcher opens, and
 * [HostProfiles.merge] folds the answer into the registry — so a Hermes profile appears
 * without the user ever typing its name. When the host does not advertise its profiles the
 * switcher keeps the manual path and says so in one line ([HostProfiles.FALLBACK_LINE]);
 * a profile the host stopped serving is shown as unavailable, never deleted, because the
 * key belongs to the user. Adding a profile is not switching one: a refresh never ends a call.
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

    /** #371: the last thing this host said. null = not asked yet, so say nothing about it. */
    @Volatile private var answer: HostProfiles.Answer? = null

    /** One question at a time: opening the switcher while a connect-time ask is in flight
     *  must not double the call (and must not reorder two answers). */
    @Volatile private var asking = false

    /** The switcher currently on screen, so an answer that lands late can still land visibly. */
    private var open: AlertDialog? = null

    /** The first use: the connection the app already has becomes the first profile, so the
     *  user never re-enters it. Named after the agent. */
    private fun seeded(): List<Profiles.Profile> {
        val l = list()
        if (l.isNotEmpty() || prefs.getString(Profiles.K_URL, "").isNullOrBlank()) return l
        val name = prefs.getString(Profiles.K_AGENT, "").orEmpty().ifBlank { "Default" }.take(Profiles.NAME_MAX)
        val first = Profiles.fromPrefs(name) { prefs.getString(it, "").orEmpty() }
        save(listOf(first))
        prefs.edit().putString(Profiles.PREF_ACTIVE, first.name).apply()
        return listOf(first)
    }

    fun showSwitcher() {
        // #371: every open asks the host, so a profile added on the gateway shows up without
        // the user typing anything. The dialog is already usable while the ask is in flight.
        refreshFromHost()
        renderSwitcher()
    }

    private fun renderSwitcher() {
        val l = seeded()
        val active = activeName()
        val a = answer
        val base = prefs.getString(Profiles.K_URL, "").orEmpty()
        val gone = HostProfiles.unavailable(l, base, a)
        val rows = l.map { p ->
            val mark = if (p.name.equals(active, ignoreCase = true)) "● " else "   "
            mark + p.name + if (p.name in gone) " — unavailable" else ""
        } + "+ Add a profile"
        val b = AlertDialog.Builder(activity)
            .setTitle("Profiles")
            .setItems(rows.toTypedArray()) { _, i ->
                if (i == l.size) addProfile(l, a)
                else if (!l[i].name.equals(active, ignoreCase = true)) onSwitch(l[i])
            }
            .setNegativeButton("Close", null)
        if (l.isNotEmpty()) b.setNeutralButton("Edit…") { _, _ -> pickToEdit(l) }
        a?.let { b.setMessage(HostProfiles.line(it)) }
        open = b.show()
    }

    /**
     * #371: ask the host which profiles it serves, then fold the answer into the registry.
     * Runs off the UI thread; [HostProfiles] decides what the answer means and whether
     * anything changes. Adding profiles does not switch them (a switch ends a live call).
     */
    fun refreshFromHost() {
        if (asking) return
        val base = prefs.getString(Profiles.K_URL, "").orEmpty().trim()
        if (base.isBlank()) return
        val stored = prefs.getString(Profiles.K_KEY, "").orEmpty()
        val key = GatewayKey.resolve(stored) { SecureStore.decrypt(it) }
        if (key.isBlank()) {
            // Nothing to authenticate with: the manual path, and the same honest line — the
            // host was never asked, so it cannot be said to have listed anything.
            settle(HostProfiles.Answer.Silent(-1, "no key"), base, stored, "")
            return
        }
        asking = true
        val scope = SessionScope.normalize(prefs.getString(SessionScope.PREF, "").orEmpty())
        val model = prefs.getString(Profiles.K_MODEL, "").orEmpty()
        thread {
            val (code, body) = try { fetch(base, key, scope) } catch (_: Throwable) { -1 to "" }
            val a = HostProfiles.answer(code, body)
            activity.runOnUiThread {
                asking = false
                settle(a, base, stored, model)
            }
        }
    }

    /** Apply one answer on the UI thread: save what changed, log it, re-render if open. */
    private fun settle(a: HostProfiles.Answer, base: String, stored: String, model: String) {
        // The connection the app is on is a profile in its own right before the host is
        // compared against the registry, or a first connect would add the host's list beside
        // a current connection that was never saved.
        val local = seeded()
        val before = answer
        val merged = HostProfiles.merge(local, base, stored, a, model)
        val dead = activity.isFinishing || activity.isDestroyed
        if (merged.changed) {
            save(merged.list)
            if (!dead) onRegistryChanged()
        }
        answer = a
        VoxLog.d(
            "event=profiles-host status=${statusOf(a)} added=${merged.added.size} " +
                "unavailable=${merged.unavailable.size}"
        )
        if (dead) return
        val shown = open
        // Only redraw when the list or the line would actually differ: an unchanged answer
        // must not make the switcher flicker under the user's finger.
        if (shown != null && shown.isShowing && (merged.changed || before != a)) {
            shown.dismiss()
            renderSwitcher()
        }
    }

    private fun statusOf(a: HostProfiles.Answer): String = when (a) {
        is HostProfiles.Answer.Advertised -> "200"
        is HostProfiles.Answer.Silent -> a.code.toString()
    }

    /** Add ([editing] null) or edit a profile. The dialog stays open until the gateway
     *  accepts it, and says precisely why when it does not. */
    fun showEditor(editing: Profiles.Profile?) {
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
     * #371 — "+ Add a profile" when the host advertises its list: pick the profile the host
     * named instead of typing its name, address and key. "Enter an address manually…" stays,
     * because a saved connection may live on another host.
     */
    private fun addProfile(l: List<Profiles.Profile>, a: HostProfiles.Answer?) {
        val served = a as? HostProfiles.Answer.Advertised ?: run { showEditor(null); return }
        val unsaved = served.served.filter { Profiles.find(l, it.name) == null }
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

    /** #371: the host supplied the name, the address follows its addressing rule, and the
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
        val gone = HostProfiles.unavailable(l, prefs.getString(Profiles.K_URL, "").orEmpty(), answer)
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
        val c = URL(base.trimEnd('/') + HostProfiles.PATH).openConnection() as HttpURLConnection
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
    private fun modelsStatus(base: String, key: String): Int = try {
        val c = URL(base.trimEnd('/') + "/v1/models").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "GET"; c.connectTimeout = 8000; c.readTimeout = 8000; c.useCaches = false
            c.setRequestProperty("Authorization", "Bearer $key")
            c.responseCode
        } finally { c.disconnect() }
    } catch (_: Throwable) { -1 }

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
}
