package ru.sipaha.sawe.core

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WorkspaceDtosTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun snapshot_decodes_empty_state() {
        val raw = """{"seq":0,"solutions":[]}"""
        val snap = json.decodeFromString<WorkspaceSnapshot>(raw)
        assertEquals(0L, snap.seq)
        assertEquals(0, snap.solutions.size)
    }

    @Test
    fun snapshot_decodes_one_solution_with_one_session() {
        val raw = """
            {
              "seq": 42,
              "solutions": [{
                "id": 1, "name": "alpha", "root": "/x",
                "member_count": 1, "open": true,
                "sessions": [{
                  "id": "se1", "solution_id": 1, "agent_id": "claude",
                  "title": "t", "state": {"kind": "idle"},
                  "created_at": 1, "last_activity_at": 1
                }]
              }]
            }
        """.trimIndent()
        val snap = json.decodeFromString<WorkspaceSnapshot>(raw)
        assertEquals(42L, snap.seq)
        assertEquals(1, snap.solutions.size)
        assertEquals(1, snap.solutions[0].sessions.size)
    }

    @Test
    fun solution_opened_payload_carries_seq_and_optional_solution() {
        val raw = """{"seq":3,"solution":{"id":1,"name":"a","root":"/x","member_count":0,"open":true},"sessions":[]}"""
        val p = json.decodeFromString<WorkspaceSolutionOpenedPayload>(raw)
        assertEquals(3L, p.seq)
        assertEquals(1L, p.solution?.id)
    }

    @Test
    fun session_metrics_changed_payload_omits_seq() {
        val raw = """{"session_id":"se1","total_tokens":42}"""
        val p = json.decodeFromString<WorkspaceSessionMetricsChangedPayload>(raw)
        assertEquals("se1", p.sessionId)
        assertEquals(42L, p.totalTokens)
    }
}
