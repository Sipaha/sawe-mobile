package ru.sipaha.sawe.app.data

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import ru.sipaha.sawe.app.data.CachedSessionHistory.Companion.CACHE_SCHEMA_VERSION

/**
 * Tests for [CachedSessionHistory] schema versioning and [SessionHistoryRepository.gateBySchema].
 *
 * The serialization round-trip cases use a [Json] instance mirroring the repository's private
 * config (`ignoreUnknownKeys = true`, `encodeDefaults = false`) so they exercise the exact
 * absent-key behaviour that a real on-disk blob would have. This is what catches the legacy-cache
 * gate bug that pure in-memory `schemaVersion = 1` objects miss (a `1` set explicitly never
 * survives a serialize→deserialize round trip under `encodeDefaults = false`).
 *
 * Note on EncryptedSharedPreferences: the full disk round-trip through
 * [SessionHistoryRepository.load] / [SessionHistoryRepository.save] requires a real Android
 * Keystore (MasterKey → EncryptedSharedPreferences), which Robolectric's shadow does not
 * emulate. The serialization-level tests below prove the gate behaviour against the SAME bytes
 * that would land on disk; the encrypted-prefs file I/O + key eviction side-effect still needs
 * a manual / instrumented verify.
 */
class SessionHistoryRepositoryTest {

    /** Mirrors `SessionHistoryRepository.JSON` (which is private). */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private fun minimalHistory(
        sessionId: String = "s1",
        schemaVersion: Int = CACHE_SCHEMA_VERSION,
        epoch: Long = 0,
        lastSeq: Long = 0,
    ) = CachedSessionHistory(
        sessionId = sessionId,
        solutionId = 1L,
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

    // Test 3: The data-class default must be the legacy sentinel 1 (the load-path correctness
    // invariant). What lands on disk for a NEW write is re-stamped to 2 by writeNow.
    @Test
    fun `default schemaVersion is the legacy sentinel 1`() {
        val history = CachedSessionHistory(
            sessionId = "s2",
            solutionId = 1L,
            agentId = "agent1",
            entries = emptyList(),
            lastIndex = null,
            totalCountAtLastWrite = 0,
            // schemaVersion not specified — must default to the legacy sentinel 1.
        )

        assertEquals(1, history.schemaVersion)
    }

    // Null input is safely handled.
    @Test
    fun `gateBySchema returns null for null input`() {
        assertNull(SessionHistoryRepository.gateBySchema(null))
    }

    // --- Serialization round-trip tests (the ones that would have caught the legacy-gate bug) ---

    // A default-schema blob is what a genuine pre-Phase-6 cache looks like on disk: under
    // encodeDefaults=false the schemaVersion key is dropped, so it decodes back to the legacy
    // sentinel 1 and MUST be rejected by the gate.
    @Test
    fun `default-schema blob omits schemaVersion key, decodes to 1, and gate rejects it`() {
        val history = minimalHistory(schemaVersion = 1, epoch = 0, lastSeq = 0)

        val encoded = json.encodeToString(CachedSessionHistory.serializer(), history)
        assertFalse(
            encoded.contains("schemaVersion"),
            "default schemaVersion (1) must be omitted under encodeDefaults=false; got: $encoded",
        )

        val decoded = json.decodeFromString(CachedSessionHistory.serializer(), encoded)
        assertEquals(1, decoded.schemaVersion)
        assertNull(SessionHistoryRepository.gateBySchema(decoded))
    }

    // A hand-written legacy JSON blob (no schemaVersion, no epoch, no lastSeq keys) — exactly what
    // a real pre-Phase-6 disk cache holds — decodes to the legacy defaults and the gate drops it.
    @Test
    fun `hand-written legacy JSON without cursor keys is rejected by gate`() {
        val legacyJson = """
            {
              "sessionId": "s1",
              "solutionId": 1,
              "agentId": "agent1",
              "entries": [],
              "lastIndex": null,
              "totalCountAtLastWrite": 0
            }
        """.trimIndent()

        val decoded = json.decodeFromString(CachedSessionHistory.serializer(), legacyJson)

        assertEquals(1, decoded.schemaVersion)
        assertEquals(0L, decoded.epoch)
        assertEquals(0L, decoded.lastSeq)
        assertNull(SessionHistoryRepository.gateBySchema(decoded))
    }

    // A CACHE_SCHEMA_VERSION-stamped blob (what writeNow produces) DOES carry the schemaVersion
    // key, decodes back to CACHE_SCHEMA_VERSION, and passes the gate with its cursor intact.
    @Test
    fun `current-schema blob carries schemaVersion key, decodes to CACHE_SCHEMA_VERSION, and passes gate`() {
        val history = minimalHistory(schemaVersion = CACHE_SCHEMA_VERSION, epoch = 5, lastSeq = 42)

        val encoded = json.encodeToString(CachedSessionHistory.serializer(), history)
        assertTrue(
            encoded.contains("\"schemaVersion\":$CACHE_SCHEMA_VERSION"),
            "a CACHE_SCHEMA_VERSION blob must persist the key; got: $encoded",
        )

        val decoded = json.decodeFromString(CachedSessionHistory.serializer(), encoded)
        assertEquals(CACHE_SCHEMA_VERSION, decoded.schemaVersion)
        val gated = SessionHistoryRepository.gateBySchema(decoded)
        assertNotNull(gated)
        assertEquals(5L, gated!!.epoch)
        assertEquals(42L, gated.lastSeq)
    }
}
