package com.hermesvox

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * PhoneMcpToken — the bearer credential the gateway presents to the phone's MCP server (#421 step 3).
 *
 * One token, generated once, 128 bits of randomness, stored encrypted at rest ([SecureStore], Android
 * Keystore) and never logged. It is *not* the Hermes `API_SERVER_KEY` and it is not derived from
 * anything else: revoking the phone's access is replacing this value, which the gateway then fails to
 * authenticate against until its config is updated.
 *
 * Comparison is constant-time. A bearer check that returns early on the first wrong byte leaks the
 * prefix of a secret to anyone who can reach the port, and the port is on a tailnet.
 *
 * Pure JVM, unit-tested in [PhoneMcpTokenTest].
 */
object PhoneMcpToken {

    /** 128 bits, like the file-offer capability. */
    const val BITS = 128

    /** 32 lowercase hex characters. */
    const val LENGTH = 32

    /** A fresh token: [BITS] random bits, hex. */
    fun generate(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(BITS / 8)
        random.nextBytes(bytes)
        val sb = StringBuilder(LENGTH)
        for (b in bytes) sb.append("%02x".format(b.toInt() and 0xFF))
        return sb.toString()
    }

    /**
     * Constant-time comparison. False for an empty presented value, an empty expected value, or any
     * difference — including a length difference, which is compared without early return.
     */
    fun matches(presented: String, expected: String): Boolean {
        if (presented.isEmpty() || expected.isEmpty()) return false
        val a = presented.toByteArray(Charsets.UTF_8)
        val b = expected.toByteArray(Charsets.UTF_8)
        // Fold the length difference into the result instead of returning early.
        var diff = a.size xor b.size
        val n = minOf(a.size, b.size)
        for (i in 0 until n) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0 && MessageDigest.isEqual(a, b)
    }
}
