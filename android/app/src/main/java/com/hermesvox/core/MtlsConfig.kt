package com.hermesvox.core

import android.content.Context
import android.content.SharedPreferences
import java.net.IDN
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory

/**
 * Per-origin mTLS client certificate configuration and SSLSocketFactory management.
 *
 * Scopes client certificates strictly by HTTPS origin (host + port).
 * Integrates directly with [HttpsURLConnection] while keeping system trust managers intact.
 */
object MtlsConfig {
    const val PREF_PREFIX = "mtls_alias_"

    /**
     * A TLS origin: the normalised host (see [normaliseHost]) and the port.
     *
     * [host] is always the ASCII form, with IPv6 brackets stripped, so it can be
     * compared with the peer the socket reports. [key] puts the brackets back for an
     * IPv6 literal: as a preference key "::1:443" would be indistinguishable from a
     * host named "::1:443", and a key that can be misread is a key that can be written
     * to the wrong origin.
     */
    data class Origin(val host: String, val port: Int) {
        val key: String get() = if (host.contains(':')) "[$host]:$port" else "$host:$port"
    }

    @Volatile private var appContext: Context? = null
    @Volatile private var appPrefs: SharedPreferences? = null
    @Volatile private var activeProvider: MtlsKeyProvider? = null
    @Volatile private var testPrefs: SharedPreferences? = null
    private val socketFactoryCache = ConcurrentHashMap<String, SSLSocketFactory>()

    /**
     * Installs the process-wide runtime facts: the application context, the preference
     * store and the KeyChain provider.
     *
     * Called once, from `VoxApplication.onCreate`. It used to be called from two
     * activities' `onCreate`, which left every path that runs before an activity — a
     * service, a background probe, a cold turn — with `appContext == null`, and a null
     * context here means "no certificate sent", silently. Idempotent, so a second call
     * (a test harness, a future entry point) changes nothing.
     */
    fun init(context: Context, prefs: SharedPreferences? = null) {
        val app = context.applicationContext
        appContext = app
        if (prefs != null) appPrefs = prefs
        if (activeProvider == null) activeProvider = AndroidKeyChainProvider(app)
    }

    /** Overrides the [MtlsKeyProvider] for JVM unit testing. */
    fun setProviderForTest(provider: MtlsKeyProvider?) {
        activeProvider = provider
        invalidateCache()
    }

    /** Overrides [SharedPreferences] for JVM unit testing. */
    fun setPrefsForTest(prefs: SharedPreferences?) {
        testPrefs = prefs
        invalidateCache()
    }

    /** Clears cached SSLSocketFactories. */
    fun invalidateCache() {
        socketFactoryCache.clear()
    }

