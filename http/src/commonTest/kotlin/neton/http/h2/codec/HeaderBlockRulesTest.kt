package neton.http.h2.codec

import neton.http.Method
import neton.http.Request
import neton.http.StatusCode
import neton.http.h2.frame.Headers
import neton.http.h2.frame.PushPromise
import neton.http.h2.frame.PushPromiseHeaderError
import neton.http.h2.frame.Reason
import neton.http.h2.frame.parseU64
import neton.http.h2.hpack.Encoder
import neton.http.h2.hpack.Header
import neton.http.h2.proto.ProtoError
import neton.http.h2.raw
import neton.http.header.HeaderName
import neton.http.header.HeaderValue
import neton.io.bytes.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// SPEC §4.1 "头部块校验": pseudo-headers after regular ones or repeated, connection-specific headers, `te`, HPACK always
// decoding the whole block, and the header list size (32 + name + value) with its two thresholds.

class HeaderBlockRulesTest {
    private fun field(name: String, value: String) = Header.Field(HeaderName.fromStr(name), HeaderValue.fromStr(value))

    private val get = Header.Method(Method.GET)

    private fun headers(stream: Int, block: ByteArray, flags: Int = 0x4) = frame(1, flags, stream, block)

    // ===== pseudo-headers =====

    @Test
    fun pseudoAfterRegularIsMalformed() {
        assertReset(headers(1, hpack(get, field("a", "b"), Header.Path("/"))), 1)
        assertReset(headers(1, hpack(field("a", "b"), Header.Status(StatusCode.OK))), 1)
    }

    @Test
    fun repeatedPseudoIsMalformed() {
        val pairs = listOf(
            Header.Method(Method.GET) to Header.Method(Method.POST),
            Header.Scheme("https") to Header.Scheme("http"),
            Header.Authority("a") to Header.Authority("b"),
            Header.Path("/") to Header.Path("/x"),
            Header.Protocol("websocket") to Header.Protocol("websocket"),
            Header.Status(StatusCode.OK) to Header.Status(StatusCode.OK),
        )
        for ((first, second) in pairs) assertReset(headers(3, hpack(first, second)), 3)
    }

    @Test
    fun pseudoHeadersAreParsed() {
        val h = assertIs<Headers>(
            readAll(
                headers(
                    1,
                    hpack(get, Header.Scheme("https"), Header.Authority("example.com"), Header.Path("/p?q"), Header.Protocol("websocket")),
                ),
            ).single(),
        )
        assertEquals(Method.GET, h.pseudo.method)
        assertEquals("https", h.pseudo.scheme)
        assertEquals("example.com", h.pseudo.authority)
        assertEquals("/p?q", h.pseudo.path)
        assertEquals("websocket", h.pseudo.protocol)
        assertNull(h.pseudo.status)
    }

    // ===== connection-specific headers =====

    @Test
    fun connectionSpecificHeadersAreMalformed() {
        for (name in listOf("connection", "transfer-encoding", "upgrade", "keep-alive", "proxy-connection")) {
            assertReset(headers(1, hpack(get, field(name, "x"))), 1)
        }
    }

    @Test
    fun teTrailersIsAllowed() {
        val h = assertIs<Headers>(readAll(headers(1, hpack(get, field("te", "trailers")))).single())
        assertTrue(h.fields[HeaderName.TE]!!.contentEquals("trailers"))
    }

    @Test
    fun teOtherThanTrailersIsMalformed() {
        for (value in listOf("gzip", "trailers, deflate", "Trailers", "")) {
            assertReset(headers(1, hpack(get, field("te", value))), 1)
        }
    }

    // ===== HPACK always decodes the whole block =====

    @Test
    fun hpackStaysInSyncPastAMalformedBlock() {
        val encoder = Encoder()
        val first = hpack(get, field("connection", "close"), field("x-a", "1"), encoder = encoder)
        val second = hpack(get, field("x-a", "1"), encoder = encoder)
        // The second block refers to the dynamic table entry the first one inserted after the malformed field.
        assertEquals(listOf(0x82, 0xbe), second.map { it.toInt() and 0xff })
        val bytes = raw(headers(1, first), headers(3, second))
        val reader = FramedRead()
        val buf = Buffer().also { it.writeBytes(bytes) }
        assertFailsWith<ProtoError.Reset> { reader.decode(buf) }
        val h = assertIs<Headers>(reader.decode(buf))
        assertEquals("1", h.fields["x-a"]!!.toStr())
    }

