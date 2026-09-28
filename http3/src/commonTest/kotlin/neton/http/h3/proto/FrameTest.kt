package neton.http.h3.proto

import neton.http.h3.Code
import neton.http.h3.FrameDecoder
import neton.http.h3.MAX_SETTINGS_PAYLOAD
import neton.io.bytes.Buffer
import neton.io.bytes.Bytes
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

private fun buf(vararg b: Int) = Buffer().also { it.writeBytes(bytes(*b)) }

private fun settingsOf(vararg entries: Pair<Long, Long>): Settings =
    Settings().also { s -> entries.forEach { (id, v) -> assertNull(s.insert(id, v)) } }

// Tests of `src/proto/frame.rs` (all 10 ported), then the ⚖️ behaviours and SETTINGS rules.
class FrameTest {
    private val decoder = FrameDecoder()

    private fun decodeError(input: Buffer): FrameError =
        assertFailsWith<FrameException> { FrameDecoder().decode(input) }.error

    // ---- src/proto/frame.rs ----

    @Test
    fun unknown_frame_type() {
        val b = buf(22, 4, 0, 255, 128, 0, 3, 1, 2)
        assertNull(decoder.decodeFrame(b))
        assertEquals(FrameDecoder.UNKNOWN, decoder.status)
        assertEquals(22L, decoder.unknownType)
        assertEquals(Frame.CancelPush(2), decoder.decodeFrame(b))
    }

    @Test
    fun len_unexpected_end() {
        assertNull(decoder.decodeFrame(buf(0, 255)))
        assertEquals(FrameDecoder.INCOMPLETE, decoder.status)
        assertEquals(3, decoder.incompleteMin)
    }

    @Test
    fun type_unexpected_end() {
        assertNull(decoder.decodeFrame(buf(255)))
        assertEquals(FrameDecoder.INCOMPLETE, decoder.status)
        assertEquals(2, decoder.incompleteMin)
    }

    @Test
    fun buffer_too_short() {
        assertNull(decoder.decodeFrame(buf(4, 4, 0, 255, 128)))
        assertEquals(FrameDecoder.INCOMPLETE, decoder.status)
        assertEquals(6, decoder.incompleteMin)
    }

    private fun codecFrameCheck(frame: Frame, wire: ByteArray, checkFrame: Frame) {
        val b = Buffer()
        frame.encodeWithPayload(b)
        assertContentEquals(wire, b.peekAll())
        val decoded = FrameDecoder().decodeFrame(b)
        assertEquals(checkFrame, decoded)
    }

    @Test
    fun settings_frame() {
        codecFrameCheck(
            settingsOf(
                SettingId.MAX_HEADER_LIST_SIZE to 0xfad1, SettingId.QPACK_MAX_TABLE_CAPACITY to 0xfad2,
                SettingId.QPACK_MAX_BLOCKED_STREAMS to 0xfad3, 95L to 0,
            ),
            bytes(4, 18, 6, 128, 0, 250, 209, 1, 128, 0, 250, 210, 7, 128, 0, 250, 211, 64, 95, 0),
            // Without the GREASE setting, which is ignored.
            settingsOf(
                SettingId.MAX_HEADER_LIST_SIZE to 0xfad1, SettingId.QPACK_MAX_TABLE_CAPACITY to 0xfad2,
                SettingId.QPACK_MAX_BLOCKED_STREAMS to 0xfad3,
            ),
        )
    }

    @Test
    fun settings_frame_emtpy() {
        codecFrameCheck(Settings(), bytes(4, 0), Settings())
    }

    @Test
    fun data_frame() {
        codecFrameCheck(
            Frame.Data(Bytes.wrap("1234567".encodeToByteArray())),
            bytes(0, 7, 49, 50, 51, 52, 53, 54, 55),
            Frame.Data(Bytes.wrap("1234567".encodeToByteArray())),
        )
    }

    @Test
    fun simple_frames() {
        codecFrameCheck(Frame.CancelPush(2), bytes(3, 1, 2), Frame.CancelPush(2))
        codecFrameCheck(Frame.Goaway(2), bytes(7, 1, 2), Frame.Goaway(2))
        codecFrameCheck(Frame.MaxPushId(2), bytes(13, 1, 2), Frame.MaxPushId(2))
    }

    @Test
    fun headers_frames() {
        codecFrameCheck(
            Frame.headers("TODO QPACK"),
            bytes(1, 10, 84, 79, 68, 79, 32, 81, 80, 65, 67, 75),
            Frame.headers("TODO QPACK"),
        )
        val pp = PushPromise(134, Bytes.wrap("TODO QPACK".encodeToByteArray()))
        codecFrameCheck(pp, bytes(5, 12, 64, 134, 84, 79, 68, 79, 32, 81, 80, 65, 67, 75), pp)
    }

