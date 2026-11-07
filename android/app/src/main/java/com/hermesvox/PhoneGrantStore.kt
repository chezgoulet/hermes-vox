package com.hermesvox

import android.content.Context

/**
 * PhoneGrantStore — the Android side of [PhoneGrants]: the owner's choices, read live.
 *
 * **Read at call time, never cached.** [source] hands the engine and the MCP server a reader that
 * consults SharedPreferences on every call, so turning a group Off in the Phone tools sheet takes
 * effect on the very next tool call — including one the agent has already started. A cached
 * snapshot would be a revocation that waits for the next call to begin, which is not a revocation.
 *
 * **The stored value is the policy, honoured as stored.** Every group offers Off, Ask and Allow,
 * and the three words mean the same thing whatever the group, so [state] returns what [set] wrote
 * with no normalisation in between. An Allow on a consequential group is a real permission; the
 * warning on its row is what tells the owner so.
 *
 * The log line names the group and the state only: a grant is not a secret, but the phone's log is
 * attached to bug reports, so nothing else belongs in it.
 */
object PhoneGrantStore {

    private const val PREFS = "hv"
    private const val PREFIX = "phone_grant_"

    /** The stored state of [group]. A missing or unreadable value is Off, never On. */
    fun state(context: Context, group: PhoneGrants.Group): PhoneGrants.State {
        val raw = prefs(context).getString(PREFIX + group.id, null)
        return PhoneGrants.State.of(raw) ?: PhoneGrants.DEFAULT
    }

    /** Every group's state, in [PhoneGrants.GROUPS] order. */
    fun states(context: Context): Map<String, PhoneGrants.State> {
        val out = LinkedHashMap<String, PhoneGrants.State>()
        for (g in PhoneGrants.GROUPS) out[g.id] = state(context, g)
        return out
    }

    /**
     * Store the owner's choice and return the state that is now in force. There is nothing to
     * normalise: every state is legal for every group, so what was picked is what is kept.
     */
    fun set(context: Context, group: PhoneGrants.Group, wanted: PhoneGrants.State): PhoneGrants.State {
        if (wanted == PhoneGrants.DEFAULT) {
            prefs(context).edit().remove(PREFIX + group.id).apply()
        } else {
            prefs(context).edit().putString(PREFIX + group.id, wanted.id).apply()
        }
        VoxLog.d("event=phone-grants group=${group.id} state=${wanted.id}")
        return wanted
    }

    /**
     * A reader for the engine and the server. It closes over the application context and consults
     * SharedPreferences per group per call — the cheap, in-memory side of SharedPreferences — so
     * no revocation can be stale.
     */
    fun source(context: Context): PhoneGrants.Source {
        val app = context.applicationContext ?: context
        return PhoneGrants.Source { group -> state(app, group) }
    }

    private fun prefs(context: Context) =
        (context.applicationContext ?: context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
