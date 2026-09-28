package neton.http.h2.codec

import neton.http.h2.frame.ByteWindow
import neton.http.h2.frame.DEFAULT_MAX_FRAME_SIZE
import neton.http.h2.frame.DEFAULT_SETTINGS_HEADER_TABLE_SIZE
import neton.http.h2.frame.Data
import neton.http.h2.frame.Frame
import neton.http.h2.frame.FrameError
import neton.http.h2.frame.FrameException
import neton.http.h2.frame.GoAway
import neton.http.h2.frame.HEADER_LEN
import neton.http.h2.frame.Head
import neton.http.h2.frame.Headers
import neton.http.h2.frame.Kind
import neton.http.h2.frame.MAX_MAX_FRAME_SIZE
import neton.http.h2.frame.Ping
import neton.http.h2.frame.Priority
import neton.http.h2.frame.PushPromise
import neton.http.h2.frame.Reason
import neton.http.h2.frame.Reset
import neton.http.h2.frame.Settings
import neton.http.h2.frame.StreamId
import neton.http.h2.frame.WindowUpdate
import neton.http.h2.hpack.Decoder
import neton.http.h2.proto.IoErrorKind
import neton.http.h2.proto.ProtoError
import neton.io.bytes.Buffer
import neton.io.bytes.Bytes

/** 16 MiB "sane default" taken from golang http2 (`DEFAULT_SETTINGS_MAX_HEADER_LIST_SIZE`). */
const val DEFAULT_SETTINGS_MAX_HEADER_LIST_SIZE: Int = 16 shl 20

/**
 * Reads HTTP/2 frames out of a byte buffer (`h2::codec::FramedRead` with its length-delimited framing,
 * `src/codec/framed_read.rs`). Sans-I/O: the connection appends what it reads from its `IoStream` to a [Buffer] and
 * calls [decode] until it returns null.
 *
 * - Frames are delimited by their 3-byte length; a length above [maxFrameSize] (the local SETTINGS_MAX_FRAME_SIZE)
 *   is a GOAWAY FRAME_SIZE_ERROR, raised as soon as the length is readable.
 * - Frames of unknown type are skipped.
 * - A HEADERS or PUSH_PROMISE without END_HEADERS is held until its CONTINUATION frames complete it; any other
 *   frame in between is a GOAWAY PROTOCOL_ERROR, and more than [maxContinuationFrames] of them a GOAWAY
 *   ENHANCE_YOUR_CALM. Header blocks are HPACK-decoded fragment by fragment, so the decoder's dynamic table stays in
 *   sync even for rejected messages.
 * - Errors are thrown as [ProtoError]: [ProtoError.Reset] for a stream error, [ProtoError.GoAway] for a connection
 *   error, exactly where the reference returns them.
 *
 * DATA payloads up to [DATA_COPY_LIMIT] bytes are copied out of the read buffer into right-sized arrays; larger ones
 * are zero-copy slices of it ([Buffer.readSlice]): a slice makes the buffer share its array, so the connection's next
 * read would move to a fresh array of the buffer's whole capacity for every small frame. Header block fragments are decoded
 * straight from the read buffer; only the bytes of a representation split across frames are copied, into a buffer
 * owned by this reader and reused.
 *
 * ⚖️ Sans-I/O: the reference reads through tokio's `FramedRead` + `LengthDelimitedCodec` over the transport; here
 * the length framing works on the caller's buffer, and [decodeEof] stands for the end of the stream (tokio's
 * `decode_eof`: an incomplete frame left over is an I/O error, "bytes remaining on stream").
 *
 * Not thread-safe: use it from the connection's reactor.
 */
class FramedRead(maxFrameSize: Int = DEFAULT_MAX_FRAME_SIZE) {
    private val hpack = Decoder(DEFAULT_SETTINGS_HEADER_TABLE_SIZE)

