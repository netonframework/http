package neton.http.h3.proto

import neton.http.Method
import neton.http.StatusCode
import neton.http.h3.Code
import neton.http.h3.qpack.Decoder
import neton.http.h3.qpack.DecoderError
import neton.http.h3.qpack.DecoderException
import neton.http.h3.qpack.Encoder
import neton.http.h3.qpack.HeaderField
import neton.http.h3.qpack.Indexed
import neton.http.h3.qpack.StaticTable
import neton.http.header.HeaderMap
import neton.http.header.HeaderName
import neton.http.header.HeaderValue
import neton.http.uri.Scheme
import neton.http.uri.Uri
import neton.io.bytes.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun f(name: String, value: String) = HeaderField(name, value)

private fun headerError(vararg fields: HeaderField): HeaderError =
    assertFailsWith<HeaderException> { Header.fromFields(fields.toList()) }.error

private fun map(vararg pairs: Pair<String, String>): HeaderMap<HeaderValue> =
    HeaderMap<HeaderValue>().also { m -> pairs.forEach { m.append(HeaderName.fromStr(it.first), HeaderValue.fromStr(it.second)) } }

// Tests of `src/proto/headers.rs` (all 8 ported), then the four ⚖️ checks (SPEC §5), the typed QPACK round trip, and
// decoding with validation and limits together.
class HeadersTest {
    // ---- proto/headers.rs ----

    @Test
    fun request_has_no_authority_nor_host() {
        val headers = Header.fromFields(listOf(f(":method", "GET")))
        assertNull(headers.pseudo.authority)
        assertEquals(HeaderError.MissingAuthority, assertFailsWith<HeaderException> { headers.intoRequestParts() }.error)
    }

    @Test
    fun request_has_empty_authority() {
        assertIs<HeaderError.InvalidHeaderValue>(headerError(f(":method", "GET"), f(":authority", "")))
    }

    @Test
    fun request_has_empty_host() {
        val headers = Header.fromFields(listOf(f(":method", "GET"), f("host", "")))
        assertIs<HeaderError.InvalidRequest>(assertFailsWith<HeaderException> { headers.intoRequestParts() }.error)
    }

    @Test
    fun request_has_authority() {
        Header.fromFields(listOf(f(":method", "GET"), f(":authority", "test.com"))).intoRequestParts()
    }

    @Test
    fun request_has_host() {
        val headers = Header.fromFields(listOf(f(":method", "GET"), f("host", "test.com")))
        assertNull(headers.pseudo.authority)
        headers.intoRequestParts()
    }

    @Test
    fun request_has_same_host_and_authority() {
        Header.fromFields(listOf(f(":method", "GET"), f(":authority", "test.com"), f("host", "test.com"))).intoRequestParts()
    }

    @Test
    fun request_has_different_host_and_authority() {
        val headers = Header.fromFields(listOf(f(":method", "GET"), f(":authority", "authority.com"), f("host", "host.com")))
        assertEquals(HeaderError.ContradictedAuthority, assertFailsWith<HeaderException> { headers.intoRequestParts() }.error)
    }

    @Test
    fun preserves_duplicate_headers() {
        val headers = Header.fromFields(
            listOf(
                f(":method", "GET"), f(":authority", "test.com"), f("set-cookie", "foo=foo"), f("set-cookie", "bar=bar"),
                f("other-header", "other-header-value"),
            ),
        )
        val lines = headers.fieldLines()
        assertEquals(listOf(f("set-cookie", "foo=foo"), f("set-cookie", "bar=bar")), lines.filter { it.name.decodeToString() == "set-cookie" })
        assertEquals(listOf(f("other-header", "other-header-value")), lines.filter { it.name.decodeToString() == "other-header" })
    }

    // ---- ⚖️ 1. duplicate pseudo-headers ----

    @Test
    fun duplicatePseudoHeadersAreMalformed() {
        val values = mapOf(
            ":method" to "GET", ":scheme" to "https", ":authority" to "a.com", ":path" to "/", ":status" to "200",
            ":protocol" to "connect-udp",
        )
        for ((name, value) in values) {
            val e = assertFailsWith<HeaderException> { Header.fromFields(listOf(f(name, value), f(name, value))) }
            assertEquals(HeaderError.DuplicatePseudo(name), e.error)
            assertEquals(Code.H3_MESSAGE_ERROR, e.code)
        }
    }

    // ---- ⚖️ 2. pseudo-headers after regular fields ----

