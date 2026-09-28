package neton.http.h2.codec

import neton.http.Method
import neton.http.h2.Frames
import neton.http.h2.buildLargeHeaders
import neton.http.h2.drain
import neton.http.h2.frame.Data
import neton.http.h2.frame.DEFAULT_MAX_FRAME_SIZE
import neton.http.h2.frame.Frame
import neton.http.h2.frame.GoAway
import neton.http.h2.frame.HEADER_LEN
import neton.http.h2.frame.Head
import neton.http.h2.frame.Headers
import neton.http.h2.frame.Kind
import neton.http.h2.frame.Ping
import neton.http.h2.frame.Priority
import neton.http.h2.frame.PushPromise
import neton.http.h2.frame.Reason
import neton.http.h2.frame.Reset
import neton.http.h2.frame.Settings
import neton.http.h2.frame.StreamDependency
import neton.http.h2.frame.StreamId
import neton.http.h2.frame.WindowUpdate
import neton.http.h2.hpack.Header
import neton.http.h2.raw
import neton.http.h2.write
import neton.http.header.HeaderMap
import neton.http.header.HeaderName
import neton.http.header.HeaderValue
import neton.io.bytes.Buffer
import neton.io.bytes.Bytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

// SPEC §4.1 "读" and "写": length framing against the local max frame size, unknown frame types, CONTINUATION
// sequencing and flood limit; the reusable write buffer, DATA chaining thresholds, CONTINUATION splitting, WriteZero.

class CodecRulesTest {
    private val getBlock = hpack(Header.Method(Method.GET))

    // ===== read =====

    @Test
    fun frameLongerThanLocalMaxIsFrameSizeError() {
        // Reported as soon as the length is readable, before the payload arrives.
        val buf = Buffer().also { it.writeBytes(raw(0, 0x40, 0x01)) }
        val e = assertFailsWith<neton.http.h2.proto.ProtoError.GoAway> { FramedRead().decode(buf) }
        assertEquals(Reason.FRAME_SIZE_ERROR, e.reason)
    }

    @Test
    fun frameAtLocalMaxIsAccepted() {
        val max = frame(0, 0, 1, ByteArray(DEFAULT_MAX_FRAME_SIZE))
        assertEquals(DEFAULT_MAX_FRAME_SIZE, assertIs<Data>(readAll(max).single()).payload.size)

        val big = frame(0, 0, 1, ByteArray(20_000))
        assertGoAway(big, Reason.FRAME_SIZE_ERROR)
        val reader = FramedRead(20_000)
        assertEquals(20_000, assertIs<Data>(readAll(big, reader).single()).payload.size)
    }

    @Test
    fun localMaxFrameSizeRange() {
        val reader = FramedRead()
        assertFailsWith<IllegalArgumentException> { reader.maxFrameSize = 16_383 }
        assertFailsWith<IllegalArgumentException> { reader.maxFrameSize = 1 shl 24 }
        reader.maxFrameSize = (1 shl 24) - 1
        assertFailsWith<IllegalArgumentException> { FramedRead(0) }
    }

    @Test
    fun unknownFrameTypesAreIgnored() {
        val frames = readAll(
            raw(
                frame(10, 0xff, 1, "ignored"),
                frame(0, 0, 1, "a"),
                frame(0xff, 0, 0),
                frame(0x42, 0, 7, ByteArray(100)),
                frame(0, 0, 1, "b"),
            ),
        )
        assertEquals(listOf("a", "b"), frames.map { assertIs<Data>(it).payload.decodeToString() })
    }

    @Test
    fun frameOtherThanContinuationInsideAHeaderBlockIsConnectionError() {
        assertGoAway(raw(frame(1, 0, 1, getBlock), frame(0, 0, 1, "x")), Reason.PROTOCOL_ERROR)
        assertGoAway(raw(frame(1, 0, 1, getBlock), frame(1, 0x4, 3, getBlock)), Reason.PROTOCOL_ERROR)
        // Unknown frame types too.
        assertGoAway(raw(frame(1, 0, 1, getBlock), frame(10, 0, 1)), Reason.PROTOCOL_ERROR)
        assertGoAway(raw(frame(5, 0, 1, 0, 0, 0, 2, getBlock), frame(6, 0, 0, ByteArray(8))), Reason.PROTOCOL_ERROR)
    }

