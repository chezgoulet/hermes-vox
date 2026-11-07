package com.hermesvox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #219: the model row's displayed state is a pure mapping over the SAME facts the
 * download path uses — [ModelDownloads.State] plus the licence acceptance that
 * [ModelDownloader] enforces. These tests pin every state, including the ones a
 * device would need a download (or a failure) to reach.
 */
class ModelRowStateTest {

    private val nvidia = ModelLicence(
        name = "NVIDIA Open Model License Agreement",
        attribution = "Licensed by NVIDIA Corporation",
        modelCardUrl = "https://example.invalid/model-card"
    )

    private fun row(
        state: ModelDownloads.State,
        licence: ModelLicence? = null,
        accepted: Boolean = false
    ) = ModelRowState.of(state, licence, accepted)

    @Test
    fun not_installed_says_so_and_offers_download() {
        val r = row(ModelDownloads.State.Idle)
        assertEquals(ModelRowState.Stage.NOT_INSTALLED, r.stage)
        assertEquals("Not installed", r.state)
        assertEquals(ModelRowState.Action.DOWNLOAD, r.action)
        assertEquals("Download", r.actionLabel)
    }

    @Test
    fun needs_a_licence_is_its_own_state_before_downloading() {
        val r = row(ModelDownloads.State.Idle, nvidia, accepted = false)
        assertEquals(ModelRowState.Stage.NEEDS_LICENCE, r.stage)
        assertTrue(r.state.contains(nvidia.name))
        assertEquals(ModelRowState.Action.REVIEW_LICENCE, r.action)
        assertEquals("Review licence", r.actionLabel)
        assertEquals(ModelRowState.Tone.WARN, r.tone)
    }

    @Test
    fun an_accepted_licence_falls_through_to_the_download_state() {
        val r = row(ModelDownloads.State.Idle, nvidia, accepted = true)
        assertEquals(ModelRowState.Stage.NOT_INSTALLED, r.stage)
        assertEquals(ModelRowState.Action.DOWNLOAD, r.action)
    }

    @Test
    fun an_installed_model_wins_over_a_stale_licence_record() {
        val r = row(ModelDownloads.State.Installed, nvidia, accepted = false)
        assertEquals(ModelRowState.Stage.INSTALLED, r.stage)
        assertEquals(ModelRowState.Action.NONE, r.action)
        assertEquals(ModelRowState.Tone.OK, r.tone)
    }

    @Test
    fun queued_says_it_is_waiting_and_can_be_cancelled() {
        val r = row(ModelDownloads.State.Queued)
        assertEquals(ModelRowState.Stage.QUEUED, r.stage)
        assertTrue(r.state.contains("Waiting"))
        assertEquals(ModelRowState.Action.CANCEL, r.action)
        assertTrue(r.indeterminate)
    }

    @Test
    fun downloading_with_a_total_reports_real_progress() {
        val r = row(ModelDownloads.State.Running(
            ModelDownloader.Phase.DOWNLOAD, 40L * 1048576L, 80L * 1048576L))
        assertEquals(ModelRowState.Stage.DOWNLOADING, r.stage)
        assertEquals("Downloading — 40.0 of 80.0 MB", r.state)
        assertEquals(0.5f, r.progress!!, 0.001f)
        assertFalse(r.indeterminate)
        assertEquals(ModelRowState.Action.PAUSE, r.action)
    }

    @Test
    fun downloading_just_started_still_has_a_determinate_zero() {
        val r = row(ModelDownloads.State.Running(
            ModelDownloader.Phase.DOWNLOAD, 0L, 80L * 1048576L))
        assertEquals(ModelRowState.Stage.DOWNLOADING, r.stage)
        assertEquals(0f, r.progress!!, 0.001f)
        assertFalse(r.indeterminate)
    }

    @Test
    fun downloading_with_no_progress_reported_yet_is_indeterminate_not_invented() {
        val r = row(ModelDownloads.State.Running(ModelDownloader.Phase.DOWNLOAD, 0L, -1L))
        assertEquals(ModelRowState.Stage.DOWNLOADING, r.stage)
        assertEquals("Downloading — no progress reported yet", r.state)
        assertNull(r.progress)
        assertTrue(r.indeterminate)
    }

