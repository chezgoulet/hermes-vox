package com.hermesvox.core

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class MtlsConfigTest {

    @Test
    fun testOriginOfParsing() {
        // Standard HTTPS default port 443
        val o1 = MtlsConfig.originOf("https://hermes.example.com")
        assertNotNull(o1)
        assertEquals("hermes.example.com", o1!!.host)
        assertEquals(443, o1.port)
        assertEquals("hermes.example.com:443", o1.key)

        // Custom port and paths
        val o2 = MtlsConfig.originOf("https://gateway.local:8642/v1/responses?key=val")
        assertNotNull(o2)
        assertEquals("gateway.local", o2!!.host)
        assertEquals(8642, o2.port)
        assertEquals("gateway.local:8642", o2.key)

        // Case insensitivity
        val o3 = MtlsConfig.originOf("HTTPS://MyHost.Domain.COM:443/")
        assertNotNull(o3)
        assertEquals("myhost.domain.com", o3!!.host)
        assertEquals(443, o3.port)

        // Plain HTTP must return null (mTLS strictly HTTPS only)
        assertNull(MtlsConfig.originOf("http://hermes.example.com:8642"))
        assertNull(MtlsConfig.originOf("http://localhost:8080"))

        // Blank and malformed
        assertNull(MtlsConfig.originOf(""))
        assertNull(MtlsConfig.originOf("   "))
        assertNull(MtlsConfig.originOf("not a url"))
    }

    /**
     * The origin key is what a certificate is filed under and what the runtime guard
     * compares against. Two spellings of one origin must be one key, or a certificate
     * chosen under one spelling is never sent under the other — silently.
     */
    @Test
    fun testOriginOfNormalisesIpv6Literals() {
        val bare = MtlsConfig.originOf("https://[2001:db8::1]:8642/v1/models")
        assertNotNull("an IPv6 literal origin must parse", bare)
        // No brackets in the host: this is what a socket reports and what the guard compares.
        assertEquals("2001:db8::1", bare!!.host)
        // The preference key keeps brackets, so it can never be read as host+port twice.
        assertEquals("[2001:db8::1]:8642", bare.key)

        // Every spelling of the same address is the same origin.
        assertEquals(bare, MtlsConfig.originOf("https://[2001:DB8:0:0:0:0:0:1]:8642"))
        assertEquals(bare, MtlsConfig.originOf("https://[2001:db8::1]:8642/"))

        val loopback = MtlsConfig.originOf("https://[::1]:8642/")
        assertNotNull(loopback)
        assertEquals("::1", loopback!!.host)
        assertEquals(loopback, MtlsConfig.originOf("https://[0:0:0:0:0:0:0:1]:8642"))
    }

    /** `URI.host` is null for a non-ASCII host (and `URL.host` is on Android), so an IDN
     *  gateway used to yield no origin at all — and therefore no certificate. */
    @Test
    fun testOriginOfNormalisesIdnToAscii() {
        val unicode = MtlsConfig.originOf("https://münchen.example:8642/v1/models")
        assertNotNull("an IDN origin must parse", unicode)
        assertEquals("xn--mnchen-3ya.example", unicode!!.host)
        // The punycode spelling is the same origin, so a certificate chosen under
        // either spelling is found under both.
        assertEquals(unicode, MtlsConfig.originOf("https://XN--MNCHEN-3YA.example:8642/"))
    }

    @Test
    fun testOriginOfRejectsAmbiguousAuthority() {
        // A non-numeric port must not be read as part of the host.
        assertNull(MtlsConfig.originOf("https://host:abc/"))
        // An unbracketed IPv6 literal is not a valid authority.
        assertNull(MtlsConfig.originOf("https://2001:db8::1:8642/"))
        // A port outside 1..65535 is not an origin.
        assertNull(MtlsConfig.originOf("https://host:70000/"))
        // Userinfo is dropped, not mistaken for the host.
        assertEquals("gateway.local", MtlsConfig.originOf("https://user:pw@gateway.local:8642/")!!.host)
        // The root-dot spelling is the same origin.
        assertEquals(MtlsConfig.originOf("https://gateway.local/"),
            MtlsConfig.originOf("https://gateway.local./"))
    }

    @Test
    fun testOriginAndPeerUseTheSameSpelling() {
        // The origin key (from a URL) and the peer (from a socket) are normalised by the
        // same function, so the guard cannot refuse a certificate over spelling.
        assertEquals(MtlsConfig.normaliseHost("[2001:DB8::1]"), MtlsConfig.normaliseHost("2001:db8::1"))
        assertEquals(MtlsConfig.normaliseHost("MÜNCHEN.example"), "xn--mnchen-3ya.example")
        assertEquals(MtlsConfig.normaliseHost("Gateway.Local."), "gateway.local")
        assertNull(MtlsConfig.normaliseHost(""))
        assertNull(MtlsConfig.normaliseHost("ho st"))
    }

    /**
     * The origin-binding rule. Editing the address must never make the old host's
     * certificate the new host's credential; only an explicit choice on an origin may
     * write an alias to it.
     */
    @Test
    fun testSaveAliasNeverInheritsAnotherOriginsCertificate() {
        val a = MtlsConfig.Origin("gateway-a.example", 443)
        val b = MtlsConfig.Origin("gateway-b.example", 443)

        // Unchanged address: the stored certificate stays as it is.
        assertEquals(MtlsConfig.SaveAlias.Write("first"),
            MtlsConfig.saveAlias(a, a, "first", a))
        // Cleared by the user on that origin: removed.
        assertEquals(MtlsConfig.SaveAlias.Write(null), MtlsConfig.saveAlias(a, a, null, a))
        // Address changed, certificate not chosen again: nothing is written to B, and
        // anything B held is cleared rather than silently used.
        assertEquals(MtlsConfig.SaveAlias.Write(null), MtlsConfig.saveAlias(a, a, "first", b))
        // Address changed AND the user chose a certificate for B: that explicit act wins.
        assertEquals(MtlsConfig.SaveAlias.Write("second"), MtlsConfig.saveAlias(a, b, "second", b))
        // Changed to B, chose for B, then changed back to A: A is untouched (and the
        // choice made for B is not written to A).
        assertEquals(MtlsConfig.SaveAlias.Leave, MtlsConfig.saveAlias(a, b, "second", a))
        // A blank alias is an absent one, never a stored empty string.
        assertEquals(MtlsConfig.SaveAlias.Write(null), MtlsConfig.saveAlias(a, a, "  ", a))
    }

    @Test
    fun testSocketFactoryCreationAndCaching() {
        val provider = TestKeyProvider()
        val origin = MtlsConfig.Origin("gateway.example.com", 8443)

        val sf1 = MtlsConfig.getSocketFactory(origin, "first", provider)
        assertNotNull("Socket factory should be created with provider", sf1)

        val sf2 = MtlsConfig.getSocketFactory(origin, "first", provider)
        assertEquals("Socket factory should be cached for same origin and alias", sf1, sf2)

        // Invalidate cache
        MtlsConfig.invalidateCache()
        val sf3 = MtlsConfig.getSocketFactory(origin, "first", provider)
        assertNotNull(sf3)
    }
}
