package neton.http.h3

import neton.http.h3.proto.Frame
import neton.http.h3.proto.FrameError
import neton.http.h3.proto.FrameException
import neton.http.h3.proto.FrameType
import neton.http.h3.proto.PushPromise
import neton.http.h3.proto.Settings
import neton.http.h3.proto.VarInt
import neton.io.bytes.Buffer
import neton.io.bytes.Bytes

/** Default `maxHeadersFrameSize`: 64 KiB of encoded HEADERS payload (SPEC §5, "the three header limits"). */
const val DEFAULT_MAX_HEADERS_FRAME_SIZE: Int = 64 * 1024

/**
 * ⚖️ The largest SETTINGS payload accepted: the reference buffers a SETTINGS frame of any declared length. Unknown
 * (GREASE) settings are ignored but still have to be read, so the cap is generous.
 */
const val MAX_SETTINGS_PAYLOAD: Int = 16 * 1024

/** Payloads up to this size are copied out of the read buffer, larger ones are zero-copy slices (as in the h2 codec). */
internal const val PAYLOAD_COPY_LIMIT: Int = 16 * 1024

/**
 * Decodes HTTP/3 frames from a byte buffer (`h3::frame::FrameDecoder` and `Frame::decode` of `src/proto/frame.rs`).
 * Sans-I/O: the caller appends received bytes to a [Buffer] and calls [decode] until it returns null. DATA frames
 * yield their header only ([Frame.Data] with the payload length); the payload stays in the buffer for the caller
 * ([FrameStream] streams it).
 *
 * Differences from the reference (⚖️), all to bound memory and follow RFC 9114 §7.1:
 * - Frames of unknown type (including GREASE and, without WebTransport, `WEBTRANSPORT_BI_STREAM`) are skipped as their
 *   bytes arrive; the reference buffers the whole frame first, whatever its declared length.
 * - HEADERS and PUSH_PROMISE longer than [maxHeadersFrameSize] are rejected from the frame header
 *   ([FrameError.HeadersTooLarge]), before any payload is buffered; the reference has no such limit.
 * - SETTINGS longer than [MAX_SETTINGS_PAYLOAD] are rejected ([FrameError.TooLarge]).
 * - CANCEL_PUSH, GOAWAY and MAX_PUSH_ID must hold exactly one varint: extra or missing bytes are H3_FRAME_ERROR
 *   ([FrameError.Malformed]). The reference reads the varint and leaves any extra payload bytes in the stream, to be
 *   parsed as the next frame, and waits forever on an empty payload.
 * - HTTP/2-only frame types are rejected as soon as their header is read, not after their payload is buffered.
 *
 * Not thread-safe.
 */
class FrameDecoder(maxHeadersFrameSize: Int = DEFAULT_MAX_HEADERS_FRAME_SIZE) {
    /** The largest HEADERS / PUSH_PROMISE payload accepted, in bytes. */
    var maxHeadersFrameSize: Int = maxHeadersFrameSize
        set(value) {
            require(value >= 0) { "negative limit: $value" }
            field = value
        }

    init {
        require(maxHeadersFrameSize >= 0) { "negative limit: $maxHeadersFrameSize" }
    }

    /** What the last [decodeFrame] returned: [OK], [INCOMPLETE] or [UNKNOWN]. */
    var status: Int = OK
        private set

    /** After [INCOMPLETE]: a lower bound of the bytes needed (the reference's `Incomplete(n)`). */
    var incompleteMin: Int = 0
        private set

    /** After [UNKNOWN]: the type of the skipped frame (the reference's `UnknownFrame(ty)`). */
    var unknownType: Long = 0
        private set

    // Payload bytes of an unknown frame still to be skipped.
    private var skipRemaining = 0L

    // The reference's `expected`: bytes needed before trying again.
    private var expected = 0

    /** Whether the decoder is inside an unknown frame whose payload has not all arrived. */
    val isSkipping: Boolean get() = skipRemaining > 0

    /**
     * Decodes the next frame from [buf], consuming it (`FrameDecoder::decode`); unknown frames are skipped on the way.
     * Returns null when [buf] holds no complete frame.
     * @throws FrameException for a protocol error.
     */
    fun decode(buf: Buffer): Frame? {
        while (true) {
            if (buf.isEmpty && skipRemaining == 0L) return null
            if (expected > 0 && buf.readableBytes < expected) return null
            val frame = decodeFrame(buf)
            when (status) {
                OK -> { expected = 0; return frame }
                UNKNOWN -> { expected = 0 }
                else -> { expected = incompleteMin; return null }
            }
        }
    }

