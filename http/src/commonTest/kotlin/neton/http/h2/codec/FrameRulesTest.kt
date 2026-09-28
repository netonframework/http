package neton.http.h2.codec

import neton.http.Method
import neton.http.h2.frame.Data
import neton.http.h2.frame.FrameError
import neton.http.h2.frame.FrameException
import neton.http.h2.frame.GoAway
import neton.http.h2.frame.Head
import neton.http.h2.frame.Headers
import neton.http.h2.frame.Kind
import neton.http.h2.frame.Ping
import neton.http.h2.frame.Priority
import neton.http.h2.frame.Reason
import neton.http.h2.frame.Reset
import neton.http.h2.frame.Settings
import neton.http.h2.frame.StreamDependency
import neton.http.h2.frame.StreamId
import neton.http.h2.frame.StreamIdOverflow
import neton.http.h2.frame.WindowUpdate
import neton.http.h2.hpack.Header
import neton.http.h2.proto.IoErrorKind
import neton.http.h2.proto.ProtoError
import neton.http.h2.raw
import neton.io.bytes.Buffer
import neton.io.bytes.Bytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

// SPEC §4.1 "帧": the parsing rules and error mapping of every frame type, frame by frame as in h2 0.4.19
// (`src/frame/*.rs` and `decode_frame`). h2 answers most malformed frames with GOAWAY PROTOCOL_ERROR (where RFC 9113
// sometimes names FRAME_SIZE_ERROR); these tests pin h2's mapping.

class FrameRulesTest {
    private val getBlock = hpack(Header.Method(Method.GET))

    private fun one(bytes: ByteArray) = readAll(bytes).single()

    // ===== DATA =====

    @Test
    fun dataOnStreamZeroIsConnectionError() = assertGoAway(frame(0, 0, 0, "x"), Reason.PROTOCOL_ERROR)

    @Test
    fun dataPaddedWithoutPadLengthIsConnectionError() = assertGoAway(frame(0, 0x8, 1), Reason.PROTOCOL_ERROR)

    @Test
    fun dataPaddingAsLongAsPayloadIsConnectionError() {
        // The pad length must be less than the payload length (it counts itself).
        assertGoAway(frame(0, 0x8, 1, 3, 0, 0), Reason.PROTOCOL_ERROR)
        assertGoAway(frame(0, 0x8, 1, 5, 0), Reason.PROTOCOL_ERROR)
    }

    @Test
    fun dataAllPaddingIsEmptyAndFlowControlled() {
        val data = assertIs<Data>(one(frame(0, 0x9, 1, 2, 0, 0)))
        assertTrue(data.payload.isEmpty)
        assertTrue(data.isEndStream)
        assertTrue(data.isPadded)
        assertEquals(3, data.flowControlledLen())
    }

    @Test
    fun dataUnknownFlagsAreDropped() {
        val data = assertIs<Data>(one(frame(0, 0xff, 1, 0, "ab")))
        assertEquals(Head(Kind.Data, 0x9, StreamId(1)), data.head())
        assertEquals("ab", data.payload.decodeToString())
    }

    @Test
    fun dataPayloadIsASliceOfTheReadBuffer() {
        val buf = Buffer().also { it.writeBytes(frame(0, 0, 1, "hello")) }
        val data = assertIs<Data>(FramedRead().decode(buf))
        assertTrue(buf.isEmpty)
        buf.writeBytes("XXXXXXXXXXXXXX".encodeToByteArray())
        // The buffer does not write over the payload (neton-io SPEC §23.7).
        assertEquals("hello", data.payload.decodeToString())
    }

    @Test
    fun smallDataPayloadIsCopiedOutOfTheReadBuffer() {
        // A slice would make the connection's read buffer move to a fresh array on its next read.
        val buf = Buffer().also { it.writeBytes(frame(0, 0, 1, "hello")) }
        val arr = buf.backingArray()
        val data = assertIs<Data>(FramedRead().decode(buf))
        arr[9] = 'j'.code.toByte()
        assertEquals("hello", data.payload.decodeToString())
        // The buffer keeps its array for the next read.
        buf.writeBytes("x".encodeToByteArray())
        assertTrue(buf.backingArray() === arr)
    }

