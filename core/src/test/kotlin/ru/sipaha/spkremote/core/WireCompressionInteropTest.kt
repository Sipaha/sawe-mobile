package ru.sipaha.spkremote.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cross-language interop: the mobile codec must decode frames produced by the
 * Rust server codec (`crates/remote_control/src/wire_codec.rs`) byte-for-byte,
 * and vice versa. The golden frames below were emitted by the Rust test suite;
 * the matching Rust test decodes a golden emitted here. If either codec's
 * framing or dictionary drifts, one of the two sides fails.
 */
class WireCompressionInteropTest {

    private val goldenText =
        """{"jsonrpc":"2.0","id":1,"result":{"sessions":[],"total_count":0,"state":{"kind":"idle"}}}"""

    // Produced by Rust `compress(goldenText, WIRE_DICT_PROTO_V1)`.
    private val rustDictHex =
        "73706b7a01010000005978f9262169dcc36e8ba10e926ea4a08ad5410d2c031d8c60027949a9b6b61600fcda1d2f"

    // Produced by Rust `compress(goldenText, WIRE_DICT_NONE)`.
    private val rustNoDictHex =
        "73706b7a01000000005978da15cb310e80200c46e1bbfc7363d0b15731c61064404931b44c84bb8bf37b5fc7ad45" +
            "ea1bc0d8160742bac02ba1466dd9c01d1a55531105ef07c18af97c86d264464750f316ffed4932e5e439628cf101fcda1d2f"

    @Test
    fun `decodes a Rust frame compressed with the protocol dictionary`() {
        assertEquals(goldenText, WireCompression.decompress(hexToBytes(rustDictHex)))
    }

    @Test
    fun `decodes a Rust frame compressed without a dictionary`() {
        assertEquals(goldenText, WireCompression.decompress(hexToBytes(rustNoDictHex)))
    }

    @Test
    fun `Kotlin produces byte-identical frames to Rust`() {
        // Java Deflater(BEST_COMPRESSION) and Rust zlib-rs(best) emit the same
        // DEFLATE bytes for this input, so the two codecs are interchangeable on
        // the wire in both directions, not merely mutually decodable.
        assertEquals(rustDictHex, bytesToHex(WireCompression.compress(goldenText, WIRE_DICT_PROTO_V1)))
        assertEquals(rustNoDictHex, bytesToHex(WireCompression.compress(goldenText, WIRE_DICT_NONE)))
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { ((hex[it * 2].digitToInt(16) shl 4) or hex[it * 2 + 1].digitToInt(16)).toByte() }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
