package ru.sipaha.sawe.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [applySessionDelta] — the pure state-transition function
 * that applies a [GetSessionChangesResult] delta onto a [SessionDeltaState]
 * snapshot. All 9 cases are pure JVM; no Android or IO.
 */
class ApplySessionDeltaTest {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun assistantEntry(index: Int, preview: String = "e$index"): EntrySummary =
        EntrySummary(role = EntryRoleDto.Assistant, preview = preview, index = index)

    /**
     * Construct a minimal delta that touches only the fields under test.
     * `streams` is always present on the wire (v3), so it is a plain list
     * here too (defaults empty); `selectedStreamId` defaults to Main.
     */
    private fun delta(
        currentSeq: Long = 10L,
        totalCount: Int = 5,
        changedEntries: List<EntrySummary> = emptyList(),
        removedIndices: List<Int> = emptyList(),
        state: SessionStateDto? = null,
        pendingBundles: List<QueuedBundleSummary>? = null,
        streams: List<StreamDto> = emptyList(),
        selectedStreamId: StreamIdDto = StreamIdDto.Main,
    ) = GetSessionChangesResult(
        epoch = 1L,
        currentSeq = currentSeq,
        reset = false,
        totalCount = totalCount,
        changedEntries = changedEntries,
        removedIndices = removedIndices,
        state = state,
        pendingBundles = pendingBundles,
        streams = streams,
        selectedStreamId = selectedStreamId,
    )

    private fun bundle(preview: String) = QueuedBundleSummary(preview = preview)

    private fun stream(id: String) = StreamDto(
        id = StreamIdDto.Teammate(id),
        kind = StreamKindDto.TEAMMATE,
        label = id,
        state = StreamStateDto.Live,
        seq = 0L,
        totalCount = 0,
    )

    private fun baseState(
        entries: List<EntrySummary>,
        totalCount: Int,
        state: SessionStateDto = SessionStateDto.Idle,
        pendingBundles: List<QueuedBundleSummary> = emptyList(),
        streams: List<StreamDto> = emptyList(),
        currentSeq: Long = 5L,
    ) = SessionDeltaState(
        entries = entries,
        totalCount = totalCount,
        state = state,
        pendingBundles = pendingBundles,
        streams = streams,
        currentSeq = currentSeq,
    )

    // -------------------------------------------------------------------------
    // Case 1: Upsert new tail entry
    // -------------------------------------------------------------------------

    @Test
    fun `case 1 - upsert new tail entry appends it sorted and advances seq and totalCount`() {
        val entries = (0..3).map { assistantEntry(it) }
        val current = baseState(entries = entries, totalCount = 4, currentSeq = 5L)
        val newEntry = assistantEntry(index = 4, preview = "newest")
        val result = applySessionDelta(
            current = current,
            delta = delta(
                currentSeq = 6L,
                totalCount = 5,
                changedEntries = listOf(newEntry),
            ),
        )

        assertEquals(5, result.entries.size)
        assertEquals(newEntry, result.entries.last())
        assertEquals(listOf(0, 1, 2, 3, 4), result.entries.map { it.index })
        assertEquals(6L, result.currentSeq)
        assertEquals(5, result.totalCount)
    }

    // -------------------------------------------------------------------------
    // Case 2: Upsert existing index in place — no growth, no duplicate
    // -------------------------------------------------------------------------

    @Test
    fun `case 2 - upsert existing index replaces slot in place without growing the list`() {
        val entries = listOf(
            assistantEntry(0, "old-0"),
            assistantEntry(1, "old-1"),
            assistantEntry(2, "old-2"),
        )
        val current = baseState(entries = entries, totalCount = 3)
        val updated = assistantEntry(index = 1, preview = "updated-1")
        val result = applySessionDelta(
            current = current,
            delta = delta(totalCount = 3, changedEntries = listOf(updated)),
        )

        assertEquals(3, result.entries.size)
        assertEquals("updated-1", result.entries[1].preview)
        assertEquals(1, result.entries[1].index)
        // Other entries untouched.
        assertEquals("old-0", result.entries[0].preview)
        assertEquals("old-2", result.entries[2].preview)
    }

    // -------------------------------------------------------------------------
    // Case 3: Tail-truncate shrink drops by COUNT from tail
    // -------------------------------------------------------------------------

    @Test
    fun `case 3 - tail truncate shrink drops by count from tail not by index cutoff`() {
        val entries = (0..4).map { assistantEntry(it) }
        val current = baseState(entries = entries, totalCount = 5)
        val result = applySessionDelta(
            current = current,
            delta = delta(totalCount = 3, changedEntries = emptyList()),
        )

        // shrink = 5 - 3 = 2; drop the last 2 by count → [0,1,2] remain
        assertEquals(3, result.entries.size)
        assertEquals(listOf(0, 1, 2), result.entries.map { it.index })
        assertEquals(3, result.totalCount)
    }

    // -------------------------------------------------------------------------
    // Case 4: null state/pendingBundles keep current; streams always replaces
    // -------------------------------------------------------------------------