    @Test
    fun representationSplitAcrossContinuationIsDecoded() {
        val block = hpack(
            get, Header.Scheme("https"), Header.Path("/a/long/path"), field("x-one", "first value"),
            field("x-two", "second value"),
        )
        val whole = readAll(headers(1, block)).single()
        for (cut in 0..block.size) {
            val split = raw(
                frame(1, 0, 1, block.copyOfRange(0, cut)),
                frame(9, 0x4, 1, block.copyOfRange(cut, block.size)),
            )
            assertEquals(whole, readAll(split).single(), "cut at $cut")
        }
        for (cut in 0 until block.size - 1) {
            val split = raw(
                frame(1, 0, 1, block.copyOfRange(0, cut)),
                frame(9, 0, 1, block.copyOfRange(cut, cut + 1)),
                frame(9, 0x4, 1, block.copyOfRange(cut + 1, block.size)),
            )
            assertEquals(whole, readAll(split).single(), "cuts at $cut, ${cut + 1}")
        }
    }

    /** ⚖️ The reference resets the stream at the HEADERS frame and then fails the connection on the CONTINUATION. */
    @Test
    fun malformedFirstFragmentResetsTheStreamAtEndHeaders() {
        val encoder = Encoder()
        val bytes = raw(
            frame(1, 0, 1, hpack(get, field("connection", "close"), encoder = encoder)),
            frame(9, 0x4, 1, hpack(field("x-b", "2"), encoder = encoder)),
            headers(3, hpack(get, field("x-b", "2"), encoder = encoder)),
        )
        val reader = FramedRead()
        val buf = Buffer().also { it.writeBytes(bytes) }
        val e = assertFailsWith<ProtoError.Reset> { reader.decode(buf) }
        assertEquals(1, e.streamId.value)
        // Nothing of stream 1 is left, and the HPACK tables are in sync.
        val h = assertIs<Headers>(reader.decode(buf))
        assertEquals(3, h.streamId.value)
        assertEquals("2", h.fields["x-b"]!!.toStr())
    }

    /** ⚖️ The reference forgets a violation in a fragment that ends inside a representation. */
    @Test
    fun malformedHeaderBeforeASplitRepresentationIsNotLost() {
        val block = hpack(get, field("connection", "close"), field("x-long", "v".repeat(50)))
        val cut = block.size - 10
        assertReset(raw(frame(1, 0, 1, block.copyOfRange(0, cut)), frame(9, 0x4, 1, block.copyOfRange(cut, block.size))), 1)
    }

    @Test
    fun hpackErrorIsConnectionError() {
        // Index 62 with an empty dynamic table.
        assertGoAway(headers(1, raw(0xbe)), Reason.PROTOCOL_ERROR)
    }

    @Test
    fun incompleteBlockAtEndHeadersIsConnectionError() {
        val block = hpack(get, field("x-a", "value"))
        assertGoAway(headers(1, block.copyOf(block.size - 1)), Reason.PROTOCOL_ERROR)
    }

    // ===== header list size =====

    @Test
    fun headerListSizeCountsNamePlusValuePlus32() {
        // :method GET = 7 + 3 + 32 = 42, a: bc = 1 + 2 + 32 = 35; 77 in all.
        val bytes = headers(1, hpack(get, field("a", "bc")))
        val under = assertIs<Headers>(readAll(bytes, FramedRead().apply { maxHeaderListSize = 78 }).single())
        assertFalse(under.isOverSize)
        assertEquals(1, under.fields.len())

        // Reaching the limit is over size; the field that reaches it is dropped.
        val at = assertIs<Headers>(readAll(bytes, FramedRead().apply { maxHeaderListSize = 77 }).single())
        assertTrue(at.isOverSize)
        assertEquals(Method.GET, at.pseudo.method)
        assertTrue(at.fields.isEmpty())
    }

