package com.hermesvox

import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Locale

/**
 * Tailnet — which address, if any, the agent can reach this phone at (SPEC §B security).
 *
 * The file server must bind to the tailnet address and nothing else. On Android there is no
 * API to ask the Tailscale app "are you up?", so the signal is the same one the routing table
 * uses: a Tailscale tunnel carries an address in the CGNAT range `100.64.0.0/10` (IPv4) or in
 * the Tailscale ULA `fd7a:115c:a1e0::/48` (IPv6). No such address means the agent cannot reach
 * us, so no server is started — "a server reachable from the LAN or the internet is a defect".
 *
 * [pick] is pure: it takes a snapshot of interfaces and addresses and returns the one address
 * the server may bind to, or null. [snapshot] is the JVM edge that produces one; it is the
 * only part that touches the platform, and it needs no Android API.
 *
 * Extra addresses are refused by construction, not by a blocklist: a Wi-Fi address
 * (`192.168.x.x`, `10.x.x.x`) and a cellular address are outside `100.64.0.0/10`, so they are
 * never candidates. Interfaces named like Wi-Fi / radio are excluded as well, so a carrier
 * that hands out CGNAT space on `rmnet0` cannot be mistaken for the tailnet.
 */
object Tailnet {

    /** One interface, as a snapshot sees it. */
    data class Iface(val name: String, val up: Boolean, val addresses: List<InetAddress>)

    /** What the user is told when there is no tailnet address. */
    const val DOWN_LINE =
        "Tailscale is not connected, so the agent could not reach this phone"

    /**
     * The address the one-shot server may bind to, or null when the phone is not on the
     * tailnet. IPv4 is preferred (the spec's URL is IPv4, and it is what a `curl` in a
     * container can reach without AAAA games); the Tailscale IPv6 ULA is the fallback.
     */
    fun pick(ifaces: List<Iface>): InetAddress? {
        val candidates = ifaces
            .filter { it.up && !excludedName(it.name) }
            .flatMap { i -> i.addresses.filter { isTailnet(it) } }
        return candidates.firstOrNull { it is java.net.Inet4Address }
            ?: candidates.firstOrNull()
    }

    /** True only for an address Tailscale itself assigns (CGNAT v4 or the Tailscale ULA). */
    fun isTailnet(a: InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isMulticastAddress) {
            return false
        }
        val b = a.address
        if (a is java.net.Inet4Address && b.size == 4) {
            val first = b[0].toInt() and 0xFF
            val second = b[1].toInt() and 0xFF
            return first == 100 && second in 64..127            // 100.64.0.0/10
        }
        if (b.size == 16) {
            // fd7a:115c:a1e0::/48 — Tailscale's IPv6 ULA (fd + 7a 11 5c a1 e0).
            return (b[0].toInt() and 0xFF) == 0xFD &&
                (b[1].toInt() and 0xFF) == 0x7A &&
                (b[2].toInt() and 0xFF) == 0x11 &&
                (b[3].toInt() and 0xFF) == 0x5C &&
                (b[4].toInt() and 0xFF) == 0xA1 &&
                (b[5].toInt() and 0xFF) == 0xE0
        }
        return false
    }

    /**
     * Names that are never the tailnet. The address test already excludes them; this is the
     * second, independent refusal the spec asks for ("never cellular"), so a future carrier
     * CGNAT assignment on a radio interface still cannot become a bind address.
     */
    private fun excludedName(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        return listOf("wlan", "rmnet", "ccmni", "radio", "eth", "ap", "p2p", "dummy", "sit")
            .any { n == it || n.startsWith(it) }
    }

    /** The live snapshot. Interfaces that cannot be enumerated are skipped, never fatal. */
    fun snapshot(): List<Iface> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp }
            .map { ni ->
                Iface(
                    name = ni.name,
                    up = ni.isUp,
                    addresses = ni.inetAddresses.toList(),
                )
            }
    } catch (_: Throwable) {
        emptyList()
    }

    /** The one call the app makes: the tailnet address now, or null. */
    fun address(ifaces: List<Iface> = snapshot()): InetAddress? = pick(ifaces)
}
