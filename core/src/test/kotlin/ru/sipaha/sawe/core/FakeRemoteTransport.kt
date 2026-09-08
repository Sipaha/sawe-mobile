package ru.sipaha.sawe.core

import java.util.concurrent.ConcurrentLinkedQueue
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Test fake for [RemoteTransport].
 *
 * One [FakeRemoteTransport] instance corresponds to one WS attempt. The
 * factory ([FakeRemoteTransportFactory]) hands one out per `connect()` call,
 * letting tests drive multiple attempts deterministically (a "disconnect"
 * means the *current* transport closes; the next attempt gets a fresh one).
 *
 * The test surface:
 *   - [completeHandshake]: simulate server-side nonce → verdict path. Drives
 *     the listener through the same byte sequence the real server would,
 *     using the pairing secret to produce the expected HMAC response.
 *   - [emit] / [emitBinary]: post a frame to the listener as if it came
 *     from the wire.
 *   - [sent]: every text frame the client called `send(text)` for.
 *   - [closeFromServer]: simulate the server hanging up.
 *   - [failFromServer]: simulate a transport-level failure.
 */
internal open class FakeRemoteTransport(
    private val pairingUrl: PairingUrl,
    val listener: RemoteTransportListener,
) : RemoteTransport {
    val sent: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()
    val sentBinary: ConcurrentLinkedQueue<ByteArray> = ConcurrentLinkedQueue()
    @Volatile var closed: Boolean = false
        private set
    /** True once [cancel] was used instead of a graceful [close]. */
    @Volatile var cancelled: Boolean = false
        private set
    /** What [queuedBytes] reports; tests set it to fake writer backlog. */
    @Volatile var queuedBytesValue: Long = 0L
    @Volatile private var clientResponseReceived = false

    override fun send(text: String): Boolean {
        if (closed) return false
        sent += text
        return true
    }

    override fun send(bytes: ByteArray): Boolean {
        if (closed) return false
        sentBinary += bytes
        clientResponseReceived = true
        return true
    }

    override fun close(code: Int, reason: String) {
        if (closed) return
        closed = true
        listener.onClosed(code, reason)
    }

    override fun cancel() {
        cancelled = true
        // A real cancel aborts without a close handshake — no onClosed.
        closed = true
    }

    override fun queuedBytes(): Long = queuedBytesValue

    /**
     * Walk the listener through the full text/JSON handshake — same
     * three-frame protocol the real R-2 server uses:
     *   1. Server → text: `{"type":"challenge","challenge":"<hex>","v":1}`
     *   2. Client → text: `{"type":"response","response":"<hex>"}`
     *   3. Server → text: `{"type":"welcome","client":"<name>"}`
     *
     * Returns true if the handshake completed successfully (client's
     * response hex matches the HMAC we expect).
     */
    fun completeHandshake(negotiateCompression: Boolean = false): Boolean {
        val nonce = ByteArray(HmacChallengeAuth.NONCE_LEN) { (it + 1).toByte() }
        val challengeHex = HexCodec.encode(nonce)
        val challengeFrame =
            """{"type":"challenge","challenge":"$challengeHex","v":1}"""
        listener.onText(challengeFrame)
        // The listener is synchronous in our fake — by the time onText
        // returns, the client has sent its response.
        val lastText = sent.lastOrNull() ?: return false
        // Parse-and-verify like the real server: check only the HMAC `response`
        // field, ignoring optional capability fields (compress/dict) the client
        // now advertises. An exact-string match would be brittle to additions.
        val obj = runCatching { JsonRpc.json.parseToJsonElement(lastText).jsonObject }
            .getOrNull() ?: return false
        if (obj["type"]?.jsonPrimitive?.contentOrNull != "response") return false
        val expectedHmac = expectedHmac(pairingUrl.secret, nonce)
        val expectedHex = HexCodec.encode(expectedHmac)
        if (obj["response"]?.jsonPrimitive?.contentOrNull != expectedHex) return false
        val welcome = if (negotiateCompression) {
            """{"type":"welcome","client":"${pairingUrl.client}","compress":"deflate","dict":1}"""
        } else {
            """{"type":"welcome","client":"${pairingUrl.client}"}"""
        }
        listener.onText(welcome)
        return true
    }

    /** Emit a server→client text frame post-handshake. */
    fun emit(text: String) {
        listener.onText(text)
    }

    /** Emit a server→client binary frame post-handshake. */
    fun emitBinary(bytes: ByteArray) {
        listener.onBinary(bytes)
    }

    /** Simulate the server closing the WS cleanly. */
    fun closeFromServer(code: Int = 1000, reason: String = "server closed") {
        if (closed) return
        closed = true
        listener.onClosed(code, reason)
    }

    /** Simulate a transport-level failure (network error, etc.). */
    fun failFromServer(cause: Throwable) {
        if (closed) return
        closed = true
        listener.onFailure(cause)
    }

    /**
     * Report a failure to the listener while leaving the socket open.
     *
     * Models the events that arrive for a socket that is fine at the TCP
     * level: OkHttp's pong timeout, and the heartbeat watchdog's synthetic
     * teardown. Two of them can be in the client's event queue at once,
     * which is exactly the case the lifecycle has to disambiguate.
     */
    fun signalFailureKeepingOpen(cause: Throwable) {
        listener.onFailure(cause)
    }

    private fun expectedHmac(secret: ByteArray, nonce: ByteArray): ByteArray {
        // Mirror HmacChallengeAuth.respond: prepend the domain-separation
        // tag before HMAC-ing the nonce. The real server side
        // (`crates/remote_control/src/auth.rs::HMAC_DOMAIN_TAG`) does the
        // same; if the fake transport doesn't, every RemoteClientLifecycle
        // handshake hangs because the simulated verdict doesn't match the
        // client's response.
        val mac = Mac.getInstance(HmacChallengeAuth.ALGORITHM)
        mac.init(SecretKeySpec(secret, HmacChallengeAuth.ALGORITHM))
        mac.update(HmacChallengeAuth.HMAC_DOMAIN_TAG)
        mac.update(nonce)
        return mac.doFinal()
    }
}

/**
 * Test factory that records every [connect] call and lets tests reach into
 * the resulting transports.
 *
 * The factory is bounded — `connect()` consumes one entry from [pendingHooks]
 * if present, otherwise creates a default fake. Tests that need to control
 * a specific attempt (e.g. "fail the second connect with SSL") can pre-load
 * a hook that wraps the listener.
 */
internal class FakeRemoteTransportFactory : RemoteTransportFactory {
    val transports = ConcurrentLinkedQueue<FakeRemoteTransport>()
    val pendingHooks = ArrayDeque<(PairingUrl, RemoteTransportListener) -> FakeRemoteTransport>()

    /**
     * Whether `connect` immediately reports the WebSocket as open. Set to
     * false to simulate a connect that hangs before the HTTP 101 (black-holed
     * TLS handshake), which the client times out on a different budget than
     * a peer that opens and then goes quiet.
     */
    var autoOpen: Boolean = true

    override fun connect(
        url: PairingUrl,
        listener: RemoteTransportListener,
    ): RemoteTransport {
        val hook = if (pendingHooks.isNotEmpty()) pendingHooks.removeFirst() else null
        val tx = hook?.invoke(url, listener) ?: FakeRemoteTransport(url, listener)
        transports += tx
        if (autoOpen) listener.onOpen()
        return tx
    }

    fun latest(): FakeRemoteTransport = transports.last()
}