    @Test
    fun overSizeBlockIsDecodedAndFlagged() {
        // The frame is still returned (the server answers 431 and then REFUSED_STREAM) and the tables stay in sync.
        val encoder = Encoder()
        val bytes = raw(
            headers(1, hpack(get, field("x-a", "a".repeat(60)), field("x-b", "b".repeat(60)), encoder = encoder)),
            headers(3, hpack(get, field("x-b", "b".repeat(60)), encoder = encoder)),
        )
        val frames = readAll(bytes, FramedRead().apply { maxHeaderListSize = 150 })
        val first = assertIs<Headers>(frames[0])
        assertTrue(first.isOverSize)
        assertEquals(1, first.fields.len())
        val second = assertIs<Headers>(frames[1])
        assertFalse(second.isOverSize)
        assertEquals("b".repeat(60), second.fields["x-b"]!!.toStr())
    }

    @Test
    fun overFourTimesTheLimitIsGoAwayEnhanceYourCalm() {
        // Each field is 1 + 67 + 32 = 100 bytes; with a limit of 100, four of them are 4x the limit: over size only.
        val four = List(4) { field("a", "x".repeat(67)) }.toTypedArray()
        val ok = assertIs<Headers>(readAll(headers(1, hpack(*four)), FramedRead().apply { maxHeaderListSize = 100 }).single())
        assertTrue(ok.isOverSize)

        val more = (four.toList() + field("b", "")).toTypedArray()
        assertGoAway(
            headers(1, hpack(*more)), Reason.ENHANCE_YOUR_CALM, "header_list_way_too_large",
            FramedRead().apply { maxHeaderListSize = 100 },
        )
    }

    @Test
    fun overSizeBlockWithAGiganticSplitStringIsCompressionError() {
        // A literal whose value (1000 bytes) spans CONTINUATION frames, after the block is already over size.
        val start = raw(hpack(field("a", "x".repeat(67))), 0x00, 1, 'b'.code, 0x7f, 0xe9, 0x06, "yyyy")
        val bytes = raw(frame(1, 0, 1, start), frame(9, 0, 1, "z".repeat(200)))
        assertGoAway(bytes, Reason.COMPRESSION_ERROR, reader = FramedRead().apply { maxHeaderListSize = 100 })
    }

    // ===== trailers, push promises =====

    @Test
    fun trailersHaveNoPseudoHeaders() {
        val h = assertIs<Headers>(readAll(headers(1, hpack(field("grpc-status", "0")), 0x5)).single())
        assertTrue(h.isEndStream)
        assertEquals(neton.http.h2.frame.Pseudo(), h.pseudo)
        assertEquals("0", h.fields["grpc-status"]!!.toStr())
    }

    @Test
    fun pushPromiseValidateRequest() {
        fun req(method: Method, contentLength: String?): Request<Unit> {
            val r = Request(Unit)
            r.method = method
            if (contentLength != null) r.headers.insert(HeaderName.CONTENT_LENGTH, HeaderValue.fromStr(contentLength))
            return r
        }
        assertNull(PushPromise.validateRequest(req(Method.GET, null)))
        assertNull(PushPromise.validateRequest(req(Method.HEAD, "0")))
        assertEquals(PushPromiseHeaderError.NotSafeAndCacheable, PushPromise.validateRequest(req(Method.POST, null)))
        assertEquals(PushPromiseHeaderError.InvalidContentLength(5UL), PushPromise.validateRequest(req(Method.GET, "5")))
        assertEquals(PushPromiseHeaderError.InvalidContentLength(null), PushPromise.validateRequest(req(Method.GET, "x")))
    }

    @Test
    fun parseU64Rules() {
        assertEquals(0UL, parseU64(ByteArray(0)))
        assertEquals(42UL, parseU64("42".encodeToByteArray()))
        assertEquals(9_999_999_999_999_999_999UL, parseU64("9999999999999999999".encodeToByteArray()))
        assertNull(parseU64("10000000000000000000".encodeToByteArray()))
        assertNull(parseU64("-1".encodeToByteArray()))
        assertNull(parseU64("1 ".encodeToByteArray()))
    }
}
