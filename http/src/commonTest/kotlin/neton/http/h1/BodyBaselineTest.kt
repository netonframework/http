package neton.http.h1

import neton.http.header.HeaderMap
import neton.http.header.HeaderName
import neton.http.header.HeaderValue
import neton.io.bytes.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The safety-baseline deviations of the body codecs (SPEC §3.4, §3.9) and the sans-I/O contract beyond the
 * reference tests.
 */
class BodyBaselineTest {

    // ---- chunk size line: 1 KiB per line, plus hyper's 16 KiB for all extensions ----

    @Test
    fun chunkSizeLineOverOneKibRejected() {
        // "1;" + ext + "\r\n": 1025 bytes.
        val tooLong = "1;" + "x".repeat(1025 - 4) + "\r\nA\r\n0\r\n\r\n"
        val d = BodyDecoder.chunked()
        val e = d.decodeFrame(bufferOf(tooLong)).err()
        assertEquals(BodyDecodeError.SIZE_LINE_TOO_LONG, e)
        assertEquals(BodyErrorKind.INVALID_DATA, e.kind)

        // Exactly 1024 bytes is accepted.
        val atLimit = "1;" + "x".repeat(1024 - 4) + "\r\nA\r\n0\r\n\r\n"
        val ok = BodyDecoder.chunked()
        val buf = bufferOf(atLimit)
        assertEquals("A", ok.decodeFrame(buf).data().text)
        assertEnd(ok.decodeFrame(buf))

        // A long run of whitespace after the size counts too.
        val lws = "1" + " ".repeat(1100) + "\r\nA\r\n"
        assertEquals(BodyDecodeError.SIZE_LINE_TOO_LONG, BodyDecoder.chunked().decodeFrame(bufferOf(lws)).err())
    }

    @Test
    fun extensionsLimitIsCumulativeUnderLineLimit() {
        // Each line (1,000 extension bytes) is within 1 KiB; 16 chunks carry 16,000 bytes, the 17th crosses 16 KiB.
        val sb = StringBuilder()
        repeat(20) { sb.append("1;").append("x".repeat(1000)).append("\r\nA\r\n") }
        sb.append("0\r\n\r\n")
        val buf = bufferOf(sb.toString())
        val d = BodyDecoder.chunked()
        var frames = 0
        while (true) {
            val f = d.decodeFrame(buf)
            if (f is TestFrame.Err) {
                assertEquals(BodyDecodeError.EXTENSIONS_OVER_LIMIT, f.error)
                break
            }
            assertEquals("A", f.data().text)
            frames++
        }
        assertEquals(16, frames)
    }

    // ---- hex digits: at most 16, plus the overflow check ----

    @Test
    fun moreThanSixteenHexDigitsRejected() {
        val e = BodyDecoder.chunked().decodeFrame(bufferOf("00000000000000001\r\nA\r\n0\r\n\r\n")).err()
        assertEquals(BodyDecodeError.TOO_MANY_SIZE_DIGITS, e)
        assertEquals(BodyErrorKind.INVALID_DATA, e.kind)

        // 16 digits, leading zeros included, are accepted.
        val d = BodyDecoder.chunked()
        val buf = bufferOf("0000000000000001\r\nA\r\n0\r\n\r\n")
        assertEquals("A", d.decodeFrame(buf).data().text)
        assertEnd(d.decodeFrame(buf))

        // The largest 16-digit size is the full unsigned 64-bit range.
        val max = BodyDecoder.chunked()
        assertEquals(DecodeResult.NEED_MORE, max.decode(bufferOf("FFFFFFFFFFFFFFFF\r\n"), eof = false))
        assertEquals(ULong.MAX_VALUE, max.chunkLen)
        assertEquals(Long.MAX_VALUE, max.wanted)
    }

    // ---- strict CRLF ----

    @Test
    fun bareLfAfterSizeLineRejected() {
        assertEquals(BodyDecodeError.INVALID_SIZE, BodyDecoder.chunked().decodeFrame(bufferOf("1\nA\r\n0\r\n\r\n")).err())
        assertEquals(
            BodyDecodeError.INVALID_SIZE_LWS,
            BodyDecoder.chunked().decodeFrame(bufferOf("1 \nA\r\n0\r\n\r\n")).err(),
        )
        assertEquals(BodyDecodeError.INVALID_SIZE_LF, BodyDecoder.chunked().decodeFrame(bufferOf("1\r\rA\r\n")).err())
        // A bare LF inside an extension.
        assertEquals(
            BodyDecodeError.EXTENSION_NEWLINE,
            BodyDecoder.chunked().decodeFrame(bufferOf("1;a\nA\r\n0\r\n\r\n")).err(),
        )
    }

