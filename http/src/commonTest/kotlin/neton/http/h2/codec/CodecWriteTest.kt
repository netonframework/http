package neton.http.h2.codec

import neton.http.h2.Frames
import neton.http.h2.RawCodec
import neton.http.h2.assertBytes
import neton.http.h2.assertClosed
import neton.http.h2.buildLargeHeaders
import neton.http.h2.frame.DEFAULT_MAX_FRAME_SIZE
import neton.http.h2.frame.HEADER_LEN
import neton.http.h2.frame.Head
import neton.http.h2.frame.Headers
import neton.http.h2.frame.Kind
import neton.http.h2.frame.Settings
import neton.http.h2.pollFrame
import neton.http.h2.proto.IoErrorKind
import neton.http.h2.proto.ProtoError
import neton.http.h2.raw
import neton.http.h2.write
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// Ported from h2 0.4.19 `tests/h2-tests/tests/codec_write.rs` (4 tests). The reference runs a client or server over
// mock I/O and checks the bytes it writes; here the same frames are buffered into a FramedWrite (and read back with a
// FramedRead where the reference's mock peer decodes them).

class CodecWriteTest {
    @Test
    fun writeContinuationFrames() {
        val large = buildLargeHeaders()

        // Build the large request frame
        val frame = large.fold(Frames.headers(1).request("GET", "https://http2.akamai.com/")) { f, (name, value) ->
            f.field(name, value)
        }

        // The client writes it.
        val wire = FramedWrite().write(frame.eos().frame)

        // Every frame fits the peer's max frame size; the first is HEADERS, the rest CONTINUATION, only the last with
        // END_HEADERS.
        var at = 0
        var count = 0
        while (at < wire.size) {
            val len = Head.payloadLength(wire, at)
            val head = Head.parse(wire, at)
            assertTrue(len <= DEFAULT_MAX_FRAME_SIZE)
            assertEquals(if (count == 0) Kind.Headers else Kind.Continuation, head.kind)
            at += HEADER_LEN + len
            assertEquals(at == wire.size, head.flag and 0x4 != 0)
            count++
        }
        assertTrue(count > 1)

        // The server receives exactly the frame.
        val expected = large.fold(Frames.headers(1).request("GET", "https://http2.akamai.com/")) { f, (name, value) ->
            f.field(name, value)
        }.eos().frame
        val srv = RawCodec(wire)
        assertEquals(expected, srv.pollFrame<Headers>())
        srv.assertClosed()
    }

    /** A server sets SETTINGS_HEADER_TABLE_SIZE to 0: the client then does not send indexed headers. */
    @Test
    fun clientSettingsHeaderTableSize() {
        val client = Codec()

        // Read SETTINGS_HEADER_TABLE_SIZE = 0
        val settings = RawCodec(
            raw(
                0, 0, 6, // len
                4, // type
                0, // flags
                0, 0, 0, 0, // stream id
                0, 0x1, // id = SETTINGS_HEADER_TABLE_SIZE
                0, 0, 0, 0, // value = 0
            ),
        ).pollFrame<Settings>()
        assertEquals(0L, settings.headerTableSize)

        // Write GET / (1st)
        assertBytes(
            raw(
                0, 0, 0x10, 1, 5, 0, 0, 0, 1, 0x82, 0x87, 0x41, 0x8B, 0x9D, 0x29, 0xAC, 0x4B, 0x8F,
                0xA8, 0xE9, 0x19, 0x97, 0x21, 0xE9, 0x84,
            ),
            client.writer.write(Frames.headers(1).request("GET", "https://http2.akamai.com").eos().frame),
        )
        // The connection applies the peer's settings and acknowledges them.
        client.setSendHeaderTableSize(settings.headerTableSize!!.toInt())
        assertBytes(Frames.SETTINGS_ACK, client.writer.write(Settings.ack()))

        // Write GET / (2nd, doesn't use indexed headers)
        // - Sends 0x20 about size change
        // - Sends :authority as literal instead of indexed
        assertBytes(
            raw(
                0, 0, 0x11, 1, 5, 0, 0, 0, 3, 0x20, 0x82, 0x87, 0x1, 0x8B, 0x9D, 0x29, 0xAC, 0x4B,
                0x8F, 0xA8, 0xE9, 0x19, 0x97, 0x21, 0xE9, 0x84,
            ),
            client.writer.write(Frames.headers(3).request("GET", "https://http2.akamai.com").eos().frame),
        )
    }

    /** A client sets SETTINGS_HEADER_TABLE_SIZE to 0: the server then does not send indexed headers. */
    @Test
    fun serverSettingsHeaderTableSize() {
        val server = Codec()
        val read = RawCodec(
            raw(
                // Read SETTINGS_HEADER_TABLE_SIZE = 0
                0, 0, 6, // len
                4, // type
                0, // flags
                0, 0, 0, 0, // stream id
                0, 0x1, // id = SETTINGS_HEADER_TABLE_SIZE
                0, 0, 0, 0, // value = 0
                Frames.SETTINGS_ACK,
                // GET /
                0, 0, 0x10, 1, 5, 0, 0, 0, 1, 0x82, 0x87, 0x41, 0x8B, 0x9D, 0x29, 0xAC, 0x4B, 0x8F,
                0xA8, 0xE9, 0x19, 0x97, 0x21, 0xE9, 0x84,
            ),
            server,
        )
        val settings = read.pollFrame<Settings>()
        server.setSendHeaderTableSize(settings.headerTableSize!!.toInt())
        assertBytes(Frames.SETTINGS, server.writer.write(Settings()))
        assertBytes(Frames.SETTINGS_ACK, server.writer.write(Settings.ack()))
        assertTrue(read.pollFrame<Settings>().isAck)

        val req = read.pollFrame<Headers>()
        assertEquals(Frames.headers(1).request("GET", "https://http2.akamai.com").eos().frame, req)
        read.assertClosed()

        // Response `200, a: b`
        assertBytes(
            raw(0, 0, 7, 1, 5, 0, 0, 0, 1, 32, 136, 0, 129, 31, 129, 143),
            server.writer.write(Frames.headers(1).response(200).field("a", "b").eos().frame),
        )
    }

    /**
     * A transport whose writes accept no bytes: the reference's flush fails with `WriteZero` instead of re-polling the
     * zero-length write forever. Here the connection reports the count it wrote through `advance`.
     */
    @Test
    fun writeZeroReturnsWriteZeroErr() {
        val client = FramedWrite()

        // 1. Send a request while the transport still accepts writes.
        client.write(Frames.headers(1).request("GET", "https://example.com/").eos().frame)

        // 4. The transport's write side dies: every write from now on accepts 0 bytes.
        // 5. A second request: writing must fail with WriteZero.
        assertEquals(null, client.buffer(Frames.headers(3).request("GET", "https://example.com/").eos().frame))
        val err = assertFailsWith<ProtoError.Io> { client.advance(0) }
        assertEquals(IoErrorKind.WriteZero, err.kind)
    }
}
