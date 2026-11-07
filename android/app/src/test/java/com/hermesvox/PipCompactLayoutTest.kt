package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The owner's decision, pinned: **the compact view is the being and nothing else.**
 *
 * A line of speech at ~240 dp is unreadable and the being *is* the presence, so the PiP
 * layout may hold one thing — [AvatarView] — and no text, no crawl, no buttons, no title,
 * no caption, no "tap to return" label. The system draws the window chrome, and the two
 * controls (Stop, and Mute when the controller has one) are `RemoteAction`s in that
 * chrome, never views here.
 *
 * This test exists so a later change cannot quietly add a caption "just for the call
 * state": adding one fails here first.
 */
class PipCompactLayoutTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun layout(): Element {
        val file = listOf(
            File("src/main/res/layout/activity_pip.xml"),
            File("app/src/main/res/layout/activity_pip.xml"),
        ).firstOrNull { it.isFile }
            ?: error("activity_pip.xml not found from ${File(".").absolutePath}")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    private fun attr(e: Element, name: String): String = e.getAttributeNS(androidNs, name)

    private fun elements(root: Element): List<Element> {
        val out = mutableListOf(root)
        for (i in 0 until root.childNodes.length) {
            val child = root.childNodes.item(i)
            if (child is Element) out += elements(child)
        }
        return out
    }

    @Test
    fun the_compact_surface_contains_the_being() {
        val root = layout()
        val kids = elements(root).filter { it !== root }
        assertEquals("exactly one child: the being", 1, kids.size)
        assertEquals("com.hermesvox.AvatarView", kids.single().tagName)
        assertEquals("@+id/pip_avatar", attr(kids.single(), "id"))
    }

    @Test
    fun the_compact_surface_contains_no_text_view() {
        val tags = elements(layout()).map { it.tagName }
        assertFalse("the window draws no words: $tags", tags.any { it == "TextView" })
        assertFalse(tags.any { it == "EditText" })
        assertFalse(tags.any { it == "Button" })
        assertFalse(tags.any { it == "ImageButton" })
        assertFalse(tags.any { it == "ImageView" })
        // The crawl is how this app draws speech; it has no place in the window.
        assertFalse(tags.any { it == "com.hermesvox.CrawlView" })
        assertFalse(tags.any { it == "CrawlView" })
        // No caption attribute smuggled onto the being either.
        assertTrue(elements(layout()).none { attr(it, "text").isNotBlank() })
    }

    @Test
    fun the_compact_surface_is_decorative_and_fills_the_window() {
        val root = layout()
        assertEquals("@+id/pip_surface", attr(root, "id"))
        // Nothing to read: a screen reader must not announce an empty being.
        assertEquals("noHideDescendants", attr(root, "importantForAccessibility"))
        assertEquals("match_parent", attr(root, "layout_width"))
        assertEquals("match_parent", attr(root, "layout_height"))
        // The ground the window paints is the app's own, so PipGlyph's ink is the pair
        // LightModeContrastTest already holds to 4.5:1.
        assertEquals("@color/hv_bg", attr(root, "background"))
    }
}
