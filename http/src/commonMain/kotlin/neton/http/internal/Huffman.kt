package neton.http.internal

import neton.http.h2.hpack.HUFFMAN_INVALID
import neton.http.h2.hpack.huffmanDecode
import neton.http.h2.hpack.huffmanEncode
import neton.http.h2.hpack.huffmanEncodedLength

/**
 * The HPACK Huffman code (RFC 7541 Appendix B) of `neton.http.h2.hpack`, shared with QPACK in `com.netonstream:http3`
 * (QPACK string literals use the same code, RFC 9204 §4.1.2), so there is one implementation of it.
 *
 * Internal: see [InternalHttpApi]. The functions delegate to the HPACK ones without any extra work.
 */
@InternalHttpApi
object HuffmanCodec {
    /** Returned by [decode] for an invalid code, an EOS symbol, or bad padding (RFC 7541 §5.2). */
    const val INVALID: Int = HUFFMAN_INVALID

    /**
     * Decodes the Huffman string `src[off, off + len)` into [dst] at [dstOff]. [dst] must have room for `2 * len`
     * bytes (every code is at least five bits). Returns the number of bytes written, or [INVALID].
     */
    fun decode(src: ByteArray, off: Int, len: Int, dst: ByteArray, dstOff: Int): Int =
        huffmanDecode(src, off, len, dst, dstOff)

    /** Length in bytes of the Huffman encoding of `src[off, off + len)`, padding included. */
    fun encodedLength(src: ByteArray, off: Int, len: Int): Int = huffmanEncodedLength(src, off, len)

    /**
     * Huffman-encodes `src[off, off + len)` into [dst] at [dstOff], padding the last byte with the EOS prefix. [dst]
     * must have room for [encodedLength] bytes. Returns the offset after the last written byte.
     */
    fun encode(src: ByteArray, off: Int, len: Int, dst: ByteArray, dstOff: Int): Int =
        huffmanEncode(src, off, len, dst, dstOff)
}
