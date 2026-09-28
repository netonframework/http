package neton.http.h1

import neton.http.header.HeaderMap
import neton.http.header.HeaderName
import neton.http.header.HeaderValue
import neton.io.bytes.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The 10 tests of hyper 1.11.1 `src/proto/h1/encode.rs`, one each; `dst: Vec<u8>` is the output [Buffer]. */
class BodyEncoderTest {
    private fun name(s: String) = HeaderName.fromStatic(s)
    private fun value(s: String) = HeaderValue.fromStatic(s)

    @Test
    fun chunked() {
        val encoder = BodyEncoder.chunked()
        val dst = Buffer()

        encoder.encode(ascii("foo bar"), dst)
        assertEquals("7\r\nfoo bar\r\n", dst.text())

        encoder.encode(ascii("baz quux herp"), dst)
        assertEquals("7\r\nfoo bar\r\nD\r\nbaz quux herp\r\n", dst.text())

        assertTrue(encoder.end(dst))
        assertEquals("7\r\nfoo bar\r\nD\r\nbaz quux herp\r\n0\r\n\r\n", dst.text())
    }

    @Test
    fun length() {
        val maxLen = 8
        val encoder = BodyEncoder.length(maxLen.toLong())
        val dst = Buffer()

        encoder.encode(ascii("foo bar"), dst)
        assertEquals("foo bar", dst.text())
        assertFalse(encoder.isEof)
        assertFalse(encoder.end(dst)) // NotEof
        assertEquals(1L, encoder.remaining)

        encoder.encode(ascii("baz"), dst)
        assertEquals(maxLen, dst.readableBytes)
        assertEquals("foo barb", dst.text())
        assertTrue(encoder.isEof)
        assertTrue(encoder.end(dst))
        assertEquals("foo barb", dst.text()) // end writes nothing
    }

    @Test
    fun eof() {
        val encoder = BodyEncoder.closeDelimited()
        val dst = Buffer()

        encoder.encode(ascii("foo bar"), dst)
        assertEquals("foo bar", dst.text())
        assertFalse(encoder.isEof)
        assertTrue(encoder.end(dst))

        encoder.encode(ascii("baz"), dst)
        assertEquals("foo barbaz", dst.text())
        assertFalse(encoder.isEof)
        assertTrue(encoder.end(dst))
        assertEquals("foo barbaz", dst.text())
    }

    @Test
    fun chunkedWithValidTrailers() {
        val encoder = BodyEncoder.chunked().withTrailerFields(listOf(name("chunky-trailer")))
        val headers = HeaderMap.fromIter(
            listOf(
                name("chunky-trailer") to value("header data"),
                name("should-not-be-included") to value("oops"),
            ),
        )
        val dst = Buffer()
        assertTrue(encoder.encodeTrailers(headers, false, dst))
        assertEquals("0\r\nchunky-trailer: header data\r\n\r\n", dst.text())
    }

    @Test
    fun chunkedWithMultipleTrailerHeaders() {
        val encoder = BodyEncoder.chunked()
            .withTrailerFields(listOf(name("chunky-trailer"), name("chunky-trailer-2")))
        val headers = HeaderMap.fromIter(
            listOf(
                name("chunky-trailer") to value("header data"),
                name("chunky-trailer-2") to value("more header data"),
            ),
        )
        val dst = Buffer()
        assertTrue(encoder.encodeTrailers(headers, false, dst))
        assertEquals("0\r\nchunky-trailer: header data\r\nchunky-trailer-2: more header data\r\n\r\n", dst.text())
    }

    @Test
    fun chunkedWithDuplicateTrailerValues() {
        val encoder = BodyEncoder.chunked().withTrailerFields(listOf(name("chunky-trailer")))
        val headers = HeaderMap<HeaderValue>()
        headers.append(name("chunky-trailer"), value("first"))
        headers.append(name("chunky-trailer"), value("second"))

        val dst = Buffer()
        assertTrue(encoder.encodeTrailers(headers, false, dst))
        assertEquals("0\r\nchunky-trailer: first\r\nchunky-trailer: second\r\n\r\n", dst.text())
    }

    @Test
    fun chunkedWithNoTrailerHeader() {
        val encoder = BodyEncoder.chunked()
        val headers = HeaderMap.fromIter(listOf(name("chunky-trailer") to value("header data")))
        val dst = Buffer()

        assertFalse(encoder.encodeTrailers(headers.clone(), false, dst))

        val withEmptyList = encoder.withTrailerFields(emptyList())
        assertFalse(withEmptyList.encodeTrailers(headers, false, dst))
        assertEquals(0, dst.readableBytes)
    }

    @Test
    fun chunkedWithInvalidTrailers() {
        val forbidden = listOf(
            HeaderName.AUTHORIZATION, HeaderName.CACHE_CONTROL, HeaderName.CONTENT_ENCODING, HeaderName.CONTENT_LENGTH,
            HeaderName.CONTENT_RANGE, HeaderName.CONTENT_TYPE, HeaderName.HOST, HeaderName.MAX_FORWARDS,
            HeaderName.SET_COOKIE, HeaderName.TRAILER, HeaderName.TRANSFER_ENCODING, HeaderName.TE,
        )
        val encoder = BodyEncoder.chunked().withTrailerFields(forbidden)
        val headers = HeaderMap<HeaderValue>()
        for (n in forbidden) headers.insert(n, value("header data"))

        val dst = Buffer()
        assertFalse(encoder.encodeTrailers(headers, true, dst))
        assertEquals(0, dst.readableBytes)
    }

    @Test
    fun chunkedWithTitleCaseHeaders() {
        val encoder = BodyEncoder.chunked().withTrailerFields(listOf(name("chunky-trailer")))
        val headers = HeaderMap.fromIter(listOf(name("chunky-trailer") to value("header data")))
        val dst = Buffer()
        assertTrue(encoder.encodeTrailers(headers, true, dst))
        assertEquals("0\r\nChunky-Trailer: header data\r\n\r\n", dst.text())
    }

    @Test
    fun chunkedTrailersCaseInsensitiveMatching() {
        // Regression test for hyper issue #4010: the `Trailer` header values are parsed into HeaderName (as
        // `HeaderName.fromBytes` does, lowercasing "Chunky-Trailer"), so they match the lowercase trailer names.
        val declared = HeaderName.fromBytes(ascii("Chunky-Trailer"))
        val encoder = BodyEncoder.chunked().withTrailerFields(listOf(declared))
        val headers = HeaderMap.fromIter(listOf(name("chunky-trailer") to value("trailer value")))

        val dst = Buffer()
        assertTrue(encoder.encodeTrailers(headers, false, dst))
        assertEquals("0\r\nchunky-trailer: trailer value\r\n\r\n", dst.text())
    }
}