    /** The largest frame payload accepted (`max_frame_size`); must be within 16,384..16,777,215. */
    var maxFrameSize: Int = DEFAULT_MAX_FRAME_SIZE
        set(value) {
            require(value in DEFAULT_MAX_FRAME_SIZE..MAX_MAX_FRAME_SIZE) { "max frame size out of range: $value" }
            field = value
            // The CONTINUATION limit depends on it.
            maxContinuationFrames = calcMaxContinuationFrames(maxHeaderListSize, value)
        }

    /** The local SETTINGS_MAX_HEADER_LIST_SIZE (`set_max_header_list_size`), 16 MiB by default. */
    var maxHeaderListSize: Int = DEFAULT_SETTINGS_MAX_HEADER_LIST_SIZE
        set(value) {
            field = value
            maxContinuationFrames = calcMaxContinuationFrames(value, maxFrameSize)
        }

    /** How many CONTINUATION frames without END_HEADERS one header block may have. */
    var maxContinuationFrames: Int = calcMaxContinuationFrames(DEFAULT_SETTINGS_MAX_HEADER_LIST_SIZE, DEFAULT_MAX_FRAME_SIZE)
        private set

    // The partially received header block (`partial: Option<Partial>`): the frame, the bytes of an incomplete
    // representation carried over to the next CONTINUATION, and the CONTINUATION count.
    private var partialFrame: Frame? = null
    private var partialBuf = ByteArray(0)
    private var partialLen = 0
    private var continuationFramesCount = 0

    private val window = ByteWindow()

    init {
        this.maxFrameSize = maxFrameSize
    }

    /** Queues a new local SETTINGS_HEADER_TABLE_SIZE for the HPACK decoder, once acknowledged (`set_header_table_size`). */
    fun setHeaderTableSize(value: Int) {
        hpack.queueSizeUpdate(value)
    }

    /**
     * Decodes the next frame from [buf], consuming its bytes; returns null when [buf] holds no complete frame (frames
     * that yield nothing, unknown ones and incomplete header blocks, are consumed on the way).
     * @throws ProtoError for a stream or connection error.
     */
    fun decode(buf: Buffer): Frame? {
        while (true) {
            val readable = buf.readableBytes
            if (readable < 3) return null
            val array = buf.backingArray()
            val start = buf.readerIndex()
            val len = Head.payloadLength(array, start)
            if (len > maxFrameSize) throw ProtoError.libraryGoAway(Reason.FRAME_SIZE_ERROR)
            if (readable < HEADER_LEN + len) return null
            val frame = decodeFrame(buf, array, start, len)
            if (frame != null) return frame
        }
    }

    /**
     * [decode] at the end of the input: returns null when [buf] is empty.
     * @throws ProtoError [ProtoError.Io] when an incomplete frame remains ("bytes remaining on stream"), or as [decode].
     */
    fun decodeEof(buf: Buffer): Frame? {
        val frame = decode(buf)
        if (frame == null && !buf.isEmpty) throw ProtoError.Io(IoErrorKind.Other, "bytes remaining on stream")
        return frame
    }

    /** `decode_frame`: one complete frame at `array[start]`, [len] payload bytes. */
    private fun decodeFrame(buf: Buffer, array: ByteArray, start: Int, len: Int): Frame? {
        val head = Head.parse(array, start)
        val kind = head.kind
        val p = start + HEADER_LEN

        if (partialFrame != null && kind != Kind.Continuation) {
            buf.skip(HEADER_LEN + len)
            // "expected CONTINUATION, got {kind}"
            throw ProtoError.libraryGoAway(Reason.PROTOCOL_ERROR)
        }

        if (kind == Kind.Data) {
            buf.skip(HEADER_LEN)
            val payload = takePayload(buf, len)
            return try {
                Data.load(head, payload)
            } catch (e: FrameException) {
                // As the reference notes: always connection level (TODO there).
                throw ProtoError.libraryGoAway(Reason.PROTOCOL_ERROR)
            }
        }

        // Every other kind is parsed straight from the read buffer; its bytes are consumed first (the array stays
        // valid: nothing is written to the buffer before this returns).
        buf.skip(HEADER_LEN + len)
        return try {
            when (kind) {
                Kind.Settings -> Settings.load(head, array, p, len)
                Kind.Ping -> Ping.load(head, array, p, len)
                Kind.WindowUpdate -> WindowUpdate.load(head, array, p, len)
                Kind.Headers, Kind.PushPromise -> headerBlock(head, array, p, len)
                Kind.Reset -> Reset.load(head, array, p, len)
                Kind.GoAway -> GoAway.load(array, p, len)
                Kind.Priority -> priority(head, array, p, len)
                Kind.Continuation -> continuation(head, array, p, len)
                Kind.Unknown -> null // Unknown frames are ignored.
                Kind.Data -> throw IllegalStateException()
            }
        } catch (e: FrameException) {
            // "failed to load <kind> frame": the errors that are not stream errors are handled here.
            throw ProtoError.libraryGoAway(Reason.PROTOCOL_ERROR)
        }
    }

