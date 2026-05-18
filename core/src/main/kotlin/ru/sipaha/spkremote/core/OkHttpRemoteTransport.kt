package ru.sipaha.spkremote.core

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import okhttp3.CipherSuite
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.TlsVersion
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * OkHttp-backed [RemoteTransportFactory].
 *
 * Each call to [connect] creates a fresh [WebSocket]. The underlying
 * [OkHttpClient] is shared across attempts so connection-pool / DNS / TLS
 * caches survive a reconnect. The fingerprint-pinning TrustManager is
 * keyed off [PairingUrl.fingerprint], so the pin survives a reconnect too;
 * that's the *desired* behaviour — if a future server reissues its leaf,
 * we want the pin to fail and report [ConnectionState.FailedTerminal].
 *
 * `pingInterval` is set to **30 s** per the R-6a spec — that's enough to
 * keep NAT translations alive on residential LTE / Wi-Fi while staying
 * well clear of common 60 s idle-timeout proxies.
 */
internal class OkHttpRemoteTransportFactory(
    private val builder: (PairingUrl) -> OkHttpClient.Builder = ::defaultBuilder,
) : RemoteTransportFactory {

    // ConcurrentHashMap (not HashMap) because [connect] is invoked from
    // arbitrary caller threads — multiple RemoteClients sharing the same
    // factory (multi-server pairing) can race the per-URL OkHttpClient
    // cache lookup. `getOrPut` is acceptable here since the OkHttpClient
    // is cheap to construct redundantly on a rare race.
    private val clients = ConcurrentHashMap<PairingUrl, OkHttpClient>()

    override fun connect(
        url: PairingUrl,
        listener: RemoteTransportListener,
    ): RemoteTransport {
        val client = clients.getOrPut(url) { builder(url).build() }
        val request = Request.Builder()
            .url("https://${url.host}:${url.port}/remote")
            .header("X-Spk-Remote-Client", url.client)
            .build()
        val socket = client.newWebSocket(request, Adapter(listener))
        return OkHttpRemoteTransport(socket)
    }

    private class Adapter(private val listener: RemoteTransportListener) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            listener.onOpen()
        }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            listener.onBinary(bytes.toByteArray())
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            listener.onText(text)
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            listener.onFailure(t)
        }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            listener.onClosed(code, reason)
        }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // Acknowledge the close to flush the queue, then OkHttp delivers
            // onClosed on the same thread.
            webSocket.close(code, reason)
        }
    }

    companion object {
        private val secureRandom = SecureRandom()

        fun defaultBuilder(url: PairingUrl): OkHttpClient.Builder {
            val trustManager = FingerprintPinningTrustManager(url.fingerprint)
            val sslContext = SSLContext.getInstance("TLSv1.3").apply {
                init(null, arrayOf(trustManager), secureRandom)
            }
            // Enforce TLS 1.3 strictly via ConnectionSpec. The SSLContext
            // setting above only configures the default protocol; OkHttp /
            // platform code will still negotiate TLS 1.2 if the server
            // offers it. We do NOT seed from RESTRICTED_TLS: its cipher
            // list is TLS-1.2-only (TLS_ECDHE_*, TLS_DHE_RSA_*), and
            // intersected with TLS 1.3 it can yield zero negotiable
            // suites on stacks like Conscrypt that don't auto-append TLS
            // 1.3 suites to a user-provided list. Seed from MODERN_TLS
            // (which gives us isTls=true + supportsTlsExtensions=true)
            // and override BOTH tlsVersions and cipherSuites with the
            // three mandatory TLS 1.3 AEAD suites.
            val tls13Spec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                .tlsVersions(TlsVersion.TLS_1_3)
                .cipherSuites(
                    CipherSuite.TLS_AES_128_GCM_SHA256,
                    CipherSuite.TLS_AES_256_GCM_SHA384,
                    CipherSuite.TLS_CHACHA20_POLY1305_SHA256,
                )
                .build()
            return OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .connectionSpecs(listOf(tls13Spec))
                .hostnameVerifier { _, _ -> true } // fingerprint pin obsoletes hostname check
                .pingInterval(30, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
        }
    }
}

private class OkHttpRemoteTransport(private val socket: WebSocket) : RemoteTransport {
    override fun send(text: String): Boolean = socket.send(text)
    override fun send(bytes: ByteArray): Boolean = socket.send(bytes.toByteString())
    override fun close(code: Int, reason: String) {
        socket.close(code, reason)
    }
}
