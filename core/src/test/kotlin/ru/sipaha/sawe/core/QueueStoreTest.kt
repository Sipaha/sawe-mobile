package ru.sipaha.sawe.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Interface contract test for any [QueueStore] implementation.
 *
 * Right now only [InMemoryQueueStore] lives in `:core`; the `:app`-only
 * `EncryptedQueueStore` is exercised manually since Android-only types
 * (Context, the Android Keystore) can't be unit-tested here without
 * Robolectric.
 * The contract test enforces the cross-implementation invariants that
 * `RemoteClient.rehydrateQueue` / `flushQueue` depend on:
 *
 *   - `loadAll` returns entries in FIFO order by `enqueuedAtMs`.
 *   - `add` preserves insertion order across multiple calls.
 *   - `remove(id)` is idempotent — removing a non-existent id is a no-op.
 *   - `clear` empties the store but doesn't break subsequent `add`.
 */
class QueueStoreTest {

    private fun newMsg(id: String, content: String, enqueuedAtMs: Long): QueuedMessage =
        QueuedMessage(
            id = id,
            method = "remote.solution_agent.send_message",
            params = buildJsonObject {
                put("session_id", "s1")
                put("content", content)
            },
            enqueuedAtMs = enqueuedAtMs,
        )

    @Test
    fun `empty store returns empty list`() {
        val store: QueueStore = InMemoryQueueStore()
        assertTrue(store.loadAll().isEmpty())
    }

    @Test
    fun `add then loadAll returns the entry`() {
        val store: QueueStore = InMemoryQueueStore()
        val msg = newMsg("id1", "hello", 100L)
        store.add(msg)
        val loaded = store.loadAll()
        assertEquals(1, loaded.size)
        assertEquals(msg, loaded[0])
    }

    @Test
    fun `FIFO order preserved across add and remove`() {
        val store: QueueStore = InMemoryQueueStore()
        store.add(newMsg("a", "first", 100L))
        store.add(newMsg("b", "second", 200L))
        store.add(newMsg("c", "third", 300L))
        assertEquals(listOf("a", "b", "c"), store.loadAll().map { it.id })
        store.remove("b")
        assertEquals(listOf("a", "c"), store.loadAll().map { it.id })
    }

    @Test
    fun `remove of unknown id is a no-op`() {
        val store: QueueStore = InMemoryQueueStore()
        store.add(newMsg("a", "first", 100L))
        store.remove("does-not-exist")
        assertEquals(1, store.loadAll().size)
    }

    @Test
    fun `clear empties the store and add still works afterwards`() {
        val store: QueueStore = InMemoryQueueStore()
        store.add(newMsg("a", "first", 100L))
        store.add(newMsg("b", "second", 200L))
        store.clear()
        assertTrue(store.loadAll().isEmpty())
        store.add(newMsg("c", "third", 300L))
        assertEquals(listOf("c"), store.loadAll().map { it.id })
    }

    @Test
    fun `re-add with same id replaces the existing entry`() {
        // RemoteClient never re-adds the same UUID, but the contract is
        // "id is the key" — putting twice should not double-insert.
        val store: QueueStore = InMemoryQueueStore()
        store.add(newMsg("a", "first", 100L))
        store.add(newMsg("a", "first-edited", 150L))
        val loaded = store.loadAll()
        assertEquals(1, loaded.size)
        assertEquals(150L, loaded[0].enqueuedAtMs)
    }
}
