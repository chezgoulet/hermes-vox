package com.hermesvox

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #149 — a paired Bluetooth headset has to become the call's route, and nothing that
 * cannot carry a microphone may ever be chosen as one. The selection itself is the
 * platform's; this pins the ORDER the app asks in, which is the part that can be
 * silently wrong.
 */
class CommRouteTest {

    @Test fun a_bt_headset_wins_over_every_other_attached_headset() {
        val attached = intArrayOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        )
        assertEquals(4, CommRoutePolicy.bestIndex(attached))   // the SCO entry
    }

    @Test fun order_is_sco_then_le_audio_then_hearing_aid_then_usb_then_wired() {
        assertEquals(0, CommRoutePolicy.rank(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertEquals(1, CommRoutePolicy.rank(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals(2, CommRoutePolicy.rank(AudioDeviceInfo.TYPE_HEARING_AID))
        assertEquals(3, CommRoutePolicy.rank(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals(4, CommRoutePolicy.rank(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals(
            CommRoutePolicy.NOT_A_ROUTE,
            CommRoutePolicy.rank(AudioDeviceInfo.TYPE_BUILTIN_MIC),
        )
    }

    @Test fun a_device_that_can_only_play_is_not_a_route() {
        // The #149 report in one assertion: a headset connected for media (A2DP) is not
        // something a call can hear, so the app must not select it and claim to have
        // routed the microphone.
        assertEquals(
            CommRoutePolicy.NOT_A_ROUTE,
            CommRoutePolicy.rank(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP),
        )
        assertEquals(
            -1,
            CommRoutePolicy.bestIndex(
                intArrayOf(
                    AudioDeviceInfo.TYPE_BUILTIN_MIC,
                    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                ),
            ),
        )
    }

    @Test fun nothing_worth_routing_to_keeps_the_platform_default() {
        assertEquals(-1, CommRoutePolicy.bestIndex(intArrayOf()))
        assertEquals(
            -1,
            CommRoutePolicy.bestIndex(
                intArrayOf(AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER),
            ),
        )
    }

    @Test fun the_first_of_two_equally_good_devices_is_chosen() {
        // Two headsets of the same kind: whichever the platform listed first, so the
        // choice is stable across the repeated calls the line-open path makes.
        assertEquals(
            0,
            CommRoutePolicy.bestIndex(
                intArrayOf(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET),
            ),
        )
    }

    @Test fun the_log_names_the_types_a_field_report_would_mention() {
        assertEquals("BT_SCO", CommRoutePolicy.name(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertEquals("BT_A2DP", CommRoutePolicy.name(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP))
        assertEquals("BUILTIN_MIC", CommRoutePolicy.name(AudioDeviceInfo.TYPE_BUILTIN_MIC))
    }
}
