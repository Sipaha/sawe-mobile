package ru.sipaha.sawe.app.vm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import ru.sipaha.sawe.app.data.PersistedPendingSend
import ru.sipaha.sawe.core.QueueCancelledException

/**
 * Pure-JVM coverage for the cancel-a-parked-send decision logic (N-07).
 *
 * The gap this closes: a send made while the wire was down went into the
 * durable queue and could sit there for its whole 24-hour TTL with no way for
 * the user to take it back, edit it, or throw it away. The affordance that
 * fixes it has two halves that can be got wrong in opposite directions —
 * cancelling something that has already gone out (the server has no
 * `spk_client_send_id` de-duplication, so a "cancelled" message that was in
 * fact delivered cannot be un-sent), and losing the user's text on the way.
 * The functions here are the two decisions behind those halves.
 */
class QueuedSendCancelTest {

    private fun marker(
        csid: Long,
        sessionId: String = "sess-1",
        text: String? = "run the tests",
    ) = PersistedPendingSend(
        csid = csid,
        localId = csid,
        sessionId = sessionId,
        text = text,
        attachments = emptyList(),
    )

    // ---- queueIdForClientSendId -------------------------------------------

    @Test
    fun `a send is filed under an id the cancel tap can reproduce`() {
        // The whole affordance rests on this: `queueCall` otherwise mints a
        // random UUID, and an id nobody can reproduce is an id nobody can
        // cancel — least of all a process that did not queue the message.
        assertEquals("csid-1757000000123", queueIdForClientSendId(1_757_000_000_123L))
    }

    @Test
    fun `queue ids are per-message and cannot collide with an older build's UUIDs`() {
        assertNotEquals(queueIdForClientSendId(1L), queueIdForClientSendId(2L))
        // A UUID has 8 hex chars before its first dash; ours never can.
        assertEquals("csid", queueIdForClientSendId(7L).substringBefore('-'))
    }

    // ---- withdrawnSendDraft -----------------------------------------------

    @Test
    fun `a cancelled send hands its full text back, addressed to its own session`() {
        val markers = listOf(marker(csid = 1L, sessionId = "other"), marker(csid = 2L))
        assertEquals(
            BouncedDraft(sessionId = "sess-1", text = "run the tests"),
            withdrawnSendDraft(markers, csid = 2L),
        )
    }

    @Test
    fun `the text is taken from the marker, not the bubble preview`() {
        // The marker holds the FULL typed body; the optimistic bubble's
        // preview is clamped to ~200 chars. Restoring the preview would hand
        // back a truncated version of the user's own message.
        val body = "x".repeat(5_000)
        assertEquals(
            body,
            withdrawnSendDraft(listOf(marker(csid = 3L, text = body)), csid = 3L)?.text,
        )
    }

    @Test
    fun `an attachment-only send is still cancellable, it just has no text`() {
        assertNull(withdrawnSendDraft(listOf(marker(csid = 4L, text = null)), csid = 4L))
        assertNull(withdrawnSendDraft(listOf(marker(csid = 4L, text = "   ")), csid = 4L))
    }

    @Test
    fun `an unknown csid yields no draft rather than another message's text`() {
        assertNull(withdrawnSendDraft(listOf(marker(csid = 1L)), csid = 99L))
        assertNull(withdrawnSendDraft(emptyList(), csid = 1L))
    }

    // ---- the failure the withdraw produces --------------------------------

    @Test
    fun `a withdrawn send never bounces its text a second time`() {
        // `cancelQueued` fails the parked call with QueueCancelledException, so
        // the still-alive send coroutine runs its failure path for a message
        // that did not fail. The cancel path has already put the text in the
        // composer; treating this as a normal failure would append a second
        // copy of it.
        assertEquals(
            SendFailureAction.ClearOnly,
            classifySendFailure(QueueCancelledException(), alreadyBouncedByQueue = false),
        )
    }

    @Test
    fun `a cancel outranks every other reading of the same failure`() {
        // Even with the queue-bounce flag set (a TTL expiry racing the tap),
        // the answer must stay ClearOnly — never Bounce.
        assertEquals(
            SendFailureAction.ClearOnly,
            classifySendFailure(QueueCancelledException(), alreadyBouncedByQueue = true),
        )
    }

    @Test
    fun `the too-late notice does not claim the message was sent`() {
        // Nobody knows whether it was. The wording has to leave that open and
        // point at the transcript, which is where the truth shows up.
        val message = CANCEL_TOO_LATE_MESSAGE.lowercase()
        assertEquals(true, "cancel" in message)
        assertEquals(true, "check the chat" in message)
        assertEquals(false, "sent" in message)
    }
}
