package neton.http

import kotlinx.coroutines.runBlocking
import neton.http.header.HeaderName
import neton.http.header.HeaderValue
import neton.http.uri.Uri
import neton.io.bytes.Bytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** http 1.5.0 `request.rs` / `response.rs`: the inline tests and the asserting doc examples. */
class RequestResponseTest {

    // request.rs tests::it_can_map_a_body_from_one_type_to_another
    @Test fun requestItCanMapABodyFromOneTypeToAnother() {
        val request = Request.builder().body("some string")
        val mapped = request.map { s -> assertEquals("some string", s); 123 }
        assertEquals(123, mapped.body)
    }

    // response.rs tests::it_can_map_a_body_from_one_type_to_another
    @Test fun responseItCanMapABodyFromOneTypeToAnother() {
        val response = Response.builder().body("some string")
        val mapped = response.map { s -> assertEquals("some string", s); 123 }
        assertEquals(123, mapped.body)
    }

    @Test fun requestNewHasDefaults() {
        val r = Request("hello world")
        assertEquals(Method.GET, r.method); assertEquals("/", r.uri.toString()); assertEquals(Version.HTTP_11, r.version)
        assertTrue(r.headers.isEmpty()); assertTrue(r.extensions.isEmpty()); assertEquals("hello world", r.body)
    }

    @Test fun requestShortcutsSetMethodAndUri() {
        val cases = listOf(Request.get("https://www.rust-lang.org/") to Method.GET, Request.put("https://www.rust-lang.org/") to Method.PUT,
            Request.post("https://www.rust-lang.org/") to Method.POST, Request.delete("https://www.rust-lang.org/") to Method.DELETE,
            Request.options("https://www.rust-lang.org/") to Method.OPTIONS, Request.head("https://www.rust-lang.org/") to Method.HEAD,
            Request.connect("https://www.rust-lang.org/") to Method.CONNECT, Request.patch("https://www.rust-lang.org/") to Method.PATCH,
            Request.trace("https://www.rust-lang.org/") to Method.TRACE)
        for ((b, m) in cases) {
            val r = b.body(Unit)
            assertEquals(m, r.method); assertEquals("https://www.rust-lang.org/", r.uri.toString())
        }
    }

    @Test fun requestMutatorsAndParts() {
        val r = Request("")
        r.method = Method.PUT; r.uri = Uri.parse("/hello"); r.version = Version.HTTP_2
        r.headers.insert(HeaderName.HOST, HeaderValue.fromStr("world"))
        r.extensions.insert("hello")
        assertEquals(Method.PUT, r.method); assertEquals("/hello", r.uri.toString()); assertEquals(Version.HTTP_2, r.version)
        assertEquals("world", r.headers[HeaderName.HOST]!!.toStr()); assertEquals("hello", r.extensions.get<String>())
        val (parts, body) = r
        assertEquals(Method.PUT, parts.method); assertEquals("", body)
        val back = Request.fromParts(parts, 7)
        assertSame(parts, back.parts); assertEquals(7, back.body)
    }

    @Test fun requestBuilderAppendsHeadersAndKeepsState() {
        val b = Request.builder().method("POST").uri("https://www.rust-lang.org/").version(Version.HTTP_2)
            .header("Accept", "text/html").header("X-Custom-Foo", "bar").header("X-Custom-Foo", "baz")
            .extension("My Extension")
        assertEquals(Method.POST, b.methodRef()); assertEquals("https://www.rust-lang.org/", b.uriRef().toString())
        assertEquals(Version.HTTP_2, b.versionRef())
        val headers = b.headersRef()!!
        assertEquals("text/html", headers["Accept"]!!.toStr())
        assertEquals(listOf("bar", "baz"), headers.getAll("X-Custom-Foo").map { it.toStr() })
        assertEquals("My Extension", b.extensionsRef()!!.get<String>())
        val r = b.body(Unit)
        assertEquals(Method.POST, r.method)
    }

    @Test fun requestBuilderRemembersTheFirstError() {
        val b = Request.builder().method("GE T").uri("https://ok/").header("Bad Header", "x")
        assertNull(b.methodRef(), "after an error the builder holds no parts")
        assertFailsWith<InvalidMethod> { b.body(Unit) }
        assertFailsWith<neton.http.header.InvalidHeaderName> { Request.builder().header("Bad Header", "x").method(Method.GET).body(Unit) }
        assertFailsWith<neton.http.uri.InvalidUri> { Request.builder().uri("http:// bad").body(Unit) }
    }

    @Test fun responseDefaultsBuilderAndErrors() {
        val r = Response("hi")
        assertEquals(StatusCode.OK, r.status); assertEquals(Version.HTTP_11, r.version)
        val b = Response.builder().status(404).version(Version.HTTP_2).header("X-Custom-Foo", "Bar").header(HeaderName.CONTENT_TYPE, "text/plain")
        assertEquals(StatusCode.NOT_FOUND, b.statusRef()); assertEquals(Version.HTTP_2, b.versionRef())
        val resp = b.body(Unit)
        assertEquals("Bar", resp.headers["X-Custom-Foo"]!!.toStr())
        assertFailsWith<InvalidStatusCode> { Response.builder().status(1000).body(Unit) }
        val (parts, body) = Response.builder().status(StatusCode.CREATED).body("x")
        assertEquals(StatusCode.CREATED, parts.status); assertEquals("x", body)
    }

    @Test fun bodies() = runBlocking {
        assertTrue(EmptyBody.isEndStream); assertEquals(0L, EmptyBody.sizeHint.exact); assertNull(EmptyBody.nextFrame())
        val full = FullBody(Bytes.wrap("abc".encodeToByteArray()))
        assertEquals(3L, full.sizeHint.exact)
        val f = full.nextFrame() as Frame.Data
        assertEquals("abc", f.bytes.decodeToString())
        assertTrue(full.isEndStream); assertNull(full.nextFrame())
        assertTrue(FullBody(Bytes.EMPTY).isEndStream)
        assertNull(SizeHint(1, 5).exact); assertEquals(4L, SizeHint.withExact(4).exact)
    }
}
