package com.hermesvox.core

import java.net.InetSocketAddress
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
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
    val targetHost: String,
    val targetPort: Int,
    val configuredAlias: String,
    private val provider: MtlsKeyProvider,
) : X509ExtendedKeyManager() {

    internal fun matchesOrigin(socket: Socket?): Boolean {
        if (socket == null) return false
        val endpoint = socket.remoteSocketAddress as? InetSocketAddress ?: return false
        val port = endpoint.port
        if (port <= 0 || port != targetPort) return false
        val host = endpoint.hostString ?: endpoint.hostName ?: return false
        if (!host.equals(targetHost, ignoreCase = true)) {
            val altHost = endpoint.hostName
            if (altHost.isNullOrEmpty() || !altHost.equals(targetHost, ignoreCase = true)) {
                return false
            }
        }
        return true
    }

    internal fun matchesEngine(engine: SSLEngine?): Boolean {
        if (engine == null) return false
        val port = engine.peerPort
        if (port <= 0 || port != targetPort) return false
        val host = engine.peerHost ?: return false
        if (!host.equals(targetHost, ignoreCase = true)) return false
        return true
    }

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
        return provider.getPrivateKey(alias)
    }

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
        if (alias.isNullOrBlank() || alias != configuredAlias) return null
        return provider.getCertificateChain(alias)
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