    @Test
    fun pseudoHeaderAfterARegularFieldIsMalformed() {
        assertEquals(HeaderError.PseudoAfterRegular(":path"), headerError(f(":method", "GET"), f("accept", "*/*"), f(":path", "/")))
        assertEquals(HeaderError.PseudoAfterRegular(":status"), headerError(f("server", "x"), f(":status", "200")))
    }

    // ---- ⚖️ 3. connection-specific fields ----

    @Test
    fun connectionSpecificFieldsAreMalformed() {
        for ((name, value) in listOf(
            "connection" to "close", "keep-alive" to "timeout=5", "proxy-connection" to "keep-alive",
            "transfer-encoding" to "chunked", "upgrade" to "h2c", "te" to "gzip", "te" to "trailers, gzip",
        )) {
            val e = assertFailsWith<HeaderException> { Header.fromFields(listOf(f(":status", "200"), f(name, value))) }
            assertEquals(HeaderError.ConnectionSpecific(name), e.error)
            assertEquals(Code.H3_MESSAGE_ERROR, e.code)
        }
        // `te: trailers` is allowed (RFC 9114 §4.2).
        Header.fromFields(listOf(f(":method", "GET"), f("te", "trailers")))
    }

    // ---- ⚖️ 4. content-length ----

    @Test
    fun contentLengthMustBeANumberAndConsistent() {
        assertEquals(10UL, Header.fromFields(listOf(f(":status", "200"), f("content-length", "10"))).contentLength)
        assertEquals(10UL, Header.fromFields(listOf(f(":status", "200"), f("content-length", "10"), f("content-length", "10"))).contentLength)
        assertNull(Header.fromFields(listOf(f(":status", "200"))).contentLength)
        // As the HTTP/2 decoder's `parse_u64`: at most 19 digits.
        assertEquals(9999999999999999999UL, Header.fromFields(listOf(f("content-length", "9999999999999999999"))).contentLength)
        for (bad in listOf("abc", "-1", "1 0", "", "+5", "0x10", "12345678901234567890")) {
            val e = assertFailsWith<HeaderException> { Header.fromFields(listOf(f(":status", "200"), f("content-length", bad))) }
            assertEquals(HeaderError.InvalidContentLength, e.error, "content-length '$bad'")
            assertEquals(Code.H3_MESSAGE_ERROR, e.code)
        }
        assertEquals(HeaderError.InvalidContentLength, headerError(f("content-length", "10"), f("content-length", "11")))
    }

    // ---- The reference's other checks ----

    @Test
    fun invalidNamesAndValues() {
        assertEquals(HeaderError.InvalidHeaderName("name is empty"), headerError(HeaderField(ByteArray(0), ByteArray(0))))
        // RFC 9114 §4.2: uppercase names are malformed.
        assertIs<HeaderError.InvalidHeaderName>(headerError(f("Content-Type", "text/plain")))
        assertIs<HeaderError.InvalidHeaderName>(headerError(f("bad name", "x")))
        assertIs<HeaderError.InvalidHeaderValue>(headerError(f("x", "a\nb")))
        assertIs<HeaderError.InvalidHeaderName>(headerError(f(":unknown", "x")))
        assertIs<HeaderError.InvalidHeaderValue>(headerError(f(":status", "20")))
        assertIs<HeaderError.InvalidHeaderValue>(headerError(f(":method", "G ET")))
        assertIs<HeaderError.InvalidHeaderValue>(headerError(f(":path", "")))
        assertIs<HeaderError.InvalidHeaderValue>(headerError(f(":protocol", "websocket")))
        assertEquals(HeaderError.MissingStatus, assertFailsWith<HeaderException> { Header.fromFields(emptyList()).intoResponseParts() }.error)
        assertEquals(
            HeaderError.MissingMethod,
            assertFailsWith<HeaderException> { Header.fromFields(listOf(f(":authority", "a.com"))).intoRequestParts() }.error,
        )
    }

    @Test
    fun requestConstruction() {
        assertEquals(
            HeaderError.MissingAuthority,
            assertFailsWith<HeaderException> { Header.request(Method.GET, Uri.parse("/x"), HeaderMap()) }.error,
        )
        assertEquals(
            HeaderError.ContradictedAuthority,
            assertFailsWith<HeaderException> { Header.request(Method.GET, Uri.parse("https://a.com/"), map("host" to "b.com")) }.error,
        )
        val h = Header.request(Method.GET, Uri.parse("http://a.com"), HeaderMap())
        assertEquals("/", h.pseudo.path!!.asStr())
        assertEquals(Scheme.HTTP, h.pseudo.scheme)
        assertEquals(4, h.pseudo.len)
        val noScheme = Header.request(Method.GET, Uri.parse("/p?q=1"), map("host" to "a.com"))
        assertEquals(Scheme.HTTPS, noScheme.pseudo.scheme)
        assertEquals(3, noScheme.pseudo.len)
        // `:protocol` only with CONNECT.
        assertEquals(Protocol.CONNECT_UDP, Header.request(Method.CONNECT, Uri.parse("https://a.com/"), HeaderMap(), Protocol.CONNECT_UDP).pseudo.protocol)
        assertNull(Header.request(Method.GET, Uri.parse("https://a.com/"), HeaderMap(), Protocol.CONNECT_UDP).pseudo.protocol)
    }

