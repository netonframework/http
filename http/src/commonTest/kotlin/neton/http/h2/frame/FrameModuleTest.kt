package neton.http.h2.frame

import neton.http.Method
import neton.http.h2.hpack.Decoder
import neton.http.h2.hpack.Encoder
import neton.http.h2.hpack.huffDecode
import neton.http.header.HeaderMap
import neton.http.header.HeaderName
import neton.http.header.HeaderValue
import neton.http.uri.Uri
import neton.io.bytes.Buffer
import neton.io.bytes.Bytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The module tests of h2 0.4.19 `src/frame`: mod.rs (1), data.rs (4), headers.rs (6).

class FrameModTest {
    @Test
    fun testUnpackOctets4() {
        val buf = byteArrayOf(0, 0, 0, 1)
        assertEquals(1, unpackOctets4(buf, 0))
    }
}

class DataTest {
    private fun dataFrame(data: ByteArray, padLen: Int?): Data =
        Data(StreamId(1), Bytes.copyOf(data), 0, padLen ?: -1)

    @Test
    fun paddingOverheadNoPadding() {
        val frame = dataFrame("hello".encodeToByteArray(), null)
        assertEquals(0, frame.flowControlledLen() - frame.payload.size)
    }

    @Test
    fun paddingOverheadSmall() {
        val frame = dataFrame("hello".encodeToByteArray(), 10)
        assertEquals(11, frame.flowControlledLen() - frame.payload.size)
    }

    /** Regression in the reference: a u8 padded length overflowed for pad_len = 255; the overhead is 256. */
    @Test
    fun paddingOverheadMaxDoesNotOverflow() {
        val frame = dataFrame(ByteArray(0), 255)
        assertEquals(256, frame.flowControlledLen() - frame.payload.size)
    }

    @Test
    fun paddingOverheadMaxWithData() {
        val frame = dataFrame("hello".encodeToByteArray(), 255)
        assertEquals(256, frame.flowControlledLen() - frame.payload.size)
        assertEquals(261, frame.flowControlledLen())
    }
}

class HeadersTest {
    private fun map(vararg pairs: Pair<String, String>): HeaderMap<HeaderValue> {
        val m = HeaderMap<HeaderValue>()
        for ((k, v) in pairs) m.append(HeaderName.fromStr(k), HeaderValue.fromStr(v))
        return m
    }

    private fun bytes(vararg b: Int) = b.map { it.toByte() }

    @Test
    fun testNamelessHeaderAtResume() {
        val encoder = Encoder()
        val dst = Buffer()

        val headers = Headers(
            StreamId.ZERO,
            Pseudo(),
            map("hello" to "world", "hello" to "zomg", "hello" to "sup"),
        )

        val continuation = headers.encode(encoder, dst, HEADER_LEN + 8)!!

        var out = dst.peekAll()
        assertEquals(17, out.size)
        assertEquals(bytes(0, 0, 8, 1, 0, 0, 0, 0, 0), out.copyOfRange(0, 9).toList())
        assertEquals(bytes(0x40, 0x80 or 4), out.copyOfRange(9, 11).toList())
        assertEquals("hello", huffDecode(out, 11, 15))
        assertEquals((0x80 or 4).toByte(), out[15])

        val world = out.copyOfRange(16, 17)

        dst.clear()

        assertNull(continuation.encode(dst, HEADER_LEN + 16))
        out = dst.peekAll()

        assertEquals("world", huffDecode(world + out.copyOfRange(9, 12), 0))

        assertEquals(24, out.size)
        assertEquals(bytes(0, 0, 15, 9, 4, 0, 0, 0, 0), out.copyOfRange(0, 9).toList())

        // Next is not indexed
        assertEquals(bytes(15, 47, 0x80 or 3), out.copyOfRange(12, 15).toList())
        assertEquals("zomg", huffDecode(out, 15, 18))
        assertEquals(bytes(15, 47, 0x80 or 3), out.copyOfRange(18, 21).toList())
        assertEquals("sup", huffDecode(out, 21))
    }

    /** CONNECT requests MUST NOT include :scheme and :path (RFC 9113 §8.5). */
    @Test
    fun testConnectRequestPseudoHeadersOmitsPathAndScheme() {
        assertEquals(
            Pseudo(method = Method.CONNECT, authority = "example.com:8443"),
            Pseudo.request(Method.CONNECT, Uri.fromStatic("https://example.com:8443"), null),
        )
        assertEquals(
            Pseudo(method = Method.CONNECT, authority = "example.com"),
            Pseudo.request(Method.CONNECT, Uri.fromStatic("https://example.com/test"), null),
        )
        assertEquals(
            Pseudo(method = Method.CONNECT, authority = "example.com:8443"),
            Pseudo.request(Method.CONNECT, Uri.fromStatic("example.com:8443"), null),
        )
    }

