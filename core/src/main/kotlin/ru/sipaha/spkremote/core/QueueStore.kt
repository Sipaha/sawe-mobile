package ru.sipaha.spkremote.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One outbound JSON-RPC call awaiting a live connection.
 *
 * Tied to a stable [id] (UUID) so a disk-backed [QueueStore] can dedup
 * across crash-recovery boundaries — if the app dies mid-flight after
 * the server received a frame but before it returned a response, we
 * don't want the replay to fabricate a duplicate user message on
 * resume. (Server-side idempotency is the canonical guard; client-side
 * id-tracking is the cheap second line.)
 *
 * [enqueuedAtMs] is the source of truth for FIFO ordering and TTL
 * arithmetic.
 */
@Serializable
data class QueuedMessage(
    val id: String,
    val method: String,
    val params: JsonElement?,
    val enqueuedAtMs: Long,
)

/**
 * Persistence interface for [RemoteClient]'s outbound queue.
 *
 * Two implementations live in this codebase:
 *  - [InMemoryQueueStore] — used by `:cli`, `:core` tests, and the
 *    default builder for backwards compatibility.
 *  - `EncryptedQueueStore` in `:app/data` — disk-backed via
 *    EncryptedSharedPreferences so a typed-but-unsent message survives
 *    a process kill.
 *
 * **Concurrency:** all methods are called from the [RemoteClient]
 * lifecycle coroutine or under [stateLock] — implementations don't
 * need to be thread-safe across arbitrary threads, but they must
 * tolerate being re-entered (e.g. add → loadAll back-to-back).
 *
 * **Ordering:** [loadAll] must return entries sorted ascending by
 * [QueuedMessage.enqueuedAtMs]. [RemoteClient.flushQueue] re-sorts
 * defensively, but a correctly-ordered store keeps the post-restart
 * replay deterministic.
 */
interface QueueStore {
    /** Snapshot of all queued messages, FIFO-ordered by `enqueuedAtMs`. */
    fun loadAll(): List<QueuedMessage>

    /** Append a message. Implementations rewrite the full blob if needed. */
    fun add(message: QueuedMessage)

    /** Remove by [id]. No-op if the message is not present. */
    fun remove(id: String)

    /** Empty the store. Used on `forgetPairing` + tests. */
    fun clear()
}

/**
 * Process-local in-memory implementation — preserves R-6a's behavior
 * for `:cli`, `:core` tests, and any non-Android consumer.
 *
 * Tracks insertion order via a [LinkedHashMap] keyed on
 * [QueuedMessage.id] so [remove] is O(1) while [loadAll] returns the
 * full list in arrival order.
 */
class InMemoryQueueStore : QueueStore {
    private val entries = LinkedHashMap<String, QueuedMessage>()

    @Synchronized
    override fun loadAll(): List<QueuedMessage> = entries.values.toList()

    @Synchronized
    override fun add(message: QueuedMessage) {
        entries[message.id] = message
    }

    @Synchronized
    override fun remove(id: String) {
        entries.remove(id)
    }

    @Synchronized
    override fun clear() {
        entries.clear()
    }
}
