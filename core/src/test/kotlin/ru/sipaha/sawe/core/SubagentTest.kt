package ru.sipaha.sawe.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubagentTest {

    // -------------------------------------------------------------------------
    // DTO deserialization
    // -------------------------------------------------------------------------

    @Test
    fun `SubagentDto decodes server payload`() {
        val text = """{"id":"toolu_abc","label":"Read README","started_at_ms":1700000000000}"""
        val parsed = JsonRpc.json.decodeFromString(SubagentDto.serializer(), text)
        assertEquals("toolu_abc", parsed.id)
        assertEquals("Read README", parsed.label)
        assertEquals(1700000000000L, parsed.startedAtMs)
    }

    @Test
    fun `SessionActiveSubagentsChangedPayload decodes server payload`() {
        val text = """
            {
              "session_id": "ses-1",
              "active_subagents": [
                {"id": "toolu_a", "label": "Search", "started_at_ms": 100},
                {"id": "toolu_b", "label": "Build",  "started_at_ms": 200}
              ]
            }
        """.trimIndent()
        val parsed = JsonRpc.json.decodeFromString(
            SessionActiveSubagentsChangedPayload.serializer(),
            text,
        )
        assertEquals("ses-1", parsed.sessionId)
        assertEquals(2, parsed.activeSubagents.size)
        assertEquals("toolu_a", parsed.activeSubagents[0].id)
        assertEquals("Build", parsed.activeSubagents[1].label)
    }

    @Test
    fun `SessionActiveSubagentsChangedPayload decodes empty list (queue drained)`() {
        val text = """{"session_id":"ses-1","active_subagents":[]}"""
        val parsed = JsonRpc.json.decodeFromString(
            SessionActiveSubagentsChangedPayload.serializer(),
            text,
        )
        assertEquals("ses-1", parsed.sessionId)
        assertTrue(parsed.activeSubagents.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Forward-compat: pre-Etap-5 servers omit the new fields
    // -------------------------------------------------------------------------

    @Test
    fun `SessionSummary without active_subagents decodes (default empty)`() {
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
        assertTrue(parsed.activeSubagents.isEmpty())
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
