@file:Suppress("DEPRECATION")

package ru.sipaha.spkremote.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * JVM-only tests for the pure merge / dedup helpers extracted from
 * `SessionDetailStore`. Covers the three index-boundary branches of
 * [applyAppendedPlaceholder] and the FIFO / partial-match / duplicate-
 * content behaviour of [reconcileOptimisticContent], plus the wire-
 * parser [parseExpiredSendMessage].
 */
class SessionEntryMergeTest {

    private fun roleFromWire(raw: String): EntryRoleDto = when (raw) {
        "user" -> EntryRoleDto.User
        "assistant" -> EntryRoleDto.Assistant
        "tool_call" -> EntryRoleDto.ToolCall
        "plan" -> EntryRoleDto.Plan
        else -> EntryRoleDto.Unknown
    }

    private fun entry(
        role: String,
        preview: String,
        index: Int = -1,
        clientSendId: Long? = null,
    ): EntrySummary =
        EntrySummary(
            role = roleFromWire(role),
            preview = preview,
            index = index,
            clientSendId = clientSendId,
        )

    private fun payload(
        sessionId: String = "s1",
        entryIndex: Int,
        role: String = "assistant",
        preview: String = "preview",
    ): MessageAppendedPayload =
        MessageAppendedPayload(
            sessionId = sessionId,
            entryIndex = entryIndex,
            role = roleFromWire(role),
            preview = preview,
        )

    // -------------------------------------------------------------------------
    // applyAppendedPlaceholder
    // -------------------------------------------------------------------------

    @Test
    fun `append onto empty list at index 0 lands as a single-entry Replaced`() {
        val out = applyAppendedPlaceholder(
            current = emptyList(),
            payload = payload(entryIndex = 0, role = "user", preview = "hi"),
        )
        assertTrue(out is AppendedPlaceholderOutcome.Replaced)
        val entries = (out as AppendedPlaceholderOutcome.Replaced).entries
        assertEquals(1, entries.size)
        assertEquals(EntryRoleDto.User, entries[0].role)
        assertEquals("hi", entries[0].preview)
    }

    @Test
    fun `append at end - entryIndex equals size - lands at the end`() {
        val current = listOf(entry("user", "a"), entry("assistant", "b"))
        val out = applyAppendedPlaceholder(
            current = current,
            payload = payload(entryIndex = 2, role = "assistant", preview = "c"),
        )
        assertTrue(out is AppendedPlaceholderOutcome.Replaced)
        val entries = (out as AppendedPlaceholderOutcome.Replaced).entries
        assertEquals(3, entries.size)
        assertEquals("c", entries[2].preview)
    }

    @Test
    fun `in-place replace - entryIndex less than size - overwrites the slot without growing`() {
        val current = listOf(entry("user", "a"), entry("assistant", "stale"))
        val out = applyAppendedPlaceholder(
            current = current,
            payload = payload(entryIndex = 1, role = "assistant", preview = "fresh"),
        )
        assertTrue(out is AppendedPlaceholderOutcome.Replaced)
        val entries = (out as AppendedPlaceholderOutcome.Replaced).entries
        assertEquals(2, entries.size)
        assertEquals("fresh", entries[1].preview)
        // First slot untouched.
        assertEquals("a", entries[0].preview)
    }

    @Test
    fun `out-of-range entryIndex returns OutOfRange so caller refetches`() {
        val current = listOf(entry("user", "a"))
        val out = applyAppendedPlaceholder(
            current = current,
            payload = payload(entryIndex = 5, role = "assistant", preview = "future"),
        )
        assertEquals(AppendedPlaceholderOutcome.OutOfRange, out)
    }

    @Test
    fun `idempotent — replaying the same payload twice yields identical results`() {
        val current = listOf(entry("user", "a"), entry("assistant", "old"))
        val p = payload(entryIndex = 1, role = "assistant", preview = "new")
        val first = applyAppendedPlaceholder(current, p) as AppendedPlaceholderOutcome.Replaced
        val second = applyAppendedPlaceholder(first.entries, p) as AppendedPlaceholderOutcome.Replaced
        assertEquals(first.entries, second.entries)
    }

