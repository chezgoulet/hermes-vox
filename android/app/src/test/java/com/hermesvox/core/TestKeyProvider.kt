package com.hermesvox.core

import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * In-memory [MtlsKeyProvider] backed by a PKCS#12 test keystore for JVM unit testing.
 */
class TestKeyProvider(
    resource: String = "/tls/client-identities.p12",
    password: String = "test-only",
) : MtlsKeyProvider {
    private val keyStore = KeyStore.getInstance("PKCS12").apply {
        TestKeyProvider::class.java.getResourceAsStream(resource).use { ins ->
            load(requireNotNull(ins) { "Keystore resource $resource not found" }, password.toCharArray())
        }
    }

    override fun getPrivateKey(alias: String): PrivateKey? {
        return try {
            keyStore.getKey(alias, "test-only".toCharArray()) as? PrivateKey
        } catch (_: Throwable) {
            null
        }
    }

    override fun getCertificateChain(alias: String): Array<X509Certificate>? {
        return try {
            keyStore.getCertificateChain(alias)?.filterIsInstance<X509Certificate>()?.toTypedArray()
        } catch (_: Throwable) {
            null
        }
    }
}
