package com.hermesvox

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings

/**
 * AppsAccess — the Android edge of the `apps` action: **what is installed** and **what the phone
 * can honestly say is running**.
 *
 * ## Installed means launchable
 *
 * A person asking "what's installed" means the apps they can open, so the list is
 * `queryIntentActivities(ACTION_MAIN + CATEGORY_LAUNCHER)`: package and launcher label, sorted by
 * label, capped at [MAX_APPS]. That is a package-visibility question, not a permission one. Android
 * 11+ only answers it because the manifest declares the launcher `<queries>` intent — the same
 * declaration `launch` uses. **`QUERY_ALL_PACKAGES` is deliberately not requested**: every
 * installed package is not what was asked for.
 *
 * ## Running is not free
 *
 * `getRunningAppProcesses()` has returned only the caller's own process since API 22 (and API 26
 * removed the rest), so "what's running" is not something an ordinary app can ask. Usage access is
 * the one honest path, and it is a special access the owner grants on Android's own screen
 * ([Settings.ACTION_USAGE_ACCESS_SETTINGS]), declared as `PACKAGE_USAGE_STATS`.
 *
 * [usageAllowed] reads the **system's** answer through [AppOpsManager] every time; there is no
 * stored flag that could drift from the setting. Without it [result] says plainly that it cannot
 * see what is running and names the setting. It never returns an empty `recent` list, because
 * "no rows" would read as "nothing is running" — a claim this app cannot make.
 *
 * Pure JVM above [Android]: [Host] is a tiny seam and the tests drive it with fakes. The Android
 * implementation is in this file so there is one place that knows the two queries.
 */
object AppsAccess {

    /** The action name the tool offers; the parent wires it into [PhoneTool]. */
    const val ACTION_APPS = "apps"

    /** "What's installed" is a list to browse, not an inventory dump. */
    const val MAX_APPS = 200

    /** A short recent list, not a usage history. */
    const val RECENT_LIMIT = 5

    /** The launcher query. Android 11+ visibility is declared against exactly these two values. */
    const val LAUNCH_ACTION = "android.intent.action.MAIN"
    const val LAUNCH_CATEGORY = "android.intent.category.LAUNCHER"

    /** `AppOpsManager.OPSTR_GET_USAGE_STATS`: the row the system screen flips. */
    const val USAGE_OP = "android:get_usage_stats"

    /** The setting screen that grants usage access. */
    const val USAGE_SETTINGS_ACTION = "android.settings.USAGE_ACCESS_SETTINGS"

    /**
     * The sentence `apps` gives when it cannot see what is running. It says what is missing, where
     * to grant it, and never dignifies an empty list as "nothing is running".
     */
    const val CANNOT_SEE_RUNNING =
        "I cannot see what is running: Android only lets an app with usage access read the " +
            "foreground app, and Vox does not have it. Grant Usage access to Vox in Settings → " +
            "Special app access → Usage access (PACKAGE_USAGE_STATS), then ask again."

    /** One launchable app: the package Android starts, and the name the launcher shows. */
    data class App(val packageName: String, val label: String)

    /** The installed answer, already sorted and capped, plus the whole count and the cap flag. */
    data class Installed(val apps: List<App>, val total: Int, val truncated: Boolean)

    /** What usage access lets the phone say: one foreground app and a short recent list. */
    data class Running(val foreground: App?, val recent: List<App>)

    /** The launcher query as values, so a JVM test can pin it without an Android Intent. */
    data class Query(val action: String, val category: String)

    /** The one visibility query this action asks. */
    fun launcherQuery(): Query = Query(LAUNCH_ACTION, LAUNCH_CATEGORY)

    /**
     * The platform seam. Every method has a safe default, so a device (or a test) that cannot
     * answer says so instead of inventing a phone. [PhoneEngine.PhonePlatform] extends this.
     */
    interface Host {
        /** Launchable apps, or null when the package manager itself failed. */
        fun launchableApps(): List<App>? = null

        /** The system's AppOps answer right now. Never a preference this app wrote. */
        fun usageAccess(): Boolean = false

        /** The app in front, when usage access makes it knowable. */
        fun foregroundApp(): App? = null

        /** Apps used recently, newest first, bounded by [limit]. */
        fun recentApps(limit: Int): List<App> = emptyList()
    }

    /** AppOpsManager's mode, as a function so a test can supply the system's answer. */
    fun interface AppOps {
        fun mode(op: String, uid: Int, packageName: String): Int
    }

    /**
     * True only for [AppOpsManager.MODE_ALLOWED]. `MODE_DEFAULT` is not permission, and reading it
     * as one is how a "granted" flag starts lying to the owner.
     */
    fun usageAllowed(ops: AppOps, op: String, uid: Int, packageName: String): Boolean =
        try {
            ops.mode(op, uid, packageName) == AppOpsManager.MODE_ALLOWED
        } catch (_: Throwable) {
            false
        }

