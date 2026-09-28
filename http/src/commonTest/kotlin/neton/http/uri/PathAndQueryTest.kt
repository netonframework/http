package neton.http.uri

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

// Port of the 22 tests in http 1.5.0 `src/uri/path.rs`.
class PathAndQueryTest {

    private fun pq(s: String): PathAndQuery = PathAndQuery.parse(s)

    @Test
    fun equalToSelfOfSamePath() {
        val p1 = pq("/hello/world&foo=bar")
        val p2 = pq("/hello/world&foo=bar")
        assertEquals(p1, p2)
        assertEquals(p2, p1)
    }

    @Test
    fun notEqualToSelfOfDifferentPath() {
        val p1 = pq("/hello/world&foo=bar")
        val p2 = pq("/world&foo=bar")
        assertNotEquals(p1, p2)
        assertNotEquals(p2, p1)
    }

    @Test
    fun equatesWithAStr() {
        val pathAndQuery = pq("/hello/world&foo=bar")
        assertTrue(pathAndQuery eq "/hello/world&foo=bar")
        assertTrue("/hello/world&foo=bar" eq pathAndQuery)
    }

    @Test
    fun notEqualWithAStrOfADifferentPath() {
        val pathAndQuery = pq("/hello/world&foo=bar")
        assertFalse(pathAndQuery eq "/hello&foo=bar")
        assertFalse("/hello&foo=bar" eq pathAndQuery)
    }

    @Test
    fun equatesWithAString() {
        val pathAndQuery = pq("/hello/world&foo=bar")
        assertTrue(pathAndQuery eq "/hello/world&foo=bar")
        assertTrue("/hello/world&foo=bar" eq pathAndQuery)
    }

    @Test
    fun notEqualWithAStringOfADifferentPath() {
        val pathAndQuery = pq("/hello/world&foo=bar")
        assertFalse(pathAndQuery eq "/hello&foo=bar")
        assertFalse("/hello&foo=bar" eq pathAndQuery)
    }

    @Test
    fun comparesToSelf() {
        val p1 = pq("/a/world&foo=bar")
        val p2 = pq("/b/world&foo=bar")
        assertTrue(p1 < p2)
        assertTrue(p2 > p1)
    }

    @Test
    fun comparesWithAStr() {
        val pathAndQuery = pq("/b/world&foo=bar")
        assertTrue(pathAndQuery < "/c/world&foo=bar")
        assertTrue("/c/world&foo=bar" > pathAndQuery)
        assertTrue(pathAndQuery > "/a/world&foo=bar")
        assertTrue("/a/world&foo=bar" < pathAndQuery)
    }

    @Test
    fun comparesWithAString() {
        val pathAndQuery = pq("/b/world&foo=bar")
        assertTrue(pathAndQuery < "/c/world&foo=bar")
        assertTrue("/c/world&foo=bar" > pathAndQuery)
        assertTrue(pathAndQuery > "/a/world&foo=bar")
        assertTrue("/a/world&foo=bar" < pathAndQuery)
    }

    @Test
    fun ignoresValidPercentEncodings() {
        assertEquals("/a%20b", pq("/a%20b?r=1").path)
        assertEquals("qr=%31", pq("/a/b?qr=%31").query!!)
    }

    @Test
    fun ignoresInvalidPercentEncodings() {
        assertEquals("/a%%b", pq("/a%%b?r=1").path)
        assertEquals("/aaa%", pq("/aaa%").path)
        assertEquals("/aaa%", pq("/aaa%?r=1").path)
        assertEquals("/aa%2", pq("/aa%2").path)
        assertEquals("/aa%2", pq("/aa%2?r=1").path)
        assertEquals("qr=%3", pq("/a/b?qr=%3").query!!)
    }

    @Test
    fun allowUtf8InPath() {
        assertEquals("/🍕", pq("/🍕").path)
    }

    @Test
    fun allowUtf8InQuery() {
        assertEquals("pizza=🍕", pq("/test?pizza=🍕").query)
    }

    @Test
    fun rejectsInvalidUtf8InPath() {
        assertFailsWith<InvalidUri> { PathAndQuery.fromBytes(byteArrayOf('/'.code.toByte(), 0xFF.toByte())) }
    }

    @Test
    fun rejectsInvalidUtf8InQuery() {
        assertFailsWith<InvalidUri> {
            PathAndQuery.fromBytes(byteArrayOf('/'.code.toByte(), 'a'.code.toByte(), '?'.code.toByte(), 0xFF.toByte()))
        }
    }

    @Test
    fun rejectsEmptyString() {
        assertFailsWith<InvalidUri> { PathAndQuery.parse("") }
    }

    @Test
    fun requiresStartingWithSlash() {
        assertFailsWith<InvalidUri> { PathAndQuery.parse("sneaky") }
    }

    @Test
    fun rejectsDelInPath() {
        assertFailsWith<InvalidUri> { PathAndQuery.fromBytes(byteArrayOf('/'.code.toByte(), 0x7F)) }
    }

    @Test
    fun rejectsDelInQuery() {
        assertFailsWith<InvalidUri> {
            PathAndQuery.fromBytes(byteArrayOf('/'.code.toByte(), 'a'.code.toByte(), '?'.code.toByte(), 0x7F))
        }
    }

    @Test
    fun rejectsTooLongPathAndQuery() {
        val path = "/" + "a".repeat(MAX_LEN) + "?query"
        val err = assertFailsWith<InvalidUri> { PathAndQuery.parse(path) }
        assertEquals(InvalidUri.ErrorKind.TooLong, err.kind)
    }

    @Test
    fun acceptsMaxLengthPathAndQuery() {
        val path = "/" + "a".repeat(MAX_LEN - 2) + "?"
        val pathAndQuery = PathAndQuery.parse(path)
        assertEquals(MAX_LEN, pathAndQuery.asStr().length)
        assertEquals("", pathAndQuery.query)
    }

    @Test
    fun jsonIsFine() {
        assertEquals("/{\"bread\":\"baguette\"}", pq("/{\"bread\":\"baguette\"}").path)
    }
}
