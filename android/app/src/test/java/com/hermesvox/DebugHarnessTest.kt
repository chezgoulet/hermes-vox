package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * M3 (0.9.1 review): the debug harness is reachable only through a component another app
 * cannot start.
 *
 * `MainActivity` is exported for the launcher, the share targets and the assistant role, so a
 * harness that lives there is a harness any co-installed app can drive. The harness therefore
 * lives in the debug source set, in its own activity, behind `android.permission.DUMP`.
 */
class DebugHarnessTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun src(path: String): File = File(path).takeIf { it.isFile }
        ?: File("app/$path").takeIf { it.isFile }
        ?: error("not found from ${File(".").absolutePath}: $path")

    private fun manifest(path: String): Element {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(src(path)).documentElement
    }

    private fun attr(e: Element, name: String): String = e.getAttributeNS(androidNs, name)

    private fun activityNamed(root: Element, name: String): Element? {
        val all = root.getElementsByTagName("activity")
        for (i in 0 until all.length) {
            val a = all.item(i) as Element
            if (attr(a, "name") == name || attr(a, "name") == "com.hermesvox$name") return a
        }
        return null
    }

    @Test
    fun `release main manifest does not declare the harness`() {
        assertEquals(null, activityNamed(manifest("src/main/AndroidManifest.xml"), ".DebugHarnessActivity"))
    }

    @Test
    fun `the debug manifest puts the harness behind DUMP`() {
        val a = activityNamed(manifest("src/debug/AndroidManifest.xml"), ".DebugHarnessActivity")
            ?: error("the debug manifest must declare .DebugHarnessActivity")
        assertEquals("true", attr(a, "exported"))
        assertEquals("android.permission.DUMP", attr(a, "permission"))
    }

    @Test
    fun `MainActivity no longer reads any harness extra`() {
        val main = src("src/main/java/com/hermesvox/MainActivity.kt").readText()
        assertFalse("a harness in the launcher activity is reachable by any app",
            main.contains("handleDebugHarness"))
        for (extra in listOf("arm", "clipboard", "offer_file", "ringer", "cue", "being", "sheet", "settings")) {
            assertFalse("MainActivity still reads the `$extra` harness extra",
                main.contains("getStringExtra(\"$extra\")") || main.contains("getBooleanExtra(\"$extra\""))
        }
    }

    @Test
    fun `the harness is the call screen, in the debug source set`() {
        val debug = src("src/debug/java/com/hermesvox/DebugHarnessActivity.kt").readText()
        assertTrue("it reuses MainActivity's views and session", debug.contains("class DebugHarnessActivity : MainActivity()"))
        assertTrue("a turn uses the same send() the UI uses", debug.contains("send(text)"))
        assertTrue("the file path stages through the same card", debug.contains("stageDraft("))
    }
}