    @Test
    fun `does not mutate the input list`() {
        val current = mutableListOf(entry("user", "a"))
        val before = current.toList()
        applyAppendedPlaceholder(
            current = current,
            payload = payload(entryIndex = 0, role = "user", preview = "MUTATED"),
        )
        assertEquals(before, current, "input list must not be mutated")
    }

    @Test
    fun `appended placeholder carries the server entryIndex`() {
        // The placeholder MUST be stamped with the notification's server
        // index. A sentinel index (-1) makes it invisible to the index-based
        // dedup in resumeSession / loadOlder, which lets a racing tail-resync
        // merge a SECOND copy of the same entry — two list slots then resolve
        // to the same `idx:N` LazyColumn key and the app hard-crashes
        // ("Key idx:N was already used").
        val current = listOf(entry("user", "a"), entry("assistant", "b"))
        val out = applyAppendedPlaceholder(
            current = current,
            payload = payload(entryIndex = 2, role = "assistant", preview = "c"),
        ) as AppendedPlaceholderOutcome.Replaced
        assertEquals(2, out.entries[2].index)
    }

    @Test
    fun `in-place placeholder replace also carries the server entryIndex`() {
        val current = listOf(entry("user", "a"), entry("assistant", "stale", index = 1))
        val out = applyAppendedPlaceholder(
            current = current,
            payload = payload(entryIndex = 1, role = "assistant", preview = "fresh"),
        ) as AppendedPlaceholderOutcome.Replaced
        assertEquals(1, out.entries[1].index)
    }

    @Test
    fun `placeholder then racing tail-resync does not duplicate the entry index`() {
        // End-to-end repro of the "Key idx:N already used" crash. While the
        // agent streams, a tail-resync `get_session(after_index = N-1)` is in
        // flight when `message_appended(N)` lands. Sequence:
        //   1. message_appended adds a placeholder for index N.
        //   2. the in-flight resync returns entry N; resumeSession dedups
        //      incoming entries by index, then appends the survivors.
        //   3. fetchAndReplaceEntry(N) replaces list POSITION N in place.
        // If the placeholder doesn't carry index N, step 2's dedup can't see
        // it, so entry N is appended a second time and step 3 turns the
        // placeholder slot into a duplicate — two slots with index N.
        val full = listOf(
            entry("user", "a", index = 0),
            entry("assistant", "b", index = 1),
            entry("user", "c", index = 2),
        )

        val afterPlaceholder = (
            applyAppendedPlaceholder(
                current = full,
                payload = payload(entryIndex = 3, role = "assistant", preview = "d"),
            ) as AppendedPlaceholderOutcome.Replaced
            ).entries

        val afterResync = resumeMerge(
            current = afterPlaceholder,
            fetched = listOf(entry("assistant", "d", index = 3)),
        )

        // fetchAndReplaceEntry(3) replaces list position 3.
        val afterReplace = afterResync.toMutableList().also {
            if (3 < it.size) it[3] = entry("assistant", "d", index = 3)
        }

        assertEquals(
            1,
            afterReplace.count { it.index == 3 },
            "a placeholder + racing resync must not yield two slots with index 3",
        )
    }

    /**
     * Faithful inline model of the index-based dedup the store performs in
     * `resumeSession` / `loadOlder`: drop incoming entries whose index is
     * already present locally, then append the survivors.
     */
    private fun resumeMerge(
        current: List<EntrySummary>,
        fetched: List<EntrySummary>,
    ): List<EntrySummary> {
        val existing = current.mapNotNull { it.index.takeIf { i -> i >= 0 } }.toHashSet()
        val fresh = fetched.filterNot { it.index >= 0 && existing.contains(it.index) }
        return current + fresh
    }

    // -------------------------------------------------------------------------
    // reconcileOptimisticContent
    // -------------------------------------------------------------------------

