package neton.http.uri

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The doc-test examples of http 1.5.0 `src/uri/*.rs` that assert behaviour.
class UriDocExamplesTest {

    @Test
    fun moduleAndUriExamples() {
        val uri = Uri.parse("/foo/bar?baz")
        assertEquals("/foo/bar", uri.path)
        assertEquals("baz", uri.query)
        assertNull(uri.host)

        val uri2 = Uri.parse("https://www.rust-lang.org/install.html")
        assertEquals("https", uri2.schemeStr)
        assertEquals("www.rust-lang.org", uri2.host)
        assertEquals("/install.html", uri2.path)
    }

    @Test
    fun builderExample() {
        Uri.builder().scheme("https").authority("hyper.rs").pathAndQuery("/").build()
        UriBuilder().authority("tokio.rs").build()
        UriBuilder().pathAndQuery("/hello?foo=bar").build()
        Uri.builder().build()
    }

    @Test
    fun fromPartsRelative() {
        val parts = UriParts()
        parts.pathAndQuery = PathAndQuery.parse("/foo")
        val uri = Uri.fromParts(parts)
        assertEquals("/foo", uri.path)
        assertNull(uri.scheme)
        assertNull(uri.authority)
    }

    @Test
    fun fromPartsAbsolute() {
        val parts = UriParts()
        parts.scheme = Scheme.parse("http")
        parts.authority = Authority.parse("foo.com")
        parts.pathAndQuery = PathAndQuery.parse("/foo")
        val uri = Uri.fromParts(parts)
        assertEquals("http", uri.scheme!!.asStr())
        assertTrue(uri.authority!! eq "foo.com")
        assertEquals("/foo", uri.path)
    }

    @Test
    fun fromStatic() {
        val uri = Uri.fromStatic("http://example.com/foo")
        assertEquals("example.com", uri.host)
        assertEquals("/foo", uri.path)
    }

    @Test
    fun intoParts() {
        val uri = Uri.parse("/foo")
        val parts = uri.intoParts()
        assertTrue(parts.pathAndQuery!! eq "/foo")
        assertNull(parts.scheme)
        assertNull(parts.authority)
    }

    @Test
    fun uriAccessors() {
        assertEquals("/hello/world", Uri.parse("/hello/world").path)
        assertEquals("/hello/world", Uri.parse("http://example.org/hello/world").path)
        assertEquals(Scheme.HTTP, Uri.parse("http://example.org/hello/world").scheme)
        assertNull(Uri.parse("/hello/world").scheme)
        assertEquals("http", Uri.parse("http://example.org/hello/world").schemeStr)
        assertEquals("example.org:80", Uri.parse("http://example.org:80/hello/world").authority?.asStr())
        assertNull(Uri.parse("/hello/world").authority)
        assertEquals("example.org", Uri.parse("http://example.org:80/hello/world").host)
        assertNull(Uri.parse("/hello/world").host)
        assertEquals(80, Uri.parse("http://example.org:80/hello/world").port!!.asU16())
        assertNull(Uri.parse("http://example.org/hello/world").port)
        assertNull(Uri.parse("/hello/world").port)
        assertEquals(80, Uri.parse("http://example.org:80/hello/world").portU16)
        assertEquals("key=value", Uri.parse("http://example.org/hello/world?key=value").query)
        assertEquals("key=value&foo=bar", Uri.parse("/hello/world?key=value&foo=bar").query)
        assertNull(Uri.parse("/hello/world").query)
    }

    @Test
    fun schemeExamples() {
        assertEquals("http", Scheme.parse("http").asStr())
        val scheme = Scheme.parse("HTTP")
        assertTrue(scheme eq "http")
    }

    @Test
    fun authorityExamples() {
        assertEquals("example.com", Authority.fromStatic("example.com").host)
        assertEquals("example.org", Authority.parse("example.org:80").host)
        val port = Authority.parse("example.org:80").port!!
        assertEquals(80, port.asU16())
        assertEquals("80", port.asStr())
        assertNull(Authority.parse("example.org").port)
        assertEquals(80, Authority.parse("example.org:80").portU16)

        val a = Authority.parse("HELLO.com")
        assertTrue(a eq "hello.coM")
        assertTrue("hello.com" eq a)

        val d = Authority.parse("DEF.com")
        assertTrue(d < "ghi.com")
        assertTrue(d > "abc.com")

        assertEquals(Authority.parse("HELLO.com").hashCode(), Authority.parse("hello.coM").hashCode())
    }