    @Test
    fun `case 4 - null state and pendingBundles keep current while streams always replaces`() {
        val originalState = SessionStateDto.Running(startedAtMs = 1000L)
        val originalBundles = listOf(bundle("queued-1"))
        val originalStreams = listOf(stream("sa-1"))
        val current = baseState(
            entries = emptyList(),
            totalCount = 0,
            state = originalState,
            pendingBundles = originalBundles,
            streams = originalStreams,
        )
        val newStreams = listOf(stream("sa-2"))
        val result = applySessionDelta(
            current = current,
            delta = delta(
                totalCount = 0,
                state = null,
                pendingBundles = null,
                streams = newStreams,
            ),
        )

        assertEquals(originalState, result.state)
        assertEquals(originalBundles, result.pendingBundles)
        // streams is unconditional-replace (no null-vs-empty semantics).
        assertEquals(newStreams, result.streams)
    }

    // -------------------------------------------------------------------------
    // Case 5: present-empty pendingBundles replaces; streams replaces with empty
    // -------------------------------------------------------------------------

    @Test
    fun `case 5 - present-empty pendingBundles replaces and streams replaces with empty`() {
        val current = baseState(
            entries = emptyList(),
            totalCount = 0,
            pendingBundles = listOf(bundle("waiting")),
            streams = listOf(stream("sa-1")),
        )
        val result = applySessionDelta(
            current = current,
            delta = delta(
                totalCount = 0,
                pendingBundles = emptyList(),
                streams = emptyList(),
            ),
        )

        assertTrue(result.pendingBundles.isEmpty())
        assertTrue(result.streams.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Case 6: Section present-nonempty replaces
    // -------------------------------------------------------------------------

    @Test
    fun `case 6 - present-nonempty sections replace state pendingBundles and streams`() {
        val current = baseState(
            entries = emptyList(),
            totalCount = 0,
            state = SessionStateDto.Idle,
            pendingBundles = emptyList(),
            streams = emptyList(),
        )
        val newState = SessionStateDto.Running(startedAtMs = 2000L)
        val newBundles = listOf(bundle("new-bundle"))
        val result = applySessionDelta(
            current = current,
            delta = delta(
                totalCount = 0,
                state = newState,
                pendingBundles = newBundles,
                streams = listOf(stream("sa-new")),
            ),
        )

        assertEquals(newState, result.state)
        assertEquals(newBundles, result.pendingBundles)
        assertEquals(1, result.streams.size)
        assertEquals(StreamIdDto.Teammate("sa-new"), result.streams[0].id)
    }

    // -------------------------------------------------------------------------
    // Case 7: Sparse/filtered indices — drop-by-count not drop-by-index-cutoff
    // -------------------------------------------------------------------------

    @Test
    fun `case 7 - sparse filtered indices use drop-by-count not drop by absolute index`() {
        // A per-subagent-tab view holds entries at ABSOLUTE indices [10, 25, 40].
        // totalCount = 3 (filtered count, not max_index).
        val entries = listOf(
            assistantEntry(10, "e10"),
            assistantEntry(25, "e25"),
            assistantEntry(40, "e40"),
        )
        val current = baseState(entries = entries, totalCount = 3, currentSeq = 1L)

        // Step A: upsert index 55, totalCount becomes 4.
        val afterUpsert = applySessionDelta(
            current = current,
            delta = delta(
                currentSeq = 2L,
                totalCount = 4,
                changedEntries = listOf(assistantEntry(55, "e55")),
            ),
        )
        assertEquals(listOf(10, 25, 40, 55), afterUpsert.entries.map { it.index })
        assertEquals(4, afterUpsert.totalCount)

        // Step B: delta totalCount 2 (shrink 2) — drop LAST 2 by count.
        // If we dropped by "index >= totalCount" we'd wrongly drop indices >=2
        // (i.e. all of them, since every absolute index is ≥2). Drop-by-count
        // correctly removes the last 2 entries: [40, 55] → leaving [10, 25].
        val afterTruncate = applySessionDelta(
            current = afterUpsert,
            delta = delta(
                currentSeq = 3L,
                totalCount = 2,
                changedEntries = emptyList(),
            ),
        )
        assertEquals(listOf(10, 25), afterTruncate.entries.map { it.index },
            "drop-by-count must remove the last 2 entries, not those with index >= 2")
    }

    // -------------------------------------------------------------------------
    // Case 8: removedIndices drops the indicated entry
    // -------------------------------------------------------------------------

    @Test
    fun `case 8 - removedIndices drops the named entry and leaves others intact`() {
        val entries = (0..4).map { assistantEntry(it, "e$it") }
        val current = baseState(entries = entries, totalCount = 5)
        val result = applySessionDelta(
            current = current,
            delta = delta(
                totalCount = 5,
                removedIndices = listOf(2),
            ),
        )

        assertEquals(4, result.entries.size)
        assertEquals(listOf(0, 1, 3, 4), result.entries.map { it.index })
    }

    // -------------------------------------------------------------------------
    // Case 9: totalCount sentinel -1 suppresses shrink
    // -------------------------------------------------------------------------

    @Test
    fun `case 9 - unknown totalCount sentinel -1 suppresses shrink even when delta totalCount is small`() {
        val entries = (0..4).map { assistantEntry(it) }
        // current.totalCount = -1 → unknown; shrink must be 0 regardless of delta.
        val current = baseState(entries = entries, totalCount = -1)
        val result = applySessionDelta(
            current = current,
            delta = delta(totalCount = 1, changedEntries = emptyList()),
        )

        // No entries should be dropped despite delta.totalCount << entries.size.
        assertEquals(5, result.entries.size)
        assertEquals(listOf(0, 1, 2, 3, 4), result.entries.map { it.index })
    }
}