    @Test
    fun continuationWithoutHeaderBlockIsConnectionError() =
        assertGoAway(frame(9, 0x4, 1, getBlock), Reason.PROTOCOL_ERROR)

    @Test
    fun continuationOnAnotherStreamIsConnectionError() =
        assertGoAway(raw(frame(1, 0, 1, getBlock), frame(9, 0x4, 3)), Reason.PROTOCOL_ERROR)

    @Test
    fun continuationCompletesPushPromise() {
        // (As in the reference, PUSH_PROMISE must carry at least one byte of the block besides the promised ID.)
        val block = hpack(Header.Method(Method.GET), Header.Path("/x"))
        val pp = assertIs<PushPromise>(
            readAll(raw(frame(5, 0, 1, 0, 0, 0, 2, block.copyOf(1)), frame(9, 0x4, 1, block.copyOfRange(1, block.size)))).single(),
        )
        assertTrue(pp.isEndHeaders)
        assertEquals(StreamId(2), pp.promisedId)
        assertEquals(Method.GET, pp.pseudo.method)
        assertEquals("/x", pp.pseudo.path)
    }

    @Test
    fun continuationFloodIsEnhanceYourCalm() {
        val reader = FramedRead().apply { maxHeaderListSize = 16_384 } // 1 frame's worth: the minimum, 5
        assertEquals(5, reader.maxContinuationFrames)

        fun block(continuations: Int, end: Boolean): ByteArray {
            val parts = ArrayList<ByteArray>()
            parts.add(frame(1, 0, 1))
            repeat(continuations) { parts.add(frame(9, 0, 1)) }
            if (end) parts.add(frame(9, 0x4, 1, getBlock))
            return raw(*parts.toTypedArray())
        }
        assertIs<Headers>(readAll(block(5, true), reader).single())
        assertGoAway(block(6, false), Reason.ENHANCE_YOUR_CALM, "too_many_continuations", FramedRead().apply { maxHeaderListSize = 16_384 })
    }

    @Test
    fun maxContinuationFrames() {
        assertEquals(5, FramedRead.calcMaxContinuationFrames(0, 16_384))
        assertEquals(5, FramedRead.calcMaxContinuationFrames(64 * 1024, 16_384))
        assertEquals(1280, FramedRead.calcMaxContinuationFrames(16 shl 20, 16_384))
        val reader = FramedRead()
        assertEquals(1280, reader.maxContinuationFrames)
        reader.maxFrameSize = 32_768
        assertEquals(640, reader.maxContinuationFrames)
        reader.maxHeaderListSize = 1 shl 20
        assertEquals(40, reader.maxContinuationFrames)
    }

    @Test
    fun framesArrivingByteByByte() {
        val encoder = neton.http.h2.hpack.Encoder()
        val bytes = raw(
            frame(4, 0, 0, 0, 3, 0, 0, 0, 100),
            frame(1, 0x20, 1, 0, 0, 0, 0, 16, hpack(Header.Method(Method.GET), encoder = encoder)),
            frame(9, 0, 1, hpack(Header.Path("/a"), encoder = encoder)),
            frame(9, 0x4, 1),
            frame(0, 0x9, 1, 3, "data", 0, 0, 0),
            frame(0xf0, 0, 0, "unknown"),
            frame(8, 0, 1, 0, 0, 1, 0),
            frame(7, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, "bye"),
        )
        val whole = readAll(bytes)
        assertEquals(5, whole.size)

        val reader = FramedRead()
        val buf = Buffer()
        val got = ArrayList<Frame>()
        for (b in bytes) {
            buf.writeByte(b)
            while (true) got.add(reader.decode(buf) ?: break)
        }
        assertEquals(whole, got)
        assertTrue(buf.isEmpty)
    }

