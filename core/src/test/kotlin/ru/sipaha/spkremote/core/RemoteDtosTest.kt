package ru.sipaha.spkremote.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RemoteDtosTest {

    @Test
    fun `SolutionSummary round-trips with all optional fields present`() {
        val text = """
            {
              "id": "sol-1",
              "name": "Spk Editor",
              "root": "/home/spk/.spk/spk-editor",
              "member_count": 4,
              "last_opened_at": "2026-05-16T08:00:00Z",
              "window_open": true,
              "main_window_id": "win-7"
            }
        """.trimIndent()
        val parsed = JsonRpc.json.decodeFromString(SolutionSummary.serializer(), text)
        assertEquals("sol-1", parsed.id)
        assertEquals("Spk Editor", parsed.name)
        assertEquals("/home/spk/.spk/spk-editor", parsed.root)
        assertEquals(4, parsed.memberCount)
        assertEquals("2026-05-16T08:00:00Z", parsed.lastOpenedAt)
        assertTrue(parsed.windowOpen)
        assertEquals("win-7", parsed.mainWindowId)

        val reencoded = JsonRpc.json.encodeToString(SolutionSummary.serializer(), parsed)
        val again = JsonRpc.json.decodeFromString(SolutionSummary.serializer(), reencoded)
        assertEquals(parsed, again)
    }

    @Test
    fun `SolutionSummary tolerates missing optional fields`() {
        val text = """
            {
              "id": "sol-2",
              "name": "Other",
              "root": "/tmp/x",
              "member_count": 0,
              "window_open": false
            }
        """.trimIndent()
        val parsed = JsonRpc.json.decodeFromString(SolutionSummary.serializer(), text)
        assertNull(parsed.lastOpenedAt)
        assertNull(parsed.mainWindowId)
        assertEquals(0, parsed.memberCount)
        assertEquals(false, parsed.windowOpen)
    }

    @Test
    fun `ListSolutionsResult round-trips`() {
        val text = """
            {
              "solutions": [
                {
                  "id": "sol-a",
                  "name": "A",
                  "root": "/a",
                  "member_count": 1,
                  "window_open": true
                },
                {
                  "id": "sol-b",
                  "name": "B",
                  "root": "/b",
                  "member_count": 2,
                  "last_opened_at": "2026-05-15T00:00:00Z",
                  "window_open": false,
                  "main_window_id": "win-2"
                }
              ]
            }
        """.trimIndent()
        val parsed = JsonRpc.json.decodeFromString(ListSolutionsResult.serializer(), text)
        assertEquals(2, parsed.solutions.size)
        assertEquals("sol-a", parsed.solutions[0].id)
        assertEquals("win-2", parsed.solutions[1].mainWindowId)
    }

    @Test
    fun `SessionSummary round-trips a clean Idle state`() {
        val text = """
            {
              "id": "ses-1",
              "solution_id": "sol-1",
              "agent_id": "claude",
              "title": "Refactor auth",
              "state": "Idle",
              "created_at": 1715800000000,
              "last_activity_at": 1715800100000
            }
        """.trimIndent()
        val parsed = JsonRpc.json.decodeFromString(SessionSummary.serializer(), text)
        assertEquals("ses-1", parsed.id)
        assertEquals("sol-1", parsed.solutionId)
        assertEquals("claude", parsed.agentId)
        assertEquals("Idle", parsed.state)
        assertEquals(1715800000000L, parsed.createdAt)
        assertEquals(1715800100000L, parsed.lastActivityAt)
        assertEquals(DisplayState.Idle, parsedDisplayStateOf(parsed))
    }

    @Test
    fun `SessionSummary preserves a Running-with-Instant debug payload verbatim`() {
        // Regression: the `Running` variant has been seen to embed an
        // `Instant { tv_sec, tv_nsec }`. The classifier MUST still recognise
        // it via the prefix; we also assert the raw string survives a JSON
        // round-trip so debugging future regressions stays cheap.
        val rawState = "Running { started_at: Instant { tv_sec: 0, tv_nsec: 0 }, notified: false }"
        val seed = SessionSummary(
            id = "ses-2",
            solutionId = "sol-1",
            agentId = "claude",
            title = "Long-running",
            state = rawState,
            createdAt = 1L,
            lastActivityAt = 2L,
        )
        val encoded = JsonRpc.json.encodeToString(SessionSummary.serializer(), seed)
        val parsed = JsonRpc.json.decodeFromString(SessionSummary.serializer(), encoded)
        assertEquals(rawState, parsed.state)
        assertEquals(DisplayState.Running, parseDisplayState(parsed.state))
    }

    @Test
    fun `ListSessionsResult round-trips`() {
        val text = """
            {
              "sessions": [
                {
                  "id": "s1",
                  "solution_id": "sol",
                  "agent_id": "claude",
                  "title": "T",
                  "state": "AwaitingInput",
                  "created_at": 10,
                  "last_activity_at": 20
                }
              ]
            }
        """.trimIndent()
        val parsed = JsonRpc.json.decodeFromString(ListSessionsResult.serializer(), text)
        assertEquals(1, parsed.sessions.size)
        assertEquals("AwaitingInput", parsed.sessions[0].state)
    }

    @Test
    fun `parseDisplayState classifies Idle`() {
        assertEquals(DisplayState.Idle, parseDisplayState("Idle"))
    }

    @Test
    fun `parseDisplayState classifies Running with payload`() {
        assertEquals(
            DisplayState.Running,
            parseDisplayState("Running { started_at: Instant { tv_sec: 0, tv_nsec: 0 }, notified: false }"),
        )
        assertEquals(DisplayState.Running, parseDisplayState("Running"))
    }

    @Test
    fun `parseDisplayState classifies AwaitingInput`() {
        assertEquals(DisplayState.AwaitingInput, parseDisplayState("AwaitingInput"))
    }

    @Test
    fun `parseDisplayState classifies Errored with payload`() {
        assertEquals(DisplayState.Errored, parseDisplayState("Errored(\"boom\")"))
        assertEquals(DisplayState.Errored, parseDisplayState("Errored"))
    }

    @Test
    fun `parseDisplayState falls back to Unknown for surprises`() {
        assertEquals(DisplayState.Unknown, parseDisplayState("FuturePhase"))
        assertEquals(DisplayState.Unknown, parseDisplayState(""))
    }

    private fun parsedDisplayStateOf(s: SessionSummary): DisplayState = parseDisplayState(s.state)
}
