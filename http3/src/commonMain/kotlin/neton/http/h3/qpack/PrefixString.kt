@file:OptIn(InternalHttpApi::class)

package neton.http.h3.qpack

import neton.http.internal.HuffmanCodec
import neton.http.internal.InternalHttpApi
import neton.io.bytes.Buffer

/**
 * QPACK string literals (RFC 9204 §4.1.2), `h3::qpack::prefix_string`: a length with a prefix, the Huffman flag just
 * above it, then the bytes.
 *
 * ⚖️ The Huffman code is the one of the HPACK implementation (`neton.http.h2.hpack`, shared through
 * [HuffmanCodec]); the reference has a second, bit-by-bit implementation of the same code. The shared decoder applies
 * RFC 7541 §5.2, which the reference's does not: padding longer than 7 bits, or containing the EOS symbol, is an
 * error.
 */
object PrefixString {
    /** [QpackReader.readString] result: an invalid Huffman string. */
    const val HUFFMAN_INVALID: Int = 3

    /**
     * Appends `value[off, off + len)` Huffman-encoded, its length with a `size - 1`-bit prefix and [flags] above the
     * Huffman bit (`encode`). As in the reference, strings are always Huffman-encoded.
     */
    fun encode(size: Int, flags: Int, value: ByteArray, off: Int, len: Int, buf: Buffer) {
        val encodedLen = HuffmanCodec.encodedLength(value, off, len)
        PrefixInt.encode(size - 1, (flags shl 1) or 1, encodedLen.toLong(), buf)
        buf.reserve(encodedLen)
        val end = HuffmanCodec.encode(value, off, len, buf.backingArray(), buf.writerIndex())
        buf.commitWrite(end - buf.writerIndex())
    }

    /** [encode] of the whole of [value]. */
    fun encode(size: Int, flags: Int, value: ByteArray, buf: Buffer) = encode(size, flags, value, 0, value.size, buf)

    /**
     * Decodes a string from [buf], consuming it (`decode`).
     * @throws PrefixStringException `UnexpectedEnd` (nothing consumed), `Integer` or `HuffmanDecoding`.
     */
    fun decode(size: Int, buf: Buffer): ByteArray {
        val r = QpackReader()
        r.reset(buf.backingArray(), buf.readerIndex(), buf.writerIndex())
        when (r.readString(size, 0)) {
            PrefixInt.OK -> {
                buf.skip(r.pos - buf.readerIndex())
                return r.copyString(0)
            }
            PrefixInt.UNEXPECTED_END -> throw PrefixStringException(PrefixStringError.UnexpectedEnd)
            PrefixInt.OVERFLOW -> throw PrefixStringException(PrefixStringError.Integer)
            else -> throw PrefixStringException(PrefixStringError.HuffmanDecoding)
        }
    }
}

/**
 * Huffman-decodes `src[off, off + len)` into [dst] (at least `2 * len` bytes); returns the decoded length, or -1 for
 * an invalid string.
 */
internal fun decodeHuffman(src: ByteArray, off: Int, len: Int, dst: ByteArray): Int {
    val n = HuffmanCodec.decode(src, off, len, dst, 0)
    return if (n == HuffmanCodec.INVALID) -1 else n
}

/** String literal errors (`prefix_string::Error`); `BufSize` cannot occur with 64-bit lengths checked against the input. */
enum class PrefixStringError { UnexpectedEnd, Integer, HuffmanDecoding }

/** A [PrefixStringError] thrown by [PrefixString.decode]. */
class PrefixStringException(val error: PrefixStringError) : Exception(error.name)
