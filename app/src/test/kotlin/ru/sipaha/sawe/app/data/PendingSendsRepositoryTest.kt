package ru.sipaha.sawe.app.data

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.sipaha.sawe.app.data.PersistedPendingSend.Companion.UNKNOWN_ENQUEUED_AT

/**
 * N-03 — a pending-send marker is only removed by the live send coroutine
 * or by reconcile spotting the csid in a loaded page. After a process kill
 * neither happens, so the marker re-materialises a "Sending" bubble on
 * every `openSession`, forever.
 *
 * The fix is a GC keyed on "is this csid still in the offline queue", with
 * an age backstop that needs the new `enqueued_at` field. These cases cover
 * the enumeration policy ([PendingSendsRepository.selectOrphans]) and the
 * on-disk format compatibility of the new field; the prefs I/O around them
 * needs a real Android Keystore and is not reachable from a JVM test (see
 * [SessionHistoryRepositoryTest]'s note).
 */
class PendingSendsRepositoryTest {

    /** Mirrors `PendingSendsRepository.JSON` (which is private). */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private fun record(
        csid: Long,
        enqueuedAtMs: Long = 1_000L,
        attachments: List<PersistedPendingAttachment> = emptyList(),
    ) = PersistedPendingSend(
        csid = csid,
        localId = csid,
        sessionId = "s1",
        text = "hello",
        attachments = attachments,
        enqueuedAtMs = enqueuedAtMs,
    )

    private fun photo(localKey: String = "u-1") = PersistedPendingAttachment(
        localKey = localKey,
        displayName = "IMG_0001.jpg",
        mime = "image/jpeg",
    )

    /**
     * The cold-start sweep exactly as `resumeDeferredSendsFromDiskOnIo`
     * performs it: `gcOrphans` runs *before* `list`, and both in-memory
     * registries it would draw [inFlightCsids] from are still empty because
     * this very function is what populates them. Returns what `list()` would
     * hand back afterwards.
     */
    private fun survivorsOfColdStart(
        onDisk: List<PersistedPendingSend>,
        queuedCsids: Set<Long> = emptySet(),
        nowMs: Long = 2_000L,
    ): List<PersistedPendingSend> {
        val swept = PendingSendsRepository.selectOrphans(
            records = onDisk,
            liveCsids = queuedCsids,
            inFlightCsids = emptySet(),
            nowMs = nowMs,
        ).toSet()
        return onDisk.filterNot { it.csid in swept }
    }

    // --- format compatibility -------------------------------------------

    // A marker written by the shipped build has no `enqueued_at` key at all.
    // It must decode (not crash) and land on the documented sentinel.
    @Test
    fun `legacy JSON without enqueued_at decodes to the unknown sentinel`() {
        val legacyJson = """
            {
              "csid": 42,
              "local_id": 7,
              "session_id": "s1",
              "text": "hello",
              "attachments": []
            }
        """.trimIndent()

        val decoded = json.decodeFromString(PersistedPendingSend.serializer(), legacyJson)

        assertEquals(42L, decoded.csid)
        assertEquals(UNKNOWN_ENQUEUED_AT, decoded.enqueuedAtMs)
    }

    // The new field round-trips under the repository's encodeDefaults=false
    // config: a real timestamp is persisted, the sentinel is omitted.
    @Test
    fun `enqueued_at round-trips and the sentinel stays out of the blob`() {
        val stamped = json.encodeToString(PersistedPendingSend.serializer(), record(1, enqueuedAtMs = 555L))
        assertTrue(stamped.contains("\"enqueued_at\":555"), "got: $stamped")
        assertEquals(555L, json.decodeFromString(PersistedPendingSend.serializer(), stamped).enqueuedAtMs)

        val unstamped = json.encodeToString(
            PersistedPendingSend.serializer(),
            record(1, enqueuedAtMs = UNKNOWN_ENQUEUED_AT),
        )
        assertFalse(unstamped.contains("enqueued_at"), "got: $unstamped")
    }

    // A legacy record must survive the age check — an older build simply
    // didn't stamp it, which says nothing about whether the send is live.
    @Test
    fun `legacy record backed by the queue is never dropped for unknown age`() {
        val legacy = record(csid = 1, enqueuedAtMs = UNKNOWN_ENQUEUED_AT)

        val orphans = PendingSendsRepository.selectOrphans(
            records = listOf(legacy),
            liveCsids = setOf(1L),
            nowMs = Long.MAX_VALUE / 2,
        )

        assertTrue(orphans.isEmpty(), "unknown age must never expire a record on its own")
    }

    // --- GC enumeration --------------------------------------------------

    // The core N-03 case: the message left the offline queue (sent, or
    // TTL-bounced) but the marker stayed behind. It is an orphan.
    @Test
    fun `marker whose message is no longer queued is an orphan`() {
        val orphans = PendingSendsRepository.selectOrphans(
            records = listOf(record(1), record(2), record(3)),
            liveCsids = setOf(2L),
            nowMs = 2_000L,
        )

        assertEquals(listOf(1L, 3L), orphans)
    }

