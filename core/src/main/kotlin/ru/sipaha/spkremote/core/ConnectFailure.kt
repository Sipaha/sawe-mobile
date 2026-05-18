package ru.sipaha.spkremote.core

/**
 * Classified reason a [RemoteClient] connection attempt failed.
 *
 * Each variant carries a [userMessage] suitable for direct display in the
 * UI — short, actionable, and free of stack-trace noise — plus an optional
 * [cause] for debug logging. The split exists so the UI never has to
 * pattern-match on free-form error strings; the call site is what decides
 * how to phrase a failure, not the consumer.
 *
 * Categorisation:
 * - [Unreachable] — TCP/TLS couldn't connect (no route, refused, timeout,
 *   DNS lookup failed). Retryable. Most likely cause: server's IP/port not
 *   reachable from the phone (NAT not port-forwarded, wrong public IP in
 *   the QR, server not running, firewall).
 * - [TlsPinMismatch] — TLS handshake completed at the protocol level but
 *   the peer's leaf certificate didn't match the SHA-256 pin from the QR.
 *   Terminal. Either the server regenerated its cert (rare; persisted by
 *   default per R-2) or a man-in-the-middle is in the path.
 * - [AuthRejected] — server explicitly rejected the HMAC challenge.
 *   Terminal. The secret in the QR is wrong, the client was removed from
 *   the server's authorised list, or the secret was rotated.
 * - [HandshakeTimeout] — TCP+TLS+WS upgrade succeeded but the server
 *   didn't send the 16-byte challenge nonce within [HANDSHAKE_TIMEOUT_MS]
 *   (10s by default). Retryable; usually means a peer that isn't actually
 *   spk-editor on the other end, or a server bug.
 * - [ProtocolError] — the server sent something this client doesn't
 *   understand (wrong-length nonce, malformed verdict, unexpected text
 *   frame before nonce). Retryable per attempt but recurring = server
 *   protocol drift.
 * - [TlsNegotiationFailed] — the TLS handshake itself failed for a reason
 *   that wasn't a pin mismatch (cipher/protocol mismatch, peer aborted
 *   the handshake mid-flight, …). Retryable per attempt, but the same
 *   error recurring usually means a misconfigured server.
 * - [ServerClosed] — server closed the WebSocket during the handshake
 *   (often code 1008 "policy violation" = unauthorised client). Often
 *   means the same as [AuthRejected] but framed differently.
 * - [Unknown] — fallback for anything the classifier didn't recognise.
 *   The raw message is preserved in [userMessage] so the user at least
 *   has something to copy when reporting a bug.
 */
sealed interface ConnectFailure {

    /** Short, user-facing reason. Shown in [UiState.Disconnected] / banner. */
    val userMessage: String

    /** Original throwable, when one was available. For debug logging. */
    val cause: Throwable?

    /** True if a retry has any reasonable chance of succeeding. */
    val isRetryable: Boolean

    data class Unreachable(
        override val userMessage: String,
        override val cause: Throwable? = null,
    ) : ConnectFailure {
        override val isRetryable: Boolean = true
    }

    data class TlsPinMismatch(
        override val userMessage: String = DEFAULT_MESSAGE,
        override val cause: Throwable? = null,
    ) : ConnectFailure {
        override val isRetryable: Boolean = false
        companion object {
            const val DEFAULT_MESSAGE =
                "Server's TLS certificate doesn't match the pinned fingerprint. " +
                    "Re-pair from the editor's Remote Control panel."
        }
    }

    data class AuthRejected(
        override val userMessage: String = DEFAULT_MESSAGE,
        override val cause: Throwable? = null,
    ) : ConnectFailure {
        override val isRetryable: Boolean = false
        companion object {
            const val DEFAULT_MESSAGE =
                "Server rejected the pairing secret. Re-pair from the editor's " +
                    "Remote Control panel."
        }
    }

    data class TlsNegotiationFailed(
        val reason: String,
        override val cause: Throwable? = null,
    ) : ConnectFailure {
        override val userMessage: String = "TLS handshake failed: $reason"
        override val isRetryable: Boolean = true
    }

    data class HandshakeTimeout(
        val elapsedMs: Long,
        override val cause: Throwable? = null,
    ) : ConnectFailure {
        override val userMessage: String =
            "Server didn't complete the handshake within ${elapsedMs / 1000}s. " +
                "The remote endpoint may not be spk-editor."
        override val isRetryable: Boolean = true
    }

    data class ProtocolError(
        val detail: String,
        override val cause: Throwable? = null,
    ) : ConnectFailure {
        override val userMessage: String = "Protocol error: $detail"
        override val isRetryable: Boolean = true
    }

