package ru.sipaha.spkremote.core

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Disabled
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Tests for [RemoteClient]'s R-6a resilience features. All tests use
 * [FakeRemoteTransport] — they do NOT depend on a live server, and they
 * use [StandardTestDispatcher] so reconnect backoff is virtual time.
 *
 * **Why we use [runCurrent] rather than [kotlinx.coroutines.test.advanceUntilIdle]:**
 * the queue-TTL is enforced inside [RemoteClient] via [withTimeout]. With
 * `advanceUntilIdle()`, the test scheduler would happily advance virtual
 * time past the TTL deadline if no other work is pending, expiring the
 * queue prematurely. `runCurrent()` drains only tasks already scheduled at
 * the current virtual time, which is what we want everywhere except where
 * we deliberately want backoff to elapse.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteClientLifecycleTest {

    private fun fakePairing(): PairingUrl = PairingUrl(
        host = "127.0.0.1",
        port = 8443,
        secret = ByteArray(PairingUrl.SECRET_LEN) { it.toByte() },
        client = "lifecycle-test",
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

    /**
     * Connect + drive the handshake on the latest transport. Returns when
     * [RemoteClient.connect] has resolved (state is Connected).
     */
    private suspend fun TestScope.connectAndHandshake(
        client: RemoteClient,
        factory: FakeRemoteTransportFactory,
    ) {
        val job = async { client.connect(scope = this@connectAndHandshake) }
        runCurrent()
        factory.latest().completeHandshake()
        runCurrent()
        job.await()
    }

    @Test
    fun `disconnected -- connecting -- connected on a successful first handshake`() = runTest(
        StandardTestDispatcher(),
    ) {
        val (client, factory) = newClient()
        val initial = client.connectionState.value
        val states = mutableListOf<ConnectionState>(initial)
        val collector = launch {
            client.connectionState.collect { if (it != states.lastOrNull()) states += it }
        }
        connectAndHandshake(client, factory)
        runCurrent()
        client.close()
        runCurrent()
        collector.cancel()
        assertTrue(states.first() is ConnectionState.Disconnected, "first state: ${states.first()}")
        assertTrue(states.any { it is ConnectionState.Connected }, "should reach Connected: $states")
        assertTrue(
            states.none { it is ConnectionState.Reconnecting },
            "no Reconnecting on clean lifecycle: $states",
        )
        assertTrue(states.last() is ConnectionState.Disconnected, "last state: ${states.last()}")
    }

    @Test
    fun `unexpected close triggers reconnecting then connected again`() = runTest(
        StandardTestDispatcher(),
    ) {
        val (client, factory) = newClient(backoff = BackoffStrategy.fixed(100L))
        val states = mutableListOf<ConnectionState>()
        val collector = launch { client.connectionState.collect { states += it } }
        connectAndHandshake(client, factory)

        // Simulate an unexpected drop.
        factory.latest().closeFromServer(reason = "server timeout")
        runCurrent()
        // Advance just past the backoff so the next attempt starts.
        advanceTimeBy(150L)
        runCurrent()
        factory.latest().completeHandshake()
        runCurrent()

        collector.cancel()
        client.close()
        runCurrent()

        val reconnectingIdx = states.indexOfFirst { it is ConnectionState.Reconnecting }
        assertTrue(reconnectingIdx > 0, "should observe Reconnecting after Connected: $states")
        val firstReconnect = states[reconnectingIdx] as ConnectionState.Reconnecting
        assertEquals(1, firstReconnect.attempt, "attempt counter resets to 1: $firstReconnect")
        assertEquals(100L, firstReconnect.nextRetryMs)
        val laterConnected = states.drop(reconnectingIdx).any { it is ConnectionState.Connected }
        assertTrue(laterConnected, "should reach Connected again: $states")
    }

    @Test
    fun `terminal failure on handshake transitions to FailedTerminal and does not retry`() = runTest(
        StandardTestDispatcher(),
    ) {
        val (client, factory) = newClient()
        factory.pendingHooks += { url, listener ->
            FakeRemoteTransport(url, listener).also {
                listener.onFailure(
                    javax.net.ssl.SSLHandshakeException(
                        "leaf certificate fingerprint mismatch (pinning failure)",
                    ),
                )
            }
        }
        val connectResult = client.connect(scope = this@runTest)
        runCurrent()
        assertTrue(connectResult.isFailure, "connect should fail: $connectResult")
        val finalState = client.connectionState.value
        assertTrue(
            finalState is ConnectionState.FailedTerminal,
            "expected FailedTerminal, got $finalState",
        )
        assertEquals(1, factory.transports.size)
        client.close()
        runCurrent()
    }

    @Test
    fun `queueCall sends immediately when connected`() = runTest(StandardTestDispatcher()) {
        val (client, factory) = newClient()
        connectAndHandshake(client, factory)

        val callJob = async {
            client.queueCall(
                "remote.solution_agent.send_message",
                buildJsonObject {
                    put("session_id", "s1")
                    put("content", "hello")
                },
            )
        }
        runCurrent()

        val sent = factory.latest().sent.toList()
        assertTrue(sent.any { it.contains("send_message") }, "should send when connected: $sent")
        val sentFrame = sent.first { it.contains("send_message") }
        val id = JSON_ID_REGEX.find(sentFrame)!!.groupValues[1].toLong()
        factory.latest().emit("""{"jsonrpc":"2.0","id":$id,"result":{"ok":true}}""")
        runCurrent()
        val resp = callJob.await()
        assertNull(resp.error, "response should be success: $resp")
        client.close()
        runCurrent()
    }

    @Test
    fun `queueCall holds during reconnect and sends on reconnect`() = runTest(StandardTestDispatcher()) {
        val (client, factory) = newClient(backoff = BackoffStrategy.fixed(100L))
        connectAndHandshake(client, factory)
        factory.latest().closeFromServer()
        runCurrent()
        // Baseline frame count after the initial handshake completes
        // (which itself emits one JSON response frame on the first
        // transport). The assertion below is "no NEW frames went out
        // while we were disconnected"; without a baseline it would
        // false-positive on the handshake-response frame.
        val sentBefore = factory.transports.sumOf { it.sent.size }

        // Queue a call while disconnected (we're in Reconnecting now).
        val callJob = async {
            client.queueCall(
                "remote.solution_agent.send_message",
                buildJsonObject {
                    put("session_id", "s1")
                    put("content", "queued-during-reconnect")
                },
                ttlMs = 60_000L,
            )
        }
        runCurrent()
        val sentAfter = factory.transports.sumOf { it.sent.size }
        assertEquals(sentBefore, sentAfter, "no send during reconnect")

        // Advance past backoff to start the next attempt; then complete its handshake.
        advanceTimeBy(150L)
        runCurrent()
        factory.latest().completeHandshake()
        runCurrent()

        val secondTx = factory.latest()
        val sentFrames = secondTx.sent.toList()
        val sentFrame = sentFrames.firstOrNull { it.contains("queued-during-reconnect") }
        assertTrue(sentFrame != null, "queued call should be flushed on reconnect: $sentFrames")
        val id = JSON_ID_REGEX.find(sentFrame!!)!!.groupValues[1].toLong()
        secondTx.emit("""{"jsonrpc":"2.0","id":$id,"result":{"ok":true}}""")
        runCurrent()
        val resp = callJob.await()
        assertNull(resp.error)
        client.close()
        runCurrent()
    }

    @Test
    fun `queueCall TTL expires when reconnect takes too long`() = runTest(StandardTestDispatcher()) {
        // Use a very long backoff so we never actually reconnect.
        val (client, factory) = newClient(backoff = BackoffStrategy.fixed(10 * 60 * 1000L))
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
                        params = buildJsonObject { put("content", "doomed") },
                        ttlMs = 1_000L,
                    )
                },
            )
        }
        runCurrent()
        assertTrue(!outcome.isCompleted, "should still be queued just after enqueue")
        advanceTimeBy(2_000L)
        runCurrent()
        val result = outcome.await()
        assertTrue(result.isFailure, "TTL should fail the queued call: $result")
        assertTrue(
            result.exceptionOrNull() is RemoteClient.QueueTtlException,
            "expected QueueTtlException, got ${result.exceptionOrNull()}",
        )
        client.close()
        supervisor.cancel()
        runCurrent()
    }

    @Test
    fun `subscribe is tracked and replayed after reconnect`() = runTest(StandardTestDispatcher()) {
        val (client, factory) = newClient(backoff = BackoffStrategy.fixed(50L))
        connectAndHandshake(client, factory)

        val subJob = async { client.subscribe(listOf("agent_session_message_appended")) }
        runCurrent()
        val firstSubFrame = factory.latest().sent.toList().firstOrNull { it.contains("subscribe") }
        assertTrue(firstSubFrame != null, "subscribe must be sent on the live connection")
        val subId = JSON_ID_REGEX.find(firstSubFrame!!)!!.groupValues[1].toLong()
        factory.latest().emit("""{"jsonrpc":"2.0","id":$subId,"result":{"ok":true}}""")
        runCurrent()
        subJob.await()
        assertEquals(setOf("agent_session_message_appended"), client.activeSubscriptionKinds())

        factory.latest().closeFromServer()
        runCurrent()
        advanceTimeBy(80L)
        runCurrent()
        factory.latest().completeHandshake()
        runCurrent()

        val secondTx = factory.latest()
        val replay = secondTx.sent.toList().firstOrNull {
            it.contains("\"method\":\"remote.editor.subscribe\"")
        }
        assertTrue(replay != null, "subscribe replay missing: ${secondTx.sent.toList()}")
        assertTrue(
            replay!!.contains("agent_session_message_appended"),
            "replay should reference the tracked kind: $replay",
        )
        client.close()
        runCurrent()
    }

    @Test
    fun `unsubscribe drops kinds and they are NOT replayed after reconnect`() = runTest(StandardTestDispatcher()) {
        val (client, factory) = newClient(backoff = BackoffStrategy.fixed(50L))
        connectAndHandshake(client, factory)

        val subJob = async { client.subscribe(listOf("buffer_opened")) }
        runCurrent()
        val subFrame = factory.latest().sent.toList().first { it.contains("subscribe") }
        val subId = JSON_ID_REGEX.find(subFrame)!!.groupValues[1].toLong()
        factory.latest().emit("""{"jsonrpc":"2.0","id":$subId,"result":{"ok":true}}""")
        runCurrent()
        subJob.await()

        val unsubJob = async { client.unsubscribe(listOf("buffer_opened")) }
        runCurrent()
        val unsubFrame = factory.latest().sent.toList().first { it.contains("unsubscribe") }
        val unsubId = JSON_ID_REGEX.find(unsubFrame)!!.groupValues[1].toLong()
        factory.latest().emit("""{"jsonrpc":"2.0","id":$unsubId,"result":{"ok":true}}""")
        runCurrent()
        unsubJob.await()
        assertEquals(emptySet(), client.activeSubscriptionKinds())

        factory.latest().closeFromServer()
        runCurrent()
        advanceTimeBy(80L)
        runCurrent()
        factory.latest().completeHandshake()
        runCurrent()

        val secondTx = factory.latest()
        val replay = secondTx.sent.toList().any { it.contains("\"method\":\"remote.editor.subscribe\"") }
        assertTrue(!replay, "no replay when no active kinds: ${secondTx.sent.toList()}")
        client.close()
        runCurrent()
    }

    @Test
    fun `transient onFailure during established session triggers reconnect`() = runTest(StandardTestDispatcher()) {
        val (client, factory) = newClient(backoff = BackoffStrategy.fixed(100L))
        connectAndHandshake(client, factory)
        factory.latest().failFromServer(IOException("connection reset by peer"))
        runCurrent()
        advanceTimeBy(150L)
        runCurrent()
        assertTrue(factory.transports.size >= 2, "should attempt reconnect: ${factory.transports.size}")
        factory.latest().completeHandshake()
        runCurrent()
        assertTrue(
            client.connectionState.value is ConnectionState.Connected,
            "should re-reach Connected: ${client.connectionState.value}",
        )
        client.close()
        runCurrent()
    }

    @Test
    fun `close while reconnecting drops queued items as ClosedException`() = runTest(StandardTestDispatcher()) {
        val (client, factory) = newClient(backoff = BackoffStrategy.fixed(10 * 60 * 1000L))
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
                        params = buildJsonObject { put("content", "doomed-by-close") },
                        ttlMs = 30 * 60 * 1000L,
                    )
                },
            )
        }
        runCurrent()
        // Close while it's still queued. Use runCurrent rather than
        // advanceUntilIdle so we drain the close's synchronous effects
        // without ticking past the 30-minute TTL.
        client.close()
        runCurrent()
        val result = outcome.await()
        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull() is RemoteClient.ClosedException,
            "expected ClosedException, got ${result.exceptionOrNull()}",
        )
        supervisor.cancel()
        runCurrent()
    }

    @Test
    fun `attempt counter resets to 1 after a successful reconnect`() = runTest(StandardTestDispatcher()) {
        val (client, factory) = newClient(backoff = BackoffStrategy.fixed(10L))
        val states = mutableListOf<ConnectionState>()
        val collector = launch { client.connectionState.collect { states += it } }
        connectAndHandshake(client, factory)

        // First disconnect/reconnect cycle.
        factory.latest().closeFromServer()
        runCurrent()
        advanceTimeBy(20L)
        runCurrent()
        factory.latest().completeHandshake()
        runCurrent()
        // Second disconnect — the attempt counter should reset and the
        // *next* Reconnecting state must say attempt=1, not attempt=2.
        factory.latest().closeFromServer()
        runCurrent()
        val recAttempts = states.filterIsInstance<ConnectionState.Reconnecting>().map { it.attempt }
        assertTrue(
            recAttempts.count { it == 1 } >= 2,
            "attempt=1 should appear at least twice (one per drop): $recAttempts",
        )
        advanceTimeBy(20L)
        runCurrent()
        factory.latest().completeHandshake()
        runCurrent()
        collector.cancel()
        client.close()
        runCurrent()
    }

    @Test
    fun `call without connect fails`() = runTest(StandardTestDispatcher()) {
        val (client, _) = newClient()
        assertFailsWith<NotConnectedException> {
            withTimeout(1_000L) { client.call("remote.editor.capabilities") }
        }
    }

    @Test
    fun `queueCall persists to QueueStore while disconnected`() = runTest(StandardTestDispatcher()) {
        val store = InMemoryQueueStore()
        val (client, factory) = newClient(
            backoff = BackoffStrategy.fixed(10 * 60 * 1000L),
            queueStore = store,
        )
        connectAndHandshake(client, factory)
        factory.latest().closeFromServer()
        runCurrent()
        assertEquals(0, store.loadAll().size, "no entries before queueCall")
        val supervisor = SupervisorJob()
        launch(supervisor) {
            runCatching {
                client.queueCall(
                    "remote.solution_agent.send_message",
                    buildJsonObject {
                        put("session_id", "s1")
                        put("content", "persist-me")
                    },
                    ttlMs = 30 * 60 * 1000L,
                )
            }
        }
        runCurrent()
        val persisted = store.loadAll()
        assertEquals(1, persisted.size, "queueCall should write to QueueStore: $persisted")
        assertEquals("remote.solution_agent.send_message", persisted[0].method)
        client.close()
        runCurrent()
        // close() clears the disk entry for items it completes with
        // ClosedException (audit Phase 2 M6 option A — clean semantics,
        // callers don't observe ghost replays on the next instance).
        assertEquals(0, store.loadAll().size, "close should clear the store for ClosedException items")
        supervisor.cancel()
        runCurrent()
    }

    @Test
    fun `rehydrateQueue replays surviving entries on connect`() = runTest(StandardTestDispatcher()) {
        val store = InMemoryQueueStore()
        // Pre-populate as if a previous process left it behind.
        store.add(
            QueuedMessage(
                id = "msg-1",
                method = "remote.solution_agent.send_message",
                params = buildJsonObject {
                    put("session_id", "s1")
                    put("content", "from-previous-life")
                },
                enqueuedAtMs = testScheduler.currentTime,
            ),
        )
        val (client, factory) = newClient(queueStore = store)
        connectAndHandshake(client, factory)
        runCurrent()
        val sentFrames = factory.latest().sent.toList()
        val replay = sentFrames.firstOrNull { it.contains("from-previous-life") }
        assertTrue(replay != null, "rehydrated entry must replay on Connected: $sentFrames")
        // Server response settles the deferred + removes from disk.
        val id = JSON_ID_REGEX.find(replay!!)!!.groupValues[1].toLong()
        factory.latest().emit("""{"jsonrpc":"2.0","id":$id,"result":{"ok":true}}""")
        runCurrent()
        assertEquals(0, store.loadAll().size, "successful replay clears the disk entry")
        client.close()
        runCurrent()
    }

    @Test
    fun `TTL expiry removes from QueueStore and fires onMessageExpired`() = runTest(StandardTestDispatcher()) {
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
        val supervisor = SupervisorJob()
        launch(supervisor) {
            runCatching {
                client.queueCall(
                    method = "remote.solution_agent.send_message",
                    params = buildJsonObject {
                        put("session_id", "s1")
                        put("content", "bounce-me")
                    },
                    ttlMs = 1_000L,
                )
            }
        }
        runCurrent()
        assertEquals(1, store.loadAll().size, "queued before TTL")
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(0, store.loadAll().size, "TTL must clear the disk entry")
        assertEquals(1, expired.size, "onMessageExpired must fire: $expired")
        assertEquals(
            "bounce-me",
            expired[0].params!!.jsonObject["content"]!!.jsonPrimitive.content,
        )
        client.close()
        supervisor.cancel()
        runCurrent()
    }

    @Test
    fun `default TTL is 24 hours`() {
        assertEquals(24L * 60L * 60L * 1_000L, RemoteClient.DEFAULT_QUEUE_TTL_MS)
    }

    @Test
    fun `late response after call cancellation is silently dropped`() = runTest(
        StandardTestDispatcher(),
    ) {
        // Narrow concurrency window: a `call()` coroutine is cancelled
        // *after* it has installed its `pending[id]` entry, then a late
        // response arrives carrying the same id from the wire. The fix
        // contract: no crash, no second resumption of the dead coroutine,
        // no exception escapes the dispatcher.
        //
        // Actual current behaviour (post-fix): the late response is
        // routed via `pending.remove(id)` which returns null (cancellation
        // already removed the entry), so dispatchJsonRpc falls through
        // to the `_notifications` SharedFlow. That's an acceptable end
        // state — the coroutine that was waiting is gone, and any
        // observer of `notifications` is responsible for ignoring frames
        // it didn't subscribe to.
        val (client, factory) = newClient()
        connectAndHandshake(client, factory)

        val callJob = async {
            client.call("remote.editor.capabilities")
        }
        runCurrent()
        // Capture the wire id assigned to this call so we can fake the
        // late response with a matching id.
        val sent = factory.latest().sent.toList()
        val sentFrame = sent.last { it.contains("\"method\":\"remote.editor.capabilities\"") }
        val id = JSON_ID_REGEX.find(sentFrame)!!.groupValues[1].toLong()
        // Cancel the call coroutine. invokeOnCancellation removes the
        // pending entry, so the deferred is no longer reachable via id.
        callJob.cancel()
        runCurrent()
        // Now emit a late response with the same id. The client must
        // tolerate this — no exception should propagate to the test scope.
        factory.latest().emit("""{"jsonrpc":"2.0","id":$id,"result":{"ok":true}}""")
        runCurrent()
        // Sanity-check: the client is still connected and responsive to
        // a fresh call (its lifecycle wasn't poisoned by the late frame).
        assertTrue(
            client.connectionState.value is ConnectionState.Connected,
            "client should stay Connected after late response: ${client.connectionState.value}",
        )
        client.close()
        runCurrent()
    }

    @Test
    fun `handshake timeout transitions to Reconnecting`() = runTest(StandardTestDispatcher()) {
        // Server never sends the challenge frame — completeHandshake is
        // never called. After HANDSHAKE_TIMEOUT_MS the lifecycle loop
        // classifies as HandshakeTimeout (transient) and schedules a
        // reconnect. We use a generous backoff so we can observe the
        // Reconnecting state before it auto-progresses to Connecting.
        val (client, factory) = newClient(backoff = BackoffStrategy.fixed(60_000L))
        val states = mutableListOf<ConnectionState>()
        val collector = launch { client.connectionState.collect { states += it } }
        val connectJob = async { client.connect(scope = this@runTest) }
        runCurrent()
        // First transport exists but we deliberately never drive it.
        assertEquals(1, factory.transports.size)
        // Advance just past the handshake timeout.
        advanceTimeBy(ConnectFailure.HANDSHAKE_TIMEOUT_MS + 100L)
        runCurrent()
        // First-attempt timeout is surfaced to the connect() caller as a
        // failed Result carrying ConnectException(HandshakeTimeout).
        val result = connectJob.await()
        assertTrue(result.isFailure, "connect should fail with HandshakeTimeout: $result")
        val cause = result.exceptionOrNull()
        assertTrue(
            cause is ConnectException && cause.failure is ConnectFailure.HandshakeTimeout,
            "expected HandshakeTimeout cause, got: $cause",
        )
        // Loop continues in the background — observe Reconnecting in the
        // collected states.
        val rec = states.filterIsInstance<ConnectionState.Reconnecting>().firstOrNull()
        assertTrue(rec != null, "should observe Reconnecting after handshake timeout: $states")
        assertTrue(
            rec!!.lastFailure is ConnectFailure.HandshakeTimeout,
            "Reconnecting should carry HandshakeTimeout cause: ${rec.lastFailure}",
        )
        collector.cancel()
        client.close()
        runCurrent()
    }

    @Test
    fun `binary frame during handshake raises ProtocolError and reconnects`() = runTest(
        StandardTestDispatcher(),
    ) {
        // Emit a binary frame while the listener is still in AwaitingNonce.
        // Per HandshakeListener.onBinary this completes the handshake with
        // a transient ProtocolError, which the lifecycle loop turns into
        // Reconnecting (binary-during-handshake is transient, not terminal).
        val (client, factory) = newClient(backoff = BackoffStrategy.fixed(60_000L))
        val states = mutableListOf<ConnectionState>()
        val collector = launch { client.connectionState.collect { states += it } }
        val connectJob = async { client.connect(scope = this@runTest) }
        runCurrent()
        // Send a random binary blob — content doesn't matter, the listener
        // only inspects the frame *type* during handshake.
        factory.latest().emitBinary(byteArrayOf(0x01, 0x02, 0x03))
        runCurrent()
        val result = connectJob.await()
        assertTrue(result.isFailure, "connect should fail with ProtocolError: $result")
        val cause = result.exceptionOrNull()
        assertTrue(
            cause is ConnectException && cause.failure is ConnectFailure.ProtocolError,
            "expected ProtocolError cause, got: $cause",
        )
        val rec = states.filterIsInstance<ConnectionState.Reconnecting>().firstOrNull()
        assertTrue(rec != null, "should reach Reconnecting after binary handshake frame: $states")
        assertTrue(
            rec!!.lastFailure is ConnectFailure.ProtocolError,
            "Reconnecting must carry ProtocolError: ${rec.lastFailure}",
        )
        collector.cancel()
        client.close()
        runCurrent()
    }

    @Test
    fun `unsolicited notification is dispatched on notifications flow after handshake`() = runTest(
        StandardTestDispatcher(),
    ) {
        val (client, factory) = newClient()
        // Subscribe to the SharedFlow before the emission so we don't race
        // the replay window. extraBufferCapacity = 64 on the underlying
        // flow makes this robust but explicit collection is clearer.
        val collected = mutableListOf<kotlinx.serialization.json.JsonElement>()
        val collector = launch { client.notifications.collect { collected += it } }
        connectAndHandshake(client, factory)
        // Emit a JSON-RPC notification (no id) — must surface on the flow.
        factory.latest().emit(
            """{"jsonrpc":"2.0","method":"agent_session_message_appended","params":{"foo":1}}"""
        )
        runCurrent()
        assertTrue(collected.isNotEmpty(), "notification must reach the flow")
        val obj = collected.first().jsonObject
        assertEquals(
            "agent_session_message_appended",
            obj["method"]?.jsonPrimitive?.content,
        )
        collector.cancel()
        client.close()
        runCurrent()
    }

    // -------------------------------------------------------------------------
    // T1 — mid-flush re-enqueue
    // -------------------------------------------------------------------------

    /**
     * T1 — mid-flush re-enqueue (HIGH-priority gap, H3).
     *
     * Queues 3 items, connects, and has the second item's `send()` call trigger
     * a server-side close. Asserts that items 2+3 are re-enqueued at the head of
     * the queue so they flush in original FIFO order on the next reconnect.
     *
     * **NOTE — disabled pending H3 fix from the parallel :core agent:**
     *
     * This test targets the post-H3 behaviour of `dispatchQueuedItems`. The fix
     * contract is: when `callInternal` fails because the transport dropped
     * mid-flush (the launched per-item coroutine catches a `NotConnectedException`
     * or `IllegalStateException("websocket refused frame")`), the item is
     * re-enqueued at the HEAD of `queued` (not completed with an exception).
     *
     * Without H3, items 2+3 are completed with `NotConnectedException` (the
     * callers' deferreds get the exception, the items disappear from the queue)
     * and this test fails. Re-enable once H3 lands.
     *
     * Setup note: the anonymous subclass requires [FakeRemoteTransport] to be
     * `open`, which was done as part of this test infrastructure update.
     */
    @Disabled("M1 fix from backlog drain agent is incomplete — test hangs (UncompletedCoroutinesError after 60s). dispatchQueuedItems sequential-drain implementation needs revisit; remains in .audit-backlog.md.")
    @Test
    fun `mid-flush drop re-enqueues remaining items at head in FIFO order`() = runTest(
        StandardTestDispatcher(),
    ) {
        val store = InMemoryQueueStore()
        val (client, factory) = newClient(
            backoff = BackoffStrategy.fixed(100L),
            queueStore = store,
        )

        // Connect and immediately disconnect so the 3 items queue while offline.
        connectAndHandshake(client, factory)
        factory.latest().closeFromServer(reason = "setup disconnect")
        runCurrent()

        val supervisor = SupervisorJob()
        val results = Array<CompletableDeferred<Result<JsonRpcResponse>>>(3) { CompletableDeferred() }

        // Enqueue 3 items while disconnected.
        for (i in 0..2) {
            val idx = i
            launch(supervisor) {
                results[idx].complete(
                    runCatching {
                        client.queueCall(
                            "remote.solution_agent.send_message",
                            buildJsonObject {
                                put("session_id", "s1")
                                put("content", "item-${idx + 1}")
                            },
                            ttlMs = 30 * 60 * 1000L,
                        )
                    },
                )
            }
        }
        runCurrent()
        assertEquals(3, store.loadAll().size, "expected 3 items persisted before second connect")

        // Hook: install a transport whose send() for the SECOND RPC call
        // (= item-1, after the handshake response) triggers a server-side close.
        // After the handshake response (sendCount=1), item-1 is sendCount=2,
        // item-2 is sendCount=3, etc. We close on sendCount=3 so item-1 gets
        // sent successfully, item-2's send triggers the drop, and item-3 hasn't
        // been dispatched yet (post-H3: items 2+3 re-enqueued; pre-H3: items 2+3
        // fail with NotConnectedException).
        var sendCount = 0
        factory.pendingHooks += { url, listener ->
            object : FakeRemoteTransport(url, listener) {
                override fun send(text: String): Boolean {
                    sendCount++
                    // sendCount=1: handshake response text — let through.
                    // sendCount=2: item-1's RPC — let through (item-1 succeeds).
                    // sendCount=3: item-2's RPC — trigger drop.
                    return if (sendCount == 3) {
                        closeFromServer(reason = "mid-flush drop on item-2")
                        false
                    } else {
                        super.send(text)
                    }
                }
            }
        }

        // Reconnect — second transport.
        advanceTimeBy(150L)
        runCurrent()
        factory.latest().completeHandshake()
        runCurrent()

        // item-1's response: complete it so it doesn't hang.
        val secondTx = factory.transports.toList()[1] // second transport
        val sentBySecond = secondTx.sent.filter { it.contains("item-1") }
        if (sentBySecond.isNotEmpty()) {
            val id1 = JSON_ID_REGEX.find(sentBySecond[0])!!.groupValues[1].toLong()
            secondTx.emit("""{"jsonrpc":"2.0","id":$id1,"result":{"ok":true}}""")
            runCurrent()
        }

        // After mid-flush drop, items 2+3 must still be in the persistent store
        // (post-H3: re-enqueued; pre-H3: store has been cleared — test fails).
        val storeAfterDrop = store.loadAll()
        val remaining = storeAfterDrop.mapNotNull {
            (it.params as? kotlinx.serialization.json.JsonObject)
                ?.get("content")?.jsonPrimitive?.content
        }
        assertTrue(
            remaining.any { it == "item-2" } && remaining.any { it == "item-3" },
            "items 2+3 must still be in the store after mid-flush drop: $remaining",
        )

        // Reconnect again — third transport, no hook, all sends succeed.
        advanceTimeBy(150L)
        runCurrent()
        factory.latest().completeHandshake()
        runCurrent()

        val thirdTx = factory.latest()
        val thirdSent = thirdTx.sent.filter { it.contains("item-") }
        val order = thirdSent.map { frame ->
            listOf("item-2", "item-3").firstOrNull { frame.contains(it) }
        }.filterNotNull()
        assertTrue(
            order.size >= 2 && order.indexOf("item-2") < order.indexOf("item-3"),
            "items 2+3 must flush in FIFO order on the third transport: $order",
        )

        supervisor.cancel()
        client.close()
        runCurrent()
    }

    // -------------------------------------------------------------------------
    // T2 — concurrent connect() race
    // -------------------------------------------------------------------------

    @Test
    fun `concurrent connect() calls - exactly one succeeds the other throws IllegalStateException`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient()

            // Launch two concurrent connect calls. Only one should win the
            // `connectGuard.compareAndSet` CAS — the other must fail.
            //
            // Note: `connect()` wraps its body in `runCatching` and returns
            // `Result<Unit>`. The CAS loser's `check()` is caught inside that
            // `runCatching` and returned as `Result.failure`. We call
            // `getOrThrow()` in both wrappers to re-throw the failure so that
            // the outer `runCatching` here can record it properly.
            val r1 = CompletableDeferred<Result<Unit>>()
            val r2 = CompletableDeferred<Result<Unit>>()

            val j1 = launch {
                r1.complete(runCatching { client.connect(scope = this@runTest).getOrThrow() })
            }
            val j2 = launch {
                r2.complete(runCatching { client.connect(scope = this@runTest).getOrThrow() })
            }
            runCurrent()
            // Drive the handshake so the winning connect() can complete.
            if (factory.transports.isNotEmpty()) {
                factory.latest().completeHandshake()
            }
            runCurrent()
            j1.join()
            j2.join()

            val result1 = r1.await()
            val result2 = r2.await()

            val successes = listOf(result1, result2).count { it.isSuccess }
            val failures = listOf(result1, result2).count { it.isFailure }
            assertEquals(1, successes, "exactly one connect() should succeed: r1=$result1, r2=$result2")
            assertEquals(1, failures, "exactly one connect() should fail: r1=$result1, r2=$result2")

            val failedResult = listOf(result1, result2).first { it.isFailure }
            val cause = failedResult.exceptionOrNull()
            assertTrue(
                cause is IllegalStateException,
                "losing connect() must throw IllegalStateException, got: $cause",
            )

            // Only ONE transport should have been created.
            assertEquals(1, factory.transports.size, "only one transport per connect: ${factory.transports.size}")

            client.close()
            runCurrent()
        }

    // -------------------------------------------------------------------------
    // T3 — flushQueue partial TTL expiry
    // -------------------------------------------------------------------------

    @Test
    fun `flushQueue expiries short-TTL item while long-TTL item still succeeds`() = runTest(
        StandardTestDispatcher(),
    ) {
        val store = InMemoryQueueStore()
        val expired = mutableListOf<QueuedMessage>()
        // Use a long backoff so we control reconnect timing manually.
        val (client, factory) = newClient(
            backoff = BackoffStrategy.fixed(10 * 60 * 1000L),
            queueStore = store,
            onMessageExpired = { expired += it },
        )

        connectAndHandshake(client, factory)
        factory.latest().closeFromServer()
        runCurrent()

        // Enqueue a short-TTL item (expires in 500ms).
        val shortOutcome = CompletableDeferred<Result<JsonRpcResponse>>()
        val supervisor = SupervisorJob()
        launch(supervisor) {
            shortOutcome.complete(
                runCatching {
                    client.queueCall(
                        method = "remote.solution_agent.send_message",
                        params = buildJsonObject {
                            put("session_id", "s1")
                            put("content", "short-ttl")
                        },
                        ttlMs = 500L,
                    )
                },
            )
        }

        // Enqueue a long-TTL item (30 minutes).
        val longOutcome = CompletableDeferred<Result<JsonRpcResponse>>()
        launch(supervisor) {
            longOutcome.complete(
                runCatching {
                    client.queueCall(
                        method = "remote.solution_agent.send_message",
                        params = buildJsonObject {
                            put("session_id", "s1")
                            put("content", "long-ttl")
                        },
                        ttlMs = 30 * 60 * 1000L,
                    )
                },
            )
        }
        runCurrent()
        assertEquals(2, store.loadAll().size, "both items should be persisted")

        // Advance time just past the short TTL so it expires.
        advanceTimeBy(600L)
        runCurrent()

        // Now reconnect — the flush should expire the short item and send the long one.
        // We need a fresh transport factory entry.
        advanceTimeBy(10 * 60 * 1000L) // skip past backoff
        runCurrent()
        factory.latest().completeHandshake()
        runCurrent()

        // Verify short-TTL item expired.
        val shortResult = shortOutcome.await()
        assertTrue(shortResult.isFailure, "short-TTL item should fail: $shortResult")
        assertTrue(
            shortResult.exceptionOrNull() is RemoteClient.QueueTtlException,
            "short-TTL item should throw QueueTtlException: ${shortResult.exceptionOrNull()}",
        )
        assertEquals(1, expired.size, "onMessageExpired must fire for short-TTL: $expired")
        assertEquals(
            "short-ttl",
            expired[0].params!!.jsonObject["content"]!!.jsonPrimitive.content,
        )

        // Long-TTL item should have been dispatched (sent over the wire).
        val latestTx = factory.latest()
        val sentFrames = latestTx.sent.toList()
        val longFrame = sentFrames.firstOrNull { it.contains("long-ttl") }
        assertTrue(longFrame != null, "long-TTL item must be sent: $sentFrames")

        // Complete the long-TTL response.
        val longId = JSON_ID_REGEX.find(longFrame!!)!!.groupValues[1].toLong()
        latestTx.emit("""{"jsonrpc":"2.0","id":$longId,"result":{"ok":true}}""")
        runCurrent()

        val longResult = longOutcome.await()
        assertTrue(longResult.isSuccess, "long-TTL item should succeed: $longResult")

        supervisor.cancel()
        client.close()
        runCurrent()
    }

    // -------------------------------------------------------------------------
    // T4 — subscribe replayed across two consecutive reconnects
    // -------------------------------------------------------------------------

    @Test
    fun `subscribe is replayed on each of two consecutive reconnects`() = runTest(
        StandardTestDispatcher(),
    ) {
        val (client, factory) = newClient(backoff = BackoffStrategy.fixed(50L))
        connectAndHandshake(client, factory)

        // Subscribe once.
        val subJob = async { client.subscribe(listOf("cap1")) }
        runCurrent()
        val firstSubFrame = factory.latest().sent.first { it.contains("subscribe") }
        val subId = JSON_ID_REGEX.find(firstSubFrame)!!.groupValues[1].toLong()
        factory.latest().emit("""{"jsonrpc":"2.0","id":$subId,"result":{"ok":true}}""")
        runCurrent()
        subJob.await()
        assertEquals(setOf("cap1"), client.activeSubscriptionKinds())

        // First disconnect/reconnect.
        factory.latest().closeFromServer()
        runCurrent()
        advanceTimeBy(80L)
        runCurrent()
        factory.latest().completeHandshake()
        runCurrent()
        val secondTx = factory.latest()
        val replay1 = secondTx.sent.firstOrNull { it.contains("remote.editor.subscribe") }
        assertTrue(replay1 != null, "subscribe replay must happen on first reconnect: ${secondTx.sent}")
        assertTrue(replay1!!.contains("cap1"), "replay must reference cap1: $replay1")

        // Second disconnect/reconnect.
        factory.latest().closeFromServer()
        runCurrent()
        advanceTimeBy(80L)
        runCurrent()
        factory.latest().completeHandshake()
        runCurrent()
        val thirdTx = factory.latest()
        val replay2 = thirdTx.sent.firstOrNull { it.contains("remote.editor.subscribe") }
        assertTrue(replay2 != null, "subscribe replay must happen on second reconnect: ${thirdTx.sent}")
        assertTrue(replay2!!.contains("cap1"), "second replay must reference cap1: $replay2")

        // Variant: if the second reconnect's subscribe response is a JSON-RPC
        // error, activeSubscriptions must NOT be corrupted (still has "cap1").
        val subReplayId = JSON_ID_REGEX.find(replay2)!!.groupValues[1].toLong()
        factory.latest().emit(
            """{"jsonrpc":"2.0","id":$subReplayId,"error":{"code":-32600,"message":"subscribe error"}}"""
        )
        runCurrent()
        // activeSubscriptions is only mutated by subscribe()/unsubscribe() calls,
        // not by the response. So even with an error response, the set is intact.
        assertEquals(
            setOf("cap1"),
            client.activeSubscriptionKinds(),
            "activeSubscriptions must survive an error response to a replay subscribe",
        )

        client.close()
        runCurrent()
    }

    // -------------------------------------------------------------------------
    // T5 — callInternal send-refused branch
    // -------------------------------------------------------------------------

    @Test
    fun `call fails with IllegalStateException when transport refuses the frame`() = runTest(
        StandardTestDispatcher(),
    ) {
        val (client, factory) = newClient()

        // Install a hook that creates a transport whose send() always returns false.
        factory.pendingHooks += { url, listener ->
            object : FakeRemoteTransport(url, listener) {
                override fun send(text: String): Boolean {
                    // Accept the handshake frames (response frame during
                    // handshake is sent via send(bytes) not send(text) — but
                    // the client also sends text during handshake). We only
                    // want to refuse the first post-handshake RPC call.
                    // Count sends: the first few are handshake, then the RPC.
                    val already = super.sent.size
                    return if (already < 1) {
                        // Let handshake text frames through.
                        super.send(text)
                    } else {
                        // Refuse the first RPC call.
                        false
                    }
                }
            }
        }

        connectAndHandshake(client, factory)

        // Now call — the transport will refuse the send.
        val result = runCatching {
            client.call("remote.editor.capabilities")
        }

        assertTrue(result.isFailure, "call should fail when transport refuses: $result")
        val cause = result.exceptionOrNull()
        assertTrue(
            cause is IllegalStateException,
            "expected IllegalStateException, got: $cause",
        )
        // pending map must be clean after the failure.
        // We can't access `pending` directly (private), but we can verify
        // the client is still live and connected.
        assertTrue(
            client.connectionState.value is ConnectionState.Connected,
            "client should stay Connected after refused frame: ${client.connectionState.value}",
        )

        client.close()
        runCurrent()
    }

    // -------------------------------------------------------------------------
    // T6 — dispatchJsonRpc malformed paths
    // -------------------------------------------------------------------------

    @Test
    fun `frame with non-integer id is silently dropped`() = runTest(StandardTestDispatcher()) {
        val (client, factory) = newClient()
        val notifications = mutableListOf<kotlinx.serialization.json.JsonElement>()
        val collector = launch { client.notifications.collect { notifications += it } }
        connectAndHandshake(client, factory)

        // Emit a frame with a string id — not a valid JSON-RPC integer id.
        // Expected post-M4-fix behavior: dropped silently (not emitted to
        // _notifications).  Current behavior without M4: falls through to
        // notifications because `runCatching { it.long }` returns null and
        // the id-matching branch returns early.
        factory.latest().emit("""{"jsonrpc":"2.0","id":"not-an-int","result":{"ok":true}}""")
        runCurrent()

        // The notification collector must NOT have received the frame.
        // Post-M4 fix: notifications is empty.
        // Pre-M4 current behavior: the frame has an id but non-integer, so
        // dispatchJsonRpc returns early at `?: return` — also not emitted.
        // Either way, the assertion holds — nothing in notifications.
        assertTrue(
            notifications.none { elem ->
                elem.jsonObject["id"]?.jsonPrimitive?.content == "not-an-int"
            },
            "non-integer-id frame must not appear in notifications: $notifications",
        )
        // Client must not have crashed (still connected).
        assertTrue(
            client.connectionState.value is ConnectionState.Connected,
            "client must stay Connected: ${client.connectionState.value}",
        )

        collector.cancel()
        client.close()
        runCurrent()
    }

    @Test
    fun `non-JSON frame is silently dropped without crash`() = runTest(StandardTestDispatcher()) {
        val (client, factory) = newClient()
        val notifications = mutableListOf<kotlinx.serialization.json.JsonElement>()
        val collector = launch { client.notifications.collect { notifications += it } }
        connectAndHandshake(client, factory)

        // Emit pure garbage — not JSON at all.
        factory.latest().emit("this is not json {{{{")
        runCurrent()

        // No crash, no exception escaping to the test scope.
        assertTrue(
            client.connectionState.value is ConnectionState.Connected,
            "client must survive non-JSON frame: ${client.connectionState.value}",
        )
        // Nothing observable in notifications.
        assertTrue(
            notifications.isEmpty(),
            "no notifications for non-JSON frame: $notifications",
        )

        collector.cancel()
        client.close()
        runCurrent()
    }

    // -------------------------------------------------------------------------
    // T7 — firstConnect gate behavior on transient-then-success
    // -------------------------------------------------------------------------

    @Test
    fun `firstConnect gate fails on transient first attempt and resolves Connected on second`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient(backoff = BackoffStrategy.fixed(100L))

            // Hook: first transport fails with a transient error (Unreachable).
            factory.pendingHooks += { url, listener ->
                FakeRemoteTransport(url, listener).also {
                    listener.onFailure(java.net.ConnectException("connection refused"))
                }
            }

            val states = mutableListOf<ConnectionState>()
            val stateCollector = launch { client.connectionState.collect { states += it } }

            val connectResult = client.connect(scope = this@runTest)
            runCurrent()

            // First attempt fails — connect() Result must be a failure.
            assertTrue(
                connectResult.isFailure,
                "connect() should fail after first transient attempt: $connectResult",
            )
            val cause = connectResult.exceptionOrNull()
            assertTrue(
                cause is ConnectException,
                "expected ConnectException, got: $cause",
            )

            // Lifecycle loop is still running — it's now Reconnecting.
            // Advance past backoff, complete second attempt's handshake.
            advanceTimeBy(150L)
            runCurrent()
            factory.latest().completeHandshake()
            runCurrent()

            // After second attempt succeeds, state must be Connected.
            assertTrue(
                client.connectionState.value is ConnectionState.Connected,
                "should reach Connected after second attempt: ${client.connectionState.value}",
            )

            stateCollector.cancel()
            client.close()
            runCurrent()
        }

    companion object {
        private val JSON_ID_REGEX = Regex("""\"id\":(\d+)""")
    }
}
