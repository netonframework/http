package neton.http.h1

import neton.io.bytes.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The 17 tests of hyper 1.11.1 `src/proto/h1/decode.rs`, one each. hyper's mock readers (`&[u8]`, `Bytes`, and the
 * `tokio_test` reader that blocks at a given byte) become a read buffer fed incrementally: "the reader has no more
 * bytes" is `eof = true`, "the reader would block" is [DecodeResult.NEED_MORE].
 */
class BodyDecoderTest {

    /** `test_read_chunk_size`: drives the size-line states only, stopping at `Body` / `EndCr` as the reference. */
    @Test
    fun testReadChunkSize() {
        // The reference loop stops once the state is Body or EndCr; with no more input the decoder asks for more
        // right there. Checked with the line in one piece and byte by byte.
        fun read(s: String): ULong {
            val whole = BodyDecoder.chunked()
            assertEquals(DecodeResult.NEED_MORE, whole.decode(bufferOf(s), eof = false), "read_size failed for $s")
            assertTrue(whole.state == ChunkedState.BODY || whole.state == ChunkedState.END_CR, "state ${whole.state} for $s")

            val bytewise = BodyDecoder.chunked()
            val buf = Buffer()
            for (c in s) {
                buf.writeByte(c.code.toByte())
                assertEquals(DecodeResult.NEED_MORE, bytewise.decode(buf, eof = false), "read_size failed for $s")
            }
            assertEquals(whole.state, bytewise.state)
            assertEquals(whole.chunkLen, bytewise.chunkLen)
            return whole.chunkLen
        }

        fun readErr(s: String, expected: BodyErrorKind) {
            val d = BodyDecoder.chunked()
            val r = d.decode(bufferOf(s), eof = true)
            assertEquals(DecodeResult.ERROR, r, "Was Ok. Expected Err for $s")
            assertTrue(d.state != ChunkedState.BODY && d.state != ChunkedState.END, "reached ${d.state} for $s")
            assertEquals(expected, d.error!!.kind, "Reading $s, expected $expected, but got ${d.error}")

            // Byte by byte, EOF only after the last byte.
            val b = BodyDecoder.chunked()
            val buf = Buffer()
            var result = DecodeResult.NEED_MORE
            for ((i, c) in s.withIndex()) {
                buf.writeByte(c.code.toByte())
                result = b.decode(buf, eof = i == s.length - 1)
                if (result != DecodeResult.NEED_MORE) break
            }
            assertEquals(DecodeResult.ERROR, result, "bytewise: $s")
            assertEquals(d.error, b.error, "bytewise: $s")
        }

        assertEquals(1uL, read("1\r\n"))
        assertEquals(1uL, read("01\r\n"))
        assertEquals(0uL, read("0\r\n"))
        assertEquals(0uL, read("00\r\n"))
        assertEquals(10uL, read("A\r\n"))
        assertEquals(10uL, read("a\r\n"))
        assertEquals(255uL, read("Ff\r\n"))
        assertEquals(255uL, read("Ff   \r\n"))
        // Missing LF or CRLF
        readErr("F\rF", BodyErrorKind.INVALID_INPUT)
        readErr("F", BodyErrorKind.UNEXPECTED_EOF)
        // Missing digit
        readErr("\r\n\r\n", BodyErrorKind.INVALID_INPUT)
        readErr("\r\n", BodyErrorKind.INVALID_INPUT)
        // Invalid hex digit
        readErr("X\r\n", BodyErrorKind.INVALID_INPUT)
        readErr("1X\r\n", BodyErrorKind.INVALID_INPUT)
        readErr("-\r\n", BodyErrorKind.INVALID_INPUT)
        readErr("-1\r\n", BodyErrorKind.INVALID_INPUT)
        // Acceptable (if not fully valid) extensions do not influence the size
        assertEquals(1uL, read("1;extension\r\n"))
        assertEquals(10uL, read("a;ext name=value\r\n"))
        assertEquals(1uL, read("1;extension;extension2\r\n"))
        assertEquals(1uL, read("1;;;  ;\r\n"))
        assertEquals(2uL, read("2; extension...\r\n"))
        assertEquals(3uL, read("3   ; extension=123\r\n"))
        assertEquals(3uL, read("3   ;\r\n"))
        assertEquals(3uL, read("3   ;   \r\n"))
        // Invalid extensions cause an error
        readErr("1 invalid extension\r\n", BodyErrorKind.INVALID_INPUT)
        readErr("1 A\r\n", BodyErrorKind.INVALID_INPUT)
        readErr("1;no CRLF", BodyErrorKind.UNEXPECTED_EOF)
        readErr("1;reject\nnewlines\r\n", BodyErrorKind.INVALID_DATA)
        // Overflow (here stopped one digit earlier by the 16-digit limit, same kind)
        readErr("f0000000000000003\r\n", BodyErrorKind.INVALID_DATA)
    }

