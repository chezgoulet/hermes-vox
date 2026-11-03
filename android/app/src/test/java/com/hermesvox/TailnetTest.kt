package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * The bind rule: only a tailnet address may become the file server's address.
 *
 * `pick` is pure — a snapshot of interfaces in, one address out — so the whole "never 0.0.0.0,
 * never a LAN address, never cellular" rule is proven here. What a real phone's interfaces
 * look like is the device's business; that this code only ever hands `FileServer.start` the
 * result of `pick` is visible in `FileShare`.
 */
class TailnetTest {

    private fun ip(s: String): InetAddress = InetAddress.getByName(s)

    private fun iface(name: String, vararg addrs: String, up: Boolean = true) =
        Tailnet.Iface(name, up, addrs.map { ip(it) })

    @Test fun theTailscaleAddressIsPicked() {
        val a = Tailnet.pick(
            listOf(
                iface("wlan0", "192.168.1.42"),
                iface("rmnet0", "10.31.4.9"),
                iface("tun0", "100.82.156.1"),
            ),
        )
        assertEquals("100.82.156.1", a?.hostAddress)
    }

    @Test fun wifiAndCellularAreNeverCandidates() {
        val lan = listOf("192.168.1.42", "10.0.0.7", "172.16.9.2", "169.254.1.1", "127.0.0.1")
        for (a in lan) {
            assertNull("$a must never be a bind address", Tailnet.pick(listOf(iface("wlan0", a))))
            assertNull(a, Tailnet.pick(listOf(iface("rmnet0", a))))
        }
    }

    @Test fun aCgnatAddressOnACellularInterfaceIsStillRefused() {
        // Some carriers hand out 100.64/10 space. It is not the tailnet, and the radio
        // interface name excludes it independently of the address test.
        assertNull(Tailnet.pick(listOf(iface("rmnet0", "100.70.1.2"))))
        assertNull(Tailnet.pick(listOf(iface("ccmni0", "100.70.1.2"))))
        assertNull(Tailnet.pick(listOf(iface("wlan0", "100.70.1.2"))))
        assertNull(Tailnet.pick(listOf(iface("radio0", "100.70.1.2"))))
    }

    @Test fun ipv4IsPreferredOverTheTailscaleIpv6Ula() {
        val a = Tailnet.pick(
            listOf(iface("tun0", "fd7a:115c:a1e0::1", "100.64.0.9")),
        )
        assertEquals("100.64.0.9", a?.hostAddress)
    }

    @Test fun theIpv6UlaIsUsedWhenItIsAllThereIs() {
        val a = Tailnet.pick(listOf(iface("tun0", "fd7a:115c:a1e0:ab12::1")))
        assertEquals("fd7a:115c:a1e0:ab12:0:0:0:1", a?.hostAddress?.lowercase())
    }

    @Test fun aNonTailscaleIpv6IsNotUsed() {
        assertNull(Tailnet.pick(listOf(iface("wlan0", "2001:db8::1"))))
        assertNull(Tailnet.pick(listOf(iface("tun0", "fe80::1"))))
        assertNull(Tailnet.pick(listOf(iface("tun0", "::1"))))
    }

    @Test fun aDownInterfaceIsIgnored() {
        assertNull(Tailnet.pick(listOf(iface("tun0", "100.82.156.1", up = false))))
    }

    @Test fun noTailnetAddressMeansDown() {
        assertNull(Tailnet.pick(emptyList()))
        assertNull(Tailnet.pick(listOf(iface("wlan0", "192.168.1.2"), iface("rmnet0", "10.1.2.3"))))
    }

    @Test fun theCgnatRangeIsExactly100_64To100_127() {
        assertTrue(Tailnet.isTailnet(ip("100.64.0.0")))
        assertTrue(Tailnet.isTailnet(ip("100.127.255.255")))
        assertFalse(Tailnet.isTailnet(ip("100.63.255.255")))
        assertFalse(Tailnet.isTailnet(ip("100.128.0.0")))
        assertFalse(Tailnet.isTailnet(ip("0.0.0.0")))
    }
}