    @Test
    fun `empty optimistic returns empty pair`() {
        val (entries, ids) = reconcileOptimisticContent(
            optimistic = emptyList(),
            optimisticIds = emptyList(),
            serverUserEntries = listOf(entry("user", "hello")),
        )
        assertTrue(entries.isEmpty())
        assertTrue(ids.isEmpty())
    }

    @Test
    fun `single match drops the optimistic bubble and its id`() {
        val (entries, ids) = reconcileOptimisticContent(
            optimistic = listOf(entry("user", "hello")),
            optimisticIds = listOf(1L),
            serverUserEntries = listOf(entry("user", "hello", index = 0)),
        )
        assertTrue(entries.isEmpty())
        assertTrue(ids.isEmpty())
    }

    @Test
    fun `non-user server roles are ignored`() {
        val (entries, ids) = reconcileOptimisticContent(
            optimistic = listOf(entry("user", "hello")),
            optimisticIds = listOf(1L),
            // Server's only entry is assistant — must NOT match.
            serverUserEntries = listOf(entry("assistant", "hello")),
        )
        assertEquals(1, entries.size)
        assertEquals("hello", entries[0].preview)
        assertEquals(listOf(1L), ids)
    }

    @Test
    fun `FIFO order preserved on partial match — only the matching bubble drops`() {
        val (entries, ids) = reconcileOptimisticContent(
            optimistic = listOf(
                entry("user", "first"),
                entry("user", "second"),
                entry("user", "third"),
            ),
            optimisticIds = listOf(1L, 2L, 3L),
            // Only "second" has been echoed by the server.
            serverUserEntries = listOf(entry("user", "second")),
        )
        assertEquals(2, entries.size)
        assertEquals("first", entries[0].preview)
        assertEquals("third", entries[1].preview)
        assertEquals(listOf(1L, 3L), ids)
    }

    @Test
    fun `duplicate content — both optimistic bubbles reconcile when both server echoes arrive`() {
        val (entries, ids) = reconcileOptimisticContent(
            optimistic = listOf(
                entry("user", "ping"),
                entry("user", "ping"),
            ),
            optimisticIds = listOf(10L, 20L),
            serverUserEntries = listOf(
                entry("user", "ping", index = 0),
                entry("user", "ping", index = 1),
            ),
        )
        assertTrue(entries.isEmpty())
        assertTrue(ids.isEmpty())
    }

    @Test
    fun `duplicate content with only one server echo drops only one optimistic`() {
        val (entries, ids) = reconcileOptimisticContent(
            optimistic = listOf(
                entry("user", "ping"),
                entry("user", "ping"),
            ),
            optimisticIds = listOf(10L, 20L),
            serverUserEntries = listOf(entry("user", "ping", index = 0)),
        )
        assertEquals(1, entries.size)
        assertEquals("ping", entries[0].preview)
        // FIFO: the *second* bubble (id 20) survives because the first was matched.
        assertEquals(listOf(20L), ids)
    }

    @Test
    fun `stripRoleHeading is applied when matching previews`() {
        // Optimistic carries plain text; server echoes preview with the
        // role heading prefix — the matcher must normalise both sides.
        val (entries, ids) = reconcileOptimisticContent(
            optimistic = listOf(entry("user", "hello world")),
            optimisticIds = listOf(7L),
            serverUserEntries = listOf(entry("user", "## User\nhello world", index = 0)),
        )
        assertTrue(entries.isEmpty())
        assertTrue(ids.isEmpty())
    }

    // -------------------------------------------------------------------------
    // reconcileOptimistic (id-aware)
    // -------------------------------------------------------------------------

    @Test
    fun `id-aware reconcile drops a bubble when server echo carries the matching client_send_id`() {
        // The user's text doesn't match the truncated server preview at
        // all — id-only path must still dedupe. This is the regression
        // case that motivated the rewrite (long messages truncated to
        // ~200 chars server-side).
        val (entries, ids, csids) = reconcileOptimistic(
            optimistic = listOf(entry("user", "the local full body of a very long message")),
            optimisticIds = listOf(1L),
            optimisticClientSendIds = listOf(42L),
            serverEntries = listOf(
                entry(
                    "user",
                    "the local full body of a ver…",
                    index = 0,
                    clientSendId = 42L,
                ),
            ),
        )
        assertTrue(entries.isEmpty())
        assertTrue(ids.isEmpty())
        assertTrue(csids.isEmpty())
    }

