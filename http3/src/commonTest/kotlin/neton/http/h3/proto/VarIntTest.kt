package neton.http.h3.proto

import neton.io.bytes.Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The reference's varint.rs and coding.rs have no unit tests (only the fuzz target, ported in VarIntFuzzTest); these
// cover the RFC 9000 §16 / Appendix A.1 examples and the boundaries.
class VarIntTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun encoded(x: Long): ByteArray = Buffer().also { VarInt.encode(x, it) }.readAll()

    @Test
    fun rfc9000Examples() {
        assertEquals(151288809941952652L, VarInt.decode(hex("c2197c5eff14e88c"), 0, 8))
        assertEquals(494878333L, VarInt.decode(hex("9d7f3e7d"), 0, 4))
        assertEquals(15293L, VarInt.decode(hex("7bbd"), 0, 2))
        assertEquals(37L, VarInt.decode(hex("25"), 0, 1))
        // Not the shortest form, still valid.
        assertEquals(37L, VarInt.decode(hex("4025"), 0, 2))
        assertContentEquals(hex("c2197c5eff14e88c"), encoded(151288809941952652L))
        assertContentEquals(hex("9d7f3e7d"), encoded(494878333L))
        assertContentEquals(hex("7bbd"), encoded(15293L))
        assertContentEquals(hex("25"), encoded(37L))
    }

    @Test
    fun sizesAndBoundaries() {
        for ((x, n) in listOf(0L to 1, 63L to 1, 64L to 2, 16383L to 2, 16384L to 4, (1L shl 30) - 1 to 4,
            1L shl 30 to 8, VarInt.MAX to 8)) {
            assertEquals(n, VarInt.size(x), "size of $x")
            val e = encoded(x)
            assertEquals(n, e.size)
            assertEquals(n, VarInt.encodedSize(e[0]))
            assertEquals(x, VarInt.decode(e, 0, e.size))
        }
        assertFailsWith<IllegalArgumentException> { VarInt.size(VarInt.MAX + 1) }
        assertFailsWith<IllegalArgumentException> { encoded(-1) }
        assertTrue(VarInt.isValid(VarInt.MAX))
        assertFalse(VarInt.isValid(VarInt.MAX + 1))
    }

    @Test
    fun incompleteInputConsumesNothing() {
        assertEquals(VarInt.INCOMPLETE, VarInt.decode(ByteArray(0), 0, 0))
        val buf = Buffer()
        buf.writeBytes(hex("c2197c5eff14e8"))
        assertEquals(VarInt.INCOMPLETE, VarInt.decode(buf))
        assertEquals(7, buf.readableBytes)
        buf.writeBytes(hex("8c25"))
        assertEquals(151288809941952652L, VarInt.decode(buf))
        assertEquals(37L, VarInt.decode(buf))
        assertTrue(buf.isEmpty)
    }
}
