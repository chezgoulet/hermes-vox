package com.hermesvox.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLContext

class MtlsKeyManagerTest {
    private val provider = TestKeyProvider()

    @Test
    fun testKeyManagerRetrievalForConfiguredAlias() {
        val km = MtlsKeyManager("example.com", 8443, "first", provider)
        val key = km.getPrivateKey("first")
        assertNotNull("Private key should be retrieved for configured alias", key)

        val chain = km.getCertificateChain("first")
        assertNotNull("Certificate chain should be retrieved for configured alias", chain)
        assertEquals(1, chain!!.size)

        // Non-matching alias returns null
        assertNull(km.getPrivateKey("second"))
        assertNull(km.getCertificateChain("second"))
        assertNull(km.getPrivateKey(""))
        assertNull(km.getPrivateKey(null))
    }

    @Test
    fun testChooseClientAliasMatchesTargetOrigin() {
        val km = MtlsKeyManager("gateway.example.com", 443, "first", provider)

        // Null socket returns configured alias
        assertEquals("first", km.chooseClientAlias(null, null, null))

        // Client aliases list
        assertArrayEquals(arrayOf("first"), km.getClientAliases(null, null))

        // Server aliases always null (client auth only)
        assertNull(km.getServerAliases(null, null))
        assertNull(km.chooseServerAlias(null, null, null))
    }

    @Test
    fun testChooseEngineClientAliasMatchesOrigin() {
        val km = MtlsKeyManager("gateway.example.com", 8443, "first", provider)
        val ctx = SSLContext.getDefault()

        val matchingEngine = ctx.createSSLEngine("gateway.example.com", 8443)
        assertEquals("first", km.chooseEngineClientAlias(null, null, matchingEngine))

        // Mismatched host
        val mismatchHostEngine = ctx.createSSLEngine("evil.example.com", 8443)
        assertNull("Cross-origin host must not leak client alias", km.chooseEngineClientAlias(null, null, mismatchHostEngine))

        // Mismatched port
        val mismatchPortEngine = ctx.createSSLEngine("gateway.example.com", 9443)
        assertNull("Mismatched port must not leak client alias", km.chooseEngineClientAlias(null, null, mismatchPortEngine))
    }

    @Test
    fun testBlankAliasReturnsNull() {
        val km = MtlsKeyManager("gateway.example.com", 443, "", provider)
        assertNull(km.chooseClientAlias(null, null, null))
        assertNull(km.getPrivateKey(""))
        assertNull(km.getClientAliases(null, null))
    }
}