    /**
     * One decoding step (`Frame::decode`): returns a frame ([status] [OK]), or null with [status] [INCOMPLETE] (nothing
     * consumed, unless the rest of an unknown frame was skipped) or [UNKNOWN] (an unknown frame's header and the
     * payload bytes available were consumed; the rest is skipped by the next calls).
     * @throws FrameException for a protocol error.
     */
    fun decodeFrame(buf: Buffer): Frame? {
        if (skipRemaining > 0) {
            val n = minOf(skipRemaining, buf.readableBytes.toLong()).toInt()
            buf.skip(n)
            skipRemaining -= n
            if (skipRemaining > 0) return incomplete(1)
        }
        val a = buf.backingArray()
        val p = buf.readerIndex()
        val end = buf.writerIndex()
        val remaining = end - p
        val type = VarInt.decode(a, p, end)
        if (type < 0) return incomplete(remaining + 1)
        val q = p + VarInt.encodedSize(a[p])
        val len = VarInt.decode(a, q, end)
        if (len < 0) return incomplete(remaining + 1)
        val header = q + VarInt.encodedSize(a[q]) - p

        when (type) {
            FrameType.DATA -> {
                buf.skip(header)
                status = OK
                return Frame.Data(len)
            }
            FrameType.HEADERS, FrameType.PUSH_PROMISE ->
                if (len > maxHeadersFrameSize) throw FrameException(FrameError.HeadersTooLarge(type, len, maxHeadersFrameSize))
            FrameType.SETTINGS -> if (len > MAX_SETTINGS_PAYLOAD) throw FrameException(FrameError.TooLarge(type, len))
            FrameType.CANCEL_PUSH, FrameType.GOAWAY, FrameType.MAX_PUSH_ID ->
                if (len > VarInt.MAX_SIZE) throw FrameException(FrameError.Malformed(type))
            // RFC 9114 §7.2.8: HTTP/2 frame types must not be received (H3_FRAME_UNEXPECTED).
            FrameType.H2_PRIORITY, FrameType.H2_PING, FrameType.H2_WINDOW_UPDATE, FrameType.H2_CONTINUATION ->
                throw FrameException(FrameError.UnsupportedFrame(type))
            else -> {
                // Unknown and reserved types have no meaning (RFC 9114 §9): skip the payload.
                buf.skip(header)
                val n = minOf(len, buf.readableBytes.toLong()).toInt()
                buf.skip(n)
                skipRemaining = len - n
                unknownType = type
                status = UNKNOWN
                return null
            }
        }

        // `len` is now bounded by one of the limits above.
        val length = len.toInt()
        if (remaining - header < length) return incomplete(2 + length)
        val s = p + header
        val e = s + length
        val frame: Frame = when (type) {
            FrameType.HEADERS -> {
                buf.skip(header)
                return ok(Frame.Headers(takePayload(buf, length)))
            }
            FrameType.SETTINGS -> Settings.decode(a, s, e)
            FrameType.CANCEL_PUSH -> Frame.CancelPush(exactVarint(type, a, s, e))
            FrameType.GOAWAY -> Frame.Goaway(exactVarint(type, a, s, e))
            FrameType.MAX_PUSH_ID -> Frame.MaxPushId(exactVarint(type, a, s, e))
            FrameType.PUSH_PROMISE -> {
                val id = VarInt.decode(a, s, e)
                if (id < 0) throw FrameException(FrameError.Malformed(type))
                val idLen = VarInt.encodedSize(a[s])
                buf.skip(header + idLen)
                return ok(PushPromise(id, takePayload(buf, length - idLen)))
            }
            else -> throw IllegalStateException()
        }
        buf.skip(header + length)
        return ok(frame)
    }

    private fun ok(frame: Frame): Frame {
        status = OK
        return frame
    }

    private fun incomplete(min: Int): Frame? {
        status = INCOMPLETE
        incompleteMin = min
        return null
    }

    /** The payload `a[s, e)` of a frame made of exactly one varint (RFC 9114 §7.1). */
    private fun exactVarint(type: Long, a: ByteArray, s: Int, e: Int): Long {
        val v = VarInt.decode(a, s, e)
        if (v < 0 || VarInt.encodedSize(a[s]) != e - s) throw FrameException(FrameError.Malformed(type))
        return v
    }