    // ---- QPACK round trips ----

    private fun roundTrip(h: Header): Header {
        val block = Buffer()
        val size = h.encode(Encoder(), block)
        val bytes = block.readAll()
        val decoder = Decoder()
        val decoded = Header.decode(bytes, 0, bytes.size, decoder)
        assertEquals(h, decoded)
        assertEquals(size, decoder.memSize)
        return decoded
    }

    @Test
    fun requestResponseAndTrailerRoundTrip() {
        val req = roundTrip(
            Header.request(
                Method.POST, Uri.parse("https://example.com:8443/upload?x=1"),
                map("content-type" to "application/json", "x-custom" to "a", "x-custom" to "b", "accept" to "*/*", "content-length" to "3"),
            ),
        )
        val parts = req.intoRequestParts()
        assertEquals(Method.POST, parts.method)
        assertEquals("https://example.com:8443/upload?x=1", parts.uri.toString())
        assertEquals(3UL, req.contentLength)
        roundTrip(Header.request(Method.fromStr("PURGE"), Uri.parse("http://h/"), HeaderMap()))
        roundTrip(Header.request(Method.CONNECT, Uri.parse("https://proxy:443/"), HeaderMap(), Protocol.CONNECT_UDP))
        val res = roundTrip(Header.response(StatusCode.OK, map("server" to "neton", "date" to "Tue, 29 Sep 2026 00:00:00 GMT")))
        assertEquals(StatusCode.OK, res.intoResponseParts().first)
        roundTrip(Header.response(StatusCode.fromU16(299), HeaderMap()))
        roundTrip(Header.trailer(map("grpc-status" to "0")))
    }

    @Test
    fun staticEntriesAreIndexed() {
        val block = Buffer()
        Header.request(Method.GET, Uri.parse("https://a.com/"), map("accept" to "*/*")).encode(Encoder(), block)
        block.skip(2)
        assertEquals(Indexed.Static(17), Indexed.decode(block)) // :method GET
        assertEquals(Indexed.Static(23), Indexed.decode(block)) // :scheme https
        block.skip(block.readableBytes)
        val res = Buffer()
        Header.response(StatusCode.NOT_FOUND, HeaderMap()).encode(Encoder(), res)
        res.skip(2)
        assertEquals(Indexed.Static(StaticTable.find(f(":status", "404"))!!.toLong()), Indexed.decode(res))
        assertTrue(res.isEmpty)
    }

    @Test
    fun validationStopsDecodingAtTheFirstInvalidLine() {
        // A duplicate pseudo-header followed by a line that would fail to decode: the header error is reported.
        val block = Buffer()
        Encoder().encodeStateless(block, listOf(f(":status", "200"), f(":status", "204")))
        Indexed.Static(1000).encode(block)
        val bytes = block.readAll()
        val e = assertFailsWith<HeaderException> { Header.decode(bytes, 0, bytes.size, Decoder()) }
        assertEquals(HeaderError.DuplicatePseudo(":status"), e.error)
    }

    @Test
    fun decoderLimitsApplyToHeaderDecoding() {
        val many = HeaderMap<HeaderValue>()
        repeat(101) { many.append(HeaderName.fromStr("x-$it"), HeaderValue.fromStr("v")) }
        val block = Buffer()
        Header.response(StatusCode.OK, many).encode(Encoder(), block)
        val bytes = block.readAll()
        // :status and 99 fields fit the default 100; the 101st line is refused.
        val e = assertFailsWith<DecoderException> { Header.decode(bytes, 0, bytes.size, Decoder()) }
        assertEquals(DecoderError.TooManyFields(101), e.error)
        val big = Buffer()
        Header.response(StatusCode.OK, map("x-big" to "v".repeat(70_000))).encode(Encoder(), big)
        val bigBytes = big.readAll()
        assertIs<DecoderError.HeaderTooLong>(assertFailsWith<DecoderException> { Header.decode(bigBytes, 0, bigBytes.size, Decoder()) }.error)
    }
}
