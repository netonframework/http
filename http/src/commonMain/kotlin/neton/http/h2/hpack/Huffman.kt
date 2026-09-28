package neton.http.h2.hpack

// Huffman coding of HPACK string literals (RFC 7541 Appendix B), ported from h2 0.4.19 `src/hpack/huffman/mod.rs`.
// The encode table is the reference's `ENCODE_TABLE`; the decode table is built at first use with the same algorithm
// as the reference's generator (`util/genhuff/src/main.rs`), giving the identical 15 x 256 byte-at-a-time table
// (checked by a test against a digest of the reference's `DECODE_TABLE`).

/** Huffman code of each symbol 0..256 (256 is EOS), right-aligned. */
private val HUFF_CODES: IntArray = intArrayOf(
    0x1ff8, 0x7fffd8, 0xfffffe2, 0xfffffe3, 0xfffffe4, 0xfffffe5, 0xfffffe6, 0xfffffe7,
    0xfffffe8, 0xffffea, 0x3ffffffc, 0xfffffe9, 0xfffffea, 0x3ffffffd, 0xfffffeb, 0xfffffec,
    0xfffffed, 0xfffffee, 0xfffffef, 0xffffff0, 0xffffff1, 0xffffff2, 0x3ffffffe, 0xffffff3,
    0xffffff4, 0xffffff5, 0xffffff6, 0xffffff7, 0xffffff8, 0xffffff9, 0xffffffa, 0xffffffb,
    0x14, 0x3f8, 0x3f9, 0xffa, 0x1ff9, 0x15, 0xf8, 0x7fa,
    0x3fa, 0x3fb, 0xf9, 0x7fb, 0xfa, 0x16, 0x17, 0x18,
    0x0, 0x1, 0x2, 0x19, 0x1a, 0x1b, 0x1c, 0x1d,
    0x1e, 0x1f, 0x5c, 0xfb, 0x7ffc, 0x20, 0xffb, 0x3fc,
    0x1ffa, 0x21, 0x5d, 0x5e, 0x5f, 0x60, 0x61, 0x62,
    0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6a,
    0x6b, 0x6c, 0x6d, 0x6e, 0x6f, 0x70, 0x71, 0x72,
    0xfc, 0x73, 0xfd, 0x1ffb, 0x7fff0, 0x1ffc, 0x3ffc, 0x22,
    0x7ffd, 0x3, 0x23, 0x4, 0x24, 0x5, 0x25, 0x26,
    0x27, 0x6, 0x74, 0x75, 0x28, 0x29, 0x2a, 0x7,
    0x2b, 0x76, 0x2c, 0x8, 0x9, 0x2d, 0x77, 0x78,
    0x79, 0x7a, 0x7b, 0x7ffe, 0x7fc, 0x3ffd, 0x1ffd, 0xffffffc,
    0xfffe6, 0x3fffd2, 0xfffe7, 0xfffe8, 0x3fffd3, 0x3fffd4, 0x3fffd5, 0x7fffd9,
    0x3fffd6, 0x7fffda, 0x7fffdb, 0x7fffdc, 0x7fffdd, 0x7fffde, 0xffffeb, 0x7fffdf,
    0xffffec, 0xffffed, 0x3fffd7, 0x7fffe0, 0xffffee, 0x7fffe1, 0x7fffe2, 0x7fffe3,
    0x7fffe4, 0x1fffdc, 0x3fffd8, 0x7fffe5, 0x3fffd9, 0x7fffe6, 0x7fffe7, 0xffffef,
    0x3fffda, 0x1fffdd, 0xfffe9, 0x3fffdb, 0x3fffdc, 0x7fffe8, 0x7fffe9, 0x1fffde,
    0x7fffea, 0x3fffdd, 0x3fffde, 0xfffff0, 0x1fffdf, 0x3fffdf, 0x7fffeb, 0x7fffec,
    0x1fffe0, 0x1fffe1, 0x3fffe0, 0x1fffe2, 0x7fffed, 0x3fffe1, 0x7fffee, 0x7fffef,
    0xfffea, 0x3fffe2, 0x3fffe3, 0x3fffe4, 0x7ffff0, 0x3fffe5, 0x3fffe6, 0x7ffff1,
    0x3ffffe0, 0x3ffffe1, 0xfffeb, 0x7fff1, 0x3fffe7, 0x7ffff2, 0x3fffe8, 0x1ffffec,
    0x3ffffe2, 0x3ffffe3, 0x3ffffe4, 0x7ffffde, 0x7ffffdf, 0x3ffffe5, 0xfffff1, 0x1ffffed,
    0x7fff2, 0x1fffe3, 0x3ffffe6, 0x7ffffe0, 0x7ffffe1, 0x3ffffe7, 0x7ffffe2, 0xfffff2,
    0x1fffe4, 0x1fffe5, 0x3ffffe8, 0x3ffffe9, 0xffffffd, 0x7ffffe3, 0x7ffffe4, 0x7ffffe5,
    0xfffec, 0xfffff3, 0xfffed, 0x1fffe6, 0x3fffe9, 0x1fffe7, 0x1fffe8, 0x7ffff3,
    0x3fffea, 0x3fffeb, 0x1ffffee, 0x1ffffef, 0xfffff4, 0xfffff5, 0x3ffffea, 0x7ffff4,
    0x3ffffeb, 0x7ffffe6, 0x3ffffec, 0x3ffffed, 0x7ffffe7, 0x7ffffe8, 0x7ffffe9, 0x7ffffea,
    0x7ffffeb, 0xffffffe, 0x7ffffec, 0x7ffffed, 0x7ffffee, 0x7ffffef, 0x7fffff0, 0x3ffffee,
    0x3fffffff,
)

