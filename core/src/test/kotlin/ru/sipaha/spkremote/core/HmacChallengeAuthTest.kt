package ru.sipaha.spkremote.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HmacChallengeAuthTest {

    /**
     * Reference vector 1, computed offline with `javax.crypto.Mac`:
     *
     *   secret = byte[32] all 0x42
     *   nonce  = byte[16] ascending 0x00..0x0f
     *   expected = HMAC-SHA256(secret, nonce)
     *
     * The exact hex output was captured from a one-shot JDK 25 run; HMAC-SHA256
     * is deterministic, so this lock value will pass on any conforming JCE
     * provider. If this test ever fails, *do not* update the expected value —
     * something is wrong with the implementation or the provider.
     */
    @Test
    fun `vector 1 matches reference`() {
        val secret = ByteArray(32) { 0x42.toByte() }
        val nonce = ByteArray(16) { it.toByte() }
        val expected = hexToBytes(
            "3c11ddd5996bab20165bb16079e1303302bee56f1479bbebf802ba9a51980cbb"
        )
        val actual = HmacChallengeAuth(secret).respond(nonce)
        assertContentEquals(expected, actual)
    }

    @Test
    fun `vector 2 matches reference`() {
        val secret = ByteArray(32) { it.toByte() }
        val nonce = ByteArray(16) { (0xff - it).toByte() }
        val expected = hexToBytes(
            "1570e414c43bc8fdad1098ba0b3a6aec1a107d271fe6af665c737032cb0a515b"
        )
        val actual = HmacChallengeAuth(secret).respond(nonce)
        assertContentEquals(expected, actual)
    }

    @Test
    fun `response is always 32 bytes`() {
        val secret = ByteArray(32) { 0x11.toByte() }
        val nonce = ByteArray(16) { 0x00.toByte() }
        val response = HmacChallengeAuth(secret).respond(nonce)
        assertContentEquals(
            ByteArray(HmacChallengeAuth.RESPONSE_LEN),
            response.size.let { ByteArray(it) }
        )
        // separate explicit size assertion for clarity
        kotlin.test.assertEquals(HmacChallengeAuth.RESPONSE_LEN, response.size)
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
    fun `parses verdict OK`() {
        val auth = HmacChallengeAuth(ByteArray(32))
        assertTrue(auth.isAccepted("OK".toByteArray(Charsets.US_ASCII)))
        assertTrue(auth.isAccepted("OK\n".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun `parses verdict REJECT`() {
        val auth = HmacChallengeAuth(ByteArray(32))
        assertFalse(auth.isAccepted("REJECT".toByteArray(Charsets.US_ASCII)))
        assertFalse(auth.isAccepted("ok".toByteArray(Charsets.US_ASCII))) // case sensitive
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0)
        return ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) or
                Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
    }
}
