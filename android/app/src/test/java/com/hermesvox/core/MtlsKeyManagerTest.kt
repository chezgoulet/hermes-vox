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

        // Null socket fail-closed returns null (never leak alias without verified origin)
        assertNull(km.chooseClientAlias(null, null, null))
        assertNull(km.chooseEngineClientAlias(null, null, null))

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

    /** The guard and the origin key must agree on IPv6 and case, or a certificate is
     *  never sent over an IPv6 gateway. */
    @Test
    fun testChooseEngineClientAliasNormalisesHostSpelling() {
        val ctx = SSLContext.getDefault()
        val v6 = MtlsKeyManager("[2001:db8::1]", 8642, "first", provider)
        assertEquals("first",
            v6.chooseEngineClientAlias(null, null, ctx.createSSLEngine("2001:db8:0:0:0:0:0:1", 8642)))
        assertNull("another address on the same port is another origin",
            v6.chooseEngineClientAlias(null, null, ctx.createSSLEngine("2001:db8::2", 8642)))

        val folded = MtlsKeyManager("Gateway.Local", 443, "first", provider)
        assertEquals("first",
            folded.chooseEngineClientAlias(null, null, ctx.createSSLEngine("gateway.local", 443)))
    }

    /** The socket branch: the alias follows the host the socket actually used, never its
     *  reverse-DNS name and never a stale origin. */
    @Test
    fun testChooseClientAliasComparesTheHostTheSocketUsed() {
        val km = MtlsKeyManager("127.0.0.1", 9443, "first", provider)
        assertEquals("first", km.chooseClientAlias(null, null, socketTo("127.0.0.1", 9443)))
        assertNull("a different host on the same port must not get the alias",
            km.chooseClientAlias(null, null, socketTo("127.0.0.2", 9443)))
        assertNull("the same host on another port must not get the alias",
            km.chooseClientAlias(null, null, socketTo("127.0.0.1", 9444)))

        // An IPv6 literal: the origin keeps no brackets, the socket reports none either.
        val v6 = MtlsKeyManager("[::1]", 9443, "first", provider)
        assertEquals("first", v6.chooseClientAlias(null, null, socketTo("::1", 9443)))
        assertNull(v6.chooseClientAlias(null, null, socketTo("::2", 9443)))
    }

    private fun socketTo(host: String, port: Int): Socket = object : Socket() {
        override fun getRemoteSocketAddress(): java.net.SocketAddress = InetSocketAddress(host, port)
    }

    @Test
    fun testBlankAliasReturnsNull() {
        val km = MtlsKeyManager("gateway.example.com", 443, "", provider)
        assertNull(km.chooseClientAlias(null, null, null))
        assertNull(km.getPrivateKey(""))
        assertNull(km.getClientAliases(null, null))
    }
}