    /** scheme://authority — the authority ends at the first path, query or fragment. */
    private val ORIGIN_RE = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*)://([^/?#]*)")

    /** A DNS name / IPv4 literal / percent-escaped reg-name. No spaces, no colon. */
    private val HOST_RE = Regex("^[A-Za-z0-9._~%\\-]+$")

    /**
     * Extracts the canonical [Origin] from a URL string, or null when the string is not a
     * usable HTTPS origin.
     *
     * This is the key every certificate decision is stored and compared under, so it is
     * parsed here once, by hand, rather than through `URI`/`URL`:
     *
     *  * `URI.host` is **null** for a non-ASCII host, and `URL.host` is null for one on
     *    Android — either way an IDN gateway would silently end up with no certificate.
     *    The host is converted to its ASCII (punycode) form first, which is also what the
     *    TLS layer will connect to, so both sides agree on one spelling.
     *  * `URI.host` keeps an IPv6 literal's **brackets** (`[2001:db8::1]`) while a socket
     *    reports the bare address, so an IPv6 literal could never match and the
     *    certificate would never be sent. Brackets are stripped and the address is
     *    canonicalised (`2001:db8:0:0::1` and `2001:db8::1` are one origin).
     *
     * Everything else is rejected (plain http, a non-numeric port, a malformed authority)
     * so a caller can name the reason instead of pretending no certificate was configured.
     */
    fun originOf(urlString: String): Origin? {
        val trimmed = urlString.trim()
        if (trimmed.isEmpty()) return null
        val m = ORIGIN_RE.find(trimmed) ?: return null
        if (!m.groupValues[1].equals("https", ignoreCase = true)) return null
        // userinfo ends at the LAST '@': a password may contain one.
        val hostPort = m.groupValues[2].substringAfterLast('@')
        if (hostPort.isEmpty()) return null
        val split = splitHostPort(hostPort) ?: return null
        val host = normaliseHost(split.first) ?: return null
        val port = when {
            split.second.isNullOrEmpty() -> 443
            else -> split.second!!.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        }
        return Origin(host, port)
    }

    /** `host[:port]`, with an IPv6 literal in brackets as RFC 3986 requires. */
    private fun splitHostPort(hostPort: String): Pair<String, String?>? {
        if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            if (close <= 1) return null
            val host = hostPort.substring(0, close + 1)
            val rest = hostPort.substring(close + 1)
            if (rest.isEmpty()) return host to null
            if (!rest.startsWith(":")) return null
            return host to rest.substring(1)
        }
        // An IPv6 literal must be bracketed (RFC 3986). Unbracketed, "2001:db8::1:8642"
        // cannot say which of those groups is the port, and guessing is how one origin
        // gets filed under another — so it is refused, not interpreted.
        if (hostPort.contains(':')) {
            val colon = hostPort.lastIndexOf(':')
            if (hostPort.substring(0, colon).contains(':')) return null
        }
        val colon = hostPort.lastIndexOf(':')
        if (colon < 0) return hostPort to null
        val port = hostPort.substring(colon + 1)
        if (port.isEmpty()) return hostPort.substring(0, colon) to null
        if (!port.all { it.isDigit() }) return hostPort to null
        return hostPort.substring(0, colon) to port
    }

    /**
     * The one spelling of a host: ASCII, case-folded, no brackets, no trailing root dot,
     * IPv6 literals in canonical compressed form.
     *
     * Used for both sides of every comparison — the origin in preferences and the peer a
     * socket reports — so the two can never disagree about spelling (which is how an IPv6
     * or IDN gateway ended up sending no certificate, silently). Returns null for a host
     * that cannot be a TLS origin at all.
     */
    internal fun normaliseHost(raw: String): String? {
        var h = raw.trim()
        if (h.startsWith("[") && h.endsWith("]") && h.length > 2) h = h.substring(1, h.length - 1)
        if (h.isEmpty()) return null
        if (h.any { it.code >= 128 }) {
            h = try { IDN.toASCII(h, IDN.ALLOW_UNASSIGNED) } catch (_: Exception) { return null }
        }
        h = h.trimEnd('.')
        if (h.isEmpty()) return null
        if (h.contains(':')) {
            // An IPv6 literal: canonicalise through InetAddress, which never does DNS for
            // a literal (there is no name to resolve).
            return try { compressV6(InetAddress.getByName(h).hostAddress) } catch (_: Exception) { null }
        }
        if (!HOST_RE.matches(h)) return null
        return h.lowercase(Locale.ROOT)
    }

    /**
     * One spelling for an IPv6 literal (RFC 5952: lower case, no leading zeros, the
     * longest run of zero groups — two or more — written as `::`), because
     * `InetAddress.getHostAddress` expands every group. Equality is all the guard needs,
     * but the origin key is also what a log line and a preference carry, and
     * `[0:0:0:0:0:0:0:1]:8642` is unreadable next to `[::1]:8642`.
     */
    private fun compressV6(expanded: String): String {
        val groups = expanded.split(':')
        if (groups.size != 8) return expanded
        var bestStart = -1
        var bestLen = 0
        var i = 0
        while (i < groups.size) {
            if (groups[i] != "0") { i++; continue }
            var j = i
            while (j < groups.size && groups[j] == "0") j++
            if (j - i > bestLen) { bestLen = j - i; bestStart = i }
            i = j
        }
        if (bestLen < 2) return expanded
        val head = groups.subList(0, bestStart).joinToString(":")
        val tail = groups.subList(bestStart + bestLen, groups.size).joinToString(":")
        return "$head::$tail"
    }

    /**
     * What a Save may do with the alias for [finalOrigin].
     *
     * [Write] with a null alias means "remove whatever this origin held"; [Leave] means
     * "this origin is not the subject of the edit, do not touch its certificate".
     */
    sealed class SaveAlias {
        object Leave : SaveAlias()
        data class Write(val alias: String?) : SaveAlias()
    }

    /**
     * The origin-binding rule, in one testable place.
     *
     * A certificate belongs to the origin it was explicitly chosen on:
     *
     *  * the user chose it while the address field spelled [finalOrigin] → write it;
     *  * the address changed away from [initialOrigin] and was not chosen for again →
     *    write nothing and clear [finalOrigin]. An alias must never travel to a new host
     *    because the edit left it on screen: that would silently make the old host's
     *    client certificate the new host's credential, while the row still showed the old
     *    alias and read as confirmation;
     *  * otherwise (the address is unchanged) → leave the stored value alone, which keeps
     *    a certificate configured in an earlier session.
     */
    fun saveAlias(
        initialOrigin: Origin?,
        chosenFor: Origin?,
        alias: String?,
        finalOrigin: Origin,
    ): SaveAlias {
        val clean = alias?.trim()?.takeIf { it.isNotEmpty() }
        if (finalOrigin == chosenFor) return SaveAlias.Write(clean)
        if (finalOrigin != initialOrigin) return SaveAlias.Write(null)
        return SaveAlias.Leave
    }

    /** Retrieves the configured certificate alias for [origin], or null if unconfigured. */
    fun getAlias(prefs: SharedPreferences, origin: Origin): String? {
        return prefs.getString(PREF_PREFIX + origin.key, null)?.takeIf { it.isNotBlank() }
    }

    /** Sets or removes the configured certificate alias for [origin]. */
    fun setAlias(prefs: SharedPreferences, origin: Origin, alias: String?) {
        val editor = prefs.edit()
        val k = PREF_PREFIX + origin.key
        if (alias.isNullOrBlank()) {
            editor.remove(k)
        } else {
            editor.putString(k, alias.trim())
        }
        editor.apply()
        invalidateCache()
    }

    /**
     * Builds or returns a cached [SSLSocketFactory] containing an [MtlsKeyManager]
     * for [origin] with [alias].
     */
    fun getSocketFactory(
        origin: Origin,
        alias: String,
        customProvider: MtlsKeyProvider? = null,
    ): SSLSocketFactory? {
        val provider = customProvider ?: activeProvider ?: return null
        val cacheKey = "${origin.key}:$alias"
        return socketFactoryCache.computeIfAbsent(cacheKey) {
            val km = MtlsKeyManager(origin.host, origin.port, alias, provider)
            val ctx = SSLContext.getInstance("TLS")
            // Passing null for TrustManager preserves platform standard trust verification
            ctx.init(arrayOf<KeyManager>(km), null, null)
            ctx.socketFactory
        }
    }

    /**
     * Applies the configured client certificate SSLSocketFactory to [conn] if [url]
     * has a configured alias in preferences.
     */
    fun applyIfConfigured(
        conn: HttpsURLConnection,
        url: String,
        explicitPrefs: SharedPreferences? = null,
    ) {
        val origin = originOf(url) ?: return
        val prefs = explicitPrefs
            ?: testPrefs
            ?: appPrefs
            ?: appContext?.getSharedPreferences("hv", Context.MODE_PRIVATE)
            ?: return
        val alias = getAlias(prefs, origin) ?: return
        val factory = getSocketFactory(origin, alias) ?: return
        conn.sslSocketFactory = factory
    }
}
