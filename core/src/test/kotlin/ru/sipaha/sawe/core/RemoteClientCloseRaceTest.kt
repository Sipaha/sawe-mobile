package ru.sipaha.sawe.core

import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * `close()` racing a handshake that is completing on another thread.
 *
 * Deliberately NOT a `TestScope` test. The window this guards —
 * between the listener completing the handshake on the transport's thread
 * and the lifecycle coroutine publishing the socket — cannot be entered
 * under a single-threaded test dispatcher: there, `close()` and the
 * lifecycle coroutine can never run at the same instant, so the job
 * cancellation always lands first and the publish is simply skipped. Only
 * a genuinely multi-threaded scope (which is what `connect(scope = null)`
 * builds, and what any non-`:app` consumer gets) can interleave them.
 *
 * This is therefore a stress test over the invariant rather than a
 * deterministic reproduction: it asserts what must hold after every
 * interleaving, and runs enough of them to have a good chance of hitting
 * the bad one.
 */
class RemoteClientCloseRaceTest {

    private fun pairing(): PairingUrl = PairingUrl(
        host = "127.0.0.1",
        port = 8443,
        secret = ByteArray(PairingUrl.SECRET_LEN) { it.toByte() },
        client = "close-race",
        fingerprint = ByteArray(PairingUrl.FP_LEN) { (255 - it).toByte() },
    )

    @Test
    fun `close never leaves a Connected state or an unclosed socket behind`() {
        repeat(ITERATIONS) { iteration ->
            val factory = FakeRemoteTransportFactory()
            val client = RemoteClient(
                url = pairing(),
                transportFactory = factory,
                backoff = BackoffStrategy.fixed(50L),
                queueStore = InMemoryQueueStore(),
            )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                runBlocking {
                    scope.launch { runCatching { client.connect(scope = scope) } }
                    // Wait for the attempt to produce a transport.
                    val deadline = System.nanoTime() + SETTLE_TIMEOUT_NANOS
                    while (factory.transports.isEmpty()) {
                        check(System.nanoTime() < deadline) { "no transport on iteration $iteration" }
                        Thread.yield()
                    }
                    val tx = factory.latest()
                    // Drive the handshake to completion on one thread while
                    // close() runs on another, as close together as the
                    // scheduler allows.
                    val handshaker = thread { runCatching { tx.completeHandshake() } }
                    client.close()
                    handshaker.join()
                }

                // Wait for the lifecycle coroutine to finish unwinding.
                // `close()` sets the state synchronously but the attempt
                // that was in flight releases its socket on its own thread,
                // so sampling immediately would report a leak that is only
                // a scheduling lag. A real leak never resolves and still
                // fails when the deadline expires.
                val settleBy = System.nanoTime() + SETTLE_TIMEOUT_NANOS
                while (System.nanoTime() < settleBy &&
                    !(client.connectionState.value is ConnectionState.Disconnected &&
                        factory.transports.all { it.closed || it.cancelled })
                ) {
                    Thread.sleep(1)
                }

                assertTrue(
                    client.connectionState.value is ConnectionState.Disconnected,
                    "iteration $iteration: a closed client must not report " +
                        "${client.connectionState.value}",
                )
                // A socket that authenticated and was then closed underneath
                // must not be left open — the server would hold the session
                // until its idle timeout while our pings kept it alive.
                val leaked = factory.transports.filter { !it.closed && !it.cancelled }
                assertTrue(
                    leaked.isEmpty(),
                    "iteration $iteration: ${leaked.size} authenticated socket(s) leaked by close()",
                )
            } finally {
                scope.cancel()
            }
        }
    }

    private companion object {
        /**
         * Enough interleavings to make the window likely to be hit, small
         * enough to keep the suite fast (each iteration is a full
         * connect/handshake/close cycle on real threads).
         */
        const val ITERATIONS = 400

        /**
         * How long to let an iteration settle before calling it a failure.
         *
         * Generous on purpose. It is not a latency assertion: a genuine
         * leak never settles, so the only thing a longer deadline costs is
         * time on a run that was going to fail anyway — while a short one
         * turns a machine busy with other Gradle daemons into a false
         * failure. (It did: 2s flaked once under four parallel builds.)
         */
        const val SETTLE_TIMEOUT_NANOS = 30_000_000_000L
    }
}
