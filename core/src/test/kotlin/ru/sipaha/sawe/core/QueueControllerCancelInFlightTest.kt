package ru.sipaha.sawe.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Cancelling a send that a flush has CLAIMED but not written.
 *
 * [QueueController.expireStaleEntries] moves every survivor into the
 * in-flight set before the dispatch coordinator writes a byte, and the
 * cross-process replay gate's bounded feature wait
 * ([QueueController.REPLAY_GATE_TIMEOUT_MS], 30 s) can hold it there —
 * so "claimed" and "written" are up to half a minute apart, which is
 * exactly the interval in which a user staring at a stuck send taps
 * cancel. [QueueController.cancelQueued] used to search only the parked
 * deque and answer false, which the UI presents as "already on its way".
 *
 * The invariants, in the order they matter:
 *
 *  1. A claim with [FrameState.NOT_ATTEMPTED] is the user's to take back:
 *     nothing is ever written for it, its record is dropped, the caller
 *     gets [QueueCancelledException] and `onMessageExpired` does NOT fire
 *     (a cancel is not a bounce — the user threw the text away).
 *  2. Cancelling one item does not strand the flush: the child stands
 *     down but still opens its gate, so the item behind it goes out.
 *  3. `false` now means "not withdrawable", and it is decided by
 *     [FrameState], not by set membership: mid-write, written, and
 *     refused-and-awaiting-restore all refuse.
 *  4. The race with the dispatch coroutine has exactly two outcomes,
 *     never both and never neither — the cancel and the write contend for
 *     one `stateLock`-guarded transition (`tryBeginWrite`).
 *  5. A withdrawn item is gone from the controller for good: invisible to
 *     [QueueController.drainOnClose] and never restored to the deque.
 *
 * Drives [QueueController] directly, like [QueueControllerCloseDrainTest]
 * and [QueueControllerReplayGateTest]: every state under test is a
 * mid-flush one. The race cases are made deterministic with hooks on the
 * fake transport path (the transport probe at the top of `dispatchOne`,
 * and a park inside the write before `onSent`) rather than with sleeps —
 * each one drops the cancel at a named point of the dispatch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QueueControllerCancelInFlightTest {

    private object NoopTransport : RemoteTransport {
        override fun send(text: String): Boolean = true
        override fun send(bytes: ByteArray): Boolean = true
        override fun close(code: Int, reason: String) {}
    }

    /** Everything the controller under test is wired to, mutable per step. */
    private class Fixture {
        val store: QueueStore = InMemoryQueueStore()
        val expired = mutableListOf<QueuedMessage>()

        /** Ids that entered the RPC at all, i.e. a frame was built. */
        val attempted = mutableListOf<String>()

        /** Ids whose frame the transport accepted, in wire order. */
        val wire = mutableListOf<String>()

        var transport: RemoteTransport? = NoopTransport

        /** Ids the fake transport refuses instead of accepting. */
        var refuse: (String) -> Boolean = { false }

        /**
         * Per-id park INSIDE the write: the child has already won
         * `tryBeginWrite` (so it is [FrameState.IN_PROGRESS]) but has not
         * signalled `onSent` yet. Absent = write straight through.
         */
        val writeGate = mutableMapOf<String, CompletableDeferred<Unit>>()

        /** Per-id server answer; absent = never answers. */
        val response = mutableMapOf<String, CompletableDeferred<JsonRpcResponse>>()

        /**
         * Fires once, from the transport probe at the top of
         * `dispatchOne` — after this child's gate opened, before it tries
         * to claim the write. The one interleaving point the cancel has
         * to win.
         */
        var onTransportProbe: (() -> Unit)? = null

        /** Fires inside the write, after the claim, before `onSent`. */
        var onInsideWrite: ((String) -> Unit)? = null
    }

    private fun TestScope.newController(f: Fixture): QueueController = QueueController(
        // [backgroundScope]: several of these tests deliberately leave a
        // dispatch child parked forever inside its write — that is the
        // state under test — so the scope has to be one `runTest` tears
        // down rather than waits for.
        scope = backgroundScope,
        nowMs = { testScheduler.currentTime },
        queueStore = f.store,
        onMessageExpired = { f.expired += it },
        stateLock = Any(),
        transportAccessor = {
            f.onTransportProbe?.let { hook ->
                f.onTransportProbe = null
                hook()
            }
            f.transport
        },
        callRpc = { _, params, onSent ->
            val id = params!!.jsonObject["id"]!!.jsonPrimitive.content
            f.attempted += id
            f.onInsideWrite?.invoke(id)
            f.writeGate[id]?.await()
            if (f.refuse(id)) {
                onSent?.invoke(false)
                throw FrameRefusedException()
            }
            f.wire += id
            onSent?.invoke(true)
            f.response.getOrPut(id) { CompletableDeferred() }.await()
        },
        events = Channel(Channel.UNLIMITED),
        // Disconnected so `queueCall` always parks instead of taking the
        // fast path; the flush is driven by hand.
        connectionState = MutableStateFlow(ConnectionState.Disconnected),
        currentFeatures = { ServerFeatures.NONE },
        awaitDedupeFeatures = { ServerFeatures.NONE },
        replayGate = { _, _ -> true },
    )

    /**
     * Queue [id] the way the user does while the wire is down, and hand
     * back the caller-side outcome — the only place [QueueCancelledException]
     * is observable.
     */
    private fun TestScope.queueOffline(
        controller: QueueController,
        id: String,
    ): Deferred<Result<JsonRpcResponse>> {
        val outcome = backgroundScope.async {
            runCatching {
                controller.queueCall(
                    method = "remote.solution_agent.send_message_blocks",
                    params = buildJsonObject { put("id", id) },
                    ttlMs = RemoteClient.DEFAULT_QUEUE_TTL_MS,
                    messageId = id,
                )
            }
        }
        // One item at a time so the deque order is the typing order.
        runCurrent()
        return outcome
    }

    private fun Fixture.park(id: String) {
        writeGate[id] = CompletableDeferred()
    }

    private fun Fixture.release(id: String) {
        writeGate[id]?.complete(Unit)
    }

    private fun Fixture.answer(id: String) {
        response.getOrPut(id) { CompletableDeferred() }.complete(JsonRpcResponse(id = 1L))
    }

    private fun storeIds(f: Fixture): List<String> = f.store.loadAll().map { it.id }

    private fun cancelCause(outcome: Deferred<Result<JsonRpcResponse>>): Throwable? =
        outcome.getCompleted().exceptionOrNull()

    // ---- 1. the whole point: a claim whose frame never went out --------

    @Test
    fun `a claim parked on its gate with nothing written is withdrawn`() =
        runTest(StandardTestDispatcher()) {
            val f = Fixture()
            val controller = newController(f)
            // "a" parks inside its write and never signals, so "b" is
            // claimed into the in-flight set and stuck on its gate with
            // FrameState.NOT_ATTEMPTED — the widened window.
            f.park("a")
            queueOffline(controller, "a")
            val doomed = queueOffline(controller, "b")
            controller.flushQueue()
            runCurrent()
            assertEquals(listOf("a"), f.attempted, "only the head may be writing")
            assertTrue(f.wire.isEmpty(), "and it has not signalled yet")

            assertTrue(controller.cancelQueued("b"), "an unwritten claim is the user's to take")
            runCurrent()

            assertTrue(doomed.isCompleted, "the caller must be released immediately")
            assertIs<QueueCancelledException>(cancelCause(doomed))
            assertEquals(listOf("a"), storeIds(f), "only the cancelled record may be deleted")
            assertTrue(f.expired.isEmpty(), "a cancel is not a bounce: ${f.expired}")

            // Let the head finish: "b"'s gate opens and its child must
            // stand down rather than write the frame.
            f.release("a")
            runCurrent()
            assertEquals(listOf("a"), f.attempted, "the cancelled frame must never be built")
            assertEquals(listOf("a"), f.wire, "and never reach the transport")
        }

    // ---- 2. one cancellation must not strand the flush -----------------

    @Test
    fun `the item behind a cancelled claim still goes out, in order`() =
        runTest(StandardTestDispatcher()) {
            val f = Fixture()
            val controller = newController(f)
            f.park("a")
            queueOffline(controller, "a")
            val doomed = queueOffline(controller, "b")
            val third = queueOffline(controller, "c")
            controller.flushQueue()
            runCurrent()

            assertTrue(controller.cancelQueued("b"))
            f.release("a")
            runCurrent()

            assertEquals(listOf("a", "c"), f.wire, "the cancelled item must not block the rest")
            assertIs<QueueCancelledException>(cancelCause(doomed))
            f.answer("a")
            f.answer("c")
            runCurrent()
            assertTrue(third.getCompleted().isSuccess, "the survivor still delivers")
            assertTrue(storeIds(f).isEmpty(), "delivered and cancelled records are both gone")
        }

    // ---- 3. mid-write refuses, and is not disturbed by the attempt -----

    @Test
    fun `a claim whose frame is mid-write refuses and still completes`() =
        runTest(StandardTestDispatcher()) {
            val f = Fixture()
            val controller = newController(f)
            f.park("a")
            val outcome = queueOffline(controller, "a")
            controller.flushQueue()
            runCurrent()
            assertEquals(listOf("a"), f.attempted, "the child is inside the write")
            assertTrue(f.wire.isEmpty(), "which has not been accepted yet")

            assertFalse(controller.cancelQueued("a"), "a frame being written is not withdrawable")
            runCurrent()
            assertFalse(outcome.isCompleted, "refusing must not disturb the in-flight call")
            assertEquals(listOf("a"), storeIds(f), "nothing may be mutated by a refused cancel")
            assertTrue(f.expired.isEmpty())

            f.release("a")
            f.answer("a")
            runCurrent()
            assertEquals(listOf("a"), f.wire)
            assertTrue(outcome.getCompleted().isSuccess, "it must still deliver normally")
            assertTrue(storeIds(f).isEmpty(), "a delivered record is dropped by the dispatch")
        }

    // ---- 4. written refuses; refused refuses until it is back ----------

    @Test
    fun `a claim whose frame was written refuses`() =
        runTest(StandardTestDispatcher()) {
            val f = Fixture()
            val controller = newController(f)
            val outcome = queueOffline(controller, "a")
            controller.flushQueue()
            runCurrent()
            assertEquals(listOf("a"), f.wire, "the frame was accepted; delivery is now unknowable")

            assertFalse(controller.cancelQueued("a"), "nothing can un-deliver a written frame")
            f.answer("a")
            runCurrent()
            assertTrue(outcome.getCompleted().isSuccess)
        }

    @Test
    fun `a refused claim refuses while the flush owns it and is cancellable once restored`() =
        runTest(StandardTestDispatcher()) {
            val f = Fixture()
            val controller = newController(f)
            // "a" is refused by the transport; "b" parks inside its write
            // so the coordinator cannot settle the flush and "a" stays
            // claimed, FrameState.REFUSED.
            f.refuse = { it == "a" }
            f.park("b")
            val doomed = queueOffline(controller, "a")
            queueOffline(controller, "b")
            controller.flushQueue()
            runCurrent()
            assertEquals(listOf("a", "b"), f.attempted, "a refusal must open the next gate")
            assertTrue(f.wire.isEmpty())

            assertFalse(
                controller.cancelQueued("a"),
                "a refused claim belongs to the flush that is about to restore it",
            )
            assertFalse(doomed.isCompleted)

            // Settle the flush: "a" goes back to the head of the deque
            // and is cancellable again through the ordinary parked path.
            f.release("b")
            f.answer("b")
            runCurrent()
            assertTrue(controller.cancelQueued("a"), "once restored it is parked, so it can go")
            runCurrent()
            assertIs<QueueCancelledException>(cancelCause(doomed))
            assertTrue(storeIds(f).isEmpty())
            assertTrue(f.expired.isEmpty(), "still not a bounce: ${f.expired}")
        }

    // ---- 5. the race, at a named interleaving point --------------------

    @Test
    fun `a cancel landing between the gate and the write claim wins outright`() =
        runTest(StandardTestDispatcher()) {
            val f = Fixture()
            val controller = newController(f)
            val outcome = queueOffline(controller, "a")
            // The transport probe is the first thing `dispatchOne` does
            // after its gate opens, and it runs before the write is
            // claimed — so this drops the cancel exactly in the race
            // window, with no sleeps and no thread scheduling involved.
            var verdict: Boolean? = null
            f.onTransportProbe = { verdict = controller.cancelQueued("a") }
            controller.flushQueue()
            runCurrent()

            assertEquals(true, verdict, "the frame was not claimed yet, so the cancel takes it")
            assertTrue(f.attempted.isEmpty(), "the losing child must not build a frame")
            assertTrue(f.wire.isEmpty(), "exactly one of {cancelled, sent} — and it was cancelled")
            assertIs<QueueCancelledException>(cancelCause(outcome))
            assertTrue(storeIds(f).isEmpty(), "the record is gone")
            assertTrue(f.expired.isEmpty(), "and it does not bounce: ${f.expired}")
        }

    @Test
    fun `a cancel landing after the write claim loses outright`() =
        runTest(StandardTestDispatcher()) {
            val f = Fixture()
            val controller = newController(f)
            f.park("a")
            val outcome = queueOffline(controller, "a")
            // Inside the write, i.e. the mirror image: the child won the
            // transition first, so the cancel must not take the item even
            // though the transport has not accepted the frame yet.
            var verdict: Boolean? = null
            f.onInsideWrite = { verdict = controller.cancelQueued("a") }
            controller.flushQueue()
            runCurrent()

            assertEquals(false, verdict, "the write was claimed first")
            f.release("a")
            f.answer("a")
            runCurrent()
            assertEquals(listOf("a"), f.wire, "exactly one of {cancelled, sent} — and it was sent")
            assertTrue(outcome.getCompleted().isSuccess)
        }

    // ---- 6. a withdrawn item is gone from the controller ---------------

    @Test
    fun `a cancelled claim is in neither half of a later close drain`() =
        runTest(StandardTestDispatcher()) {
            val f = Fixture()
            val controller = newController(f)
            f.park("a")
            queueOffline(controller, "a")
            queueOffline(controller, "b")
            controller.flushQueue()
            runCurrent()

            assertTrue(controller.cancelQueued("b"))
            val drain = controller.drainOnClose()
            assertEquals(
                listOf("a"),
                drain.onTheWire.map { it.message.id },
                "the head is inside its write, so its delivery is unknown",
            )
            assertTrue(
                drain.parked.isEmpty(),
                "the cancelled item must not come back as 'still queued': ${drain.parked}",
            )
        }

    @Test
    fun `the restore path does not resurrect a cancelled claim`() =
        runTest(StandardTestDispatcher()) {
            val f = Fixture()
            val controller = newController(f)
            val outcome = queueOffline(controller, "a")
            // Cancel AND lose the wire in the same instant: the child's
            // fail-fast reports Failed (nothing was written), which is the
            // one path on which a withdrawn item reaches the coordinator's
            // restore block.
            f.onTransportProbe = {
                assertTrue(controller.cancelQueued("a"))
                f.transport = null
            }
            controller.flushQueue()
            runCurrent()

            assertIs<QueueCancelledException>(cancelCause(outcome))
            assertTrue(
                controller.parkedDeferredsForTest().isEmpty(),
                "a cancelled item must not be pushed back into the deque",
            )
            assertTrue(storeIds(f).isEmpty())
            assertTrue(f.expired.isEmpty())

            // A later flush has nothing to send, and a close sees nothing.
            f.transport = NoopTransport
            controller.flushQueue()
            runCurrent()
            assertTrue(f.attempted.isEmpty(), "the withdrawn message must never go out")
            val drain = controller.drainOnClose()
            assertTrue(drain.parked.isEmpty(), "nothing is parked: ${drain.parked}")
            assertTrue(drain.onTheWire.isEmpty(), "and nothing is on the wire: ${drain.onTheWire}")
        }
}
