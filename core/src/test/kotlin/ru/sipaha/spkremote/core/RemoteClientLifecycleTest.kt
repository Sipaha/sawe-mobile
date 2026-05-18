package ru.sipaha.spkremote.core

import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
        assertTrue(resp.isSuccess, "response should be success: $resp")
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
        assertTrue(resp.isSuccess)
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
        // close() must NOT clear the store — the next process incarnation
        // is responsible for replay. forgetPairing() in :app is the only
        // path that intentionally clears.
        assertEquals(1, store.loadAll().size, "close should not clear the store")
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

    companion object {
        private val JSON_ID_REGEX = Regex("""\"id\":(\d+)""")
    }
}
