package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Test

/** #207 — which on-device STT model loads when the streaming arm is selected, and the
 *  fallback when it cannot be used: always the selected shipped model, never another one. */
class SttFallbackTest {
    private val nem = ModelCatalog.NEMOTRON_STREAM_ID

    @Test fun never_chosen_keeps_the_shipped_model_and_logs_nothing() {
        for (pref in listOf("", null, "  ")) {
            assertEquals(OnDeviceSttPick("whisper-base", null),
                pickOnDeviceStt(pref, StreamSttStatus.ABSENT, "whisper-base", true))
            assertEquals(OnDeviceSttPick("parakeet-v2", null),
                pickOnDeviceStt(pref, StreamSttStatus.ABSENT, "parakeet-v2", true))
        }
        assertEquals(OnDeviceSttPick(null, null), pickOnDeviceStt("", StreamSttStatus.ABSENT, "whisper-base", false))
    }

    @Test fun selected_and_ready_uses_the_streaming_arm() {
        assertEquals(OnDeviceSttPick(nem, null), pickOnDeviceStt(nem, StreamSttStatus.READY, "whisper-base", true))
        // ...even when the shipped model underneath is not installed.
        assertEquals(OnDeviceSttPick(nem, null), pickOnDeviceStt(nem, StreamSttStatus.READY, "whisper-base", false))
    }

    @Test fun absent_downloading_or_failed_falls_back_to_the_selected_shipped_model() {
        val cases = mapOf(
            StreamSttStatus.ABSENT to "absent",
            StreamSttStatus.DOWNLOADING to "downloading",
            StreamSttStatus.INIT_FAILED to "init-failed",
        )
        for ((status, reason) in cases) {
            assertEquals(OnDeviceSttPick("parakeet-v2", reason), pickOnDeviceStt(nem, status, "parakeet-v2", true))
            assertEquals(OnDeviceSttPick("whisper-small", reason), pickOnDeviceStt(nem, status, "whisper-small", true))
            // No shipped model installed: the existing chain's platform recognizer, still logged.
            assertEquals(OnDeviceSttPick(null, reason), pickOnDeviceStt(nem, status, "whisper-base", false))
        }
    }

    @Test fun an_unknown_streaming_pref_is_ignored() {
        assertEquals(OnDeviceSttPick("whisper-base", null),
            pickOnDeviceStt("zipformer-0626", StreamSttStatus.READY, "whisper-base", true))
    }

    @Test fun the_leg_resolver_counts_a_ready_streaming_arm_as_on_device() {
        val pick = pickOnDeviceStt(nem, StreamSttStatus.READY, "whisper-base", false)
        assertEquals(ModelCatalog.BACKEND_ONDEVICE, resolveSttLeg(ModelCatalog.BACKEND_ONDEVICE, "", pick.modelId != null))
        val none = pickOnDeviceStt(nem, StreamSttStatus.ABSENT, "whisper-base", false)
        assertEquals(ModelCatalog.BACKEND_PLATFORM, resolveSttLeg(ModelCatalog.BACKEND_ONDEVICE, "", none.modelId != null))
    }
}