    @Test
    fun bareLfAfterDataRejected() {
        val d = BodyDecoder.chunked()
        val buf = bufferOf("1\r\nA\n0\r\n\r\n")
        assertEquals("A", d.decodeFrame(buf).data().text)
        assertEquals(BodyDecodeError.INVALID_BODY_CR, d.decodeFrame(buf).err())

        val d2 = BodyDecoder.chunked()
        val buf2 = bufferOf("1\r\nA\r0\r\n\r\n")
        assertEquals("A", d2.decodeFrame(buf2).data().text)
        assertEquals(BodyDecodeError.INVALID_BODY_LF, d2.decodeFrame(buf2).err())
    }

    @Test
    fun bareLfInTrailersRejected() {
        // A bare LF line ending inside the trailer section: the section is parsed with bare LF rejected.
        val d = BodyDecoder.chunked()
        assertEquals(BodyDecodeError.INVALID_TRAILERS, d.decodeFrame(bufferOf("0\r\nA: b\nC: d\r\n\r\n")).err())
        // A bare LF as the final line.
        assertEquals(BodyDecodeError.INVALID_END_LF, BodyDecoder.chunked().decodeFrame(bufferOf("0\r\n\r\r")).err())
        assertEquals(
            BodyDecodeError.INVALID_TRAILERS,
            BodyDecoder.chunked().decodeFrame(bufferOf("0\r\nA: b\r\n\n\r\n\r\n")).err(),
        )
    }

    // ---- trailer limits ----

    @Test
    fun trailerSectionOverEightKibRejected() {
        val big = "0\r\nx-big: ${"v".repeat(9000)}\r\n\r\n"
        val e = BodyDecoder.chunked().decodeFrame(bufferOf(big)).err()
        assertEquals(BodyDecodeError.TRAILERS_OVER_LIMIT, e)
        assertEquals(BodyErrorKind.INVALID_DATA, e.kind)

        // Just below the default 8 KiB: "x-big: " (7) + value + CRLF + CRLF must stay under 8192.
        val ok = "0\r\nx-big: ${"v".repeat(8192 - 7 - 4 - 1)}\r\n\r\n"
        val d = BodyDecoder.chunked()
        val t = d.decodeFrame(bufferOf(ok)).trailers()
        assertEquals(8192 - 12, t["x-big"]!!.length)

        // One more byte reaches the limit (hyper's `put_u8!` fails at `len >= limit`).
        val at = "0\r\nx-big: ${"v".repeat(8192 - 7 - 4)}\r\n\r\n"
        assertEquals(BodyDecodeError.TRAILERS_OVER_LIMIT, BodyDecoder.chunked().decodeFrame(bufferOf(at)).err())

        // Configurable.
        val larger = BodyDecoder.chunked(maxTrailerSize = 16 * 1024)
        assertEquals(9000, larger.decodeFrame(bufferOf(big)).trailers()["x-big"]!!.length)
    }

    @Test
    fun trailerCountOverMaxHeadersRejected() {
        fun section(n: Int) = buildString {
            append("0\r\n")
            for (i in 0 until n) append("t$i: $i\r\n")
            append("\r\n")
        }
        // Default maxHeaders = 100.
        assertEquals(100, BodyDecoder.chunked().decodeFrame(bufferOf(section(100))).trailers().len())
        val e = BodyDecoder.chunked().decodeFrame(bufferOf(section(101))).err()
        assertEquals(BodyDecodeError.TRAILERS_COUNT_OVERFLOW, e)
        assertEquals(BodyErrorKind.INVALID_DATA, e.kind)
    }

    @Test
    fun trailersDeliveredAsFrameAfterData() {
        val d = BodyDecoder.chunked()
        val buf = bufferOf("3\r\nabc\r\n0\r\nX-Checksum: 42\r\nX-Trace: a\r\nX-Trace: b\r\n\r\nNEXT")
        assertEquals("abc", d.decodeFrame(buf, eof = false).data().text)
        val t = d.decodeFrame(buf, eof = false).trailers()
        assertEquals("42", t["x-checksum"]!!.toStr())
        assertEquals(listOf("a", "b"), t.getAll("x-trace").map { it.toStr() })
        assertTrue(d.isEof)
        assertEnd(d.decodeFrame(buf, eof = false))
        assertEquals("NEXT", buf.text()) // the pipelined next message stays in the buffer
    }

    // ---- truncation ----

