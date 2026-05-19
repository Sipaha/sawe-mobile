package ru.sipaha.spkremote.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * JVM tests for [mergeSessionHistory] — the pure-fn cache-vs-fetch
 * resolver that drives `SessionHistoryRepository` integration in `:app`.
 */
class SessionHistoryMergeTest {

    private fun entry(index: Int, role: String = "assistant", preview: String = "p$index"): EntrySummary =
        EntrySummary(role = role, preview = preview, index = index)

    private fun result(
        entries: List<EntrySummary>,
        totalCount: Int,
        id: String = "s1",
    ): GetSessionResult = GetSessionResult(
        id = id,
        solutionId = "sol",
        agentId = "agent",
        title = "t",
        state = "Idle",
        createdAt = 0L,
        lastActivityAt = 0L,
        entries = entries,
        totalCount = totalCount,
    )

    @Test
    fun `empty cache returns FullReplace`() {
        val fetched = result(listOf(entry(0), entry(1)), totalCount = 2)

        val outcome = mergeSessionHistory(
            cachedEntries = emptyList(),
            cachedLastIndex = null,
            cachedTotalCount = -1,
            fetched = fetched,
            afterIndexHint = null,
        )

        assertTrue(outcome is MergeOutcome.FullReplace)
        outcome as MergeOutcome.FullReplace
        assertEquals(2, outcome.entries.size)
        assertEquals(2, outcome.newTotalCount)
    }

    @Test
    fun `non-empty cache plus successful diff fetch returns Appended`() {
        val cached = listOf(entry(0), entry(1), entry(2))
        val fetched = result(listOf(entry(3), entry(4)), totalCount = 5)

        val outcome = mergeSessionHistory(
            cachedEntries = cached,
            cachedLastIndex = 2,
            cachedTotalCount = 3,
            fetched = fetched,
            afterIndexHint = 2,
        )

        assertTrue(outcome is MergeOutcome.Appended)
        outcome as MergeOutcome.Appended
        assertEquals(2, outcome.appended.size)
        assertEquals(5, outcome.mergedEntries.size)
        assertEquals(5, outcome.newTotalCount)
        assertEquals(listOf(0, 1, 2, 3, 4), outcome.mergedEntries.map { it.index })
    }

    @Test
    fun `diff fetch returning zero entries when caller hint matches lastIndex is benign Appended`() {
        val cached = listOf(entry(0), entry(1))
        // Server reports the same totalCount as before; no new entries past after_index.
        val fetched = result(emptyList(), totalCount = 2)

        val outcome = mergeSessionHistory(
            cachedEntries = cached,
            cachedLastIndex = 1,
            cachedTotalCount = 2,
            fetched = fetched,
            afterIndexHint = 1,
        )

        assertTrue(outcome is MergeOutcome.Appended)
        outcome as MergeOutcome.Appended
        assertEquals(0, outcome.appended.size)
        assertEquals(cached, outcome.mergedEntries)
        assertEquals(2, outcome.newTotalCount)
    }

    @Test
    fun `gap when server total moved beyond cached + fetched yields GapDetected`() {
        val cached = listOf(entry(0), entry(1))
        // Server reports totalCount=10 but only returned 2 entries past after_index=1.
        // 2 (cached) + 2 (returned) = 4 != 10 → gap.
        val fetched = result(listOf(entry(2), entry(3)), totalCount = 10)

        val outcome = mergeSessionHistory(
            cachedEntries = cached,
            cachedLastIndex = 1,
            cachedTotalCount = 2,
            fetched = fetched,
            afterIndexHint = 1,
        )

        assertTrue(outcome is MergeOutcome.GapDetected)
    }

    @Test
    fun `full fetch on top of non-empty cache returns FullReplace`() {
        val cached = listOf(entry(0), entry(1))
        val fetched = result(listOf(entry(0), entry(1), entry(2)), totalCount = 3)

        val outcome = mergeSessionHistory(
            cachedEntries = cached,
            cachedLastIndex = 1,
            cachedTotalCount = 2,
            fetched = fetched,
            afterIndexHint = null,
        )

        assertTrue(outcome is MergeOutcome.FullReplace)
        outcome as MergeOutcome.FullReplace
        assertEquals(3, outcome.entries.size)
        assertEquals(3, outcome.newTotalCount)
    }

    @Test
    fun `pre-R6e server with totalCount sentinel still Appends without spurious gap`() {
        val cached = listOf(entry(0))
        // Pre-R-6e server: totalCount = -1 sentinel. Trust the diff.
        val fetched = result(listOf(entry(1), entry(2)), totalCount = -1)

        val outcome = mergeSessionHistory(
            cachedEntries = cached,
            cachedLastIndex = 0,
            cachedTotalCount = 1,
            fetched = fetched,
            afterIndexHint = 0,
        )

        assertTrue(outcome is MergeOutcome.Appended)
        outcome as MergeOutcome.Appended
        assertEquals(2, outcome.appended.size)
        assertEquals(3, outcome.mergedEntries.size)
    }
}
