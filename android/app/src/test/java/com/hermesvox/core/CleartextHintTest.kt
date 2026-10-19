package com.hermesvox.core

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #126: the platform's cleartext refusal must arrive with a way out of it. */
class CleartextHintTest {

    @Test
    fun `android's cleartext refusal is translated into a tailscale instruction`() {
        val hint = Entity.cleartextHint(
            "CLEARTEXT communication to 192.168.1.11 not permitted by network security policy"
        )
        assertTrue("should name the cause", hint!!.contains("plain HTTP"))
        assertTrue("should name tailscale serve", hint.contains("tailscale serve"))
        assertTrue("should name the https endpoint shape", hint.contains(".ts.net"))
    }

    @Test
    fun `any other network error is left alone`() {
        assertNull(Entity.cleartextHint("Connection refused"))
        assertNull(Entity.cleartextHint("timeout after 10s"))
        assertNull(Entity.cleartextHint(null))
    }
}
