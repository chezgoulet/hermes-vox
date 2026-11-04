package com.hermesvox

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.hermesvox.core.HermesSession
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PhoneSetupStore — the Android edge of [PhoneSetup]: persistence, the ordinary turn, and the
 * session it rides.
 *
 * The setup turn is sent with [HermesSession.turnStored] — the same `/v1/responses` path a typed
 * turn uses, chained off the same session — and its reply is kept so the sheet can show the agent's
 * own words. Nothing here writes a gateway file, and nothing here treats the reply as proof: the
 * proof is the MCP `initialize` recorded by [PhoneMcp.recordHandshake].
 *
 * The prompt carries the bearer token. It is never logged: the failure line names only the class of
 * failure, and the sheet is the one place the turn is rendered, for the owner who is setting it up.
 */
object PhoneSetupStore {

    private const val PREFS = "hv"
    private const val K_REQUESTED = "phone_setup_requested_at"
    private const val K_REPLY = "phone_setup_reply"
    private const val K_SEND_FAILED = "phone_setup_send_failed"
    private const val K_REFUSED = "phone_setup_refused"
    private const val K_TOKEN_TAG = "phone_setup_token_tag"

    private val sending = AtomicBoolean(false)

    /** The last prompt sent in this process; not persisted, because it contains the token. */
    @Volatile private var lastSent: String? = null

    /** The live session MainActivity connected; setup rides it so the turn is in the same chain. */
    @Volatile private var live: HermesSession? = null

    fun lastPrompt(): String? = lastSent

    /** Called by MainActivity's connection path. Null while there is no live session. */
    fun attach(session: HermesSession?) {
        live = session
    }

    private fun prefs(context: Context) =
        (context.applicationContext ?: context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The persisted record, with the handshake read from the one place that can observe it. */
    fun read(context: Context): PhoneSetup.Record {
        val p = prefs(context)
        return PhoneSetup.Record(
            requestedAt = p.getLong(K_REQUESTED, 0L),
            reply = p.getString(K_REPLY, null),
            sendFailed = p.getBoolean(K_SEND_FAILED, false),
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
            .putBoolean(K_SEND_FAILED, record.sendFailed)
            .putBoolean(K_REFUSED, record.refused)
            .putString(K_TOKEN_TAG, record.tokenTag)
            .apply()
    }

    fun state(context: Context, now: Long = System.currentTimeMillis()): PhoneSetup.State =
        PhoneSetup.state(read(context), now, PhoneMcp.tokenTag(context))

    /** A setup turn is being sent: the waiting line, with the previous answer cleared. */
    fun begin(context: Context, now: Long = System.currentTimeMillis()) {
        write(context, PhoneSetup.begin(read(context), now, PhoneMcp.tokenTag(context)))
    }

    fun replied(context: Context, reply: String?) {
        write(context, PhoneSetup.replied(read(context), reply))
    }

    fun unreachable(context: Context) {
        write(context, PhoneSetup.unreachable(read(context)))
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
        val prompt = PhoneSetup.prompt(host, token)
        lastSent = prompt
        begin(app)
        Thread({
            val reply = try {
                session.turnStored(prompt)
            } catch (_: Throwable) {
                VoxLog.w("event=phone-setup result=unreachable")
                null
            }
            if (reply == null) unreachable(app) else replied(app, reply)
            sending.set(false)
            try { onDone() } catch (_: Throwable) { }
        }, "phone-setup").apply { isDaemon = true }.start()
    }

    /** The prompt for the current facts, for a sheet that wants to show what will be sent. */
    fun prompt(host: String, token: String): String = PhoneSetup.prompt(host, token)

    /**
     * The app's existing session configuration — the same URL, key, model, provider and declared
     * scope every other caller uses. Null when the app is not connected yet; the sheet says so
     * instead of inventing an endpoint.
     */
    fun session(activity: AppCompatActivity): HermesSession? {
        live?.let { return it }
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val url = prefs.getString("url", "").orEmpty()
        if (url.isBlank()) return null
        val stored = prefs.getString("key", "").orEmpty()
        val key = GatewayKey.resolve(stored) { SecureStore.decrypt(it) }
        if (GatewayKey.isMissing(key)) return null
        return EntitySession.build(url, key, { prefs.getString(it, "").orEmpty() })
    }
}
