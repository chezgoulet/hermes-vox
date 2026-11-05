package com.hermesvox

import android.content.Context

/**
 * PhoneMcp — the app's side of the phone-as-MCP-server (#421 steps 3–5): the token, the bind, the
 * life, and the one piece of evidence that makes **Connected** an honest word.
 *
 * **When it runs.** The server is up exactly while a voice call is up (started by
 * `VoiceController.start`, stopped by `VoiceController.stop`). That is deliberate: the mind only
 * reaches for the phone while the caller is talking to it, and a listening socket that outlives the
 * call is a surface with no one watching it. There is no foreground service for the listener and no
 * new service type: the call's own microphone service is the lifetime. The cost, stated plainly in
 * `docs/MCP-PHONE.md`: the gateway's first tool call after a call begins may pay one connect, and a
 * call that is down means the tool is down.
 *
 * **Where it binds.** The tailnet address [Tailnet] picked — the same rule as the file-offer server,
 * for the same reason. Never `0.0.0.0`, never the LAN, never cellular. If the phone is not on the
 * tailnet there is no server and the log says so.
 *
 * **The token.** Generated once, [PhoneMcpToken.BITS] bits, stored encrypted with an Android
 * Keystore alias of its own ([SecureStore]). If the keystore refuses to encrypt, the token is kept in
 * memory for this launch only rather than written in the clear — the log says `token-store=failed`,
 * and the owner has to set up again next launch. A stored secret in plaintext SharedPreferences is
 * the one outcome worth losing a session over.
 *
 * **It is not the only credential** (#452). The agent this phone already talks to presents the API
 * key of the active connection — the one the phone sends on every turn — and the server accepts it
 * too ([connectionKey]). So the normal setup needs no pairing step and no secret in the setup turn:
 * the agent writes the entry with a reference to the key it already holds. The phone's own token
 * stays for a host that is *not* the one this phone talks to, which is what the manual block and
 * ROTATE TOKEN are for.
 *
 * **Rotation.** [rotate] replaces the token and the live server reads the new one on the next
 * request, so the old credential stops working immediately. It also clears the recorded handshake:
 * a connection proved with the old token is not evidence for the new one. The setup sheet then asks
 * the agent again with the new token.
 *
 * **Connected means handshake, not reply.** [PhoneMcpServer] calls [recordHandshake] only after an
 * authenticated `initialize`. The sheet reads that through [PhoneSetupStore]; no agent's sentence
 * can set the state.
 */
object PhoneMcp {

    private const val PREFS = "hv"
    private const val KEY_TOKEN = "phone_mcp_token"
    private const val KEY_HANDSHAKE_AT = "phone_mcp_handshake_at"
    private const val KEY_HANDSHAKE_TAG = "phone_mcp_handshake_tag"
    private const val ALIAS = "hv_phone_mcp"

    /** What the owner can be told about the credential without printing it. */
    enum class TokenState { NONE, STORED, SESSION_ONLY, UNREADABLE }

    /** The facts the setup sheet shows. Addresses and shape, never the token itself. */
    data class Status(
        val tailnet: String?,
        val listening: Boolean,
        val url: String?,
        val served: Int,
        val lastRequest: String?,
        val lastRequestAt: Long?,
        val token: TokenState,
        val dndGranted: Boolean,
        val volumeGranted: Boolean,
        /** The folders the owner granted with the system picker, by their own names. */
        val folders: List<String> = emptyList(),
        /** True when both calendar runtime permissions are held. */
        val calendarGranted: Boolean = false,
        /** The exact permission still missing, or null when both are held. */
        val calendarMissing: String? = null,
        /** Whether Alpha features' Phone tools set is selected. False means nothing here runs. */
        val allowed: Boolean = true,
    )

    @Volatile private var server: PhoneMcpServer? = null
    @Volatile private var memoryToken: String? = null
    @Volatile private var lastRequest: String? = null
    @Volatile private var lastRequestAt: Long? = null

    /** The URL the gateway should be pointed at, or null while the server is not running. */
    fun currentUrl(): String? = server?.url

    /**
     * The bearer token, generated on first use and then stable. Never logged.
     *
     * Encrypted on disk; a value that cannot be encrypted stays in memory for this launch only.
     * Calling this is the moment a credential exists, which is why the setup sheet's **Set up**
     * button is the only UI path that reaches it before a call does.
     */
    fun token(context: Context): String {
        // #421/alpha: the credential does not exist before the owner selects Phone tools.
        // Returning "" (rather than generating and hiding it) keeps the server's own
        // `require(tokenProvider().isNotEmpty())` a second door, not the only one.
        if (!AlphaGate.allows(context, AlphaGate.FeatureSet.PHONE_TOOLS)) {
            VoxLog.d("event=phone-mcp token=off reason=alpha")
            return ""
        }
        synchronized(this) {
            memoryToken?.let { return it }
            val app = context.applicationContext ?: context
            val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
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
            storeToken(app, fresh)
            memoryToken = fresh
            return fresh
        }
    }

