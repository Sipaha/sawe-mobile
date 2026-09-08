package ru.sipaha.sawe.core

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
 * - [HandshakeTimeout] — the WebSocket opened but the challenge/response/
 *   welcome exchange didn't finish within [HANDSHAKE_TIMEOUT_MS] (10s by
 *   default, measured from the HTTP 101, NOT from the connect call).
 *   Retryable; usually means a peer that isn't actually spk-editor on the
 *   other end, or a server bug. A slow link cannot produce this — the
 *   pre-open phase has its own, much larger [PRE_OPEN_TIMEOUT_MS] budget
 *   and reports [Unreachable] instead.
 * - [ProtocolError] — the server sent something this client doesn't
 *   understand (wrong-length nonce, malformed verdict, unexpected text
 *   frame before nonce). Retryable per attempt but recurring = server
 *   protocol drift.
 * - [TlsNegotiationFailed] — the TLS handshake itself failed for a reason
 *   that wasn't a pin mismatch (cipher/protocol mismatch, peer aborted
 *   the handshake mid-flight, …). Retryable per attempt, but the same
 *   error recurring usually means a misconfigured server.
 * - [ServerClosed] — server closed the WebSocket (often code 1008
 *   "policy violation" = unauthorised client, which means the same as
 *   [AuthRejected] framed differently; or code 1009 "message too big",
 *   which condemns the message rather than the connection — see
 *   [ConnectFailure.rejectsMessage]).
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

    /**
     * True when the peer rejected the **message**, not the connection.
     *
     * Orthogonal to [isRetryable]: reconnecting is right (the link is
     * healthy, the server is willing to talk), but replaying the request
     * that caused it is not — it walks into the same wall, and each
     * attempt costs a full connect + TLS + handshake cycle. The queue
     * bounces such a request back to the user instead of retrying it.
     */
    val rejectsMessage: Boolean get() = false

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
            "Connected to the server, but it didn't finish the pairing " +
                "handshake within ${elapsedMs / 1000}s. The remote endpoint " +
                "may not be spk-editor."
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
            if (code == MESSAGE_TOO_BIG_CLOSE_CODE) {
                append("The server refused the message because it was too large.")
            } else {
                append("Server closed the connection")
                if (code != 0) append(" (code $code)")
                if (reason.isNotBlank()) append(": $reason")
                if (code == 1008) {
                    append(". This usually means an authorisation failure — try re-pairing.")
                }
            }
        }
        override val isRetryable: Boolean = code !in TERMINAL_CLOSE_CODES

        /**
         * 1009 is the server telling us it read a frame above its 1 MiB
         * limit and gave up on that frame — not on us. Reconnecting is
         * correct and happens as usual; re-sending the offending request
         * is not, because the outcome is fixed by the payload.
         */
        override val rejectsMessage: Boolean = code == MESSAGE_TOO_BIG_CLOSE_CODE

        companion object {
            /** WebSocket close codes that the spk-editor listener uses for non-retryable rejects. */
            val TERMINAL_CLOSE_CODES = setOf(1008 /* policy violation = unauthorised */)

            /**
             * RFC 6455 1009 "message too big". The desktop half-closes with
             * this code (and reason "message too big") when an inbound frame
             * exceeds its read limit, so the client learns *why* the socket
             * went away instead of seeing an unexplained drop.
             */
            const val MESSAGE_TOO_BIG_CLOSE_CODE: Int = 1009
        }
    }

    data class Unknown(
        override val userMessage: String,
        override val cause: Throwable? = null,
    ) : ConnectFailure {
        override val isRetryable: Boolean = true
    }

    companion object {
        /**
         * Budget for the post-open handshake only: challenge → response →
         * welcome, i.e. one round trip after the HTTP 101. Matches the
         * per-stage budget the server applies on its side.
         */
        const val HANDSHAKE_TIMEOUT_MS: Long = 10_000

        /**
         * Budget for everything BEFORE the WebSocket opens — DNS, TCP,
         * TLS 1.3, the HTTP upgrade, plus whatever the server's accept
         * limiter adds. That is 3–4 round trips before a single byte of
         * our protocol moves, so on a roaming / EDGE link with a 2–4s RTT
         * it alone can exceed [HANDSHAKE_TIMEOUT_MS]; charging both phases
         * to one 10s budget made every attempt on such a link fail with a
         * "the remote endpoint may not be spk-editor" message while the
         * server logged successful authentications from the attempts that
         * leaked past the timeout.
         *
         * Also the OkHttp `callTimeout` for the same phase, so the socket
         * is released rather than leaked when the budget runs out.
         */
        const val PRE_OPEN_TIMEOUT_MS: Long = 20_000

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

/**
 * Thrown to a caller whose request was **already on the wire** when the
 * transport went away.
 *
 * **Delivery is unknown.** The frame was handed to the socket, so the
 * server may have received and applied it (a user message would then show
 * up on the next poll) or it may have died in the kernel buffer. The two
 * are indistinguishable from here.
 *
 * Consequences for callers:
 *  - Do **not** auto-retry. The server has no `spk_client_send_id`
 *    de-duplication yet, so a blind replay posts the message twice.
 *  - Do **not** report it as an application error either — the server
 *    never said no. For user-authored text the correct recovery is to
 *    put the text back in the composer (bounce to draft) and let the
 *    user decide, which also covers the "it did arrive" case gracefully
 *    because the echo shows up next to the restored draft.
 *
 * Distinct from [NotConnectedException] ("we never sent it — safe to
 * queue") and from a plain server-side error envelope ("the server said
 * no").
 *
 * [failure] carries the classified transport failure when one is known;
 * it is null when the client itself was closed underneath the request.
 */
class TransportLostException(val failure: ConnectFailure? = null) :
    RuntimeException(
        "connection closed: ${failure?.userMessage ?: "client closed"}",
        failure?.cause,
    )

/**
 * Thrown when the transport refused to enqueue a frame (socket already
 * closed or closing).
 *
 * Unlike [TransportLostException] this is unambiguous: nothing was
 * written, so the request is safe to park in the durable queue and send
 * on the next connection. [QueueController] does exactly that.
 *
 * Extends `IllegalStateException` because that is the type this path has
 * always thrown; the subclass only adds the "definitely not delivered"
 * discrimination.
 */
class FrameRefusedException : IllegalStateException("websocket refused frame")

/**
 * Thrown when an outbound frame exceeds [MAX_OUTBOUND_FRAME_BYTES].
 *
 * The server's WebSocket reader rejects inbound messages larger than
 * 1 MiB by tearing the connection down at the framing layer — before any
 * JSON-RPC parsing, so it cannot answer with an error envelope and the
 * client only sees an unexplained drop. Retrying such a frame reconnects,
 * re-sends, and gets dropped again about once a second, indefinitely.
 * Refusing to put it on the wire in the first place keeps the connection
 * usable and gives the caller a message it can actually show.
 *
 * Permanent for the payload: retrying the same bytes cannot succeed.
 */
class FrameTooLargeException(val frameBytes: Int) :
    RuntimeException(
        "message is too large to send (${frameBytes / 1024} KiB, " +
            "limit ${MAX_OUTBOUND_FRAME_BYTES / 1024} KiB)",
    )

/**
 * Hard cap on a single outbound WebSocket frame, compressed size if the
 * frame is compressed. Held below the server's 1 MiB read limit with
 * enough headroom for the WebSocket framing and masking overhead.
 */
const val MAX_OUTBOUND_FRAME_BYTES: Int = 900 * 1024

/**
 * Thrown when the **server** refused a request permanently, in a way that
 * condemns the message rather than the connection — today, a close with
 * code 1009 "message too big".
 *
 * Guarantees for callers:
 *  - The request was **not** applied. Unlike [TransportLostException]
 *    there is no ambiguity: the server told us it discarded the frame.
 *  - Retrying the identical payload cannot succeed, so nothing does.
 *  - The connection is unaffected and reconnects on the normal schedule.
 *
 * For a queued message this arrives together with the
 * `onMessageExpired` bounce — the payload has already been handed back
 * for the user to edit, so the caller only has to clear its optimistic
 * UI, not re-stage the text itself.
 *
 * This is the server-side counterpart to [FrameTooLargeException], which
 * catches the same class of payload before it ever leaves the phone. Both
 * exist because the two limits are set independently: a message under our
 * cap can still be over the desktop's.
 */
class MessageRejectedException(val failure: ConnectFailure) :
    RuntimeException(failure.userMessage, failure.cause)