    @Test
    fun reserved_frame() {
        val raw = Buffer()
        VarInt.encode(0x21L + 2 * 0x1f, raw)
        raw.writeBytes(bytes(6, 0, 255, 128, 0, 250, 218))
        assertNull(decoder.decodeFrame(raw))
        assertEquals(FrameDecoder.UNKNOWN, decoder.status)
        assertEquals(95L, decoder.unknownType)
    }

    // ---- ⚖️ limits and layout checks ----

    @Test
    fun headersLongerThanTheLimitAreRejectedFromTheFrameHeader() {
        val d = FrameDecoder(maxHeadersFrameSize = 100)
        // Only the type and the declared length (101) have arrived.
        val e = assertFailsWith<FrameException> { d.decode(buf(1, 0x40, 101)) }
        assertEquals(FrameError.HeadersTooLarge(FrameType.HEADERS, 101, 100), e.error)
        assertEquals(Code.H3_EXCESSIVE_LOAD, e.code)
        // At the limit: waits for the payload, then decodes it.
        val ok = FrameDecoder(maxHeadersFrameSize = 100)
        val b = buf(1, 0x40, 100)
        assertNull(ok.decode(b))
        b.writeBytes(ByteArray(100))
        assertEquals(Frame.Headers(Bytes.wrap(ByteArray(100))), ok.decode(b))
    }

    @Test
    fun pushPromiseLongerThanTheLimitIsRejectedFromTheFrameHeader() {
        val e = assertFailsWith<FrameException> { FrameDecoder(maxHeadersFrameSize = 10).decode(buf(5, 11)) }
        assertEquals(FrameError.HeadersTooLarge(FrameType.PUSH_PROMISE, 11, 10), e.error)
    }

    @Test
    fun hugeDeclaredHeadersLengthIsRejectedWithoutOverflow() {
        val b = Buffer()
        VarInt.encode(FrameType.HEADERS, b)
        VarInt.encode(VarInt.MAX, b)
        assertEquals(FrameError.HeadersTooLarge(FrameType.HEADERS, VarInt.MAX, 65536), decodeError(b))
    }

    @Test
    fun settingsLongerThanTheCapAreRejected() {
        val b = Buffer()
        VarInt.encode(FrameType.SETTINGS, b)
        VarInt.encode(MAX_SETTINGS_PAYLOAD + 1L, b)
        assertEquals(FrameError.TooLarge(FrameType.SETTINGS, MAX_SETTINGS_PAYLOAD + 1L), decodeError(b))
    }

    @Test
    fun singleVarintFramesMustHoldExactlyOneVarint() {
        for (type in listOf(3, 7, 13)) {
            // An extra byte after the varint: the reference left it in the stream as the start of the next frame.
            assertEquals(FrameError.Malformed(type.toLong()), decodeError(buf(type, 2, 2, 0)))
            // Empty payload: the reference waited forever.
            assertEquals(FrameError.Malformed(type.toLong()), decodeError(buf(type, 0)))
            // The varint announces more bytes than the payload has.
            assertEquals(FrameError.Malformed(type.toLong()), decodeError(buf(type, 1, 0x40)))
            // Longer than any varint: rejected from the frame header.
            assertEquals(FrameError.Malformed(type.toLong()), decodeError(buf(type, 9)))
            assertEquals(Code.H3_FRAME_ERROR, FrameError.Malformed(type.toLong()).code)
        }
        // A two-byte varint filling the payload is fine.
        assertEquals(Frame.Goaway(300), FrameDecoder().decode(buf(7, 2, 0x41, 0x2c)))
    }

    @Test
    fun http2FrameTypesAreRejectedFromTheFrameHeader() {
        for (type in listOf(2, 6, 8, 9)) {
            val e = assertFailsWith<FrameException> { FrameDecoder().decode(buf(type, 10)) }
            assertEquals(FrameError.UnsupportedFrame(type.toLong()), e.error)
            assertEquals(Code.H3_FRAME_UNEXPECTED, e.code)
        }
    }

    @Test
    fun unknownFramesAreSkippedAsTheyArriveWithoutBuffering() {
        val d = FrameDecoder()
        val b = Buffer()
        VarInt.encode(FrameType.RESERVED, b)
        VarInt.encode(1_000_000, b)
        b.writeBytes(ByteArray(1000))
        assertNull(d.decode(b))
        assertTrue(b.isEmpty, "the available payload is dropped")
        assertTrue(d.isSkipping)
        repeat(998) {
            b.writeBytes(ByteArray(1000))
            assertNull(d.decode(b))
            assertTrue(b.isEmpty)
        }
        b.writeBytes(ByteArray(1000))
        Frame.Goaway(4).encode(b)
        assertEquals(Frame.Goaway(4), d.decode(b))
        assertTrue(!d.isSkipping && b.isEmpty)
    }