    /**
     * The launchable list: package once, label falling back to the package, sorted case-
     * insensitively by label (package breaks ties), capped at [MAX_APPS]. [Installed.total] is the
     * whole list, so `truncated:true` still says how many there are.
     */
    fun installed(host: Host): Installed {
        val all = host.launchableApps().orEmpty()
            .asSequence()
            .filter { it.packageName.isNotBlank() }
            .map { app ->
                App(
                    packageName = app.packageName,
                    label = app.label.trim().ifEmpty { app.packageName },
                )
            }
            .distinctBy { it.packageName }
            .sortedWith(
                compareBy<App, String>(String.CASE_INSENSITIVE_ORDER) { it.label }
                    .thenBy { it.packageName },
            )
            .toList()
        return Installed(
            apps = all.take(MAX_APPS),
            total = all.size,
            truncated = all.size > MAX_APPS,
        )
    }

    /** Null when usage access is missing — the absence is the answer, never an empty list. */
    fun running(host: Host): Running? {
        if (!host.usageAccess()) return null
        val foreground = host.foregroundApp()?.normalised()
        val recent = host.recentApps(RECENT_LIMIT)
            .asSequence()
            .map { it.normalised() }
            .filter { it.packageName.isNotBlank() }
            .filter { it.packageName != foreground?.packageName }
            .distinctBy { it.packageName }
            .take(RECENT_LIMIT)
            .toList()
        return Running(foreground, recent)
    }

    /**
     * The whole `apps` result. Installed apps are always answered; the running half is either a
     * foreground app plus a short recent list, or the sentence that says it cannot see them.
     */
    fun result(host: Host): PhoneResult {
        val installed = installed(host)
        val facts = linkedMapOf<String, Any?>(
            "apps" to installed.apps.map { app ->
                linkedMapOf<String, Any?>(
                    "package" to app.packageName,
                    "label" to app.label,
                )
            },
            "total" to installed.total,
            "truncated" to installed.truncated,
            // Reading the launchable list changes nothing, and the mind is told so rather than
            // left to infer it from a missing key.
            "undoable" to false,
        )
        val running = running(host)
        if (running == null) {
            facts["can_see_running"] = false
            facts["running_detail"] = CANNOT_SEE_RUNNING
            facts["usage_settings"] = USAGE_SETTINGS_ACTION
        } else {
            facts["can_see_running"] = true
            running.foreground?.let { foreground ->
                facts["foreground"] = linkedMapOf<String, Any?>(
                    "package" to foreground.packageName,
                    "label" to foreground.label,
                )
            }
            facts["recent"] = running.recent.map { app ->
                linkedMapOf<String, Any?>(
                    "package" to app.packageName,
                    "label" to app.label,
                )
            }
        }
        return PhoneResult(
            ok = true,
            action = ACTION_APPS,
            tier = PhoneTiers.INSTANT,
            id = null,
            facts = facts,
        )
    }

    private fun App.normalised(): App =
        App(packageName = packageName, label = label.trim().ifEmpty { packageName })

    /**
     * The Android implementation. It knows exactly two queries: the launcher entries for the
     * installed list, and [UsageStatsManager]/[AppOpsManager] for the running half.
     */
    class Android(private val context: Context) : Host {

        override fun launchableApps(): List<App>? = try {
            val pm = context.packageManager
            val query = launcherQuery()
            pm.queryIntentActivities(
                Intent(query.action).addCategory(query.category),
                0,
            )
                .mapNotNull { it.activityInfo?.packageName }
                .distinct()
                .map { pkg -> App(packageName = pkg, label = label(pkg)) }
        } catch (_: Throwable) {
            null
        }

        override fun usageAccess(): Boolean = try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return false
            usageAllowed(
                AppOps { op, uid, pkg -> appOps.checkOpNoThrow(op, uid, pkg) },
                USAGE_OP,
                Process.myUid(),
                context.packageName,
            )
        } catch (_: Throwable) {
            false
        }

        override fun foregroundApp(): App? {
            val events = eventsSince(DAY_MS) ?: return null
            var packageName: String? = null
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    packageName = event.packageName
                }
            }
            return packageName?.takeIf { it.isNotBlank() }?.let { App(it, label(it)) }
        }

        override fun recentApps(limit: Int): List<App> {
            if (limit < 1) return emptyList()
            val events = eventsSince(DAY_MS) ?: return emptyList()
            val ordered = LinkedHashMap<String, Long>()
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    val pkg = event.packageName
                    if (!pkg.isNullOrBlank() && pkg != context.packageName) {
                        ordered[pkg] = event.timeStamp
                    }
                }
            }
            return ordered.entries
                .sortedByDescending { it.value }
                .take(limit)
                .map { App(it.key, label(it.key)) }
        }

        private fun eventsSince(windowMs: Long): UsageEvents? = try {
            val manager = context.getSystemService(Context.USAGE_STATS_SERVICE)
                as? UsageStatsManager ?: return null
            val now = System.currentTimeMillis()
            manager.queryEvents(now - windowMs, now)
        } catch (_: Throwable) {
            null
        }

        private fun label(packageName: String): String = try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0))
                .toString()
                .trim()
                .ifEmpty { packageName }
        } catch (_: Throwable) {
            packageName
        }

        private companion object {
            const val DAY_MS = 24L * 60L * 60L * 1000L
        }
    }

    /** The system screen that grants usage access; the sheet opens this, not a permission dialog. */
    fun usageSettingsIntent(): Intent = Intent(USAGE_SETTINGS_ACTION)
}
