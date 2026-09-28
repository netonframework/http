package neton.http.h3.qpack

import neton.io.bytes.Buffer

/**
 * QPACK prefixed integers (RFC 9204 §4.1.1, RFC 7541 §5.1), `h3::qpack::prefix_int`.
 *
 * Values are unsigned 64-bit, held in a [Long] (the reference decodes up to 2^63 + 254, so the top bit is used).
 * The integer occupies the low [size] bits of the first byte; the bits above it are the representation's flags.
 */
object PrefixInt {
    /** Decoding result: success. */
    const val OK: Int = 0

    /** Decoding result: the input ended first (`Error::UnexpectedEnd`). */
    const val UNEXPECTED_END: Int = 1

    /** Decoding result: too many continuation bytes (`Error::Overflow`). */
    const val OVERFLOW: Int = 2

    /** Continuation bits after which decoding stops (`MAX_POWER`). */
    private const val MAX_POWER = 9 * 7

    /** The longest encoding: one prefix byte and ten continuation bytes. */
    const val MAX_ENCODED_SIZE: Int = 11

    /**
     * Writes [value] (unsigned) with a [size]-bit prefix and [flags] above it into [dst] at [off] (`encode`); returns
     * the offset after it. [dst] needs [MAX_ENCODED_SIZE] bytes of room.
     * @throws IllegalArgumentException when [size] is not within 1..8 (the reference asserts).
     */
    fun encode(size: Int, flags: Int, value: Long, dst: ByteArray, off: Int): Int {
        require(size in 1..8) { "prefix size $size" }
        val mask = (0xff ushr (8 - size))
        val f = (flags shl size) and 0xff
        val v = value.toULong()
        if (v < mask.toULong()) {
            dst[off] = (f or value.toInt()).toByte()
            return off + 1
        }
        dst[off] = (mask or f).toByte()
        var p = off + 1
        var remaining = v - mask.toULong()
        while (remaining >= 128u) {
            dst[p++] = ((remaining % 128u).toInt() + 128).toByte()
            remaining /= 128u
        }
        dst[p++] = remaining.toInt().toByte()
        return p
    }

    /** Appends [value] with a [size]-bit prefix and [flags] (`encode`). */
    fun encode(size: Int, flags: Int, value: Long, buf: Buffer) {
        buf.reserve(MAX_ENCODED_SIZE)
        val end = encode(size, flags, value, buf.backingArray(), buf.writerIndex())
        buf.commitWrite(end - buf.writerIndex())
    }

    /** A decoded integer: the [flags] above the prefix and the unsigned [value]. */
    data class Decoded(val flags: Int, val value: Long)

    /**
     * Decodes an integer with a [size]-bit prefix from [buf], consuming it (`decode`).
     * @throws PrefixIntException `UnexpectedEnd` (nothing consumed) or `Overflow`.
     * @throws IllegalArgumentException when [size] is not within 1..8.
     */
    fun decode(size: Int, buf: Buffer): Decoded {
        val r = QpackReader()
        r.reset(buf.backingArray(), buf.readerIndex(), buf.writerIndex())
        when (r.readInt(size)) {
            OK -> {
                buf.skip(r.pos - buf.readerIndex())
                return Decoded(r.flags, r.value)
            }
            UNEXPECTED_END -> throw PrefixIntException(PrefixIntError.UnexpectedEnd)
            else -> throw PrefixIntException(PrefixIntError.Overflow)
        }
    }
}

/** Prefixed integer errors (`prefix_int::Error`). */
enum class PrefixIntError { Overflow, UnexpectedEnd }

/** A [PrefixIntError] thrown by [PrefixInt.decode]. */
class PrefixIntException(val error: PrefixIntError) : Exception(error.name)

