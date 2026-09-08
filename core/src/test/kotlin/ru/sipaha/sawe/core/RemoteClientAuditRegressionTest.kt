package ru.sipaha.sawe.core

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Regression tests for the 2026-09-05 mobile network audit (`:core` half).
 *
 * Each test names the finding it pins down and reproduces the original
 * failure: without the corresponding fix it fails at the assertion that
 * describes the bug, not at setup.
 *
 * Same conventions as [RemoteClientLifecycleTest] — [FakeRemoteTransport],
 * [StandardTestDispatcher] so backoff is virtual time, and `runCurrent()`
 * rather than `advanceUntilIdle()` so nothing silently ages past a TTL.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteClientAuditRegressionTest {

    private fun fakePairing(): PairingUrl = PairingUrl(
        host = "127.0.0.1",
        port = 8443,
        secret = ByteArray(PairingUrl.SECRET_LEN) { it.toByte() },
        client = "audit-regression",
        fingerprint = ByteArray(PairingUrl.FP_LEN) { (255 - it).toByte() },
    )

    private fun TestScope.newClient(
        backoff: BackoffStrategy = BackoffStrategy.fixed(100L),
        queueStore: QueueStore = InMemoryQueueStore(),
        onMessageExpired: ((QueuedMessage) -> Unit)? = null,
    ): Pair<RemoteClient, FakeRemoteTransportFactory> {
        val factory = FakeRemoteTransportFactory()
        val client = RemoteClient(
            url = fakePairing(),
            transportFactory = factory,
            backoff = backoff,
            nowMs = { testScheduler.currentTime },
            queueStore = queueStore,
            onMessageExpired = onMessageExpired,
        )
        return client to factory
    }

    private suspend fun TestScope.connectAndHandshake(
        client: RemoteClient,
        factory: FakeRemoteTransportFactory,
        negotiateCompression: Boolean = false,
    ) {
        val job = async { client.connect(scope = this@connectAndHandshake) }
        runCurrent()
        factory.latest().completeHandshake(negotiateCompression)
        runCurrent()
        job.await()
    }

    // ---------------------------------------------------------------------
    // N-02 / N-66 — close() is a handoff, not a cancellation
    // ---------------------------------------------------------------------

    @Test
    fun `N-02 close keeps queued messages on disk and releases the caller as StillQueued`() =
        runTest(StandardTestDispatcher()) {
            val store = InMemoryQueueStore()
            val expired = mutableListOf<QueuedMessage>()
            val (client, factory) = newClient(
                backoff = BackoffStrategy.fixed(10 * 60 * 1000L),
                queueStore = store,
                onMessageExpired = { expired += it },
            )
            connectAndHandshake(client, factory)
            factory.latest().closeFromServer()
            runCurrent()

            val outcome = CompletableDeferred<Result<JsonRpcResponse>>()
            val supervisor = SupervisorJob()
            launch(supervisor) {
                outcome.complete(
                    runCatching {
                        client.queueCall(
                            method = "remote.solution_agent.send_message",
                            params = buildJsonObject { put("content", "typed-while-offline") },
                            ttlMs = 30 * 60 * 1000L,
                        )
                    },
                )
            }
            runCurrent()
            assertEquals(1, store.loadAll().size, "message should be persisted while offline")

            client.close()
            runCurrent()

            // The caller must be released promptly — the whole point of
            // failing it at all is that it must not sit for the 24h TTL.
            assertTrue(outcome.isCompleted, "close must release the parked caller")
            val error = outcome.await().exceptionOrNull()
            assertTrue(
                error is RemoteClient.ClosedException.StillQueued,
                "expected StillQueued, got $error",
            )
            // …and the message must survive for the next client on this server.
            assertEquals(1, store.loadAll().size, "close must NOT delete the queued message")
            assertTrue(expired.isEmpty(), "close must not bounce: nothing was lost, got $expired")

            supervisor.cancel()
            runCurrent()
        }

    @Test
    fun `N-02 close leaves a rehydrated orphan on disk for the next client`() =
        runTest(StandardTestDispatcher()) {
            val store = InMemoryQueueStore()
            store.add(
                QueuedMessage(
                    id = "orphan-1",
                    method = "remote.solution_agent.send_message",
                    params = buildJsonObject { put("content", "from a previous process") },
                    enqueuedAtMs = testScheduler.currentTime,
                ),
            )
            val expired = mutableListOf<QueuedMessage>()
            val (client, factory) = newClient(queueStore = store, onMessageExpired = { expired += it })
            // Connect, but leave it mid-handshake — nobody is awaiting the
            // orphan's deferred, it was created by rehydrate().
            val connectJob = async { client.connect(scope = this@runTest) }
            runCurrent()
            assertEquals(1, factory.transports.size)

            client.close()
            runCurrent()
            connectJob.await()

            assertEquals(1, store.loadAll().size, "orphan must outlive the client that rehydrated it")
            assertTrue(expired.isEmpty(), "orphan must not be bounced, got $expired")
        }

    @Test
    fun `N-66 close releases an in-flight queue item as delivery-unknown`() =
        runTest(StandardTestDispatcher()) {
            val store = InMemoryQueueStore()
            val expired = mutableListOf<QueuedMessage>()
            val (client, factory) = newClient(
                backoff = BackoffStrategy.fixed(50L),
                queueStore = store,
                onMessageExpired = { expired += it },
            )
            connectAndHandshake(client, factory)
            factory.latest().closeFromServer()
            runCurrent()

            val outcome = CompletableDeferred<Result<JsonRpcResponse>>()
            val supervisor = SupervisorJob()
            launch(supervisor) {
                outcome.complete(
                    runCatching {
                        client.queueCall(
                            method = "remote.solution_agent.send_message",
                            params = buildJsonObject { put("content", "mid-flush") },
                            ttlMs = RemoteClient.DEFAULT_QUEUE_TTL_MS,
                        )
                    },
                )
            }
            runCurrent()
            // Reconnect so the flush starts: the item leaves the deque and
            // is on the wire, awaiting a response that never comes.
            advanceTimeBy(60L)
            runCurrent()
            factory.latest().completeHandshake()
            runCurrent()
            assertFalse(outcome.isCompleted, "item should still be awaiting its response")

            client.close()
            runCurrent()

            assertTrue(
                outcome.isCompleted,
                "close must release an in-flight item instead of leaving it for the 24h TTL",
            )
            // The frame was written. We cannot claim it was not delivered,
            // so it must NOT be labelled StillQueued and must NOT be left
            // on disk for the next client to post a second time.
            val error = outcome.await().exceptionOrNull()
            assertTrue(
                error is TransportLostException,
                "an item on the wire is delivery-unknown, got $error",
            )
            assertEquals(
                0,
                store.loadAll().size,
                "a possibly-delivered message must not be left for a blind replay",
            )
            assertEquals(
                listOf("mid-flush"),
                expired.map { it.params!!.jsonObject["content"]!!.jsonPrimitive.content },
                "the text must be handed back rather than silently dropped",
            )

            supervisor.cancel()
            runCurrent()
        }

    @Test
    fun `close mid-flush parks the in-flight item whose frame was never written`() =
        runTest(StandardTestDispatcher()) {
            val store = InMemoryQueueStore()
            val expired = mutableListOf<QueuedMessage>()
            val (client, factory) = newClient(
                backoff = BackoffStrategy.fixed(50L),
                queueStore = store,
                onMessageExpired = { expired += it },
            )
            connectAndHandshake(client, factory)
            factory.latest().closeFromServer()
            runCurrent()

            // Two messages typed offline. The flush claims BOTH into the
            // in-flight set up front, but the gate chain means only the
            // first one's frame can have been written when the second is
            // still parked on the gate its predecessor opens.
            val alpha = CompletableDeferred<Result<JsonRpcResponse>>()
            val bravo = CompletableDeferred<Result<JsonRpcResponse>>()
            val supervisor = SupervisorJob()
            for ((tag, outcome) in listOf("alpha" to alpha, "bravo" to bravo)) {
                launch(supervisor) {
                    outcome.complete(
                        runCatching {
                            client.queueCall(
                                method = "remote.solution_agent.send_message",
                                params = buildJsonObject { put("content", tag) },
                                ttlMs = RemoteClient.DEFAULT_QUEUE_TTL_MS,
                            )
                        },
                    )
                }
                runCurrent()
            }
            assertEquals(2, store.loadAll().size, "both messages parked on disk")

            // The reconnect that flushes them closes the client from inside
            // `send`, i.e. exactly when alpha's frame is on the transport
            // and bravo has not been looked at.
            factory.pendingHooks.addLast { url, listener ->
                object : FakeRemoteTransport(url, listener) {
                    override fun send(text: String): Boolean {
                        val accepted = super.send(text)
                        if ("alpha" in text) client.close()
                        return accepted
                    }
                }
            }
            advanceTimeBy(60L)
            runCurrent()
            factory.latest().completeHandshake()
            runCurrent()

            assertTrue(alpha.isCompleted && bravo.isCompleted, "close must release both callers")
            // alpha: bytes left the process, delivery unknown.
            assertTrue(
                alpha.await().exceptionOrNull() is TransportLostException,
                "a written frame is delivery-unknown, got ${alpha.await().exceptionOrNull()}",
            )
            // bravo: nothing was ever written for it, so it is honestly
            // still queued — the record must survive for the next client
            // and the text must NOT be bounced into the composer.
            assertTrue(
                bravo.await().exceptionOrNull() is RemoteClient.ClosedException.StillQueued,
                "an unwritten in-flight item is still queued, " +
                    "got ${bravo.await().exceptionOrNull()}",
            )
            assertEquals(
                listOf("bravo"),
                store.loadAll().map { it.params!!.jsonObject["content"]!!.jsonPrimitive.content },
                "the unwritten message must be kept for the next client",
            )
            assertEquals(
                listOf("alpha"),
                expired.map { it.params!!.jsonObject["content"]!!.jsonPrimitive.content },
                "only the written frame may bounce back to the composer",
            )

            supervisor.cancel()
            runCurrent()
        }

    @Test
    fun `close mid-flush parks a claim whose frame the transport refused`() =
        runTest(StandardTestDispatcher()) {
            val store = InMemoryQueueStore()
            val expired = mutableListOf<QueuedMessage>()
            val (client, factory) = newClient(
                backoff = BackoffStrategy.fixed(50L),
                queueStore = store,
                onMessageExpired = { expired += it },
            )
            connectAndHandshake(client, factory)
            factory.latest().closeFromServer()
            runCurrent()

            val alpha = CompletableDeferred<Result<JsonRpcResponse>>()
            val bravo = CompletableDeferred<Result<JsonRpcResponse>>()
            val supervisor = SupervisorJob()
            for ((tag, outcome) in listOf("alpha" to alpha, "bravo" to bravo)) {
                launch(supervisor) {
                    outcome.complete(
                        runCatching {
                            client.queueCall(
                                method = "remote.solution_agent.send_message",
                                params = buildJsonObject { put("content", tag) },
                                ttlMs = RemoteClient.DEFAULT_QUEUE_TTL_MS,
                            )
                        },
                    )
                }
                runCurrent()
            }

            // alpha's frame is REFUSED — a socket that had already started
            // closing. It opens the gate anyway, so bravo goes out, and
            // bravo's send closes the client while alpha is still claimed
            // by the same (unsettled) flush.
            factory.pendingHooks.addLast { url, listener ->
                object : FakeRemoteTransport(url, listener) {
                    override fun send(text: String): Boolean {
                        if ("alpha" in text) return false
                        val accepted = super.send(text)
                        if ("bravo" in text) client.close()
                        return accepted
                    }
                }
            }
            advanceTimeBy(60L)
            runCurrent()
            factory.latest().completeHandshake()
            runCurrent()

            assertTrue(
                alpha.await().exceptionOrNull() is RemoteClient.ClosedException.StillQueued,
                "a refused frame provably never went out, " +
                    "got ${alpha.await().exceptionOrNull()}",
            )
            assertTrue(
                bravo.await().exceptionOrNull() is TransportLostException,
                "the accepted frame is delivery-unknown, got ${bravo.await().exceptionOrNull()}",
            )
            assertEquals(
                listOf("alpha"),
                store.loadAll().map { it.params!!.jsonObject["content"]!!.jsonPrimitive.content },
                "the refused message must be kept for the next client",
            )
            assertEquals(
                listOf("bravo"),
                expired.map { it.params!!.jsonObject["content"]!!.jsonPrimitive.content },
                "only the accepted frame may bounce back to the composer",
            )

            supervisor.cancel()
            runCurrent()
        }

    @Test
    fun `the send signal fires exactly once per call and reports whether bytes left`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient()
            factory.pendingHooks.addLast { url, listener ->
                object : FakeRemoteTransport(url, listener) {
                    override fun send(text: String): Boolean =
                        if ("refuse_me" in text) false else super.send(text)
                }
            }
            connectAndHandshake(client, factory)
            val tx = factory.latest()

            // 1. Accepted: the bytes left this process.
            val accepted = mutableListOf<Boolean>()
            val ok = async {
                runCatching { client.callForTest("remote.editor.capabilities") { accepted += it } }
            }
            runCurrent()
            assertEquals(listOf(true), accepted, "an accepted frame signals written=true, once")
            tx.emit("""{"jsonrpc":"2.0","id":${idOf(tx.sent.last())},"result":{"ok":true}}""")
            runCurrent()
            assertTrue(ok.await().isSuccess)

            // 2. Refused by the transport: provably nothing went out.
            val refused = mutableListOf<Boolean>()
            val refusal = runCatching { client.callForTest("remote.editor.refuse_me") { refused += it } }
            assertEquals(listOf(false), refused, "a refused frame signals written=false, once")
            assertTrue(
                refusal.exceptionOrNull() is FrameRefusedException,
                "got ${refusal.exceptionOrNull()}",
            )

            // 3. Threw on the way to the socket — same verdict.
            val oversize = mutableListOf<Boolean>()
            val tooBig = runCatching {
                client.callForTest(
                    "remote.solution_agent.send_message",
                    buildJsonObject { put("content", "x".repeat(MAX_OUTBOUND_FRAME_BYTES + 1024)) },
                ) { oversize += it }
            }
            assertEquals(listOf(false), oversize, "a frame that never reached the socket is not written")
            assertTrue(
                tooBig.exceptionOrNull() is FrameTooLargeException,
                "got ${tooBig.exceptionOrNull()}",
            )

            // 4. Not connected: the pre-flight bail must signal too, or the
            //    queue's gate chain would strand every item behind it.
            client.close()
            runCurrent()
            val offline = mutableListOf<Boolean>()
            val bail = runCatching { client.callForTest("remote.editor.capabilities") { offline += it } }
            assertEquals(listOf(false), offline, "the not-connected bail signals written=false, once")
            assertTrue(
                bail.exceptionOrNull() is NotConnectedException,
                "got ${bail.exceptionOrNull()}",
            )
        }

    // ---------------------------------------------------------------------
    // N-01 support — typed "delivery unknown" vs "definitely not sent"
    // ---------------------------------------------------------------------

    @Test
    fun `N-01 a request on the wire when the socket dies fails with TransportLostException`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient()
            connectAndHandshake(client, factory)
            val tx = factory.latest()

            val result = async { runCatching { client.call("remote.editor.capabilities") } }
            runCurrent()
            assertEquals(1, tx.sent.count { it.contains("capabilities") }, "frame must be on the wire")

            tx.closeFromServer(reason = "nat rebinding")
            runCurrent()

            val error = result.await().exceptionOrNull()
            assertTrue(error is TransportLostException, "expected TransportLostException, got $error")
            assertNotNull(
                (error as TransportLostException).failure,
                "the classified failure must ride along for the banner",
            )
            client.close()
            runCurrent()
        }

    @Test
    fun `N-01 a frame the transport refuses is retried through the queue not reported as an error`() =
        runTest(StandardTestDispatcher()) {
            val store = InMemoryQueueStore()
            val (client, factory) = newClient(
                backoff = BackoffStrategy.fixed(10 * 60 * 1000L),
                queueStore = store,
            )
            // Refuse exactly one post-handshake frame — a socket that had
            // already started closing when the RPC arrived. Nothing was
            // written, so the send is safe to repeat.
            var refusalsLeft = 1
            factory.pendingHooks += { url, listener ->
                object : FakeRemoteTransport(url, listener) {
                    override fun send(text: String): Boolean {
                        if (super.sent.isNotEmpty() && refusalsLeft > 0) {
                            refusalsLeft--
                            return false
                        }
                        return super.send(text)
                    }
                }
            }
            connectAndHandshake(client, factory)

            val outcome = CompletableDeferred<Result<JsonRpcResponse>>()
            val supervisor = SupervisorJob()
            launch(supervisor) {
                outcome.complete(
                    runCatching {
                        client.queueCall(
                            method = "remote.solution_agent.send_message",
                            params = buildJsonObject { put("content", "refused-once") },
                            ttlMs = 30 * 60 * 1000L,
                        )
                    },
                )
            }
            runCurrent()

            // A refused frame provably never left this process, so it must
            // be re-sent rather than reported to the user as a failed send.
            assertFalse(outcome.isCompleted, "a refused send must not fail the caller")
            val tx = factory.latest()
            assertEquals(2, tx.sent.size, "the refused frame must be sent again: ${tx.sent}")

            tx.emit("""{"jsonrpc":"2.0","id":${idOf(tx.sent.last())},"result":{"ok":true}}""")
            runCurrent()
            assertTrue(outcome.await().isSuccess, "the retry must deliver: ${outcome.await()}")
            assertEquals(0, store.loadAll().size, "a delivered message leaves no record")

            supervisor.cancel()
            client.close()
            runCurrent()
        }

    // ---------------------------------------------------------------------
    // N-08 — an oversize frame must never reach the wire
    // ---------------------------------------------------------------------

    @Test
    fun `N-08 an over-cap message is rejected locally instead of poisoning the socket`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient()
            connectAndHandshake(client, factory)
            val tx = factory.latest()
            val framesBefore = tx.sent.size

            val huge = "x".repeat(MAX_OUTBOUND_FRAME_BYTES + 1024)
            val result = runCatching {
                client.call(
                    "remote.solution_agent.send_message",
                    buildJsonObject { put("content", huge) },
                )
            }

            val error = result.exceptionOrNull()
            assertTrue(error is FrameTooLargeException, "expected FrameTooLargeException, got $error")
            assertEquals(framesBefore, tx.sent.size, "the oversize frame must not be sent")
            assertTrue(
                client.connectionState.value is ConnectionState.Connected,
                "the connection must survive: ${client.connectionState.value}",
            )
            client.close()
            runCurrent()
        }

    @Test
    fun `N-08 a message the transport keeps refusing is bounced instead of spinning`() =
        runTest(StandardTestDispatcher()) {
            val store = InMemoryQueueStore()
            val expired = mutableListOf<QueuedMessage>()
            val (client, factory) = newClient(
                backoff = BackoffStrategy.fixed(50L),
                queueStore = store,
                onMessageExpired = { expired += it },
            )
            // A transport that stays live but refuses every RPC frame.
            // Nothing clears the deque, so restore → QueueChanged →
            // re-flush spins while still Connected. That spin is what the
            // attempt cap exists to stop.
            factory.pendingHooks += { url, listener ->
                object : FakeRemoteTransport(url, listener) {
                    override fun send(text: String): Boolean =
                        if (super.sent.isEmpty()) super.send(text) else false
                }
            }
            connectAndHandshake(client, factory)

            val outcome = CompletableDeferred<Result<JsonRpcResponse>>()
            val supervisor = SupervisorJob()
            launch(supervisor) {
                outcome.complete(
                    runCatching {
                        client.queueCall(
                            method = "remote.solution_agent.send_message",
                            params = buildJsonObject { put("content", "always-refused") },
                            ttlMs = RemoteClient.DEFAULT_QUEUE_TTL_MS,
                        )
                    },
                )
            }
            runCurrent()

            assertTrue(outcome.isCompleted, "the refused item must be given up on, not spun on")
            assertTrue(
                outcome.await().exceptionOrNull() is FrameRefusedException,
                "expected FrameRefusedException, got ${outcome.await().exceptionOrNull()}",
            )
            assertEquals(0, store.loadAll().size, "an abandoned item must leave no disk record")
            assertEquals(1, expired.size, "the payload must be bounced back to the user: $expired")

            supervisor.cancel()
            client.close()
            runCurrent()
        }

    @Test
    fun `N-08 repeated transport loss retries rather than bouncing a possibly-sent message`() =
        runTest(StandardTestDispatcher()) {
            val store = InMemoryQueueStore()
            val expired = mutableListOf<QueuedMessage>()
            val (client, factory) = newClient(
                backoff = BackoffStrategy.fixed(50L),
                queueStore = store,
                onMessageExpired = { expired += it },
            )
            connectAndHandshake(client, factory)
            factory.latest().closeFromServer()
            runCurrent()

            val outcome = CompletableDeferred<Result<JsonRpcResponse>>()
            val supervisor = SupervisorJob()
            launch(supervisor) {
                outcome.complete(
                    runCatching {
                        client.queueCall(
                            method = "remote.solution_agent.send_message",
                            params = buildJsonObject { put("content", "flaky-link") },
                            ttlMs = RemoteClient.DEFAULT_QUEUE_TTL_MS,
                        )
                    },
                )
            }
            runCurrent()

            // Ten reconnects, each losing the socket with the frame already
            // written. Delivery is unknown every time, so the queue must
            // keep the message rather than telling the user it never sent —
            // the server may hold up to ten copies already.
            repeat(10) {
                advanceTimeBy(60L)
                runCurrent()
                val tx = factory.latest()
                if (!tx.closed) {
                    tx.completeHandshake()
                    runCurrent()
                    tx.failFromServer(IOException("connection reset by peer"))
                    runCurrent()
                }
            }

            assertFalse(
                outcome.isCompleted,
                "an ambiguous delivery must never be reported as a failed send",
            )
            assertEquals(1, store.loadAll().size, "the message must stay queued")
            assertTrue(expired.isEmpty(), "it must not be bounced into the composer: $expired")

            supervisor.cancel()
            client.close()
            runCurrent()
        }

    // ---------------------------------------------------------------------
    // N-13 / N-21 — two handshake budgets, and the timed-out socket is cancelled
    // ---------------------------------------------------------------------

    @Test
    fun `N-13 a slow link gets the pre-open budget not the handshake budget`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient(backoff = BackoffStrategy.fixed(1_000L))
            factory.autoOpen = false // TCP/TLS still in flight
            val connectJob = async { client.connect(scope = this@runTest) }
            runCurrent()

            // Past the old 10s all-in budget: an attempt that hasn't even
            // opened yet must still be alive.
            advanceTimeBy(ConnectFailure.HANDSHAKE_TIMEOUT_MS + 1_000L)
            runCurrent()
            assertEquals(1, factory.transports.size, "must not have given up and retried yet")

            // The link finally comes up and the handshake completes.
            factory.latest().listener.onOpen()
            runCurrent()
            factory.latest().completeHandshake()
            runCurrent()
            connectJob.await()
            assertTrue(
                client.connectionState.value is ConnectionState.Connected,
                "slow-but-healthy link must connect: ${client.connectionState.value}",
            )
            client.close()
            runCurrent()
        }

    @Test
    fun `N-13 a peer that opens but never challenges times out on the handshake budget`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient(backoff = BackoffStrategy.fixed(1_000L))
            val connectJob = async { client.connect(scope = this@runTest) }
            runCurrent()
            advanceTimeBy(ConnectFailure.HANDSHAKE_TIMEOUT_MS + 1L)
            runCurrent()

            connectJob.await()
            val state = client.connectionState.value
            assertTrue(state is ConnectionState.Reconnecting, "expected Reconnecting, got $state")
            assertTrue(
                (state as ConnectionState.Reconnecting).lastFailure is ConnectFailure.HandshakeTimeout,
                "expected HandshakeTimeout, got ${state.lastFailure}",
            )
            // N-21: the dead socket must be cancelled, not politely closed —
            // a graceful close can't abort a connect that never produced a
            // writer, and the socket would leak.
            assertTrue(factory.transports.first().cancelled, "timed-out transport must be cancelled")
            client.close()
            runCurrent()
        }

    @Test
    fun `N-13 a black-holed connect fails as Unreachable after the pre-open budget`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient(backoff = BackoffStrategy.fixed(1_000L))
            factory.autoOpen = false
            val connectJob = async { client.connect(scope = this@runTest) }
            runCurrent()
            advanceTimeBy(ConnectFailure.PRE_OPEN_TIMEOUT_MS + 1L)
            runCurrent()

            connectJob.await()
            val state = client.connectionState.value
            assertTrue(state is ConnectionState.Reconnecting, "expected Reconnecting, got $state")
            assertTrue(
                (state as ConnectionState.Reconnecting).lastFailure is ConnectFailure.Unreachable,
                "a connect that never opened is unreachable, not a bad handshake: ${state.lastFailure}",
            )
            assertTrue(factory.transports.first().cancelled, "stuck connect must be cancelled")
            client.close()
            runCurrent()
        }

    // ---------------------------------------------------------------------
    // Review M1/M2 — the welcome → publish window
    // ---------------------------------------------------------------------

    /**
     * A transport that hangs up the instant it has sent `welcome`, i.e.
     * inside the window between the handshake completing on the reader
     * thread and the lifecycle coroutine publishing the socket. A peer
     * evicting a duplicate client name does exactly this.
     */
    private fun dieOnWelcomeHook(
        code: Int = 1001,
        reason: String = "evicted by a newer connection",
    ): (PairingUrl, RemoteTransportListener) -> FakeRemoteTransport = { url, listener ->
        object : FakeRemoteTransport(url, listener) {
            override fun send(text: String): Boolean {
                val ok = super.send(text)
                // The handshake response is the last thing we accept; the
                // welcome the fake sends back completes `handshake`, and
                // the close lands before the loop is resumed to publish.
                return ok
            }
        }
    }

    @Test
    fun `M1 a socket that dies between welcome and publish still reconnects`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient(backoff = BackoffStrategy.fixed(100L))
            factory.pendingHooks += dieOnWelcomeHook()
            val connectJob = async { client.connect(scope = this@runTest) }
            runCurrent()

            // Complete the handshake AND kill the socket in the same batch,
            // before the lifecycle coroutine has been resumed to publish
            // `transport`. Everything the listener sees here is for a
            // socket the shared field does not yet name.
            val tx1 = factory.latest()
            tx1.completeHandshake()
            tx1.closeFromServer(1001, "evicted by a newer connection")
            runCurrent()
            connectJob.join()

            // The event must NOT have been swallowed: the client cannot be
            // left reporting Connected on a socket that is already gone.
            advanceTimeBy(150L)
            runCurrent()
            assertTrue(
                factory.transports.size >= 2,
                "the drop must be noticed and retried, not dropped as 'stale': " +
                    "transports=${factory.transports.size} state=${client.connectionState.value}",
            )
            factory.latest().completeHandshake()
            runCurrent()
            assertTrue(
                client.connectionState.value is ConnectionState.Connected,
                "must recover on the next attempt: ${client.connectionState.value}",
            )
            client.close()
            runCurrent()
        }

    @Test
    fun `M2 close during the post-handshake replay must not publish Connected`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient(backoff = BackoffStrategy.fixed(100L))
            connectAndHandshake(client, factory)
            // A subscription gives onConnected() something to await, which
            // is what lets the test hold the lifecycle coroutine *past* the
            // handshake and *before* the Connected publish — the window a
            // close() can land in.
            val subJob = async { runCatching { client.subscribe(listOf("agent_session")) } }
            runCurrent()
            factory.latest().emit(
                """{"jsonrpc":"2.0","id":${idOf(factory.latest().sent.last())},"result":{}}""",
            )
            runCurrent()
            subJob.await()

            factory.latest().closeFromServer()
            runCurrent()
            advanceTimeBy(150L)
            runCurrent()
            val tx2 = factory.latest()
            tx2.completeHandshake()
            runCurrent()
            // Authenticated and published, but not yet announced.
            assertTrue(
                client.connectionState.value is ConnectionState.Connecting,
                "precondition: still inside the replay, got ${client.connectionState.value}",
            )

            client.close()
            runCurrent()

            assertTrue(
                client.connectionState.value is ConnectionState.Disconnected,
                "a closed client must not be overwritten with Connected by the " +
                    "handshake it was closed on top of: ${client.connectionState.value}",
            )
            assertTrue(
                tx2.closed || tx2.cancelled,
                "the authenticated socket must not be leaked to the server",
            )
            // Nothing may resurrect it afterwards either.
            advanceTimeBy(10_000L)
            runCurrent()
            assertTrue(
                client.connectionState.value is ConnectionState.Disconnected,
                "still closed: ${client.connectionState.value}",
            )
        }

    // ---------------------------------------------------------------------
    // N-14 — a drop during the post-handshake replay is noticed immediately
    // ---------------------------------------------------------------------

    @Test
    fun `N-14 a drop during the subscribe replay fails callers at once and reconnects`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient(backoff = BackoffStrategy.fixed(100L))
            connectAndHandshake(client, factory)
            val subJob = async { runCatching { client.subscribe(listOf("agent_session")) } }
            runCurrent()
            // Answer the initial subscribe so the client is in steady state.
            factory.latest().emit(
                """{"jsonrpc":"2.0","id":${idOf(factory.latest().sent.last())},"result":{}}""",
            )
            runCurrent()
            subJob.await()

            // Drop, reconnect, hand it the welcome — the lifecycle is now
            // inside onConnected() awaiting the subscribe replay.
            factory.latest().closeFromServer()
            runCurrent()
            advanceTimeBy(150L)
            runCurrent()
            val tx2 = factory.latest()
            tx2.completeHandshake()
            runCurrent()

            // The replay is on the wire and unanswered; the link dies.
            tx2.failFromServer(IOException("wifi gone"))
            runCurrent()

            // Within one scheduler pass the client must be reconnecting —
            // not sitting for the replay's own timeout.
            val state = client.connectionState.value
            assertTrue(
                state is ConnectionState.Reconnecting || state is ConnectionState.Connecting,
                "expected an immediate reconnect, got $state after ${testScheduler.currentTime}ms",
            )
            client.close()
            runCurrent()
        }

    // ---------------------------------------------------------------------
    // N-17 — pin mismatch retries a few times, then demands a re-pair
    // ---------------------------------------------------------------------

    @Test
    fun `N-17 a first pin mismatch retries instead of freezing the client`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient(backoff = BackoffStrategy.fixed(100L))
            factory.pendingHooks += pinMismatchHook()
            val connectJob = async { client.connect(scope = this@runTest) }
            runCurrent()
            connectJob.await()

            val state = client.connectionState.value
            assertTrue(
                state is ConnectionState.Reconnecting,
                "a captive portal's certificate must not be terminal on sight: $state",
            )
            assertTrue(
                (state as ConnectionState.Reconnecting).lastFailure is ConnectFailure.TlsPinMismatch,
                "the banner must still say pin mismatch: ${state.lastFailure}",
            )
            assertTrue(
                state.nextRetryMs >= RemoteClient.PIN_MISMATCH_RETRY_DELAY_MS,
                "pin retries wait on a human, not on a blip: ${state.nextRetryMs}",
            )
            client.close()
            runCurrent()
        }

    @Test
    fun `N-17 repeated pin mismatches escalate to FailedTerminal`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient(backoff = BackoffStrategy.fixed(100L))
            repeat(RemoteClient.PIN_MISMATCH_RETRY_LIMIT) { factory.pendingHooks += pinMismatchHook() }
            val connectJob = async { client.connect(scope = this@runTest) }
            runCurrent()
            connectJob.await()
            repeat(RemoteClient.PIN_MISMATCH_RETRY_LIMIT) {
                advanceTimeBy(RemoteClient.PIN_MISMATCH_RETRY_DELAY_MS + 1L)
                runCurrent()
            }

            val state = client.connectionState.value
            assertTrue(
                state is ConnectionState.FailedTerminal,
                "a peer that keeps failing the pin must end in re-pair: $state",
            )
            assertTrue(
                (state as ConnectionState.FailedTerminal).failure is ConnectFailure.TlsPinMismatch,
                "the terminal reason must stay accurate: ${state.failure}",
            )
            client.close()
            runCurrent()
        }

    @Test
    fun `N-17 a client that has connected before still retries a pin mismatch`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient(backoff = BackoffStrategy.fixed(100L))
            // The motivating case: a phone that is working normally walks
            // into a hotel whose portal terminates TLS. Having connected
            // before is not evidence that the certificate changed — it is
            // the normal state of every mobile client.
            connectAndHandshake(client, factory)
            factory.pendingHooks += pinMismatchHook()
            factory.latest().closeFromServer(reason = "left the network")
            runCurrent()
            advanceTimeBy(150L)
            runCurrent()

            val state = client.connectionState.value
            assertTrue(
                state is ConnectionState.Reconnecting,
                "a previously-connected client must still get its retries: $state",
            )
            assertTrue(
                (state as ConnectionState.Reconnecting).lastFailure is ConnectFailure.TlsPinMismatch,
                "the banner must still name the mismatch: ${state.lastFailure}",
            )
            client.close()
            runCurrent()
        }

    @Test
    fun `N-17 a mid-session pin failure escalates only after the retry budget`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient(backoff = BackoffStrategy.fixed(100L))
            connectAndHandshake(client, factory)
            // A pin failure surfacing on an already-established socket
            // takes the terminal-disconnect path rather than the handshake
            // path; it must obey the same policy.
            factory.latest().failFromServer(
                javax.net.ssl.SSLHandshakeException(
                    "leaf certificate fingerprint mismatch (pinning failure)",
                ),
            )
            runCurrent()
            assertTrue(
                client.connectionState.value is ConnectionState.Reconnecting,
                "not terminal on the first occurrence: ${client.connectionState.value}",
            )

            // Keep failing the pin until the budget is spent.
            repeat(RemoteClient.PIN_MISMATCH_RETRY_LIMIT) {
                factory.pendingHooks += pinMismatchHook()
            }
            repeat(RemoteClient.PIN_MISMATCH_RETRY_LIMIT + 1) {
                advanceTimeBy(RemoteClient.PIN_MISMATCH_RETRY_DELAY_MS + 1L)
                runCurrent()
            }
            val state = client.connectionState.value
            assertTrue(
                state is ConnectionState.FailedTerminal,
                "a peer that keeps failing the pin must still end in re-pair: $state",
            )
            assertTrue(
                (state as ConnectionState.FailedTerminal).failure is ConnectFailure.TlsPinMismatch,
                "the terminal reason must stay accurate: ${state.failure}",
            )
            client.close()
            runCurrent()
        }

    private fun pinMismatchHook(): (PairingUrl, RemoteTransportListener) -> FakeRemoteTransport =
        { url, listener ->
            FakeRemoteTransport(url, listener).also {
                listener.onFailure(
                    javax.net.ssl.SSLHandshakeException(
                        "leaf certificate fingerprint mismatch (pinning failure)",
                    ),
                )
            }
        }

    // ---------------------------------------------------------------------
    // N-22 — a peer that drops straight after every handshake climbs the ladder
    // ---------------------------------------------------------------------

    @Test
    fun `N-22 a connection that dies immediately does not reset the backoff ladder`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient(backoff = BackoffStrategy.Default)
            val states = mutableListOf<ConnectionState>()
            val collector = launch { client.connectionState.collect { states += it } }
            connectAndHandshake(client, factory)

            // Four handshake → instant eviction cycles, the two-devices-with-
            // one-client-name signature.
            repeat(4) {
                factory.latest().closeFromServer(code = 1001, reason = "evicted")
                runCurrent()
                advanceTimeBy(60_000L)
                runCurrent()
                factory.latest().completeHandshake()
                runCurrent()
            }
            factory.latest().closeFromServer(code = 1001, reason = "evicted")
            runCurrent()

            val attempts = states.filterIsInstance<ConnectionState.Reconnecting>().map { it.attempt }
            assertTrue(
                attempts.any { it > 1 },
                "a peer that never keeps a connection must not be retried at 1 Hz forever: $attempts",
            )
            collector.cancel()
            client.close()
            runCurrent()
        }

    // ---------------------------------------------------------------------
    // N-23 — a stale transport event must not tear down the live socket
    // ---------------------------------------------------------------------

    @Test
    fun `N-23 a watchdog event for a replaced socket is discarded`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient(backoff = BackoffStrategy.fixed(100L))
            connectAndHandshake(client, factory)
            val tx1 = factory.latest()

            // Two teardown signals for the SAME socket land in the event
            // queue before the lifecycle reads either — OkHttp's pong
            // timeout and the heartbeat watchdog firing in the same
            // millisecond. Only the first should mean anything.
            tx1.signalFailureKeepingOpen(IOException("pong timeout"))
            tx1.signalFailureKeepingOpen(IOException("heartbeat watchdog"))
            runCurrent()

            advanceTimeBy(150L)
            runCurrent()
            factory.latest().completeHandshake()
            runCurrent()
            val transportsAfterReconnect = factory.transports.size

            // Give the loop a chance to consume anything still buffered.
            advanceTimeBy(500L)
            runCurrent()

            assertTrue(
                client.connectionState.value is ConnectionState.Connected,
                "the fresh socket must survive the stale event: ${client.connectionState.value}",
            )
            assertEquals(
                transportsAfterReconnect,
                factory.transports.size,
                "a stale event must not trigger another reconnect",
            )
            client.close()
            runCurrent()
        }

    @Test
    fun `N-23 the abandoned transport is closed rather than left open`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient(backoff = BackoffStrategy.fixed(100L))
            connectAndHandshake(client, factory)
            val tx1 = factory.latest()
            // A teardown of a socket that is healthy at the TCP level:
            // unless we close it, nothing ever sends a Close frame and the
            // server keeps the session until its own idle timeout while
            // our pings keep it alive.
            tx1.signalFailureKeepingOpen(IOException("heartbeat watchdog"))
            runCurrent()
            assertTrue(tx1.closed, "the abandoned socket must be closed, not just forgotten")
            client.close()
            runCurrent()
        }

    // ---------------------------------------------------------------------
    // N-24 — a wake poke at the backoff edge must not eat the next backoff
    // ---------------------------------------------------------------------

    @Test
    fun `N-24 a wake poke racing the backoff edge does not short-circuit the next wait`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient(backoff = BackoffStrategy.fixed(1_000L))
            val connectJob = async { client.connect(scope = this@runTest) }
            runCurrent()
            // Attempt 1 fails → Reconnecting(1s).
            factory.latest().failFromServer(IOException("no route"))
            runCurrent()
            connectJob.await()

            // Land exactly on the expiry: the timer task has run, the state
            // still reads Reconnecting, the loop hasn't resumed yet.
            advanceTimeBy(1_000L)
            client.wakeReconnect()
            runCurrent()
            // Attempt 2 fails too → Reconnecting(1s) again.
            factory.latest().failFromServer(IOException("no route"))
            runCurrent()
            val transportsAtEdge = factory.transports.size

            // The buffered poke must NOT have fired attempt 3 for free.
            runCurrent()
            assertEquals(
                transportsAtEdge,
                factory.transports.size,
                "a poke for an elapsed backoff must not spend a free handshake",
            )
            assertTrue(
                client.connectionState.value is ConnectionState.Reconnecting,
                "should be waiting out the new backoff: ${client.connectionState.value}",
            )
            client.close()
            runCurrent()
        }

    // ---------------------------------------------------------------------
    // N-25 — an unexpected first frame is retryable, not terminal
    // ---------------------------------------------------------------------

    @Test
    fun `N-25 a non-challenge first frame retries instead of freezing`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient(backoff = BackoffStrategy.fixed(100L))
            val connectJob = async { client.connect(scope = this@runTest) }
            runCurrent()
            // A challenge frame from a peer speaking a different version:
            // wrong nonce length, so it doesn't parse as one.
            factory.latest().emit("""{"type":"challenge","challenge":"0011","v":2}""")
            runCurrent()
            connectJob.await()

            val state = client.connectionState.value
            assertTrue(
                state is ConnectionState.Reconnecting,
                "a protocol error is classified retryable — deliver it that way: $state",
            )
            // And it does retry: a healthy peer on the next attempt connects.
            advanceTimeBy(150L)
            runCurrent()
            factory.latest().completeHandshake()
            runCurrent()
            assertTrue(
                client.connectionState.value is ConnectionState.Connected,
                "must recover once the peer behaves: ${client.connectionState.value}",
            )
            client.close()
            runCurrent()
        }

    // ---------------------------------------------------------------------
    // N-28 — cancellation is not an error
    // ---------------------------------------------------------------------

    @Test
    fun `N-28 cancelling connect propagates instead of returning a failed Result`() =
        runTest(StandardTestDispatcher()) {
            val (client, _) = newClient()
            var observed: Throwable? = null
            var resultSeen: Result<Unit>? = null
            val job = launch {
                try {
                    resultSeen = client.connect(scope = this@runTest)
                } catch (t: Throwable) {
                    observed = t
                    throw t
                }
            }
            runCurrent()
            job.cancel()
            runCurrent()

            assertTrue(
                observed is kotlinx.coroutines.CancellationException,
                "cancellation must unwind the caller, got observed=$observed result=$resultSeen",
            )
            client.close()
            runCurrent()
        }

    // ---------------------------------------------------------------------
    // N-45 — the writer backlog is observable
    // ---------------------------------------------------------------------

    @Test
    fun `N-45 queuedBytes surfaces the transport write backlog`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient()
            assertEquals(0L, client.queuedBytes(), "no transport means no backlog")
            connectAndHandshake(client, factory)
            factory.latest().queuedBytesValue = 512L * 1024L
            assertEquals(512L * 1024L, client.queuedBytes())
            client.close()
            runCurrent()
            assertEquals(0L, client.queuedBytes(), "closed client reports no backlog")
        }

    // ---------------------------------------------------------------------
    // N-65 — a malformed frame is dropped, not escalated to a reconnect
    // ---------------------------------------------------------------------

    @Test
    fun `N-65 malformed response frames are dropped without killing the connection`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient()
            val notifications = mutableListOf<kotlinx.serialization.json.JsonElement>()
            val collector = launch { client.notifications.collect { notifications += it } }
            connectAndHandshake(client, factory)
            val tx = factory.latest()

            // Valid JSON, but none of these are a frame we can route.
            tx.emit("""[1,2,3]""")
            tx.emit(""""just a string"""")
            tx.emit("""{"jsonrpc":"2.0","id":{"nested":1},"result":{}}""")
            tx.emit("""{"jsonrpc":"2.0","id":null,"error":{"code":-32700,"message":"parse error"}}""")
            runCurrent()

            assertTrue(
                client.connectionState.value is ConnectionState.Connected,
                "a malformed frame must not reconnect the session: ${client.connectionState.value}",
            )
            assertEquals(1, factory.transports.size, "no reconnect should have happened")
            assertTrue(notifications.isEmpty(), "malformed frames are not notifications: $notifications")

            // The socket is still usable.
            val result = async { client.call("remote.editor.capabilities") }
            runCurrent()
            tx.emit("""{"jsonrpc":"2.0","id":${idOf(tx.sent.last())},"result":{"ok":true}}""")
            runCurrent()
            assertEquals(null, result.await().error)

            collector.cancel()
            client.close()
            runCurrent()
        }

    // ---------------------------------------------------------------------
    // N-67 — a send failure racing a disconnect reports the real reason
    // ---------------------------------------------------------------------

    @Test
    fun `N-67 a refused send racing a disconnect does not resume the caller twice`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient()
            // A socket that fails its writer before delivering onFailure —
            // exactly what OkHttp does: `failed = true` is set (so send()
            // returns false) while the callback is still in flight.
            factory.pendingHooks += { url, listener ->
                object : FakeRemoteTransport(url, listener) {
                    override fun send(text: String): Boolean {
                        if (super.sent.isEmpty()) return super.send(text) // handshake
                        listener.onFailure(IOException("broken pipe"))
                        return false
                    }
                }
            }
            connectAndHandshake(client, factory)

            val result = async { runCatching { client.call("remote.editor.capabilities") } }
            runCurrent()
            val error = result.await().exceptionOrNull()

            // Whichever of the two paths wins, the caller must see a real
            // reason — never an "Already resumed" IllegalStateException from
            // double-resuming the continuation.
            assertTrue(
                error is TransportLostException || error is FrameRefusedException,
                "expected a truthful failure, got $error",
            )
            assertFalse(
                error!!.message.orEmpty().contains("Already resumed"),
                "double resume: $error",
            )
            client.close()
            runCurrent()
        }

    // ---------------------------------------------------------------------
    // N-30 — get_session_entry is stream-scoped (the lazy image backfill)
    // ---------------------------------------------------------------------

    /**
     * Drive a real `getSessionEntry` through the transport and hand back
     * the `params` object the client actually put on the wire.
     */
    private suspend fun TestScope.paramsOfGetSessionEntry(
        client: RemoteClient,
        tx: FakeRemoteTransport,
        streamId: StreamIdDto?,
    ): JsonObject {
        val call = async {
            runCatching { client.getSessionEntry("s-1", index = 7, streamId = streamId) }
        }
        runCurrent()
        val frame = tx.sent.last()
        val request = JsonRpc.json.parseToJsonElement(frame).jsonObject
        assertEquals(
            "remote.solution_agent.get_session_entry",
            request["method"]!!.jsonPrimitive.content,
        )
        // Unblock the caller so the test scope drains.
        tx.emit(
            """{"jsonrpc":"2.0","id":${idOf(frame)},"result":{"structuredContent":null}}""",
        )
        runCurrent()
        call.await()
        return request["params"]!!.jsonObject
    }

    @Test
    fun `N-30 get_session_entry encodes stream_id exactly like get_session_changes`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient()
            connectAndHandshake(client, factory)
            val tx = factory.latest()

            // Omitted: the key must be ABSENT, not JSON null — the server's
            // params struct is deny_unknown_fields with
            // skip_serializing_if = "Option::is_none", so a null is rejected
            // where an omitted key is accepted.
            val defaulted = paramsOfGetSessionEntry(client, tx, streamId = null)
            assertFalse(
                defaulted.containsKey("stream_id"),
                "omitting the stream must omit the key entirely: $defaulted",
            )
            assertEquals("s-1", defaulted["session_id"]!!.jsonPrimitive.content)
            assertEquals(7, defaulted["index"]!!.jsonPrimitive.content.toInt())

            val streams = listOf(
                StreamIdDto.Main,
                StreamIdDto.Teammate(toolu = "toolu_abc"),
                StreamIdDto.Shell(id = "sh-1"),
            )
            for (stream in streams) {
                val params = paramsOfGetSessionEntry(client, tx, streamId = stream)
                val encoded = params["stream_id"]!!

                // Byte for byte what the other stream-scoped RPC sends.
                val changes = async {
                    runCatching {
                        client.getSessionChanges(
                            sessionId = "s-1",
                            sinceSeq = 0L,
                            knownEpoch = 0L,
                            streamId = stream,
                        )
                    }
                }
                runCurrent()
                val changesFrame = tx.sent.last()
                val changesParams = JsonRpc.json.parseToJsonElement(changesFrame)
                    .jsonObject["params"]!!.jsonObject
                tx.emit(
                    """{"jsonrpc":"2.0","id":${idOf(changesFrame)},"result":{"structuredContent":null}}""",
                )
                runCurrent()
                changes.await()
                assertEquals(
                    changesParams["stream_id"],
                    encoded,
                    "get_session_entry must encode $stream identically to get_session_changes",
                )

                // …and it round-trips back to the same value.
                assertEquals(
                    stream,
                    JsonRpc.json.decodeFromJsonElement(StreamIdDto.serializer(), encoded),
                    "stream_id must round-trip: $encoded",
                )
            }

            client.close()
            runCurrent()
        }

    // ---------------------------------------------------------------------
    // N-07 — the user can cancel a message parked in the offline queue
    // ---------------------------------------------------------------------

    /** Queue [content] offline under a caller-chosen id; returns its outcome. */
    private fun TestScope.queueOffline(
        client: RemoteClient,
        supervisor: Job,
        id: String,
        content: String,
    ): CompletableDeferred<Result<JsonRpcResponse>> {
        val outcome = CompletableDeferred<Result<JsonRpcResponse>>()
        launch(supervisor) {
            outcome.complete(
                runCatching {
                    client.queueCall(
                        method = "remote.solution_agent.send_message",
                        params = buildJsonObject { put("content", content) },
                        ttlMs = RemoteClient.DEFAULT_QUEUE_TTL_MS,
                        messageId = id,
                    )
                },
            )
        }
        runCurrent()
        return outcome
    }

    @Test
    fun `N-07 cancelling a parked message drops it and leaves the rest in order`() =
        runTest(StandardTestDispatcher()) {
            val store = InMemoryQueueStore()
            val expired = mutableListOf<QueuedMessage>()
            val (client, factory) = newClient(
                backoff = BackoffStrategy.fixed(50L),
                queueStore = store,
                onMessageExpired = { expired += it },
            )
            connectAndHandshake(client, factory)
            factory.latest().closeFromServer()
            runCurrent()

            val supervisor = SupervisorJob()
            val first = queueOffline(client, supervisor, "csid-1", "first")
            val doomed = queueOffline(client, supervisor, "csid-2", "cancel-me")
            val third = queueOffline(client, supervisor, "csid-3", "third")
            assertEquals(3, store.loadAll().size)

            // The user taps cancel on the middle one.
            assertTrue(client.cancelQueued("csid-2"), "a parked message must be cancellable")
            runCurrent()

            // Released on the next scheduler pass, with no virtual time
            // advanced — i.e. not left parked for the rest of its TTL.
            assertTrue(doomed.isCompleted, "the caller must be released immediately")
            assertTrue(
                doomed.await().exceptionOrNull() is QueueCancelledException,
                "expected QueueCancelledException, got ${doomed.await().exceptionOrNull()}",
            )
            assertEquals(
                listOf("csid-1", "csid-3"),
                store.loadAll().map { it.id },
                "only the cancelled record may be deleted",
            )
            assertTrue(
                expired.isEmpty(),
                "cancelling is a deliberate user action — it must not bounce: $expired",
            )
            assertFalse(first.isCompleted, "cancelling one message must not disturb the others")
            assertFalse(third.isCompleted)

            // The survivors still go out, still in order.
            advanceTimeBy(60L)
            runCurrent()
            val tx = factory.latest()
            tx.completeHandshake()
            runCurrent()
            val sent = tx.sent.filter { it.contains("send_message") }
            assertEquals(2, sent.size, "exactly the two survivors are sent: ${tx.sent}")
            assertTrue(sent[0].contains("first"), "FIFO order broken: $sent")
            assertTrue(sent[1].contains("third"), "FIFO order broken: $sent")
            assertTrue(sent.none { it.contains("cancel-me") }, "cancelled text must never go out")

            supervisor.cancel()
            client.close()
            runCurrent()
        }

    @Test
    fun `N-07 cancelling refuses once the message is on the wire`() =
        runTest(StandardTestDispatcher()) {
            val store = InMemoryQueueStore()
            val (client, factory) = newClient(
                backoff = BackoffStrategy.fixed(50L),
                queueStore = store,
            )
            connectAndHandshake(client, factory)
            factory.latest().closeFromServer()
            runCurrent()

            val supervisor = SupervisorJob()
            val outcome = queueOffline(client, supervisor, "csid-1", "already-sent")

            // Reconnect: the item leaves the deque and goes on the wire,
            // awaiting a response the server hasn't produced yet.
            advanceTimeBy(60L)
            runCurrent()
            val tx = factory.latest()
            tx.completeHandshake()
            runCurrent()
            val frame = tx.sent.single { it.contains("send_message") }

            // The server may already have applied it, and there is no
            // send-id dedupe to undo it with — so we must refuse.
            assertFalse(
                client.cancelQueued("csid-1"),
                "a message already handed to the transport must not be reported as cancelled",
            )
            assertFalse(outcome.isCompleted, "refusing must not disturb the in-flight call")

            // And it does in fact deliver.
            tx.emit("""{"jsonrpc":"2.0","id":${idOf(frame)},"result":{"ok":true}}""")
            runCurrent()
            assertTrue(outcome.await().isSuccess, "the in-flight message must still deliver")
            assertEquals(0, store.loadAll().size)

            supervisor.cancel()
            client.close()
            runCurrent()
        }

    @Test
    fun `N-07 cancelling an unknown id is a refusal not a crash`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient()
            // Before connect there is no queue at all.
            assertFalse(client.cancelQueued("nope"), "no queue yet — nothing to cancel")
            connectAndHandshake(client, factory)
            assertFalse(client.cancelQueued("nope"), "unknown ids are refused")
            client.close()
            runCurrent()
        }

    // ---------------------------------------------------------------------
    // N-19 — the reconnect ladder parks while the app is backgrounded
    // ---------------------------------------------------------------------

    @Test
    fun `N-19 a paused client does not dial and resumes promptly when released`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient(backoff = BackoffStrategy.fixed(1_000L))
            connectAndHandshake(client, factory)
            assertEquals(1, factory.transports.size)

            // Backgrounded while connected: the live socket is untouched.
            client.setReconnectPaused(true)
            advanceTimeBy(10 * 60 * 1000L)
            runCurrent()
            assertTrue(
                client.connectionState.value is ConnectionState.Connected,
                "pausing must not disturb a live connection: ${client.connectionState.value}",
            )
            assertEquals(1, factory.transports.size, "pausing must not dial")

            // Now the socket dies with the screen still off. The ladder
            // must park instead of retrying every second for ten minutes.
            factory.latest().closeFromServer()
            runCurrent()
            advanceTimeBy(10 * 60 * 1000L)
            runCurrent()
            assertEquals(
                1,
                factory.transports.size,
                "a parked ladder must not spend a single radio wake-up",
            )
            assertTrue(
                client.connectionState.value is ConnectionState.Reconnecting,
                "still nominally reconnecting: ${client.connectionState.value}",
            )

            // Foreground edge: retry NOW, without waiting out a backoff
            // that elapsed while we were parked.
            client.setReconnectPaused(false)
            runCurrent()
            assertEquals(2, factory.transports.size, "release must dial immediately")
            factory.latest().completeHandshake()
            runCurrent()
            assertTrue(
                client.connectionState.value is ConnectionState.Connected,
                "must reconnect after release: ${client.connectionState.value}",
            )
            client.close()
            runCurrent()
        }

    @Test
    fun `N-19 wakeReconnect releases a parked ladder`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient(backoff = BackoffStrategy.fixed(1_000L))
            connectAndHandshake(client, factory)
            client.setReconnectPaused(true)
            factory.latest().closeFromServer()
            runCurrent()
            advanceTimeBy(60_000L)
            runCurrent()
            assertEquals(1, factory.transports.size, "parked")

            // A foreground edge that pokes without unpausing must still work
            // — the caller shouldn't have to get the ordering right.
            client.wakeReconnect()
            runCurrent()
            assertEquals(2, factory.transports.size, "wakeReconnect must release the park")

            // Released, not merely poked once: a network-change edge fires
            // while the interface is still settling, so the first dial
            // routinely fails. The ladder must keep trying for a bounded
            // burst rather than re-parking on that first failure and
            // holding the user's queue until the next event.
            factory.latest().failFromServer(IOException("route still settling"))
            runCurrent()
            advanceTimeBy(60_000L)
            runCurrent()
            assertTrue(
                factory.transports.size > 2,
                "a failed first dial must not re-park immediately: " +
                    "transports=${factory.transports.size}",
            )

            // …but the burst is bounded: once it is spent the ladder parks
            // again, because `paused` was never cleared.
            repeat(RemoteClient.WAKE_ATTEMPT_BURST + 2) {
                factory.transports.lastOrNull()?.takeIf { !it.closed }
                    ?.failFromServer(IOException("still down"))
                runCurrent()
                advanceTimeBy(60_000L)
                runCurrent()
            }
            val parkedAt = factory.transports.size
            advanceTimeBy(10 * 60 * 1000L)
            runCurrent()
            assertEquals(
                parkedAt,
                factory.transports.size,
                "the wake burst must be bounded — a paused client cannot dial forever",
            )
            client.close()
            runCurrent()
        }

    @Test
    fun `N-19 pausing mid-handshake lets the attempt finish`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient(backoff = BackoffStrategy.fixed(1_000L))
            val connectJob = async { client.connect(scope = this@runTest) }
            runCurrent()
            // Screen goes off while the handshake is in flight.
            client.setReconnectPaused(true)
            runCurrent()
            factory.latest().completeHandshake()
            runCurrent()
            connectJob.await()

            assertTrue(
                client.connectionState.value is ConnectionState.Connected,
                "pausing must never abort an in-flight handshake: ${client.connectionState.value}",
            )
            client.close()
            runCurrent()
        }

    @Test
    fun `N-19 the queue and subscriptions survive a pause`() =
        runTest(StandardTestDispatcher()) {
            val store = InMemoryQueueStore()
            val expired = mutableListOf<QueuedMessage>()
            val (client, factory) = newClient(
                backoff = BackoffStrategy.fixed(1_000L),
                queueStore = store,
                onMessageExpired = { expired += it },
            )
            connectAndHandshake(client, factory)
            val subJob = async { runCatching { client.subscribe(listOf("agent_session")) } }
            runCurrent()
            factory.latest().emit(
                """{"jsonrpc":"2.0","id":${idOf(factory.latest().sent.last())},"result":{}}""",
            )
            runCurrent()
            subJob.await()

            client.setReconnectPaused(true)
            factory.latest().closeFromServer()
            runCurrent()
            val supervisor = SupervisorJob()
            launch(supervisor) {
                runCatching {
                    client.queueCall(
                        method = "remote.solution_agent.send_message",
                        params = buildJsonObject { put("content", "typed-with-screen-off") },
                        ttlMs = RemoteClient.DEFAULT_QUEUE_TTL_MS,
                    )
                }
            }
            runCurrent()
            advanceTimeBy(60_000L)
            runCurrent()

            assertEquals(1, store.loadAll().size, "a pause must not drop the queue")
            assertTrue(expired.isEmpty(), "a pause must not bounce anything: $expired")
            assertEquals(
                setOf("agent_session"),
                client.activeSubscriptionKinds(),
                "a pause must not drop subscriptions",
            )

            // And on release, the queued message goes out on the new socket.
            client.setReconnectPaused(false)
            runCurrent()
            val tx2 = factory.latest()
            tx2.completeHandshake()
            runCurrent()
            // The subscription replay is awaited before the queue flush
            // (reconnect-handshake atomicity); answer it so the flush runs.
            val replay = tx2.sent.single { it.contains("editor.subscribe") }
            assertTrue(replay.contains("agent_session"), "subscriptions must be replayed: $replay")
            tx2.emit("""{"jsonrpc":"2.0","id":${idOf(replay)},"result":{}}""")
            runCurrent()
            assertTrue(
                tx2.sent.any { it.contains("typed-with-screen-off") },
                "the queued message must flush after release: ${tx2.sent}",
            )
            supervisor.cancel()
            client.close()
            runCurrent()
        }

    @Test
    fun `N-19 close releases a parked ladder instead of hanging`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient(backoff = BackoffStrategy.fixed(1_000L))
            connectAndHandshake(client, factory)
            client.setReconnectPaused(true)
            factory.latest().closeFromServer()
            runCurrent()
            advanceTimeBy(60_000L)
            runCurrent()

            client.close()
            runCurrent()
            assertTrue(
                client.connectionState.value is ConnectionState.Disconnected,
                "close must win against the park: ${client.connectionState.value}",
            )
            // Nothing left running: the scheduler has no pending work.
            advanceTimeBy(10 * 60 * 1000L)
            runCurrent()
            assertEquals(1, factory.transports.size, "a closed client must not dial")
        }

    // ---------------------------------------------------------------------
    // N-16 — a handover is evidence, not a guess: it may bypass the grace
    // ---------------------------------------------------------------------

    @Test
    fun `N-16 forceReconnect honours the grace by default and force bypasses it`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient(backoff = BackoffStrategy.fixed(100L))
            connectAndHandshake(client, factory)
            val tx1 = factory.latest()
            // The connection is milliseconds old in wall-clock terms, i.e.
            // well inside FORCE_RECONNECT_GRACE_MS.

            client.forceReconnect("heartbeat watchdog: server unresponsive")
            runCurrent()
            assertTrue(
                client.connectionState.value is ConnectionState.Connected,
                "an inferred failure must not kick a fresh socket: ${client.connectionState.value}",
            )
            assertFalse(tx1.closed, "the grace must still protect against probe storms")
            assertEquals(1, factory.transports.size)

            // A default-network change is direct evidence, not a guess.
            client.forceReconnect("network handover", force = true)
            runCurrent()
            assertTrue(tx1.closed, "a forced reconnect must tear the dead socket down")
            advanceTimeBy(150L)
            runCurrent()
            assertEquals(2, factory.transports.size, "…and dial again immediately")
            factory.latest().completeHandshake()
            runCurrent()
            assertTrue(
                client.connectionState.value is ConnectionState.Connected,
                "handover recovery must complete: ${client.connectionState.value}",
            )
            client.close()
            runCurrent()
        }

    /** Pull the JSON-RPC `id` out of a request frame the client sent. */
    private fun idOf(frame: String): Long =
        Regex("\"id\":(\\d+)").find(frame)?.groupValues?.get(1)?.toLong()
            ?: error("no id in $frame")
}
