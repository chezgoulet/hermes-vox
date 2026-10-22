package com.hermesvox

import android.content.SharedPreferences
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

/**
 * The profile switcher and editor (#174): list → pick → done. Opened from the agent's
 * name on the main screen, where the active profile is shown.
 *
 * A profile is saved only after `GET <base>/v1/models` answers 200, so a profile the gateway
 * does not serve says "not served" and a wrong key says "key rejected" — at save time, not
 * as a vague failure in the middle of a call. Switching is the caller's ([onSwitch]): it
 * ends any live call and starts a new conversation.
 */
class ProfileDialogs(
    private val activity: AppCompatActivity,
    private val prefs: SharedPreferences,
    private val onSwitch: (Profiles.Profile) -> Unit,
) {
    fun list(): List<Profiles.Profile> = Profiles.decode(prefs.getString(Profiles.PREF_LIST, "").orEmpty())
    fun activeName(): String = prefs.getString(Profiles.PREF_ACTIVE, "").orEmpty()
    private fun save(list: List<Profiles.Profile>) =
        prefs.edit().putString(Profiles.PREF_LIST, Profiles.encode(list)).apply()

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
        val l = seeded()
        val active = activeName()
        val rows = l.map { if (it.name.equals(active, ignoreCase = true)) "● ${it.name}" else "   ${it.name}" } + "+ Add a profile"
        AlertDialog.Builder(activity)
            .setTitle("Profiles")
            .setItems(rows.toTypedArray()) { _, i ->
                if (i == l.size) showEditor(null)
                else if (!l[i].name.equals(active, ignoreCase = true)) onSwitch(l[i])
            }
            .setNeutralButton(if (l.isEmpty()) null else "Edit…") { _, _ -> pickToEdit(l) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun pickToEdit(l: List<Profiles.Profile>) {
        AlertDialog.Builder(activity)
            .setTitle("Edit a profile")
            .setItems(l.map { it.name }.toTypedArray()) { _, i -> showEditor(l[i]) }
            .setNegativeButton("Back") { _, _ -> showSwitcher() }
            .show()
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

    private fun confirmDelete(p: Profiles.Profile, editor: AlertDialog) {
        if (p.name.equals(activeName(), ignoreCase = true)) { toast("Switch to another profile before deleting this one"); return }
        AlertDialog.Builder(activity)
            .setTitle("Delete ${p.name}?")
            .setMessage("Its address and key are removed from this phone. Nothing changes on the gateway.")
            .setPositiveButton("Delete") { _, _ -> save(Profiles.remove(list(), p.name)); editor.dismiss() }
            .setNegativeButton("Keep", null)
            .show()
    }

    /** `GET <base>/v1/models` with the profile's own key: the status, or -1 for no answer. */
    private fun modelsStatus(base: String, key: String): Int = try {
        val c = java.net.URL(base.trimEnd('/') + "/v1/models").openConnection() as java.net.HttpURLConnection
        try {
            c.requestMethod = "GET"; c.connectTimeout = 8000; c.readTimeout = 8000; c.useCaches = false
            c.setRequestProperty("Authorization", "Bearer $key")
            c.responseCode
        } finally { c.disconnect() }
    } catch (_: Throwable) { -1 }

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
}
