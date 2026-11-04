package com.hermesvox

import android.content.Context

/**
 * PhoneMcp — the app's side of the phone-as-MCP-server (#421 step 3): the token, the bind, the life.
 *
 * **When it runs.** The server is up exactly while a voice call is up (started by
 * `VoiceController.start`, stopped by `VoiceController.stop`). That is deliberate: the mind only
 * reaches for the phone while the caller is talking to it, and a listening socket that outlives the
 * call is a surface with no one watching it. The cost, stated plainly in `docs/MCP-PHONE.md`: the
 * gateway's first tool call after a call begins may pay one connect, and a call that is down means
 * the tool is down.
 *
 * **Where it binds.** The tailnet address [Tailnet] picked — the same rule as the file-offer server,
 * for the same reason. Never `0.0.0.0`, never the LAN, never cellular. If the phone is not on the
 * tailnet there is no server and the log says so.
 *
 * **The token.** Generated once, [PhoneMcpToken.BITS] bits, stored encrypted with an Android
 * Keystore alias of its own ([SecureStore]). If the keystore refuses to encrypt, the token is kept in
 * memory for this launch only rather than written in the clear — the log says `token-store=failed`,
 * and the owner has to re-copy the config next time. A stored secret in plaintext SharedPreferences
 * is the one outcome worth losing a session over.
 */
object PhoneMcp {

    private const val PREFS = "hv"
    private const val KEY_TOKEN = "phone_mcp_token"
    private const val ALIAS = "hv_phone_mcp"

    @Volatile private var server: PhoneMcpServer? = null
    @Volatile private var memoryToken: String? = null

    /** The URL the gateway should be pointed at, or null while the server is not running. */
    fun currentUrl(): String? = server?.url

    /**
     * The bearer token, generated on first use and then stable. Never logged.
     *
     * Encrypted on disk; a value that cannot be encrypted stays in memory for this launch only.
     */
    fun token(context: Context): String {
        synchronized(this) {
            memoryToken?.let { return it }
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val stored = prefs.getString(KEY_TOKEN, null)
            if (!stored.isNullOrBlank()) {
                val plain = SecureStore.decrypt(stored, ALIAS)
                if (!plain.isNullOrEmpty()) {
                    memoryToken = plain
                    return plain
                }
                VoxLog.w("event=phone-mcp token-store=unreadable")
            }
            val fresh = PhoneMcpToken.generate()
            val encrypted = SecureStore.encrypt(fresh, ALIAS)
            if (encrypted != null && encrypted != fresh) {
                prefs.edit().putString(KEY_TOKEN, encrypted).apply()
            } else {
                // No plaintext token on disk, ever. The session works; the next launch rotates it.
                VoxLog.w("event=phone-mcp token-store=failed session-only=true")
            }
            memoryToken = fresh
            return fresh
        }
    }

    /**
     * Start serving on the tailnet. Idempotent: a second call while a server is up returns it.
     * Returns null (with a log line naming why) when there is no tailnet address or the port is
     * taken — a phone that cannot be reached says so instead of pretending to listen.
     */
    fun start(context: Context, handler: PhoneToolHandler): PhoneMcpServer? {
        synchronized(this) {
            server?.let { return it }
            val app = context.applicationContext ?: context
            val address = try {
                Tailnet.pick(Tailnet.snapshot())
            } catch (_: Throwable) {
                null
            }
            if (address == null) {
                VoxLog.w("event=phone-mcp unavailable reason=no-tailnet")
                return null
            }
            val token = token(app)
            return try {
                val s = PhoneMcpServer.start(
                    bind = address,
                    token = token,
                    handler = handler,
                    log = { line -> VoxLog.d(line) },
                )
                server = s
                VoxLog.d("event=phone-mcp start url=${s.url} port=${s.port}")
                s
            } catch (e: Throwable) {
                VoxLog.w("event=phone-mcp unavailable reason=bind error=${e.javaClass.simpleName}")
                null
            }
        }
    }

    /** Stop serving. The registry in [LocalActions] is untouched: the phone's state outlives the socket. */
    fun stop() {
        synchronized(this) {
            val s = server ?: return
            server = null
            s.close()
            VoxLog.d("event=phone-mcp stop served=${s.served}")
        }
    }
}

/**
 * PhoneMcpConfig — the `mcp_servers` snippet, in one place so the app can show the owner exactly
 * what to paste and `docs/MCP-PHONE.md` can say the same bytes.
 *
 * Pure JVM, unit-tested in [PhoneMcpConfigTest]: a snippet that drifts from what the server actually
 * speaks is a support ticket, not documentation.
 */
object PhoneMcpConfig {

    /** The `mcp_servers` key the owner adds. The model sees the tool as `mcp__vox__phone`. */
    const val SERVER_NAME = "vox"

    /** The tool name the model sees, once the gateway has prefixed the server. */
    const val MODEL_TOOL_NAME = "mcp__vox__phone"

    /**
     * The exact YAML. Four decisions are visible in it:
     *
     *  * `url` names the tailnet address and the port — nothing on the LAN reaches it;
     *  * the bearer token rides a header, from the profile's `.env` rather than pasted inline;
     *  * `connect_timeout` is short, because the phone is only up while a call is;
     *  * `trust: full` is what makes Tier 0 instant; `trust: untrusted` is the option for an owner
     *    who would rather approve every call on the phone.
     */
    fun snippet(host: String, token: String = "<your phone's token>", port: Int = PhoneMcpServer.PORT): String =
        buildString {
            append("mcp_servers:\n")
            append("  ").append(SERVER_NAME).append(":\n")
            append("    url: \"http://").append(host).append(':').append(port).append(PhoneMcpServer.PATH).append("\"\n")
            append("    headers:\n")
            append("      Authorization: \"Bearer ${token}\"\n")
            append("    connect_timeout: 5\n")
            append("    tool_timeout: 30\n")
            append("    trust: full   # set `untrusted` to require an approval card for every call\n")
            append("    tools:\n")
            append("      include: [").append(PhoneTool.NAME).append("]\n")
        }

    /** The one line the settings row shows before the dialog is opened. */
    fun rowValue(url: String?): String = if (url.isNullOrBlank()) "off (no call)" else url
}
