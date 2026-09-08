package ru.sipaha.sawe.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Cross-process replay gate: what [QueueController] may put back on the
 * wire after the process that wrote the frame died.
 *
 * The whole question is decided by two facts on the record —
 * [QueuedCall.rehydrated] ("a PREVIOUS process queued this") and
 * [QueuedMessage.attempt] ("a frame was already written for it, to that
 * editor process") — plus a `:app`-supplied predicate. The invariants under
 * test, in the order they matter:
 *
 *  1. A rehydrated record with NO attempt is a first delivery and must go
 *     out unconditionally. This is the durable offline queue itself; a
 *     regression here bounces typed-offline text on every cold start.
 *  2. A rehydrated record WITH an attempt goes out only if the predicate
 *     says the desktop would absorb the repeat.
 *  3. Items THIS process queued are never gated at all — that ambiguity is
 *     owned by the still-live caller, see the [TransportLostException]
 *     branch in `dispatchOne`.
 *  4. The provenance stamp is written ahead of the frame, names the peer
 *     the frame actually goes to, and is not rewritten for nothing.
 *
 * Drives [QueueController] directly rather than through [RemoteClient]:
 * the gate is a queue-side decision and this keeps the fakes down to a
 * store, a transport stub and two lambdas.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QueueControllerReplayGateTest {

    private object NoopTransport : RemoteTransport {
        override fun send(text: String): Boolean = true
        override fun send(bytes: ByteArray): Boolean = true
        override fun close(code: Int, reason: String) {}
    }

    /**
     * [QueueStore] that counts writes. `EncryptedQueueStore.add` rewrites
     * the whole encrypted blob, so "we don't re-stamp an unchanged
     * instance" is a claim about disk churn and has to be checkable.
     */
    private class CountingStore(
        private val delegate: QueueStore = InMemoryQueueStore(),
    ) : QueueStore {
        var addCount: Int = 0
            private set

        override fun loadAll(): List<QueuedMessage> = delegate.loadAll()

        override fun add(message: QueuedMessage) {
            addCount++
            delegate.add(message)
        }

        override fun remove(id: String) = delegate.remove(id)

        override fun clear() = delegate.clear()
    }

    /** Everything the controller under test is wired to, mutable per step. */
    private class Fixture {
        val store = CountingStore()
        val expired = mutableListOf<QueuedMessage>()

        /** Ids of the messages whose frames reached [callRpc], in wire order. */
        val wire = mutableListOf<String>()

        /** Messages the replay gate was consulted about, in call order. */
        val gateSaw = mutableListOf<QueuedMessage>()

        /** How many times the bounded dedupe wait was paid for. */
        var awaitCalls: Int = 0

        var features: ServerFeatures = ServerFeatures.NONE
        var transport: RemoteTransport? = NoopTransport
        var connectionState: ConnectionState = ConnectionState.Connected
        var replayGate: ((QueuedMessage, ServerFeatures) -> Boolean)? = null

        /** Response (or failure) for the message with the given id. */
        var respond: (String) -> JsonRpcResponse = { JsonRpcResponse(id = 1L) }

        fun denyingGate(): (QueuedMessage, ServerFeatures) -> Boolean = { msg, _ ->
            gateSaw += msg
            false
        }

        fun allowingGate(): (QueuedMessage, ServerFeatures) -> Boolean = { msg, _ ->
            gateSaw += msg
            true
        }
    }

    private fun TestScope.newController(f: Fixture): QueueController = QueueController(
        scope = this,
        nowMs = { testScheduler.currentTime },
        queueStore = f.store,
        onMessageExpired = { f.expired += it },
        stateLock = Any(),
        transportAccessor = { f.transport },
        callRpc = { _, params, onSent ->
            val id = params!!.jsonObject["id"]!!.jsonPrimitive.content
            f.wire += id
            onSent?.invoke(true)
            f.respond(id)
        },
        events = Channel(Channel.UNLIMITED),
        connectionState = MutableStateFlow(f.connectionState),
        currentFeatures = { f.features },
        awaitDedupeFeatures = {
            f.awaitCalls++
            f.features
        },
        replayGate = f.replayGate,
    )

    private fun msg(
        id: String,
        enqueuedAtMs: Long = 0L,
        attempt: QueuedSendAttempt? = null,
    ): QueuedMessage = QueuedMessage(
        id = id,
        method = "remote.solution_agent.send_message_blocks",
        params = buildJsonObject { put("id", id) },
        enqueuedAtMs = enqueuedAtMs,
        attempt = attempt,
    )

    private fun deduping(instance: String): ServerFeatures = ServerFeatures(
        tokens = setOf(WireFeature.CSID_DEDUPE),
        serverInstanceId = instance,
        csidDedupeWindowMs = 86_400_000L,
    )

    // ---- 1. never attempted: the offline queue's headline case ----------

    @Test
    fun `rehydrated record that never reached a transport is sent without asking the gate`() =
        runTest(StandardTestDispatcher()) {
            val f = Fixture()
            f.replayGate = f.denyingGate()
            f.store.add(msg("a"))
            val controller = newController(f)
            controller.rehydrate()
            controller.flushQueue()
            advanceUntilIdle()

            assertEquals(listOf("a"), f.wire, "a first delivery must go out")
            assertTrue(f.gateSaw.isEmpty(), "gate must not even be consulted: ${f.gateSaw}")
            assertTrue(f.expired.isEmpty(), "nothing to bounce")
            assertTrue(f.store.loadAll().isEmpty(), "record removed on success")
        }

    @Test
    fun `a flush with nothing to gate never pays for the dedupe wait`() =
        runTest(StandardTestDispatcher()) {
            val f = Fixture()
            f.replayGate = f.denyingGate()
            f.store.add(msg("a"))
            f.store.add(msg("b", enqueuedAtMs = 1L))
            val controller = newController(f)
            controller.rehydrate()
            controller.flushQueue()
            advanceUntilIdle()

            assertEquals(listOf("a", "b"), f.wire)
            assertEquals(0, f.awaitCalls, "no verdict is needed, so no wait may be entered")
        }

    // ---- 2. attempted + refused ----------------------------------------

    @Test
    fun `rehydrated record with an attempt is abandoned when the gate refuses`() =
        runTest(StandardTestDispatcher()) {
            val f = Fixture()
            f.replayGate = f.denyingGate()
            val stored = msg("a", attempt = QueuedSendAttempt(atMs = 0L, instanceId = "inst-1"))
            f.store.add(stored)
            val controller = newController(f)
            controller.rehydrate()
            val orphaned: Deferred<JsonRpcResponse> = controller.parkedDeferredsForTest().single()
            controller.flushQueue()
            advanceUntilIdle()

            assertTrue(f.wire.isEmpty(), "nothing may go on the wire: ${f.wire}")
            assertTrue(f.store.loadAll().isEmpty(), "the record is dropped, not left to retry")
            assertEquals(listOf(stored), f.expired, "bounced exactly once")
            assertEquals(1, f.awaitCalls, "the verdict is resolved once per flush")
            assertFailsWith<ReplayNotSafeException> { orphaned.await() }
        }

    @Test
    fun `rehydrated record with an attempt is sent when the gate allows`() =
        runTest(StandardTestDispatcher()) {
            val f = Fixture()
            f.replayGate = f.allowingGate()
            f.features = deduping("inst-1")
            val stored = msg("a", attempt = QueuedSendAttempt(atMs = 0L, instanceId = "inst-1"))
            f.store.add(stored)
            val controller = newController(f)
            controller.rehydrate()
            val orphaned = controller.parkedDeferredsForTest().single()
            controller.flushQueue()
            advanceUntilIdle()

            assertEquals(listOf("a"), f.wire)
            assertEquals(listOf(stored), f.gateSaw, "the gate sees the persisted record")
            assertTrue(f.expired.isEmpty(), "an allowed replay is not a bounce")
            assertTrue(f.store.loadAll().isEmpty(), "record removed on success")
            assertEquals(1L, orphaned.await().id)
        }

    @Test
    fun `the gate is handed the feature set of the connection that would carry the replay`() =
        runTest(StandardTestDispatcher()) {
            val f = Fixture()
            val seen = mutableListOf<ServerFeatures>()
            f.replayGate = { _, live ->
                seen += live
                true
            }
            f.features = deduping("inst-9")
            f.store.add(msg("a", attempt = QueuedSendAttempt(atMs = 0L, instanceId = "inst-9")))
            val controller = newController(f)
            controller.rehydrate()
            controller.flushQueue()
            advanceUntilIdle()

            assertEquals(listOf(deduping("inst-9")), seen)
        }

    // ---- 3. this process's own items are never gated --------------------

    @Test
    fun `a freshly queued item is never gated, not even after a transport loss and re-flush`() =
        runTest(StandardTestDispatcher()) {
            val f = Fixture()
            f.replayGate = f.denyingGate()
            f.features = deduping("inst-1")
            f.connectionState = ConnectionState.Disconnected
            val controller = newController(f)

            var outcome: Result<JsonRpcResponse>? = null
            launch {
                outcome = runCatching {
                    controller.queueCall(
                        method = "remote.solution_agent.send_message_blocks",
                        params = buildJsonObject { put("id", "a") },
                        ttlMs = RemoteClient.DEFAULT_QUEUE_TTL_MS,
                        messageId = "a",
                    )
                }
            }
            runCurrent()
            assertEquals(listOf("a"), f.store.loadAll().map { it.id }, "parked on disk")

            // First flush: the frame is written and the socket dies before
            // the answer — the ambiguous case the in-process path retries.
            f.respond = { throw TransportLostException() }
            controller.flushQueue()
            runCurrent()
            assertEquals(listOf("a"), f.wire)
            assertNotNull(f.store.loadAll().single().attempt, "provenance survives the loss")

            // Second flush: the record now carries an attempt, but it was
            // never rehydrated, so the gate stays out of it.
            f.respond = { JsonRpcResponse(id = 7L) }
            controller.flushQueue()
            advanceUntilIdle()

            assertEquals(listOf("a", "a"), f.wire, "the ambiguous send is retried, not bounced")
            assertTrue(f.gateSaw.isEmpty(), "own-process items are never gated: ${f.gateSaw}")
            assertEquals(0, f.awaitCalls, "and never pay for the dedupe wait")
            assertTrue(f.expired.isEmpty())
            assertEquals(7L, outcome?.getOrNull()?.id)
        }

    // ---- 4. write-ahead provenance --------------------------------------

    @Test
    fun `provenance is written before the frame and names the instance it went to`() =
        runTest(StandardTestDispatcher()) {
            val f = Fixture()
            f.features = deduping("inst-1")
            f.connectionState = ConnectionState.Disconnected
            val controller = newController(f)

            launch {
                runCatching {
                    controller.queueCall(
                        method = "remote.solution_agent.send_message_blocks",
                        params = buildJsonObject { put("id", "a") },
                        ttlMs = RemoteClient.DEFAULT_QUEUE_TTL_MS,
                        messageId = "a",
                    )
                }
            }
            runCurrent()
            val addsAfterQueueing = f.store.addCount
            assertEquals(1, addsAfterQueueing, "one write to park it")

            f.respond = { throw TransportLostException() }
            controller.flushQueue()
            runCurrent()

            val afterLoss = f.store.loadAll().single()
            val attempt = assertNotNull(afterLoss.attempt, "the kill window must not lose the fact")
            assertEquals("inst-1", attempt.instanceId)
            assertEquals(testScheduler.currentTime, attempt.atMs)
            assertEquals(2, f.store.addCount, "exactly one rewrite for the stamp")

            // Same instance on the retry: nothing new to record, so no
            // second full-blob rewrite.
            controller.flushQueue()
            runCurrent()
            assertEquals(listOf("a", "a"), f.wire)
            assertEquals(2, f.store.addCount, "an unchanged instance must not churn the disk")
        }

    @Test
    fun `provenance records no instance when the peer advertises no dedupe`() =
        runTest(StandardTestDispatcher()) {
            val f = Fixture()
            // A live connection with an instance id but WITHOUT the token —
            // e.g. an older desktop, or the window before the capabilities
            // probe answers. Recording the id would let a later gate treat
            // the repeat as provably absorbed by a table that isn't there.
            f.features = ServerFeatures(serverInstanceId = "inst-1")
            f.connectionState = ConnectionState.Disconnected
            val controller = newController(f)

            launch {
                runCatching {
                    controller.queueCall(
                        method = "remote.solution_agent.send_message_blocks",
                        params = buildJsonObject { put("id", "a") },
                        ttlMs = RemoteClient.DEFAULT_QUEUE_TTL_MS,
                        messageId = "a",
                    )
                }
            }
            runCurrent()
            f.respond = { throw TransportLostException() }
            controller.flushQueue()
            runCurrent()

            val attempt = assertNotNull(f.store.loadAll().single().attempt)
            assertNull(attempt.instanceId, "no dedupe token means no provable instance")
        }

    // ---- 5. FIFO under a partial denial ---------------------------------

    @Test
    fun `FIFO survives a denial in the middle of the queue`() =
        runTest(StandardTestDispatcher()) {
            val f = Fixture()
            f.features = deduping("inst-1")
            f.replayGate = { m, _ ->
                f.gateSaw += m
                m.id != "b"
            }
            f.store.add(msg("a", enqueuedAtMs = 0L, attempt = QueuedSendAttempt(0L, "inst-1")))
            f.store.add(msg("b", enqueuedAtMs = 1L, attempt = QueuedSendAttempt(0L, "inst-1")))
            f.store.add(msg("c", enqueuedAtMs = 2L, attempt = QueuedSendAttempt(0L, "inst-1")))
            val controller = newController(f)
            controller.rehydrate()
            controller.flushQueue()
            advanceUntilIdle()

            assertEquals(listOf("a", "c"), f.wire, "the survivors keep their order")
            assertEquals(listOf("b"), f.expired.map { it.id }, "only the refused one bounces")
            assertTrue(f.store.loadAll().isEmpty(), "all three records are settled")
            assertEquals(1, f.awaitCalls, "one verdict for the whole flush, not one per item")
        }

    // ---- 6. the on-disk contract ----------------------------------------

    @Test
    fun `a blob written before the attempt field decodes with no attempt`() {
        val json = Json { ignoreUnknownKeys = true }
        val legacy = """
            {"id":"a","method":"remote.solution_agent.send_message_blocks",
             "params":{"id":"a"},"enqueuedAtMs":17}
        """.trimIndent()
        val decoded = json.decodeFromString(QueuedMessage.serializer(), legacy)
        assertNull(decoded.attempt, "absent means never handed to a transport")
        assertEquals(17L, decoded.enqueuedAtMs)
    }

    @Test
    fun `an attempt survives a serialization round trip`() {
        val json = Json { ignoreUnknownKeys = true }
        val original = msg("a", enqueuedAtMs = 17L, attempt = QueuedSendAttempt(99L, "inst-1"))
        val encoded = json.encodeToString(QueuedMessage.serializer(), original)
        assertTrue("at_ms" in encoded, "snake_case on the wire-adjacent blob: $encoded")
        assertTrue("instance_id" in encoded, encoded)
        assertEquals(original, json.decodeFromString(QueuedMessage.serializer(), encoded))
    }

    @Test
    fun `an attempt without an instance id round trips as null`() {
        val json = Json { ignoreUnknownKeys = true }
        val original = msg("a", attempt = QueuedSendAttempt(atMs = 5L))
        val decoded = json.decodeFromString(
            QueuedMessage.serializer(),
            json.encodeToString(QueuedMessage.serializer(), original),
        )
        assertEquals(5L, decoded.attempt?.atMs)
        assertNull(decoded.attempt?.instanceId)
    }
}