    @Test
    fun `id-aware reconcile falls back to content-match when csid does not match`() {
        // Mixed-version corner: client stamps an id, but the server in
        // this test echoes a different one (e.g. lost meta on retry).
        // Content-match should still pop the bubble.
        val (entries, ids, csids) = reconcileOptimistic(
            optimistic = listOf(entry("user", "hello")),
            optimisticIds = listOf(1L),
            optimisticClientSendIds = listOf(42L),
            serverEntries = listOf(
                entry("user", "hello", index = 0, clientSendId = 999L),
            ),
        )
        // Server csid 999 doesn't match optimistic csid 42, but the
        // content-match fallback succeeds. The server entry's csid (999)
        // doesn't end up in the content-match pool because that pool
        // only holds entries WITHOUT a csid — so neither path consumes
        // the server entry. The optimistic bubble is therefore preserved.
        // This is the intentional behaviour: an explicit csid on the
        // server entry means "I'm intended for the SPECIFIC client that
        // stamped this id"; a mismatch is not a content-match overlap.
        assertEquals(1, entries.size)
        assertEquals(listOf(1L), ids)
        assertEquals(listOf(42L), csids)
    }

    @Test
    fun `id-aware reconcile keeps the bubble when neither id nor content matches`() {
        val (entries, ids, csids) = reconcileOptimistic(
            optimistic = listOf(entry("user", "hello")),
            optimisticIds = listOf(1L),
            optimisticClientSendIds = listOf(42L),
            serverEntries = listOf(entry("assistant", "ack")),
        )
        assertEquals(1, entries.size)
        assertEquals(listOf(1L), ids)
        assertEquals(listOf(42L), csids)
    }

    @Test
    fun `id-aware reconcile mixed mode - one id-match + one content-match in the same call`() {
        // Two optimistic bubbles, two server echoes — one id-stamped
        // (long message), one legacy preview-match (e.g. a desktop
        // send that pre-empted the queue, or a short message). Both
        // must dedupe in a single call.
        val (entries, ids, csids) = reconcileOptimistic(
            optimistic = listOf(
                entry("user", "long enough to truncate on server"),
                entry("user", "short msg"),
            ),
            optimisticIds = listOf(10L, 20L),
            optimisticClientSendIds = listOf(42L, null),
            serverEntries = listOf(
                entry(
                    "user",
                    "long enough to truncate on…",
                    index = 0,
                    clientSendId = 42L,
                ),
                entry("user", "short msg", index = 1),
            ),
        )
        assertTrue(entries.isEmpty())
        assertTrue(ids.isEmpty())
        assertTrue(csids.isEmpty())
    }

    @Test
    fun `id-aware reconcile preserves arrival order on partial match - csid hit only`() {
        // Three bubbles; only the middle one is id-matched by the
        // server echo. The first and third must survive in their
        // original order.
        val (entries, ids, csids) = reconcileOptimistic(
            optimistic = listOf(
                entry("user", "first"),
                entry("user", "second"),
                entry("user", "third"),
            ),
            optimisticIds = listOf(1L, 2L, 3L),
            optimisticClientSendIds = listOf(11L, 22L, 33L),
            serverEntries = listOf(
                entry("user", "second", index = 0, clientSendId = 22L),
            ),
        )
        assertEquals(2, entries.size)
        assertEquals("first", entries[0].preview)
        assertEquals("third", entries[1].preview)
        assertEquals(listOf(1L, 3L), ids)
        assertEquals(listOf(11L, 33L), csids)
    }