    @Test
    fun pathAndQueryExamples() {
        val v = PathAndQuery.fromStatic("/hello?world")
        assertEquals("/hello", v.path)
        assertEquals("world", v.query)

        assertEquals("/hello/world", PathAndQuery.parse("/hello/world").path)
        assertEquals("key=value&foo=bar", PathAndQuery.parse("/hello/world?key=value&foo=bar").query)
        assertNull(PathAndQuery.parse("/hello/world").query)
        assertEquals("/hello/world?key=value&foo=bar", PathAndQuery.parse("/hello/world?key=value&foo=bar").asStr())
        assertEquals("/hello/world", PathAndQuery.parse("/hello/world").asStr())
    }
}

// Behaviour of the Kotlin entry points (bytes, try-forms, error kinds) checked against the reference's rules.
class UriKotlinSurfaceTest {

    private fun kind(s: String): InvalidUri.ErrorKind = assertFailsWith<InvalidUri> { Uri.parse(s) }.kind

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test
    fun errorKinds() {
        assertEquals(InvalidUri.ErrorKind.Empty, kind(""))
        assertEquals(InvalidUri.ErrorKind.Empty, kind("http://"))
        assertEquals(InvalidUri.ErrorKind.InvalidFormat, kind("hyper.rs/"))
        assertEquals(InvalidUri.ErrorKind.InvalidFormat, kind("http:///path"))
        assertEquals(InvalidUri.ErrorKind.InvalidUriChar, kind("?"))
        assertEquals(InvalidUri.ErrorKind.InvalidUriChar, kind("/a b"))
        assertEquals(InvalidUri.ErrorKind.InvalidAuthority, kind("localhost:8080:3030"))
        assertEquals(InvalidUri.ErrorKind.InvalidAuthority, kind("@"))
        assertEquals(InvalidUri.ErrorKind.PathDoesNotStartWithSlash, PathAndQuery.tryParse("x").let {
            assertFailsWith<InvalidUri> { PathAndQuery.parse("x") }.kind
        })
        assertEquals(InvalidUri.ErrorKind.InvalidScheme, assertFailsWith<InvalidUri> { Scheme.parse("a:b") }.kind)
        assertEquals(
            InvalidUri.ErrorKind.SchemeTooLong,
            assertFailsWith<InvalidUri> { Scheme.parse("a".repeat(65)) }.kind,
        )
        assertEquals(InvalidUri.ErrorKind.TooLong, kind("/" + "a".repeat(MAX_LEN)))
        assertTrue(Uri.tryParse("/" + "a".repeat(MAX_LEN - 1)) != null)
        assertEquals(
            InvalidUri.ErrorKind.SchemeTooLong,
            kind("a".repeat(65) + "://localhost/"),
        )
        // Length is counted in UTF-8 bytes: 21845 three-byte chars plus "/" is 65536 bytes.
        assertEquals(InvalidUri.ErrorKind.TooLong, kind("/" + "中".repeat(21845)))
    }

    @Test
    fun partsErrorKinds() {
        val a = Authority.parse("a.com")
        val p = PathAndQuery.parse("/")
        fun partsKind(parts: UriParts) = assertFailsWith<InvalidUriParts> { Uri.fromParts(parts) }.kind
        assertEquals(InvalidUri.ErrorKind.AuthorityMissing, partsKind(UriParts(Scheme.HTTP, null, p)))
        assertEquals(InvalidUri.ErrorKind.PathAndQueryMissing, partsKind(UriParts(Scheme.HTTP, a, null)))
        assertEquals(InvalidUri.ErrorKind.SchemeMissing, partsKind(UriParts(null, a, p)))
        assertNull(Uri.tryFromParts(UriParts(null, a, p)))
    }

    @Test
    fun builderKeepsFirstError() {
        val e = assertFailsWith<InvalidUri> {
            UriBuilder().scheme("bad_scheme").authority("a b").pathAndQuery("/").build()
        }
        assertEquals(InvalidUri.ErrorKind.InvalidScheme, e.kind)
        assertNull(UriBuilder().authority("").tryBuild())
    }

    @Test
    fun fromBytesWithOffset() {
        val raw = "GET /a/b?c=d HTTP/1.1".encodeToByteArray()
        val uri = Uri.fromBytes(raw, 4, 8)
        assertEquals("/a/b", uri.path)
        assertEquals("c=d", uri.query)
        assertTrue(uri eq "/a/b?c=d")
        assertEquals(Uri.parse("/a/b?c=d"), uri)
    }

