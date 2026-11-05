package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The wiring a pure `PipTest` cannot see: the manifest flags and the Activity's entry
 * points.
 *
 * A manifest that does not declare the feature, or an Activity that never overrides
 * `onPictureInPictureModeChanged`, is a build that compiles and does nothing. The same
 * goes the other way: the device without the feature must take the old path, so the
 * guard in `enterPip` and the `FEATURE_PICTURE_IN_PICTURE` check are asserted, not
 * assumed.
 */
class PipWiringTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    private fun mainActivityElement(): Element {
        val file = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).firstOrNull { it.isFile }
            ?: error("AndroidManifest.xml not found from ${File(".").absolutePath}")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val root = factory.newDocumentBuilder().parse(file).documentElement
        val activities = root.getElementsByTagName("activity")
        for (i in 0 until activities.length) {
            val a = activities.item(i) as Element
            if (a.getAttributeNS(androidNs, "name") == ".MainActivity") return a
        }
        error("no .MainActivity in the manifest")
    }

    private fun mainActivitySource(): String = listOf(
        File("src/main/java/com/hermesvox/MainActivity.kt"),
        File("app/src/main/java/com/hermesvox/MainActivity.kt"),
    ).first { it.isFile }.readText()

    @Test
    fun the_activity_declares_pip_and_survives_the_window_changing_shape() {
        val a = mainActivityElement()
        assertEquals("true", a.getAttributeNS(androidNs, "supportsPictureInPicture"))
        val changes = a.getAttributeNS(androidNs, "configChanges").split('|').toSet()
        // Without all four the Activity is recreated when the window shrinks: the call
        // survives in the service, but the turn's surface, the crawl and the being reset.
        assertTrue("configChanges=$changes", changes.containsAll(
            setOf("screenSize", "smallestScreenSize", "screenLayout", "orientation")
        ))
    }

    @Test
    fun a_device_without_the_feature_takes_the_old_path() {
        val src = mainActivitySource()
        // The guard asks the platform, not just the API level.
        assertTrue(src.contains("PackageManager.FEATURE_PICTURE_IN_PICTURE"))
        assertTrue(src.contains("Pip.supported("))
        // Every entry point refuses before touching a PiP API.
        assertTrue(src.contains("if (!callLive || !pipSupported() || Build.VERSION.SDK_INT < Pip.MIN_API) return false"))
        // The 26–30 fallback and the mode switch exist.
        assertTrue(src.contains("override fun onUserLeaveHint()"))
        assertTrue(src.contains("override fun onPictureInPictureModeChanged("))
        // Not one new permission: picture-in-picture is a window flag, not a grant.
        val manifest = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).first { it.isFile }.readText()
        assertFalse(manifest.contains("PICTURE_IN_PICTURE_PERMISSION"))
    }

    @Test
    fun the_launch_path_goes_through_pip_first_and_stop_reuses_the_one_teardown() {
        val src = mainActivitySource()
        // The funnel, not five ad-hoc startActivity calls.
        assertTrue(src.contains("CallWindow.launch(pipHost, start)"))
        // Stop ends the call through endCall(), the same path the hang-up button uses;
        // no second teardown lives in the receiver.
        val receiver = src.substringAfter("private val pipReceiver = object : BroadcastReceiver()")
            .substringBefore("\n    }\n")
        assertTrue(receiver.contains("endCall()"))
        assertFalse("a second teardown", receiver.contains("liveController?.stop()"))
        assertFalse(receiver.contains("VoiceService.stop("))
    }

    @Test
    fun ending_the_call_from_pip_closes_the_window() {
        val src = mainActivitySource()
        val endCall = src.substringAfter("private fun endCall()")
            .substringBefore("override fun onRequestPermissionsResult")
        // A dial handoff ends the call from inside PiP; an idle app must never float.
        assertTrue("the teardown notices PiP", endCall.contains("isInPictureInPictureMode"))
        assertTrue("and closes the window", endCall.contains("finish()"))
    }
}
