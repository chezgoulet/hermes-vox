package com.hermesvox

import android.app.AppOpsManager
import android.content.Intent
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #apps-and-dismiss: `apps` lists what a person can open, and says honestly whether it can see
 * what is running.
 *
 * The rules this file exists to keep:
 *
 *  * installed means **launchable**, sorted by label, capped at 200 with `truncated` and a total;
 *  * the query is the launcher intent, declared in the manifest's `<queries>` — no
 *    `QUERY_ALL_PACKAGES`;
 *  * usage access is the system's AppOps answer, not a stored flag;
 *  * without it the reply says it **cannot see what is running** and names the setting; it never
 *    returns an empty list that reads as "nothing is running";
 *  * with it, the foreground app and a short recent list are reported.
 */
class AppsAccessTest {

    private class FakeOps(private val mode: Int) : AppsAccess.AppOps {
        val calls = ArrayList<Triple<String, Int, String>>()
        override fun mode(op: String, uid: Int, packageName: String): Int {
            calls.add(Triple(op, uid, packageName))
            return mode
        }
    }

    private class FakeHost(
        private val apps: List<AppsAccess.App>?,
        private val access: Boolean = false,
        private val foreground: AppsAccess.App? = null,
        private val recent: List<AppsAccess.App> = emptyList(),
    ) : AppsAccess.Host {
        var askedFor = 0
        override fun launchableApps(): List<AppsAccess.App>? = apps
        override fun usageAccess(): Boolean = access
        override fun foregroundApp(): AppsAccess.App? = foreground
        override fun recentApps(limit: Int): List<AppsAccess.App> {
            askedFor = limit
            return recent
        }
    }

    private fun app(pkg: String, label: String = pkg) = AppsAccess.App(pkg, label)

    // ---- installed ----

    @Test fun installed_is_sorted_by_label_case_insensitively_and_keeps_packages() {
        val host = FakeHost(
            listOf(
                app("com.zeta", "zeta"),
                app("com.alpha", "Alpha"),
                app("com.beta", "beta"),
            ),
        )

        val installed = AppsAccess.installed(host)

        assertEquals(listOf("Alpha", "beta", "zeta"), installed.apps.map { it.label })
        assertEquals(
            listOf("com.alpha", "com.beta", "com.zeta"),
            installed.apps.map { it.packageName },
        )
        assertEquals(3, installed.total)
        assertFalse(installed.truncated)
    }

    @Test fun installed_is_capped_at_200_and_still_reports_the_whole_total() {
        val many = (1..250).map { i -> app("com.example.$i", "App %03d".format(i)) }
        val host = FakeHost(many)

        val installed = AppsAccess.installed(host)

        assertEquals(AppsAccess.MAX_APPS, installed.apps.size)
        assertEquals(200, installed.apps.size)
        assertEquals(250, installed.total)
        assertTrue(installed.truncated)
        assertEquals("App 001", installed.apps.first().label)
        assertEquals("App 200", installed.apps.last().label)
        assertFalse("App 201 is past the cap", installed.apps.any { it.label == "App 201" })
    }

    @Test fun exactly_two_hundred_is_not_truncated() {
        val host = FakeHost((1..200).map { app("com.example.$it", "App $it") })
        val installed = AppsAccess.installed(host)
        assertEquals(200, installed.apps.size)
        assertEquals(200, installed.total)
        assertFalse(installed.truncated)
    }

    @Test fun a_blank_label_falls_back_to_the_package_and_duplicates_are_dropped() {
        val host = FakeHost(
            listOf(
                app("com.one", "   "),
                app("com.one", "One"),
                app("com.two", "Two"),
            ),
        )
        val installed = AppsAccess.installed(host)
        assertEquals(2, installed.total)
        assertEquals(listOf("com.one", "Two"), installed.apps.map { it.label })
    }

    @Test fun no_launchable_apps_is_an_honest_empty_list_not_a_failure() {
        val installed = AppsAccess.installed(FakeHost(emptyList()))
        assertEquals(emptyList<AppsAccess.App>(), installed.apps)
        assertEquals(0, installed.total)
        assertFalse(installed.truncated)
    }

    // ---- package visibility ----

    @Test fun the_query_is_the_launcher_intent_and_the_manifest_declares_it() {
        assertEquals(Intent.ACTION_MAIN, AppsAccess.launcherQuery().action)
        assertEquals(Intent.CATEGORY_LAUNCHER, AppsAccess.launcherQuery().category)
        assertEquals("android.intent.action.MAIN", AppsAccess.LAUNCH_ACTION)
        assertEquals("android.intent.category.LAUNCHER", AppsAccess.LAUNCH_CATEGORY)

        val manifest = File("src/main/AndroidManifest.xml").readText()
        val queries = manifest.substringAfter("<queries>").substringBefore("</queries>")
        assertTrue(
            "the launcher action is visible to queryIntentActivities",
            queries.contains(AppsAccess.LAUNCH_ACTION),
        )
        assertTrue(
            "the launcher category is part of the same query",
            queries.contains(AppsAccess.LAUNCH_CATEGORY),
        )
        assertFalse(
            "QUERY_ALL_PACKAGES is never requested: launchable entries are the whole ask",
            manifest.contains("android.permission.QUERY_ALL_PACKAGES"),
        )
    }

    // ---- usage access is the system's answer ----