    /** With :protocol, :scheme and :path MUST be included (RFC 8441 §4). */
    @Test
    fun testExtendedConnectRequestPseudoHeadersIncludesPathAndScheme() {
        assertEquals(
            Pseudo(
                method = Method.CONNECT, authority = "example.com:8443", scheme = "https", path = "/",
                protocol = "the-bread-protocol",
            ),
            Pseudo.request(Method.CONNECT, Uri.fromStatic("https://example.com:8443"), "the-bread-protocol"),
        )
        assertEquals(
            Pseudo(
                method = Method.CONNECT, authority = "example.com:8443", scheme = "https", path = "/test",
                protocol = "the-bread-protocol",
            ),
            Pseudo.request(Method.CONNECT, Uri.fromStatic("https://example.com:8443/test"), "the-bread-protocol"),
        )
        assertEquals(
            Pseudo(
                method = Method.CONNECT, authority = "example.com", scheme = "http", path = "/a/b/c",
                protocol = "the-bread-protocol",
            ),
            Pseudo.request(Method.CONNECT, Uri.fromStatic("http://example.com/a/b/c"), "the-bread-protocol"),
        )
    }

    /** An OPTIONS request without a path has `:path *` (RFC 9113 §8.3.1). */
    @Test
    fun testOptionsRequestWithEmptyPathHasAsteriskAsPseudoPath() {
        assertEquals(
            Pseudo(method = Method.OPTIONS, authority = "example.com:8080", path = "*"),
            Pseudo.request(Method.OPTIONS, Uri.fromStatic("example.com:8080"), null),
        )
    }

    /**
     * Decoding more than 24,576 unique headers (the header map's capacity) sets `isOverSize` instead of crashing. The
     * HPACK block is built by hand, as a header map that large cannot be constructed either.
     */
    @Test
    fun testTryAppendPreventsPanicOnMaxSizeReached() {
        val numHeaders = 25_000
        val hpack = ArrayList<Byte>()
        fun push(b: Int) = hpack.add(b.toByte())
        push(0x82) // :method GET (static index 2)
        push(0x86) // :scheme http (static index 6)
        push(0x84) // :path / (static index 4)
        // :authority "localhost": literal with incremental indexing, name index 1.
        push(0x41)
        push(0x09)
        "localhost".encodeToByteArray().forEach { hpack.add(it) }
        // 25,000 unique headers: literal without indexing, new name.
        for (i in 0 until numHeaders) {
            val name = "x-h-$i".encodeToByteArray()
            push(0x00)
            push(name.size)
            name.forEach { hpack.add(it) }
            push(1)
            push('v'.code)
        }

        val payloadLen = hpack.size
        val frame = Buffer(9 + payloadLen)
        frame.writeByte((payloadLen shr 16).toByte())
        frame.writeByte((payloadLen shr 8).toByte())
        frame.writeByte(payloadLen.toByte())
        frame.writeByte(0x01) // HEADERS
        frame.writeByte(0x04) // END_HEADERS
        frame.writeInt(1)
        frame.writeBytes(hpack.toByteArray())

        val bytes = frame.peekAll()
        val head = Head.parse(bytes, 0)
        val window = ByteWindow().apply { set(bytes, 9, bytes.size) }
        val headers = Headers.load(head, window)

        val decoder = Decoder(4096)
        val defaultMaxHeaderListSize = 16 shl 20
        headers.loadHpack(window, defaultMaxHeaderListSize, decoder) // must not throw

        assertTrue(headers.isOverSize, "isOverSize should be true when the header map capacity is exceeded")
    }

    @Test
    fun testNonOptionAndNonConnectRequestsIncludePathAndScheme() {
        val methods = listOf(Method.GET, Method.POST, Method.PUT, Method.DELETE, Method.HEAD, Method.PATCH, Method.TRACE)
        for (method in methods) {
            assertEquals(
                Pseudo(method = method, authority = "example.com:8080", scheme = "http", path = "/"),
                Pseudo.request(method, Uri.fromStatic("http://example.com:8080"), null),
            )
            assertEquals(
                Pseudo(method = method, authority = "example.com", scheme = "https", path = "/a/b/c"),
                Pseudo.request(method, Uri.fromStatic("https://example.com/a/b/c"), null),
            )
        }
    }
}
