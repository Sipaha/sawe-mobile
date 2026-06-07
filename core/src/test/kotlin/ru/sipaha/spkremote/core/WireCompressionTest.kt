package ru.sipaha.spkremote.core

import java.util.zip.Adler32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WireCompressionTest {

    private val sampleJson =
        """{"jsonrpc":"2.0","method":"remote/notification","params":{"kind":""" +
            "\"session_entry_appended\",\"session_id\":\"s-1\",\"entry\":{\"index\":42," +
            "\"role\":\"assistant\",\"text\":\"" + "lorem ipsum dolor sit amet ".repeat(40) +
            "\",\"tool_call\":null,\"created_ms\":1700000000000}}}"

    @Test
    fun `round-trips with the protocol dictionary`() {
        val frame = WireCompression.compress(sampleJson, WIRE_DICT_PROTO_V1)
        assertTrue(WireCompression.isCompressed(frame))
        assertEquals(sampleJson, WireCompression.decompress(frame))
    }

    @Test
    fun `round-trips without a dictionary`() {
        val frame = WireCompression.compress(sampleJson, WIRE_DICT_NONE)
        assertEquals(sampleJson, WireCompression.decompress(frame))
    }

    @Test
    fun `compresses a repetitive payload to well under half`() {
        val frame = WireCompression.compress(sampleJson, WIRE_DICT_PROTO_V1)
        assertTrue(
            frame.size < sampleJson.toByteArray().size / 2,
            "expected <50% of ${sampleJson.toByteArray().size}, got ${frame.size}",
        )
    }

    @Test
    fun `unicode survives the round-trip`() {
        val text = """{"text":"Привет, мир — 你好 🌍 🚀"}"""
        val frame = WireCompression.compress(text, WIRE_DICT_PROTO_V1)
        assertEquals(text, WireCompression.decompress(frame))
    }

    @Test
    fun `isCompressed is false for plain JSON text bytes and upload-style frames`() {
        assertFalse(WireCompression.isCompressed(sampleJson.toByteArray()))
        // Upload chunk frame: first 8 bytes are a small u64 upload_id (BE),
        // so byte[0] == 0x00 — never the 0x73 ('s') magic.
        val uploadish = buildUploadChunkFrame(uploadId = 7, offset = 0, payload = ByteArray(32))
        assertFalse(WireCompression.isCompressed(uploadish))
    }

    @Test
    fun `decompress rejects a bad magic`() {
        val frame = WireCompression.compress(sampleJson, WIRE_DICT_NONE)
        frame[0] = 0x00
        assertFailsWith<WireCompressionException> { WireCompression.decompress(frame) }
    }

    @Test
    fun `decompress rejects an oversized declared length`() {
        val frame = WireCompression.compress(sampleJson, WIRE_DICT_NONE)
        // Overwrite the u32 BE declared-length field (bytes 6..10) with a huge value.
        frame[6] = 0x7F; frame[7] = 0xFF.toByte(); frame[8] = 0xFF.toByte(); frame[9] = 0xFF.toByte()
        assertFailsWith<WireCompressionException> { WireCompression.decompress(frame) }
    }

    @Test
    fun `decompress rejects a truncated payload`() {
        val frame = WireCompression.compress(sampleJson, WIRE_DICT_NONE)
        val truncated = frame.copyOf(frame.size - 5)
        assertFailsWith<WireCompressionException> { WireCompression.decompress(truncated) }
    }

    @Test
    fun `compressIfWorthwhile returns null below the threshold`() {
        assertEquals(null, WireCompression.compressIfWorthwhile("hi", WIRE_DICT_PROTO_V1, threshold = 64))
    }

    @Test
    fun `compressIfWorthwhile compresses and round-trips a large payload`() {
        val frame = WireCompression.compressIfWorthwhile(sampleJson, WIRE_DICT_PROTO_V1, threshold = 64)
        assertTrue(frame != null)
        assertEquals(sampleJson, WireCompression.decompress(frame!!))
    }

    @Test
    fun `protocol dictionary Adler-32 matches the cross-language pin`() {
        val adler = Adler32().apply { update(WIRE_DICT_PROTO_V1_BYTES) }.value
        assertEquals(WIRE_DICT_PROTO_V1_ADLER32, adler)
    }
}
