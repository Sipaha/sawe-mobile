package ru.sipaha.sawe.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cross-repo agreement tests for [Digests.bodyDigest].
 *
 * The three vectors below are SHARED LITERALS: the same strings appear in
 * the server's `sha256_16_hex_reference_vectors` test
 * (`crates/solution_agent/src/mcp/tests.rs`). They are copied, never
 * recomputed on either side — the whole point is that an accidental
 * divergence in the digest definition fails a test here instead of splicing
 * a tail onto the wrong prefix at runtime, which produces a corrupted
 * transcript and no error at all.
 */
class DigestsTest {

    private fun digestOf(s: String) = Digests.bodyDigest(s.toByteArray(Charsets.UTF_8))

    @Test
    fun `bodyDigest matches the shared reference vectors`() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb924", digestOf(""))
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e", digestOf("hello"))
        assertEquals("3c48591d8d098a4538f5e013dfcf406e", digestOf("héllo"))
    }

    /**
     * The reason the third vector exists. `"héllo".length` is 5 (UTF-16
     * units) but the body is 6 UTF-8 bytes, and `markdown_len` is a BYTE
     * count — the server splices at that offset with Rust's `String::len()`.
     * A client that paired the digest with `String.length` would offer
     * `(5, digest-of-6-bytes)` and the server would splice one byte early.
     */
    @Test
    fun `markdown_len counts utf8 bytes not utf16 units`() {
        val body = "héllo"
        assertEquals(5, body.length, "sanity: Kotlin String.length is UTF-16 units")
        assertEquals(6, body.toByteArray(Charsets.UTF_8).size)

        val entry = entry(index = 0, markdown = body.repeat(200))
        val known = buildKnownEntries(listOf(entry)).single()
        assertEquals(
            entry.markdown!!.toByteArray(Charsets.UTF_8).size.toLong(),
            known.markdownLen,
        )
        assertTrue(known.markdownLen > entry.markdown!!.length.toLong())
    }

    @Test
    fun `bodyDigest is 32 lowercase hex chars`() {
        val digest = digestOf("anything at all")
        assertEquals(32, digest.length)
        assertEquals(Digests.DIGEST_HEX_LEN, digest.length)
        assertTrue(digest.all { it in '0'..'9' || it in 'a'..'f' }, "not lowercase hex: $digest")
    }

    /** Guards the truncation point: fixed at 16 bytes, not "half the digest". */
    @Test
    fun `bodyDigest is the first 16 bytes of the full sha256`() {
        val full = java.security.MessageDigest.getInstance("SHA-256")
            .digest("hello".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        assertEquals(full.take(32), digestOf("hello"))
    }
}
