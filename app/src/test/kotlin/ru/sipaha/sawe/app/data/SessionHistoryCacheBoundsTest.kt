package ru.sipaha.sawe.app.data

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.sipaha.sawe.core.EntryRoleDto
import ru.sipaha.sawe.core.EntrySummary

/**
 * N-56 / N-32 — the transcript cache had no bound: `entries` grew with the
 * session, SharedPreferences rewrites the whole file on every write, and
 * the prune sweep decrypted every cached transcript on the caller's thread
 * to answer a list refresh.
 *
 * These cases pin the pure halves of the fix: the per-session caps that
 * keep one write cheap ([SessionHistoryRepository.encodeCapped]), and the
 * index-driven selection that lets prune / the session cap decide what to
 * drop without touching a single transcript blob
 * ([SessionHistoryRepository.selectPrunable],
 * [SessionHistoryRepository.selectSessionsOverCap]).
 */
class SessionHistoryCacheBoundsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private fun entry(index: Int, bodyChars: Int = 8) = EntrySummary(
        role = EntryRoleDto.Assistant,
        preview = "e$index",
        index = index,
        markdown = "x".repeat(bodyChars),
    )

    private fun history(entries: List<EntrySummary>) = CachedSessionHistory(
        sessionId = "s1",
        solutionId = 1L,
        agentId = "agent1",
        entries = entries,
        lastIndex = entries.lastOrNull()?.index,
        totalCountAtLastWrite = entries.size,
        schemaVersion = CachedSessionHistory.CACHE_SCHEMA_VERSION,
    )

    // --- per-session caps -------------------------------------------------

    // Entry-count cap: an 800-entry transcript is stored as the newest 200.
    @Test
    fun `entry count is capped to the newest MAX_CACHED_ENTRIES`() {
        val full = history((0 until 800).map { entry(it) })

        val (capped, raw) = SessionHistoryRepository.encodeCapped(full)

        assertEquals(SessionHistoryRepository.MAX_CACHED_ENTRIES, capped.entries.size)
        assertEquals(799, capped.entries.last().index, "the newest entry must survive the trim")
        assertEquals(600, capped.entries.first().index, "the trim must come off the head")
        val decoded = json.decodeFromString(CachedSessionHistory.serializer(), raw)
        assertEquals(SessionHistoryRepository.MAX_CACHED_ENTRIES, decoded.entries.size)
    }

    // Below the cap nothing is touched — the common case must not re-encode
    // a different object than the caller handed over.
    @Test
    fun `a small transcript passes through unchanged`() {
        val small = history((0 until 10).map { entry(it) })

        val (capped, _) = SessionHistoryRepository.encodeCapped(small)

        assertEquals(small.entries, capped.entries)
        assertEquals(small.lastIndex, capped.lastIndex)
    }

    // Byte cap: 200 fat entries are still far too big for one prefs write,
    // so the encoder keeps halving the window until the blob fits.
    @Test
    fun `serialised blob is capped in bytes even when the entry count fits`() {
        val fat = history((0 until 200).map { entry(it, bodyChars = 4_000) })

        val (capped, raw) = SessionHistoryRepository.encodeCapped(fat)

        assertTrue(
            raw.length <= SessionHistoryRepository.MAX_BLOB_BYTES,
            "blob is ${raw.length} B, cap is ${SessionHistoryRepository.MAX_BLOB_BYTES}",
        )
        assertTrue(capped.entries.isNotEmpty(), "the cache must keep a usable window")
        assertEquals(199, capped.entries.last().index, "the newest entry must survive the byte trim")
    }

    // Degenerate case: one entry bigger than the whole cap. We keep it
    // rather than writing an empty, cursor-less cache.
    @Test
    fun `a single oversized entry is kept rather than producing an empty cache`() {
        val huge = history(listOf(entry(0, bodyChars = SessionHistoryRepository.MAX_BLOB_BYTES * 2)))

        val (capped, _) = SessionHistoryRepository.encodeCapped(huge)

        assertEquals(1, capped.entries.size)
    }

    // The delta cursor must survive capping — a trimmed head would otherwise
    // silently reset `after_index` and force a full refetch on every open.
    @Test
    fun `capping preserves the delta cursor fields`() {
        val full = history((0 until 500).map { entry(it) }).copy(epoch = 9, lastSeq = 77)

        val (capped, raw) = SessionHistoryRepository.encodeCapped(full)

        assertEquals(9L, capped.epoch)
        assertEquals(77L, capped.lastSeq)
        assertEquals(499, capped.lastIndex)
        val decoded = json.decodeFromString(CachedSessionHistory.serializer(), raw)
        assertEquals(77L, decoded.lastSeq)
        assertEquals(CachedSessionHistory.CACHE_SCHEMA_VERSION, decoded.schemaVersion)
    }

    // --- index-driven sweeps ---------------------------------------------

    private fun idx(solutionId: Long, updatedAtMs: Long) = HistoryIndexEntry(solutionId, updatedAtMs)

    // prune drops only sessions of the scoped solution that vanished from
    // list_sessions — and answers from the index, so no blob is decrypted.
    @Test
    fun `prune selection is scoped to one solution and to the missing sessions`() {
        val index = mapOf(
            "keep" to idx(solutionId = 1, updatedAtMs = 10),
            "gone" to idx(solutionId = 1, updatedAtMs = 10),
            "other-solution" to idx(solutionId = 2, updatedAtMs = 10),
        )

        val doomed = SessionHistoryRepository.selectPrunable(
            index = index,
            keepSessionIds = setOf("keep"),
            scopeSolutionId = 1L,
        )

        assertEquals(listOf("gone"), doomed)
    }

    // A refresh whose keep-set covers everything must produce no write at
    // all — that is what makes the throttled background sweep free.
    @Test
    fun `prune selection is empty when every cached session is still listed`() {
        val index = mapOf("a" to idx(1, 10), "b" to idx(1, 20))

        val doomed = SessionHistoryRepository.selectPrunable(index, setOf("a", "b"), 1L)

        assertTrue(doomed.isEmpty())
    }

    // Session-count cap: the cache cannot grow without bound across
    // sessions either. Least-recently-written go first.
    @Test
    fun `session count cap evicts the least recently written`() {
        val over = SessionHistoryRepository.MAX_CACHED_SESSIONS + 3
        val index = (0 until over).associate { "s$it" to idx(solutionId = 1, updatedAtMs = it.toLong()) }

        val evicted = SessionHistoryRepository.selectSessionsOverCap(index)

        assertEquals(listOf("s0", "s1", "s2"), evicted)
        assertEquals(
            SessionHistoryRepository.MAX_CACHED_SESSIONS,
            index.size - evicted.size,
        )
    }

    // At or below the cap nothing is evicted.
    @Test
    fun `session count cap is a no-op at the limit`() {
        val index = (0 until SessionHistoryRepository.MAX_CACHED_SESSIONS)
            .associate { "s$it" to idx(1, it.toLong()) }

        assertTrue(SessionHistoryRepository.selectSessionsOverCap(index).isEmpty())
    }
}
