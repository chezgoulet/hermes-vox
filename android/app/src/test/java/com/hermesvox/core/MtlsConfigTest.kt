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