    @Test
    fun truncationAtEveryPositionIsAnError() {
        val chunked = "3\r\nfoo\r\n3;e=1\r\nbar\r\n0\r\nT: v\r\n\r\n"
        for (cut in 0 until chunked.length) {
            val d = BodyDecoder.chunked()
            val buf = bufferOf(chunked.substring(0, cut))
            var r: DecodeResult
            do r = d.decode(buf, eof = true) while (r == DecodeResult.DATA)
            assertEquals(DecodeResult.ERROR, r, "cut at $cut")
            assertEquals(BodyErrorKind.UNEXPECTED_EOF, d.error!!.kind, "cut at $cut")
            assertEquals(DecodeResult.ERROR, d.decode(bufferOf("rest"), eof = false), "error is sticky")
        }
        val length = "0123456789"
        for (cut in 0 until length.length) {
            val d = BodyDecoder.length(length.length.toLong())
            val buf = bufferOf(length.substring(0, cut))
            var r: DecodeResult
            do r = d.decode(buf, eof = true) while (r == DecodeResult.DATA)
            assertEquals(DecodeResult.ERROR, r, "cut at $cut")
            assertEquals(BodyDecodeError.INCOMPLETE_BODY, d.error, "cut at $cut")
        }
        assertFailsWith<BodyDecodeException> { BodyDecoder.length(5).decodeOrThrow(Buffer(), eof = true) }
    }

    // ---- sans-I/O contract ----

    @Test
    fun lengthLeavesFollowingBytesAndReportsWanted() {
        val d = BodyDecoder.length(5)
        val buf = bufferOf("ab")
        assertEquals(DecodeResult.DATA, d.decode(buf, false))
        assertEquals("ab", d.data.decodeToString())
        assertEquals(DecodeResult.NEED_MORE, d.decode(buf, false))
        assertEquals(3L, d.wanted)
        buf.writeBytes(ascii("cdeGET /"))
        assertEquals(DecodeResult.DATA, d.decode(buf, false))
        assertEquals("cde", d.data.decodeToString())
        assertTrue(d.isEof)
        assertEquals(DecodeResult.END, d.decode(buf, false))
        assertEquals("GET /", buf.text())
        assertTrue(BodyDecoder.length(0).isEof)
    }

    @Test
    fun eofBodyReadsAtMost8192PerFrame() {
        val d = BodyDecoder.eof()
        val buf = bufferOf(ByteArray(20000) { 'a'.code.toByte() })
        val sizes = mutableListOf<Int>()
        while (d.decode(buf, eof = true) == DecodeResult.DATA) sizes += d.data.size
        assertEquals(listOf(8192, 8192, 20000 - 16384), sizes)
        assertTrue(d.isEof)
        assertEquals(DecodeResult.NEED_MORE, BodyDecoder.eof().decode(Buffer(), false))
    }

    @Test
    fun chunkDataIsZeroCopySlice() {
        // Small chunks are copied (a slice would make the connection's read buffer move to a fresh array).
        val small = BodyDecoder.chunked()
        val buf = bufferOf("5\r\nhello\r\n")
        val arr = buf.backingArray()
        assertEquals(DecodeResult.DATA, small.decode(buf, false))
        arr[3] = 'j'.code.toByte()
        assertEquals("hello", small.data.decodeToString())
        // Large chunks stay zero-copy slices of the buffer.
        val n = DATA_COPY_LIMIT + 1
        val big = BodyDecoder.chunked()
        val buf2 = bufferOf(n.toString(16) + "\r\n" + "a".repeat(n) + "\r\n")
        val arr2 = buf2.backingArray()
        val start = buf2.readerIndex() + n.toString(16).length + 2
        assertEquals(DecodeResult.DATA, big.decode(buf2, false))
        arr2[start] = 'j'.code.toByte()
        assertEquals('j'.code.toByte(), big.data[0])
        assertEquals(DecodeResult.NEED_MORE, big.decode(buf2, false))
        assertEquals(1L, big.wanted)
    }

    // ---- encoder ----

    @Test
    fun forbiddenTrailerFieldsDroppedByEncoder() {
        val allowed = listOf(HeaderName.fromStatic("x-checksum"), HeaderName.CONTENT_TYPE, HeaderName.SET_COOKIE)
        val encoder = BodyEncoder.chunked().withTrailerFields(allowed)
        val trailers = HeaderMap<HeaderValue>()
        trailers.append(HeaderName.CONTENT_TYPE, HeaderValue.fromStatic("text/plain"))
        trailers.append(HeaderName.fromStatic("x-checksum"), HeaderValue.fromStatic("42"))
        trailers.append(HeaderName.SET_COOKIE, HeaderValue.fromStatic("a=b"))
        trailers.append(HeaderName.fromStatic("x-undeclared"), HeaderValue.fromStatic("no"))
        val out = Buffer()
        assertTrue(encoder.encodeEnd(trailers, out))
        assertEquals("0\r\nx-checksum: 42\r\n\r\n", out.text())
        assertFalse(BodyEncoder.isValidTrailerField(HeaderName.TE))
        assertTrue(BodyEncoder.isValidTrailerField(HeaderName.fromStatic("x-checksum")))
    }

