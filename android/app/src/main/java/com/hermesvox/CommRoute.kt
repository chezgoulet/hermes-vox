package com.hermesvox

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * CommRoute — which device a call actually uses when a headset is attached (#149).
 *
 * Android does not move capture onto a Bluetooth headset on its own. A paired
 * headset sits on the media route (A2DP) and the microphone stays on the phone
 * until the *communication* route is selected. Phone and dialer apps get that
 * from the telecom stack; a self-managed voice app has to ask for it.
 *
 * One policy, two mechanisms:
 *  - **API 31+** `setCommunicationDevice()`. This is the current API — the platform
 *    guide says apps must migrate to it, and `startBluetoothSco()`/`stopBluetoothSco()`
 *    were deprecated in API 34 for exactly this reason. Only an output (sink) device
 *    is named; the platform picks the matching source, so naming the headset's output
 *    is also what moves the headset's microphone.
 *  - **API 24-30** `startBluetoothSco()` + `isBluetoothScoOn`, the only mechanism that
 *    exists below 31.
 *
 * No Bluetooth permission is involved. This is audio routing (MODIFY_AUDIO_SETTINGS,
 * already declared), not a Bluetooth API call — declaring BLUETOOTH_CONNECT for it
 * would put a "Nearby devices" prompt in front of every user for nothing.
 *
 * Two consequences worth knowing before reading a field log:
 *  - The selection follows the **communication** route, which is what
 *    `AudioSource.VOICE_COMMUNICATION` (the default capture source) uses. With
 *    "Hardware AEC/NS" switched off the capture is a raw `MIC`, which routes on the
 *    media strategy and stays on the phone — a headset mic needs the default source.
 *  - The selection lives as long as the app's process and is dropped by the platform
 *    when the device disconnects, so a missed `clear()` cannot leave a call routed to
 *    a headset that is no longer there.
 */
object CommRoute {

    /** The type we asked for, or -1 for "the platform default is the right answer".
     *  Kept so [clear] only ever undoes a selection this app made. */
    @Volatile private var selected = -1

    /**
     * Make the best attached headset the call's route. Safe to call on every line open,
     * including a mid-call device change: it re-reads the device list every time and
     * returns early when the platform is already on the device we want. Returns the
     * device type it settled on, or -1.
     */
    fun select(ctx: Context): Int {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return -1
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) selectModern(am) else selectLegacy(am)
        } catch (e: Throwable) {
            VoxLog.e("event=comm-route action=select-failed err=${e.message}")
            -1
        }
    }

    /**
     * Leave the route as we found it, at the end of a call. Does nothing when this app
     * never selected a device, so a call that did not touch routing cannot stomp a
     * choice another app made.
     */
    fun clear(ctx: Context) {
        val was = selected
        if (was < 0) return
        selected = -1
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.clearCommunicationDevice() else clearLegacy(am)
            VoxLog.d("event=comm-route action=cleared was=${CommRoutePolicy.name(was)}")
        } catch (e: Throwable) {
            VoxLog.e("event=comm-route action=clear-failed err=${e.message}")
        }
    }

    private fun selectModern(am: AudioManager): Int {
        val devices = am.availableCommunicationDevices
        val names = devices.joinToString("+") { CommRoutePolicy.name(it.type) }
        val idx = CommRoutePolicy.bestIndex(IntArray(devices.size) { devices[it].type })
        if (idx < 0) {
            VoxLog.d("event=comm-route action=none api=${Build.VERSION.SDK_INT} available=${names.ifEmpty { "none" }}")
            return -1
        }
        val want = devices[idx]
        val cur = am.communicationDevice
        if (cur != null && cur.type == want.type && selected == want.type) return want.type
        val ok = am.setCommunicationDevice(want)
        selected = if (ok) want.type else -1
        VoxLog.d(
            "event=comm-route action=set ok=$ok type=${CommRoutePolicy.name(want.type)} " +
                "previous=${cur?.let { CommRoutePolicy.name(it.type) } ?: "default"} available=${names.ifEmpty { "none" }}"
        )
        return selected
    }

    /** Pre-31: start the SCO link when a HFP output is attached. The platform confirms
     *  the link asynchronously, so this is the request, not the arrival — the SCO device
     *  appearing afterwards is what the call's route callback rebuilds the line on. */
    @Suppress("DEPRECATION")
    private fun selectLegacy(am: AudioManager): Int {
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val names = outs.joinToString("+") { CommRoutePolicy.name(it.type) }
        if (outs.none { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }) {
            VoxLog.d("event=comm-route action=none api=${Build.VERSION.SDK_INT} outputs=${names.ifEmpty { "none" }}")
            return -1
        }
        if (!am.isBluetoothScoOn) {
            am.isBluetoothScoOn = true
            am.startBluetoothSco()
        }
        selected = AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        VoxLog.d(
            "event=comm-route action=set ok=true type=BT_SCO previous=default " +
                "api=${Build.VERSION.SDK_INT} outputs=${names.ifEmpty { "none" }}"
        )
        return selected
    }

    @Suppress("DEPRECATION")
    private fun clearLegacy(am: AudioManager) {
        am.stopBluetoothSco()
        am.isBluetoothScoOn = false
    }
}

/**
 * The preference order, as a pure decision so it is unit-tested rather than implied by
 * the order of an `if` chain: a headset beat an earpiece beat a cable. Audio routed to a
 * device that only plays (A2DP) is not a route — a call has to be able to hear you — so
 * A2DP is deliberately absent and the built-in mic/speaker stays the answer when nothing
 * better is attached.
 */
object CommRoutePolicy {

    fun rank(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> 0   // HFP headset — captures and plays
        AudioDeviceInfo.TYPE_BLE_HEADSET -> 1     // LE Audio headset (API 31+)
        AudioDeviceInfo.TYPE_HEARING_AID -> 2     // API 28+
        AudioDeviceInfo.TYPE_USB_HEADSET -> 3
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> 4
        else -> NOT_A_ROUTE
    }

    /** Index of the best-ranked entry in [types], or -1 when nothing there is worth
     *  routing to (the built-in mic and speaker are then the right answer). */
    fun bestIndex(types: IntArray): Int {
        var best = -1
        var bestRank = Int.MAX_VALUE
        for (i in types.indices) {
            val r = rank(types[i])
            if (r != NOT_A_ROUTE && r < bestRank) {
                best = i
                bestRank = r
            }
        }
        return best
    }

    fun name(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT_SCO"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        AudioDeviceInfo.TYPE_HEARING_AID -> "HEARING_AID"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "BUILTIN_SPEAKER"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT_A2DP"
        else -> "TYPE_$type"
    }

    const val NOT_A_ROUTE = -1
}
