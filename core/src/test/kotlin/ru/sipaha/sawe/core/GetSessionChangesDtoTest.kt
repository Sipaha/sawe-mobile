package ru.sipaha.sawe.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GetSessionChangesDtoTest {

    // Test 1: reset payload — nullable sections absent, streams + selected present
    @Test
    fun `GetSessionChangesResult decodes reset payload with null optional sections`() {
        val json = """
            {
              "epoch": 2,
              "current_seq": 9,
              "reset": true,
              "total_count": 0,
              "streams": [
                {"id":{"type":"main"},"kind":"main","label":"Main","state":{"type":"live"},"seq":9,"total_count":0}
              ],
              "selected_stream_id": {"type":"main"}
            }
        """.trimIndent()
        val result = JsonRpc.json.decodeFromString(GetSessionChangesResult.serializer(), json)

        assertEquals(2L, result.epoch)
        assertEquals(9L, result.currentSeq)
        assertEquals(true, result.reset)
        assertEquals(0, result.totalCount)
        assertTrue(result.changedEntries.isEmpty())
        assertTrue(result.removedIndices.isEmpty())
        // CRITICAL: absent nullable sections must be null, not emptyList()
        assertNull(result.state)
        assertNull(result.pendingBundles)
        // streams is ALWAYS present on the wire (full list every poll).
        assertEquals(1, result.streams.size)
        assertEquals(StreamIdDto.Main, result.streams[0].id)
        assertEquals(StreamIdDto.Main, result.selectedStreamId)
    }

    // Test 2: changed entries + state present, queue ABSENT, streams present
    @Test
    fun `GetSessionChangesResult decodes changed entries and state while queue remains null`() {
        val json = """
            {
              "epoch": 1,
              "current_seq": 12,
              "reset": false,
              "total_count": 5,
              "changed_entries": [{"role": "assistant", "preview": "hi", "index": 4}],
              "state": {"kind": "idle"},
              "streams": [
                {"id":{"type":"main"},"kind":"main","label":"Main","state":{"type":"live"},"seq":12,"total_count":5}
              ],
              "selected_stream_id": {"type":"main"}
            }
        """.trimIndent()
        val result = JsonRpc.json.decodeFromString(GetSessionChangesResult.serializer(), json)

        assertEquals(1L, result.epoch)
        assertEquals(12L, result.currentSeq)
        assertEquals(false, result.reset)
        assertEquals(5, result.totalCount)
        assertEquals(1, result.changedEntries.size)
        assertEquals(4, result.changedEntries[0].index)
        assertEquals(EntryRoleDto.Assistant, result.changedEntries[0].role)
        assertEquals("hi", result.changedEntries[0].preview)
        assertEquals(SessionStateDto.Idle, result.state)
        // CRITICAL: absent means null, not empty
        assertNull(result.pendingBundles)
        assertEquals(1, result.streams.size)
        assertEquals(StreamIdDto.Main, result.selectedStreamId)
    }

    // Test 3: present-but-empty pending_bundles — "now empty" case, NOT null;
    // a non-Main selected stream + multi-stream list round-trip.
    @Test
    fun `GetSessionChangesResult distinguishes absent (null) from present-empty pending_bundles and carries selected stream`() {
        val json = """
            {
              "epoch": 1,
              "current_seq": 13,
              "reset": false,
              "total_count": 5,
              "pending_bundles": [],
              "streams": [
                {"id":{"type":"main"},"kind":"main","label":"Main","state":{"type":"live"},"seq":20,"total_count":6},
                {"id":{"type":"teammate","toolu":"t1"},"kind":"teammate","label":"Search","state":{"type":"live"},"seq":13,"total_count":5}
              ],
              "selected_stream_id": {"type":"teammate","toolu":"t1"}
            }
        """.trimIndent()
        val result = JsonRpc.json.decodeFromString(GetSessionChangesResult.serializer(), json)

        assertEquals(13L, result.currentSeq)
        assertEquals(false, result.reset)
        // CRITICAL: present-but-empty must be emptyList(), NOT null
        assertEquals(emptyList<QueuedBundleSummary>(), result.pendingBundles)
        // state was absent (JSON key missing) — must be null
        assertNull(result.state)
        assertEquals(2, result.streams.size)
        assertEquals(StreamIdDto.Teammate("t1"), result.selectedStreamId)
    }

    // Test 4: GetSessionResult carries epoch/current_seq
    @Test
    fun `GetSessionResult decodes epoch and current_seq when present`() {
        val json = """
            {
              "id": "ses-1",
              "solution_id": 1,
              "agent_id": "claude",
              "title": "Test",
              "state": {"kind": "idle"},
              "created_at": 0,
              "last_activity_at": 0,
              "entries": [],
              "epoch": 3,
              "current_seq": 20
            }
        """.trimIndent()
        val result = JsonRpc.json.decodeFromString(GetSessionResult.serializer(), json)

        assertEquals(3L, result.epoch)
        assertEquals(20L, result.currentSeq)
    }

    @Test
    fun `GetSessionResult defaults epoch and current_seq to 0 when absent`() {
        val json = """
            {
              "id": "ses-2",
              "solution_id": 1,
              "agent_id": "claude",
              "title": "Pre-phase5",
              "state": {"kind": "idle"},
              "created_at": 0,
              "last_activity_at": 0,
              "entries": []
            }
        """.trimIndent()
        val result = JsonRpc.json.decodeFromString(GetSessionResult.serializer(), json)

        assertEquals(0L, result.epoch)
        assertEquals(0L, result.currentSeq)
    }
}