    @Test
    fun largeDataPayloadIsASliceOfTheReadBuffer() {
        val payload = ByteArray(DATA_COPY_LIMIT + 1) { 'a'.code.toByte() }
        val buf = Buffer(DATA_COPY_LIMIT + 64).also { it.writeBytes(frame(0, 0, 1, payload)) }
        val arr = buf.backingArray()
        val data = assertIs<Data>(FramedRead(maxFrameSize = 32 * 1024).decode(buf))
        assertEquals(DATA_COPY_LIMIT + 1, data.payload.size)
        arr[9] = 'b'.code.toByte() // the slice shares the buffer's array
        assertEquals('b'.code.toByte(), data.payload[0])
    }

    // ===== HEADERS =====

    @Test
    fun headersOnStreamZeroIsConnectionError() = assertGoAway(frame(1, 0x4, 0, getBlock), Reason.PROTOCOL_ERROR)

    @Test
    fun headersPaddedWithoutPadLengthIsConnectionError() = assertGoAway(frame(1, 0x4 or 0x8, 1), Reason.PROTOCOL_ERROR)

    @Test
    fun headersTooMuchPaddingIsConnectionError() =
        assertGoAway(frame(1, 0x4 or 0x8, 1, 2, getBlock), Reason.PROTOCOL_ERROR)

    @Test
    fun headersPaddingIsStripped() {
        val h = assertIs<Headers>(one(frame(1, 0x4 or 0x8, 1, 3, getBlock, 0, 0, 0)))
        assertEquals(Method.GET, h.pseudo.method)
        // A pad length equal to what is left leaves an empty block.
        val empty = assertIs<Headers>(one(frame(1, 0x4 or 0x8, 3, 2, 0, 0)))
        assertTrue(empty.fields.isEmpty())
    }

    @Test
    fun headersPriorityIsParsedAndIgnored() {
        val h = assertIs<Headers>(one(frame(1, 0x4 or 0x20, 3, 0x80, 0, 0, 1, 15, getBlock)))
        assertEquals(StreamDependency(StreamId(1), 15, true), h.streamDep)
        assertEquals(Method.GET, h.pseudo.method)
    }

    @Test
    fun headersPriorityTooShortIsConnectionError() =
        assertGoAway(frame(1, 0x4 or 0x20, 3, 0, 0, 0, 1), Reason.PROTOCOL_ERROR)

    @Test
    fun headersDependingOnItselfIsStreamError() =
        assertReset(frame(1, 0x4 or 0x20, 3, 0, 0, 0, 3, 15, getBlock), 3)

    @Test
    fun headersStreamIdReservedBitIsIgnored() {
        val h = assertIs<Headers>(one(frame(1, 0x4, 0x80000001.toInt(), getBlock)))
        assertEquals(StreamId(1), h.streamId)
    }

    // ===== PRIORITY =====

    @Test
    fun priorityIsParsed() {
        val p = assertIs<Priority>(one(frame(2, 0, 3, 0, 0, 0, 1, 200)))
        assertEquals(StreamId(3), p.streamId)
        assertEquals(StreamDependency(StreamId(1), 200, false), p.dependency)
    }

    @Test
    fun priorityOnStreamZeroIsConnectionError() = assertGoAway(frame(2, 0, 0, 0, 0, 0, 1, 1), Reason.PROTOCOL_ERROR)

    @Test
    fun priorityWrongLengthIsConnectionError() {
        assertGoAway(frame(2, 0, 3, 0, 0, 0, 1), Reason.PROTOCOL_ERROR)
        assertGoAway(frame(2, 0, 3, 0, 0, 0, 1, 1, 1), Reason.PROTOCOL_ERROR)
    }

    @Test
    fun priorityDependingOnItselfIsStreamError() = assertReset(frame(2, 0, 3, 0, 0, 0, 3, 1), 3)

    // ===== RST_STREAM =====

    @Test
    fun resetIsParsedWithAnyCode() {
        assertEquals(Reset(StreamId(1), Reason.CANCEL), one(frame(3, 0, 1, 0, 0, 0, 8)))
        val r = assertIs<Reset>(one(frame(3, 0, 1, 0xff, 0xff, 0xff, 0xff)))
        assertEquals(-1, r.reason.code)
        assertEquals("Reason(ffffffff)", r.reason.toString())
    }

    @Test
    fun resetWrongLengthIsConnectionError() {
        assertGoAway(frame(3, 0, 1, 0, 0, 8), Reason.PROTOCOL_ERROR)
        assertGoAway(frame(3, 0, 1, 0, 0, 0, 0, 8), Reason.PROTOCOL_ERROR)
    }

    // ===== SETTINGS =====

