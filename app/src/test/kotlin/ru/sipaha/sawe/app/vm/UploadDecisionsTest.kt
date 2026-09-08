package ru.sipaha.sawe.app.vm

import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.CancellationException as JavaCancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.sipaha.sawe.core.ConnectFailure
import ru.sipaha.sawe.core.NotConnectedException
import ru.sipaha.sawe.core.UPLOAD_CHUNK_PAYLOAD_BYTES

/**
 * Pure-JVM coverage of the upload state machine's decision logic
 * ([UploadDecisions]) — the half of `UploadManager` that decides whether
 * a failure is terminal, how long one chunk may take, how big the next
 * chunk should be, which uploads belong to the bound server and when a
 * chunk may be handed to the socket.
 *
 * Each test names the audit finding it pins down.
 */
class UploadDecisionsTest {

    // ---- N-40: only a server verdict may be terminal --------------------

    @Test
    fun `N-40 call timeout is a transport failure, not cancellation`() = runTest {
        // The 30s budget inside RemoteClient.call surfaces as a
        // TimeoutCancellationException, which IS a CancellationException.
        // Before the fix that was swallowed by runCatching and turned
        // into a terminal Failed even though every byte was on the server.
        val cause = captureTimeout()
        assertTrue(cause is TimeoutCancellationException)
        assertEquals(CallFailureKind.TRANSPORT, UploadDecisions.classifyCallFailure(cause))
    }

    @Test
    fun `N-40 job cancellation is re-thrown, never classified as a failure`() {
        assertEquals(
            CallFailureKind.CANCELLED,
            UploadDecisions.classifyCallFailure(kotlinx.coroutines.CancellationException("paused")),
        )
        // The java.util.concurrent alias kotlinx uses on the JVM.
        assertEquals(
            CallFailureKind.CANCELLED,
            UploadDecisions.classifyCallFailure(JavaCancellationException("paused")),
        )
    }

    @Test
    fun `N-40 a dropped socket is transport, a missing socket is not-connected`() {
        assertEquals(
            CallFailureKind.TRANSPORT,
            UploadDecisions.classifyCallFailure(IllegalStateException("connection lost")),
        )
        assertEquals(
            CallFailureKind.TRANSPORT,
            UploadDecisions.classifyCallFailure(IOException("Software caused connection abort")),
        )
        assertEquals(
            CallFailureKind.NOT_CONNECTED,
            UploadDecisions.classifyCallFailure(NotConnectedException(null)),
        )
        assertEquals(
            CallFailureKind.NOT_CONNECTED,
            UploadDecisions.classifyCallFailure(
                NotConnectedException(ConnectFailure.Unreachable("wifi down")),
            ),
        )
    }

    // ---- N-43: unknown_upload_id is a restart, not a failure ------------

    @Test
    fun `N-43 UP-6 every wording the server actually uses means the slot is gone`() {
        // These are the literal strings the desktop emits, not invented
        // ones — the first version of this test asserted against made-up
        // wording and so missed that upload_finish spells it with a
        // space, which made the whole re-init branch dead code.
        // uploads.rs: `format!("unknown_upload_id: {id}")`
        assertTrue(UploadDecisions.isExpiredSlot("unknown_upload_id: 17"))
        // upload.rs chunk rejection: `reason: "unknown_upload_id"`
        assertTrue(UploadDecisions.isExpiredSlot("unknown_upload_id"))
        // upload.rs upload_finish: `format!("finish: unknown upload_id {id}")`
        assertTrue(UploadDecisions.isExpiredSlot("finish: unknown upload_id 17"))
        // upload.rs upload_abort, same shape.
        assertTrue(UploadDecisions.isExpiredSlot("abort: unknown upload_id 17"))
        assertTrue(UploadDecisions.isExpiredSlot("upload not found"))

        assertFalse(UploadDecisions.isExpiredSlot("unsupported_mime"))
        assertFalse(UploadDecisions.isExpiredSlot("too_many_concurrent_uploads"))
        assertFalse(UploadDecisions.isExpiredSlot("duplicate_mismatch"))
    }

    // ---- UP-3: an upload with a dead driver must be rescued -------------

    @Test
    fun `UP-3 recovery keys off having no driver, not off the Paused label`() {
        val total = 3_000L
        val paused = UploadManager.State.Paused(500L, total, "no connection")
        val uploading = UploadManager.State.Uploading(500L, total)
        val queued = UploadManager.State.Queued(total)

        // A live driver is left alone whatever the label says.
        assertFalse(UploadDecisions.needsDriver(paused, jobActive = true))
        assertFalse(UploadDecisions.needsDriver(uploading, jobActive = true))

        // The designed resume point.
        assertTrue(UploadDecisions.needsDriver(paused, jobActive = false))
        // The lost-update casualty: a pump wrote Uploading just after
        // pauseAll published Paused, so the label says Uploading but the
        // job is dead. Before the fix nothing looked at this and the chip
        // froze forever.
        assertTrue(UploadDecisions.needsDriver(uploading, jobActive = false))
        assertTrue(UploadDecisions.needsDriver(queued, jobActive = false))

        // Terminal states are never revived behind the user's back.
        assertFalse(
            UploadDecisions.needsDriver(UploadManager.State.Done("h"), jobActive = false),
        )
        assertFalse(
            UploadDecisions.needsDriver(UploadManager.State.Failed("x"), jobActive = false),
        )
    }

