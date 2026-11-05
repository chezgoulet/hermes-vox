package com.hermesvox.core

import android.content.Context
import android.content.SharedPreferences
import java.net.URI
import java.net.URL
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

    data class Origin(val host: String, val port: Int) {
        val key: String get() = "$host:$port"
    }

    private var appContext: Context? = null
    private var activeProvider: MtlsKeyProvider? = null
    private var testPrefs: SharedPreferences? = null
    private val socketFactoryCache = ConcurrentHashMap<String, SSLSocketFactory>()

    /** Initializes runtime context. Safe to call multiple times. */
    fun init(context: Context) {
        appContext = context.applicationContext
        if (activeProvider == null) {
            activeProvider = AndroidKeyChainProvider(context.applicationContext)
        }
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

    /**
     * Extracts canonical [Origin] from a URL string.
     * Returns null for non-HTTPS schemes or invalid URLs.
     */
    fun originOf(urlString: String): Origin? {
        val trimmed = urlString.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val uri = URI(trimmed)
            val scheme = uri.scheme ?: return null
            if (!scheme.equals("https", ignoreCase = true)) return null
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else 443
            Origin(host.lowercase(), port)
        } catch (_: Throwable) {
            try {
                val u = URL(trimmed)
                if (!u.protocol.equals("https", ignoreCase = true)) return null
                val host = u.host ?: return null
                val port = if (u.port > 0) u.port else 443
                Origin(host.lowercase(), port)
            } catch (_: Throwable) {
                null
            }
        }
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
            ?: appContext?.getSharedPreferences("hv", Context.MODE_PRIVATE)
            ?: return
        val alias = getAlias(prefs, origin) ?: return
        val factory = getSocketFactory(origin, alias) ?: return
        conn.sslSocketFactory = factory
    }
}
