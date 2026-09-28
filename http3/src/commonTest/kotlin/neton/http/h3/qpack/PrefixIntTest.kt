package neton.http.h3.qpack

import neton.io.bytes.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

private fun buf(vararg b: Int) = Buffer().also { it.writeBytes(bytes(*b)) }

// Tests of `src/qpack/prefix_int.rs` (all 8 ported).
class PrefixIntTest {
    private fun checkCodec(size: Int, flags: Int, value: Long, data: ByteArray) {
        val b = Buffer()
        PrefixInt.encode(size, flags, value, b)
        assertContentEquals(data, b.peekAll())
        assertEquals(PrefixInt.Decoded(flags, value), PrefixInt.decode(size, b))
        assertTrue(b.isEmpty)
    }

    @Test
    fun codec_5_bits() {
        checkCodec(5, 0b101, 10, bytes(0b1010_1010))
        checkCodec(5, 0b101, 0, bytes(0b1010_0000))
        checkCodec(5, 0b010, 1337, bytes(0b0101_1111, 154, 10))
        checkCodec(5, 0b010, 31, bytes(0b0101_1111, 0))
        checkCodec(5, 0b010, 0x80_00_00_00_00_00_00_1EUL.toLong(), bytes(95, 255, 255, 255, 255, 255, 255, 255, 255, 127))
    }

    @Test
    fun codec_8_bits() {
        checkCodec(8, 0, 42, bytes(0b0010_1010))
        checkCodec(8, 0, 424_242, bytes(255, 179, 240, 25))
        checkCodec(8, 0, 0x80_00_00_00_00_00_00_FEUL.toLong(), bytes(255, 255, 255, 255, 255, 255, 255, 255, 255, 127))
    }

    @Test
    fun size_too_big_value() {
        assertFailsWith<IllegalArgumentException> { PrefixInt.encode(9, 1, 1, Buffer()) }
    }

    @Test
    fun size_too_big_of_size() {
        assertFailsWith<IllegalArgumentException> { PrefixInt.decode(9, Buffer()) }
    }

    @Test
    fun overflow() {
        assertFailsWith<PrefixIntException> { PrefixInt.decode(8, buf(255, 128, 254, 255, 255, 255, 255, 255, 255, 255, 255, 1)) }
    }

    @Test
    fun number_never_ends_with_0x80() {
        checkCodec(4, 0b0001, 143, bytes(31, 128, 1))
    }

    @Test
    fun overflow2() {
        val e = assertFailsWith<PrefixIntException> { PrefixInt.decode(5, buf(95, 225, 255, 255, 255, 255, 255, 255, 255, 255, 1)) }
        assertEquals(PrefixIntError.Overflow, e.error)
    }

    @Test
    fun allow_62_bit() {
        // The largest value a 1-bit prefix can carry; it needs more than 62 bits, which the specification allows.
        val d = PrefixInt.decode(1, buf(3, 255, 255, 255, 255, 255, 255, 255, 255, 127))
        assertEquals(1, d.flags)
        assertEquals(9223372036854775808UL, d.value.toULong())
    }

    // ---- Beyond the reference's tests ----

    @Test
    fun truncatedInputConsumesNothing() {
        val b = buf(0b0101_1111, 154)
        val e = assertFailsWith<PrefixIntException> { PrefixInt.decode(5, b) }
        assertEquals(PrefixIntError.UnexpectedEnd, e.error)
        assertEquals(2, b.readableBytes)
        assertFailsWith<PrefixIntException> { PrefixInt.decode(5, Buffer()) }
    }

    @Test
    fun everyPrefixSizeRoundTrips() {
        for (size in 1..8) for (value in listOf(0L, 1L, (1L shl size) - 2, (1L shl size) - 1, 1L shl size, 1000L, Long.MAX_VALUE)) {
            val b = Buffer()
            PrefixInt.encode(size, 0, value, b)
            assertEquals(PrefixInt.Decoded(0, value), PrefixInt.decode(size, b), "size $size value $value")
        }
        // As in the reference, the encoder can write values (here 2^64 - 1, ten continuation bytes) that the decoder
        // refuses: decoding stops after nine continuation bytes (`MAX_POWER`).
        val b = Buffer()
        PrefixInt.encode(8, 0, -1L, b)
        assertEquals(PrefixIntError.Overflow, assertFailsWith<PrefixIntException> { PrefixInt.decode(8, b) }.error)
    }
}