    private fun setting(id: Int, value: Long) =
        raw(id ushr 8, id, (value ushr 24).toInt(), (value ushr 16).toInt(), (value ushr 8).toInt(), value.toInt())

    @Test
    fun settingsAreParsed() {
        val s = assertIs<Settings>(
            one(
                frame(
                    4, 0, 0,
                    setting(1, 0), setting(2, 0), setting(3, 100), setting(4, 1_000_000), setting(5, 32_768),
                    setting(6, 0xffffffffL), setting(8, 1), setting(0x99, 7),
                ),
            ),
        )
        assertFalse(s.isAck)
        assertEquals(0L, s.headerTableSize)
        assertEquals(false, s.isPushEnabled)
        assertEquals(100L, s.maxConcurrentStreams)
        assertEquals(1_000_000L, s.initialWindowSize)
        assertEquals(32_768L, s.maxFrameSize)
        assertEquals(0xffffffffL, s.maxHeaderListSize)
        assertEquals(true, s.isExtendedConnectProtocolEnabled)
    }

    @Test
    fun settingsUnknownIdentifiersAreIgnored() {
        assertEquals(Settings(), one(frame(4, 0, 0, setting(7, 1), setting(0xffff, 9))))
    }

    @Test
    fun settingsNotOnStreamZeroIsConnectionError() = assertGoAway(frame(4, 0, 1), Reason.PROTOCOL_ERROR)

    @Test
    fun settingsAckWithPayloadIsConnectionError() = assertGoAway(frame(4, 1, 0, setting(1, 0)), Reason.PROTOCOL_ERROR)

    @Test
    fun settingsAck() = assertTrue(assertIs<Settings>(one(frame(4, 1, 0))).isAck)

    @Test
    fun settingsLengthNotMultipleOfSixIsConnectionError() =
        assertGoAway(frame(4, 0, 0, setting(1, 0), 0), Reason.PROTOCOL_ERROR)

    @Test
    fun settingsEnablePushMustBeZeroOrOne() {
        assertEquals(true, assertIs<Settings>(one(frame(4, 0, 0, setting(2, 1)))).isPushEnabled)
        assertGoAway(frame(4, 0, 0, setting(2, 2)), Reason.PROTOCOL_ERROR)
    }

    @Test
    fun settingsInitialWindowSizeUpperBound() {
        assertEquals(0x7fffffffL, assertIs<Settings>(one(frame(4, 0, 0, setting(4, 0x7fffffffL)))).initialWindowSize)
        assertGoAway(frame(4, 0, 0, setting(4, 0x80000000L)), Reason.PROTOCOL_ERROR)
    }

    @Test
    fun settingsMaxFrameSizeRange() {
        assertEquals(16_384L, assertIs<Settings>(one(frame(4, 0, 0, setting(5, 16_384)))).maxFrameSize)
        assertEquals(16_777_215L, assertIs<Settings>(one(frame(4, 0, 0, setting(5, 16_777_215)))).maxFrameSize)
        assertGoAway(frame(4, 0, 0, setting(5, 16_383)), Reason.PROTOCOL_ERROR)
        assertGoAway(frame(4, 0, 0, setting(5, 16_777_216)), Reason.PROTOCOL_ERROR)
        assertFailsWith<IllegalArgumentException> { Settings().maxFrameSize = 16_383 }
    }

    @Test
    fun settingsEnableConnectProtocolMustBeZeroOrOne() {
        assertEquals(false, assertIs<Settings>(one(frame(4, 0, 0, setting(8, 0)))).isExtendedConnectProtocolEnabled)
        assertEquals(true, assertIs<Settings>(one(frame(4, 0, 0, setting(8, 1)))).isExtendedConnectProtocolEnabled)
        assertGoAway(frame(4, 0, 0, setting(8, 2)), Reason.PROTOCOL_ERROR)
    }

    @Test
    fun settingsEncodeInReferenceOrder() {
        val s = Settings()
        s.enableConnectProtocol = 1
        s.maxHeaderListSize = 16_384
        s.maxFrameSize = 16_384
        s.initialWindowSize = 65_535
        s.maxConcurrentStreams = 100
        s.setEnablePush(false)
        s.headerTableSize = 4096
        val b = Buffer().also { s.encode(it) }.peekAll()
        assertEquals(
            raw(
                0, 0, 42, 4, 0, 0, 0, 0, 0,
                setting(1, 4096), setting(2, 0), setting(3, 100), setting(4, 65_535), setting(5, 16_384),
                setting(6, 16_384), setting(8, 1),
            ).toList(),
            b.toList(),
        )
        assertEquals(s, one(b))
    }

