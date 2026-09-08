package ru.sipaha.sawe.app.vm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure-JVM coverage for the deferred-send stall guard
 * ([trackUploadProgress] / [isUploadStalled]).
 *
 * The bug this pins (N-04): the guard was a wall-clock
 * `withTimeoutOrNull(5 min)` around the whole wait for one attachment, so a
 * large file that was uploading perfectly well tripped it — and the failure
 * path then dropped the optimistic bubble, forgot the upload and cleared the
 * composer, losing both the photo and the typed message.
 */
class UploadStallTimeoutTest {

    private val stall = 5L * 60_000L

    @Test
    fun `a transfer that keeps making progress never stalls, however long it runs`() {
        var w = UploadProgressWatermark(lastProgressAtMs = 0L)
        var now = 0L
        var sent = 0L
        // 30 minutes of steady 1 MB/min progress — far past any wall clock cap.
        repeat(30) {
            now += 60_000L
            sent += 1_000_000L
            w = trackUploadProgress(w, sent, now)
            assertFalse(isUploadStalled(w, now, stall), "still moving at t=$now")
        }
        assertEquals(sent, w.sentBytes)
    }

    @Test
    fun `silence for the whole window stalls`() {
        var w = UploadProgressWatermark(lastProgressAtMs = 0L)
        w = trackUploadProgress(w, sentBytes = 4_096L, nowMs = 1_000L)
        assertFalse(isUploadStalled(w, nowMs = 1_000L + stall - 1, stallTimeoutMs = stall))
        assertTrue(isUploadStalled(w, nowMs = 1_000L + stall, stallTimeoutMs = stall))
    }

    @Test
    fun `a paused-resumed cycle resets the deadline`() {
        var w = UploadProgressWatermark(lastProgressAtMs = 0L)
        w = trackUploadProgress(w, sentBytes = 1_000L, nowMs = 10_000L)
        // Four minutes of Paused reporting the same confirmed offset...
        w = trackUploadProgress(w, sentBytes = 1_000L, nowMs = 250_000L)
        assertEquals(10_000L, w.lastProgressAtMs, "a repeated offset is not progress")
        // ...then the transfer resumes just inside the window.
        w = trackUploadProgress(w, sentBytes = 2_000L, nowMs = 290_000L)
        assertFalse(isUploadStalled(w, nowMs = 290_000L, stallTimeoutMs = stall))
        assertEquals(290_000L, w.lastProgressAtMs)
    }

    @Test
    fun `a backwards or zero byte count cannot extend the deadline`() {
        // Failed reports 0 sent; a re-seek after a rejected chunk can report a
        // lower confirmed offset. Neither is progress.
        var w = UploadProgressWatermark(lastProgressAtMs = 0L)
        w = trackUploadProgress(w, sentBytes = 5_000L, nowMs = 1_000L)
        w = trackUploadProgress(w, sentBytes = 0L, nowMs = 2_000L)
        w = trackUploadProgress(w, sentBytes = 4_000L, nowMs = 3_000L)
        assertEquals(1_000L, w.lastProgressAtMs)
        assertEquals(5_000L, w.sentBytes)
    }

    @Test
    fun `the very first observation counts as progress`() {
        val w = trackUploadProgress(
            UploadProgressWatermark(lastProgressAtMs = 0L),
            sentBytes = 0L,
            nowMs = 7_000L,
        )
        assertEquals(7_000L, w.lastProgressAtMs, "0 > the -1 sentinel, so Queued starts the clock")
    }
}
