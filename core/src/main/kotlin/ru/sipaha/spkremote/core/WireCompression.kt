package ru.sipaha.spkremote.core

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Thrown when a binary frame claims to be compressed but is malformed. */
class WireCompressionException(message: String) : Exception(message)

/**
 * App-level wire compression for JSON-RPC frames, negotiated in the handshake.
 *
 * A compressed message travels as a WebSocket BINARY frame so it can carry raw
 * DEFLATE bytes. The 10-byte header keeps it unambiguous from the only other
 * binary frames on this socket — upload chunks, whose first byte is the high
 * byte of a small `u64 upload_id` (`0x00`), never the `0x73` ('s') magic:
 *
 * ```
 * [0..4)  magic "spkz" (0x73 0x70 0x6B 0x7A)
 * [4]     format  (1 = zlib/DEFLATE)
 * [5]     dict id (0 = none, 1 = proto-v1 preset dictionary)
 * [6..10) u32 BE  decompressed length (bounds the inflate buffer + integrity check)
 * [10..)  zlib stream (RFC 1950; FDICT set iff dict id != 0)
 * ```
 *
 * The format is RFC-1950 standard zlib, so it interoperates byte-for-byte with
 * the Rust `flate2` codec in `crates/remote_control/src/wire_codec.rs`.
 */
object WireCompression {
    /** "spkz" — distinguishes a compressed frame from an upload chunk. */
    private val MAGIC = byteArrayOf(0x73, 0x70, 0x6B, 0x7A)
    private const val FORMAT_DEFLATE: Byte = 1
    const val HEADER_BYTES: Int = 10

    /** Hard cap on a single decompressed message (DEFLATE-bomb guard). */
    const val MAX_DECOMPRESSED_BYTES: Int = 32 * 1024 * 1024

    /** Below this raw size compression rarely beats the framing overhead. */
    const val DEFAULT_COMPRESS_THRESHOLD_BYTES: Int = 180

    fun isCompressed(frame: ByteArray): Boolean =
        frame.size >= HEADER_BYTES &&
            frame[0] == MAGIC[0] && frame[1] == MAGIC[1] &&
            frame[2] == MAGIC[2] && frame[3] == MAGIC[3] &&
            frame[4] == FORMAT_DEFLATE

    /** Frame `text` as a compressed binary message. */
    fun compress(text: String, dictId: Int): ByteArray {
        val raw = text.toByteArray(Charsets.UTF_8)
        val deflater = Deflater(Deflater.BEST_COMPRESSION, /* nowrap = */ false)
        try {
            dictionaryFor(dictId)?.let { deflater.setDictionary(it) }
            deflater.setInput(raw)
            deflater.finish()
            val out = ByteArrayOutputStream(HEADER_BYTES + (raw.size / 2) + 16)
            out.write(MAGIC)
            out.write(FORMAT_DEFLATE.toInt())
            out.write(dictId and 0xFF)
            writeU32Be(out, raw.size.toLong())
            val scratch = ByteArray(8192)
            while (!deflater.finished()) {
                val n = deflater.deflate(scratch)
                if (n > 0) out.write(scratch, 0, n)
            }
            return out.toByteArray()
        } finally {
            deflater.end()
        }
    }

    /**
     * Compress only when it's worth it: returns the framed bytes when `text` is
     * at least [threshold] bytes AND the result is strictly smaller than the
     * raw UTF-8, else `null` (caller sends the original as a TEXT frame).
     */
    fun compressIfWorthwhile(
        text: String,
        dictId: Int,
        threshold: Int = DEFAULT_COMPRESS_THRESHOLD_BYTES,
    ): ByteArray? {
        val rawSize = utf8Size(text)
        if (rawSize < threshold) return null
        val frame = compress(text, dictId)
        return if (frame.size < rawSize) frame else null
    }

    /** Decode a compressed binary frame back to the original JSON text. */
    fun decompress(frame: ByteArray): String {
        if (!isCompressed(frame)) {
            throw WireCompressionException("not a compressed frame (bad magic/format)")
        }
        val dictId = frame[5].toInt() and 0xFF
        val declaredLen = readU32Be(frame, 6)
        if (declaredLen > MAX_DECOMPRESSED_BYTES) {
            throw WireCompressionException("declared length $declaredLen exceeds cap")
        }
        val inflater = Inflater(/* nowrap = */ false)
        try {
            inflater.setInput(frame, HEADER_BYTES, frame.size - HEADER_BYTES)
            val out = ByteArray(declaredLen)
            var off = 0
            while (off < out.size) {
                val n = inflater.inflate(out, off, out.size - off)
                if (n > 0) {
                    off += n
                    continue
                }
                if (inflater.needsDictionary()) {
                    val dict = dictionaryFor(dictId)
                        ?: throw WireCompressionException("frame needs dictionary $dictId, none known")
                    inflater.setDictionary(dict)
                    continue
                }
                // No progress and no dictionary needed → input exhausted before
                // the declared length was produced: a truncated/corrupt frame.
                break
            }
            if (off != out.size || !inflater.finished()) {
                throw WireCompressionException(
                    "inflate produced $off of declared $declaredLen bytes (finished=${inflater.finished()})",
                )
            }
            // Reject any trailing compressed data beyond the declared length.
            if (inflater.inflate(ByteArray(1)) != 0) {
                throw WireCompressionException("frame carries data past declared length")
            }
            return String(out, Charsets.UTF_8)
        } catch (e: DataFormatException) {
            throw WireCompressionException("inflate failed: ${e.message}")
        } finally {
            inflater.end()
        }
    }

    private fun dictionaryFor(dictId: Int): ByteArray? = when (dictId) {
        WIRE_DICT_NONE -> null
        WIRE_DICT_PROTO_V1 -> WIRE_DICT_PROTO_V1_BYTES
        else -> throw WireCompressionException("unknown dictionary id $dictId")
    }

    private fun utf8Size(text: String): Int {
        var bytes = 0
        for (c in text) {
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                Character.isHighSurrogate(c) -> 2 // pair → 4 total, counted 2+2
                else -> 3
            }
        }
        return bytes
    }

    private fun writeU32Be(out: ByteArrayOutputStream, value: Long) {
        out.write(((value ushr 24) and 0xFF).toInt())
        out.write(((value ushr 16) and 0xFF).toInt())
        out.write(((value ushr 8) and 0xFF).toInt())
        out.write((value and 0xFF).toInt())
    }

    private fun readU32Be(buf: ByteArray, offset: Int): Int {
        val v = ((buf[offset].toLong() and 0xFF) shl 24) or
            ((buf[offset + 1].toLong() and 0xFF) shl 16) or
            ((buf[offset + 2].toLong() and 0xFF) shl 8) or
            (buf[offset + 3].toLong() and 0xFF)
        if (v > Int.MAX_VALUE) throw WireCompressionException("declared length too large")
        return v.toInt()
    }
}