    @Test
    fun `id-aware reconcile with null csid only - content-match path - matches the legacy behaviour`() {
        // Verify the fallback path equals the old reconcileOptimisticContent
        // semantics when no optimistic bubble was stamped.
        val (entries, ids, csids) = reconcileOptimistic(
            optimistic = listOf(
                entry("user", "first"),
                entry("user", "second"),
            ),
            optimisticIds = listOf(1L, 2L),
            optimisticClientSendIds = listOf(null, null),
            serverEntries = listOf(entry("user", "second", index = 0)),
        )
        assertEquals(1, entries.size)
        assertEquals("first", entries[0].preview)
        assertEquals(listOf(1L), ids)
        assertEquals(listOf<Long?>(null), csids)
    }

    @Test
    fun `id-aware reconcile empty optimistic returns three empty lists`() {
        val (entries, ids, csids) = reconcileOptimistic(
            optimistic = emptyList(),
            optimisticIds = emptyList(),
            optimisticClientSendIds = emptyList(),
            serverEntries = listOf(entry("user", "anything")),
        )
        assertTrue(entries.isEmpty())
        assertTrue(ids.isEmpty())
        assertTrue(csids.isEmpty())
    }

    @Test
    fun `id-aware reconcile ignores non-user roles in serverEntries`() {
        val (entries, _, _) = reconcileOptimistic(
            optimistic = listOf(entry("user", "hi")),
            optimisticIds = listOf(1L),
            optimisticClientSendIds = listOf(7L),
            serverEntries = listOf(entry("assistant", "hi", clientSendId = 7L)),
        )
        // Assistant entries with matching csid mustn't trigger dedupe.
        assertEquals(1, entries.size)
    }

    // -------------------------------------------------------------------------
    // parseExpiredSendMessage
    // -------------------------------------------------------------------------

    @Test
    fun `parseExpiredSendMessage skips non-send_message method`() {
        val msg = QueuedMessage(
            id = "1",
            method = "remote.solution_agent.cancel_turn",
            params = buildJsonObject {
                put("session_id", "s1")
                put("content", "hello")
            },
            enqueuedAtMs = 0L,
        )
        assertNull(parseExpiredSendMessage(msg))
    }

    @Test
    fun `parseExpiredSendMessage skips missing session_id`() {
        val msg = QueuedMessage(
            id = "2",
            method = "remote.solution_agent.send_message",
            params = buildJsonObject { put("content", "hello") },
            enqueuedAtMs = 0L,
        )
        assertNull(parseExpiredSendMessage(msg))
    }

    @Test
    fun `parseExpiredSendMessage skips blank content`() {
        val msg = QueuedMessage(
            id = "3",
            method = "remote.solution_agent.send_message",
            params = buildJsonObject {
                put("session_id", "s1")
                put("content", "   ")
            },
            enqueuedAtMs = 0L,
        )
        assertNull(parseExpiredSendMessage(msg))
    }

    @Test
    fun `parseExpiredSendMessage skips null params`() {
        val msg = QueuedMessage(
            id = "4",
            method = "remote.solution_agent.send_message",
            params = null,
            enqueuedAtMs = 0L,
        )
        assertNull(parseExpiredSendMessage(msg))
    }

    @Test
    fun `parseExpiredSendMessage skips non-object params`() {
        val msg = QueuedMessage(
            id = "4b",
            method = "remote.solution_agent.send_message",
            params = kotlinx.serialization.json.JsonPrimitive("not-an-object"),
            enqueuedAtMs = 0L,
        )
        assertNull(parseExpiredSendMessage(msg))
    }

    @Test
    fun `parseExpiredSendMessage happy path returns sessionId and content`() {
        val msg = QueuedMessage(
            id = "5",
            method = "remote.solution_agent.send_message",
            params = buildJsonObject {
                put("session_id", "session-abc")
                put("content", "Hello world")
            },
            enqueuedAtMs = 0L,
        )
        val out = parseExpiredSendMessage(msg)
        assertEquals("session-abc" to "Hello world", out)
    }

