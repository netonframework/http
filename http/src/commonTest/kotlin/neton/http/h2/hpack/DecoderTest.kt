package neton.http.h2.hpack

import neton.http.header.HeaderName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// Ported from h2 0.4.19 `src/hpack/decoder.rs` (5 tests).

class DecoderTest {
    private fun collect(de: Decoder, buf: ByteArray, out: MutableList<Header>): DecoderError? =
        de.decode(buf, 0, buf.size) { out.add(it); true }

    /**
     * `test_peek_u8`: the reference checks its private `peek_u8` helper (reads the first byte without consuming it).
     * The observable equivalent: the representation type comes from a peeked byte, so an incomplete representation
     * consumes nothing.
     */
    @Test
    fun testPeekU8() {
        val de = Decoder()
        val buf = byteArrayOf(0xff.toByte())
        assertEquals(DecoderError.NeedMoreIntegerUnderflow, collect(de, buf, mutableListOf()))
        assertEquals(0, de.consumed)
    }

    /**
     * `test_decode_string_empty`: decoding a string from an empty buffer is `NeedMore(UnexpectedEndOfStream)`. The
     * reference calls the private `decode_string`; here a name-indexed literal whose value string is missing takes
     * the same path.
     */
    @Test
    fun testDecodeStringEmpty() {
        val de = Decoder(0)
        val err = collect(de, byteArrayOf(0x01), mutableListOf())
        assertEquals(DecoderError.NeedMoreUnexpectedEndOfStream, err)
        assertEquals(NeedMore.UnexpectedEndOfStream, err!!.needMore)
    }

    @Test
    fun testDecodeEmpty() {
        val de = Decoder(0)
        assertNull(de.decode(ByteArray(0), 0, 0) { true })
    }

    @Test
    fun testDecodeIndexedLargerThanTable() {
        val de = Decoder(0)
        val buf = byteArrayOf(0b01000000, (0x80 or 2).toByte()) + huffEncode("foo".encodeToByteArray()) +
            byteArrayOf((0x80 or 3).toByte()) + huffEncode("bar".encodeToByteArray())

        val res = mutableListOf<Header>()
        assertNull(collect(de, buf, res))

        assertEquals(1, res.size)
        assertEquals(0, de.tableSize)

        val h = assertIs<Header.Field>(res[0])
        assertEquals("foo", h.name.asStr())
        assertTrue(h.value.contentEquals("bar"))
    }

    @Test
    fun testDecodeContinuationHeaderWithNonHuffEncodedName() {
        val de = Decoder(0)
        val value = huffEncode("bar".encodeToByteArray())
        // The header name is not Huffman-coded; the header value is partial.
        var buf = byteArrayOf(0b01000000, 3) + "foo".encodeToByteArray() +
            byteArrayOf((0x80 or 3).toByte()) + value.copyOfRange(0, 1)

        val res = mutableListOf<Header>()
        // A decode error because the header value is partial.
        assertEquals(DecoderError.NeedMoreStringUnderflow, collect(de, buf, res))

        // Keep what was not consumed and append the rest of the value.
        buf = buf.copyOfRange(de.consumed, buf.size) + value.copyOfRange(1, value.size)
        assertNull(collect(de, buf, res))

        assertEquals(1, res.size)
        assertEquals(0, de.tableSize)

        val h = assertIs<Header.Field>(res[0])
        assertEquals("foo", h.name.asStr())
        assertTrue(h.value.contentEquals("bar"))
    }

    // ---- Additions (not in the reference): limits, errors and the no-copy guarantees. ----

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun raw(s: String): ByteArray = byteArrayOf(s.length.toByte()) + s.encodeToByteArray()

    @Test
    fun standardNamesAndIndexedEntriesAreShared() {
        val de = Decoder()
        val res = mutableListOf<Header>()
        // Literal without indexing, literal (standard) name; then the static entry 2 twice.
        val buf = b(0x00) + raw("content-type") + raw("text/plain") + b(0x82, 0x82)
        assertNull(collect(de, buf, res))
        assertSame(HeaderName.CONTENT_TYPE, assertIs<Header.Field>(res[0]).name)
        assertSame(res[1], res[2])

        // A dynamic entry comes back as the inserted instance.
        res.clear()
        assertNull(collect(de, b(0x40) + raw("x-a") + raw("1") + b(0xbe, 0xbe), res))
        assertSame(res[0], res[1])
        assertSame(res[1], res[2])
        assertEquals(1, de.tableLen)
        assertEquals(32 + 3 + 1, de.tableSize)
    }

