package ru.sipaha.spkremote.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HmacChallengeAuthTest {

    /**
     * Reference vector 1, computed offline with the spk-editor side's exact
     * HMAC pipeline (Python):
     *
     *   secret = byte[32] all 0x42
     *   nonce  = byte[16] ascending 0x00..0x0f
     *   expected = HMAC-SHA256(secret, b"sawe-remote-v1\x00" || nonce)
     *
     * The domain-separation tag MUST match
     * `crates/remote_control/src/auth.rs::HMAC_DOMAIN_TAG`. If this test
     * fails, do NOT update the expected value — either the tag drifted
     * out-of-sync between client + server, or the HMAC pipeline is broken.
     */
    @Test
    fun `vector 1 matches reference`() {
        val secret = ByteArray(32) { 0x42.toByte() }
        val nonce = ByteArray(16) { it.toByte() }
        val expected = hexToBytes(
            "1a100b642ec27489ce7a2281d7723c6aba4350176fd357ef79db708a04e02149"
        )
        val actual = HmacChallengeAuth(secret).respond(nonce)
        assertContentEquals(expected, actual)
    }

    @Test
    fun `vector 2 matches reference`() {
        val secret = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(16) { (0xff - it).toByte() }
        val expected = hexToBytes(
            "47913fd792585282e70154a09688b79d316244dda27011f8c22488b2bf3db938"
        )
        val actual = HmacChallengeAuth(secret).respond(nonce)
        assertContentEquals(expected, actual)
    }

    @Test
    fun `response is always 32 bytes`() {
        val secret = ByteArray(32) { 0x11.toByte() }
        val nonce = ByteArray(16) { 0x00.toByte() }
        val response = HmacChallengeAuth(secret).respond(nonce)
        // HMAC-SHA256 output is always 32 bytes.
        assertContentEquals(
            ByteArray(32),
            response.size.let { ByteArray(it) }
        )
        // separate explicit size assertion for clarity
        kotlin.test.assertEquals(32, response.size)
    }

    @Test
    fun `rejects wrong-length secret`() {
        assertFailsWith<IllegalArgumentException> {
            HmacChallengeAuth(ByteArray(31))
        }
    }

    @Test
    fun `rejects wrong-length nonce`() {
        val auth = HmacChallengeAuth(ByteArray(32))
        assertFailsWith<IllegalArgumentException> { auth.respond(ByteArray(15)) }
        assertFailsWith<IllegalArgumentException> { auth.respond(ByteArray(17)) }
    }

    @Test
    fun `HexCodec round-trips arbitrary bytes`() {
        val raw = ByteArray(32) { it.toByte() }
        val hex = HexCodec.encode(raw)
        assertContentEquals(raw, HexCodec.decode(hex))
        // 32 raw bytes → 64 hex chars (lowercase).
        kotlin.test.assertEquals(64, hex.length)
        kotlin.test.assertEquals(hex, hex.lowercase())
    }

    @Test
    fun `HexCodec accepts mixed case input`() {
        val raw = HexCodec.decode("CAFEBABE")
        assertContentEquals(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()), raw)
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) or
                Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
    }
}
