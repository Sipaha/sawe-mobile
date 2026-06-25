package ru.sipaha.spkremote.core

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Client side of the R-2 handshake.
 *
 * Wire framing (matches `crates/remote_control/src/listener.rs::handle_conn`):
 *
 *   1. Server sends a TEXT frame:
 *      `{"type":"challenge","challenge":"<32 hex chars>","v":1}`
 *      where `challenge` is the hex of a 16-byte server-generated nonce.
 *   2. Client replies with a TEXT frame:
 *      `{"type":"response","response":"<64 hex chars>"}`
 *      where `response` is the hex of
 *      `HMAC-SHA256(secret, HMAC_DOMAIN_TAG || challenge_bytes)` — 32 bytes.
 *   3. Server replies with a TEXT frame on success:
 *      `{"type":"welcome","client":"<name>"}`
 *      …or closes the WebSocket with code 1008 (policy) on auth reject.
 *
 * The domain-separation prefix [HMAC_DOMAIN_TAG] must match
 * `HMAC_DOMAIN_TAG` in `crates/remote_control/src/auth.rs` on the spk-editor
 * side; otherwise the HMAC bytes diverge and every handshake gets a 1008
 * close even with the right secret.
 *
 * This class only owns the HMAC primitive — JSON framing + transport
 * lives in [RemoteClient]; tests can exercise [respond] without any
 * sockets.
 */
class HmacChallengeAuth(secret: ByteArray) {

    init {
        require(secret.size == PairingUrl.SECRET_LEN) {
            "secret must be ${PairingUrl.SECRET_LEN} bytes, was ${secret.size}"
        }
    }

    private val keySpec = SecretKeySpec(secret, ALGORITHM)

    /**
     * Compute the HMAC-SHA256 response for the server's 16-byte nonce.
     * Prepends [HMAC_DOMAIN_TAG] before the nonce so the HMAC input is
     * domain-separated from any other use of the same secret.
     */
    fun respond(nonce: ByteArray): ByteArray {
        require(nonce.size == NONCE_LEN) {
            "nonce must be $NONCE_LEN bytes, was ${nonce.size}"
        }
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(keySpec)
        mac.update(HMAC_DOMAIN_TAG)
        mac.update(nonce)
        return mac.doFinal()
    }

    companion object {
        const val ALGORITHM = "HmacSHA256"
        const val NONCE_LEN = 16

        /**
         * Domain-separation tag prepended to the challenge before HMAC-ing.
         * MUST match `crates/remote_control/src/auth.rs::HMAC_DOMAIN_TAG`
         * — change requires synchronous server + client release.
         *
         * Bytes: `sawe-remote-v1\0` (15 bytes). Renamed from
         * `spk-editor-remote-v1` in lockstep with the editor's
         * spk-editor → sawe rebrand; the tag is a hard handshake invariant,
         * so it carries no backward-compat alias.
         */
        val HMAC_DOMAIN_TAG: ByteArray =
            "sawe-remote-v1".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
    }
}

/** Hex encode/decode helpers used by the [RemoteClient] handshake framing. */
internal object HexCodec {
    fun encode(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            append(HEX_DIGITS[(b.toInt() shr 4) and 0xF])
            append(HEX_DIGITS[b.toInt() and 0xF])
        }
    }

    fun decode(hex: String): ByteArray {
        val trimmed = hex.trim()
        require(trimmed.length % 2 == 0) { "hex string must have even length" }
        val out = ByteArray(trimmed.length / 2)
        for (i in out.indices) {
            val hi = asciiHexValue(trimmed[i * 2])
            val lo = asciiHexValue(trimmed[i * 2 + 1])
            require(hi >= 0 && lo >= 0) { "invalid hex char in '$trimmed'" }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    /**
     * Strict ASCII-only hex value lookup. Returns -1 for anything outside
     * `[0-9A-Fa-f]`. `Character.digit(c, 16)` is too permissive — it
     * accepts Arabic-Indic and Fullwidth digits, neither of which appear
     * on the wire, so we reject them at the decoder rather than trusting
     * the platform's notion of "digit".
     */
    private fun asciiHexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> 10 + (c - 'a')
        in 'A'..'F' -> 10 + (c - 'A')
        else -> -1
    }

    private val HEX_DIGITS = "0123456789abcdef".toCharArray()
}
