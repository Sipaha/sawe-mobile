package ru.sipaha.spkremote.core

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
    fun `GetSessionResult without active_subagents decodes (default empty)`() {
        val text = """
            {
              "id": "ses-1",
              "solution_id": "sol-1",
              "agent_id": "claude",
              "title": "T",
              "state": {"kind":"idle"},
              "created_at": 0,
              "last_activity_at": 0,
              "entries": []
            }
        """.trimIndent()
        val parsed = JsonRpc.json.decodeFromString(GetSessionResult.serializer(), text)
        assertTrue(parsed.activeSubagents.isEmpty())
    }

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

    @Test
    fun `GetSessionResult with active_subagents populated decodes`() {
        val text = """
            {
              "id": "ses-1",
              "solution_id": "sol-1",
              "agent_id": "claude",
              "title": "T",
              "state": {"kind":"idle"},
              "created_at": 0,
              "last_activity_at": 0,
              "entries": [],
              "active_subagents": [
                {"id":"toolu_a","label":"Search","started_at_ms":100}
              ]
            }
        """.trimIndent()
        val parsed = JsonRpc.json.decodeFromString(GetSessionResult.serializer(), text)
        assertEquals(1, parsed.activeSubagents.size)
        assertEquals("toolu_a", parsed.activeSubagents[0].id)
    }

    // -------------------------------------------------------------------------
    // Pure filter logic
    // -------------------------------------------------------------------------

    private fun entry(role: EntryRoleDto, preview: String, subagentId: String? = null) =
        EntrySummary(role = role, preview = preview, subagentId = subagentId)

    @Test
    fun `filterEntriesBySubagent with null selected returns only main-thread entries`() {
        val entries = listOf(
            entry(EntryRoleDto.User, "u1"),
            entry(EntryRoleDto.Assistant, "a1", subagentId = "toolu_a"),
            entry(EntryRoleDto.Assistant, "a2"),
            entry(EntryRoleDto.Assistant, "a3", subagentId = "toolu_b"),
        )
        val filtered = filterEntriesBySubagent(entries, selectedId = null)
        assertEquals(2, filtered.size)
        assertEquals("u1", filtered[0].preview)
        assertEquals("a2", filtered[1].preview)
    }

    @Test
    fun `filterEntriesBySubagent with subagent id returns only matching entries`() {
        val entries = listOf(
            entry(EntryRoleDto.User, "u1"),
            entry(EntryRoleDto.Assistant, "a1", subagentId = "toolu_a"),
            entry(EntryRoleDto.Assistant, "a2"),
            entry(EntryRoleDto.Assistant, "a3", subagentId = "toolu_b"),
            entry(EntryRoleDto.Assistant, "a4", subagentId = "toolu_a"),
        )
        val filtered = filterEntriesBySubagent(entries, selectedId = "toolu_a")
        assertEquals(2, filtered.size)
        assertEquals("a1", filtered[0].preview)
        assertEquals("a4", filtered[1].preview)
    }

    @Test
    fun `filterEntriesBySubagent on empty list returns empty`() {
        assertTrue(filterEntriesBySubagent(emptyList(), null).isEmpty())
        assertTrue(filterEntriesBySubagent(emptyList(), "toolu_a").isEmpty())
    }

    @Test
    fun `filterEntriesBySubagent with all-null entries and null selected returns all`() {
        val entries = listOf(
            entry(EntryRoleDto.User, "u1"),
            entry(EntryRoleDto.Assistant, "a1"),
            entry(EntryRoleDto.Assistant, "a2"),
        )
        val filtered = filterEntriesBySubagent(entries, selectedId = null)
        assertEquals(3, filtered.size)
    }

    @Test
    fun `filterEntriesBySubagent with unknown subagent id returns empty`() {
        val entries = listOf(
            entry(EntryRoleDto.User, "u1"),
            entry(EntryRoleDto.Assistant, "a1", subagentId = "toolu_a"),
        )
        val filtered = filterEntriesBySubagent(entries, selectedId = "toolu_zzz_unknown")
        assertTrue(filtered.isEmpty())
    }
}
