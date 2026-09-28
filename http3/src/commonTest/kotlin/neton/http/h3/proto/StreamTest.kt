package neton.http.h3.proto

import neton.http.h3.FrameDecoder
import neton.io.bytes.Buffer
import neton.io.bytes.Bytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

// Tests of `src/stream.rs` (the WriteBuf tests; `write_wt_uni_header` is WebTransport, not in the first version),
// then stream IDs and the unidirectional stream header codec (`src/proto/stream.rs` has no tests).
class StreamTest {
    @Test
    fun write_buf_encode_streamtype() {
        val w = WriteBuf.ofStreamType(StreamType.ENCODER)
        assertContentEquals(bytes(2), w.chunk().toByteArray())
        assertEquals(1, w.headerLength)
    }

    @Test
    fun write_buf_encode_frame() {
        val w = WriteBuf.of(Frame.Goaway(2))
        assertContentEquals(bytes(7, 1, 2), w.chunk().toByteArray())
        assertEquals(3, w.headerLength)
    }

    @Test
    fun write_buf_encode_streamtype_then_frame() {
        val w = WriteBuf.of(StreamType.ENCODER, Frame.Goaway(2))
        assertContentEquals(bytes(2, 7, 1, 2), w.chunk().toByteArray())
    }

    @Test
    fun write_buf_advances() {
        val w = WriteBuf.of(StreamType.ENCODER, Frame.Data(Bytes.wrap("hey".encodeToByteArray())))
        assertContentEquals(bytes(2, 0, 3), w.chunk().toByteArray())
        w.advance(3)
        assertEquals(3, w.remaining)
        assertContentEquals("hey".encodeToByteArray(), w.chunk().toByteArray())
        w.advance(2)
        assertContentEquals("y".encodeToByteArray(), w.chunk().toByteArray())
        w.advance(1)
        assertEquals(0, w.remaining)
    }

    @Test
    fun write_buf_advance_jumps_header_and_payload_start() {
        val w = WriteBuf.of(StreamType.ENCODER, Frame.Data(Bytes.wrap("hey".encodeToByteArray())))
        w.advance(4)
        assertContentEquals("ey".encodeToByteArray(), w.chunk().toByteArray())
    }

    // ---- Beyond the reference's tests ----

    @Test
    fun writeBufWriteToAndHeadersPayload() {
        val w = WriteBuf.of(Frame.headers("abc"))
        val out = Buffer()
        w.writeTo(out)
        assertContentEquals(bytes(1, 3, 97, 98, 99), out.readAll())
        assertEquals(0, w.remaining)
        assertFailsWith<IllegalArgumentException> { w.advance(1) }
    }

    @Test
    fun writeBufControlStreamHeaderWithLargeSettings() {
        // SETTINGS with eight entries of 8-byte varints is larger than the reference's fixed array.
        val s = Settings()
        for (i in 0 until Settings.SETTINGS_LEN) assertNull(s.insert(VarInt.MAX - i, VarInt.MAX))
        val w = WriteBuf.of(UniStreamHeader.Control(s))
        val out = Buffer()
        w.writeTo(out)
        assertEquals(1 + 1 + 2 + Settings.SETTINGS_LEN * 16, out.readableBytes)
        assertEquals(StreamType.CONTROL, VarInt.decode(out))
        // The identifiers are unknown to the receiver, hence ignored; the frame parses whole.
        assertEquals(0, (FrameDecoder().decode(out) as Settings).len)
        assertTrue(out.isEmpty)
    }

    @Test
    fun uniStreamHeaders() {
        val b = Buffer()
        UniStreamHeader.Encoder.encode(b)
        UniStreamHeader.Decoder.encode(b)
        val settings = Settings().also { it.insert(SettingId.MAX_HEADER_LIST_SIZE, 100) }
        UniStreamHeader.Control(settings).encode(b)
        assertEquals(StreamType.ENCODER, VarInt.decode(b))
        assertEquals(StreamType.DECODER, VarInt.decode(b))
        assertEquals(StreamType.CONTROL, VarInt.decode(b))
        assertEquals(settings, FrameDecoder().decode(b))
    }

    @Test
    fun streamTypeDecoderPartialInput() {
        val d = StreamTypeDecoder()
        val b = Buffer()
        b.writeBytes(bytes(0x40))
        assertFalse(d.decode(b))
        b.writeBytes(bytes(0x02, 7, 1))
        assertTrue(d.decode(b))
        assertEquals(StreamType.ENCODER, d.type)
        assertEquals(-1L, d.pushId)
        // The rest is the stream's body.
        assertContentEquals(bytes(7, 1), b.readAll())
    }

    @Test
    fun streamTypeDecoderPushId() {
        val d = StreamTypeDecoder()
        val b = Buffer()
        b.writeBytes(bytes(0x01, 0x80))
        assertFalse(d.decode(b))
        assertFalse(d.isResolved)
        b.writeBytes(bytes(0, 0, 5))
        assertTrue(d.decode(b))
        assertEquals(StreamType.PUSH, d.type)
        assertEquals(5L, d.pushId)
    }

    @Test
    fun streamTypeDecoderUnknownAndWebTransportTypes() {
        for (t in listOf(StreamType.WEBTRANSPORT_UNI, StreamType.grease(), 0x1234L)) {
            val d = StreamTypeDecoder()
            val b = Buffer()
            VarInt.encode(t, b)
            b.writeBytes(bytes(9))
            assertTrue(d.decode(b))
            assertEquals(t, d.type)
            assertEquals(1, b.readableBytes)
        }
    }

    @Test
    fun streamIds() {
        assertTrue(StreamId(0).isRequest)
        assertTrue(StreamId(4).isRequest)
        assertFalse(StreamId(1).isRequest)
        assertTrue(StreamId(3).isPush)
        assertFalse(StreamId(2).isPush)
        assertEquals(Side.Server, StreamId(7).initiator)
        assertEquals(Dir.Uni, StreamId(7).dir)
        assertEquals(1L, StreamId(7).index)
        assertEquals(StreamId(8), StreamId.FIRST_REQUEST + 2)
        assertEquals(VarInt.MAX ushr 2, (StreamId(VarInt.MAX - 3) + 5).index)
        assertEquals("client bidirectional stream 2", StreamId(8).toString())
        assertEquals("server unidirectional stream 0", StreamId(3).toString())
        assertNull(StreamId.tryFrom(VarInt.MAX + 1))
        assertEquals("Control", StreamType.name(0))
        assertEquals("StreamType(1)", StreamType.name(1))
    }
}
