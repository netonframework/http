package neton.http.h2.codec

import neton.http.Method
import neton.http.StatusCode
import neton.http.h2.Frames
import neton.http.h2.RawCodec
import neton.http.h2.assertClosed
import neton.http.h2.buildLargeHeaders
import neton.http.h2.frame.Data
import neton.http.h2.frame.GoAway
import neton.http.h2.frame.Headers
import neton.http.h2.frame.PushPromise
import neton.http.h2.frame.Reason
import neton.http.h2.pollFrame
import neton.http.h2.proto.ProtoError
import neton.http.h2.raw
import neton.http.header.HeaderMap
import neton.http.header.HeaderName
import neton.http.header.HeaderValue
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Ported from h2 0.4.19 `tests/h2-tests/tests/codec_read.rs` (14 tests, 4 of them empty and ignored as in the
// reference). The reference drives a codec over mock I/O; here the codec decodes the same bytes from a buffer. The
// connection-level test (read_continuation_frames) is reduced to what reaches the codec: the server's frame is
// encoded by a FramedWrite and read back.

class CodecReadTest {
    @Test
    fun readNone() {
        val codec = RawCodec(ByteArray(0))
        codec.assertClosed()
    }

    @Test
    @Ignore // #[ignore] in the reference, an empty placeholder there (tests/codec_read.rs)
    fun readFrameTooBig() {
    }

    // ===== DATA =====

    @Test
    fun readDataNoPadding() {
        val codec = RawCodec(raw(0, 0, 5, 0, 0, 0, 0, 0, 1, "hello"))

        val data = codec.pollFrame<Data>()
        assertEquals(1, data.streamId.value)
        assertEquals("hello", data.payload.decodeToString())
        assertFalse(data.isEndStream)

        codec.assertClosed()
    }

    @Test
    fun readDataEmptyPayload() {
        val codec = RawCodec(raw(0, 0, 0, 0, 0, 0, 0, 0, 1))

        val data = codec.pollFrame<Data>()
        assertEquals(1, data.streamId.value)
        assertEquals("", data.payload.decodeToString())
        assertFalse(data.isEndStream)

        codec.assertClosed()
    }

    @Test
    fun readDataEndStream() {
        val codec = RawCodec(raw(0, 0, 5, 0, 1, 0, 0, 0, 1, "hello"))

        val data = codec.pollFrame<Data>()
        assertEquals(1, data.streamId.value)
        assertEquals("hello", data.payload.decodeToString())
        assertTrue(data.isEndStream)
        codec.assertClosed()
    }

    @Test
    fun readDataPadding() {
        val codec = RawCodec(
            raw(
                0, 0, 16, 0, 0x8, 0, 0, 0, 1,
                5, // Pad length
                "helloworld", // Data
                "\u0000\u0000\u0000\u0000\u0000", // Padding
            ),
        )

        val data = codec.pollFrame<Data>()
        assertEquals(1, data.streamId.value)
        assertEquals("helloworld", data.payload.decodeToString())
        assertFalse(data.isEndStream)

        codec.assertClosed()
    }

    @Test
    fun readPushPromise() {
        val codec = RawCodec(
            raw(
                0, 0, 0x5,
                0x5, 0x4,
                0, 0, 0, 0x1, // stream id
                0, 0, 0, 0x2, // promised id
                0x82, // HPACK :method="GET"
            ),
        )

        val pp = codec.pollFrame<PushPromise>()
        assertEquals(1, pp.streamId.value)
        assertEquals(2, pp.promisedId.value)
        assertEquals(Method.GET, pp.intoParts().first.method)

        codec.assertClosed()
    }

    @Test
    fun readDataStreamIdZero() {
        val codec = RawCodec(raw(0, 0, 5, 0, 0, 0, 0, 0, 0, "hello"))

        assertFailsWith<ProtoError> { codec.next() }
    }

    // ===== HEADERS =====

    @Test
    @Ignore // #[ignore] in the reference, an empty placeholder there (tests/codec_read.rs)
    fun readHeadersWithoutPseudo() {
    }

    @Test
    @Ignore // #[ignore] in the reference, an empty placeholder there (tests/codec_read.rs)
    fun readHeadersWithPseudo() {
    }

    @Test
    @Ignore // #[ignore] in the reference, an empty placeholder there (tests/codec_read.rs)
    fun readHeadersEmptyPayload() {
    }

    @Test
    fun readContinuationFrames() {
        val large = buildLargeHeaders()
        val frame = large.fold(Frames.headers(1).response(200)) { f, (name, value) -> f.field(name, value) }.eos().frame

        // The server side: the frame goes out as HEADERS + CONTINUATION frames.
        val wire = FramedWrite().let { w ->
            assertEquals(null, w.buffer(frame))
            val out = ArrayList<Byte>()
            var frames = 1
            while (true) {
                while (!w.isEmpty) {
                    val b = w.writeBuffer.peekAll()
                    out.addAll(b.toList())
                    w.advance(b.size)
                }
                if (!w.unsetFrame()) break
                frames++
            }
            assertTrue(frames > 1, "expected CONTINUATION frames")
            out.toByteArray()
        }

        // The client side.
        val codec = RawCodec(wire)
        val res = codec.pollFrame<Headers>()
        assertEquals(StatusCode.OK, res.pseudo.status)
        assertTrue(res.isEndStream)
        val expected = HeaderMap<HeaderValue>()
        for ((name, value) in large) expected.append(HeaderName.fromStr(name), HeaderValue.fromStr(value))
        assertEquals(expected, res.fields)
        codec.assertClosed()
    }

    @Test
    fun updateMaxFrameLenAtRest() {
        val codec = RawCodec(
            raw(
                0, 0, 5, 0, 0, 0, 0, 0, 1,
                "hello",
                0, 64, 1, 0, 0, 0, 0, 0, 1,
                ByteArray(16_385),
            ),
        )

        assertEquals("hello", codec.pollFrame<Data>().payload.decodeToString())

        codec.codec.setMaxRecvFrameSize(16_384)

        assertEquals(16_384, codec.codec.maxRecvFrameSize)
        val err = assertFailsWith<ProtoError> { codec.next() }
        assertEquals("frame with invalid size", err.message)

        // drain codec buffer
        codec.buf.clear()
    }

    @Test
    fun readGoawayWithDebugData() {
        val codec = RawCodec(
            raw(
                // head
                0, 0, 22, 7, 0, 0, 0, 0, 0,
                // last_stream_id
                0, 0, 0, 1,
                // error_code
                0, 0, 0, 11,
                // debug_data
                "too_many_pings",
            ),
        )

        val data = codec.pollFrame<GoAway>()
        assertEquals(Reason.ENHANCE_YOUR_CALM, data.reason)
        assertEquals(1, data.lastStreamId.value)
        assertEquals("too_many_pings", data.debugData.decodeToString())

        codec.assertClosed()
    }
}