    @Test
    fun webTransportStreamFrameIsAnUnknownFrameWithoutWebTransport() {
        val d = FrameDecoder()
        val b = buf(0x40, 0x41, 2, 9, 9, 7, 1, 5)
        assertEquals(Frame.Goaway(5), d.decode(b))
    }

    @Test
    fun greaseFrameIsSkipped() {
        val b = Buffer()
        Frame.Grease.encode(b)
        Frame.MaxPushId(1).encode(b)
        assertEquals(Frame.MaxPushId(1), FrameDecoder().decode(b))
        repeat(1000) { assertTrue(FrameType.isGrease(FrameType.grease(Random(it)))) }
        assertTrue(FrameType.isGrease(FrameType.RESERVED))
        assertTrue(!FrameType.isGrease(FrameType.WEBTRANSPORT_BI_STREAM))
    }

    // ---- SETTINGS rules (SPEC §5) ----

    private fun settingsPayload(vararg values: Long): Buffer {
        val payload = Buffer()
        values.forEach { VarInt.encode(it, payload) }
        val b = Buffer()
        VarInt.encode(FrameType.SETTINGS, b)
        VarInt.encode(payload.readableBytes.toLong(), b)
        b.writeBytes(payload.readAll())
        return b
    }

    @Test
    fun settingsDuplicateIsAnError() {
        val e = assertFailsWith<FrameException> {
            FrameDecoder().decode(settingsPayload(SettingId.MAX_HEADER_LIST_SIZE, 1, SettingId.MAX_HEADER_LIST_SIZE, 2))
        }
        assertEquals(FrameError.Settings(SettingsError.Repeated(SettingId.MAX_HEADER_LIST_SIZE)), e.error)
        assertEquals(Code.H3_SETTINGS_ERROR, e.code)
    }

    @Test
    fun settingsHttp2ReservedIdsAreAnError() {
        for (id in listOf(0L, 2L, 3L, 4L, 5L)) {
            val e = assertFailsWith<FrameException> { FrameDecoder().decode(settingsPayload(id, 0)) }
            assertEquals(FrameError.Settings(SettingsError.InvalidSettingId(id)), e.error)
            assertEquals(Code.H3_SETTINGS_ERROR, e.code)
        }
    }

    @Test
    fun settingsUnknownAndGreaseIdsAreIgnored() {
        val s = FrameDecoder().decode(
            settingsPayload(0x21, 7, SettingId.grease(Random(1)), 8, 0x1234, 9, SettingId.QPACK_MAX_TABLE_CAPACITY, 0),
        ) as Settings
        assertEquals(1, s.len)
        assertEquals(0L, s.get(SettingId.QPACK_MAX_TABLE_CAPACITY))
        assertNull(s.get(0x21))
        // ⚖️ Only present entries are looked at (the reference's get(0) found an unused slot).
        assertNull(s.get(0))
    }

    @Test
    fun settingsMalformedPayload() {
        // A lone byte left.
        assertEquals(FrameError.Settings(SettingsError.Malformed), decodeError(buf(4, 3, 6, 1, 6)))
        // The value's varint runs past the payload.
        assertEquals(FrameError.Settings(SettingsError.Malformed), decodeError(buf(4, 2, 6, 0x40)))
    }

    @Test
    fun settingsInsertLimits() {
        val s = Settings()
        for (i in 0 until Settings.SETTINGS_LEN) assertNull(s.insert(0x100L + i, i.toLong()))
        assertEquals(SettingsError.Exceeded, s.insert(0x200, 0))
        val t = Settings()
        assertNull(t.insert(6, 1))
        assertEquals(SettingsError.Repeated(6), t.insert(6, 2))
    }

    @Test
    fun allSupportedSettingsRoundTrip() {
        val s = settingsOf(
            SettingId.MAX_HEADER_LIST_SIZE to 65536, SettingId.QPACK_MAX_TABLE_CAPACITY to 0,
            SettingId.QPACK_MAX_BLOCKED_STREAMS to 0, SettingId.ENABLE_CONNECT_PROTOCOL to 1,
            SettingId.ENABLE_WEBTRANSPORT to 0, SettingId.WEBTRANSPORT_MAX_SESSIONS to 0, SettingId.H3_DATAGRAM to 0,
            SettingId.grease(Random(3)) to 1,
        )
        val b = Buffer()
        s.encode(b)
        val decoded = FrameDecoder().decode(b) as Settings
        assertEquals(7, decoded.len)
        assertEquals(65536L, decoded.get(SettingId.MAX_HEADER_LIST_SIZE))
        assertEquals(1L, decoded.get(SettingId.ENABLE_CONNECT_PROTOCOL))
    }
}
