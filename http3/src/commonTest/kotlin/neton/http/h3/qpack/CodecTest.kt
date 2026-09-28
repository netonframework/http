package neton.http.h3.qpack

import neton.http.h3.Code
import neton.io.bytes.Buffer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val TABLE_SIZE = 4096L

private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

private fun decodeError(block: Buffer, maxSize: Long = Long.MAX_VALUE, maxCount: Int = Int.MAX_VALUE): DecoderError =
    assertFailsWith<DecoderException> { Decoder.decodeStateless(block, maxSize, maxCount) }.error

/** Collects what a sink receives. */
private class Collect : FieldSink {
    val fields = ArrayList<HeaderField>()
    override fun onField(name: ByteArray, nameOff: Int, nameLen: Int, value: ByteArray, valueOff: Int, valueLen: Int) {
        fields.add(HeaderField(name.copyOfRange(nameOff, nameOff + nameLen), value.copyOfRange(valueOff, valueOff + valueLen)))
    }
}

// The static-table and literal tests of `src/qpack/encoder.rs`, `src/qpack/decoder.rs` and `src/qpack/tests.rs`;
// the dynamic-table ones are not applicable (see SPEC §11), and the encoder / decoder stream ones are in
// QpackStreamsTest. Then the ⚖️ behaviours: dynamic references and the incremental limits.
class CodecTest {
    // ---- encoder.rs ----

    @Test
    fun encode_static() {
        val block = Buffer()
        Encoder().field(":method".encodeToByteArray(), "GET".encodeToByteArray(), block)
        assertEquals(Indexed.Static(17), Indexed.decode(block))
        // Nothing is ever written to the encoder stream: the stateless encoder has none.
        assertTrue(block.isEmpty)
    }

    @Test
    fun encode_literal() {
        // The reference sets the table size to 0: the stateless encoder's case.
        val block = Buffer()
        Encoder().field("foo".encodeToByteArray(), "bar".encodeToByteArray(), block)
        assertEquals(Literal("foo", "bar"), Literal.decode(block))
    }

    @Test
    fun encodeStaticNameRefWithoutDynamicTable() {
        // The reference's `encode_static_nameref` inserts into its dynamic table; without one, the same field is a
        // literal with a static name reference.
        val block = Buffer()
        Encoder().field("location".encodeToByteArray(), "/bar".encodeToByteArray(), block)
        assertEquals(LiteralWithNameRef.newStatic(12, "/bar"), LiteralWithNameRef.decode(block))
    }

    // ---- decoder.rs ----

    @Test
    fun test_header_too_long() {
        val block = Buffer()
        Encoder().encodeStateless(block, listOf(HeaderField("trailer", "value"), HeaderField("trailer2", "value2")))
        assertEquals(DecoderError.HeaderTooLong(44), decodeError(block, maxSize = 2))
    }

    @Test
    fun largest_ref_too_big() {
        // ⚖️ The reference's stateful decoder reports MissingRefs(8) (the Required Insert Count); without a dynamic
        // table the Required Insert Count cannot be decoded, and any non-zero encoded count is refused.
        val block = Buffer()
        HeaderPrefix.new(8, 8, 10, TABLE_SIZE).encode(block)
        val e = decodeError(block)
        assertEquals(DecoderError.MissingRefs(9), e)
        assertEquals(Code.QPACK_DECOMPRESSION_FAILED, e.code)
    }

    @Test
    fun decode_without_name_ref_header_field() {
        val block = Buffer()
        HeaderPrefix.new(0, 0, 0, TABLE_SIZE).encode(block)
        Literal("foo", "bar").encode(block)
        assertEquals(listOf(HeaderField("foo", "bar")), Decoder.decodeStateless(block, Long.MAX_VALUE).fields)
    }

    // ---- tests.rs ----

    private fun roundTrip(header: List<HeaderField>) {
        val block = Buffer()
        val size = Encoder().encodeStateless(block, header)
        val decoded = Decoder.decodeStateless(block, Long.MAX_VALUE)
        assertEquals(header, decoded.fields)
        assertEquals(size, decoded.memSize)
    }