    // A send that is still in flight in THIS process has no queue entry yet
    // (it hasn't bounced offline). Sweeping it would delete a live bubble.
    // NOTE: this only covers a warm sweep. At cold start the caller cannot
    // populate inFlightCsids at all — see the attachment cases below, which
    // are the ones that pin the regression.
    @Test
    fun `in-flight csid is protected even without a queue entry`() {
        val orphans = PendingSendsRepository.selectOrphans(
            records = listOf(record(1), record(2)),
            liveCsids = emptySet(),
            inFlightCsids = setOf(2L),
            nowMs = 2_000L,
        )

        assertEquals(listOf(1L), orphans)
    }

    // --- attachment-bearing markers: the deferred-send survival case -------

    // The regression this store exists to prevent. A deferred send is NEVER
    // in the offline queue (runDeferredSend calls queueCall only once every
    // upload is Done, and clears `attachments` right before it does), and at
    // cold start it cannot be in inFlightCsids either. Judging it by queue
    // membership deleted 100 % of them on every launch.
    @Test
    fun `attachment-bearing marker survives a cold start with an empty queue`() {
        val deferred = record(csid = 1, attachments = listOf(photo()))

        val orphans = PendingSendsRepository.selectOrphans(
            records = listOf(deferred),
            liveCsids = emptySet(),
            inFlightCsids = emptySet(),
            nowMs = 2_000L,
        )

        assertTrue(
            orphans.isEmpty(),
            "an uploading deferred send has no queue entry by construction; sweeping it loses the message",
        )
    }

    // End-to-end ordering of the real cold-start call site: gcOrphans is
    // awaited BEFORE list(), so if the sweep is wrong the resume loop reads
    // an empty list and never re-spawns runDeferredSend.
    @Test
    fun `cold start keeps the deferred send and still drops the text phantom`() {
        val deferred = record(csid = 1, attachments = listOf(photo()))
        val textPhantom = record(csid = 2)

        val survivors = survivorsOfColdStart(onDisk = listOf(deferred, textPhantom))

        assertEquals(listOf(1L), survivors.map { it.csid })
        assertEquals(listOf(photo()), survivors.single().attachments)
    }

    // A legacy attachment record (written before enqueued_at existed) has no
    // age to judge either, so it must be kept rather than deleted blind —
    // runDeferredSend stamps it on its first terminal.
    @Test
    fun `legacy attachment-bearing marker survives a cold start`() {
        val legacy = record(
            csid = 1,
            enqueuedAtMs = UNKNOWN_ENQUEUED_AT,
            attachments = listOf(photo()),
        )

        val survivors = survivorsOfColdStart(onDisk = listOf(legacy), nowMs = Long.MAX_VALUE / 2)

        assertEquals(listOf(1L), survivors.map { it.csid })
    }

    // The exemption is not unconditional: uploads that genuinely died leave a
    // marker that the TTL still reaps, so it cannot accumulate forever.
    @Test
    fun `attachment-bearing marker past the TTL is still swept`() {
        val abandoned = record(csid = 1, enqueuedAtMs = 1_000L, attachments = listOf(photo()))

        val survivors = survivorsOfColdStart(
            onDisk = listOf(abandoned),
            nowMs = 1_000L + PendingSendsRepository.MARKER_TTL_MS + 1,
        )

        assertTrue(survivors.isEmpty())
    }

    // The age backstop: even a still-queued marker past the queue's own TTL
    // can no longer resolve, so it goes.
    @Test
    fun `queued marker older than the TTL is swept anyway`() {
        val stale = record(csid = 1, enqueuedAtMs = 1_000L)
        val recent = record(csid = 2, enqueuedAtMs = 500_000L)

        val orphans = PendingSendsRepository.selectOrphans(
            records = listOf(stale, recent),
            liveCsids = setOf(1L, 2L),
            nowMs = 1_000L + PendingSendsRepository.MARKER_TTL_MS + 1,
            ttlMs = PendingSendsRepository.MARKER_TTL_MS,
        )

        // TTL expiry alone is enough to sweep a marker that IS still backed
        // by the queue; the one enqueued later is still within the window.
        assertEquals(listOf(1L), orphans)
    }

    // Exactly at the TTL boundary the marker survives (strictly-greater
    // comparison), so a GC pass racing the queue's own expiry doesn't
    // double-report.
    @Test
    fun `marker exactly at the TTL boundary survives`() {
        val orphans = PendingSendsRepository.selectOrphans(
            records = listOf(record(csid = 1, enqueuedAtMs = 1_000L)),
            liveCsids = setOf(1L),
            nowMs = 1_000L + PendingSendsRepository.MARKER_TTL_MS,
            ttlMs = PendingSendsRepository.MARKER_TTL_MS,
        )

        assertTrue(orphans.isEmpty())
    }

    // Nothing to do is the common case and must not produce a write.
    @Test
    fun `fully-backed marker set yields no removals`() {
        val orphans = PendingSendsRepository.selectOrphans(
            records = listOf(record(1), record(2)),
            liveCsids = setOf(1L, 2L),
            nowMs = 2_000L,
        )

        assertTrue(orphans.isEmpty())
    }
}
