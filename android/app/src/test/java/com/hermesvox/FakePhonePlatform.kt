package com.hermesvox

/**
 * FakePhonePlatform — the phone, recorded.
 *
 * The surface audit asks two questions of every advertised action: *did it reach its effect?* and
 * *did a refusal reach nothing?* Neither can be answered by looking at the tool schema, so this
 * platform implements every method the engine can call, appends its name to [called] when it is
 * asked anything, and to [performed] when it actually changes the phone.
 *
 * It is deliberately dumb: it answers what the test set and never invents a value, so a test that
 * passes proves the engine, not the fake. [PhoneSurfaceAuditTest] drives it with every advertised
 * action; [PhoneMcpServerTest] uses it for the two wire-level cases (an Off group is an `isError`
 * with `not_granted`, and a grant change is visible on the next `tools/list`).
 *
 * Pure JVM, like the engine it serves.
 */
class FakePhonePlatform : PhoneEngine.PhonePlatform {

    /** Every platform method reached, in call order — reads included. */
    val called = ArrayList<String>()

    /**
     * The methods that change the phone. A refusal must leave this empty; a read is allowed to
     * describe a card, but nothing may be performed on the way to "no".
     */
    val performed = ArrayList<String>()

    private fun read(name: String) {
        called.add(name)
    }

    private fun write(name: String) {
        called.add(name)
        performed.add(name)
    }

    // ---- what the test can set ----

    var dndAccess = true
    var dndState = false
    var volume = 40
    var canStart = true
    var dialerAnswers = true
    var listableApps: List<AppsAccess.App>? = listOf(AppsAccess.App("org.example", "Example"))
    var usageAccessHeld = false
    var contactsPermissionMissing: String? = null
    var calendarPermissionMissing: String? = null
    var listenerGranted = true
    var grantedFolders = listOf(
        PhoneEngine.PhonePlatform.GrantedFolder("primary:Download/Work", "Work"),
    )

    // ---- alarms, sound, notes ----

    override fun startTimer(id: String, durationMs: Long, atEpochMs: Long): Boolean {
        write("startTimer")
        return true
    }

    override fun startAlarm(id: String, hour: Int, minute: Int, atEpochMs: Long): Boolean {
        write("startAlarm")
        return true
    }

    override fun cancelTimer(id: String): Boolean {
        write("cancelTimer")
        return true
    }

    override fun dndGranted(): Boolean = dndAccess

    override fun currentDnd(): Boolean? = dndState

    override fun setDnd(on: Boolean): Boolean {
        write("setDnd")
        dndState = on
        return true
    }

    override fun currentVolume(): Int? = volume

    override fun setVolume(percent: Int): Int? {
        write("setVolume")
        volume = percent
        return percent
    }

    override fun adjustVolume(direction: Int): Int? {
        write("adjustVolume")
        volume = (volume + direction).coerceIn(0, 100)
        return volume
    }

    override fun saveNote(id: String, text: String, atEpochMs: Long): Boolean {
        write("saveNote")
        return true
    }

    override fun deleteNote(id: String): Boolean {
        write("deleteNote")
        return true
    }

    // ---- media ----

    override fun mediaKey(key: PhoneEngine.PhonePlatform.MediaKey): Boolean {
        write("mediaKey")
        return true
    }

    override fun playback(): PhoneEngine.PhonePlatform.Playback {
        read("playback")
        return PhoneEngine.PhonePlatform.Playback(true, false, "org.example", "A track")
    }

    // ---- device state and clipboard ----

    override fun deviceState(): PhoneEngine.PhonePlatform.DeviceSnapshot {
        read("deviceState")
        return PhoneEngine.PhonePlatform.DeviceSnapshot(
            batteryPercent = 80, chargingSource = "usb", chargingStatus = "charging",
            screenOn = true, network = "wifi", tailnetPresent = true, freeStorageBytes = 1_000,
            mediaVolume = volume, dnd = "off", timeZoneId = "Europe/Berlin",
            utcOffsetMinutes = 60,
        )
    }

    override fun clip(text: String): PhoneEngine.PhonePlatform.ClipWrite {
        write("clip")
        return PhoneEngine.PhonePlatform.ClipWrite.Done
    }

    override fun readClip(): PhoneEngine.PhonePlatform.ClipRead {
        read("readClip")
        return PhoneEngine.PhonePlatform.ClipRead.Text("clipboard text")
    }

    // ---- files ----

    override fun folders(): List<PhoneEngine.PhonePlatform.GrantedFolder> {
        read("folders")
        return grantedFolders
    }

    override fun fileText(
        folder: PhoneEngine.PhonePlatform.GrantedFolder,
        name: String,
    ): PhoneEngine.PhonePlatform.FileText {
        read("fileText")
        return PhoneEngine.PhonePlatform.FileText.Found(name, 5, "hello")
    }

    override fun fileWindow(
        folder: PhoneEngine.PhonePlatform.GrantedFolder,
        name: String,
        offset: Long,
        length: Long,
    ): PhoneEngine.PhonePlatform.FileText {
        read("fileWindow")
        return PhoneEngine.PhonePlatform.FileText.Window(name, 5, 0, 5, "hello")
    }

