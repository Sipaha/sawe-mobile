package ru.sipaha.sawe.app.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import ru.sipaha.sawe.app.data.CachedSessionHistory.Companion.CACHE_SCHEMA_VERSION

/**
 * Tests for [CachedSessionHistory] schema versioning and [SessionHistoryRepository.gateBySchema].
 *
 * Note on EncryptedSharedPreferences: the full disk round-trip through
 * [SessionHistoryRepository.load] / [SessionHistoryRepository.save] requires a real Android
 * Keystore (MasterKey → EncryptedSharedPreferences), which Robolectric's shadow does not
 * emulate. The schema-gate logic is therefore tested via the extracted
 * [SessionHistoryRepository.gateBySchema] helper, which is the seam that enforces the
 * legacy-cache-returns-null contract. Manual verification of the full encrypted prefs
 * path (write v1 blob → load returns null + key evicted) is needed on a real device or
 * instrumented test.
 */
class SessionHistoryRepositoryTest {

    private fun minimalHistory(
        sessionId: String = "s1",
        schemaVersion: Int = CACHE_SCHEMA_VERSION,
        epoch: Long = 0,
        lastSeq: Long = 0,
    ) = CachedSessionHistory(
        sessionId = sessionId,
        solutionId = "sol1",
        agentId = "agent1",
        entries = emptyList(),
        lastIndex = null,
        totalCountAtLastWrite = 0,
        schemaVersion = schemaVersion,
        epoch = epoch,
        lastSeq = lastSeq,
    )

    // Test 1: Round-trip with cursor — a CachedSessionHistory with epoch=2, lastSeq=17
    // and schemaVersion=2 survives gateBySchema with the cursor values intact.
    @Test
    fun `round-trip with cursor preserves epoch and lastSeq`() {
        val history = minimalHistory(epoch = 2, lastSeq = 17, schemaVersion = CACHE_SCHEMA_VERSION)

        val result = SessionHistoryRepository.gateBySchema(history)

        assertNotNull(result)
        assertEquals(2L, result!!.epoch)
        assertEquals(17L, result.lastSeq)
    }

    // Test 2: Legacy v1 cache forces full load — gateBySchema returns null for schemaVersion=1.
    @Test
    fun `legacy v1 cache is rejected by gate and returns null`() {
        val legacyHistory = minimalHistory(schemaVersion = 1)

        val result = SessionHistoryRepository.gateBySchema(legacyHistory)

        assertNull(result)
    }

    // Test 3: New writes stamp version 2 — a CachedSessionHistory built without specifying
    // schemaVersion defaults to CACHE_SCHEMA_VERSION (2) and passes the gate.
    @Test
    fun `new CachedSessionHistory defaults to schema version 2 and passes gate`() {
        val history = CachedSessionHistory(
            sessionId = "s2",
            solutionId = "sol1",
            agentId = "agent1",
            entries = emptyList(),
            lastIndex = null,
            totalCountAtLastWrite = 0,
            // schemaVersion not specified — should default to CACHE_SCHEMA_VERSION
        )

        assertEquals(CACHE_SCHEMA_VERSION, history.schemaVersion)
        val gated = SessionHistoryRepository.gateBySchema(history)
        assertNotNull(gated)
    }

    // Null input is safely handled.
    @Test
    fun `gateBySchema returns null for null input`() {
        assertNull(SessionHistoryRepository.gateBySchema(null))
    }
}
