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

    @Test
    fun `EntrySummary round-trips with each known role`() {
        // Sanity-check every role we map in parseEntryRole survives both
        // decode and encode unchanged, including the truncated preview.
        val text = """
            {
              "id": "s",
              "solution_id": "sol",
              "agent_id": "claude",
              "title": "T",
              "state": "Idle",
              "created_at": 0,
              "last_activity_at": 0,
              "entries": [
                {"role": "user",        "preview": "hi"},
                {"role": "assistant",   "preview": "hello world"},
                {"role": "tool_call",   "preview": "Read(file.kt)"},
                {"role": "plan",        "preview": "1. step\n2. step"},
                {"role": "future_kind", "preview": "..."}
              ]
            }
        """.trimIndent()
        val parsed = JsonRpc.json.decodeFromString(GetSessionResult.serializer(), text)
        assertEquals(5, parsed.entries.size)
        assertEquals(EntryRole.User, parseEntryRole(parsed.entries[0].role))
        assertEquals(EntryRole.Assistant, parseEntryRole(parsed.entries[1].role))
        assertEquals(EntryRole.ToolCall, parseEntryRole(parsed.entries[2].role))
        assertEquals(EntryRole.Plan, parseEntryRole(parsed.entries[3].role))
        assertEquals(EntryRole.Unknown, parseEntryRole(parsed.entries[4].role))

        // Re-encode preserves verbatim — preview field is a black box on the
        // wire and we don't want to accidentally re-flow newlines or escape
        // unicode that the server already chose to embed.
        val reencoded = JsonRpc.json.encodeToString(GetSessionResult.serializer(), parsed)
        val again = JsonRpc.json.decodeFromString(GetSessionResult.serializer(), reencoded)
        assertEquals(parsed, again)
    }

    @Test
    fun `GetSessionResult round-trips an empty transcript`() {
        val text = """
            {
              "id": "ses-empty",
              "solution_id": "sol-1",
              "agent_id": "claude",
              "title": "Just opened",
              "state": "Idle",
              "created_at": 1,
              "last_activity_at": 1,
              "entries": []
            }
        """.trimIndent()
        val parsed = JsonRpc.json.decodeFromString(GetSessionResult.serializer(), text)
        assertEquals("ses-empty", parsed.id)
        assertEquals("sol-1", parsed.solutionId)
        assertTrue(parsed.entries.isEmpty())
        assertEquals(DisplayState.Idle, parseDisplayState(parsed.state))
    }

    @Test
    fun `GetSessionResult survives a Running state with payload and multiple entries`() {
        val text = """
            {
              "id": "ses-running",
              "solution_id": "sol-1",
              "agent_id": "claude",
              "title": "Mid-turn",
              "state": "Running { started_at: Instant { tv_sec: 0, tv_nsec: 0 }, notified: false }",
              "created_at": 10,
              "last_activity_at": 20,
              "entries": [
                {"role": "user", "preview": "Write a haiku."},
                {"role": "assistant", "preview": "Snowflakes fall softly..."},
                {"role": "tool_call", "preview": "Edit(haiku.txt)"}
              ]
            }
        """.trimIndent()
        val parsed = JsonRpc.json.decodeFromString(GetSessionResult.serializer(), text)
        assertEquals(3, parsed.entries.size)
        assertEquals(DisplayState.Running, parseDisplayState(parsed.state))
        assertEquals("Write a haiku.", parsed.entries[0].preview)
    }

    @Test
    fun `parseEntryRole maps all known roles and falls back to Unknown`() {
        assertEquals(EntryRole.User, parseEntryRole("user"))
        assertEquals(EntryRole.Assistant, parseEntryRole("assistant"))
        assertEquals(EntryRole.ToolCall, parseEntryRole("tool_call"))
        assertEquals(EntryRole.Plan, parseEntryRole("plan"))
        assertEquals(EntryRole.Unknown, parseEntryRole("system"))
        assertEquals(EntryRole.Unknown, parseEntryRole(""))
    }

    @Test
    fun `EntrySummary round-trips with all rich fields populated`() {
        val text = """
            {
              "role": "assistant",
              "preview": "Here is an image: ![image #0](spk-image://0)",
              "markdown": "Here is an image: ![image #0](spk-image://0)\n\nAnd more text after.",
              "images": [
                {"index": 0, "mime_type": "image/png", "data_base64": "iVBORw0KGgo="}
              ],
              "tool_call": null,
              "plan": null
            }
        """.trimIndent()
        val parsed = JsonRpc.json.decodeFromString(EntrySummary.serializer(), text)
        assertEquals("assistant", parsed.role)
        assertEquals(
            "Here is an image: ![image #0](spk-image://0)\n\nAnd more text after.",
            parsed.markdown,
        )
        assertEquals(1, parsed.images?.size)
        assertEquals("image/png", parsed.images?.get(0)?.mimeType)
        assertEquals("iVBORw0KGgo=", parsed.images?.get(0)?.dataBase64)
        assertNull(parsed.toolCall)
        assertNull(parsed.plan)

        val reencoded = JsonRpc.json.encodeToString(EntrySummary.serializer(), parsed)
        val again = JsonRpc.json.decodeFromString(EntrySummary.serializer(), reencoded)
        assertEquals(parsed, again)
    }

    @Test
    fun `EntrySummary is backwards compatible with R-5d flat shape`() {
        // Older server / minimal payload: only role+preview, no rich fields.
        // Must decode cleanly with every optional field defaulting to null.
        val text = """{"role":"user","preview":"hello"}"""
        val parsed = JsonRpc.json.decodeFromString(EntrySummary.serializer(), text)
        assertEquals("user", parsed.role)
        assertEquals("hello", parsed.preview)
        assertNull(parsed.markdown)
        assertNull(parsed.images)
        assertNull(parsed.toolCall)
        assertNull(parsed.plan)
    }

    @Test
    fun `EntryImage round-trips standalone`() {
        val text = """
            {"index": 3, "mime_type": "image/jpeg", "data_base64": "/9j/4AAQ"}
        """.trimIndent()
        val parsed = JsonRpc.json.decodeFromString(EntryImage.serializer(), text)
        assertEquals(3, parsed.index)
        assertEquals("image/jpeg", parsed.mimeType)
        assertEquals("/9j/4AAQ", parsed.dataBase64)

        val reencoded = JsonRpc.json.encodeToString(EntryImage.serializer(), parsed)
        val again = JsonRpc.json.decodeFromString(EntryImage.serializer(), reencoded)
        assertEquals(parsed, again)
    }

    @Test
    fun `ToolCallSummary round-trips every documented status string`() {
        // The seven server-side status phrases are part of the wire contract;
        // any client that wants to colour-code them MUST tolerate all of
        // these verbatim (with spaces, not underscores).
        val statuses = listOf(
            "pending",
            "waiting for confirmation",
            "running",
            "done",
            "failed",
            "rejected",
            "canceled",
        )
        for (status in statuses) {
            val text = """
                {
                  "name": "Read",
                  "status": "$status",
                  "args_preview": "{ \"path\": \"foo.kt\" }",
                  "result_preview": "ok"
                }
            """.trimIndent()
            val parsed = JsonRpc.json.decodeFromString(ToolCallSummary.serializer(), text)
            assertEquals(status, parsed.status)
            assertEquals("Read", parsed.name)
            assertEquals("{ \"path\": \"foo.kt\" }", parsed.argsPreview)
            assertEquals("ok", parsed.resultPreview)

            val reencoded = JsonRpc.json.encodeToString(ToolCallSummary.serializer(), parsed)
            val again = JsonRpc.json.decodeFromString(ToolCallSummary.serializer(), reencoded)
            assertEquals(parsed, again)
        }
    }

    @Test
    fun `ToolCallSummary defaults result_preview to empty when omitted`() {
        // Pending / running tool-calls won't yet have a result; the server
        // omits the field entirely rather than emit `""`. The DTO must
        // default it so decoding still succeeds.
        val text = """
            {
              "name": "Edit",
              "status": "running",
              "args_preview": "edit args"
            }
        """.trimIndent()
        val parsed = JsonRpc.json.decodeFromString(ToolCallSummary.serializer(), text)
        assertEquals("", parsed.resultPreview)
    }

    @Test
    fun `PlanSummary round-trips empty and non-empty item lists`() {
        val empty = JsonRpc.json.decodeFromString(
            PlanSummary.serializer(),
            """{"items": []}""",
        )
        assertTrue(empty.items.isEmpty())

        val populated = JsonRpc.json.decodeFromString(
            PlanSummary.serializer(),
            """{"items": ["First step", "Second step", "Third step"]}""",
        )
        assertEquals(3, populated.items.size)
        assertEquals("First step", populated.items[0])

        val reencoded = JsonRpc.json.encodeToString(PlanSummary.serializer(), populated)
        val again = JsonRpc.json.decodeFromString(PlanSummary.serializer(), reencoded)
        assertEquals(populated, again)
    }

    @Test
    fun `GetSessionEntryResult round-trips a tool_call entry`() {
        val text = """
            {
              "entry": {
                "role": "tool_call",
                "preview": "Read(foo.kt)",
                "markdown": "Read(foo.kt) → 42 lines",
                "tool_call": {
                  "name": "Read",
                  "status": "done",
                  "args_preview": "{\"path\":\"foo.kt\"}",
                  "result_preview": "42 lines"
                }
              }
            }
        """.trimIndent()
        val parsed = JsonRpc.json.decodeFromString(GetSessionEntryResult.serializer(), text)
        assertEquals("tool_call", parsed.entry.role)
        assertEquals("Read", parsed.entry.toolCall?.name)
        assertEquals("done", parsed.entry.toolCall?.status)

        val reencoded = JsonRpc.json.encodeToString(GetSessionEntryResult.serializer(), parsed)
        val again = JsonRpc.json.decodeFromString(GetSessionEntryResult.serializer(), reencoded)
        assertEquals(parsed, again)
    }

    @Test
    fun `MessageAppendedPayload round-trips`() {
        // Wire shape sent inside the `data` field of an
        // `agent_session_message_appended` notification.
        val text = """
            {
              "session_id": "ses-1",
              "entry_index": 7,
              "role": "assistant",
              "preview": "Hello, world."
            }
        """.trimIndent()
        val parsed = JsonRpc.json.decodeFromString(MessageAppendedPayload.serializer(), text)
        assertEquals("ses-1", parsed.sessionId)
        assertEquals(7, parsed.entryIndex)
        assertEquals("assistant", parsed.role)
        assertEquals("Hello, world.", parsed.preview)

        val reencoded = JsonRpc.json.encodeToString(MessageAppendedPayload.serializer(), parsed)
        val again = JsonRpc.json.decodeFromString(MessageAppendedPayload.serializer(), reencoded)
        assertEquals(parsed, again)
    }

    @Test
    fun `AgentSummary round-trips with snake_case display_name`() {
        val text = """
            {"id": "claude", "display_name": "Claude Code"}
        """.trimIndent()
        val parsed = JsonRpc.json.decodeFromString(AgentSummary.serializer(), text)
        assertEquals("claude", parsed.id)
        assertEquals("Claude Code", parsed.displayName)

        val reencoded = JsonRpc.json.encodeToString(AgentSummary.serializer(), parsed)
        val again = JsonRpc.json.decodeFromString(AgentSummary.serializer(), reencoded)
        assertEquals(parsed, again)
    }

    @Test
    fun `ListAgentsResult round-trips empty and populated agent lists`() {
        // Empty list is a valid server response — no adapters registered.
        val empty = JsonRpc.json.decodeFromString(
            ListAgentsResult.serializer(),
            """{"agents": []}""",
        )
        assertTrue(empty.agents.isEmpty())

        val text = """
            {
              "agents": [
                {"id": "claude", "display_name": "Claude Code"},
                {"id": "gemini", "display_name": "Gemini CLI"}
              ]
            }
        """.trimIndent()
        val parsed = JsonRpc.json.decodeFromString(ListAgentsResult.serializer(), text)
        assertEquals(2, parsed.agents.size)
        assertEquals("claude", parsed.agents[0].id)
        assertEquals("Gemini CLI", parsed.agents[1].displayName)

        val reencoded = JsonRpc.json.encodeToString(ListAgentsResult.serializer(), parsed)
        val again = JsonRpc.json.decodeFromString(ListAgentsResult.serializer(), reencoded)
        assertEquals(parsed, again)
    }

    @Test
    fun `CreateSessionResult round-trips snake_case session_id`() {
        // Server emits `session_id` (not `id`) on the structured result of
        // `remote.solution_agent.create_session` — the SerialName must hold.
        val text = """{"session_id": "ses-new-1"}"""
        val parsed = JsonRpc.json.decodeFromString(CreateSessionResult.serializer(), text)
        assertEquals("ses-new-1", parsed.sessionId)

        val reencoded = JsonRpc.json.encodeToString(CreateSessionResult.serializer(), parsed)
        val again = JsonRpc.json.decodeFromString(CreateSessionResult.serializer(), reencoded)
        assertEquals(parsed, again)
    }

    @Test
    fun `EntrySummary round-trips with R-6e index field populated`() {
        // R-6e: server now stamps an absolute `index` on every entry so
        // paginated clients can stitch slices together. The DTO must
        // round-trip the value verbatim, including when other rich fields
        // are also present.
        val text = """
            {
              "role": "assistant",
              "preview": "hi",
              "index": 42,
              "markdown": "hi"
            }
        """.trimIndent()
        val parsed = JsonRpc.json.decodeFromString(EntrySummary.serializer(), text)
        assertEquals(42, parsed.index)
        assertEquals("assistant", parsed.role)
        assertEquals("hi", parsed.markdown)

        val reencoded = JsonRpc.json.encodeToString(EntrySummary.serializer(), parsed)
        val again = JsonRpc.json.decodeFromString(EntrySummary.serializer(), reencoded)
        assertEquals(parsed, again)
        assertEquals(42, again.index)
    }

    @Test
    fun `EntrySummary defaults index to -1 when omitted by pre-R-6e server`() {
        // Back-compat with R-5d/R-5f payloads — those servers never emit
        // an `index` field. The DTO must surface the sentinel rather than
        // throwing so a mixed-version mobile/server pair keeps working.
        val text = """{"role":"user","preview":"hello"}"""
        val parsed = JsonRpc.json.decodeFromString(EntrySummary.serializer(), text)
        assertEquals(-1, parsed.index)
        assertEquals("user", parsed.role)
        assertEquals("hello", parsed.preview)
    }

    @Test
    fun `GetSessionResult round-trips with R-6e total_count populated`() {
        val text = """
            {
              "id": "ses-page",
              "solution_id": "sol-1",
              "agent_id": "claude",
              "title": "Paginated",
              "state": "Idle",
              "created_at": 1,
              "last_activity_at": 2,
              "entries": [
                {"role": "user", "preview": "first", "index": 0},
                {"role": "assistant", "preview": "ack", "index": 1}
              ],
              "total_count": 137
            }
        """.trimIndent()
        val parsed = JsonRpc.json.decodeFromString(GetSessionResult.serializer(), text)
        assertEquals(137, parsed.totalCount)
        assertEquals(2, parsed.entries.size)
        assertEquals(0, parsed.entries[0].index)
        assertEquals(1, parsed.entries[1].index)

        val reencoded = JsonRpc.json.encodeToString(GetSessionResult.serializer(), parsed)
        val again = JsonRpc.json.decodeFromString(GetSessionResult.serializer(), reencoded)
        assertEquals(parsed, again)
        assertEquals(137, again.totalCount)
    }

    @Test
    fun `GetSessionResult defaults total_count to -1 when omitted by pre-R-6e server`() {
        // Back-compat with the R-5d / R-5f response shape — those servers
        // didn't stamp a `total_count` field. Decoder must fall through
        // to the sentinel so UI gap-detection guards against -1 rather
        // than misclassifying a legacy full pull as a paginated slice.
        val text = """
            {
              "id": "ses-legacy",
              "solution_id": "sol-1",
              "agent_id": "claude",
              "title": "Legacy",
              "state": "Idle",
              "created_at": 1,
              "last_activity_at": 1,
              "entries": [{"role": "user", "preview": "hi"}]
            }
        """.trimIndent()
        val parsed = JsonRpc.json.decodeFromString(GetSessionResult.serializer(), text)
        assertEquals(-1, parsed.totalCount)
        assertEquals(1, parsed.entries.size)
        // Pre-R-6e entries also have index=-1 — sentinel must survive both
        // levels of nesting.
        assertEquals(-1, parsed.entries[0].index)
    }

    @Test
    fun `ListSessionsResult round-trips with optional total_count`() {
        // R-6e: list_sessions also gained pagination + total_count. UI wiring
        // is deferred (R-6f) but the DTO must surface it now so the
        // schema bump isn't a wire break later.
        val withCount = """
            {
              "sessions": [
                {"id":"s1","solution_id":"sol","agent_id":"claude","title":"T","state":"Idle","created_at":0,"last_activity_at":0}
              ],
              "total_count": 5
            }
        """.trimIndent()
        val parsedWith = JsonRpc.json.decodeFromString(ListSessionsResult.serializer(), withCount)
        assertEquals(5, parsedWith.totalCount)

        val without = """
            {"sessions": [{"id":"s2","solution_id":"sol","agent_id":"c","title":"T","state":"Idle","created_at":0,"last_activity_at":0}]}
        """.trimIndent()
        val parsedWithout = JsonRpc.json.decodeFromString(ListSessionsResult.serializer(), without)
        assertEquals(-1, parsedWithout.totalCount)
    }

    @Test
    fun `EntrySummary with plan payload round-trips`() {
        val text = """
            {
              "role": "plan",
              "preview": "1. Foo\n2. Bar",
              "markdown": "1. Foo\n2. Bar",
              "plan": {"items": ["Foo", "Bar"]}
            }
        """.trimIndent()
        val parsed = JsonRpc.json.decodeFromString(EntrySummary.serializer(), text)
        assertEquals(2, parsed.plan?.items?.size)
        assertEquals("Foo", parsed.plan?.items?.get(0))
        assertEquals("Bar", parsed.plan?.items?.get(1))
    }

    private fun parsedDisplayStateOf(s: SessionSummary): DisplayState = parseDisplayState(s.state)
}
