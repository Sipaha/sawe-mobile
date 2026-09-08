package ru.sipaha.sawe.app.ui.solutions

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ru.sipaha.sawe.app.vm.PendingUploadProgress
import ru.sipaha.sawe.core.DisplayState
import ru.sipaha.sawe.core.EntryRoleDto
import ru.sipaha.sawe.core.EntrySummary

/**
 * The per-bubble delivery badge, and specifically the parked-offline state
 * (N-07).
 *
 * A send made while the wire is down goes into the durable queue and can sit
 * there for its whole 24-hour TTL. It used to render as `Sending` (or, if the
 * last-known session state said the agent was busy, `Queued`) — both of which
 * tell the user something is happening. Nothing is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UserBubbleStatusTest {

    private fun userEntry(csid: Long? = 42L) =
        EntrySummary(role = EntryRoleDto.User, preview = "ping", clientSendId = csid)

    private fun statusOf(
        entry: EntrySummary = userEntry(),
        isOptimistic: Boolean = true,
        isServerQueued: Boolean = false,
        pendingUploads: Map<Long, PendingUploadProgress> = emptyMap(),
        sessionDisplayState: DisplayState = DisplayState.Idle,
        offline: Boolean = false,
    ) = userBubbleStatusFor(
        entry = entry,
        isOptimistic = isOptimistic,
        isServerQueued = isServerQueued,
        pendingUploads = pendingUploads,
        sessionDisplayState = sessionDisplayState,
        offline = offline,
    )

    @Test
    fun an_optimistic_send_on_a_live_wire_is_sending() {
        assertEquals(UserBubbleStatus.Sending, statusOf())
    }

    @Test
    fun an_optimistic_send_while_the_agent_is_busy_is_queued() {
        assertEquals(
            UserBubbleStatus.Queued,
            statusOf(sessionDisplayState = DisplayState.Running),
        )
    }

    @Test
    fun an_optimistic_send_with_the_wire_down_says_it_is_waiting_for_connection() {
        assertEquals(UserBubbleStatus.WaitingForConnection, statusOf(offline = true))
    }

    @Test
    fun offline_beats_a_stale_agent_busy_reading() {
        // `Running` came from the last successful poll; with the socket gone
        // it says nothing about why this send hasn't moved.
        assertEquals(
            UserBubbleStatus.WaitingForConnection,
            statusOf(sessionDisplayState = DisplayState.Running, offline = true),
        )
    }

    @Test
    fun an_in_flight_upload_still_reports_its_progress_while_offline() {
        // The upload's own Paused/Uploading badge is more specific than
        // "waiting for connection" and already tells the truth.
        val status = statusOf(
            pendingUploads = mapOf(
                42L to PendingUploadProgress(
                    sentBytes = 1_024L,
                    totalBytes = 4_096L,
                    status = PendingUploadProgress.Status.Paused,
                ),
            ),
            offline = true,
        )
        assertEquals(UserBubbleStatus.Uploading(1_024L, 4_096L, paused = true), status)
    }

    @Test
    fun a_server_side_queue_bundle_is_queued_regardless_of_the_wire() {
        // The server already has it; our socket's health doesn't change that.
        assertEquals(
            UserBubbleStatus.Queued,
            statusOf(isOptimistic = false, isServerQueued = true, offline = true),
        )
    }

    @Test
    fun a_server_echoed_entry_is_delivered_and_a_historic_one_is_bare() {
        assertEquals(UserBubbleStatus.Delivered, statusOf(isOptimistic = false))
        assertEquals(
            UserBubbleStatus.None,
            statusOf(entry = userEntry(csid = null), isOptimistic = false),
        )
    }

    @Test
    fun non_user_roles_never_carry_a_badge() {
        val assistant = EntrySummary(role = EntryRoleDto.Assistant, preview = "pong")
        assertEquals(UserBubbleStatus.None, statusOf(entry = assistant, offline = true))
    }
}
