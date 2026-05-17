package ru.sipaha.spkremote.core

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PairingUrlTest {

    /** URL-safe base64 (no padding) of 32 bytes of 0x42 — the wire-shape the server emits. */
    private val secretB64 = encodeUrlSafe(ByteArray(32) { 0x42.toByte() })

    /**
     * URL-safe base64 (no padding) of `0x00..0x1f` — 32-byte fingerprint with
     * a recognizable first/last byte for sanity assertions.
     */
    private val fpBytes = ByteArray(32) { it.toByte() }
    private val fpB64 = encodeUrlSafe(fpBytes)

    @Test
    fun `parses a valid URL`() {
        val uri = "spk-remote://192.168.1.10:8443?secret=$secretB64&client=phone&server_fp=$fpB64"
        val parsed = PairingUrl.parse(uri).getOrThrow()
        assertEquals("192.168.1.10", parsed.host)
        assertEquals(8443, parsed.port)
        assertEquals("phone", parsed.client)
        assertEquals(PairingUrl.SECRET_LEN, parsed.secret.size)
        assertEquals(PairingUrl.FP_LEN, parsed.fingerprint.size)
        assertEquals(0x00.toByte(), parsed.fingerprint.first())
        assertEquals(0x1f.toByte(), parsed.fingerprint.last())
        assertTrue(parsed.secret.all { it == 0x42.toByte() })
    }

    @Test
    fun `accepts padded URL-safe base64`() {
        // The server emits NO_PAD; the standard URL-safe decoder must tolerate
        // either flavour so a third-party generator that adds padding still
        // parses.
        val padded = Base64.getUrlEncoder().encodeToString(ByteArray(32) { 0x42.toByte() })
        val uri = "spk-remote://h:1?secret=$padded&client=c&server_fp=$fpB64"
        assertNotNull(PairingUrl.parse(uri).getOrThrow())
    }

    @Test
    fun `accepts the live wire-shape from R-3 QR popover`() {
        // Captured 2026-05-18 from the editor's "Pair client" popover. Secret +
        // fingerprint are real-shape URL-safe base64 (no pad) including the
        // distinctive `_` and `-` characters that ruled out the original
        // standard-base64 decoder.
        val uri = "spk-remote://37.1.199.69:27772" +
            "?secret=w7GiCb0dpOcWmzPtfbUtI2v72SwccpbgRx_1mrStbqo" +
            "&client=my-phone" +
            "&server_fp=f1VXB_EPxPR7Vm6a-IqvLgo8YCD07IdFRoCtt7FFgn8"
        val parsed = PairingUrl.parse(uri).getOrThrow()
        assertEquals("37.1.199.69", parsed.host)
        assertEquals(27772, parsed.port)
        assertEquals("my-phone", parsed.client)
        assertEquals(PairingUrl.SECRET_LEN, parsed.secret.size)
        assertEquals(PairingUrl.FP_LEN, parsed.fingerprint.size)
    }

    @Test
    fun `rejects wrong scheme`() {
        val uri = "http://h:1?secret=$secretB64&client=c&server_fp=$fpB64"
        val err = PairingUrl.parse(uri).exceptionOrNull()
        assertNotNull(err)
        assertContains(err.message ?: "", "scheme")
    }

    @Test
    fun `rejects missing secret`() {
        val uri = "spk-remote://h:1?client=c&server_fp=$fpB64"
        val err = PairingUrl.parse(uri).exceptionOrNull()
        assertNotNull(err)
        assertContains(err.message ?: "", "secret")
    }

    @Test
    fun `rejects missing client`() {
        val uri = "spk-remote://h:1?secret=$secretB64&server_fp=$fpB64"
        val err = PairingUrl.parse(uri).exceptionOrNull()
        assertNotNull(err)
        assertContains(err.message ?: "", "client")
    }

    @Test
    fun `rejects missing server_fp`() {
        val uri = "spk-remote://h:1?secret=$secretB64&client=c"
        val err = PairingUrl.parse(uri).exceptionOrNull()
        assertNotNull(err)
        assertContains(err.message ?: "", "server_fp")
    }

    @Test
    fun `rejects standard-base64 secret with plus or slash`() {
        // Pre-fix behaviour decoded standard base64 (alphabet includes `+`/`/`).
        // The current decoder rejects them — those characters are reserved
        // inside URL queries and the server NEVER emits them in `secret=`.
        val standardWithPlus = "Pk+/" + "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        val uri = "spk-remote://h:1?secret=$standardWithPlus&client=c&server_fp=$fpB64"
        val err = PairingUrl.parse(uri).exceptionOrNull()
        assertNotNull(err)
    }

    @Test
    fun `rejects wrong-length secret`() {
        val shortB64 = encodeUrlSafe(ByteArray(16))
        val uri = "spk-remote://h:1?secret=$shortB64&client=c&server_fp=$fpB64"
        val err = PairingUrl.parse(uri).exceptionOrNull()
        assertNotNull(err)
        assertContains(err.message ?: "", "32")
    }

    @Test
    fun `rejects wrong-length fingerprint`() {
        val shortFp = encodeUrlSafe(ByteArray(8))
        val uri = "spk-remote://h:1?secret=$secretB64&client=c&server_fp=$shortFp"
        val err = PairingUrl.parse(uri).exceptionOrNull()
        assertNotNull(err)
    }

    @Test
    fun `rejects missing port`() {
        val uri = "spk-remote://h?secret=$secretB64&client=c&server_fp=$fpB64"
        val err = PairingUrl.parse(uri).exceptionOrNull()
        assertNotNull(err)
    }

    private fun encodeUrlSafe(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