    // ===== PING =====

    @Test
    fun pingIsParsed() {
        val p = assertIs<Ping>(one(frame(6, 1, 0, 1, 2, 3, 4, 5, 6, 7, 8)))
        assertTrue(p.isAck)
        assertEquals(0x0102030405060708L, p.payload)
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5, 6, 7, 8), p.payloadBytes().toList())
    }

    @Test
    fun pingNotOnStreamZeroIsConnectionError() =
        assertGoAway(frame(6, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0), Reason.PROTOCOL_ERROR)

    @Test
    fun pingWrongLengthIsConnectionError() {
        assertGoAway(frame(6, 0, 0, 0, 0, 0, 0, 0, 0, 0), Reason.PROTOCOL_ERROR)
        assertGoAway(frame(6, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), Reason.PROTOCOL_ERROR)
    }

    @Test
    fun pingRoundTrip() {
        for (p in listOf(Ping(Ping.SHUTDOWN), Ping.pong(Ping.USER), Ping(-1L))) {
            assertEquals(p, one(Buffer().also { p.encode(it) }.peekAll()))
        }
    }

    // ===== GOAWAY =====

    @Test
    fun goAwayTooShortIsConnectionError() = assertGoAway(frame(7, 0, 0, 0, 0, 0, 1, 0, 0, 0), Reason.PROTOCOL_ERROR)

    @Test
    fun goAwayIgnoresReservedBitAndStreamId() {
        // As in the reference, GOAWAY's stream ID is not checked by the frame parser.
        val g = assertIs<GoAway>(one(frame(7, 0, 5, 0x80, 0, 0, 7, 0, 0, 0, 0)))
        assertEquals(StreamId(7), g.lastStreamId)
        assertEquals(Reason.NO_ERROR, g.reason)
        assertTrue(g.debugData.isEmpty)
    }

    @Test
    fun goAwayRoundTrip() {
        val g = GoAway(StreamId(9), Reason.ENHANCE_YOUR_CALM, Bytes.wrap("calm".encodeToByteArray()))
        assertEquals(g, one(Buffer().also { g.encode(it) }.peekAll()))
    }

    // ===== WINDOW_UPDATE =====

    @Test
    fun windowUpdateWrongLengthIsConnectionError() = assertGoAway(frame(8, 0, 1, 0, 0, 1), Reason.PROTOCOL_ERROR)

    @Test
    fun windowUpdateZeroIsConnectionError() {
        assertGoAway(frame(8, 0, 1, 0, 0, 0, 0), Reason.PROTOCOL_ERROR)
        // The reserved bit is ignored, so this is 0 as well.
        assertGoAway(frame(8, 0, 0, 0x80, 0, 0, 0), Reason.PROTOCOL_ERROR)
    }

    @Test
    fun windowUpdateReservedBitIsIgnored() {
        assertEquals(WindowUpdate(StreamId(1), 0x7fffffff), one(frame(8, 0, 1, 0xff, 0xff, 0xff, 0xff)))
        val w = WindowUpdate(StreamId.ZERO, 1)
        assertEquals(w, one(Buffer().also { w.encode(it) }.peekAll()))
    }

    // ===== PUSH_PROMISE =====

    @Test
    fun pushPromiseOnStreamZeroIsConnectionError() =
        assertGoAway(frame(5, 0x4, 0, 0, 0, 0, 2, getBlock), Reason.PROTOCOL_ERROR)

    @Test
    fun pushPromiseShorterThanFiveBytesIsConnectionError() =
        assertGoAway(frame(5, 0x4, 1, 0, 0, 0, 2), Reason.PROTOCOL_ERROR)

    @Test
    fun pushPromisePaddedWithoutPadLengthIsConnectionError() =
        assertGoAway(frame(5, 0x4 or 0x8, 1), Reason.PROTOCOL_ERROR)

    @Test
    fun pushPromiseTooMuchPaddingIsConnectionError() =
        assertGoAway(frame(5, 0x4 or 0x8, 1, 3, 0, 0, 0, 2, getBlock), Reason.PROTOCOL_ERROR)

    @Test
    fun pushPromisePaddingIsStrippedAndReservedBitIgnored() {
        val pp = assertIs<neton.http.h2.frame.PushPromise>(one(frame(5, 0x4 or 0x8, 1, 2, 0x80, 0, 0, 2, getBlock, 0, 0)))
        assertEquals(StreamId(2), pp.promisedId)
        assertEquals(Method.GET, pp.pseudo.method)
    }

    // ===== Reason, StreamId, Head =====

    @Test
    fun reasonCodes() {
        val names = listOf(
            "NO_ERROR", "PROTOCOL_ERROR", "INTERNAL_ERROR", "FLOW_CONTROL_ERROR", "SETTINGS_TIMEOUT", "STREAM_CLOSED",
            "FRAME_SIZE_ERROR", "REFUSED_STREAM", "CANCEL", "COMPRESSION_ERROR", "CONNECT_ERROR", "ENHANCE_YOUR_CALM",
            "INADEQUATE_SECURITY", "HTTP_1_1_REQUIRED",
        )
        val constants = listOf(
            Reason.NO_ERROR, Reason.PROTOCOL_ERROR, Reason.INTERNAL_ERROR, Reason.FLOW_CONTROL_ERROR,
            Reason.SETTINGS_TIMEOUT, Reason.STREAM_CLOSED, Reason.FRAME_SIZE_ERROR, Reason.REFUSED_STREAM,
            Reason.CANCEL, Reason.COMPRESSION_ERROR, Reason.CONNECT_ERROR, Reason.ENHANCE_YOUR_CALM,
            Reason.INADEQUATE_SECURITY, Reason.HTTP_1_1_REQUIRED,
        )
        for (code in 0..13) {
            assertEquals(Reason(code), constants[code])
            assertEquals(names[code], Reason(code).toString())
            assertTrue(Reason(code).description() != "unknown reason")
        }
        assertEquals("frame with invalid size", Reason.FRAME_SIZE_ERROR.description())
        assertEquals("Reason(e)", Reason(14).toString())
        assertEquals("unknown reason", Reason(14).description())
    }

    @Test
    fun streamIds() {
        assertEquals(StreamId(5), StreamId.parse(byteArrayOf(0x80.toByte(), 0, 0, 5), 0))
        assertTrue(StreamId.parseFlag(byteArrayOf(0x80.toByte(), 0, 0, 5), 0))
        assertTrue(StreamId(1).isClientInitiated)
        assertFalse(StreamId(1).isServerInitiated)
        assertTrue(StreamId(2).isServerInitiated)
        assertFalse(StreamId.ZERO.isClientInitiated)
        assertFalse(StreamId.ZERO.isServerInitiated)
        assertEquals(StreamId(3), StreamId(1).nextId())
        assertEquals(StreamId.MAX, StreamId(Int.MAX_VALUE - 2).nextId())
        assertFailsWith<StreamIdOverflow> { StreamId.MAX.nextId() }
        assertFailsWith<StreamIdOverflow> { StreamId(Int.MAX_VALUE - 1).nextId() }
        assertFailsWith<IllegalArgumentException> { StreamId(-1) }
    }

    @Test
    fun headEncodeAndParse() {
        val head = Head(Kind.Headers, 0x25, StreamId(0x12345678))
        val b = Buffer().also { head.encode(0x0abcde, it) }.peekAll()
        assertEquals(raw(0x0a, 0xbc, 0xde, 1, 0x25, 0x12, 0x34, 0x56, 0x78).toList(), b.toList())
        assertEquals(head, Head.parse(b))
        assertEquals(0x0abcde, Head.payloadLength(b))
        assertEquals(Kind.Unknown, Head.parse(raw(0, 0, 0, 10, 0, 0, 0, 0, 0)).kind)
        assertEquals(Kind.Unknown, Head.parse(raw(0, 0, 0, 0xff, 0, 0, 0, 0, 0)).kind)
    }

    @Test
    fun frameLoadErrorsCarryTheirKind() {
        val e = assertFailsWith<FrameException> { Ping.load(Head(Kind.Ping, 0, StreamId.ZERO), ByteArray(7), 0, 7) }
        assertEquals(FrameError.BadFrameSize, e.error)
        assertNull(e.hpack)
    }

    @Test
    fun decodeEofWithAnIncompleteFrameIsAnIoError() {
        val buf = Buffer().also { it.writeBytes(frame(0, 0, 1, "hello").copyOf(10)) }
        assertNull(FramedRead().decode(buf))
        val e = assertFailsWith<ProtoError.Io> { FramedRead().decodeEof(buf) }
        assertEquals(IoErrorKind.Other, e.kind)
        assertEquals("bytes remaining on stream", e.message)
    }
}