    @Test
    fun codec_basic_get() {
        // `Encoder::default()` has a dynamic table of size 0: stateless encoding.
        roundTrip(listOf(HeaderField(":method", "GET"), HeaderField(":path", "/"), HeaderField("foo", "bar")))
    }

    @Test
    fun codec_table_size_0() {
        roundTrip(listOf(HeaderField(":method", "GET"), HeaderField(":path", "/"), HeaderField("foo", "bar")))
    }

    // ---- Beyond the reference's tests ----

    @Test
    fun rfc9204AppendixB1LiteralWithNameReference() {
        // RFC 9204 B.1: ":path: /index.html" as a literal with a static name reference, not Huffman-encoded.
        val block = Buffer().also { it.writeBytes(hex("0000 510b 2f69 6e64 6578 2e68 746d 6c")) }
        assertEquals(listOf(HeaderField(":path", "/index.html")), Decoder.decodeStateless(block, Long.MAX_VALUE).fields)
    }

    @Test
    fun dynamicReferencesAreDecompressionFailures() {
        val cases = listOf<(Buffer) -> Unit>(
            { Indexed.Dynamic(0).encode(it) },
            { IndexedWithPostBase(0).encode(it) },
            { LiteralWithNameRef.newDynamic(0, "x").encode(it) },
            { LiteralWithPostBaseNameRef(0, "x").encode(it) },
        )
        for (write in cases) {
            val block = Buffer()
            HeaderPrefix.ZERO.encode(block)
            Indexed.Static(17).encode(block)
            write(block)
            val e = decodeError(block)
            assertEquals(DecoderError.MissingRefs(0), e)
            assertEquals(Code.QPACK_DECOMPRESSION_FAILED, e.code)
            assertTrue(!e.isLimit)
        }
    }

    @Test
    fun nonZeroRequiredInsertCountIsRefusedEvenWithoutDynamicLines() {
        // ⚖️ RFC 9204 §4.5.1.1 with a maximum table capacity of 0; the reference's stateless decoder ignores it.
        val block = Buffer()
        PrefixInt.encode(8, 0, 1, block)
        PrefixInt.encode(7, 0, 0, block)
        Indexed.Static(17).encode(block)
        assertEquals(DecoderError.MissingRefs(1), decodeError(block))
    }

    @Test
    fun anyDeltaBaseIsAcceptedWithARequiredInsertCountOfZero() {
        // RFC 9204 §4.5.1.2: a section without dynamic references may use any Base.
        val block = Buffer()
        PrefixInt.encode(8, 0, 0, block)
        PrefixInt.encode(7, 1, 1234, block)
        Indexed.Static(17).encode(block)
        assertEquals(listOf(HeaderField(":method", "GET")), Decoder.decodeStateless(block, Long.MAX_VALUE).fields)
    }

    @Test
    fun malformedSections() {
        // Invalid static index.
        val a = Buffer().also { HeaderPrefix.ZERO.encode(it); Indexed.Static(99).encode(it) }
        assertEquals(DecoderError.InvalidStaticIndex(99), decodeError(a))
        val b = Buffer().also { HeaderPrefix.ZERO.encode(it); LiteralWithNameRef.newStatic(1000, "v").encode(it) }
        assertEquals(DecoderError.InvalidStaticIndex(1000), decodeError(b))
        // Truncated prefix, representation and string.
        assertEquals(DecoderError.UnexpectedEnd, decodeError(Buffer()))
        assertEquals(DecoderError.UnexpectedEnd, decodeError(Buffer().also { it.writeBytes(hex("00")) }))
        val c = Buffer().also { HeaderPrefix.ZERO.encode(it); Literal("foo", "bar").encode(it) }
        val bytes = c.readAll()
        assertEquals(DecoderError.UnexpectedEnd, decodeError(Buffer().also { it.writeBytes(bytes, 0, bytes.size - 1) }))
        // Invalid Huffman string.
        val d = Buffer().also { it.writeBytes(hex("0000 5184 ffffffff")) }
        assertEquals(DecoderError.InvalidString(PrefixStringError.HuffmanDecoding), decodeError(d))
        // An integer too long.
        val e = Buffer().also { it.writeBytes(hex("0000 ff ffffffffffffffffff01")) }
        assertEquals(DecoderError.InvalidInteger(PrefixIntError.Overflow), decodeError(e))
    }

