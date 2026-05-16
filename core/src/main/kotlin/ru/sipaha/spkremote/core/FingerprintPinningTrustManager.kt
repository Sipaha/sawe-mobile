package ru.sipaha.spkremote.core

import java.net.Socket
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Trust manager that pins by SHA-256 of the **leaf** certificate's DER encoding.
 *
 * The editor's HTTPS listener uses a self-signed certificate generated on first
 * launch; the corresponding fingerprint travels in the pairing QR. The client
 * doesn't have a CA to verify against, so it pins exactly the leaf and rejects
 * anything else. This is the simpler half of standard "cert pinning" — we don't
 * accept SubjectPublicKeyInfo pins, only the whole-leaf DER hash.
 */
class FingerprintPinningTrustManager(
    private val expectedFingerprint: ByteArray,
) : X509ExtendedTrustManager() {

    init {
        require(expectedFingerprint.size == 32) {
            "expected SHA-256 fingerprint (32 bytes), got ${expectedFingerprint.size}"
        }
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        verify(chain)
    }

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        socket: Socket?,
    ) {
        verify(chain)
    }

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        engine: SSLEngine?,
    ) {
        verify(chain)
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("client auth not supported")
    }

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        socket: Socket?,
    ) {
        throw CertificateException("client auth not supported")
    }

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
        engine: SSLEngine?,
    ) {
        throw CertificateException("client auth not supported")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    private fun verify(chain: Array<out X509Certificate>?) {
        val leaf = chain?.firstOrNull()
            ?: throw CertificateException("server presented an empty chain")
        val actual = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
        if (!constantTimeEquals(actual, expectedFingerprint)) {
            throw CertificateException(
                "leaf certificate fingerprint mismatch (pinning failure)"
            )
        }
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].toInt() xor b[i].toInt())
        }
        return diff == 0
    }
}
