package ru.sipaha.sawe.core

import java.security.MessageDigest

/**
 * Content digests that BOTH peers compute over the same bytes.
 *
 * The only member today is [bodyDigest], the entry-body digest behind
 * [WireFeature.ENTRY_BODY_DELTA]. Its definition is a wire contract, not an
 * implementation detail: the server verifies the client's digest against the
 * prefix of the body it is about to send, and a match licenses it to reply
 * with only the tail. If the two sides ever disagree about a single bit, the
 * result is a tail spliced onto the wrong prefix — a silently corrupted
 * transcript, not a decode error. See [rehydrateEntryBodies].
 */
object Digests {

    /**
     * Lowercase hex of the FIRST 16 BYTES of `SHA-256(utf8)` — exactly 32
     * characters from `[0-9a-f]`, no prefix and no separators.
     *
     * Byte-for-byte identical to the server's `sha256_16_hex`
     * (`crates/solution_agent/src/mcp/dto.rs`). Every part of that sentence
     * is load-bearing:
     *
     *  - **SHA-256**, because both platforms have it in the standard
     *    toolchain, so there is no seed, bit-order or overflow convention
     *    for the two implementations to disagree about.
     *  - **The first 16 bytes**, fixed at 16 — not "half the digest".
     *  - **Lowercase** hex; the server compares the strings, so `A1` would
     *    simply never match.
     *
     * [utf8] must already be the UTF-8 ENCODING of the body prefix
     * (`String.toByteArray(Charsets.UTF_8)`), which is why this takes a
     * `ByteArray` and not a `String`: the length that accompanies the digest
     * on the wire is a byte count, and taking a `String` here would invite a
     * caller to pair the digest with a UTF-16 `String.length`.
     *
     * Reference vectors — shared literals, asserted verbatim on both sides:
     *
     * | input | UTF-8 bytes | digest |
     * |---|---|---|
     * | `""` | 0 | `e3b0c44298fc1c149afbf4c8996fb924` |
     * | `"hello"` | 5 | `2cf24dba5fb0a30e26e83b2ac5b9e29e` |
     * | `"héllo"` | 6 | `3c48591d8d098a4538f5e013dfcf406e` |
     */
    fun bodyDigest(utf8: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(utf8)
        val sb = StringBuilder(DIGEST_HEX_LEN)
        for (i in 0 until DIGEST_PREFIX_BYTES) {
            val b = digest[i].toInt() and 0xFF
            sb.append(HEX[b ushr 4])
            sb.append(HEX[b and 0x0F])
        }
        return sb.toString()
    }

    /** How many leading bytes of the SHA-256 the digest keeps. */
    const val DIGEST_PREFIX_BYTES: Int = 16

    /** Length of a [bodyDigest] string: two hex chars per kept byte. */
    const val DIGEST_HEX_LEN: Int = DIGEST_PREFIX_BYTES * 2

    private val HEX = "0123456789abcdef".toCharArray()
}
