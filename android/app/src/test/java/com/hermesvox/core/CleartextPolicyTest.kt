package com.hermesvox.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M4 (0.9.1 review): cleartext is trusted only while the tailnet is up.
 *
 * MagicDNS is private by construction only when the tunnel is. With Tailscale down a
 * hostile network can answer `*.ts.net` and collect the bearer key from the first request,
 * so the app refuses plain HTTP for every off-device host until [CleartextPolicy.tunnelUp]
 * says the tunnel exists. HTTPS and loopback are untouched.
 */
class CleartextPolicyTest {

    @Test fun httpsIsNeverRefused() {
        assertNull(CleartextPolicy.refusal("https://sasquatch.leopard-vibes.ts.net", tunnelUp = false))
        assertNull(CleartextPolicy.refusal("https://192.168.1.10:8642", tunnelUp = false))
    }

    @Test fun tailnetCleartextIsRefusedWhenTheTunnelIsDown() {
        val refusal = CleartextPolicy.refusal("http://sasquatch.leopard-vibes.ts.net:8642", tunnelUp = false)
        assertEquals(CleartextPolicy.REFUSAL, refusal)
        assertTrue("the line names the cause", refusal!!.contains("Tailscale"))
        assertTrue("the line names the way out", refusal.contains("https://"))
        assertFalse("the line never carries a credential", refusal.contains("Bearer"))
    }

    @Test fun tailnetCleartextIsAllowedWhenTheTunnelIsUp() {
        assertNull(CleartextPolicy.refusal("http://sasquatch.leopard-vibes.ts.net:8642", tunnelUp = true))
    }

    @Test fun aLanCleartextEndpointAsksTheSameQuestion() {
        assertEquals(CleartextPolicy.REFUSAL, CleartextPolicy.refusal("http://192.168.1.10:8642", tunnelUp = false))
        assertNull(CleartextPolicy.refusal("http://192.168.1.10:8642", tunnelUp = true))
    }

    @Test fun loopbackNeedsNoTunnel() {
        for (u in listOf(
            "http://127.0.0.1:8642", "http://127.4.5.6/", "http://localhost:8642",
            "http://[::1]:8642", "http://0.0.0.0:8642",
        )) {
            assertNull(u, CleartextPolicy.refusal(u, tunnelUp = false))
        }
    }

    @Test fun loopbackDetectionDoesNotCatchLookalikes() {
        assertFalse(CleartextPolicy.isLoopback("http://127.0.0.1.example.com"))
        assertFalse(CleartextPolicy.isLoopback("http://10.0.0.1"))
        assertFalse(CleartextPolicy.isLoopback("not a url"))
    }

    @Test fun blankAndGarbageAreNotDeletedAsLoopback() {
        assertEquals(CleartextPolicy.REFUSAL, CleartextPolicy.refusal("http://", tunnelUp = false))
        assertNull(CleartextPolicy.refusal("", tunnelUp = false))
    }

    @Test fun theDefaultFailsClosed() {
        val old = CleartextPolicy.tunnelUp
        try {
            CleartextPolicy.tunnelUp = { false }
            assertEquals(CleartextPolicy.REFUSAL, CleartextPolicy.refusal("http://host.ts.net"))
            CleartextPolicy.tunnelUp = { true }
            assertNull(CleartextPolicy.refusal("http://host.ts.net"))
        } finally {
            CleartextPolicy.tunnelUp = old
        }
    }
}
