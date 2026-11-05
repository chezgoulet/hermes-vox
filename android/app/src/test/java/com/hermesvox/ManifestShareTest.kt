package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * #370: the share surface is only a share surface if Android can find it, and "no new
 * permissions" is a promise a test can keep.
 *
 * The manifest is the source of truth here, not a constant that could drift from it. The
 * declared mime set is asserted exactly, so a narrowed or widened filter is a deliberate
 * change with a failing test rather than an accident.
 */
class ManifestShareTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"

    private data class Filter(
        val actions: Set<String>,
        val categories: Set<String>,
        val schemes: Set<String>,
        val mimes: Set<String>,
    )

    private fun manifest(): Element {
        val file = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        ).firstOrNull { it.isFile }
            ?: error("AndroidManifest.xml not found from ${File(".").absolutePath}")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(file).documentElement
    }

    private fun attr(e: Element, name: String): String = e.getAttributeNS(androidNs, name)

    private fun children(e: Element, tag: String): List<Element> =
        (0 until e.childNodes.length).map { e.childNodes.item(it) }
            .filterIsInstance<Element>()
            .filter { it.tagName == tag }

    private fun filtersOf(activity: Element): List<Filter> = children(activity, "intent-filter").map { f ->
        Filter(
            actions = children(f, "action").map { attr(it, "name") }.toSet(),
            categories = children(f, "category").map { attr(it, "name") }.toSet(),
            schemes = children(f, "data").mapNotNull { attr(it, "scheme").takeIf(String::isNotBlank) }.toSet(),
            mimes = children(f, "data").mapNotNull { attr(it, "mimeType").takeIf(String::isNotBlank) }.toSet(),
        )
    }

    private fun mainActivity(): Element {
        val activities = manifest().getElementsByTagName("activity")
        for (i in 0 until activities.length) {
            val a = activities.item(i) as Element
            if (attr(a, "name") == ".MainActivity") return a
        }
        error("no .MainActivity in the manifest")
    }

    @Test fun voxRegistersSendSendMultipleAndView() {
        val filters = filtersOf(mainActivity())
        val share = filters.filter { f ->
            f.actions.any {
                it == "android.intent.action.SEND" || it == "android.intent.action.SEND_MULTIPLE"
            }
        }
        assertEquals(1, share.size)
        assertEquals(
            setOf("android.intent.action.SEND", "android.intent.action.SEND_MULTIPLE"),
            share.single().actions,
        )
        assertTrue("a share target needs DEFAULT", share.single().categories.contains("android.intent.category.DEFAULT"))
        assertEquals("every shared type", setOf("*/*"), share.single().mimes)

        val views = filters.filter { "android.intent.action.VIEW" in it.actions }
        assertEquals("one VIEW filter per scheme", 2, views.size)
        assertEquals(setOf("content", "file"), views.flatMap { it.schemes }.toSet())
        for (v in views) {
            assertTrue("a VIEW target needs DEFAULT", v.categories.contains("android.intent.category.DEFAULT"))
            assertEquals("every viewed type", setOf("*/*"), v.mimes)
        }
        // Vox is not a browser: no http(s) VIEW filter.
        assertFalse(views.any { it.schemes.any { s -> s == "http" || s == "https" } })
    }

    @Test fun theDeclaredMimeSetForEveryShareFilterIsExactlyEverything() {
        val share = filtersOf(mainActivity()).filter { f ->
            f.actions.any {
                it == "android.intent.action.SEND" ||
                    it == "android.intent.action.SEND_MULTIPLE" ||
                    it == "android.intent.action.VIEW"
            }
        }
        assertEquals(setOf("*/*"), share.flatMap { it.mimes }.toSet())
        // The filters must declare a type at all: a SEND filter with no data element would
        // only accept the exact mime the sender names in an explicit intent, not the share
        // sheet's list.
        assertTrue(share.all { it.mimes.isNotEmpty() })
    }

    @Test fun sharingAddedNoPermission() {
        val declared = manifest().getElementsByTagName("uses-permission")
        val names = (0 until declared.length)
            .map { attr(declared.item(it) as Element, "name") }
            .toSet()
        assertEquals(
            setOf(
                "android.permission.INTERNET",
                "android.permission.RECORD_AUDIO",
                "android.permission.MODIFY_AUDIO_SETTINGS",
                "android.permission.VIBRATE",
                "android.permission.WAKE_LOCK",
                "android.permission.ACCESS_NETWORK_STATE",
                "android.permission.POST_NOTIFICATIONS",
                // #325: Do Not Disturb during a call — not for sharing; granted by the user in
                // system settings only when they switch the setting on.
                "android.permission.ACCESS_NOTIFICATION_POLICY",
                // #429 lane 2: the calendar group's runtime permissions. Files add none: the
                // owner grants one folder through the system picker.
                "android.permission.READ_CALENDAR",
                "android.permission.WRITE_CALENDAR",
                // #429 read lane: the contacts group's runtime permission, asked when Contacts is
                // turned on. Notification reading adds no permission: it is the listener special
                // access, shared with Media, not POST_NOTIFICATIONS.
                "android.permission.READ_CONTACTS",
                // #apps-and-dismiss: the apps listing's running half. A special access granted on
                // Android's own Usage access screen; declaring it is what makes Vox appear there.
                // Not QUERY_ALL_PACKAGES, which is never requested.
                "android.permission.PACKAGE_USAGE_STATS",
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_MICROPHONE",
                "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
            ),
            names,
        )
        // A share target reads what the sender grants it; it never asks for storage.
        for (denied in listOf(
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.MANAGE_EXTERNAL_STORAGE",
            "android.permission.QUERY_ALL_PACKAGES",
        )) {
            assertFalse(denied, names.contains(denied))
        }
    }
}