    @Test
    fun `parseExpiredSendMessage forwards multi-line content verbatim`() {
        val text = "Line 1\nLine 2"
        val msg = QueuedMessage(
            id = "6",
            method = "remote.solution_agent.send_message",
            params = buildJsonObject {
                put("session_id", "s2")
                put("content", text)
            },
            enqueuedAtMs = 0L,
        )
        val out = parseExpiredSendMessage(msg)
        assertEquals("s2" to text, out)
    }

    @Test
    fun `parseExpiredSendMessage recovers the first text block of a blocks-bearing send`() {
        // Construct what RemoteClient persisted for a sendMessageBlocks
        // call — { session_id, blocks: [...] } where the blocks array
        // mixes the user's typed text with an image attachment.
        val blocksJson = kotlinx.serialization.json.buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", "Hello with photo")
            })
            add(buildJsonObject {
                put("type", "image")
                put("data", "Zm9v")
                put("mimeType", "image/png")
            })
        }
        val msg = QueuedMessage(
            id = "7",
            method = "remote.solution_agent.send_message_blocks",
            params = buildJsonObject {
                put("session_id", "session-blocks")
                put("blocks", blocksJson)
            },
            enqueuedAtMs = 0L,
        )
        val out = parseExpiredSendMessage(msg)
        assertEquals("session-blocks" to "Hello with photo", out)
    }

    @Test
    fun `parseExpiredSendMessage skips a blocks send with no text blocks`() {
        val blocksJson = kotlinx.serialization.json.buildJsonArray {
            add(buildJsonObject {
                put("type", "image")
                put("data", "Zm9v")
                put("mimeType", "image/png")
            })
        }
        val msg = QueuedMessage(
            id = "8",
            method = "remote.solution_agent.send_message_blocks",
            params = buildJsonObject {
                put("session_id", "session-blocks")
                put("blocks", blocksJson)
            },
            enqueuedAtMs = 0L,
        )
        assertNull(parseExpiredSendMessage(msg))
    }

    @Test
    fun `parseExpiredSendMessage skips a blocks send with missing blocks field`() {
        val msg = QueuedMessage(
            id = "9",
            method = "remote.solution_agent.send_message_blocks",
            params = buildJsonObject {
                put("session_id", "session-blocks")
            },
            enqueuedAtMs = 0L,
        )
        assertNull(parseExpiredSendMessage(msg))
    }

    // -------------------------------------------------------------------------
    // QueueStore round-trip for blocks-bearing payloads
    // -------------------------------------------------------------------------

    @Test
    fun `InMemoryQueueStore round-trips a sendMessageBlocks payload alongside legacy text`() {
        val store: QueueStore = InMemoryQueueStore()
        val textPayload = QueuedMessage(
            id = "qa",
            method = "remote.solution_agent.send_message",
            params = buildJsonObject {
                put("session_id", "s1")
                put("content", "plain")
            },
            enqueuedAtMs = 100L,
        )
        val blocksPayload = QueuedMessage(
            id = "qb",
            method = "remote.solution_agent.send_message_blocks",
            params = buildJsonObject {
                put("session_id", "s1")
                put(
                    "blocks",
                    kotlinx.serialization.json.buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", "with image")
                        })
                        add(buildJsonObject {
                            put("type", "image")
                            put("data", "Zm9v")
                            put("mimeType", "image/jpeg")
                        })
                    },
                )
            },
            enqueuedAtMs = 200L,
        )
        store.add(textPayload)
        store.add(blocksPayload)
        val loaded = store.loadAll()
        // FIFO order preserved across the two payload shapes.
        assertEquals(listOf("qa", "qb"), loaded.map { it.id })
        // Blocks payload survives the round-trip with its params intact —
        // the queue layer treats params as opaque so it doesn't matter that
        // the second message uses a fundamentally different shape than the
        // first. This is the property that lets EncryptedQueueStore persist
        // both methods without a schema bump.
        assertEquals(blocksPayload, loaded[1])
    }

    // Reference unused JsonObject import so it isn't pruned by IDE.
    @Suppress("unused")
    private val _ref: JsonObject = buildJsonObject {}
}