/** Bit length of each code in [HUFF_CODES]. */
private val HUFF_LENS: IntArray = intArrayOf(
    13, 23, 28, 28, 28, 28, 28, 28, 28, 24, 30, 28, 28, 30, 28, 28,
    28, 28, 28, 28, 28, 28, 30, 28, 28, 28, 28, 28, 28, 28, 28, 28,
    6, 10, 10, 12, 13, 6, 8, 11, 10, 10, 8, 11, 8, 6, 6, 6,
    5, 5, 5, 6, 6, 6, 6, 6, 6, 6, 7, 8, 15, 6, 12, 10,
    13, 6, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
    7, 7, 7, 7, 7, 7, 7, 7, 8, 7, 8, 13, 19, 13, 14, 6,
    15, 5, 6, 5, 6, 5, 6, 6, 6, 5, 7, 7, 6, 6, 6, 5,
    6, 7, 6, 5, 5, 6, 7, 7, 7, 7, 7, 15, 11, 14, 13, 28,
    20, 22, 20, 20, 22, 22, 22, 23, 22, 23, 23, 23, 23, 23, 24, 23,
    24, 24, 22, 23, 24, 23, 23, 23, 23, 21, 22, 23, 22, 23, 23, 24,
    22, 21, 20, 22, 22, 23, 23, 21, 23, 22, 22, 24, 21, 22, 23, 23,
    21, 21, 22, 21, 23, 22, 23, 23, 20, 22, 22, 22, 23, 22, 22, 23,
    26, 26, 20, 19, 22, 23, 22, 25, 26, 26, 26, 27, 27, 26, 24, 25,
    19, 21, 26, 27, 27, 26, 27, 24, 21, 21, 26, 26, 28, 27, 27, 27,
    20, 24, 20, 21, 22, 21, 21, 23, 22, 22, 25, 25, 24, 24, 26, 23,
    26, 27, 26, 26, 27, 27, 27, 27, 27, 28, 27, 27, 27, 27, 27, 26,
    30,
)

private const val BRANCH = 0x8000
private const val TABLE_INDEX_MASK = 0x7f00
private const val TABLE_WIDTH = 256
private const val ERROR_ENTRY = 0x80ff

/** Returned by [huffmanDecode] for an invalid code, EOS, or bad padding (`DecoderError::InvalidHuffmanCode`). */
internal const val HUFFMAN_INVALID = -1

/**
 * The decode trie: 15 tables of 256 entries, each indexed by the next eight buffered bits. A branch entry has bit 15
 * set and the next table's index in bits 8..14; a leaf holds the number of bits the symbol consumes in its high byte
 * and the symbol in its low byte (reference `huffman/table.rs`).
 */
internal val HUFF_DECODE_TABLE: IntArray = buildDecodeTable()

private fun buildDecodeTable(): IntArray {
    val tables = IntArray(15 * TABLE_WIDTH)
    var tableCount = 1
    for (octet in 0..256) {
        val bitLen = HUFF_LENS[octet]
        var code = HUFF_CODES[octet] shl (32 - bitLen)
        var bitsLeft = bitLen
        var table = 0
        while (bitsLeft > 0) {
            val slot = table * TABLE_WIDTH + (code ushr 24)
            if (bitsLeft <= 8) {
                val value = if (octet == 256) ERROR_ENTRY else (bitsLeft shl 8) or octet
                for (suffix in 0 until (1 shl (8 - bitsLeft))) tables[slot or suffix] = value
            } else if (tables[slot] == 0) {
                val next = tableCount++
                tables[slot] = BRANCH or (next shl 8)
                table = next
            } else {
                table = (tables[slot] and TABLE_INDEX_MASK) ushr 8
            }
            bitsLeft = if (bitsLeft > 8) bitsLeft - 8 else 0
            code = code shl 8
        }
    }
    check(tableCount == 15)
    return tables
}