/**
 * A cursor over QPACK input `src[pos, end)` that decodes prefixed integers and strings without allocating (the hot
 * path of the header block and instruction decoders; the reference decodes from a `Buf`). Results are left in fields:
 * [flags] and [value] after [readInt]; [strArray], [strOff] and [strLen] (per slot) after [readString]. A literal
 * string is a range of [src]; a Huffman string is decoded into a scratch array owned by the reader and reused.
 *
 * On any result but [PrefixInt.OK], [pos] is where it was before the call.
 */
class QpackReader {
    var src: ByteArray = EMPTY
        private set
    var pos: Int = 0
    var end: Int = 0
        private set

    /** Flags above the prefix of the last integer read. */
    var flags: Int = 0
        private set

    /** The last integer read (unsigned 64-bit). */
    var value: Long = 0
        private set

    val remaining: Int get() = end - pos

    /** The array holding the string of each slot (after [readString]). */
    val strArray: Array<ByteArray> = arrayOf(EMPTY, EMPTY)

    /** The offset of the string of each slot in [strArray]. */
    val strOff: IntArray = IntArray(2)

    /** The length of the string of each slot. */
    val strLen: IntArray = IntArray(2)

    private val scratch = arrayOf(EMPTY, EMPTY)

    fun reset(src: ByteArray, off: Int, end: Int) {
        this.src = src
        this.pos = off
        this.end = end
    }

    /** The next byte, without consuming it; [pos] must be below [end]. */
    fun peek(): Int = src[pos].toInt() and 0xff

    /** Reads an integer with a [size]-bit prefix (`prefix_int::decode`); returns [PrefixInt.OK] or an error. */
    fun readInt(size: Int): Int {
        require(size in 1..8) { "prefix size $size" }
        var p = pos
        if (p >= end) return PrefixInt.UNEXPECTED_END
        val a = src
        val first = a[p++].toInt() and 0xff
        val mask = 0xff ushr (8 - size)
        val f = first ushr size
        val prefix = first and mask
        if (prefix < mask) {
            flags = f
            value = prefix.toLong()
            pos = p
            return PrefixInt.OK
        }
        var v = mask.toLong()
        var power = 0
        while (true) {
            if (p >= end) return PrefixInt.UNEXPECTED_END
            val b = a[p++].toInt() and 0xff
            v += (b and 127).toLong() shl power
            power += 7
            if (b and 128 == 0) break
            if (power >= 63) return PrefixInt.OVERFLOW
        }
        flags = f
        value = v
        pos = p
        return PrefixInt.OK
    }

    /**
     * Reads a string whose length has a [size]-bit prefix, the Huffman flag being the bit just above it
     * (`prefix_string::decode`), into [slot] (0 or 1, so that a name and a value can be held at once). Returns
     * [PrefixInt.OK], [PrefixInt.UNEXPECTED_END] (also for a length beyond the input), [PrefixInt.OVERFLOW] or
     * [PrefixString.HUFFMAN_INVALID].
     */
    fun readString(size: Int, slot: Int): Int {
        val start = pos
        val r = readInt(size - 1)
        if (r != PrefixInt.OK) return r
        val len = value
        if (len.toULong() > (end - pos).toULong()) {
            pos = start
            return PrefixInt.UNEXPECTED_END
        }
        val n = len.toInt()
        if (flags and 1 == 0) {
            strArray[slot] = src
            strOff[slot] = pos
            strLen[slot] = n
        } else {
            var out = scratch[slot]
            if (out.size < 2 * n) {
                out = ByteArray(maxOf(2 * n, 64))
                scratch[slot] = out
            }
            val m = decodeHuffman(src, pos, n, out)
            if (m < 0) {
                pos = start
                return PrefixString.HUFFMAN_INVALID
            }
            strArray[slot] = out
            strOff[slot] = 0
            strLen[slot] = m
        }
        pos += n
        return PrefixInt.OK
    }

    /** A copy of the string in [slot]. */
    fun copyString(slot: Int): ByteArray = strArray[slot].copyOfRange(strOff[slot], strOff[slot] + strLen[slot])

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