    private fun priority(head: Head, array: ByteArray, p: Int, len: Int): Frame {
        if (head.streamId.isZero) throw ProtoError.libraryGoAway(Reason.PROTOCOL_ERROR) // "invalid stream ID 0"
        try {
            return Priority.load(head, array, p, len)
        } catch (e: FrameException) {
            // A stream cannot depend on itself: a stream error of type PROTOCOL_ERROR.
            if (e.error == FrameError.InvalidDependencyId) throw ProtoError.libraryReset(head.streamId, Reason.PROTOCOL_ERROR)
            throw e
        }
    }

    /** The reference's `header_block!` macro, for HEADERS and PUSH_PROMISE. */
    private fun headerBlock(head: Head, array: ByteArray, p: Int, len: Int): Frame? {
        window.set(array, p, p + len)
        val frame: Frame = try {
            if (head.kind == Kind.Headers) Headers.load(head, window) else PushPromise.load(head, window)
        } catch (e: FrameException) {
            if (e.error == FrameError.InvalidDependencyId) {
                // "invalid HEADERS dependency ID": a stream error of type PROTOCOL_ERROR.
                throw ProtoError.libraryReset(head.streamId, Reason.PROTOCOL_ERROR)
            }
            throw e
        }
        val isEndHeaders = isEndHeaders(frame)
        loadHpack(frame, head.streamId, isEndHeaders)
        if (isEndHeaders) return frame

        // Defer returning the frame until its CONTINUATION frames arrive, keeping an incomplete representation.
        partialFrame = frame
        continuationFramesCount = 0
        keepLeftover()
        return null
    }

    private fun continuation(head: Head, array: ByteArray, p: Int, len: Int): Frame? {
        val isEndHeaders = head.flag and 0x4 == 0x4
        // "received unexpected CONTINUATION frame"
        val frame = partialFrame ?: throw ProtoError.libraryGoAway(Reason.PROTOCOL_ERROR)
        partialFrame = null

        // "CONTINUATION frame stream ID does not match previous frame stream ID"
        if (streamIdOf(frame) != head.streamId) throw ProtoError.libraryGoAway(Reason.PROTOCOL_ERROR)

        // Check for a CONTINUATION flood.
        if (isEndHeaders) {
            continuationFramesCount = 0
        } else {
            val cnt = continuationFramesCount + 1
            if (cnt > maxContinuationFrames) {
                throw ProtoError.libraryGoAwayData(Reason.ENHANCE_YOUR_CALM, "too_many_continuations")
            }
            continuationFramesCount = cnt
        }

        if (partialLen == 0) {
            window.set(array, p, p + len)
        } else {
            if (isOverSize(frame)) {
                // Left-over bytes may still be needed to keep the HPACK state in sync although the block is being
                // ignored; but a gigantic string spread over many frames must not grow memory without bound. (The
                // reference compares against the whole frame, header included.)
                if (partialLen + HEADER_LEN + len > maxHeaderListSize) {
                    throw ProtoError.libraryGoAway(Reason.COMPRESSION_ERROR)
                }
            }
            ensurePartialCapacity(partialLen + len)
            array.copyInto(partialBuf, partialLen, p, p + len)
            partialLen += len
            window.set(partialBuf, 0, partialLen)
        }

        loadHpack(frame, head.streamId, isEndHeaders)
        if (isEndHeaders) {
            partialLen = 0
            setEndHeaders(frame)
            return frame
        }
        partialFrame = frame
        keepLeftover()
        return null
    }