    @Test
    fun testReadSizedEarlyEof() {
        val bytes = bufferOf("foo bar")
        val decoder = BodyDecoder.length(10)
        assertEquals(7, decoder.decodeFrame(bytes).data().bytes.size)
        val e = decoder.decodeFrame(bytes).err()
        assertEquals(BodyErrorKind.UNEXPECTED_EOF, e.kind)
        assertEquals(BodyDecodeError.INCOMPLETE_BODY, e)
    }

    @Test
    fun testReadChunkedEarlyEof() {
        val bytes = bufferOf("9\r\nfoo bar")
        val decoder = BodyDecoder.chunked()
        assertEquals(7, decoder.decodeFrame(bytes).data().bytes.size)
        val e = decoder.decodeFrame(bytes).err()
        assertEquals(BodyErrorKind.UNEXPECTED_EOF, e.kind)
    }

    @Test
    fun testReadChunkedSingleRead() {
        val mockBuf = bufferOf("10\r\n1234567890abcdef\r\n0\r\n")
        val buf = BodyDecoder.chunked().decodeFrame(mockBuf).data()
        assertEquals(16, buf.bytes.size)
        assertEquals("1234567890abcdef", buf.text)
    }

    @Test
    fun testReadChunkedWithMissingZeroDigit() {
        // After reading a valid chunk, the ending is missing a zero.
        val mockBuf = bufferOf("1\r\nZ\r\n\r\n\r\n")
        val decoder = BodyDecoder.chunked()
        assertEquals("Z", decoder.decodeFrame(mockBuf).data().text)
        val err = decoder.decodeFrame(mockBuf).err()
        assertEquals(BodyErrorKind.INVALID_INPUT, err.kind)
    }

    /**
     * `test_read_chunked_extensions_over_limit`, verbatim: two chunks with 10,922 extension bytes each. The
     * reference input needs the per-line limit (safety baseline, 1 KiB) lifted; the same 16 KiB whole-body limit
     * under the default line limit is covered by [BodyBaselineTest.extensionsLimitIsCumulativeUnderLineLimit].
     */
    @Test
    fun testReadChunkedExtensionsOverLimit() {
        // construct a chunked body where each individual chunked extension is totally fine, but combined is over
        // the limit.
        val perChunk = (BodyDecoder.CHUNKED_EXTENSIONS_LIMIT * 2 / 3).toInt()
        val scratch = StringBuilder()
        repeat(2) {
            scratch.append("1;")
            scratch.append("x".repeat(perChunk))
            scratch.append("\r\nA\r\n")
        }
        scratch.append("0\r\n\r\n")
        val mockBuf = bufferOf(scratch.toString())

        val decoder = BodyDecoder.chunked(maxChunkSizeLine = Int.MAX_VALUE)
        assertEquals("A", decoder.decodeFrame(mockBuf).data().text)

        val err = decoder.decodeFrame(mockBuf).err()
        assertEquals(BodyErrorKind.INVALID_DATA, err.kind)
        assertEquals("chunk extensions over limit", err.message)
    }

    @Test
    fun testReadChunkedTrailerWithMissingLf() {
        val mockBuf = bufferOf("10\r\n1234567890abcdef\r\n0\r\nbad\r\r\n")
        val decoder = BodyDecoder.chunked()
        decoder.decodeFrame(mockBuf).data()
        val e = decoder.decodeFrame(mockBuf).err()
        assertEquals(BodyErrorKind.INVALID_INPUT, e.kind)
    }

    @Test
    fun testReadChunkedAfterEof() {
        val mockBuf = bufferOf("10\r\n1234567890abcdef\r\n0\r\n\r\n")
        val decoder = BodyDecoder.chunked()

        // normal read
        val buf = decoder.decodeFrame(mockBuf).data()
        assertEquals(16, buf.bytes.size)
        assertEquals("1234567890abcdef", buf.text)

        // eof read
        assertEnd(decoder.decodeFrame(mockBuf))
        assertTrue(decoder.isEof)

        // ensure read after eof also returns eof
        assertEnd(decoder.decodeFrame(mockBuf))
    }

    // `read_async` / `all_async_cases`: the reference reads `content[..block_at]`, blocks, then reads the rest
    // and reaches EOF. Here: the first part is fed, the decoder must ask for more, the rest is fed with EOF.
    // Each case is also run with one byte arriving at a time.
    private fun allAsyncCases(content: String, expected: String, selfDelimited: Boolean, decoder: () -> BodyDecoder) {
        val bytes = ascii(content)
        for (blockAt in 0 until bytes.size) {
            val parts = listOf(bytes.copyOfRange(0, blockAt), bytes.copyOfRange(blockAt, bytes.size))
            assertEquals(expected, decodeIncrementally(decoder(), parts), "Failed async. Blocking at $blockAt")
            if (selfDelimited) {
                // Length and chunked bodies end without the transport ending.
                assertEquals(expected, decodeIncrementally(decoder(), parts, eofAtEnd = false), "no EOF, blocking at $blockAt")
            }
        }
        val bytewise = List(bytes.size) { byteArrayOf(bytes[it]) }
        assertEquals(expected, decodeIncrementally(decoder(), bytewise), "byte at a time")
        if (selfDelimited) assertEquals(expected, decodeIncrementally(decoder(), bytewise, eofAtEnd = false))
    }

