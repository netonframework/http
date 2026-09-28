package neton.http.uri

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Port of the 2 tests in http 1.5.0 `src/uri/scheme.rs`.
class SchemeTest {

    private fun scheme(s: String): Scheme = Scheme.parse(s)

    @Test
    fun schemeEqToStr() {
        assertTrue(scheme("http") eq "http")
        assertTrue(scheme("https") eq "https")
        assertTrue(scheme("ftp") eq "ftp")
        assertTrue(scheme("my+funky+scheme") eq "my+funky+scheme")
    }

    @Test
    fun invalidSchemeIsError() {
        assertFailsWith<InvalidUri>("Unexpectedly valid Scheme") { Scheme.parse("my_funky_scheme") }
        // Invalid UTF-8
        assertFailsWith<InvalidUri>("Unexpectedly valid Scheme") { Scheme.fromBytes(byteArrayOf(0xC0.toByte())) }
    }
}

// Port of the 4 tests in http 1.5.0 `src/uri/port.rs`.
class PortTest {

    @Test
    fun partialeqPort() {
        val portA = Port.fromStr("8080")!!
        val portB = Port.fromStr("8080")!!
        assertEquals(portA, portB)
    }

    @Test
    fun partialeqPortDifferentReprs() {
        // The reference compares a `Port<&str>` with a `Port<String>`; here both hold a String.
        val portA = Port(8081, "8081")
        val portB = Port(8081, buildString { append("8081") })
        assertEquals(portA, portB)
        assertEquals(portB, portA)
    }

    @Test
    fun partialeqU16() {
        val port = Port.fromStr("8080")!!
        // test equals in both directions
        assertTrue(port eq 8080)
        assertTrue(8080 eq port)
    }

    @Test
    fun u16FromPort() {
        val port = Port.fromStr("8080")!!
        assertEquals(8080, port.asU16())
    }
}

// Port of the 8 tests in http 1.5.0 `src/uri/builder.rs`.
class UriBuilderTest {

    @Test
    fun buildFromStr() {
        val uri = UriBuilder()
            .scheme(Scheme.HTTP)
            .authority("hyper.rs")
            .pathAndQuery("/foo?a=1")
            .build()
        assertEquals("http", uri.schemeStr)
        assertEquals("hyper.rs", uri.authority!!.host)
        assertEquals("/foo", uri.path)
        assertEquals("a=1", uri.query)
    }

    @Test
    fun buildFromString() {
        for (i in 1 until 10) {
            val uri = UriBuilder().pathAndQuery("/foo?a=$i").build()
            val expectedQuery = "a=$i"
            assertEquals("/foo", uri.path)
            assertEquals(expectedQuery, uri.query)
        }
    }

    @Test
    fun buildFromStringRef() {
        for (i in 1 until 10) {
            val pAQ = "/foo?a=$i"
            val uri = UriBuilder().pathAndQuery(pAQ).build()
            val expectedQuery = "a=$i"
            assertEquals("/foo", uri.path)
            assertEquals(expectedQuery, uri.query)
        }
    }

    @Test
    fun buildFromEmptyPathAndQuery() {
        val uri = UriBuilder()
            .scheme(Scheme.HTTP)
            .authority("localhost:8080")
            .pathAndQuery("")
            .build()
        assertTrue(uri eq "http://localhost:8080")
        assertEquals("/", uri.path)
    }

    @Test
    fun emptyPathAndQueryRemainsStrict() {
        assertNull(PathAndQuery.tryParse(""))
    }

    @Test
    fun authorityFormPathAndQueryRemainsStrict() {
        assertFailsWith<InvalidUri> { UriBuilder().pathAndQuery("localhost:8080").build() }
    }

    @Test
    fun buildFromUri() {
        val originalUri = Uri.default()
        val uri = UriBuilder.from(originalUri).build()
        assertEquals(originalUri, uri)
    }

    @Test
    fun buildStarForHttp2() {
        val uri = UriBuilder()
            .scheme("https")
            .authority("example.com")
            .pathAndQuery("*")
            .build()
        assertEquals(Scheme.HTTPS, uri.scheme)
        assertEquals("example.com", uri.host)
        assertEquals("*", uri.path)
    }
}
