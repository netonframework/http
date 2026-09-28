package neton.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MethodTest {
    // method.rs module and `Method` type doc examples (identical).
    @Test fun methodDocExample() {
        assertEquals(Method.GET, Method.fromBytes("GET".encodeToByteArray()))
        assertTrue(Method.GET.isIdempotent())
        assertEquals("POST", Method.POST.asStr())
    }

    @Test fun testMethodEq() {
        assertEquals(Method.GET, Method.GET)
        assertEquals("GET", Method.GET.asStr())
        assertEquals(Method.GET.asStr(), "GET")
        assertEquals("GET", Method.GET.toString())
        assertNotEquals(Method.GET, Method.POST)
    }

    @Test fun testInvalidMethod() {
        assertFailsWith<InvalidMethod> { Method.fromStr("") }
        assertFailsWith<InvalidMethod> { Method.fromBytes(ByteArray(0)) }
        assertFailsWith<InvalidMethod> { Method.fromBytes(byteArrayOf(0xC0.toByte())) } // invalid UTF-8
        assertFailsWith<InvalidMethod> { Method.fromBytes(byteArrayOf(0x10)) } // not a method character
    }

    @Test fun testIsIdempotent() {
        assertTrue(Method.OPTIONS.isIdempotent())
        assertTrue(Method.GET.isIdempotent())
        assertTrue(Method.PUT.isIdempotent())
        assertTrue(Method.DELETE.isIdempotent())
        assertTrue(Method.HEAD.isIdempotent())
        assertTrue(Method.TRACE.isIdempotent())
        assertTrue(Method.QUERY.isIdempotent())

        assertFalse(Method.POST.isIdempotent())
        assertFalse(Method.CONNECT.isIdempotent())
        assertFalse(Method.PATCH.isIdempotent())
    }

    @Test fun testExtensionMethod() {
        assertEquals("WOW", Method.fromStr("WOW").asStr())
        assertEquals("wOw!!", Method.fromStr("wOw!!").asStr())

        val longMethod = "This_is_a_very_long_method.It_is_valid_but_unlikely."
        assertEquals(longMethod, Method.fromStr(longMethod).asStr())

        // The reference checks its inline (<= 15 bytes) / allocated storage boundary; storage is uniform here,
        // so check both sides of that boundary parse and compare by name.
        val longestInline = ByteArray(15) { 'A'.code.toByte() }
        assertEquals(Method.fromStr("A".repeat(15)), Method.fromBytes(longestInline))
        val shortestAllocated = ByteArray(16) { 'A'.code.toByte() }
        assertEquals(Method.fromStr("A".repeat(16)), Method.fromBytes(shortestAllocated))
    }

    @Test fun testExtensionMethodChars() {
        val validMethodChars = "!#$%&'*+-.^_`|~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        for (c in validMethodChars) {
            val s = c.toString()
            assertEquals(s, Method.fromStr(s).asStr(), "testing $s is a valid method character")
            assertEquals(s, Method.fromBytes(s.encodeToByteArray()).asStr())
        }
    }

    // Beyond the reference tests: SPEC §2 behaviour.

    @Test fun everyNonTcharByteIsRejected() {
        val valid = "!#$%&'*+-.^_`|~0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
        for (b in 0..255) {
            val bytes = byteArrayOf('X'.code.toByte(), b.toByte())
            val parsed = Method.tryFromBytes(bytes)
            if (b < 128 && b.toChar() in valid) assertEquals("X" + b.toChar(), parsed?.asStr()) else assertNull(parsed, "byte $b")
        }
        assertNull(Method.tryFromStr("G T"))
        assertNull(Method.tryFromStr("GÉT"))
        assertNull(Method.tryFromStr(""))
    }

    @Test fun isSafe() {
        for (m in listOf(Method.GET, Method.HEAD, Method.OPTIONS, Method.TRACE, Method.QUERY)) assertTrue(m.isSafe(), m.asStr())
        for (m in listOf(Method.POST, Method.PUT, Method.DELETE, Method.CONNECT, Method.PATCH)) assertFalse(m.isSafe(), m.asStr())
        assertFalse(Method.fromStr("WOW").isSafe())
        assertFalse(Method.fromStr("WOW").isIdempotent())
    }

    @Test fun standardMethodsParseToSharedConstants() {
        val standard = listOf(
            Method.GET, Method.POST, Method.PUT, Method.DELETE, Method.HEAD,
            Method.OPTIONS, Method.CONNECT, Method.PATCH, Method.TRACE, Method.QUERY,
        )
        for (m in standard) {
            assertSame(m, Method.fromBytes(m.asStr().encodeToByteArray()))
            assertSame(m, Method.fromStr(m.asStr()))
        }
    }

    @Test fun fromBytesHonoursOffsetAndLength() {
        val line = "xxPOST /".encodeToByteArray()
        assertSame(Method.POST, Method.fromBytes(line, 2, 4))
        assertEquals("POS", Method.fromBytes(line, 2, 3).asStr())
        assertFailsWith<InvalidMethod> { Method.fromBytes(line, 2, 5) } // includes the space
        assertFailsWith<InvalidMethod> { Method.fromBytes(line, 2, 0) }
        assertFailsWith<IndexOutOfBoundsException> { Method.fromBytes(line, 6, 5) }
    }

    @Test fun methodsAreCaseSensitive() {
        val lower = Method.fromStr("get")
        assertNotEquals(Method.GET, lower)
        assertEquals("get", lower.asStr())
        assertFalse(lower.isSafe())
        assertEquals(Method.fromStr("PROPFIND"), Method.fromBytes("PROPFIND".encodeToByteArray()))
        assertEquals(Method.fromStr("PROPFIND").hashCode(), Method.fromBytes("PROPFIND".encodeToByteArray()).hashCode())
    }

    @Test fun defaultAndOrdering() {
        assertSame(Method.GET, Method.DEFAULT)
        assertTrue(Method.GET < Method.POST)
        assertTrue(Method.DELETE < Method.GET)
        assertEquals(0, Method.fromStr("WOW").compareTo(Method.fromStr("WOW")))
    }
}