    @Test
    fun testReadLengthAsync() {
        val content = "foobar"
        allAsyncCases(content, content, selfDelimited = true) { BodyDecoder.length(content.length.toLong()) }
    }

    @Test
    fun testReadChunkedAsync() {
        val content = "3\r\nfoo\r\n3\r\nbar\r\n0\r\n\r\n"
        val expected = "foobar"
        allAsyncCases(content, expected, selfDelimited = true) { BodyDecoder.chunked() }
    }

    @Test
    fun testReadEofAsync() {
        val content = "foobar"
        allAsyncCases(content, content, selfDelimited = false) { BodyDecoder.eof() }
    }

    @Test
    fun testDecodeTrailers() {
        val buf = ascii("Expires: Wed, 21 Oct 2015 07:28:00 GMT\r\nX-Stream-Error: failed to decode\r\n\r\n")
        val headers = decodeTrailers(buf, 0, buf.size, 2)
        assertEquals(2, headers.len())
        assertEquals("Wed, 21 Oct 2015 07:28:00 GMT", headers["Expires"]!!.toStr())
        assertEquals("failed to decode", headers["X-Stream-Error"]!!.toStr())
    }

    @Test
    fun testDecodeTrailersPreservesDuplicateValues() {
        val buf = ascii("X-Trace: first\r\nX-Trace: second\r\n\r\n")
        val headers = decodeTrailers(buf, 0, buf.size, 2)
        val values = headers.getAll("X-Trace").map { it.toStr() }
        assertEquals(listOf("first", "second"), values)
    }

    @Test
    fun testTrailerMaxHeadersEnforced() {
        val h1MaxHeaders = 10
        val scratch = StringBuilder("10\r\n1234567890abcdef\r\n0\r\n")
        for (i in 0..h1MaxHeaders) scratch.append("trailer$i: $i\r\n")
        scratch.append("\r\n")
        val mockBuf = bufferOf(scratch.toString())

        val decoder = BodyDecoder.chunked(maxHeaders = h1MaxHeaders)

        // ready chunked body
        assertEquals(16, decoder.decodeFrame(mockBuf).data().bytes.size)

        // eof read
        val err = decoder.decodeFrame(mockBuf).err()
        assertEquals(BodyErrorKind.INVALID_DATA, err.kind)
    }

    @Test
    fun testTrailerMaxHeadersAllowsExactLimit() {
        val h1MaxHeaders = 10
        val scratch = StringBuilder("10\r\n1234567890abcdef\r\n0\r\n")
        for (i in 0 until h1MaxHeaders) scratch.append("trailer$i: $i\r\n")
        scratch.append("\r\n")
        val mockBuf = bufferOf(scratch.toString())

        val decoder = BodyDecoder.chunked(maxHeaders = h1MaxHeaders)
        assertEquals(16, decoder.decodeFrame(mockBuf).data().bytes.size)

        val trailers = decoder.decodeFrame(mockBuf).trailers()
        assertEquals(h1MaxHeaders, trailers.len())
        assertTrue(decoder.isEof)
        assertEnd(decoder.decodeFrame(mockBuf))
    }

    @Test
    fun testTrailerMaxHeaderSizeHugeTrailer() {
        val maxHeaderSize = 1024
        val scratch = StringBuilder("10\r\n1234567890abcdef\r\n0\r\n")
        scratch.append("huge_trailer: ${"x".repeat(maxHeaderSize)}\r\n")
        scratch.append("\r\n")
        val mockBuf = bufferOf(scratch.toString())

        val decoder = BodyDecoder.chunked(maxTrailerSize = maxHeaderSize)

        // ready chunked body
        assertEquals(16, decoder.decodeFrame(mockBuf).data().bytes.size)

        // eof read
        val err = decoder.decodeFrame(mockBuf).err()
        assertEquals(BodyErrorKind.INVALID_DATA, err.kind)
    }

    @Test
    fun testTrailerMaxHeaderSizeManySmallTrailers() {
        val maxHeaders = 10
        val headerSize = 64
        val scratch = StringBuilder("10\r\n1234567890abcdef\r\n0\r\n")
        for (i in 0 until maxHeaders) scratch.append("trailer$i: ${"x".repeat(headerSize)}\r\n")
        scratch.append("\r\n")
        val mockBuf = bufferOf(scratch.toString())

        val decoder = BodyDecoder.chunked(maxTrailerSize = maxHeaders * headerSize)

        // ready chunked body
        assertEquals(16, decoder.decodeFrame(mockBuf).data().bytes.size)

        // eof read
        val err = decoder.decodeFrame(mockBuf).err()
        assertEquals(BodyErrorKind.INVALID_DATA, err.kind)
    }
}