    @Test
    fun encodeEndFallsBackToTerminator() {
        // No Trailer list: the trailers are dropped and the body ends normally.
        val t = HeaderMap.fromIter(listOf(HeaderName.fromStatic("x-a") to HeaderValue.fromStatic("1")))
        val out = Buffer()
        assertTrue(BodyEncoder.chunked().encodeEnd(t, out))
        assertEquals("0\r\n\r\n", out.text())
        // Trailers are chunked-only.
        val len = Buffer()
        assertTrue(BodyEncoder.length(0).encodeEnd(t, len))
        assertEquals(0, len.readableBytes)
        // Ending a Content-Length body early: BodyWriteAborted.
        val short = BodyEncoder.length(10)
        short.encode(ascii("abc"), Buffer())
        assertFalse(short.encodeEnd(null, Buffer()))
        assertEquals(7L, short.remaining)
    }

    @Test
    fun chunkHeaderHex() {
        for (n in listOf(1, 9, 10, 15, 16, 255, 256, 4095, 4096, 65535, 1 shl 20)) {
            val out = Buffer()
            BodyEncoder.chunked().encode(ByteArray(n), out)
            val s = out.text()
            assertEquals(n.toString(16).uppercase() + "\r\n", s.substring(0, s.indexOf('\n') + 1), "n=$n")
            assertEquals(s.indexOf('\n') + 1 + n + 2, s.length)
        }
        // Empty frames write nothing (a 0-size chunk would end the body).
        val out = Buffer()
        assertEquals(0, BodyEncoder.chunked().encode(ByteArray(0), out))
        assertEquals(0, out.readableBytes)
    }

    @Test
    fun encodeAndEndCoalescesLastChunk() {
        val out = Buffer()
        val e = BodyEncoder.chunked()
        assertTrue(e.encodeAndEnd(ascii("xyz"), 0, 3, out))
        assertEquals("3\r\nxyz\r\n0\r\n\r\n", out.text())
        assertFalse(BodyEncoder.chunked().setLast(true).encodeAndEnd(ascii("x"), 0, 1, Buffer()))

        // Length: exact keeps alive, longer is truncated, shorter cannot keep alive.
        val exact = Buffer()
        assertTrue(BodyEncoder.length(3).encodeAndEnd(ascii("abc"), 0, 3, exact))
        assertEquals("abc", exact.text())
        val longer = Buffer()
        assertTrue(BodyEncoder.length(2).encodeAndEnd(ascii("abc"), 0, 3, longer))
        assertEquals("ab", longer.text())
        assertFalse(BodyEncoder.length(5).encodeAndEnd(ascii("abc"), 0, 3, Buffer()))
        // Close-delimited never keeps alive.
        assertFalse(BodyEncoder.closeDelimited().encodeAndEnd(ascii("abc"), 0, 3, Buffer()))
    }

    @Test
    fun prefixSuffixForQueuedWrites() {
        val e = BodyEncoder.chunked()
        val out = Buffer()
        assertEquals(5, e.writeDataPrefix(5, out))
        out.writeBytes(ascii("hello")) // stands in for the queued data slice
        e.writeDataSuffix(out)
        assertEquals("5\r\nhello\r\n", out.text())

        val len = BodyEncoder.length(4)
        val o2 = Buffer()
        assertEquals(4, len.writeDataPrefix(6, o2))
        assertEquals(0, o2.readableBytes)
        assertTrue(len.isEof)
        assertEquals(0, len.writeDataPrefix(3, o2))
    }

    @Test
    fun encodeBytesTruncatesAtLength() {
        val e = BodyEncoder.length(4)
        val out = Buffer()
        val src = Buffer().also { it.writeBytes(ascii("abcdef")) }.readSlice(6)
        assertEquals(4, e.encode(src, out))
        assertEquals("abcd", out.text())
        assertEquals(0, e.encode(src, out))
        val c = Buffer()
        BodyEncoder.chunked().encode(src, c)
        assertEquals("6\r\nabcdef\r\n", c.text())
    }

    @Test
    fun roundTripThroughDecoder() {
        val enc = BodyEncoder.chunked().withTrailerFields(listOf(HeaderName.fromStatic("x-sum")))
        val wire = Buffer()
        val parts = listOf("a", "bb".repeat(300), "c".repeat(5000))
        for (p in parts) enc.encode(ascii(p), wire)
        val t = HeaderMap.fromIter(listOf(HeaderName.fromStatic("x-sum") to HeaderValue.fromStatic("7")))
        assertTrue(enc.encodeEnd(t, wire, titleCase = true))
        val all = wire.peekAll()
        val bytewise = List(all.size) { byteArrayOf(all[it]) }
        assertEquals(parts.joinToString(""), decodeIncrementally(BodyDecoder.chunked(), bytewise, eofAtEnd = false))
    }
}