    override fun fileList(
        folder: PhoneEngine.PhonePlatform.GrantedFolder,
        path: String,
        depth: Int,
        pattern: String?,
        offset: Int,
        limit: Int,
    ): PhoneFiles.ListResult {
        read("fileList")
        return PhoneFiles.ListResult.Ok(
            PhoneFiles.Listing(
                folder = folder.label,
                path = path,
                depth = depth,
                count = 0,
                hasMore = false,
                nextOffset = null,
                entries = emptyList(),
            )
        )
    }

    override fun fileInfo(
        folder: PhoneEngine.PhonePlatform.GrantedFolder,
        name: String,
    ): PhoneEngine.PhonePlatform.FileInfo {
        read("fileInfo")
        return PhoneEngine.PhonePlatform.FileInfo.Ready(name, "text/plain", 5)
    }

    override fun fileOffer(
        folder: PhoneEngine.PhonePlatform.GrantedFolder,
        name: String,
    ): PhoneEngine.PhonePlatform.FileHandoff {
        write("fileOffer")
        return PhoneEngine.PhonePlatform.FileHandoff.Ready(
            name = name,
            mime = "text/plain",
            bytes = 5,
            sha256 = "0".repeat(64),
            url = "http://100.64.0.1:8766/offer/1",
            expiresAtSec = 1_800_000_000,
        )
    }

    // ---- calendar ----

    override fun calendarPermission(): String? = calendarPermissionMissing

    override fun calendarReadPermission(): String? = calendarPermissionMissing

    override fun calendarWritePermission(): String? = calendarPermissionMissing

    override fun agenda(): List<PhoneEngine.PhonePlatform.AgendaEvent> {
        read("agenda")
        return emptyList()
    }

    override fun createEvent(
        title: String,
        startLocal: String,
        durationMin: Int,
        calendar: String?,
    ): PhoneEngine.PhonePlatform.CreatedEvent {
        write("createEvent")
        return PhoneEngine.PhonePlatform.CreatedEvent(
            id = "event-1",
            start = "${startLocal}:00",
            end = "${startLocal}:00",
            startMs = 1_800_000_000_000,
            endMs = 1_800_000_000_000 + durationMin * 60_000L,
            calendar = calendar ?: "Personal",
        )
    }

    override fun deleteEvent(id: String): Boolean {
        write("deleteEvent")
        return true
    }

    // ---- notifications ----

    override fun notificationListenerGranted(): Boolean = listenerGranted

    // ---- contacts ----

    override fun contactsPermission(): String? = contactsPermissionMissing

    override fun contactMatches(
        name: String,
        limit: Int,
    ): List<PhoneEngine.PhonePlatform.ContactMatch> {
        read("contactMatches")
        return listOf(
            PhoneEngine.PhonePlatform.ContactMatch(
                "Ada Lovelace",
                listOf(PhoneEngine.PhonePlatform.ContactNumber("mobile", "+15550100")),
            )
        )
    }

    override fun contactNumber(name: String): String? {
        read("contactNumber")
        return "+15550100"
    }

    // ---- handoffs ----

    override fun sendMessage(to: String, text: String): PhoneEngine.PhonePlatform.Handoff {
        write("sendMessage")
        return PhoneEngine.PhonePlatform.Handoff.Done("org.example.messages")
    }

    override fun canStartActivity(): Boolean = canStart

    override fun canDial(): Boolean = dialerAnswers

    override fun dial(number: String): Boolean {
        write("dial")
        return dialerAnswers
    }

    override fun resolveLaunchable(name: String): PhoneEngine.PhonePlatform.AppTarget? {
        read("resolveLaunchable")
        return PhoneEngine.PhonePlatform.AppTarget("org.example", "org.example.Main")
    }

    override fun startApp(target: PhoneEngine.PhonePlatform.AppTarget): Boolean {
        write("startApp")
        return true
    }

    override fun resolveUri(
        uri: String,
        packageName: String?,
    ): PhoneEngine.PhonePlatform.UriTarget {
        read("resolveUri")
        return PhoneEngine.PhonePlatform.UriTarget.App(
            PhoneEngine.PhonePlatform.AppTarget("org.example", "org.example.Main")
        )
    }

    override fun startUri(target: PhoneEngine.PhonePlatform.AppTarget, uri: String): Boolean {
        write("startUri")
        return true
    }

    override fun appLabel(packageName: String): String? {
        read("appLabel")
        return "Example"
    }

    override fun uninstall(packageName: String): PhoneEngine.PhonePlatform.Uninstall {
        write("uninstall")
        return PhoneEngine.PhonePlatform.Uninstall.Confirming(packageName)
    }

    // ---- apps (AppsAccess.Host) ----

    override fun launchableApps(): List<AppsAccess.App>? {
        read("launchableApps")
        return listableApps
    }

    override fun usageAccess(): Boolean = usageAccessHeld

    override fun foregroundApp(): AppsAccess.App? = null

    override fun recentApps(limit: Int): List<AppsAccess.App> = emptyList()
}
