package neton.http.uri

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

// Port of http 1.5.0 `src/uri/tests.rs`: 8 tests plus the 29 `test_parse!` cases, one Kotlin test each.
class UriTest {

    @Test
    fun testCharTable() {
        for (i in URI_CHARS.indices) {
            val v = URI_CHARS[i].toInt() and 0xFF
            if (v != 0) assertEquals(i, v)
        }
    }

    // `part!`
    private fun scheme(s: String) = Scheme.parse(s)
    private fun authority(s: String) = Authority.parse(s)

    /** The `test_parse!` macro: [check] holds the per-case `method = value` assertions. */
    private fun testParse(orig: String, alt: List<String>, check: (Uri) -> Unit) {
        val uri = Uri.tryParse(orig) ?: error("parse error from \"$orig\": ${runCatching { Uri.parse(orig) }}")
        check(uri)
        assertTrue(uri eq orig, "partial eq to original str")
        // "clones are equal": Uri is immutable, so compare with an independent parse of the same text.
        val again = Uri.parse(orig)
        assertEquals(uri, again, "clones are equal")
        assertEquals(uri.hashCode(), again.hashCode())

        val newStr = uri.toString()
        val newUri = Uri.parse(newStr)
        assertTrue(newUri eq orig, "round trip still equals original str")

        for (a in alt) {
            val other = Uri.parse(a)
            assertTrue(uri eq a)
            assertEquals(uri, other)
            assertEquals(uri.hashCode(), other.hashCode())
        }
    }

    @Test
    fun testUriParsePathAndQuery() = testParse("/some/path/here?and=then&hello#and-bye", listOf()) {
        assertNull(it.scheme)
        assertNull(it.authority)
        assertEquals("/some/path/here", it.path)
        assertEquals("and=then&hello", it.query)
        assertNull(it.host)
    }

    @Test
    fun testUriParseAbsoluteForm() = testParse("http://127.0.0.1:61761/chunks", listOf()) {
        assertEquals(scheme("http"), it.scheme)
        assertEquals(authority("127.0.0.1:61761"), it.authority)
        assertEquals("/chunks", it.path)
        assertNull(it.query)
        assertEquals("127.0.0.1", it.host)
        assertEquals(Port.fromStr("61761"), it.port)
    }

    @Test
    fun testUriParseAbsoluteFormWithoutPath() = testParse("https://127.0.0.1:61761", listOf("https://127.0.0.1:61761/")) {
        assertEquals(scheme("https"), it.scheme)
        assertEquals(authority("127.0.0.1:61761"), it.authority)
        assertEquals("/", it.path)
        assertNull(it.query)
        assertEquals("127.0.0.1", it.host)
        assertEquals(Port.fromStr("61761"), it.port)
    }

    @Test
    fun testUriParseAsteriskForm() = testParse("*", listOf()) {
        assertNull(it.scheme)
        assertNull(it.authority)
        assertEquals("*", it.path)
        assertNull(it.query)
        assertNull(it.host)
    }

    @Test
    fun testUriParseAuthorityNoPort() = testParse("localhost", listOf("LOCALHOST", "LocaLHOSt")) {
        assertNull(it.scheme)
        assertEquals(authority("localhost"), it.authority)
        assertEquals("", it.path)
        assertNull(it.query)
        assertNull(it.port)
        assertEquals("localhost", it.host)
    }

    @Test
    fun testUriAuthorityOnlyOneCharacterIssue197() = testParse("S", listOf()) {
        assertNull(it.scheme)
        assertEquals(authority("S"), it.authority)
        assertEquals("", it.path)
        assertNull(it.query)
        assertNull(it.port)
        assertEquals("S", it.host)
    }

    @Test
    fun testUriParseAuthorityForm() = testParse("localhost:3000", listOf("localhosT:3000")) {
        assertNull(it.scheme)
        assertEquals(authority("localhost:3000"), it.authority)
        assertEquals("", it.path)
        assertNull(it.query)
        assertEquals("localhost", it.host)
        assertEquals(Port.fromStr("3000"), it.port)
    }

    @Test
    fun testUriParseAbsoluteWithDefaultPortHttp() = testParse("http://127.0.0.1:80", listOf("http://127.0.0.1:80/")) {
        assertEquals(scheme("http"), it.scheme)
        assertEquals(authority("127.0.0.1:80"), it.authority)
        assertEquals("127.0.0.1", it.host)
        assertEquals("/", it.path)
        assertNull(it.query)
        assertEquals(Port.fromStr("80"), it.port)
    }

    @Test
    fun testUriParseAbsoluteWithDefaultPortHttps() = testParse("https://127.0.0.1:443", listOf("https://127.0.0.1:443/")) {
        assertEquals(scheme("https"), it.scheme)
        assertEquals(authority("127.0.0.1:443"), it.authority)
        assertEquals("127.0.0.1", it.host)
        assertEquals("/", it.path)
        assertNull(it.query)
        assertEquals(Port.fromStr("443"), it.port)
    }

