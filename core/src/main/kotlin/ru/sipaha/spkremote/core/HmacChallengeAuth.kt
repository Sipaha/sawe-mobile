package ru.sipaha.spkremote.core

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Client side of the R-2 handshake.
 *
 * Flow once the WS upgrade has completed:
 *   1. Server sends a 16-byte random nonce.
 *   2. Client replies with `HMAC-SHA256(secret, nonce)` — exactly 32 bytes.
 *   3. Server sends an ASCII verdict, either `"OK"` or `"REJECT"`.
 *
 * This class encapsulates step 2 (the only step that has any cryptographic
 * substance on the client). The transport itself is owned by [RemoteClient];
 * tests can exercise [respond] without any sockets.
 */
class HmacChallengeAuth(secret: ByteArray) {

    init {
        require(secret.size == PairingUrl.SECRET_LEN) {
            "secret must be ${PairingUrl.SECRET_LEN} bytes, was ${secret.size}"
        }
    }

    private val keySpec = SecretKeySpec(secret, ALGORITHM)

    /** Compute the HMAC-SHA256 response for the server's 16-byte nonce. */
    fun respond(nonce: ByteArray): ByteArray {
        require(nonce.size == NONCE_LEN) {
            "nonce must be $NONCE_LEN bytes, was ${nonce.size}"
        }
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(keySpec)
        return mac.doFinal(nonce)
    }

    /** Parse the verdict frame after sending a response. */
    fun isAccepted(verdictBytes: ByteArray): Boolean {
        val verdict = verdictBytes.toString(Charsets.US_ASCII).trim()
        return verdict.equals(VERDICT_OK, ignoreCase = false)
    }

    companion object {
        const val ALGORITHM = "HmacSHA256"
        const val NONCE_LEN = 16
        const val RESPONSE_LEN = 32
        const val VERDICT_OK = "OK"
        const val VERDICT_REJECT = "REJECT"
    }
}
