package com.hermesvox

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.hermesvox.core.HermesSession
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PhoneSetupStore — the Android edge of [PhoneSetup]: persistence, the ordinary turn, and the
 * session it rides.
 *
 * **The turn rides the active connection, never a held object.** #430: `session()` used to return
 * `live` — the `HermesSession` MainActivity built at connect time — for as long as it existed.
 * A session keeps its url and key for life, and Settings → Entity & Connection → Save writes new
 * prefs without re-dialling, so after re-pointing the connection the sheet sent the setup turn to
 * the OLD address while the connection test (which builds its own session from the prefs) said
 * *Connection OK*. The gateway the owner was watching logged nothing, and the sheet said "could
 * not reach your agent". [PhoneSetupSend.plan] is the rule now: the live session is reused only
 * when it was built for exactly the connection in force, otherwise one is built here from that
 * connection. A missing session is not a failure — only a missing connection is.
 *
 * The setup turn is sent with [HermesSession.turnStored] — the same `/v1/runs` path a
 * typed turn uses, chained off the same session when that session is the current one — and its
 * reply is kept so the sheet can show the agent's own words. Nothing here writes a gateway file,
 * and nothing here treats the reply as proof: the proof is the MCP `initialize` recorded by
 * [PhoneMcp.recordHandshake].
 *
 * **Every failure is named.** A thrown turn and an empty answer both used to become the same
 * `unreachable` flag, which the sheet rendered as "could not reach your agent" whatever had
 * actually happened — a local misconfiguration reporting itself as a network outage. The record
 * now carries the line [PhoneSetupSend] chose: no connection configured, key rejected, agent did
 * not answer, or turn failed with the gateway's own reason.
 *
 * The prompt carries **no credential** (#452): the agent writes the entry with the API key it already
 * holds for this host, so the turn can be shown, re-sent and replayed without exposing a secret. The
 * phone's own token is still scrubbed out of any transport text before it reaches the record, because
 * the manual fallback and the failure line travel the same paths.
 */
object PhoneSetupStore {

    private const val PREFS = "hv"
    private const val K_REQUESTED = "phone_setup_requested_at"
    private const val K_REPLY = "phone_setup_reply"
    private const val K_FAILURE = "phone_setup_failure"
    private const val K_REFUSED = "phone_setup_refused"
    private const val K_TOKEN_TAG = "phone_setup_token_tag"

    private val sending = AtomicBoolean(false)

    /** The last prompt sent in this process; not persisted, because it contains the token. */
    @Volatile private var lastSent: String? = null

    /** The live session MainActivity connected; setup rides it when it is the current connection. */
    @Volatile private var live: HermesSession? = null

    /** The connection [live] was built for. Null when no session is held. */
    @Volatile private var liveFor: PhoneSetupSend.Connection? = null

    fun lastPrompt(): String? = lastSent

    /**
     * Called by MainActivity's connection path with the session *and* the connection it was built
     * from, so a later turn can tell whether it still names the address in force. Null detaches.
     */
    fun attach(session: HermesSession?, connection: PhoneSetupSend.Connection? = null) {
        live = session
        liveFor = if (session == null) null else connection
    }

    private fun prefs(context: Context) =
        (context.applicationContext ?: context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The persisted record, with the handshake read from the one place that can observe it. */
    fun read(context: Context): PhoneSetup.Record {
        val p = prefs(context)
        return PhoneSetup.Record(
            requestedAt = p.getLong(K_REQUESTED, 0L),
            reply = p.getString(K_REPLY, null),
            failure = p.getString(K_FAILURE, null),
            refused = p.getBoolean(K_REFUSED, false),
            handshakeAt = PhoneMcp.handshakeAt(context),
            handshakeTag = PhoneMcp.handshakeTag(context),
            tokenTag = p.getString(K_TOKEN_TAG, "").orEmpty(),
        )
    }

    private fun write(context: Context, record: PhoneSetup.Record) {
        prefs(context).edit()
            .putLong(K_REQUESTED, record.requestedAt)
            .putString(K_REPLY, record.reply)
            .putString(K_FAILURE, record.failure)
            .putBoolean(K_REFUSED, record.refused)
            .putString(K_TOKEN_TAG, record.tokenTag)
            .apply()
    }

    fun state(context: Context, now: Long = System.currentTimeMillis()): PhoneSetup.State =
        PhoneSetup.state(read(context), now, PhoneMcp.tokenTag(context))

    /** A setup turn is being sent: the waiting line, with the previous answer and failure cleared. */
    fun begin(context: Context, now: Long = System.currentTimeMillis()) {
        write(context, PhoneSetup.begin(read(context), now, PhoneMcp.tokenTag(context)))
    }

    fun replied(context: Context, reply: String?) {
        write(context, PhoneSetup.replied(read(context), reply))
    }

    /** The send could not be made, or the turn failed: [reason] is the named cause. */
    fun failed(context: Context, reason: String) {
        write(context, PhoneSetup.failed(read(context), reason))
    }

    /**
     * The send could not even be attempted (nothing configured): the attempt is stamped and the
     * cause kept, so the sheet shows that line rather than sliding back to "not set up yet".
     */
    fun failedBeforeSend(context: Context, reason: String, now: Long = System.currentTimeMillis()) {
        write(context, PhoneSetup.failed(PhoneSetup.begin(read(context), now, PhoneMcp.tokenTag(context)), reason))
    }

    /**
     * Replace the token and forget the old connection. The old credential stops working on the
     * next request, and the sheet stops saying Connected because that proof belonged to the old
     * token.
     */
    fun rotate(context: Context) {
        PhoneMcp.rotate(context)
        write(context, PhoneSetup.rotated(read(context)))
    }

    /**
     * Ask the agent, on a worker thread. [onDone] runs on that thread; callers that touch views
     * must post to the UI thread. The turn is an ordinary stored turn, so it is part of the same
     * conversation chain and the reply is the agent's own.
     *
     * The outcome is classified rather than flattened: an empty answer, a refused key and a
     * transport failure are three different lines in [PhoneSetupSend], and the record keeps the
     * one that happened. The token is scrubbed out of the reason before it is stored.
     */
    fun ask(
        context: Context,
        session: HermesSession,
        host: String,
        token: String,
        onDone: () -> Unit = {},
    ) {
        if (!sending.compareAndSet(false, true)) return
        val app = context.applicationContext ?: context
        val prompt = PhoneSetup.prompt(host)
        lastSent = prompt
        begin(app)
        Thread({
            var reply: String? = null
            var error: Throwable? = null
            try {
                reply = session.turnStored(prompt)
            } catch (t: Throwable) {
                error = t
            }
            val failure = PhoneSetupSend.reason(error, reply, token)
            if (failure == null) replied(app, reply) else failed(app, failure)
            VoxLog.w(
                "event=phone-setup result=" +
                    if (failure == null) "replied" else "failed cause=${PhoneSetupSend.classify(error, reply)}",
            )
            sending.set(false)
            try { onDone() } catch (_: Throwable) { }
        }, "phone-setup").apply { isDaemon = true }.start()
    }

    /** The prompt for the current facts, for a sheet that wants to show what will be sent. */
    fun prompt(host: String): String = PhoneSetup.prompt(host)

    /**
     * The connection in force now: the live prefs' url, key, model, provider and scope — the same
     * prefs the call path and Settings' connection test build their session from. Blank unless both
     * halves of a connection are there.
     */
    fun connection(activity: AppCompatActivity): PhoneSetupSend.Connection {
        val p = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return PhoneSetupSend.connection(
            { key -> p.getString(key, "").orEmpty() },
            { stored -> SecureStore.decrypt(stored) },
        )
    }

    /**
     * The client the setup turn must ride: the live session when it was built for the connection
     * in force, otherwise one built here from that connection. Null **only** when there is nothing
     * configured to send to — never because a session object was missing.
     */
    fun session(activity: AppCompatActivity): HermesSession? {
        val active = connection(activity).takeIf { it.configured }
        return when (PhoneSetupSend.plan(liveFor, live != null, active)) {
            PhoneSetupSend.Plan.RIDE_LIVE -> live
            PhoneSetupSend.Plan.BUILD -> active?.let { build(it) }
            PhoneSetupSend.Plan.NO_CONNECTION -> null
        }
    }

    /** One session for one resolved connection — the same declaration [EntitySession] makes. */
    fun build(connection: PhoneSetupSend.Connection): HermesSession =
        EntitySession.of(
            connection.url, connection.key, connection.model, connection.provider, connection.scope,
        )
}
