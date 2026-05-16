package ru.sipaha.spkremote.core

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PairingUrlTest {

    // 32 bytes of 0x42 — see /tmp/encodings.java computation.
    private val secretB64 = "QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI="
    private val fpHexLower = "000102030405060708090a0b0c0d0e0f" +
        "101112131415161718191a1b1c1d1e1f"

    @Test
    fun `parses a valid URL`() {
        val uri = "spk-remote://192.168.1.10:8443?secret=$secretB64&client=phone&fp=$fpHexLower"
        val parsed = PairingUrl.parse(uri).getOrThrow()
        assertEquals("192.168.1.10", parsed.host)
        assertEquals(8443, parsed.port)
        assertEquals("phone", parsed.client)
        assertEquals(PairingUrl.SECRET_LEN, parsed.secret.size)
        assertEquals(PairingUrl.FP_LEN, parsed.fingerprint.size)
        // First and last fingerprint bytes — sanity check that hex decode is correct.
        assertEquals(0x00.toByte(), parsed.fingerprint.first())
        assertEquals(0x1f.toByte(), parsed.fingerprint.last())
        // Secret is 32× 0x42.
        assertTrue(parsed.secret.all { it == 0x42.toByte() })
    }

    @Test
    fun `accepts uppercase fingerprint hex`() {
        val uri = "spk-remote://h:1?secret=$secretB64&client=c&fp=${fpHexLower.uppercase()}"
        val parsed = PairingUrl.parse(uri).getOrThrow()
        assertEquals(0x1f.toByte(), parsed.fingerprint.last())
    }

    @Test
    fun `accepts mixed-case fingerprint hex`() {
        val mixed = buildString {
            for ((i, c) in fpHexLower.withIndex()) {
                append(if (i % 2 == 0) c.uppercaseChar() else c)
            }
        }
        val uri = "spk-remote://h:1?secret=$secretB64&client=c&fp=$mixed"
        assertNotNull(PairingUrl.parse(uri).getOrThrow())
    }

    @Test
    fun `rejects wrong scheme`() {
        val uri = "http://h:1?secret=$secretB64&client=c&fp=$fpHexLower"
        val err = PairingUrl.parse(uri).exceptionOrNull()
        assertNotNull(err)
        assertContains(err.message ?: "", "scheme")
    }

    @Test
    fun `rejects missing secret`() {
        val uri = "spk-remote://h:1?client=c&fp=$fpHexLower"
        val err = PairingUrl.parse(uri).exceptionOrNull()
        assertNotNull(err)
        assertContains(err.message ?: "", "secret")
    }

    @Test
    fun `rejects missing client`() {
        val uri = "spk-remote://h:1?secret=$secretB64&fp=$fpHexLower"
        val err = PairingUrl.parse(uri).exceptionOrNull()
        assertNotNull(err)
        assertContains(err.message ?: "", "client")
    }

    @Test
    fun `rejects missing fingerprint`() {
        val uri = "spk-remote://h:1?secret=$secretB64&client=c"
        val err = PairingUrl.parse(uri).exceptionOrNull()
        assertNotNull(err)
        assertContains(err.message ?: "", "fp")
    }

    @Test
    fun `rejects wrong-length secret`() {
        // 16-byte base64-encoded secret instead of 32.
        val shortB64 = "AAECAwQFBgcICQoLDA0ODw=="
        val uri = "spk-remote://h:1?secret=$shortB64&client=c&fp=$fpHexLower"
        val err = PairingUrl.parse(uri).exceptionOrNull()
        assertNotNull(err)
        assertContains(err.message ?: "", "32")
    }

    @Test
    fun `rejects wrong-length fingerprint`() {
        val shortFp = "0011223344556677"
        val uri = "spk-remote://h:1?secret=$secretB64&client=c&fp=$shortFp"
        val err = PairingUrl.parse(uri).exceptionOrNull()
        assertNotNull(err)
    }

    @Test
    fun `rejects missing port`() {
        val uri = "spk-remote://h?secret=$secretB64&client=c&fp=$fpHexLower"
        val err = PairingUrl.parse(uri).exceptionOrNull()
        assertNotNull(err)
    }
}