    /** Loads the fragment in [window] into [frame], mapping the errors as the reference does. */
    private fun loadHpack(frame: Frame, streamId: StreamId, isEndHeaders: Boolean) {
        try {
            when (frame) {
                is Headers -> frame.loadHpack(window, maxHeaderListSize, hpack)
                is PushPromise -> frame.loadHpack(window, maxHeaderListSize, hpack)
                else -> throw IllegalStateException()
            }
        } catch (e: FrameException) {
            when {
                e.error == FrameError.Hpack && e.hpack?.needMore != null && !isEndHeaders -> {}
                e.error == FrameError.MalformedMessage -> {
                    // ⚖️ The reference resets the stream at once, even when CONTINUATION frames are still to come:
                    // the rest of the block is then never decoded (the HPACK tables go out of sync) and the next
                    // CONTINUATION is a connection error. Here the reset waits for END_HEADERS.
                    if (!isEndHeaders) return
                    throw ProtoError.libraryReset(streamId, Reason.PROTOCOL_ERROR) // "malformed header block"
                }
                e.error == FrameError.HeaderListWayTooLarge ->
                    throw ProtoError.libraryGoAwayData(Reason.ENHANCE_YOUR_CALM, "header_list_way_too_large")
                else -> throw ProtoError.libraryGoAway(Reason.PROTOCOL_ERROR) // "failed HPACK decoding"
            }
        }
    }

    /** Keeps the unconsumed bytes of [window] (an incomplete representation) in [partialBuf]. */
    private fun keepLeftover() {
        val n = window.length
        if (n > 0) {
            ensurePartialCapacity(n)
            window.array.copyInto(partialBuf, 0, window.start, window.end)
        }
        partialLen = n
    }

    private fun ensurePartialCapacity(n: Int) {
        if (partialBuf.size < n) partialBuf = partialBuf.copyOf(maxOf(n, partialBuf.size * 2, 256))
    }

    private fun isEndHeaders(frame: Frame): Boolean = when (frame) {
        is Headers -> frame.isEndHeaders
        is PushPromise -> frame.isEndHeaders
        else -> true
    }

    private fun setEndHeaders(frame: Frame) {
        when (frame) {
            is Headers -> frame.setEndHeaders()
            is PushPromise -> frame.setEndHeaders()
            else -> {}
        }
    }

    private fun streamIdOf(frame: Frame): StreamId = when (frame) {
        is Headers -> frame.streamId
        is PushPromise -> frame.streamId
        else -> throw IllegalStateException()
    }

    private fun isOverSize(frame: Frame): Boolean = when (frame) {
        is Headers -> frame.isOverSize
        is PushPromise -> frame.isOverSize
        else -> false
    }

    companion object {
        /** `calc_max_continuation_frames`: max(5, n + n / 4) with n = max(1, header list max / frame max). */
        internal fun calcMaxContinuationFrames(headerMax: Int, frameMax: Int): Int {
            // At least this many frames are needed to use the max header list size.
            val minFramesForList = maxOf(headerMax / frameMax, 1)
            // Some padding for imperfectly packed frames: 25%.
            val padding = minFramesForList shr 2
            val total = minFramesForList.toLong() + padding
            return maxOf(if (total > Int.MAX_VALUE) Int.MAX_VALUE else total.toInt(), 5)
        }
    }
}

/** DATA payloads up to this size are copied out of the read buffer; larger ones are zero-copy slices of it. */
internal const val DATA_COPY_LIMIT = 16 * 1024

/** A DATA payload of [n] bytes from [buf] (see [FramedRead]: copied when small, sliced when large). */
private fun takePayload(buf: Buffer, n: Int): Bytes {
    if (n > DATA_COPY_LIMIT) return buf.readSlice(n)
    if (n == 0) return Bytes.EMPTY
    val at = buf.readerIndex()
    val out = Bytes.copyOf(buf.backingArray(), at, at + n)
    buf.skip(n)
    return out
}
