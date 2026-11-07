package com.hermesvox.core

import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Abstraction over client private key and certificate chain retrieval.
 *
 * In production Android execution, this delegates to [android.security.KeyChain]
 * on background TLS handshake threads. In JVM unit tests, this allows supplying
 * in-memory keys and test certificates without Android runtime dependencies.
 */
interface MtlsKeyProvider {
    /** Returns the private key for [alias], or null if unavailable. */
    fun getPrivateKey(alias: String): PrivateKey?

    /** Returns the certificate chain for [alias], or null if unavailable. */
    fun getCertificateChain(alias: String): Array<X509Certificate>?
}
