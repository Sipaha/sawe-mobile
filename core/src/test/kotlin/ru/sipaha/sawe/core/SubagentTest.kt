package ru.sipaha.sawe.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubagentTest {

    // -------------------------------------------------------------------------
    // DTO deserialization
    // -------------------------------------------------------------------------

    @Test
    fun `SessionActiveSubagentsChangedPayload decodes bare dirty-poke`() {
        // As of v5 the server sends only session_id — the streams delta is the
        // single writer of the tab strip.
        val text = """{"session_id":"ses-1"}"""
        val parsed = JsonRpc.json.decodeFromString(
            SessionActiveSubagentsChangedPayload.serializer(),
            text,
        )
        assertEquals("ses-1", parsed.sessionId)
    }

    @Test
    fun `SessionActiveSubagentsChangedPayload ignores a stray legacy list`() {
        // ignoreUnknownKeys means a rolling-back server that still emits the old
        // active_subagents list decodes cleanly into the slimmed dirty-poke.
        val text = """{"session_id":"ses-1","active_subagents":[{"id":"toolu_a"}]}"""
        val parsed = JsonRpc.json.decodeFromString(
            SessionActiveSubagentsChangedPayload.serializer(),
            text,
        )
        assertEquals("ses-1", parsed.sessionId)
    }

    // -------------------------------------------------------------------------
    // SessionSummary no longer carries active_subagents (removed in v5)
    // -------------------------------------------------------------------------

    @Test
    fun `SessionSummary decodes without an active_subagents key`() {
        val text = """
            {
              "id": "ses-1",
              "solution_id": "sol-1",
              "agent_id": "claude",
              "title": "T",
              "state": {"kind":"idle"},
              "created_at": 0,
              "last_activity_at": 0
            }
        """.trimIndent()
        val parsed = JsonRpc.json.decodeFromString(SessionSummary.serializer(), text)
        assertEquals("ses-1", parsed.id)
    }

    @Test
    fun `EntrySummary without subagent_id decodes (default null)`() {
        val text = """{"role":"user","preview":"hi"}"""
        val parsed = JsonRpc.json.decodeFromString(EntrySummary.serializer(), text)
        assertNull(parsed.subagentId)
    }

    @Test
    fun `EntrySummary with subagent_id round-trips`() {
        val text = """{"role":"assistant","preview":"x","subagent_id":"toolu_abc"}"""
        val parsed = JsonRpc.json.decodeFromString(EntrySummary.serializer(), text)
        assertEquals("toolu_abc", parsed.subagentId)
    }

}