    // ===== write =====

    @Test
    fun writeBufferStartsAt16KiB() {
        val w = FramedWrite()
        assertEquals(16 * 1024, w.writeBuffer.capacity)
        assertTrue(w.hasCapacity())
    }

    @Test
    fun dataBelowChainThresholdIsCopied() {
        val w = FramedWrite()
        assertEquals(256, w.chainThreshold)
        val frame = Data(StreamId(1), Bytes.wrap(ByteArray(255) { 7 }))
        assertNull(w.buffer(frame))
        assertNull(w.queuedPayload)
        assertEquals(HEADER_LEN + 255, w.writeBuffer.readableBytes)
        assertSame(frame, w.takeLastDataFrame())
        assertTrue(w.hasCapacity())
    }

    @Test
    fun dataAtChainThresholdIsChained() {
        val w = FramedWrite()
        val payload = Bytes.wrap(ByteArray(256) { it.toByte() })
        val frame = Data(StreamId(1), payload)
        assertNull(w.buffer(frame))
        // The head plus enough payload bytes to reach the threshold are buffered; the rest is queued, not copied.
        assertEquals(256, w.writeBuffer.readableBytes)
        assertEquals(payload.slice(247), w.queuedPayload)
        assertFalse(w.hasCapacity())
        assertFailsWith<IllegalStateException> { w.buffer(Ping(1)) }
        assertNull(w.takeLastDataFrame())

        val bytes = w.drain()
        assertEquals(raw(0, 1, 0, 0, 0, 0, 0, 0, 1, payload.toByteArray()).toList(), bytes.toList())
        assertSame(frame, w.takeLastDataFrame())
        assertTrue(w.hasCapacity())
    }

    @Test
    fun dataIsChainedWithoutCopyWhenTheBufferHoldsEnough() {
        val w = FramedWrite()
        assertNull(w.buffer(Data(StreamId(1), Bytes.wrap(ByteArray(200)))))
        assertNull(w.buffer(Data(StreamId(3), Bytes.wrap(ByteArray(200)))))
        val payload = Bytes.wrap(ByteArray(10_000) { 1 })
        assertNull(w.buffer(Data(StreamId(5), payload)))
        assertSame(payload, w.queuedPayload)
        assertEquals(2 * (HEADER_LEN + 200) + HEADER_LEN, w.writeBuffer.readableBytes)
        assertEquals(2 * (HEADER_LEN + 200) + HEADER_LEN + 10_000, w.drain().size)
    }

    @Test
    fun chainThresholdWithoutVectoredIoIs1024() {
        val w = FramedWrite(vectoredIo = false)
        assertEquals(1024, w.chainThreshold)
        assertNull(w.buffer(Data(StreamId(1), Bytes.wrap(ByteArray(1023)))))
        assertNull(w.queuedPayload)
        assertNull(w.buffer(Data(StreamId(1), Bytes.wrap(ByteArray(1024)))))
        assertNotNull(w.queuedPayload)
    }

    @Test
    fun dataLargerThanPeerMaxFrameSizeIsRejected() {
        val w = FramedWrite()
        assertEquals(UserError.PayloadTooBig, w.buffer(Data(StreamId(1), Bytes.wrap(ByteArray(DEFAULT_MAX_FRAME_SIZE + 1)))))
        assertTrue(w.isEmpty)
        assertNull(w.buffer(Data(StreamId(1), Bytes.wrap(ByteArray(DEFAULT_MAX_FRAME_SIZE)))))
        w.drain()
        w.maxFrameSize = 20_000
        assertNull(w.buffer(Data(StreamId(1), Bytes.wrap(ByteArray(20_000)))))
        assertFailsWith<IllegalArgumentException> { w.maxFrameSize = 1 shl 24 }
    }

