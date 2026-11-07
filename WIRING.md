# Wiring `notification_dismiss` and `apps`

This branch adds the two app-surface capabilities without touching the shared engine:

- `android/app/src/main/java/com/hermesvox/AppsAccess.kt` — installed + running listing (new file).
- `android/app/src/main/java/com/hermesvox/PhoneNotifications.kt` — `dismiss`, `setDismisser`,
  `auditArguments` and the `notification_dismiss` result shape.
- `android/app/src/main/AndroidManifest.xml` — `PACKAGE_USAGE_STATS`; the launcher `<queries>`
  intent was already present and is now proven by `AppsAccessTest`.
- `PhoneNotificationsDismissTest` and `AppsAccessTest` — green without the wiring below.

Everything the parent has to do is listed here, in the order it appears in each file.

## The two actions

| action | grant group (`PhoneGrants.Group.id`) | tier | arguments | success facts |
|---|---|---|---|---|
| `notification_dismiss` | `notifications` | `PhoneTiers.REPORTED` (1) | exactly one of `id` (string, ≤ 80 chars, an id from a `notifications` summary of this call) or `app` (string, ≤ 200 chars, launcher label or package, case-insensitive) | `app`, `count`, `undoable: false`, `note` = "not undoable: a dismissed notification cannot be restored". A refusal is `unavailable` (no call / no bound listener), `unknown-id` (id never summarised, app has no notification, or Vox's own), or `invalid-arguments`. Never a title, body or listener id. |
| `apps` | `apps` | `PhoneTiers.INSTANT` (0) | none | `apps: [{package, label}]`, `total`, `truncated`. With usage access: `can_see_running: true`, `foreground: {package, label}` when known, `recent: [{package, label}]`. Without it: `can_see_running: false`, `running_detail` (the sentence below), `usage_settings: "android.settings.USAGE_ACCESS_SETTINGS"`. No `foreground`/`recent` keys are emitted at all when access is missing. |

`PhoneNotifications.dismiss(id, app)` already returns a complete `PhoneResult` with
`tier = PhoneTiers.REPORTED`, so the engine method is only the lifetime/ permission gate.

The exact sentence `apps` gives when usage access is missing
(`AppsAccess.CANNOT_SEE_RUNNING`):

> I cannot see what is running: Android only lets an app with usage access read the foreground
> app, and Vox does not have it. Grant Usage access to Vox in Settings → Special app access →
> Usage access (PACKAGE_USAGE_STATS), then ask again.

## `PhoneGrants.kt`

1. In `GROUPS`, the **notifications** group:

```diff
-            actions = listOf("notifications", "notification_read"),
+            actions = listOf("notifications", "notification_read", "notification_dismiss"),
```

   (Optionally extend its `lets`: "…, and dismiss one or all of an app's while a call is open.")

2. In `GROUPS`, the **apps** group:

```diff
-            actions = listOf("launch", "open", "uninstall"),
-            planned = listOf("install"),
+            actions = listOf("apps", "launch", "open", "uninstall"),
```

   The `planned = listOf("install")` line is deleted outright: `install` leaves the surface
   (see below), so `groupOf("install")` becomes `null` and a direct call is `unknown-action`
   rather than a governance refusal for a capability nobody built.

Nothing else in `PhoneGrants.kt` changes. `advertised`/`honoursAsk` already do the right thing:
`apps` is a Tier 0 action of an Instant group (offered only at Allow), and
`notification_dismiss` is a Tier 1 action inside the Instant notifications group, so at Ask it is
offered and runs — the existing "a reported group at Ask runs" rule, not a new one.

## `PhoneTool.kt`

1. `ACTION_HINTS` (the `linkedMapOf` near line 172): add

```kotlin
"apps" to "what is installed, and what the phone can honestly see running",
"notification_dismiss" to "dismiss one notification by id, or all of an app's; not undoable",
```

2. The `sealed class Call` (near line 440): add next to `Notifications`/`NotificationRead`

```kotlin
/** Dismiss by one summarised [id], or every notification from [app]; exactly one is set. */
data class NotificationDismiss(val id: String?, val app: String?) : Call() {
    override val action = "notification_dismiss"
}

object Apps : Call() {
    override val action = "apps"
}
```

3. `parseArguments` (the `when (action)` near line 690): add these two branches

```kotlin
"apps" -> Parsed.Run(Call.Apps)
"notification_dismiss" -> {
    val id = (args["id"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
    val app = (args["app"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
    when {
        id != null && app != null -> bad(
            action,
            "give id or app, not both: an id dismisses one notification, an app dismisses all " +
                "of that app's",
        )
        id == null && app == null -> bad(
            action,
            "id or app is required: an id from a notifications summary, or an app name",
        )
        id != null && id.length > MAX_NOTIFICATION_ID_CHARS ->
            bad(action, "id is longer than $MAX_NOTIFICATION_ID_CHARS characters")
        app != null && app.length > MAX_APP_CHARS ->
            bad(action, "app is longer than $MAX_APP_CHARS characters")
        else -> Parsed.Run(Call.NotificationDismiss(id, app))
    }
}
```

   and **delete** the whole `"install" -> Parsed.Refuse(…)` branch (around line 958).

4. `PhoneTiers`:

```diff
     private val TIER_0 = setOf(
-        "timer", "alarm", "dnd", "volume", "status", "undo",
+        "timer", "alarm", "dnd", "volume", "status", "undo",
+        // Apps (#apps-and-dismiss lane): the installed listing is a read of launchable entries.
+        "apps",
...
     private val TIER_1 = setOf(
         "note",
+        // Dismissal changes something the owner can see, and cannot be undone.
+        "notification_dismiss",
...
     private val TIER_2 = setOf(
-        "purchase", "pay", "install", "uninstall", "transfer",
+        "purchase", "pay", "uninstall", "transfer",
```

5. Schema descriptions: in the `"id"` property add "notification_dismiss: an id a `notifications`
   summary returned — the notification to dismiss." and in `"app"` add "notification_dismiss: the
   launcher label or package whose notifications are all dismissed." Nothing else in the schema
   is needed: `apps` takes no arguments.

6. The class KDoc tier table (around line 34) should move `apps` to Tier 0, add
   `notification_dismiss` to Tier 1, and drop `install` from Tier 2.

## `PhoneEngine.kt`

1. `PhonePlatform` declaration (around line 79): extend the already-defaulted seam

```diff
-    interface PhonePlatform : DialHandoff.Host {
+    interface PhonePlatform : DialHandoff.Host, AppsAccess.Host {
```

   `AppsAccess.Host` has safe defaults (`null` list, `usageAccess() = false`, no foreground, empty
   recent), so every existing fake compiles unchanged. The Android implementation is added in
   `LocalActions.kt` (below), not here.

2. `execute` (around line 602): two new branches

```diff
             PhoneTool.Call.Notifications -> notifications()
             is PhoneTool.Call.NotificationRead -> notificationRead(call.id)
+            is PhoneTool.Call.NotificationDismiss -> notificationDismiss(call)
+            PhoneTool.Call.Apps -> AppsAccess.result(platform)
```

3. Add the method beside `notificationRead` (around line 1543)

```kotlin
/**
 * Dismiss by id or app. The lifetime check comes first, exactly as `notifications` does, so a
 * phone with no live call answers `unavailable` even if the system grant is also missing.
 */
private fun notificationDismiss(call: PhoneTool.Call.NotificationDismiss): PhoneResult {
    if (!PhoneNotifications.attached()) {
        return listenerClosed(PhoneNotifications.ACTION_DISMISS)
    }
    listenerPermission(PhoneNotifications.ACTION_DISMISS)?.let { return it }
    val result = PhoneNotifications.dismiss(call.id, call.app)
    log(
        "event=phone-mcp action=${PhoneNotifications.ACTION_DISMISS} ok=${result.ok} " +
            "count=${result.facts["count"] ?: 0}",
    )
    return result
}
```

   (`PhoneNotifications.dismiss` re-checks attachment and returns its own `unavailable`; the
   engine's check only keeps the order identical to `notifications`.)

4. `dispatch` (around line 585): replace the one audit call so a dismissal's raw `id` never reaches
   the audit row

```diff
-                audit(result, arguments, nowEpoch)
+                audit(
+                    result,
+                    if (result.action == PhoneNotifications.ACTION_DISMISS) {
+                        PhoneNotifications.auditArguments(result)
+                    } else {
+                        arguments
+                    },
+                    nowEpoch,
+                )
```

   `auditArguments` emits only `{app, notification_count}` (count `"0"` on a refusal that named an
   app; an empty map for an id refusal). `PhoneAudit.summarise` then renders exactly those two
   fields — no `PhoneAudit.kt` change is needed.

## Android edges (outside the three shared files)

1. `MediaListen.kt` — the listener is the only thing that can cancel:

```kotlin
override fun onListenerConnected() {
    PhoneNotifications.setResync { seed() }
    PhoneNotifications.setDismisser { key ->
        try {
            cancelNotification(key)
            true
        } catch (_: Throwable) {
            false
        }
    }
    seed()
}
```

   and in both `onListenerDisconnected()` and `onDestroy()`:
   `PhoneNotifications.setDismisser(null)`.

2. `LocalActions.kt` — `Platform` (the `PhoneEngine.PhonePlatform` implementation) delegates the
   four `AppsAccess.Host` methods to one Android adapter:

```kotlin
private val appsHost = AppsAccess.Android(app)

override fun launchableApps(): List<AppsAccess.App>? = appsHost.launchableApps()
override fun usageAccess(): Boolean = appsHost.usageAccess()
override fun foregroundApp(): AppsAccess.App? = appsHost.foregroundApp()
override fun recentApps(limit: Int): List<AppsAccess.App> = appsHost.recentApps(limit)
```

3. `AndroidManifest.xml` is already done on this branch: the launcher `<queries>` intent exists
   (Android 11+ visibility for both `launch` and `apps`) and `PACKAGE_USAGE_STATS` is declared so
   the app appears in the usage-access settings screen. `QUERY_ALL_PACKAGES` is not requested.

## `install` leaves the surface

`install` was declared and never implemented: `PhoneTool.parseArguments` only refused it, and
`PhoneGrants` kept it in `apps.planned` so a direct call got a governance refusal. Remove it in
the same pass as the wiring:

- `PhoneGrants.kt`: delete `planned = listOf("install")` from the apps group.
- `PhoneTool.kt`: delete the `"install" -> Parsed.Refuse(…)` branch and remove `"install"` from
  `PhoneTiers.TIER_2`; update the class KDoc tier table.
- `PhoneGrantsTest.kt` (~lines 225-227) currently asserts `install` is planned in the apps group;
  delete or invert that assertion (it is testing a capability that no longer exists).
- Any doc or skill sentence that says `install` is a refused Tier 2 name should drop it.

Why it is out of this release: an ordinary app cannot install a package. Android always shows the
system's own installer confirmation (`ACTION_INSTALL_PACKAGE` / PackageInstaller session), and the
app never learns whether the owner accepted, so a tool result could not honestly say "installed".
Silent install exists only for a Device Owner through `PackageInstaller` with
`INSTALL_ALLOW_TEST`-class privileges, which a sideloaded voice client is not — and claiming a
capability the phone cannot verify would break the tool's one invariant.

## Tests the parent updates when wiring

- `PhoneToolTest` pins the advertised enum; add `apps` where the apps group is Allow and
  `notification_dismiss` where Notifications is on (the tests at ~line 53 and ~line 301).
- `PhoneGrantsTest` (the `install` planned assertion above).
- The new tests here stay valid: `PhoneNotificationsDismissTest` drives `PhoneNotifications`
  directly (no parse), and `AppsAccessTest` drives `AppsAccess` with fakes.
