package ru.sipaha.spkremote.core

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import javax.net.ssl.SSLContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * Connection lifecycle to one SPK Editor instance.
 *
 * Stages:
 *   1. WebSocket upgrade to `wss://host:port/remote` (TLS pinned by [PairingUrl.fingerprint]).
 *   2. Receive 16-byte binary nonce.
 *   3. Send 32-byte binary HMAC-SHA256 response (see [HmacChallengeAuth]).
 *   4. Receive ASCII verdict `OK` (accept) or `REJECT` (terminate).
 *   5. Carry JSON-RPC text frames in both directions. Frames with an `id`
 *      that matches an outstanding [call] resolve that call. Frames without an
 *      `id` (or with an unknown id) are emitted on [notifications].
 *
 * This class is intentionally minimal — it does no reconnection, no backoff,
 * no message buffering. Higher-level concerns (queued send while connecting,
 * exponential reconnect) are deferred to later phases; the ViewModel can wrap
 * a `RemoteClient` and supply that policy.
 */
class RemoteClient(
    private val url: PairingUrl,
    httpClientBuilder: OkHttpClient.Builder = defaultClientBuilder(url),
) {
    private val client: OkHttpClient = httpClientBuilder.build()
    private val nextId = AtomicLong(1L)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonRpcResponse>>()
    private val _notifications = MutableSharedFlow<JsonElement>(extraBufferCapacity = 64)
    val notifications: SharedFlow<JsonElement> = _notifications.asSharedFlow()

    @Volatile private var webSocket: WebSocket? = null
    @Volatile private var handshakeStage: HandshakeStage = HandshakeStage.AwaitingNonce
    @Volatile private var handshakeResult: CompletableDeferred<Unit>? = null
    private val auth = HmacChallengeAuth(url.secret)

    suspend fun connect(): Result<Unit> = runCatching {
        check(webSocket == null) { "RemoteClient already connected" }
        val gate = CompletableDeferred<Unit>()
        handshakeResult = gate
        handshakeStage = HandshakeStage.AwaitingNonce

        val request = Request.Builder()
            .url("https://${url.host}:${url.port}/remote".toWsUrl())
            .header("X-Spk-Remote-Client", url.client)
            .build()

        webSocket = client.newWebSocket(request, listener)
        gate.await()
    }

    suspend fun call(method: String, params: JsonElement? = null): JsonRpcResponse {
        val ws = checkNotNull(webSocket) { "not connected" }
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonRpcResponse>()
        pending[id] = deferred
        val req = JsonRpc.encodeRequest(JsonRpcRequest(method = method, params = params, id = id))
        return suspendCancellableCoroutine { cont ->
            deferred.invokeOnCompletion { cause ->
                if (cause != null) {
                    cont.resumeWithException(cause)
                } else {
                    @Suppress("UNCHECKED_CAST")
                    cont.resume((deferred as CompletableDeferred<JsonRpcResponse>).getCompleted())
                }
            }
            cont.invokeOnCancellation {
                pending.remove(id)?.cancel()
            }
            val sent = ws.send(req)
            if (!sent) {
                pending.remove(id)
                cont.resumeWithException(IllegalStateException("websocket refused frame"))
            }
        }
    }

    fun close() {
        webSocket?.close(1000, "client closing")
        webSocket = null
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            // Wait for nonce; nothing to send yet.
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            when (handshakeStage) {
                HandshakeStage.AwaitingNonce -> {
                    val nonce = bytes.toByteArray()
                    if (nonce.size != HmacChallengeAuth.NONCE_LEN) {
                        failHandshake(
                            IllegalStateException(
                                "expected ${HmacChallengeAuth.NONCE_LEN}B nonce, got ${nonce.size}B"
                            )
                        )
                        return
                    }
                    val response = auth.respond(nonce)
                    handshakeStage = HandshakeStage.AwaitingVerdict
                    webSocket.send(response.toByteString())
                }
                HandshakeStage.AwaitingVerdict -> {
                    if (auth.isAccepted(bytes.toByteArray())) {
                        handshakeStage = HandshakeStage.Established
                        handshakeResult?.complete(Unit)
                    } else {
                        failHandshake(IllegalStateException("server rejected HMAC"))
                    }
                }
                HandshakeStage.Established -> {
                    // Post-handshake binary frames are unexpected today.
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            when (handshakeStage) {
                HandshakeStage.AwaitingVerdict -> {
                    if (text.trim() == HmacChallengeAuth.VERDICT_OK) {
                        handshakeStage = HandshakeStage.Established
                        handshakeResult?.complete(Unit)
                    } else {
                        failHandshake(IllegalStateException("server verdict: $text"))
                    }
                }
                HandshakeStage.Established -> dispatchJsonRpc(text)
                HandshakeStage.AwaitingNonce ->
                    failHandshake(IllegalStateException("unexpected text frame before nonce"))
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            failHandshake(t)
            pending.values.forEach { it.completeExceptionally(t) }
            pending.clear()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            failHandshake(IllegalStateException("connection closed: $code $reason"))
            pending.values.forEach {
                it.completeExceptionally(IllegalStateException("connection closed"))
            }
            pending.clear()
        }
    }

    private fun dispatchJsonRpc(text: String) {
        val parsed = runCatching { JsonRpc.json.parseToJsonElement(text) }.getOrNull() ?: return
        val obj = parsed.jsonObject
        val id = obj["id"]?.jsonPrimitive?.let { runCatching { it.long }.getOrNull() }
        if (id != null) {
            val deferred = pending.remove(id)
            if (deferred != null) {
                val response = runCatching { JsonRpc.decodeResponse(text) }
                response.fold(
                    onSuccess = { deferred.complete(it) },
                    onFailure = { deferred.completeExceptionally(it) },
                )
                return
            }
        }
        _notifications.tryEmit(parsed)
    }

    private fun failHandshake(cause: Throwable) {
        val gate = handshakeResult ?: return
        if (!gate.isCompleted) gate.completeExceptionally(cause)
    }

    private enum class HandshakeStage { AwaitingNonce, AwaitingVerdict, Established }

    companion object {
        private val secureRandom = SecureRandom()

        fun defaultClientBuilder(url: PairingUrl): OkHttpClient.Builder {
            val trustManager = FingerprintPinningTrustManager(url.fingerprint)
            val sslContext = SSLContext.getInstance("TLSv1.3").apply {
                init(null, arrayOf(trustManager), secureRandom)
            }
            return OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .hostnameVerifier { _, _ -> true } // fingerprint pin obsoletes hostname check
                .pingInterval(20, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
        }

        // OkHttp WebSocket builder requires an http(s):// URL — it does the
        // ws/wss upgrade internally. Translate the user-facing https URL.
        private fun String.toWsUrl(): String = this
    }
}
