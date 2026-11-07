package com.hermesvox.core

import java.net.InetSocketAddress
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager

/**
 * [X509ExtendedKeyManager] scoped strictly to a specific TLS origin (host and port).
 *
 * Guarantees:
 * 1. Only supplies [configuredAlias] when connecting to the expected origin.
 *    If a redirect or socket connection routes to a different host/port, client
 *    alias selection returns null to prevent cross-origin identity leaks.
 * 2. Delegates private key and certificate retrieval to [MtlsKeyProvider],
 *    leaving private key material in hardware-backed storage (Android KeyChain).
 * 3. Server verification remains untouched: platform CA verification applies.
 */
class MtlsKeyManager(
    targetHost: String,
    val targetPort: Int,
    val configuredAlias: String,
    private val provider: MtlsKeyProvider,
) : X509ExtendedKeyManager() {

    /**
     * The origin this manager will answer for, in the SAME spelling [MtlsConfig.originOf]
     * produced (ASCII, case-folded, IPv6 unbracketed). Comparing a raw origin key with a
     * raw socket host is how an IPv6 literal never matched — and therefore never sent a
     * certificate, silently.
     */
    val targetHost: String = MtlsConfig.normaliseHost(targetHost) ?: targetHost.lowercase(Locale.ROOT)

    internal fun matchesOrigin(socket: Socket?): Boolean {
        val endpoint = socket?.remoteSocketAddress as? InetSocketAddress ?: return refuse(UNVERIFIED)
        if (endpoint.port <= 0 || endpoint.port != targetPort) return refuse(MISMATCH)
        // The host the socket actually connected to — never its reverse-DNS name.
        // A reverse lookup turns "did we connect to the origin we chose?" into "does
        // this name point at the same address?", which a redirector controls and a
        // resolver can answer differently between checks. The socket's own host string
        // is what the connection was made with, so it is the only answer that means
        // what the check claims.
        val peer = MtlsConfig.normaliseHost(endpoint.hostString) ?: return refuse(UNVERIFIED)
        return peer == targetHost || refuse(MISMATCH)
    }

    internal fun matchesEngine(engine: SSLEngine?): Boolean {
        val port = engine?.peerPort ?: return refuse(UNVERIFIED)
        if (port <= 0 || port != targetPort) return refuse(MISMATCH)
        val host = engine.peerHost ?: return refuse(UNVERIFIED)
        val peer = MtlsConfig.normaliseHost(host) ?: return refuse(UNVERIFIED)
        return peer == targetHost || refuse(MISMATCH)
    }

    /** Fail closed, and say why: a redirect to another host is a named reason, not just a
     *  handshake that unexpectedly asked for a certificate the app did not send. */
    private fun refuse(reason: MtlsConfig.Reason): Boolean {
        MtlsConfig.unavailable(reason, originKey())
        return false
    }

    private companion object {
        val MISMATCH = MtlsConfig.Reason.ORIGIN_MISMATCH
        val UNVERIFIED = MtlsConfig.Reason.ORIGIN_UNVERIFIED
    }

    /** The origin key for a failure line, in the same spelling the preferences use. */
    private fun originKey(): String =
        if (targetHost.contains(':')) "[$targetHost]:$targetPort" else "$targetHost:$targetPort"

    override fun chooseClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? {
        if (!matchesOrigin(socket)) return null
        return configuredAlias.ifBlank { null }
    }

    override fun chooseEngineClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String? {
        if (!matchesEngine(engine)) return null
        return configuredAlias.ifBlank { null }
    }

    override fun getPrivateKey(alias: String?): PrivateKey? {
        if (alias.isNullOrBlank() || alias != configuredAlias) return null
        val key = try { provider.getPrivateKey(alias) } catch (_: Throwable) { null }
        // The alias exists but its key does not come back: revoked, locked, removed from
        // the store, or the store refused the IPC. The handshake will fail with no client
        // certificate either way; naming it is the difference between "never sent" and
        // "sent and rejected".
        if (key == null) MtlsConfig.unavailable(MtlsConfig.Reason.KEY_UNAVAILABLE, originKey())
        return key
    }

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
        if (alias.isNullOrBlank() || alias != configuredAlias) return null
        val chain = try { provider.getCertificateChain(alias) } catch (_: Throwable) { null }
        if (chain.isNullOrEmpty()) {
            MtlsConfig.unavailable(MtlsConfig.Reason.CERT_UNAVAILABLE, originKey())
            return null
        }
        return chain
    }

    override fun getClientAliases(
        keyType: String?,
        issuers: Array<out Principal>?,
    ): Array<String>? = if (configuredAlias.isNotBlank()) arrayOf(configuredAlias) else null

    override fun getServerAliases(
        keyType: String?,
        issuers: Array<out Principal>?,
    ): Array<String>? = null

    override fun chooseServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = null
}