    @Test
    fun sizeLimitStopsAtTheFirstLinePastIt() {
        val fields = listOf(HeaderField("a", "1"), HeaderField("b", "2"), HeaderField("c", "3"))
        val block = Buffer()
        Encoder().encodeStateless(block, fields)
        // An invalid line after the third: decoding must stop before reaching it.
        Indexed.Static(99).encode(block)
        val bytes = block.readAll()
        // Exactly two lines fit (34 bytes each).
        val sink = Collect()
        val d = Decoder(maxFieldSectionSize = 68)
        val e = assertFailsWith<DecoderException> { d.decode(bytes, 0, bytes.size, sink) }
        assertEquals(DecoderError.HeaderTooLong(102), e.error)
        assertTrue(e.error.isLimit)
        assertEquals(fields.subList(0, 2), sink.fields)
        // At the limit is accepted.
        val ok = Buffer().also { Encoder().encodeStateless(it, fields) }.readAll()
        val all = Collect()
        Decoder(maxFieldSectionSize = 102).decode(ok, 0, ok.size, all)
        assertEquals(fields, all.fields)
    }

    @Test
    fun countLimitIsCheckedBeforeDecodingTheLine() {
        val fields = List(100) { HeaderField("x-$it", "v") }
        val block = Buffer()
        Encoder().encodeStateless(block, fields)
        val okBytes = block.peekAll()
        val sink = Collect()
        val d = Decoder()
        d.decode(okBytes, 0, okBytes.size, sink)
        assertEquals(100, d.fieldCount)
        // A 101st line that would fail to decode: the count limit is reported, before it is decoded.
        block.writeBytes(hex("5184ffffffff"))
        val bytes = block.readAll()
        val over = Collect()
        val e = assertFailsWith<DecoderException> { Decoder().decode(bytes, 0, bytes.size, over) }
        assertEquals(DecoderError.TooManyFields(101), e.error)
        assertEquals(100, over.fields.size)
        // A count limit of 0 accepts only an empty section.
        val empty = Buffer().also { HeaderPrefix.ZERO.encode(it) }.readAll()
        Decoder(maxFieldCount = 0).decode(empty, 0, empty.size, Collect())
    }

    @Test
    fun seededRoundTrips() {
        val random = Random(9204)
        val names = listOf(":authority", ":path", "content-type", "set-cookie", "x-custom", "accept", "age", "")
        repeat(500) {
            val fields = List(random.nextInt(0, 20)) {
                val name = if (random.nextInt(4) == 0) random.nextBytes(random.nextInt(0, 40)) else
                    names[random.nextInt(names.size)].encodeToByteArray()
                val value = when (random.nextInt(3)) {
                    0 -> StaticTable.get(random.nextLong(0, 99)).value
                    1 -> random.nextBytes(random.nextInt(0, 300))
                    else -> "text/html; charset=utf-8".encodeToByteArray()
                }
                HeaderField(name, value)
            }
            roundTrip(fields)
        }
    }

    @Test
    fun decodesFromTheMiddleOfAnArray() {
        val block = Buffer().also { Encoder().encodeStateless(it, listOf(HeaderField("foo", "bar"), HeaderField(":status", "200"))) }
        val bytes = block.readAll()
        val big = ByteArray(bytes.size + 20) { 0x7f }
        bytes.copyInto(big, 10)
        val sink = Collect()
        Decoder().decode(big, 10, bytes.size, sink)
        assertEquals(listOf(HeaderField("foo", "bar"), HeaderField(":status", "200")), sink.fields)
        assertContentEquals(hex("0000"), bytes.copyOf(2))
    }
}
