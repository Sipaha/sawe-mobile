package ru.sipaha.spkremote.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackgroundAgentTest {

    // -------------------------------------------------------------------------
    // DTO deserialization
    // -------------------------------------------------------------------------

    @Test
    fun `BackgroundAgentDto decodes full server payload`() {
        val text = """
            {
              "id": "agent-1",
              "label": "Refactor module",
              "mtime_ms": 1700000000000,
              "stop_reason": "end_turn"
            }
        """.trimIndent()
        val parsed = JsonRpc.json.decodeFromString(BackgroundAgentDto.serializer(), text)
        assertEquals("agent-1", parsed.id)
        assertEquals("Refactor module", parsed.label)
        assertEquals(1700000000000L, parsed.mtimeMs)
        assertEquals("end_turn", parsed.stopReason)
    }

    @Test
    fun `BackgroundAgentDto tolerates missing mtime_ms and stop_reason`() {
        val text = """{"id":"agent-2","label":"Build docs"}"""
        val parsed = JsonRpc.json.decodeFromString(BackgroundAgentDto.serializer(), text)
        assertEquals("agent-2", parsed.id)
        assertEquals("Build docs", parsed.label)
        // Missing stop_reason -> null (derived as still-running).
        assertNull(parsed.stopReason)
        // Missing mtime_ms -> null tolerated.
        assertNull(parsed.mtimeMs)
    }

    @Test
    fun `GetSessionBackgroundAgentsResult decodes full result`() {
        val text = """
            {
              "background_agents": [
                {"id":"agent-1","label":"npm dev","mtime_ms":100},
                {"id":"agent-2","label":"cargo test","stop_reason":"end_turn"},
                {"id":"agent-3","label":"tail log"}
              ]
            }
        """.trimIndent()
        val parsed = JsonRpc.json.decodeFromString(
            GetSessionBackgroundAgentsResult.serializer(),
            text,
        )
        assertEquals(3, parsed.backgroundAgents.size)
        // Running: stop_reason null.
        assertNull(parsed.backgroundAgents[0].stopReason)
        assertEquals(100L, parsed.backgroundAgents[0].mtimeMs)
        // Done: stop_reason present.
        assertEquals("end_turn", parsed.backgroundAgents[1].stopReason)
        assertNull(parsed.backgroundAgents[1].mtimeMs)
        assertNull(parsed.backgroundAgents[2].stopReason)
    }

    @Test
    fun `GetSessionBackgroundAgentsResult without background_agents decodes (default empty)`() {
        val text = "{}"
        val parsed = JsonRpc.json.decodeFromString(
            GetSessionBackgroundAgentsResult.serializer(),
            text,
        )
        assertTrue(parsed.backgroundAgents.isEmpty())
    }

    @Test
    fun `SessionBackgroundAgentsChangedPayload decodes list`() {
        val text = """
            {
              "session_id": "ses-1",
              "background_agents": [
                {"id":"agent-1","label":"npm dev"},
                {"id":"agent-2","label":"cargo test","stop_reason":"end_turn"}
              ]
            }
        """.trimIndent()
        val parsed = JsonRpc.json.decodeFromString(
            SessionBackgroundAgentsChangedPayload.serializer(),
            text,
        )
        assertEquals("ses-1", parsed.sessionId)
        assertEquals(2, parsed.backgroundAgents.size)
        // First still running (no stop_reason), second done.
        assertNull(parsed.backgroundAgents[0].stopReason)
        assertEquals("end_turn", parsed.backgroundAgents[1].stopReason)
    }

    @Test
    fun `SessionBackgroundAgentsChangedPayload decodes empty list (all agents gone)`() {
        val text = """{"session_id":"ses-1","background_agents":[]}"""
        val parsed = JsonRpc.json.decodeFromString(
            SessionBackgroundAgentsChangedPayload.serializer(),
            text,
        )
        assertEquals("ses-1", parsed.sessionId)
        assertTrue(parsed.backgroundAgents.isEmpty())
    }
}
