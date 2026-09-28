package neton.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtensionsTest {
    private data class MyType(val value: Int)

    private class Counter(var value: Int = 0)

    @Test fun testExtensions() {
        val extensions = Extensions()
        assertEquals("{}", extensions.toString())

        extensions.insert(5)
        extensions.insert(MyType(10))

        assertEquals(5, extensions.get<Int>())
        assertEquals(5, extensions.getMut<Int>())

        val dbg = extensions.toString()
        // Map order is not deterministic.
        val my = MyType::class.qualifiedName ?: MyType::class.simpleName
        assertTrue(dbg == "{$my, kotlin.Int}" || dbg == "{kotlin.Int, $my}", dbg)

        val ext2 = extensions.copy()

        assertEquals(5, extensions.remove<Int>())
        assertNull(extensions.get<Int>())

        // The copy still has it.
        assertEquals(5, ext2.get<Int>())
        assertEquals(MyType(10), ext2.get<MyType>())

        assertNull(extensions.get<Boolean>())
        assertEquals(MyType(10), extensions.get<MyType>())
    }

    // extensions.rs doc examples

    @Test fun insertDocExample() {
        val ext = Extensions()
        assertNull(ext.insert(5))
        assertNull(ext.insert(4.toByte()))
        assertEquals(5, ext.insert(9))
    }

    @Test fun getDocExample() {
        val ext = Extensions()
        assertNull(ext.get<Int>())
        ext.insert(5)
        assertEquals(5, ext.get<Int>())
    }

    @Test fun getMutDocExample() {
        val ext = Extensions()
        ext.insert(StringBuilder("Hello"))
        ext.getMut<StringBuilder>()!!.append(" World")
        assertEquals("Hello World", ext.get<StringBuilder>().toString())
    }

    @Test fun getOrInsertDocExample() {
        val ext = Extensions()
        ext.getOrInsert(Counter(1)).value += 2
        assertEquals(3, ext.get<Counter>()!!.value)
    }

    @Test fun getOrInsertWithDocExample() {
        val ext = Extensions()
        ext.getOrInsertWith { Counter(1) }.value += 2
        assertEquals(3, ext.get<Counter>()!!.value)
    }

    // `get_or_insert_default` has no Kotlin counterpart; its example is expressed with getOrInsertWith.
    @Test fun getOrInsertDefaultDocExample() {
        val ext = Extensions()
        ext.getOrInsertWith { Counter() }.value += 2
        assertEquals(2, ext.get<Counter>()!!.value)
    }

    @Test fun removeDocExample() {
        val ext = Extensions()
        ext.insert(5)
        assertEquals(5, ext.remove<Int>())
        assertNull(ext.get<Int>())
    }

    @Test fun clearDocExample() {
        val ext = Extensions()
        ext.insert(5)
        ext.clear()
        assertNull(ext.get<Int>())
    }

    @Test fun isEmptyDocExample() {
        val ext = Extensions()
        assertTrue(ext.isEmpty())
        ext.insert(5)
        assertFalse(ext.isEmpty())
    }

    @Test fun lenDocExample() {
        val ext = Extensions()
        assertEquals(0, ext.len())
        ext.insert(5)
        assertEquals(1, ext.len())
    }

    @Test fun extendDocExample() {
        val extA = Extensions()
        extA.insert(8.toByte())
        extA.insert(16.toShort())

        val extB = Extensions()
        extB.insert(4.toByte())
        extB.insert("hello")

        extA.extend(extB)
        assertEquals(3, extA.len())
        assertEquals(4.toByte(), extA.get<Byte>())
        assertEquals(16.toShort(), extA.get<Short>())
        assertEquals("hello", extA.get<String>())
    }

    // Beyond the reference tests: SPEC §2 behaviour.

    @Test fun unusedExtensionsNeedNoMap() {
        val ext = Extensions()
        ext.clear()
        assertTrue(ext.isEmpty())
        assertEquals(0, ext.len())
        assertNull(ext.remove<Int>())
        assertNull(ext.get<Int>())
        ext.extend(Extensions())
        assertTrue(ext.isEmpty())
        assertEquals("{}", ext.copy().toString())
    }

    @Test fun extendIntoEmptyTakesOverAndEmptiesSource() {
        val a = Extensions()
        val b = Extensions()
        b.insert("x")
        a.extend(b)
        assertEquals("x", a.get<String>())
        assertTrue(b.isEmpty())
    }

    @Test fun keyedByStaticType() {
        val ext = Extensions()
        ext.insert(CharSequence::class, "text")
        assertNull(ext.get<String>())
        assertEquals("text", ext.get<CharSequence>())
        assertEquals("text", ext.getOrInsert<CharSequence>("other"))
        assertEquals(1, ext.len())
    }

    @Test fun copyIsIndependentMap() {
        val a = Extensions()
        a.insert(1)
        val b = a.copy()
        b.insert(2)
        b.insert("s")
        assertEquals(1, a.get<Int>())
        assertEquals(1, a.len())
        assertEquals(2, b.get<Int>())
    }
}