    @Test
    fun fromBytesUtf8Rules() {
        // Valid UTF-8 in the path is kept.
        assertEquals("/é", Uri.fromBytes(bytes('/'.code, 0xC3, 0xA9)).path)
        // Invalid UTF-8 in the path or query is rejected.
        for (b in listOf(bytes('/'.code, 0xC0, 0x80), bytes('/'.code, 0xED, 0xA0, 0x80), bytes('/'.code, 0xF4, 0x90, 0x80, 0x80))) {
            assertEquals(InvalidUri.ErrorKind.InvalidUriChar, assertFailsWith<InvalidUri> { Uri.fromBytes(b) }.kind)
        }
        assertNull(Uri.tryFromBytes(bytes('/'.code, '?'.code, 0xFF)))
        // Invalid UTF-8 inside the dropped fragment is accepted.
        val u = Uri.fromBytes(bytes('/'.code, 'a'.code, '#'.code, 0xFF))
        assertEquals("/a", u.path)
        assertEquals("/a", Uri.fromBytes(bytes('h'.code, 't'.code, 't'.code, 'p'.code, ':'.code, '/'.code, '/'.code, 'h'.code, '/'.code, 'a'.code, '#'.code, 0xFF)).path)
        // Non-ASCII in an authority is an invalid char.
        assertEquals(
            InvalidUri.ErrorKind.InvalidUriChar,
            assertFailsWith<InvalidUri> { Uri.fromBytes(bytes('h'.code, 0xC3, 0xA9)) }.kind,
        )
        // Too long counts bytes.
        assertEquals(
            InvalidUri.ErrorKind.TooLong,
            assertFailsWith<InvalidUri> { Uri.fromBytes(ByteArray(MAX_LEN + 1) { '/'.code.toByte() }) }.kind,
        )
    }

    @Test
    fun loneSurrogateInPathIsInvalid() {
        assertEquals(InvalidUri.ErrorKind.InvalidUriChar, kind("/a\uD800"))
        // ...but a fragment is dropped before the check.
        assertEquals("/a", Uri.parse("/a#\uD800").path)
    }

    @Test
    fun fragmentIsDroppedAndAnythingGoesAfterIt() {
        val uri = Uri.parse("/a?b#c d\u0000")
        assertEquals("/a", uri.path)
        assertEquals("b", uri.query)
        assertEquals("/a?b", uri.toString())
    }

    @Test
    fun schemeCaseAndEquality() {
        val u = Uri.parse("HTTP://Example.COM/x")
        assertEquals(Scheme.HTTP, u.scheme)
        assertEquals("http://Example.COM/x", u.toString())
        assertEquals(Uri.parse("http://example.com/x"), u)
        assertTrue(u eq "http://EXAMPLE.com/x")
        assertFalse(u eq "http://example.com/X")
        // Only an exact "http" is the standard constant, as in the reference.
        assertNotEquals(Scheme.HTTP, Scheme.parse("HTTP"))
        assertEquals(Scheme.parse("Foo"), Scheme.parse("fOO"))
        assertEquals(Scheme.parse("Foo").hashCode(), Scheme.parse("fOO").hashCode())
        val other = Uri.parse("Git+SSH://host/")
        assertEquals("Git+SSH", other.schemeStr)
        assertEquals(Uri.parse("git+ssh://HOST/"), other)
    }

    @Test
    fun portQuirks() {
        assertEquals(80, Authority.parse("h:+80").portU16)
        assertEquals("+80", Authority.parse("h:+80").port!!.asStr())
        assertEquals("80", Authority.parse("h:+80").port!!.toString())
        assertNull(Authority.parse("h:65536").port)
        assertNull(Authority.parse("h:").port)
        assertNull(Authority.parse("[::1]").port)
        assertEquals(8080, Authority.parse("[::1]:8080").portU16)
    }

    @Test
    fun pathAndQueryDisplayAndEmpty() {
        val q = PathAndQuery.parse("?a=1")
        assertEquals("/", q.path)
        assertEquals("?a=1", q.asStr())
        assertEquals("/?a=1", q.toString())
        val f = PathAndQuery.parse("#frag")
        assertEquals("/", f.asStr())
        assertEquals("/", f.path)
        assertNotEquals(PathAndQuery.parse("/"), f)
        assertFailsWith<IllegalArgumentException> { PathAndQuery.fromStatic("/a#b") }
        assertFailsWith<IllegalArgumentException> { PathAndQuery.fromStatic("/é") }
        assertFailsWith<IllegalArgumentException> { Uri.fromStatic("a b") }
        assertFailsWith<IllegalArgumentException> { Authority.fromStatic("") }
    }

    @Test
    fun uriFromComponents() {
        val a = Uri.from(Authority.parse("example.com:443"))
        assertTrue(a eq "example.com:443")
        assertNull(a.pathAndQuery)
        assertEquals("", a.path)
        val p = Uri.from(PathAndQuery.parse("/x?y"))
        assertEquals("/x?y", p.toString())
    }
}