    @Test
    fun testUriParseFragmentQuestionmark() = testParse("http://127.0.0.1/#?", listOf()) {
        assertEquals(scheme("http"), it.scheme)
        assertEquals(authority("127.0.0.1"), it.authority)
        assertEquals("127.0.0.1", it.host)
        assertEquals("/", it.path)
        assertNull(it.query)
        assertNull(it.port)
    }

    @Test
    fun testUriParsePathWithTerminatingQuestionmark() = testParse("http://127.0.0.1/path?", listOf()) {
        assertEquals(scheme("http"), it.scheme)
        assertEquals(authority("127.0.0.1"), it.authority)
        assertEquals("/path", it.path)
        assertEquals("", it.query)
        assertNull(it.port)
    }

    @Test
    fun testUriParseAbsoluteFormWithEmptyPathAndNonemptyQuery() = testParse("http://127.0.0.1?foo=bar", listOf()) {
        assertEquals(scheme("http"), it.scheme)
        assertEquals(authority("127.0.0.1"), it.authority)
        assertEquals("/", it.path)
        assertEquals("foo=bar", it.query)
        assertNull(it.port)
    }

    @Test
    fun testUriParseAbsoluteFormWithEmptyPathAndFragmentWithSlash() = testParse("http://127.0.0.1#foo/bar", listOf()) {
        assertEquals(scheme("http"), it.scheme)
        assertEquals(authority("127.0.0.1"), it.authority)
        assertEquals("/", it.path)
        assertNull(it.query)
        assertNull(it.port)
    }

    @Test
    fun testUriParseAbsoluteFormWithEmptyPathAndFragmentWithQuestionmark() =
        testParse("http://127.0.0.1#foo?bar", listOf()) {
            assertEquals(scheme("http"), it.scheme)
            assertEquals(authority("127.0.0.1"), it.authority)
            assertEquals("/", it.path)
            assertNull(it.query)
            assertNull(it.port)
        }

    @Test
    fun testUriParseLongHostWithNoScheme() =
        testParse("thequickbrownfoxjumpedoverthelazydogtofindthelargedangerousdragon.localhost", listOf()) {
            assertNull(it.scheme)
            assertEquals(
                authority("thequickbrownfoxjumpedoverthelazydogtofindthelargedangerousdragon.localhost"),
                it.authority,
            )
            assertEquals("", it.path)
            assertNull(it.query)
            assertNull(it.port)
        }

    @Test
    fun testUriParseLongHostWithPortAndNoScheme() =
        testParse("thequickbrownfoxjumpedoverthelazydogtofindthelargedangerousdragon.localhost:1234", listOf()) {
            assertNull(it.scheme)
            assertEquals(
                authority("thequickbrownfoxjumpedoverthelazydogtofindthelargedangerousdragon.localhost:1234"),
                it.authority,
            )
            assertEquals("", it.path)
            assertNull(it.query)
            assertEquals(Port.fromStr("1234"), it.port)
        }

    @Test
    fun testUserinfo1() = testParse("http://a:b@127.0.0.1:1234/", listOf()) {
        assertEquals(scheme("http"), it.scheme)
        assertEquals(authority("a:b@127.0.0.1:1234"), it.authority)
        assertEquals("127.0.0.1", it.host)
        assertEquals("/", it.path)
        assertNull(it.query)
        assertEquals(Port.fromStr("1234"), it.port)
    }

    @Test
    fun testUserinfo2() = testParse("http://a:b@127.0.0.1/", listOf()) {
        assertEquals(scheme("http"), it.scheme)
        assertEquals(authority("a:b@127.0.0.1"), it.authority)
        assertEquals("127.0.0.1", it.host)
        assertEquals("/", it.path)
        assertNull(it.query)
        assertNull(it.port)
    }

    @Test
    fun testUserinfo3() = testParse("http://a@127.0.0.1/", listOf()) {
        assertEquals(scheme("http"), it.scheme)
        assertEquals(authority("a@127.0.0.1"), it.authority)
        assertEquals("127.0.0.1", it.host)
        assertEquals("/", it.path)
        assertNull(it.query)
        assertNull(it.port)
    }

    @Test
    fun testUserinfoWithPort() = testParse("user@localhost:3000", listOf()) {
        assertNull(it.scheme)
        assertEquals(authority("user@localhost:3000"), it.authority)
        assertEquals("", it.path)
        assertNull(it.query)
        assertEquals("localhost", it.host)
        assertEquals(Port.fromStr("3000"), it.port)
    }

    @Test
    fun testUserinfoPassWithPort() = testParse("user:pass@localhost:3000", listOf()) {
        assertNull(it.scheme)
        assertEquals(authority("user:pass@localhost:3000"), it.authority)
        assertEquals("", it.path)
        assertNull(it.query)
        assertEquals("localhost", it.host)
        assertEquals(Port.fromStr("3000"), it.port)
    }

    @Test
    fun testIpv6() = testParse("http://[2001:0db8:85a3:0000:0000:8a2e:0370:7334]/", listOf()) {
        assertEquals(scheme("http"), it.scheme)
        assertEquals(authority("[2001:0db8:85a3:0000:0000:8a2e:0370:7334]"), it.authority)
        assertEquals("[2001:0db8:85a3:0000:0000:8a2e:0370:7334]", it.host)
        assertEquals("/", it.path)
        assertNull(it.query)
        assertNull(it.port)
    }

