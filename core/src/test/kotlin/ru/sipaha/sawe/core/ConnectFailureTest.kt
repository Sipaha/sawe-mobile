package ru.sipaha.sawe.core

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class ConnectFailureTest {

    @Test
    fun `SSLPeerUnverifiedException classifies as TlsPinMismatch`() {
        val cls = ConnectFailure.classify(SSLPeerUnverifiedException("nope"))
        assertTrue(cls is ConnectFailure.TlsPinMismatch)
        assertFalse(cls.isRetryable)
        assertContains(cls.userMessage, "certificate")
    }

    @Test
    fun `CertificateException-with-fingerprint-message classifies as TlsPinMismatch`() {
        val cls = ConnectFailure.classify(CertificateException("server fingerprint did not match expected pin"))
        assertTrue(cls is ConnectFailure.TlsPinMismatch)
    }

    @Test
    fun `SSLHandshakeException with fingerprint message classifies as TlsPinMismatch`() {
        val cls = ConnectFailure.classify(
            SSLHandshakeException("server fingerprint did not match expected pin")
        )
        assertTrue(cls is ConnectFailure.TlsPinMismatch)
    }

    @Test
    fun `SSLHandshakeException without pin message classifies as TlsNegotiationFailed`() {
        val cls = ConnectFailure.classify(
            SSLHandshakeException("Trust anchor for certification path not found.")
        )
        assertTrue(cls is ConnectFailure.TlsNegotiationFailed, "got ${cls::class.simpleName}")
        assertContains(cls.userMessage, "TLS handshake failed", ignoreCase = true)
        assertTrue(cls.isRetryable)
    }

    @Test
    fun `ConnectException refused classifies as Unreachable`() {
        val cls = ConnectFailure.classify(ConnectException("Connection refused"))
        assertTrue(cls is ConnectFailure.Unreachable)
        assertContains(cls.userMessage, "refused", ignoreCase = true)
        assertTrue(cls.isRetryable)
    }

    @Test
    fun `SocketTimeoutException classifies as Unreachable timeout`() {
        val cls = ConnectFailure.classify(SocketTimeoutException("connect timed out"))
        assertTrue(cls is ConnectFailure.Unreachable)
        assertContains(cls.userMessage, "timed out", ignoreCase = true)
    }

    @Test
    fun `UnknownHostException classifies as Unreachable host not found`() {
        val cls = ConnectFailure.classify(UnknownHostException("nope.invalid"))
        assertTrue(cls is ConnectFailure.Unreachable)
        assertContains(cls.userMessage, "resolve", ignoreCase = true)
    }

    @Test
    fun `NoRouteToHostException classifies as Unreachable`() {
        val cls = ConnectFailure.classify(NoRouteToHostException("no route"))
        assertTrue(cls is ConnectFailure.Unreachable)
    }

    @Test
    fun `SocketException classifies as Unreachable`() {
        val cls = ConnectFailure.classify(SocketException("Connection reset"))
        assertTrue(cls is ConnectFailure.Unreachable)
        assertContains(cls.userMessage, "Network error", ignoreCase = true)
    }

    @Test
    fun `wrapped cause is unwrapped`() {
        val root = ConnectException("Connection refused")
        val wrapper = RuntimeException("OkHttp bailout", root)
        val cls = ConnectFailure.classify(wrapper)
        assertTrue(cls is ConnectFailure.Unreachable, "got ${cls::class.simpleName}: ${cls.userMessage}")
    }

    @Test
    fun `unknown exception falls through to Unknown`() {
        val cls = ConnectFailure.classify(IllegalStateException("???"))
        assertTrue(cls is ConnectFailure.Unknown)
        assertTrue(cls.isRetryable)
    }

    @Test
    fun `cause throwable is preserved`() {
        val root = ConnectException("Connection refused")
        val cls = ConnectFailure.classify(root)
        assertEquals(root, cls.cause)
    }

    @Test
    fun `ServerClosed 1008 is terminal`() {
        val cls = ConnectFailure.ServerClosed(1008, "unauthorized")
        assertFalse(cls.isRetryable)
        assertContains(cls.userMessage, "1008")
        assertContains(cls.userMessage, "re-pair", ignoreCase = true)
    }

    @Test
    fun `ServerClosed 1006 is retryable`() {
        val cls = ConnectFailure.ServerClosed(1006, "")
        assertTrue(cls.isRetryable)
    }

    @Test
    fun `AuthRejected defaults to a user-friendly message`() {
        val cls = ConnectFailure.AuthRejected()
        assertContains(cls.userMessage, "Re-pair", ignoreCase = true)
        assertFalse(cls.isRetryable)
    }

    @Test
    fun `HandshakeTimeout formats elapsed seconds`() {
        val cls = ConnectFailure.HandshakeTimeout(10_000)
        assertContains(cls.userMessage, "10s")
        assertTrue(cls.isRetryable)
    }

    @Test
    fun `ConnectException carries userMessage and failure`() {
        val failure = ConnectFailure.Unreachable("Connection refused")
        val ex = ru.sipaha.sawe.core.ConnectException(failure)
        assertEquals(failure, ex.failure)
        assertEquals(failure.userMessage, ex.message)
    }

    @Test
    fun `Unknown variant preserves original message`() {
        val cls = ConnectFailure.classify(IllegalArgumentException("something specific"))
        assertNotNull(cls.userMessage)
        assertContains(cls.userMessage, "something specific")
    }

    // N-08 (server half): 1009 condemns the message, not the connection.

    @Test
    fun `ServerClosed 1009 is retryable but rejects the message`() {
        val failure = ConnectFailure.ServerClosed(1009, "message too big")
        assertTrue(failure.isRetryable, "the link is fine — reconnecting must still happen")
        assertTrue(failure.rejectsMessage, "1009 condemns the message")
        assertTrue(
            failure.userMessage.contains("too large", ignoreCase = true),
            "the banner must say what actually happened: ${failure.userMessage}",
        )
    }

    @Test
    fun `other close codes do not reject the message`() {
        for (code in listOf(1000, 1001, 1006, 1008, 1011)) {
            assertFalse(
                ConnectFailure.ServerClosed(code, "bye").rejectsMessage,
                "close code $code must not condemn the pending message",
            )
        }
        // …and neither does any non-close failure.
        assertFalse(ConnectFailure.Unreachable("no route").rejectsMessage)
        assertFalse(ConnectFailure.HandshakeTimeout(10_000L).rejectsMessage)
    }

    @Test
    fun `MessageRejectedException carries the failure and its message`() {
        val failure = ConnectFailure.ServerClosed(1009, "message too big")
        val error = MessageRejectedException(failure)
        assertEquals(failure, error.failure)
        assertEquals(failure.userMessage, error.message)
    }
}