    // ---- UP-1: a build with no staging must say so ----------------------

    @Test
    fun `UP-1 the unreadable-source copy names an unconfigured staging build`() {
        val configured = UploadDecisions.unreadableSourceReason(stagingConfigured = true)
        val missing = UploadDecisions.unreadableSourceReason(stagingConfigured = false)

        assertTrue(configured.contains("attach it again"))
        assertTrue(missing.contains("attach it again"))
        // The degraded build must not be able to masquerade as "the
        // user's file went away" — that silence is how the staging path
        // shipped as dead code.
        assertFalse(configured.contains("staging"))
        assertTrue(missing.contains("staging"))
    }

    @Test
    fun `N-43 uploads are scoped to the server they were created against`() {
        assertTrue(UploadDecisions.belongsToActiveServer("srv-a", "srv-a"))
        assertFalse(UploadDecisions.belongsToActiveServer("srv-a", "srv-b"))
        // Unknown on either side: nothing to contradict, treat as ours.
        assertTrue(UploadDecisions.belongsToActiveServer(null, "srv-b"))
        assertTrue(UploadDecisions.belongsToActiveServer("srv-a", null))
        assertTrue(UploadDecisions.belongsToActiveServer(null, null))
    }

    // ---- N-46: never publish a bogus offset -----------------------------

    @Test
    fun `N-46 a pause keeps the progress the UI already shows`() {
        val uploading = UploadManager.State.Uploading(sent = 1_200L, total = 3_000L)
        assertEquals(1_200L, UploadDecisions.resumeOffset(uploading, 0L, 3_000L))

        val paused = UploadManager.State.Paused(900L, 3_000L, "no connection")
        assertEquals(900L, UploadDecisions.resumeOffset(paused, 0L, 3_000L))
    }

    @Test
    fun `N-46 a pause with no live progress falls back to the confirmed offset`() {
        val queued = UploadManager.State.Queued(3_000L)
        assertEquals(1_500L, UploadDecisions.resumeOffset(queued, 1_500L, 3_000L))
        // The pre-fix bug: the wall-clock-seeded upload id was published
        // as a byte count, so a 3 MB attachment read "paused at 1.6 GB".
        // Any offset is now clamped into the file.
        assertEquals(3_000L, UploadDecisions.resumeOffset(queued, 1_757_284_913L, 3_000L))
        assertEquals(0L, UploadDecisions.resumeOffset(queued, -5L, 3_000L))
    }

    // ---- N-42: an unreadable source is the one local terminal failure ---

    @Test
    fun `N-42 a lapsed URI grant is terminal, a transient IO error is not`() {
        assertTrue(UploadDecisions.isUnreadableSource(SecurityException("Permission Denial")))
        assertTrue(UploadDecisions.isUnreadableSource(FileNotFoundException("no such file")))
        assertFalse(UploadDecisions.isUnreadableSource(IOException("read failed")))
        assertFalse(UploadDecisions.isUnreadableSource(IllegalStateException("could not skip")))
    }

    // ---- N-41: a slow link must not look like a broken one --------------

    @Test
    fun `N-41 the ack budget scales with the chunk size`() {
        // 256 KiB at the 32 kbps floor legitimately takes ~64s; the old
        // fixed 30s budget paused a healthy link mid-chunk.
        val full = UploadDecisions.ackTimeoutMs(UPLOAD_CHUNK_PAYLOAD_BYTES, null)
        assertEquals(64_000L, full)

        // A shrunk chunk falls back to the 30s floor.
        val small = UploadDecisions.ackTimeoutMs(UploadDecisions.MIN_CHUNK_PAYLOAD_BYTES, null)
        assertEquals(UploadDecisions.MIN_ACK_TIMEOUT_MS, small)

        assertTrue(small < full)
    }

    @Test
    fun `N-41 the ack budget also honours the RTT this upload measured`() {
        val measured = UploadDecisions.ackTimeoutMs(
            UploadDecisions.MIN_CHUNK_PAYLOAD_BYTES,
            smoothedAckRttMs = 40_000L,
        )
        assertEquals(120_000L, measured)

        // ...but never unbounded: past the ceiling the socket is dead.
        val absurd = UploadDecisions.ackTimeoutMs(UPLOAD_CHUNK_PAYLOAD_BYTES, 10_000_000L)
        assertEquals(UploadDecisions.MAX_ACK_TIMEOUT_MS, absurd)
    }

