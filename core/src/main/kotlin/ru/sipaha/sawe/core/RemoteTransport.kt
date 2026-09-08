package ru.sipaha.sawe.core

/**
 * Transport-layer abstraction over a single WebSocket session.
 *
 * [RemoteClient] used to call OkHttp directly inside its handshake listener;
 * R-6a moves the WS lifecycle into a coroutine that owns reconnects, so we
 * need a seam where tests can replace OkHttp with a fake driven by hand.
 *
 * One [RemoteTransport] instance corresponds to one open WS (or one attempt
 * that failed before opening). The owner discards it on close and asks the
 * factory for a fresh one on the next attempt.
 *
 * The contract:
 *   - [send] is fire-and-forget. The transport is responsible for backing
 *     the bytes; on a closed/closing socket it returns `false` and the
 *     caller's listener will see [RemoteTransportListener.onFailure] /
 *     [RemoteTransportListener.onClosed] shortly after.
 *   - [close] requests a graceful close. The transport must still deliver
 *     [RemoteTransportListener.onClosed] (or `onFailure`) so the lifecycle
 *     coroutine can complete its current pass.
 *   - [cancel] aborts immediately, releasing the socket AND any connect
 *     attempt that hasn't produced a socket yet.
 */
internal interface RemoteTransport {
    fun send(text: String): Boolean
    fun send(bytes: ByteArray): Boolean
    fun close(code: Int = 1000, reason: String = "client closing")

    /**
     * Abort the session without a close handshake.
     *
     * [close] can only enqueue a Close frame, which a transport that
     * hasn't finished connecting has no writer for — a graceful close of
     * a socket stuck in TLS or in the HTTP upgrade is a no-op that leaks
     * the underlying connect attempt (its thread and its socket) until
     * the OS gives up on the TCP retransmits. [cancel] is what the
     * handshake-timeout path uses instead.
     *
     * Defaults to [close] so a transport that has nothing extra to
     * release (the in-memory test fake) needs no override.
     */
    fun cancel() {
        close()
    }

    /**
     * Bytes the transport has accepted from [send] but not yet written to
     * the socket, or 0 when the transport can't report it.
     *
     * A WebSocket writer is strictly FIFO with no interleaving: one large
     * queued message delays every frame behind it, including the RPC that
     * carries the next heartbeat. Callers that push bulk traffic (the
     * chunked-upload path) read this to gate the next chunk instead of
     * filling the writer queue and starving chat RPCs.
     */
    fun queuedBytes(): Long = 0L
}

/**
 * Per-attempt callback surface. Methods may be invoked on any thread; the
 * implementation must marshal back to its coroutine context as needed.
 */
internal interface RemoteTransportListener {
    fun onOpen() {}
    fun onBinary(bytes: ByteArray)
    fun onText(text: String)
    fun onClosed(code: Int, reason: String)
    fun onFailure(t: Throwable)
}

/**
 * Factory for fresh [RemoteTransport] instances. The default implementation
 * builds OkHttp WebSocket clients; tests inject [FakeRemoteTransportFactory]
 * (in `:core` test sources) to drive the lifecycle deterministically.
 */
internal interface RemoteTransportFactory {
    fun connect(
        url: PairingUrl,
        listener: RemoteTransportListener,
    ): RemoteTransport
}
