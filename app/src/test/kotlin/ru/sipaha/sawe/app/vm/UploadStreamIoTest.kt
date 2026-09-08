package ru.sipaha.sawe.app.vm

import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pure-JVM coverage of [UploadStreamIo] — the stream plumbing behind
 * finding N-47 (a short `skip` and a short `read` used to strand an
 * upload in a Paused → resume → same-failure loop).
 */
class UploadStreamIoTest {

    /**
     * A content provider that dribbles: `skip` never moves more than
     * [step] bytes at a time, and one call in three refuses to move at
     * all (pipe-backed providers behave exactly like this).
     */
    private class DribblingStream(
        source: ByteArray,
        private val step: Long,
    ) : FilterInputStream(ByteArrayInputStream(source)) {
        private var skipCalls = 0

        override fun skip(n: Long): Long {
            skipCalls += 1
            if (skipCalls % 3 == 0) return 0L
            return super.skip(minOf(n, step))
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, minOf(len, step.toInt()))
    }

    private fun bytes(n: Int): ByteArray = ByteArray(n) { (it % 251).toByte() }

    @Test
    fun `N-47 a short skip is retried until the offset is reached`() {
        val data = bytes(10_000)
        val stream: InputStream = DribblingStream(data, step = 700L)

        assertTrue(UploadStreamIo.skipFully(stream, 4_096L))

        val next = ByteArray(16)
        assertEquals(16, UploadStreamIo.readFully(stream, next))
        assertArrayEquals(data.copyOfRange(4_096, 4_112), next)
    }

    @Test
    fun `N-47 a skip past the end of the source reports failure instead of looping`() {
        val stream = DribblingStream(bytes(1_000), step = 300L)
        // Before the fix this raised inside runCatching and became a
        // Paused state the watchdog retried forever.
        assertFalse(UploadStreamIo.skipFully(stream, 5_000L))
    }

    @Test
    fun `N-47 a short read is topped up so a chunk is never silently truncated`() {
        val data = bytes(4_096)
        val stream = DribblingStream(data, step = 300L)

        val buf = ByteArray(4_096)
        assertEquals(4_096, UploadStreamIo.readFully(stream, buf))
        assertArrayEquals(data, buf)
    }

    @Test
    fun `N-47 readFully reports the real length when the source ends early`() {
        val stream = DribblingStream(bytes(1_500), step = 400L)
        val buf = ByteArray(4_096)
        // The caller uses this to detect an over-reported picker size and
        // fail terminally rather than uploading a truncated file.
        assertEquals(1_500, UploadStreamIo.readFully(stream, buf))
        assertEquals(0, UploadStreamIo.readFully(stream, ByteArray(16)))
    }

    @Test
    fun `N-47 reads are clamped to the declared size so the server slot cannot overrun`() {
        // The chunk loop asks for min(chunkBytes, totalSize - offset);
        // an over-long source therefore never pushes extra bytes.
        val declaredTotal = 1_000L
        val offset = 900L
        val chunkBytes = 4_096
        val want = minOf(chunkBytes.toLong(), declaredTotal - offset).toInt()
        assertEquals(100, want)

        val stream = DribblingStream(bytes(4_096), step = 40L)
        val buf = ByteArray(want)
        assertEquals(100, UploadStreamIo.readFully(stream, buf))
    }
}