    /** The token state, without generating one: "not set" must stay "not set" until **Set up**. */
    fun tokenState(context: Context): TokenState = synchronized(this) {
        val app = context.applicationContext ?: context
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_TOKEN, null)
        when {
            !stored.isNullOrBlank() -> {
                val plain = SecureStore.decrypt(stored, ALIAS)
                if (!plain.isNullOrEmpty()) TokenState.STORED else TokenState.UNREADABLE
            }
            memoryToken != null -> TokenState.SESSION_ONLY
            else -> TokenState.NONE
        }
    }

    /**
     * A non-reversible tag for the token in force, or "" when no token exists yet. Used to tie a
     * recorded handshake to the credential it was made with; never printed and never logged.
     */
    fun tokenTag(context: Context): String {
        if (tokenState(context) == TokenState.NONE) return ""
        return PhoneMcpToken.fingerprint(token(context))
    }

    /**
     * Replace the token. The live server is handed a provider ([start]), so the next request is
     * checked against the new value and the old one is refused. The recorded handshake is cleared
     * with it: the old connection is not evidence for the new credential.
     */
    fun rotate(context: Context): String = synchronized(this) {
        if (!AlphaGate.allows(context, AlphaGate.FeatureSet.PHONE_TOOLS)) {
            VoxLog.d("event=phone-mcp token=off reason=alpha action=rotate")
            return ""
        }
        val app = context.applicationContext ?: context
        val fresh = PhoneMcpToken.generate()
        storeToken(app, fresh)
        memoryToken = fresh
        clearHandshake(app)
        VoxLog.w("event=phone-mcp token=rotated")
        fresh
    }

    /**
     * The API key of the connection in force — the credential the phone sends on its own turns
     * (#452). Resolved exactly as every live path resolves it ([GatewayKey.resolve] over the stored
     * pref through [SecureStore]), so the server and the turn path can never disagree about what
     * this key is. Read per request, so a key re-saved in Settings is in force on the next one.
     *
     * Empty when no connection is configured: then only the phone's own token authenticates. It is
     * never logged, never echoed and never stored anywhere new — it already lives in the profile's
     * key slot.
     */
    private fun connectionKey(context: Context): String =
        GatewayKey.resolve(
            prefs(context).getString(Profiles.K_KEY, "").orEmpty(),
            SecureStore::decrypt,
        )

    /** The persisted handshake, if any. Read by the setup sheet through [PhoneSetupStore]. */
    fun handshakeAt(context: Context): Long =
        prefs(context).getLong(KEY_HANDSHAKE_AT, 0L)

    fun handshakeTag(context: Context): String? =
        prefs(context).getString(KEY_HANDSHAKE_TAG, null)

    /**
     * The phone's own server saw an authenticated `initialize`. This is the *only* thing that can
     * make the sheet say **Connected**; the agent's reply is never consulted for it.
     */
    fun recordHandshake(context: Context, at: Long) {
        val app = context.applicationContext ?: context
        val tag = tokenTag(app)
        if (tag.isEmpty()) return
        prefs(app).edit()
            .putLong(KEY_HANDSHAKE_AT, at)
            .putString(KEY_HANDSHAKE_TAG, tag)
            .apply()
        VoxLog.d("event=phone-mcp connected handshake=initialize")
    }

    private fun clearHandshake(context: Context) {
        prefs(context).edit().remove(KEY_HANDSHAKE_AT).remove(KEY_HANDSHAKE_TAG).apply()
    }

    private fun prefs(context: Context) =
        (context.applicationContext ?: context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun storeToken(context: Context, fresh: String) {
        val prefs = prefs(context)
        val encrypted = SecureStore.encrypt(fresh, ALIAS)
        if (encrypted != null && encrypted != fresh) {
            prefs.edit().putString(KEY_TOKEN, encrypted).apply()
        } else {
            // No plaintext token on disk, ever. The session works; the next launch generates a new one.
            prefs.edit().remove(KEY_TOKEN).apply()
            VoxLog.w("event=phone-mcp token-store=failed session-only=true")
        }
    }

    /** Everything the sheet needs, read without creating a credential or a listener. */
    fun status(context: Context): Status {
        val app = context.applicationContext ?: context
        val s = server
        val tailnet = try {
            Tailnet.address()?.hostAddress
        } catch (_: Throwable) {
            null
        }
        val nm = try {
            app.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        } catch (_: Throwable) {
            null
        }
        val dnd = try {
            nm?.isNotificationPolicyAccessGranted == true
        } catch (_: Throwable) {
            false
        }
        val volume = try {
            app.checkSelfPermission(android.Manifest.permission.MODIFY_AUDIO_SETTINGS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            true   // a normal permission; before the check API it is granted at install
        }
        val folders = try {
            PhoneFileAccess.trees(app).map { it.label }
        } catch (_: Throwable) {
            emptyList()
        }
        val calendarMissing = try {
            PhoneCalendarAccess.permission(app)
        } catch (_: Throwable) {
            android.Manifest.permission.READ_CALENDAR
        }
        return Status(
            tailnet = tailnet,
            listening = s != null,
            url = s?.url,
            served = s?.served ?: 0,
            lastRequest = lastRequest,
            lastRequestAt = lastRequestAt,
            token = tokenState(app),
            dndGranted = dnd,
            volumeGranted = volume,
            folders = folders,
            calendarGranted = calendarMissing == null,
            calendarMissing = calendarMissing,
            allowed = AlphaGate.allows(app, AlphaGate.FeatureSet.PHONE_TOOLS),
        )
    }

    /**
     * Start serving on the tailnet. Idempotent: a second call while a server is up returns it.
     * Returns null (with a log line naming why) when there is no tailnet address or the port is
     * taken — a phone that cannot be reached says so instead of pretending to listen.
     */
    fun start(context: Context, handler: PhoneToolHandler): PhoneMcpServer? {
        synchronized(this) {
            if (!AlphaGate.allows(context, AlphaGate.FeatureSet.PHONE_TOOLS)) {
                // The set is off: no listener, and no credential is created by asking.
                VoxLog.d("event=phone-mcp unavailable reason=alpha-off")
                return null
            }
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
            return try {
                val s = PhoneMcpServer.start(
                    bind = address,
                    // A provider, not a snapshot: rotation invalidates the old token on the very
                    // next request without restarting the listener.
                    tokenProvider = { token(app) },
                    // #452: the second credential is the one the agent already has. Read live from
                    // the same store the turn path uses, so this server never holds a copy.
                    keyProvider = { connectionKey(app) },
                    handler = handler,
                    log = { line -> VoxLog.d(line) },
                    onRequest = { method, at ->
                        lastRequest = method
                        lastRequestAt = at
                    },
                    onInitialize = { at -> recordHandshake(app, at) },
                    // The grants, read per request: the enum the gateway lists must lose a group
                    // the owner turns off, without restarting the listener or the call.
                    grants = PhoneGrantStore.source(app),
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
 * PhoneMcpConfig — the `mcp_servers` snippet, in one place so the sheet can show the **manual
 * fallback** and `docs/MCP-PHONE.md` can say the same bytes.
 *
 * The snippet is no longer the setup path: the app asks the agent to edit its own configuration.
 * It stays for the owner whose agent cannot self-configure, behind a disclosure and labelled as the
 * fallback. Pure JVM, unit-tested in [PhoneMcpTokenTest].
 */
object PhoneMcpConfig {

    /** The `mcp_servers` key the owner adds. The model sees the tool as `mcp__vox__phone`. */
    const val SERVER_NAME = "vox"

    /** The tool name the model sees, once the gateway has prefixed the server. */
    const val MODEL_TOOL_NAME = "mcp__vox__phone"

    /** The address and placeholder the document uses; both sides are generated from here. */
    const val DOC_HOST = "100.101.102.103"
    const val DOC_TOKEN = "<the token from the phone>"

    /**
     * The exact YAML. Four decisions are visible in it:
     *
     *  * `url` names the tailnet address and the port — nothing on the LAN reaches it;
     *  * the bearer token rides a header, from the profile's `.env` rather than pasted inline;
     *  * `connect_timeout` is short, because the phone is only up while a call is;
     *  * `trust: full` is what makes Tier 0 instant; `trust: untrusted` is the option for an owner
     *    who would rather approve every call on the phone.
     *
     * The keys are the documented ones (`url`, `headers`, `connect_timeout`, `timeout`, `trust`,
     * `tools.include` — the MCP config reference). The per-server tool-call timeout is `timeout`,
     * not `tool_timeout`: an unknown key is ignored, which reads as "the timeout did nothing".
     */
    fun snippet(host: String, token: String = DOC_TOKEN, port: Int = PhoneMcpServer.PORT): String =
        buildString {
            append("mcp_servers:\n")
            append("  ").append(SERVER_NAME).append(":\n")
            append("    url: \"http://").append(hostForUrl(host)).append(':').append(port).append(PhoneMcpServer.PATH).append("\"\n")
            append("    headers:\n")
            append("      Authorization: \"Bearer ${token}\"\n")
            append("    connect_timeout: 5\n")
            append("    timeout: 30\n")
            append("    trust: full   # set `untrusted` to require an approval card for every call\n")
            append("    tools:\n")
            append("      include: [").append(PhoneTool.NAME).append("]\n")
        }

    /** The block the document prints. Generated here, not retyped there. */
    fun docSnippet(): String = snippet(DOC_HOST, DOC_TOKEN)

    /** An IPv6 tailnet address needs brackets inside a URL; a v4 address must not get them. */
    fun hostForUrl(host: String): String {
        val h = host.trim()
        return if (h.contains(':') && !h.startsWith("[")) "[$h]" else h
    }

    /** The one line the settings row shows before the sheet is opened. */
    fun rowValue(url: String?): String = if (url.isNullOrBlank()) "off (no call)" else url
}