    @Test
    fun waiting_for_a_network_is_not_a_failure() {
        val r = row(ModelDownloads.State.Running(
            ModelDownloader.Phase.WAITING_NETWORK, 12L * 1048576L, -1L))
        assertEquals(ModelRowState.Stage.WAITING_NETWORK, r.stage)
        assertTrue(r.state.contains("Waiting for network"))
        assertEquals(ModelRowState.Action.PAUSE, r.action)
    }

    @Test
    fun verifying_and_unpacking_offer_no_no_op_action() {
        val verify = row(ModelDownloads.State.Running(
            ModelDownloader.Phase.VERIFY, 0L, 80L * 1048576L))
        assertEquals(ModelRowState.Stage.VERIFYING, verify.stage)
        assertEquals(ModelRowState.Action.NONE, verify.action)
        assertTrue(verify.indeterminate)

        val unpack = row(ModelDownloads.State.Running(
            ModelDownloader.Phase.UNPACK, 0L, 80L * 1048576L))
        assertEquals(ModelRowState.Stage.UNPACKING, unpack.stage)
        assertEquals(ModelRowState.Action.NONE, unpack.action)
    }

    @Test
    fun a_paused_partial_keeps_its_bytes_and_offers_resume() {
        val r = row(ModelDownloads.State.Paused(
            20L * 1048576L, 80L * 1048576L, null))
        assertEquals(ModelRowState.Stage.PAUSED, r.stage)
        assertTrue(r.state.contains("20.0 MB kept"))
        assertEquals(0.25f, r.progress!!, 0.001f)
        assertEquals(ModelRowState.Action.RESUME, r.action)
        assertEquals(ModelRowState.Tone.WARN, r.tone)
    }

    @Test
    fun a_pause_before_any_bytes_does_not_claim_bytes_were_kept() {
        val r = row(ModelDownloads.State.Paused(0L, 80L * 1048576L, null))
        assertEquals(ModelRowState.Stage.PAUSED, r.stage)
        assertEquals("Paused — nothing downloaded yet", r.state)
        assertNull(r.progress)
    }

    @Test
    fun a_failure_is_cannot_start_with_the_honest_reason() {
        val r = row(ModelDownloads.State.Paused(
            5L * 1048576L, 80L * 1048576L,
            "not enough storage — need 80 MB free, have 12 MB"))
        assertEquals(ModelRowState.Stage.CANNOT_START, r.stage)
        assertTrue(r.state.startsWith("Cannot start — not enough storage"))
        assertTrue(r.state.contains("5.0 MB kept"))
        assertEquals(ModelRowState.Action.RESUME, r.action)
        assertEquals(ModelRowState.Tone.DANGER, r.tone)
    }

    @Test
    fun size_is_the_catalogues_decimal_mb_verbatim() {
        assertEquals("82.0 MB", ModelRowState.sizeLabel(82.0))
        assertEquals("0.6 MB", ModelRowState.sizeLabel(0.6))
        assertEquals("2588.1 MB", ModelRowState.sizeLabel(2588.1))
    }

    @Test
    fun licence_badge_only_exists_for_a_non_permissive_licence() {
        assertNull(ModelRowState.licenceBadge(null, accepted = false))

        val needed = ModelRowState.licenceBadge(nvidia, accepted = false)!!
        assertTrue(needed.startsWith("Licence needed"))
        assertTrue(needed.contains(nvidia.name))
        assertTrue(needed.contains("tap to review and accept"))

        val accepted = ModelRowState.licenceBadge(nvidia, accepted = true)!!
        assertTrue(accepted.startsWith("Licence:"))
        assertTrue(accepted.contains("tap to review"))
    }

    @Test
    fun every_state_is_distinct_text_not_colour_alone() {
        val states = listOf(
            ModelDownloads.State.Idle,
            ModelDownloads.State.Queued,
            ModelDownloads.State.Running(ModelDownloader.Phase.DOWNLOAD, 0L, -1L),
            ModelDownloads.State.Running(ModelDownloader.Phase.WAITING_NETWORK, 0L, -1L),
            ModelDownloads.State.Running(ModelDownloader.Phase.VERIFY, 0L, 1L),
            ModelDownloads.State.Running(ModelDownloader.Phase.UNPACK, 0L, 1L),
            ModelDownloads.State.Paused(1L, 2L, null),
            ModelDownloads.State.Paused(1L, 2L, "sha256 mismatch"),
            ModelDownloads.State.Installed
        )
        val labels = states.map { row(it).state }
        assertEquals("every state must read differently", labels.size, labels.toSet().size)
        assertTrue(labels.none { it.isBlank() })
    }
}