    data class ServerClosed(
        val code: Int,
        val reason: String,
        override val cause: Throwable? = null,
    ) : ConnectFailure {
        override val userMessage: String = buildString {
            append("Server closed the connection")
            if (code != 0) append(" (code $code)")
            if (reason.isNotBlank()) append(": $reason")
            if (code == 1008) append(". This usually means an authorisation failure — try re-pairing.")
        }
        override val isRetryable: Boolean = code !in TERMINAL_CLOSE_CODES
        companion object {
            /** WebSocket close codes that the spk-editor listener uses for non-retryable rejects. */
            val TERMINAL_CLOSE_CODES = setOf(1008 /* policy violation = unauthorised */)
        }
    }

    data class Unknown(
        override val userMessage: String,
        override val cause: Throwable? = null,
    ) : ConnectFailure {
        override val isRetryable: Boolean = true
    }

    companion object {
        const val HANDSHAKE_TIMEOUT_MS: Long = 10_000

        /**
         * Classify a thrown exception from [OkHttpRemoteTransport] into
         * a [ConnectFailure] variant. Recurses through `cause` chains
         * because OkHttp wraps the actual SSL / IO error a few levels deep.
         */
        fun classify(t: Throwable): ConnectFailure {
            var cur: Throwable? = t
            while (cur != null) {
                val cls = cur.javaClass.name
                val msg = cur.message.orEmpty()
                // Order matters — fingerprint-pin errors are also
                // SSLHandshakeException subclasses; check them first.
                if (msg.contains("fingerprint", ignoreCase = true) ||
                    msg.contains("pin mismatch", ignoreCase = true) ||
                    msg.contains("did not match", ignoreCase = true)
                ) {
                    return TlsPinMismatch(cause = t)
                }
                if (cls == "javax.net.ssl.SSLPeerUnverifiedException" ||
                    cls.endsWith(".CertificateException")
                ) {
                    return TlsPinMismatch(cause = t)
                }
                if (cls == "javax.net.ssl.SSLHandshakeException") {
                    // The pin-mismatch branch above already returned for the
                    // common case (fingerprint message present anywhere in
                    // the cause chain). If we got here the handshake failed
                    // for some OTHER reason — protocol/cipher mismatch, peer
                    // abort, etc. — and labelling it `TlsPinMismatch` would
                    // wrongly tell the user to re-pair.
                    return TlsNegotiationFailed(
                        reason = msg.ifBlank { "TLS handshake aborted" },
                        cause = t,
                    )
                }
                if (cls == "java.net.SocketTimeoutException") {
                    return Unreachable("Connection timed out — server unreachable.", cause = t)
                }
                if (cls == "java.net.ConnectException") {
                    val detail = if (msg.contains("refused", ignoreCase = true)) {
                        "Connection refused. The server isn't running on that port."
                    } else {
                        "Couldn't reach the server: $msg"
                    }
                    return Unreachable(detail, cause = t)
                }
                if (cls == "java.net.UnknownHostException") {
                    return Unreachable("Couldn't resolve host: ${msg.ifBlank { "<unknown>" }}", cause = t)
                }
                if (cls == "java.net.NoRouteToHostException" ||
                    cls == "java.net.SocketException"
                ) {
                    return Unreachable("Network error: $msg", cause = t)
                }
                cur = cur.cause
            }
            // Fallback to text-match on the top-level message for cases the
            // class-name walk missed (some wrappers swallow class info).
            val topMsg = t.message.orEmpty()
            return Unknown(
                userMessage = "Connection failed: ${topMsg.ifBlank { t.javaClass.simpleName }}",
                cause = t,
            )
        }
    }
}

/**
 * Thrown out of [RemoteClient.connect] when the first connection attempt
 * fails. Carries the classified [failure] so the caller can render a
 * human-readable error without parsing strings.
 */
class ConnectException(val failure: ConnectFailure) :
    RuntimeException(failure.userMessage, failure.cause)

/**
 * Thrown when [RemoteClient.call] is invoked while the transport isn't
 * live — either pre-first-connect, mid-reconnect, or after a terminal
 * failure. [lastFailure] carries the most recent [ConnectFailure] the
 * lifecycle saw, so the UI can render a specific reason ("Connection
 * refused…") instead of just "not connected".
 *
 * [lastFailure] is null only when no attempt has actually failed yet
 * (e.g. the very first call() arrives before the initial handshake
 * completes); in that case the message says we're still connecting.
 */
class NotConnectedException(val lastFailure: ConnectFailure? = null) :
    RuntimeException(
        lastFailure?.userMessage
            ?: "Not connected to the server (initial handshake still in progress).",
        lastFailure?.cause,
    )