    @Test
    fun partialWritesAcrossBufferAndPayload() {
        val w = FramedWrite()
        val payload = Bytes.wrap(ByteArray(1000) { (it * 7).toByte() })
        assertNull(w.buffer(Data(StreamId(1), payload)))
        val out = ArrayList<Byte>()
        while (!w.isEmpty) {
            // One byte per write: from the buffer first, then from the queued payload.
            val b = if (!w.writeBuffer.isEmpty) w.writeBuffer.getByte(0) else w.queuedPayload!![0]
            out.add(b)
            w.advance(1)
        }
        assertFalse(w.unsetFrame())
        assertEquals(raw(0, 0x03, 0xe8, 0, 0, 0, 0, 0, 1, payload.toByteArray()).toList(), out)
        assertFailsWith<IllegalArgumentException> { w.advance(-1) }
        w.advance(0) // nothing left: not an error
    }

    @Test
    fun headersAreSplitIntoContinuationsAtPeerMaxFrameSize() {
        for (max in listOf(DEFAULT_MAX_FRAME_SIZE, 20_000, 100_000)) {
            val w = FramedWrite()
            w.maxFrameSize = max
            val frame = buildLargeHeaders().fold(Frames.headers(1).response(200)) { f, (n, v) -> f.field(n, v) }.frame
            val wire = w.write(frame)
            var at = 0
            val kinds = ArrayList<Kind>()
            while (at < wire.size) {
                val len = Head.payloadLength(wire, at)
                assertTrue(len <= max)
                kinds.add(Head.parse(wire, at).kind)
                at += HEADER_LEN + len
                if (at < wire.size) assertEquals(max, len) // every frame but the last is full
            }
            assertEquals(Kind.Headers, kinds.first())
            assertTrue(kinds.drop(1).all { it == Kind.Continuation })
            assertEquals(max == 100_000, kinds.size == 1)
            assertEquals(frame, readAll(wire, FramedRead(max)).single())
        }
    }

    @Test
    fun pushPromiseIsSplitIntoContinuations() {
        val w = FramedWrite()
        val fields = HeaderMap<HeaderValue>()
        for ((n, v) in buildLargeHeaders()) fields.append(HeaderName.fromStr(n), HeaderValue.fromStr(v))
        val pp = PushPromise(StreamId(1), StreamId(2), neton.http.h2.frame.Pseudo.request(Method.GET, neton.http.uri.Uri.parse("https://a/b")), fields)
        val wire = w.write(pp)
        assertEquals(Kind.PushPromise, Head.parse(wire).kind)
        assertEquals(DEFAULT_MAX_FRAME_SIZE, Head.payloadLength(wire))
        assertEquals(pp, readAll(wire).single())
    }

    @Test
    fun sendingPriorityIsNotImplemented() {
        assertFailsWith<NotImplementedError> {
            FramedWrite().buffer(Priority(StreamId(1), StreamDependency(StreamId.ZERO, 0, false)))
        }
    }

    @Test
    fun framesRoundTrip() {
        val fields = HeaderMap<HeaderValue>()
        fields.append(HeaderName.fromStr("x-a"), HeaderValue.fromStr("1"))
        fields.append(HeaderName.fromStr("x-a"), HeaderValue.fromStr("2"))
        val frames = listOf(
            Frames.settings().maxConcurrentStreams(100).initialWindowSize(1L shl 20).enableConnectProtocol(1).frame,
            Settings.ack(),
            Ping(Ping.USER),
            Ping.pong(Ping.SHUTDOWN),
            GoAway(StreamId(7), Reason.NO_ERROR),
            WindowUpdate(StreamId(3), 1000),
            Reset(StreamId(3), Reason.CANCEL),
            Frames.data(1, "small").eos().frame,
            Frames.data(3, "x".repeat(3000)).frame,
            Frames.headers(1).request("POST", "https://example.com/upload?x=1").fields(fields).frame,
            Headers.trailers(StreamId(1), fields),
            Frames.pushPromise(1, 2).request("GET", "https://example.com/style.css").frame,
        )
        val w = FramedWrite()
        val wire = raw(*frames.map { w.write(it) }.toTypedArray())
        assertEquals(frames, readAll(wire))
    }
}