/**
 * Decodes the Huffman string `src[off, off + len)` into [dst] at [dstOff] (`huffman::decode`). [dst] must have room
 * for `2 * len` bytes (every code is at least five bits). Returns the number of bytes written, or
 * [HUFFMAN_INVALID]. Validates the EOS padding: at most seven bits, all ones, after a completed symbol.
 */
internal fun huffmanDecode(src: ByteArray, off: Int, len: Int, dst: ByteArray, dstOff: Int): Int {
    val t = HUFF_DECODE_TABLE
    var table = 0
    var acc = 0
    var bits = 0
    var o = dstOff
    for (i in off until off + len) {
        acc = (acc shl 8) or (src[i].toInt() and 0xff)
        bits += 8
        while (bits >= 8) {
            val entry = t[table * TABLE_WIDTH + ((acc ushr (bits - 8)) and 0xff)]
            if (entry and BRANCH == 0) {
                dst[o++] = entry.toByte()
                table = 0
                bits -= entry ushr 8
            } else {
                table = (entry and TABLE_INDEX_MASK) ushr 8
                if (table == 0) return HUFFMAN_INVALID
                bits -= 8
            }
        }
    }
    // Fewer than eight bits remain. A prefix of the EOS code (all ones) is valid padding only when the previous
    // symbol has completed.
    while (bits > 0) {
        val padding = (1 shl bits) - 1
        if (table == 0 && acc and padding == padding) break
        val entry = t[table * TABLE_WIDTH + ((acc shl (8 - bits)) and 0xff)]
        if (entry and BRANCH != 0) return HUFFMAN_INVALID
        val used = entry ushr 8
        if (used > bits) return HUFFMAN_INVALID
        dst[o++] = entry.toByte()
        table = 0
        bits -= used
    }
    return if (table == 0) o - dstOff else HUFFMAN_INVALID
}

/** Length in bytes of the Huffman encoding of `src[off, off + len)`, EOS padding included. */
internal fun huffmanEncodedLength(src: ByteArray, off: Int, len: Int): Int {
    val lens = HUFF_LENS
    var bits = 0L
    for (i in off until off + len) bits += lens[src[i].toInt() and 0xff]
    return ((bits + 7) ushr 3).toInt()
}

/** Length in bytes of the Huffman encoding of the ASCII string [s] (every char below 0x80). */
internal fun huffmanEncodedLength(s: String): Int {
    val lens = HUFF_LENS
    var bits = 0L
    for (i in s.indices) bits += lens[s[i].code]
    return ((bits + 7) ushr 3).toInt()
}

/**
 * Huffman-encodes `src[off, off + len)` into [dst] at [dstOff] (`huffman::encode`), padding the last byte with the
 * EOS prefix. [dst] must have room for [huffmanEncodedLength] bytes. Returns the offset after the last written byte.
 */
internal fun huffmanEncode(src: ByteArray, off: Int, len: Int, dst: ByteArray, dstOff: Int): Int {
    var bits = 0L
    var bitsLeft = 40
    var o = dstOff
    val codes = HUFF_CODES
    val lens = HUFF_LENS
    for (i in off until off + len) {
        val b = src[i].toInt() and 0xff
        val nbits = lens[b]
        bits = bits or (codes[b].toLong() shl (bitsLeft - nbits))
        bitsLeft -= nbits
        while (bitsLeft <= 32) {
            dst[o++] = (bits ushr 32).toByte()
            bits = bits shl 8
            bitsLeft += 8
        }
    }
    if (bitsLeft != 40) {
        // The EOS prefix pads the last byte.
        bits = bits or ((1L shl bitsLeft) - 1)
        dst[o++] = (bits ushr 32).toByte()
    }
    return o
}

/** [huffmanEncode] for an ASCII string (every char below 0x80). */
internal fun huffmanEncode(s: String, dst: ByteArray, dstOff: Int): Int {
    var bits = 0L
    var bitsLeft = 40
    var o = dstOff
    val codes = HUFF_CODES
    val lens = HUFF_LENS
    for (i in s.indices) {
        val b = s[i].code
        val nbits = lens[b]
        bits = bits or (codes[b].toLong() shl (bitsLeft - nbits))
        bitsLeft -= nbits
        while (bitsLeft <= 32) {
            dst[o++] = (bits ushr 32).toByte()
            bits = bits shl 8
            bitsLeft += 8
        }
    }
    if (bitsLeft != 40) {
        bits = bits or ((1L shl bitsLeft) - 1)
        dst[o++] = (bits ushr 32).toByte()
    }
    return o
}
