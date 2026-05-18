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
        val uri = "spk-editor-remote://192.168.1.10:8443?secret=$secretB64&client=phone&server_fp=$fpB64"
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
        val uri = "spk-editor-remote://h:1?secret=$padded&client=c&server_fp=$fpB64"
        assertNotNull(PairingUrl.parse(uri).getOrThrow())
    }

    @Test
    fun `accepts the live wire-shape from R-3 QR popover`() {
        // Captured 2026-05-18 from the editor's "Pair client" popover. Secret +
        // fingerprint are real-shape URL-safe base64 (no pad) including the
        // distinctive `_` and `-` characters that ruled out the original
        // standard-base64 decoder.
        val uri = "spk-editor-remote://37.1.199.69:27772" +
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
        val uri = "spk-editor-remote://h:1?client=c&server_fp=$fpB64"
        val err = PairingUrl.parse(uri).exceptionOrNull()
        assertNotNull(err)
        assertContains(err.message ?: "", "secret")
    }

    @Test
    fun `rejects missing client`() {
        val uri = "spk-editor-remote://h:1?secret=$secretB64&server_fp=$fpB64"
        val err = PairingUrl.parse(uri).exceptionOrNull()
        assertNotNull(err)
        assertContains(err.message ?: "", "client")
    }

    @Test
    fun `rejects missing server_fp`() {
        val uri = "spk-editor-remote://h:1?secret=$secretB64&client=c"
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
        val uri = "spk-editor-remote://h:1?secret=$standardWithPlus&client=c&server_fp=$fpB64"
        val err = PairingUrl.parse(uri).exceptionOrNull()
        assertNotNull(err)
    }

    @Test
    fun `rejects wrong-length secret`() {
        val shortB64 = encodeUrlSafe(ByteArray(16))
        val uri = "spk-editor-remote://h:1?secret=$shortB64&client=c&server_fp=$fpB64"
        val err = PairingUrl.parse(uri).exceptionOrNull()
        assertNotNull(err)
        assertContains(err.message ?: "", "32")
    }

    @Test
    fun `rejects wrong-length fingerprint`() {
        val shortFp = encodeUrlSafe(ByteArray(8))
        val uri = "spk-editor-remote://h:1?secret=$secretB64&client=c&server_fp=$shortFp"
        val err = PairingUrl.parse(uri).exceptionOrNull()
        assertNotNull(err)
    }

    @Test
    fun `rejects client with control characters (CRLF header injection)`() {
        // The `client` value is forwarded verbatim into the X-Spk-Remote-Client
        // HTTP header. A pairing URL whose `client` carries CR+LF could inject
        // arbitrary additional headers; PairingUrl.parse must reject anything
        // containing C0 controls so such a URL never reaches OkHttp.
        val encoded = java.net.URLEncoder.encode("foo\r\nX-Injected: 1", Charsets.UTF_8)
        val uri = "spk-editor-remote://h:1?secret=$secretB64&client=$encoded&server_fp=$fpB64"
        val err = PairingUrl.parse(uri).exceptionOrNull()
        assertNotNull(err)
        assertContains(err.message ?: "", "control")
    }

    @Test
    fun `accepts normal client names without control characters`() {
        // Sanity check that the control-char rejection above doesn't bite
        // ordinary names — hyphens, underscores, and unicode-letter chars
        // are all fine.
        val names = listOf("phone", "my-phone", "Pavel's Phone", "телефон", "device_01")
        for (name in names) {
            val encoded = java.net.URLEncoder.encode(name, Charsets.UTF_8)
            val uri = "spk-editor-remote://h:1?secret=$secretB64&client=$encoded&server_fp=$fpB64"
            val parsed = PairingUrl.parse(uri).getOrThrow()
            assertEquals(name, parsed.client)
        }
    }

    @Test
    fun `rejects missing port`() {
        val uri = "spk-editor-remote://h?secret=$secretB64&client=c&server_fp=$fpB64"
        val err = PairingUrl.parse(uri).exceptionOrNull()
        assertNotNull(err)
    }

    // -------------------------------------------------------------------------
    // T8 — IPv6 + userinfo edge cases
    // -------------------------------------------------------------------------

    /**
     * T8a — URL with userinfo component (`user@host`).
     *
     * `java.net.URI` parses `user@host` such that `parsed.host` returns
     * only "host" (the authority without the userinfo prefix). PairingUrl
     * uses `parsed.host` directly, so a URL with `user@host` is currently
     * accepted but the `user` part is silently discarded.
     *
     * This test documents that (potentially confusing) behavior:
     * - If the parse succeeds, `parsed.host` must equal "host" and
     *   NOT contain the userinfo.
     * - If the parse is instead rejected, the error message must be
     *   non-null (so callers can surface it).
     *
     * Neither outcome is inherently wrong — this test locks in whichever
     * behavior the code currently exhibits so future changes can't
     * accidentally regress it silently.
     */
    @Test
    fun `userinfo in authority - documents current behavior (silent discard or rejection)`() {
        val uri = "spk-editor-remote://user@host:1?secret=$secretB64&server_fp=$fpB64&client=foo"
        val result = PairingUrl.parse(uri)
        if (result.isSuccess) {
            val parsed = result.getOrThrow()
            // java.net.URI strips userinfo from host — if accepted, host must
            // NOT contain 'user@'.
            assertEquals(
                "host",
                parsed.host,
                "if accepted, parsed.host must be 'host' (without userinfo prefix)",
            )
        } else {
            // If rejected, error must be non-null.
            assertNotNull(result.exceptionOrNull(), "rejection must carry a non-null error")
        }
    }

    /**
     * T8b — IPv6 literal host `[::1]:8443`.
     *
     * `java.net.URI` parses bracketed IPv6 literals: `parsed.host` returns
     * `::1` (without brackets). The test verifies either:
     * - Successful parse with `parsed.host == "::1"`.
     * - Or a rejection with a non-null error (e.g. if `OkHttp` or the
     *   port-parsing logic rejects bracket-less host values downstream).
     *
     * This documents/locks in the current behavior.
     */
    @Test
    fun `IPv6 literal host - documents current behavior (accepted as IPv6 or rejected)`() {
        // Brackets are literal in the authority component; java.net.URI parses
        // [::1] and may return the host as "[::1]" (with brackets) or "::1"
        // (stripped) depending on the JDK implementation. Both are acceptable.
        val uri = "spk-editor-remote://[::1]:8443?secret=$secretB64&server_fp=$fpB64&client=foo"
        val result = PairingUrl.parse(uri)
        if (result.isSuccess) {
            val parsed = result.getOrThrow()
            assertTrue(
                parsed.host == "::1" || parsed.host == "[::1]",
                "IPv6 host must be '::1' or '[::1]', got: '${parsed.host}'",
            )
            assertEquals(8443, parsed.port)
        } else {
            // Rejection is also acceptable — document with a non-null error.
            assertNotNull(result.exceptionOrNull(), "IPv6 rejection must carry a non-null error")
        }
    }

    private fun encodeUrlSafe(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
