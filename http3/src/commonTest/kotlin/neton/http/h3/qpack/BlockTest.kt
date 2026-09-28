package neton.http.h3.qpack

import neton.io.bytes.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val TABLE_SIZE = 4096L

// Tests of `src/qpack/block.rs` (all 9: the wire formats of the representations, dynamic ones included, since the
// stateless decoder must recognise a dynamic reference to reject it).
class BlockTest {
    @Test
    fun indexed_static() {
        val field = Indexed.Static(42)
        val b = Buffer().also { field.encode(it) }
        assertEquals(field, Indexed.decode(b))
    }

    @Test
    fun indexed_dynamic() {
        val field = Indexed.Dynamic(42)
        val b = Buffer().also { field.encode(it) }
        assertEquals(field, Indexed.decode(b))
    }

    @Test
    fun indexed_with_postbase() {
        val field = IndexedWithPostBase(42)
        val b = Buffer().also { field.encode(it) }
        assertEquals(field, IndexedWithPostBase.decode(b))
    }

    @Test
    fun literal_with_name_ref() {
        val field = LiteralWithNameRef.newStatic(42, "foo")
        val b = Buffer().also { field.encode(it) }
        assertEquals(field, LiteralWithNameRef.decode(b))
    }

    @Test
    fun literal_with_post_base_name_ref() {
        val field = LiteralWithPostBaseNameRef(42, "foo")
        val b = Buffer().also { field.encode(it) }
        assertEquals(field, LiteralWithPostBaseNameRef.decode(b))
    }

    @Test
    fun literal() {
        val field = Literal("foo", "bar")
        val b = Buffer().also { field.encode(it) }
        assertEquals(field, Literal.decode(b))
    }

    @Test
    fun header_prefix() {
        val prefix = HeaderPrefix.new(10, 5, 12, TABLE_SIZE)
        val b = Buffer().also { prefix.encode(it) }
        val decoded = HeaderPrefix.decode(b)
        assertEquals(prefix, decoded)
        assertEquals(10L to 5L, decoded.get(13, 3332))
    }

    @Test
    fun header_prefix_table_size_0() {
        HeaderPrefix.new(10, 5, 12, 0).get(1, 0)
    }

    @Test
    fun base_index_too_small() {
        val b = Buffer()
        val encodedLargestRef = (2 % (2 * TABLE_SIZE / 32)) + 1
        PrefixInt.encode(8, 0, encodedLargestRef, b)
        PrefixInt.encode(7, 1, 2, b) // base index negative = 0
        val e = assertFailsWith<ParseException> { HeaderPrefix.decode(b).get(2, TABLE_SIZE) }
        assertEquals(ParseError.InvalidBase(-1), e.error)
    }

    // ---- Beyond the reference's tests ----

    @Test
    fun representationKinds() {
        assertEquals(HeaderBlockField.Indexed, HeaderBlockField.decode(0xc0))
        assertEquals(HeaderBlockField.Indexed, HeaderBlockField.decode(0x80))
        assertEquals(HeaderBlockField.IndexedWithPostBase, HeaderBlockField.decode(0x10))
        assertEquals(HeaderBlockField.LiteralWithNameRef, HeaderBlockField.decode(0x50))
        assertEquals(HeaderBlockField.LiteralWithPostBaseNameRef, HeaderBlockField.decode(0x08))
        assertEquals(HeaderBlockField.Literal, HeaderBlockField.decode(0x28))
        for (b in 0..255) assertTrue(HeaderBlockField.decode(b) != HeaderBlockField.Unknown)
    }

    @Test
    fun wrongPrefixesAreErrors() {
        val b = Buffer().also { IndexedWithPostBase(1).encode(it) }
        assertEquals(ParseError.InvalidPrefix(0), assertFailsWith<ParseException> { Indexed.decode(b) }.error)
        val l = Buffer().also { Indexed.Static(1).encode(it) }
        assertEquals(ParseError.InvalidPrefix(0xc1), assertFailsWith<ParseException> { Literal.decode(l) }.error)
        assertEquals(ParseError.Integer(PrefixIntError.UnexpectedEnd), assertFailsWith<ParseException> { Literal.decode(Buffer()) }.error)
    }
}
