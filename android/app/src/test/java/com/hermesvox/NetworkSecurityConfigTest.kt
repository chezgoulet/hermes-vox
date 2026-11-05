package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * M4 (0.9.1 review): the user trust anchor belongs to the tailnet, not to every host.
 *
 * A user-installed CA trusted at the base level can mint a certificate for any gateway
 * (a work proxy, a test root, a hostile profile) and read the bearer key. The anchor is
 * scoped to `ts.net`, where the tailnet CA is what it is for. The base config keeps the
 * system anchors and refuses cleartext.
 */
class NetworkSecurityConfigTest {

    private fun xml(): Element {
        val f = listOf(File("src/main/res/xml/network_security_config.xml"),
            File("app/src/main/res/xml/network_security_config.xml")).firstOrNull { it.isFile }
            ?: error("network_security_config.xml not found from ${File(".").absolutePath}")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(f).documentElement
    }

    private fun child(e: Element, tag: String): Element? =
        (0 until e.childNodes.length).map { e.childNodes.item(it) }
            .filterIsInstance<Element>().firstOrNull { it.tagName == tag }

    private fun anchors(config: Element): Set<String> =
        child(config, "trust-anchors")?.let { a ->
            (0 until a.childNodes.length).map { a.childNodes.item(it) }
                .filterIsInstance<Element>().filter { it.tagName == "certificates" }
                .map { it.getAttribute("src") }.toSet()
        } ?: emptySet()

    private fun domainConfig(domain: String): Element? =
        (0 until xml().childNodes.length).map { xml().childNodes.item(it) }
            .filterIsInstance<Element>().filter { it.tagName == "domain-config" }
            .firstOrNull { c ->
                (0 until c.childNodes.length).map { c.childNodes.item(it) }
                    .filterIsInstance<Element>().any { d -> d.tagName == "domain" && d.textContent == domain }
            }

    @Test fun theBaseConfigRefusesCleartextAndTrustsOnlyTheSystem() {
        val base = child(xml(), "base-config") ?: error("no base-config")
        assertEquals("false", base.getAttribute("cleartextTrafficPermitted"))
        assertEquals(setOf("system"), anchors(base))
    }

    @Test fun theTailnetKeepsItsCleartextAndItsAnchor() {
        val ts = domainConfig("ts.net") ?: error("no ts.net domain-config")
        assertEquals("true", ts.getAttribute("cleartextTrafficPermitted"))
        assertEquals(setOf("system", "user"), anchors(ts))
        val domain = (0 until ts.childNodes.length).map { ts.childNodes.item(it) }
            .filterIsInstance<Element>().first { it.tagName == "domain" }
        assertEquals("true", domain.getAttribute("includeSubdomains"))
    }

    @Test fun noOtherHostMayBeCleartext() {
        val root = xml()
        val configs = (0 until root.childNodes.length).map { root.childNodes.item(it) }
            .filterIsInstance<Element>().filter { it.tagName == "domain-config" }
        for (c in configs) {
            val domains = (0 until c.childNodes.length).map { c.childNodes.item(it) }
                .filterIsInstance<Element>().filter { it.tagName == "domain" }.map { it.textContent }
            assertEquals("the only cleartext exception is ts.net", listOf("ts.net"), domains)
        }
    }

    @Test fun thePolicyAndTheXmlAgreeThatOnlyTheTailnetIsSpecial() {
        val root = xml()
        assertNull("there is no user anchor at the base level", child(root, "base-config")?.let {
            if ("user" in anchors(it)) "user" else null
        })
        assertTrue("ts.net is the exception", domainConfig("ts.net") != null)
    }
}
