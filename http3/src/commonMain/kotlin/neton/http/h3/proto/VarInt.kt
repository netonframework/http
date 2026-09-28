package neton.http.h3.proto

import neton.io.bytes.Buffer

/**
 * QUIC variable-length integers (RFC 9000 §16), as used by HTTP/3 (`h3::proto::varint`, `proto/coding.rs`).
 *
 * ⚖️ Kotlin shape: a value is a non-negative [Long] below 2^62 instead of the reference's `VarInt` newtype, and
 * decoding reports "not enough bytes" (`UnexpectedEnd`) as [INCOMPLETE] (-1, never a valid value) instead of an error
 * value, so the common "wait for more input" path neither allocates nor throws.
 */
object VarInt {
    /** The largest representable value, 2^62 - 1 (`VarInt::MAX`). */
    const val MAX: Long = (1L shl 62) - 1

    /** The largest encoded length (`VarInt::MAX_SIZE`). */
    const val MAX_SIZE: Int = 8

    /** Returned by the decoders when the input ends before the integer does. */
    const val INCOMPLETE: Long = -1L

    /** Whether [x] can be encoded (`VarInt::from_u64` succeeds). */
    fun isValid(x: Long): Boolean = x in 0..MAX

    /** The number of bytes needed to encode [x] (`VarInt::size`). */
    fun size(x: Long): Int = when {
        x < 0 -> throw IllegalArgumentException("malformed VarInt: $x")
        x < (1L shl 6) -> 1
        x < (1L shl 14) -> 2
        x < (1L shl 30) -> 4
        x <= MAX -> 8
        else -> throw IllegalArgumentException("malformed VarInt: $x")
    }

    /** The length of an encoded value from its first byte (`VarInt::encoded_size`). */
    fun encodedSize(first: Byte): Int = 1 shl ((first.toInt() and 0xff) ushr 6)

    /**
     * Decodes one integer from `src[off, end)` (`VarInt::decode`); returns [INCOMPLETE] when the input ends first.
     * The caller learns the number of bytes read from [encodedSize] of `src[off]`.
     */
    fun decode(src: ByteArray, off: Int, end: Int): Long {
        if (off >= end) return INCOMPLETE
        val first = src[off].toInt() and 0xff
        val n = 1 shl (first ushr 6)
        if (end - off < n) return INCOMPLETE
        var x = (first and 0x3f).toLong()
        for (i in 1 until n) x = (x shl 8) or (src[off + i].toLong() and 0xff)
        return x
    }

    /** Decodes one integer from the readable bytes of [buf], consuming it; [INCOMPLETE] (nothing consumed) if short. */
    fun decode(buf: Buffer): Long {
        val start = buf.readerIndex()
        val x = decode(buf.backingArray(), start, buf.writerIndex())
        if (x >= 0) buf.skip(encodedSize(buf.backingArray()[start]))
        return x
    }

    /**
     * Writes [x] into [dst] at [off] in its shortest form (`VarInt::encode`), returning the offset after it.
     * @throws IllegalArgumentException when [x] is not below 2^62 (the reference's `write_var` unwraps and panics).
     */
    fun encode(x: Long, dst: ByteArray, off: Int): Int {
        when (size(x)) {
            1 -> { dst[off] = x.toByte(); return off + 1 }
            2 -> {
                dst[off] = ((x ushr 8) or 0x40).toByte(); dst[off + 1] = x.toByte()
                return off + 2
            }
            4 -> {
                dst[off] = ((x ushr 24) or 0x80).toByte(); dst[off + 1] = (x ushr 16).toByte()
                dst[off + 2] = (x ushr 8).toByte(); dst[off + 3] = x.toByte()
                return off + 4
            }
            else -> {
                dst[off] = ((x ushr 56) or 0xc0).toByte()
                for (i in 1..7) dst[off + i] = (x ushr (56 - 8 * i)).toByte()
                return off + 8
            }
        }
    }

    /** Appends [x] to [buf] in its shortest form (`BufMutExt::write_var`). */
    fun encode(x: Long, buf: Buffer) {
        val n = size(x)
        buf.reserve(n)
        val end = encode(x, buf.backingArray(), buf.writerIndex())
        buf.commitWrite(end - buf.writerIndex())
    }
}
