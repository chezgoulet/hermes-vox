package com.hermesvox

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.SharedPreferences
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.hermesvox.core.HermesSession
import kotlin.concurrent.thread

/**
 * AppSkillInstall — the shortest honest path from the shipped skill to an installed one (#285).
 *
 * The skill belongs to the gateway, not to the app (D25): Vox cannot write it, and it must not
 * pretend to. What the app can do is ask the connected agent to install it — one stored turn on
 * the same `/v1/responses` path a normal turn uses, with the exact command in it — or hand the
 * command over. There is no second mechanism and no new network call: an install the user did
 * not ask for would be the app running commands on their gateway.
 *
 * One implementation, two surfaces: the Settings row (Entity & Connection → "Teach your agent
 * this app") and the one-time offer after a first-run connection is verified.
 *
 * Installed is not the same as enabled — the skill is on for every platform unless the owner
 * put its name in `skills.disabled` or `skills.platform_disabled.api_server` on the gateway
 * (docs/APP-SKILL.md). The reply is shown verbatim so the owner reads the agent's own answer.
 */
object AppSkillInstall {

    /** Set once the offer has been shown, whatever the answer was. Never offered twice. */
    const val PREF_OFFERED = "app_skill_offered"

    /** The install command for this build and the profile the stored endpoint addresses. */
    fun command(prefs: SharedPreferences, versionName: String): String =
        AppSkill.command(versionName, AppSkill.profileOf(prefs.getString("url", "").orEmpty()))

    /** Echo the command into the clipboard — the path that needs no agent at all. */
    fun copy(activity: Activity, command: String) {
        val cm = activity.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("hermes skills install", command))
        Toast.makeText(activity, "Command copied", Toast.LENGTH_SHORT).show()
    }

    /**
     * Ask the agent to install the skill, then hand its reply to [onDone] (null when it did not
     * answer). Blocks on a worker thread, never on the caller's.
     */
    fun ask(
        activity: Activity,
        session: HermesSession,
        versionName: String,
        profile: String,
        onDone: (String?) -> Unit = {},
    ) {
        if (activity.isFinishing) return
        val wait = AlertDialog.Builder(activity)
            .setTitle("Asking your agent…")
            .setMessage("It will run the install and say whether it worked.")
            .setCancelable(true)
            .show()
        thread {
            val reply = try {
                session.turnStored(AppSkill.installTurn(versionName, profile))
            } catch (e: Throwable) {
                VoxLog.w("event=app-skill-install result=failed err=${e.message?.take(80)}")
                null
            }
            VoxLog.d("event=app-skill-install result=${if (reply.isNullOrBlank()) "no-reply" else "replied"}")
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                wait.dismiss()
                AlertDialog.Builder(activity)
                    .setTitle("Your agent says")
                    .setMessage(reply?.takeIf { it.isNotBlank() } ?: "No answer — check the connection, or copy the command instead.")
                    .setPositiveButton("OK") { _, _ -> onDone(reply) }
                    .setCancelable(false)
                    .show()
            }
        }
    }

    /**
     * The one-time first-run offer, shown after the connection probe succeeds: the moment the
     * app knows an agent exists to ask. Declining is remembered — this is an offer, not a nag —
     * and the Settings row stays as the way back in.
     */
    fun offerOnce(
        activity: Activity,
        prefs: SharedPreferences,
        session: HermesSession,
        versionName: String,
        onDone: () -> Unit,
    ) {
        val profile = AppSkill.profileOf(prefs.getString("url", "").orEmpty())
        val command = AppSkill.command(versionName, profile)
        if (prefs.getBoolean(PREF_OFFERED, false)) { onDone(); return }
        AlertDialog.Builder(activity)
            .setTitle("Teach your agent this app?")
            .setMessage(
                "Your agent can answer \"where is that setting?\" about Hermes Vox from the app's " +
                    "own description — every setting, every log line, the failures the field has " +
                    "seen.\n\nThe skill is installed on your gateway, so this asks your agent to do " +
                    "it now:\n\n$command"
            )
            .setPositiveButton("Ask the agent") { _, _ ->
                prefs.edit().putBoolean(PREF_OFFERED, true).apply()
                ask(activity, session, versionName, profile) { onDone() }
            }
            .setNeutralButton("Copy command") { _, _ ->
                prefs.edit().putBoolean(PREF_OFFERED, true).apply()
                copy(activity, command)
                onDone()
            }
            .setNegativeButton("Later") { _, _ ->
                prefs.edit().putBoolean(PREF_OFFERED, true).apply()
                onDone()
            }
            .setOnCancelListener {
                prefs.edit().putBoolean(PREF_OFFERED, true).apply()
                onDone()
            }
            .show()
    }
}
