package ru.sipaha.sawe.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The `:core` half of the duplicate-connection fix (2026-09-08).
 *
 * `:app` grew a process-wide guard (`ConnectionManager.kt::ConnectionGuard`)
 * that enforces "at most one live [RemoteClient] per process, last bind
 * wins": when a second `ConnectionManager` binds a client, the previous one
 * is torn down. Two clients against one desktop are fatal — the server
 * allows a single connection per client id and evicts the older socket with
 * `1001 "evicted by new connection"`, so each client reads the eviction as a
 * network drop and re-dials, evicting the other, forever.
 *
 * The guard hands the loser to `RemoteClient.close()` and to nothing else.
 * That choice rests on two properties of `close()` which live here rather
 * than in `:app`, and which are what these tests pin down:
 *
 *  1. **The loser's ladder really stops.** A "handover" that left the loser
 *     dialling would be the bug with extra steps. The observable is the
 *     transport factory: after `close()` it must never be asked for another
 *     socket, however long the virtual clock runs.
 *  2. **The handover costs the user nothing.** A message the loser had
 *     parked in the durable queue must still be on disk afterwards, and the
 *     *winner* must send it. This is the property that makes "last bind
 *     wins" a safe policy instead of a message-eating one; without it the
 *     guard would be trading a reconnect storm for silent data loss.
 *
 * Conventions follow [RemoteClientLifecycleTest] / [RemoteClientAuditRegressionTest]:
 * [FakeRemoteTransport] with [StandardTestDispatcher] so the reconnect
 * backoff is virtual time, and `runCurrent()` rather than
 * `advanceUntilIdle()` so nothing silently ages past a queue TTL.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClientHandoffTest {

    /** Verbatim the reason the desktop closes an evicted socket with. */
    private companion object {
        const val EVICTION_REASON = "evicted by new connection"
        const val EVICTION_CODE = 1001
        const val PARKED_TEXT = "typed while the two clients were fighting"
    }

    private fun fakePairing(): PairingUrl = PairingUrl(
        host = "127.0.0.1",
        port = 8443,
        secret = ByteArray(PairingUrl.SECRET_LEN) { it.toByte() },
        client = "handoff-test",
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
    ) {
        val job = async { client.connect(scope = this@connectAndHandshake) }
        runCurrent()
        factory.latest().completeHandshake()
        runCurrent()
        job.await()
    }

    /**
     * Property 1: the evicted client's reconnect ladder is dead, not merely
     * detached.
     *
     * The setup is the production one exactly: the socket is killed by the
     * server's eviction close, which arms the ladder for the next attempt —
     * and only THEN does the guard close the client. A ladder that survived
     * that would keep re-authenticating against the desktop from a manager
     * nobody can see, which is precisely the storm being fixed.
     */
    @Test
    fun `a closed client never asks the transport factory for another socket`() =
        runTest(StandardTestDispatcher()) {
            val (client, factory) = newClient(backoff = BackoffStrategy.fixed(100L))
            connectAndHandshake(client, factory)
            assertEquals(1, factory.transports.size, "one socket for the first handshake")

            // The desktop evicts us because another client authenticated.
            factory.latest().closeFromServer(code = EVICTION_CODE, reason = EVICTION_REASON)
            runCurrent()
            // The ladder is now armed; without the close below it would dial
            // again at +100ms and keep going.

            client.close()
            runCurrent()

            // Ten minutes of virtual time: far past every rung of the real
            // ladder, capped backoff included.
            advanceTimeBy(10 * 60 * 1000L)
            runCurrent()

            assertEquals(
                1,
                factory.transports.size,
                "a closed client dialled again: ${factory.transports.size} sockets",
            )
            assertTrue(client.isClosed, "close() must be observable on the loser")
            assertTrue(
                client.connectionState.value is ConnectionState.Disconnected,
                "state after close: ${client.connectionState.value}",
            )
        }

    /**
     * Property 1, restated from the winner's side: closing the loser must
     * not disturb the client that took the slot. The two are independent
     * instances with independent factories, and the test would catch a
     * "close everything" teardown that reached across.
     */
    @Test
    fun `closing the loser leaves the winner connected`() = runTest(StandardTestDispatcher()) {
        val (loser, loserFactory) = newClient()
        val (winner, winnerFactory) = newClient()
        connectAndHandshake(loser, loserFactory)
        connectAndHandshake(winner, winnerFactory)

        loser.close()
        runCurrent()
        advanceTimeBy(10 * 60 * 1000L)
        runCurrent()

        assertTrue(loser.isClosed, "the loser must be closed")
        assertFalse(winner.isClosed, "the winner must survive the handover")
        assertTrue(
            winner.connectionState.value is ConnectionState.Connected,
            "winner state: ${winner.connectionState.value}",
        )
        assertEquals(1, loserFactory.transports.size, "the loser must not re-dial")
        assertEquals(1, winnerFactory.transports.size, "the winner must not be forced to re-dial")

        winner.close()
        runCurrent()
    }

    /**
     * Property 2: the handover is free for the user.
     *
     * A message typed while the connection was down sits in the durable
     * queue keyed by server, which both clients share — the same
     * `EncryptedQueueStore` instance in `:app`. When the guard closes the
     * loser, that record must stay on disk (and must NOT be bounced back to
     * the composer, which would let the user send it twice), and the winner
     * must pick it up and put it on the wire.
     */
    @Test
    fun `a message parked in the loser's queue is sent by the winner`() =
        runTest(StandardTestDispatcher()) {
            // One store, two clients — the per-server durable queue.
            val store = InMemoryQueueStore()
            val bounced = mutableListOf<QueuedMessage>()

            // A backoff long enough that the loser cannot reconnect on its
            // own while we set the case up: the message must still be parked
            // when the guard fires.
            val (loser, loserFactory) = newClient(
                backoff = BackoffStrategy.fixed(10 * 60 * 1000L),
                queueStore = store,
                onMessageExpired = { bounced += it },
            )
            connectAndHandshake(loser, loserFactory)
            loserFactory.latest().closeFromServer(code = EVICTION_CODE, reason = EVICTION_REASON)
            runCurrent()

            val outcome = CompletableDeferred<Result<JsonRpcResponse>>()
            val caller = SupervisorJob()
            launch(caller) {
                outcome.complete(
                    runCatching {
                        loser.queueCall(
                            method = "remote.solution_agent.send_message",
                            params = buildJsonObject { put("content", PARKED_TEXT) },
                            ttlMs = 30 * 60 * 1000L,
                        )
                    },
                )
            }
            runCurrent()
            assertEquals(1, store.loadAll().size, "the message should be parked while offline")

            // ---- the guard fires ----
            loser.close()
            runCurrent()

            assertTrue(outcome.isCompleted, "the parked caller must be released, not left on the TTL")
            val error = outcome.await().exceptionOrNull()
            assertTrue(
                error is RemoteClient.ClosedException.StillQueued,
                "a handover owes the caller StillQueued, got $error",
            )
            assertEquals(
                1,
                store.loadAll().size,
                "the handover deleted the user's queued message",
            )
            assertTrue(
                bounced.isEmpty(),
                "nothing was lost, so nothing may be bounced to the composer: $bounced",
            )

            // ---- the winner takes over ----
            val (winner, winnerFactory) = newClient(queueStore = store)
            connectAndHandshake(winner, winnerFactory)
            runCurrent()

            val onTheWire = winnerFactory.latest().sent.toList()
            assertTrue(
                onTheWire.any { it.contains(PARKED_TEXT) },
                "the winner never sent the message it inherited; frames: $onTheWire",
            )

            winner.close()
            caller.cancel()
            runCurrent()
        }
}