    @Test
    fun tableEvictsOldestEntries() {
        val de = Decoder(2 * (32 + 3 + 1))
        val res = mutableListOf<Header>()
        val buf = b(0x40) + raw("x-a") + raw("1") + b(0x40) + raw("x-b") + raw("2") + b(0x40) + raw("x-c") + raw("3")
        assertNull(collect(de, buf, res))
        assertEquals(2, de.tableLen)
        res.clear()
        assertNull(collect(de, b(0xbe, 0xbf), res))
        assertEquals("x-c", assertIs<Header.Field>(res[0]).name.asStr())
        assertEquals("x-b", assertIs<Header.Field>(res[1]).name.asStr())
        assertEquals(DecoderError.InvalidTableIndex, collect(de, b(0xc0), res))
    }

    @Test
    fun sizeUpdateRules() {
        val de = Decoder()
        // 4096 is allowed, 4097 is not.
        assertNull(collect(de, b(0x3f, 0xe1, 0x1f), mutableListOf()))
        assertEquals(DecoderError.InvalidMaxDynamicSize, collect(de, b(0x3f, 0xe2, 0x1f), mutableListOf()))
        // Only at the start of the block.
        assertEquals(DecoderError.InvalidMaxDynamicSize, collect(de, b(0x82, 0x20), mutableListOf()))
        // A larger size becomes valid once queued; the largest queued size is the limit.
        de.queueSizeUpdate(8192)
        de.queueSizeUpdate(100)
        assertNull(collect(de, b(0x3f, 0xe1, 0x3f), mutableListOf())) // 8192
        // Two updates in a row are fine; shrinking evicts.
        assertNull(collect(de, b(0x40) + raw("x-a") + raw("1"), mutableListOf()))
        assertEquals(1, de.tableLen)
        assertNull(collect(de, b(0x20, 0x3f, 0x01), mutableListOf())) // 0, then 32
        assertEquals(0, de.tableLen)
        assertEquals(0, de.tableSize)
    }

    @Test
    fun malformedRepresentations() {
        val de = Decoder()
        val out = mutableListOf<Header>()
        assertEquals(DecoderError.InvalidTableIndex, collect(de, b(0x80), out))
        assertEquals(DecoderError.InvalidTableIndex, collect(de, b(0xbe), out))
        assertEquals(DecoderError.IntegerOverflow, collect(de, b(0xff, 0x80, 0x80, 0x80, 0x80, 0x01), out))
        assertEquals(DecoderError.NeedMoreIntegerUnderflow, collect(de, b(0xff, 0x80), out))
        assertEquals(DecoderError.NeedMoreStringUnderflow, collect(de, b(0x00, 0x05, 0x61), out))
        assertEquals(DecoderError.InvalidHuffmanCode, collect(de, b(0x00, 0x81, 0xff, 0x00), out))
        assertEquals(DecoderError.InvalidPseudoheader, collect(de, b(0x00) + raw(":foo") + raw("x"), out))
        assertEquals(DecoderError.InvalidUtf8, collect(de, b(0x00) + raw("Upper") + raw("x"), out))
        assertEquals(DecoderError.InvalidUtf8, collect(de, b(0x00) + raw("x-a") + raw("bad\u0001"), out))
        assertEquals(DecoderError.InvalidUtf8, collect(de, b(0x04, 0x02, 0xc3, 0x28), out)) // :path, bad UTF-8
        assertEquals(DecoderError.InvalidUtf8, collect(de, b(0x00) + raw(":status") + raw("20"), out))
        assertEquals(DecoderError.InvalidStatusCode, collect(de, b(0x08) + raw("20"), out))
        assertEquals(DecoderError.NeedMoreUnexpectedEndOfStream, collect(de, b(0x00, 0x00, 0x00), out))
        assertEquals(0, out.size)
    }

    @Test
    fun sinkCanStopDecoding() {
        val de = Decoder()
        val buf = b(0x82, 0x84, 0x86)
        val seen = mutableListOf<Header>()
        assertNull(de.decode(buf, 0, buf.size) { seen.add(it); seen.size < 2 })
        assertEquals(2, seen.size)
        assertEquals(2, de.consumed)
    }
}
