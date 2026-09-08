package ru.sipaha.sawe.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * What `close()` owes each item a flush had claimed — decided by
 * [QueuedCall.frameState], not by membership of the in-flight set.
 *
 * [QueueController.expireStaleEntries] claims every survivor into
 * `inFlight` BEFORE the dispatch coordinator has written a byte, so the
 * old "everything in `inFlight` is on the wire" rule bounced messages that
 * never touched a socket: their [QueueStore] records were dropped and
 * their text was pushed back into the composer. The invariants here:
 *
 *  1. A claim whose frame was never attempted is [CloseDrain.parked] — the
 *     record stays for the next client.
 *  2. So is a claim whose frame the transport REFUSED: that is provably
 *     not sent, the same fact [FrameRefusedException] carries everywhere
 *     else.
 *  3. A frame that was written, or that was still inside the write when
 *     the close landed, is [CloseDrain.onTheWire] — delivery is unknown
 *     and a blind replay would post it twice.
 *  4. `parked` comes back in enqueue order: the unwritten claims were
 *     drained from the HEAD of the deque and must go back in front of
 *     whatever queued up behind them.
 *
 * Drives [QueueController] directly (same shape as
 * [QueueControllerReplayGateTest]) because the states that matter are
 * mid-dispatch ones: a coordinator parked on the replay gate's bounded
 * feature wait, and a child sitting between its send and its response.
 * [RemoteClientAuditRegressionTest] covers the end-to-end consequences —
 * which exception each caller gets and which records survive.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QueueControllerCloseDrainTest {

    private object NoopTransport : RemoteTransport {
        override fun send(text: String): Boolean = true
        override fun send(bytes: ByteArray): Boolean = true
        override fun close(code: Int, reason: String) {}
    }

    /** Everything the controller under test is wired to, mutable per step. */
    private class Fixture {
        val store: QueueStore = InMemoryQueueStore()
        val expired = mutableListOf<QueuedMessage>()

        /** Ids whose frames reached [callRpc], in wire order. */
        val wire = mutableListOf<String>()

        /** `onSent` invocations as (id, written), in order. */
        val sendSignals = mutableListOf<Pair<String, Boolean>>()

        var transport: RemoteTransport? = NoopTransport

        /**
         * Gate for the cross-process replay verdict. Never completed by
         * default, which parks the coordinator exactly where
         * `REPLAY_GATE_TIMEOUT_MS` parks it against a peer that hasn't
         * answered the capabilities probe.
         */
        val dedupeWait = CompletableDeferred<ServerFeatures>()

        /**
         * What the fake transport does with each id's frame: accept it
         * (`onSent(true)` and hang waiting for a response), or refuse it.
         */
        var refuse: (String) -> Boolean = { false }
    }

    private fun TestScope.newController(f: Fixture): QueueController = QueueController(
        // [backgroundScope], not the test body's own scope: several of
        // these tests deliberately leave the dispatch coordinator parked
        // forever (on the dedupe verdict, or on a response that never
        // comes) — that is the state under test — so the scope has to be
        // one `runTest` tears down rather than waits for.
        scope = backgroundScope,
        nowMs = { testScheduler.currentTime },
        queueStore = f.store,
        onMessageExpired = { f.expired += it },
        stateLock = Any(),
        transportAccessor = { f.transport },
        callRpc = { _, params, onSent ->
            val id = params!!.jsonObject["id"]!!.jsonPrimitive.content
            if (f.refuse(id)) {
                f.sendSignals += id to false
                onSent?.invoke(false)
                throw FrameRefusedException()
            }
            f.wire += id
            f.sendSignals += id to true
            onSent?.invoke(true)
            // Never answers: leaves the child parked between its send and
            // its response, which is where a close() lands mid-flush.
            CompletableDeferred<JsonRpcResponse>().await()
        },
        events = Channel(Channel.UNLIMITED),
        connectionState = MutableStateFlow(ConnectionState.Connected),
        currentFeatures = { ServerFeatures.NONE },
        awaitDedupeFeatures = { f.dedupeWait.await() },
        replayGate = { _, _ -> true },
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

    private fun ids(items: List<QueuedCall>): List<String> = items.map { it.message.id }

    // ---- 1. the widened window: parked on the replay gate ---------------

    @Test
    fun `a claim still waiting on the dedupe verdict is parked, kept and not bounced`() =
        runTest(StandardTestDispatcher()) {
            val f = Fixture()
            // Rehydrated + already attempted = the only shape that arms the
            // bounded feature wait, which is what widened this window to
            // REPLAY_GATE_TIMEOUT_MS.
            f.store.add(msg("a", attempt = QueuedSendAttempt(atMs = 0L, instanceId = "inst-1")))
            val controller = newController(f)
            controller.rehydrate()
            controller.flushQueue()
            runCurrent()

            assertTrue(f.wire.isEmpty(), "the verdict has not landed, so nothing may be sent")

            val drain = controller.drainOnClose()
            assertEquals(listOf("a"), ids(drain.parked), "never written = still queued")
            assertTrue(drain.onTheWire.isEmpty(), "nothing reached the transport: ${drain.onTheWire}")
            assertEquals(listOf("a"), f.store.loadAll().map { it.id }, "the record must survive")
            assertTrue(f.expired.isEmpty(), "and must not bounce to the composer: ${f.expired}")
        }

    // ---- 2. refused frames are provably not sent ------------------------

    @Test
    fun `a claim whose frame the transport refused is parked, not bounced`() =
        runTest(StandardTestDispatcher()) {
            val f = Fixture()
            f.refuse = { it == "a" }
            f.store.add(msg("a", enqueuedAtMs = 0L))
            f.store.add(msg("b", enqueuedAtMs = 1L))
            val controller = newController(f)
            controller.rehydrate()
            controller.flushQueue()
            runCurrent()

            // "a" was refused and opened the gate; "b" was accepted and is
            // still waiting for its response. Neither child has settled
            // with the coordinator, so both are still claimed.
            assertEquals(listOf("a" to false, "b" to true), f.sendSignals)
            assertEquals(listOf("b"), f.wire)

            val drain = controller.drainOnClose()
            assertEquals(listOf("a"), ids(drain.parked), "a refused frame provably never went out")
            assertEquals(listOf("b"), ids(drain.onTheWire), "an accepted frame is delivery-unknown")
        }

    // ---- 3. an accepted frame is still delivery-unknown -----------------

    @Test
    fun `a claim whose frame was accepted is reported on the wire`() =
        runTest(StandardTestDispatcher()) {
            val f = Fixture()
            f.store.add(msg("a"))
            val controller = newController(f)
            controller.rehydrate()
            controller.flushQueue()
            runCurrent()

            assertEquals(listOf("a"), f.wire)
            val drain = controller.drainOnClose()
            assertEquals(listOf("a"), ids(drain.onTheWire))
            assertTrue(drain.parked.isEmpty(), "a written frame is not 'still queued': ${drain.parked}")
        }

    // ---- 4. FIFO across the two collections -----------------------------

    @Test
    fun `unwritten claims come back ahead of everything queued behind them`() =
        runTest(StandardTestDispatcher()) {
            val f = Fixture()
            f.store.add(msg("a", enqueuedAtMs = 0L, attempt = QueuedSendAttempt(0L, "inst-1")))
            f.store.add(msg("b", enqueuedAtMs = 1L, attempt = QueuedSendAttempt(0L, "inst-1")))
            val controller = newController(f)
            controller.rehydrate()
            controller.flushQueue()
            runCurrent()
            // Both are claimed and parked on the dedupe verdict.

            // The user types a third message while that flush is stuck. It
            // cannot skip the queue (the flush holds the in-flight set), so
            // it lands in the deque BEHIND the two claims.
            backgroundScope.launch {
                runCatching {
                    controller.queueCall(
                        method = "remote.solution_agent.send_message_blocks",
                        params = buildJsonObject { put("id", "c") },
                        ttlMs = RemoteClient.DEFAULT_QUEUE_TTL_MS,
                        messageId = "c",
                    )
                }
            }
            runCurrent()
            assertTrue(f.wire.isEmpty(), "nothing has been sent at all")

            val drain = controller.drainOnClose()
            assertEquals(
                listOf("a", "b", "c"),
                ids(drain.parked),
                "the next client must send them in the order the user typed them",
            )
            assertTrue(drain.onTheWire.isEmpty())
            assertEquals(
                setOf("a", "b", "c"),
                f.store.loadAll().map { it.id }.toSet(),
                "all three records survive for the next client",
            )
            assertTrue(f.expired.isEmpty(), "nothing bounces: ${f.expired}")
        }

    // ---- 5. the gate chain still opens on every path --------------------

    @Test
    fun `every dispatch signals exactly once and each signal opens the next gate`() =
        runTest(StandardTestDispatcher()) {
            val f = Fixture()
            // Middle item refused: if a refusal did not open its gate, the
            // third item would never be dispatched at all.
            f.refuse = { it == "b" }
            f.store.add(msg("a", enqueuedAtMs = 0L))
            f.store.add(msg("b", enqueuedAtMs = 1L))
            f.store.add(msg("c", enqueuedAtMs = 2L))
            val controller = newController(f)
            controller.rehydrate()
            controller.flushQueue()
            runCurrent()

            assertEquals(
                listOf("a" to true, "b" to false, "c" to true),
                f.sendSignals,
                "one signal per dispatch, in queue order, carrying the transport's verdict",
            )
            assertEquals(listOf("a", "c"), f.wire, "a refusal must not block the rest of the flush")
        }
}