    companion object {
        /** [decodeFrame] returned a frame. */
        const val OK: Int = 0

        /** [decodeFrame] needs more bytes ([incompleteMin]). */
        const val INCOMPLETE: Int = 1

        /** [decodeFrame] consumed an unknown frame ([unknownType]). */
        const val UNKNOWN: Int = 2
    }
}

/**
 * The receiving side of a stream carrying frames (`h3::frame::FrameStream`), without I/O: received bytes go into
 * [buffer] ([onData], or read straight into it), the end of the stream is signalled with [onEnd], and the frames come
 * out of [nextFrame]; after a DATA frame its payload comes out of [nextData], in pieces as it arrives, never buffered
 * whole.
 *
 * ⚖️ The reference keeps received chunks in a `BufList` and hands out DATA payload chunk by chunk as received; here the
 * bytes are appended to one [Buffer], so payload bytes already received come out together, whatever the chunks were.
 *
 * Not thread-safe.
 */
class FrameStream(maxHeadersFrameSize: Int = DEFAULT_MAX_HEADERS_FRAME_SIZE) {
    /** The received bytes not yet decoded; the transport may read into it directly. */
    val buffer: Buffer = Buffer()

    private val decoder = FrameDecoder(maxHeadersFrameSize)

    /** Whether the end of the stream was received ([onEnd]); buffered bytes may remain. */
    var isEos: Boolean = false
        private set

    /** Payload bytes of the current DATA frame not yet returned by [nextData]. */
    var remainingData: Long = 0
        private set

    /** The limit on HEADERS / PUSH_PROMISE payloads ([FrameDecoder.maxHeadersFrameSize]). */
    var maxHeadersFrameSize: Int
        get() = decoder.maxHeadersFrameSize
        set(value) { decoder.maxHeadersFrameSize = value }

    /** Appends received bytes. */
    fun onData(src: ByteArray, off: Int = 0, len: Int = src.size - off) {
        buffer.writeBytes(src, off, len)
    }

    /** Appends received bytes. */
    fun onData(src: Bytes) {
        buffer.writeBytes(src)
    }

    /** The peer finished the stream. */
    fun onEnd() {
        isEos = true
    }

    /** Whether a DATA payload is being read (`has_data`). */
    val hasData: Boolean get() = remainingData != 0L

    /** The stream ended and everything received was consumed (`poll_next` returning `None`, `is_eos`). */
    val isFinished: Boolean get() = isEos && buffer.isEmpty && remainingData == 0L && !decoder.isSkipping

    /**
     * The next frame (`poll_next`), or null when none is complete yet or the stream is [isFinished]. A DATA frame sets
     * [remainingData]; its payload must then be read with [nextData] before the next frame.
     * @throws IllegalStateException when DATA payload is still unread (the reference panics the same way).
     * @throws FrameException for a protocol error, or [FrameError.UnexpectedEnd] when the stream ended inside a frame.
     */
    fun nextFrame(): Frame? {
        check(remainingData == 0L) { "There is still data to read, please call nextData() until it returns null." }
        val frame = decoder.decode(buffer)
        if (frame != null) {
            if (frame is Frame.Data) remainingData = frame.length
            return frame
        }
        if (isEos && (!buffer.isEmpty || decoder.isSkipping)) throw FrameException(FrameError.UnexpectedEnd)
        return null
    }

    /**
     * The next piece of the current DATA payload (`poll_data`): the bytes received so far, up to [remainingData].
     * Returns null when the payload is complete ([hasData] false) or nothing is buffered yet.
     * @throws FrameException [FrameError.UnexpectedEnd] when the stream ended before the payload.
     */
    fun nextData(): Bytes? {
        if (remainingData == 0L) return null
        val available = buffer.readableBytes
        if (isEos && available < remainingData) throw FrameException(FrameError.UnexpectedEnd)
        if (available == 0) return null
        val n = minOf(available.toLong(), remainingData).toInt()
        remainingData -= n
        return takePayload(buffer, n)
    }
}

/** [n] bytes taken from [buf]: a copy up to [PAYLOAD_COPY_LIMIT] bytes, else a zero-copy slice. */
internal fun takePayload(buf: Buffer, n: Int): Bytes {
    if (n > PAYLOAD_COPY_LIMIT) return buf.readSlice(n)
    if (n == 0) return Bytes.EMPTY
    val at = buf.readerIndex()
    val out = Bytes.copyOf(buf.backingArray(), at, at + n)
    buf.skip(n)
    return out
}