    @Test
    fun `N-41 the chunk size adapts to the measured ack RTT`() {
        val start = UPLOAD_CHUNK_PAYLOAD_BYTES

        // Slow link: one chunk holds the socket longer than the heartbeat
        // interval, so halve it.
        val slower = UploadDecisions.nextChunkBytes(start, smoothedAckRttMs = 9_000L)
        assertEquals(start / 2, slower)

        // Keep halving, but never below the floor.
        var size = start
        repeat(10) { size = UploadDecisions.nextChunkBytes(size, 9_000L) }
        assertEquals(UploadDecisions.MIN_CHUNK_PAYLOAD_BYTES, size)

        // Fast link: grow back, capped at the protocol chunk size.
        var grown = UploadDecisions.MIN_CHUNK_PAYLOAD_BYTES
        repeat(10) { grown = UploadDecisions.nextChunkBytes(grown, 200L) }
        assertEquals(UPLOAD_CHUNK_PAYLOAD_BYTES, grown)

        // In the comfortable band, leave it alone.
        assertEquals(start, UploadDecisions.nextChunkBytes(start, 3_000L))
        // No measurement yet — no change.
        assertEquals(start, UploadDecisions.nextChunkBytes(start, null))
    }

    @Test
    fun `N-41 smoothed RTT is history-weighted so one blip does not resize the chunk`() {
        assertEquals(1_000L, UploadDecisions.smoothAckRttMs(null, 1_000L))
        // One 9s sample against a 1s history stays well inside the band.
        val blip = UploadDecisions.smoothAckRttMs(1_000L, 9_000L)
        assertEquals(3_000L, blip)
        assertEquals(
            UPLOAD_CHUNK_PAYLOAD_BYTES,
            UploadDecisions.nextChunkBytes(UPLOAD_CHUNK_PAYLOAD_BYTES, blip),
        )
    }

    @Test
    fun `UP-10 an expired ack budget shrinks the chunk without poisoning the RTT`() {
        // The timeout is not a measurement. Feeding it into the EWMA made
        // the next budget 3x larger (30 -> 90 -> 135 -> 180s) while the
        // chunk was already at its floor, so a black-holed but Connected
        // socket ate the whole deferred-send stall guard.
        var chunk = UPLOAD_CHUNK_PAYLOAD_BYTES
        var budget = UploadDecisions.ackTimeoutMs(chunk, smoothedAckRttMs = null)
        repeat(4) {
            chunk = UploadDecisions.shrinkChunkBytes(chunk)
            val next = UploadDecisions.ackTimeoutMs(chunk, smoothedAckRttMs = null)
            assertTrue(next <= budget, "budget must not ratchet up: $next vs $budget")
            budget = next
        }
        assertEquals(UploadDecisions.MIN_CHUNK_PAYLOAD_BYTES, chunk)
        assertEquals(UploadDecisions.MIN_ACK_TIMEOUT_MS, budget)
    }

    @Test
    fun `N-41 a still-connected socket is never accused of being unreachable`() {
        val connected = UploadDecisions.ackTimeoutReason(connected = true)
        assertFalse(connected.contains("unreachable"))
        assertTrue(UploadDecisions.ackTimeoutReason(connected = false).contains("unreachable"))
    }

    // ---- N-45: keep the shared FIFO writer short ------------------------

    @Test
    fun `N-45 a chunk waits until the transport send queue has drained`() {
        assertTrue(UploadDecisions.canQueueChunk(queuedBytes = 0L))
        assertTrue(UploadDecisions.canQueueChunk(UploadDecisions.MAX_PENDING_WRITE_BYTES))
        // ~1 MiB of other uploads' chunks in front of the heartbeat ping
        // is exactly the head-of-line block that force-reconnects the app.
        assertFalse(UploadDecisions.canQueueChunk(1L * 1024 * 1024))
        assertFalse(UploadDecisions.canQueueChunk(UPLOAD_CHUNK_PAYLOAD_BYTES.toLong()))
        // The budget is well under one full chunk, so a queued chunk
        // always blocks the next one.
        assertTrue(UploadDecisions.MAX_PENDING_WRITE_BYTES < UPLOAD_CHUNK_PAYLOAD_BYTES)
    }

    // ---- rejected-chunk notification ------------------------------------

    @Test
    fun `upload_chunk_rejected decodes with and without an expected offset`() {
        val json = Json { ignoreUnknownKeys = true }

        val reseek = json.decodeFromString(
            UploadChunkRejectedPayload.serializer(),
            """{"upload_id":42,"offset":1024,"expected_offset":512,"reason":"out_of_order"}""",
        )
        assertEquals(42L, reseek.uploadId)
        assertEquals(1024L, reseek.offset)
        assertEquals(512L, reseek.expectedOffset)
        assertEquals("out_of_order", reseek.reason)

        val fatal = json.decodeFromString(
            UploadChunkRejectedPayload.serializer(),
            """{"upload_id":7,"offset":0,"reason":"overrun"}""",
        )
        assertNull(fatal.expectedOffset)

        val gone = json.decodeFromString(
            UploadChunkRejectedPayload.serializer(),
            """{"upload_id":7,"offset":0,"expected_offset":null,"reason":"unknown_upload_id"}""",
        )
        // Routed through the same re-init-from-zero path as a status miss.
        assertTrue(UploadDecisions.isExpiredSlot(gone.reason))
    }

    private suspend fun captureTimeout(): Throwable = try {
        withTimeout(1L) { kotlinx.coroutines.delay(1_000L) }
        error("withTimeout did not time out")
    } catch (e: TimeoutCancellationException) {
        e
    }
}
