package ru.sipaha.sawe.app.vm

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.sipaha.sawe.core.ConnectFailure
import ru.sipaha.sawe.core.FrameTooLargeException
import ru.sipaha.sawe.core.MessageRejectedException
import ru.sipaha.sawe.core.QueueTtlException
import ru.sipaha.sawe.core.RemoteClient
import ru.sipaha.sawe.core.TransportLostException

/**
 * Whether a failed deferred send keeps its uploaded attachments staged
 * ([shouldKeepAttachmentsOnSendFailure]).
 *
 * The bug this pins: the deferred send path released the attachments on EVERY
 * failure, on the reasoning that "the uploads finished — the handles are
 * spent". They are not. The server consumes an upload record in
 * `resolve_upload_handles`, which runs inside a SUCCESSFUL
 * `send_message_blocks` — so when the send fails the tmp file is still on the
 * server for its full TTL and the staged copy is still on the phone.
 * `forget(releaseServerSlot = true)` then deleted both, and a user who
 * attached a 4 MB photo on LTE and lost the response had to re-pick and
 * re-upload the whole thing. On a link flaky enough to lose a response, that
 * is a loop — and it is the same class of loss the text bounce exists to
 * prevent, so it must not stand.
 */
class AttachmentRetentionTest {

    @Test
    fun `a lost response keeps the photo — delivery is unknown, the bytes are not`() {
        assertTrue(
            shouldKeepAttachmentsOnSendFailure(TransportLostException()),
            "the socket died in the RTT window; the server never resolved the " +
                "handles, so the tmp file and the staged copy both still exist",
        )
    }

    @Test
    fun `a client closed without queueing keeps the photo`() {
        assertTrue(
            shouldKeepAttachmentsOnSendFailure(RemoteClient.ClosedException.NotQueued()),
        )
    }

    @Test
    fun `an editor-link error envelope keeps the photo`() {
        // Includes the desktop proxy's own 30 s CALL_TIMEOUT, where the send
        // may even have been applied.
        assertTrue(shouldKeepAttachmentsOnSendFailure(SendEnvelopeException("opening local MCP proxy: …")))
    }

    @Test
    fun `an unrecognised failure keeps the photo`() {
        assertTrue(
            shouldKeepAttachmentsOnSendFailure(IllegalStateException("boom")),
            "the default has to be the one that cannot destroy user data",
        )
    }

    @Test
    fun `a definitive refusal releases the slot`() {
        // Retrying this exact payload cannot work, and the server only has
        // four upload slots per session — holding one for an hour for a send
        // that will never happen blocks the next attachment.
        assertFalse(
            shouldKeepAttachmentsOnSendFailure(SendRejectedByServerException("session not found")),
        )
        assertFalse(shouldKeepAttachmentsOnSendFailure(FrameTooLargeException(1_000_000)))
        assertFalse(
            shouldKeepAttachmentsOnSendFailure(
                MessageRejectedException(ConnectFailure.ServerClosed(1009, "message too big")),
            ),
        )
    }

    @Test
    fun `a message that sat in the queue for a day releases the slot`() {
        assertFalse(
            shouldKeepAttachmentsOnSendFailure(QueueTtlException()),
            "the queue TTL is 24 h and the server's upload slot expires in one",
        )
    }
}
