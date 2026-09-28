package neton.http.h3.qpack

import neton.http.header.HeaderName
import neton.http.header.HeaderValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

// Tests of `src/qpack/static_.rs` (all 6) and `src/qpack/field.rs` (both).
class StaticTableTest {
    @Test
    fun test_static_table_index_is_0_based() {
        assertEquals(HeaderField(":authority", ""), StaticTable.get(0))
    }

    @Test
    fun test_static_table_is_full() {
        assertEquals(99, StaticTable.SIZE)
        assertEquals(HeaderField("x-frame-options", "sameorigin"), StaticTable.get(98))
    }

    @Test
    fun test_static_table_can_get_field() {
        assertEquals(HeaderField("x-frame-options", "sameorigin"), StaticTable.get(98))
    }

    @Test
    fun invalid_index() {
        assertEquals(99L, assertFailsWith<StaticTableException> { StaticTable.get(99) }.index)
        assertNull(StaticTable.getOrNull(-1))
    }

    @Test
    fun find_by_name() {
        assertEquals(10, StaticTable.findName("last-modified".encodeToByteArray()))
        assertNull(StaticTable.findName("does-not-exist".encodeToByteArray()))
    }

    @Test
    fun find() {
        assertEquals(17, StaticTable.find(HeaderField(":method", "GET")))
        assertNull(StaticTable.find(HeaderField("foo", "bar")))
    }

    // ---- field.rs ----

    @Test
    fun test_field_size_is_offset_by_32() {
        assertEquals(4L + 5 + 32, HeaderField("Name", "Value").memSize())
    }

    @Test
    fun with_value() {
        assertEquals(HeaderField("Name", "New value"), HeaderField("Name", "Value").withValue("New value"))
    }

    // ---- Beyond the reference's tests ----

    @Test
    fun everyEntryIsFoundAtItsIndexAndByTypedName() {
        for (i in 0 until StaticTable.SIZE) {
            val f = StaticTable.get(i.toLong())
            assertEquals(i, StaticTable.find(f))
            val first = (0..i).first { StaticTable.get(it.toLong()).name.contentEquals(f.name) }
            assertEquals(first, StaticTable.findName(f.name))
            val name = f.name.decodeToString()
            if (name.startsWith(":")) {
                assertEquals(i, StaticTable.findPseudo(name, f.value.decodeToString()))
                assertEquals(first, StaticTable.findPseudoName(name))
            } else {
                val hn = HeaderName.fromStr(name)
                assertEquals(first, StaticTable.findName(hn))
                assertEquals(i, StaticTable.find(hn, HeaderValue.fromBytes(f.value)))
            }
        }
        assertEquals(-1, StaticTable.find(HeaderName.fromStr("age"), HeaderValue.fromStr("1")))
        assertEquals(-1, StaticTable.findName(HeaderName.fromStr("x-custom")))
    }
}