    @Test
    fun testIpv6Shorthand() = testParse("http://[::1]/", listOf()) {
        assertEquals(scheme("http"), it.scheme)
        assertEquals(authority("[::1]"), it.authority)
        assertEquals("[::1]", it.host)
        assertEquals("/", it.path)
        assertNull(it.query)
        assertNull(it.port)
    }

    @Test
    fun testIpv6Shorthand2() = testParse("http://[::]/", listOf()) {
        assertEquals(scheme("http"), it.scheme)
        assertEquals(authority("[::]"), it.authority)
        assertEquals("[::]", it.host)
        assertEquals("/", it.path)
        assertNull(it.query)
        assertNull(it.port)
    }

    @Test
    fun testIpv6Shorthand3() = testParse("http://[2001:db8::2:1]/", listOf()) {
        assertEquals(scheme("http"), it.scheme)
        assertEquals(authority("[2001:db8::2:1]"), it.authority)
        assertEquals("[2001:db8::2:1]", it.host)
        assertEquals("/", it.path)
        assertNull(it.query)
        assertNull(it.port)
    }

    @Test
    fun testIpv6WithPort() = testParse("http://[2001:0db8:85a3:0000:0000:8a2e:0370:7334]:8008/", listOf()) {
        assertEquals(scheme("http"), it.scheme)
        assertEquals(authority("[2001:0db8:85a3:0000:0000:8a2e:0370:7334]:8008"), it.authority)
        assertEquals("[2001:0db8:85a3:0000:0000:8a2e:0370:7334]", it.host)
        assertEquals("/", it.path)
        assertNull(it.query)
        assertEquals(Port.fromStr("8008"), it.port)
    }

    @Test
    fun testPercentageEncodedPath() = testParse("/echo/abcdefgh_i-j%20/abcdefg_i-j%20478", listOf()) {
        assertNull(it.scheme)
        assertNull(it.authority)
        assertNull(it.host)
        assertEquals("/echo/abcdefgh_i-j%20/abcdefg_i-j%20478", it.path)
        assertNull(it.query)
        assertNull(it.port)
    }

    @Test
    fun testPathPermissive() = testParse("/foo=bar|baz\\^~%", listOf()) {
        assertEquals("/foo=bar|baz\\^~%", it.path)
    }

    @Test
    fun testQueryPermissive() = testParse("/?foo={bar|baz}\\^`", listOf()) {
        assertEquals("foo={bar|baz}\\^`", it.query)
    }

    @Test
    fun testUriParseError() {
        fun err(s: String) {
            assertFailsWith<InvalidUri>(s) { Uri.parse(s) }
            assertNull(Uri.tryParse(s))
        }

        err("http://")
        err("htt:p//host")
        err("hyper.rs/")
        err("hyper.rs?key=val")
        err("?key=val")
        err("localhost/")
        err("localhost?key=val")
        err("\u0000")
        err("http://[::1")
        err("http://::1]")
        err("localhost:8080:3030")
        err("@")
        err("http://username:password@/wut")

        // illegal queries
        err("/?foo\rbar")
        err("/?foo\nbar")
        err("/?<")
        err("/?>")
    }

    @Test
    fun testMaxUriLen() {
        val uri = "http://localhost/" + "a".repeat(70 * 1024)
        assertEquals(InvalidUri.ErrorKind.TooLong, assertFailsWith<InvalidUri> { Uri.parse(uri) }.kind)
    }

    @Test
    fun testOverflowingScheme() {
        val uri = "a".repeat(256) + "://localhost/"
        assertEquals(InvalidUri.ErrorKind.SchemeTooLong, assertFailsWith<InvalidUri> { Uri.parse(uri) }.kind)
    }

    @Test
    fun testMaxLengthScheme() {
        val uri = Uri.parse("a".repeat(64) + "://localhost/")
        assertEquals(64, uri.schemeStr!!.length)
    }

    @Test
    fun testUriToPathAndQuery() {
        val cases = listOf(
            "/" to "/",
            "/foo?bar" to "/foo?bar",
            "/foo?bar#nope" to "/foo?bar",
            "http://hyper.rs" to "/",
            "http://hyper.rs/" to "/",
            "http://hyper.rs/path" to "/path",
            "http://hyper.rs?query" to "/?query",
            "*" to "*",
        )
        for ((input, expected) in cases) {
            val uri = Uri.parse(input)
            assertEquals(expected, uri.pathAndQuery!!.toString())
        }
    }

    @Test
    fun testAuthorityUriPartsRoundTrip() {
        val s = "hyper.rs"
        val uri = Uri.parse(s)
        assertTrue(uri eq s)
        assertEquals(s, uri.toString())

        val parts = uri.intoParts()
        val uri2 = Uri.fromParts(parts)
        assertTrue(uri2 eq s)
        assertEquals(s, uri2.toString())
    }

    @Test
    fun testPartialEqPathWithTerminatingQuestionmark() {
        val a = "/path"
        val uri = Uri.parse("/path?")
        assertTrue(uri eq a)
    }
}