    @Test fun usage_access_is_only_the_system_s_modes_allowed_answer() {
        val granted = FakeOps(AppOpsManager.MODE_ALLOWED)
        assertTrue(
            AppsAccess.usageAllowed(
                granted,
                AppsAccess.USAGE_OP,
                uid = 10123,
                packageName = "com.hermesvox",
            ),
        )
        assertEquals(AppOpsManager.OPSTR_GET_USAGE_STATS, AppsAccess.USAGE_OP)
        assertEquals(
            Triple(AppsAccess.USAGE_OP, 10123, "com.hermesvox"),
            granted.calls.single(),
        )

        for (denied in listOf(
            AppOpsManager.MODE_DEFAULT,
            AppOpsManager.MODE_IGNORED,
            AppOpsManager.MODE_ERRORED,
        )) {
            assertFalse(
                "mode $denied is not a grant",
                AppsAccess.usageAllowed(
                    FakeOps(denied),
                    AppsAccess.USAGE_OP,
                    uid = 10123,
                    packageName = "com.hermesvox",
                ),
            )
        }
    }

    // ---- running: denied ----

    @Test fun without_usage_access_it_says_it_cannot_see_what_is_running() {
        val r = AppsAccess.result(FakeHost(listOf(app("com.mail", "Mail")), access = false))

        assertTrue(r.json(), r.ok)
        assertEquals(AppsAccess.ACTION_APPS, r.action)
        assertEquals(PhoneTiers.INSTANT, r.tier)
        assertEquals(false, r.facts["can_see_running"])
        assertEquals(AppsAccess.CANNOT_SEE_RUNNING, r.facts["running_detail"])

        val json = r.json()
        assertTrue(json.contains("cannot see what is running"))
        assertTrue(json.contains("Settings → Special app access → Usage access"))
        assertTrue(json.contains("PACKAGE_USAGE_STATS"))
        assertTrue(json.contains(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        // Never an empty list that would read as "nothing is running".
        assertFalse("no recent rows are invented: $json", json.contains("\"recent\""))
        assertFalse("no foreground is invented: $json", json.contains("\"foreground\""))
        assertNull(r.facts["foreground"])
    }

    @Test fun the_exact_missing_access_sentence_names_the_setting_and_what_is_missing() {
        assertEquals(
            "I cannot see what is running: Android only lets an app with usage access read the " +
                "foreground app, and Vox does not have it. Grant Usage access to Vox in Settings → " +
                "Special app access → Usage access (PACKAGE_USAGE_STATS), then ask again.",
            AppsAccess.CANNOT_SEE_RUNNING,
        )
        assertEquals(
            Settings.ACTION_USAGE_ACCESS_SETTINGS,
            AppsAccess.USAGE_SETTINGS_ACTION,
        )
    }

    // ---- running: granted (a fake AppOps says so) ----

    @Test fun a_granted_app_ops_answer_reports_the_foreground_app() {
        val ops = FakeOps(AppOpsManager.MODE_ALLOWED)
        val host = FakeHost(
            apps = listOf(app("com.mail", "Mail")),
            access = AppsAccess.usageAllowed(
                ops,
                AppsAccess.USAGE_OP,
                uid = 10123,
                packageName = "com.hermesvox",
            ),
            foreground = app("com.browser", "Browser"),
            recent = listOf(
                app("com.browser", "Browser"),
                app("com.mail", "Mail"),
                app("com.mail", "Mail"),
                app("com.chat", "Chat"),
            ),
        )

        val r = AppsAccess.result(host)

        assertTrue(r.json(), r.ok)
        assertEquals(true, r.facts["can_see_running"])
        val foreground = r.facts["foreground"] as Map<*, *>
        assertEquals("com.browser", foreground["package"])
        assertEquals("Browser", foreground["label"])

        val recent = r.facts["recent"] as List<*>
        assertEquals(
            "the foreground app is not repeated, and one row per package",
            listOf("com.mail", "com.chat"),
            recent.map { (it as Map<*, *>)["package"] },
        )
        assertEquals(AppsAccess.RECENT_LIMIT, host.askedFor)
        val json = r.json()
        assertFalse("a granted phone says nothing about missing access", json.contains("running_detail"))
        assertFalse(json.contains("cannot see what is running"))
    }

    @Test fun a_granted_phone_with_no_recent_use_says_foreground_absent_not_running_is_empty() {
        val host = FakeHost(
            apps = listOf(app("com.mail", "Mail")),
            access = true,
            foreground = null,
            recent = emptyList(),
        )
        val r = AppsAccess.result(host)
        assertTrue(r.ok)
        assertEquals(true, r.facts["can_see_running"])
        assertNull(r.facts["foreground"])
        assertEquals(emptyList<Any?>(), r.facts["recent"])
    }

    @Test fun the_api_cannot_claim_what_get_running_app_processes_would_have_said() {
        val source = File("src/main/java/com/hermesvox/AppsAccess.kt").readText()
        assertFalse(
            "an ordinary app cannot enumerate processes; usage access is the only path",
            source.contains(".getRunningAppProcesses"),
        )
        assertTrue("the system's AppOps row is read", source.contains("checkOpNoThrow"))
        assertTrue("the system settings screen is the grant", source.contains("USAGE_ACCESS_SETTINGS"))
    }
}
