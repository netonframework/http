package neton.http.h2.frame

import neton.io.bytes.Buffer
import neton.io.bytes.Bytes

/**
 * A DATA frame (`h2::frame::Data`, `src/frame/data.rs`): a chunk of a stream's body.
 *
 * The payload is a zero-copy [Bytes] slice: on read it points into the connection's read buffer, without the padding.
 * [padLen] is the padding length of a received padded frame (-1 when it was not padded); it matters for flow
 * control ([flowControlledLen]).
 */
class Data internal constructor(
    val streamId: StreamId,
    val payload: Bytes,
    private var flags: Int,
    internal val padLen: Int,
) : Frame() {
    /** A DATA frame for [streamId], which must not be 0 (`Data::new`). */
    constructor(streamId: StreamId, payload: Bytes) : this(requireNonZero(streamId), payload, 0, -1)

    /** The `END_STREAM` flag: this is the last frame the endpoint sends on the stream. */
    val isEndStream: Boolean get() = flags and END_STREAM != 0

    /** Sets or clears `END_STREAM` (`set_end_stream`). */
    fun setEndStream(value: Boolean) {
        flags = if (value) flags or END_STREAM else flags and END_STREAM.inv()
    }

    /** The `PADDED` flag (`is_padded`, an `unstable` API in the reference). */
    val isPadded: Boolean get() = flags and PADDED != 0

    /**
     * Sets `PADDED` (`set_padded`, an `unstable` API in the reference, used by its tests). As in the reference,
     * encoding does not write any padding.
     */
    internal fun setPadded() {
        flags = flags or PADDED
    }

    /** The frame header (without length). */
    fun head(): Head = Head(Kind.Data, flags, streamId)

    /** The bytes this frame counts against flow control: the payload plus, when padded, the padding and its length byte. */
    fun flowControlledLen(): Int = if (padLen >= 0) payload.size + padLen + 1 else payload.size

    /** Appends the frame header and the whole payload to [dst] (`encode_chunk`). */
    fun encodeChunk(dst: Buffer) {
        head().encode(payload.size, dst)
        dst.writeBytes(payload)
    }

    override fun equals(other: Any?): Boolean =
        other is Data && other.streamId == streamId && other.flags == flags && other.padLen == padLen && other.payload == payload

    override fun hashCode(): Int = (streamId.value * 31 + flags) * 31 + payload.hashCode()

    override fun toString(): String = buildString {
        append("Data { stream_id: ").append(streamId.value)
        if (flags != 0) append(", flags: ").append(debugFlags(flags, END_STREAM to "END_STREAM", PADDED to "PADDED"))
        if (padLen >= 0) append(", pad_len: ").append(padLen)
        append(" }") // `data` purposefully excluded, as in the reference
    }

    companion object {
        private const val ALL = END_STREAM or PADDED

        /**
         * Builds a DATA frame from its header and payload (`Data::load`), stripping the padding.
         * @throws FrameException [FrameError.InvalidStreamId] on stream 0, [FrameError.TooMuchPadding] when the padding
         * does not fit.
         */
        fun load(head: Head, payload: Bytes): Data {
            val flags = head.flag and ALL
            if (head.streamId.isZero) throw FrameException(FrameError.InvalidStreamId)
            if (flags and PADDED == 0) return Data(head.streamId, payload, flags, -1)
            // `util::strip_padding`: the first byte is the padding length, which must be less than the payload.
            val len = payload.size
            if (len == 0) throw FrameException(FrameError.TooMuchPadding)
            val pad = payload[0].toInt() and 0xff
            if (pad >= len) throw FrameException(FrameError.TooMuchPadding)
            return Data(head.streamId, payload.slice(1, len - pad), flags, pad)
        }

        private fun requireNonZero(id: StreamId): StreamId {
            require(!id.isZero) { "DATA on stream 0" }
            return id
        }
    }
}

/** `util::debug_flags`: `(0x<bits>: NAME | NAME)`. */
internal fun debugFlags(bits: Int, vararg names: Pair<Int, String>): String {
    val sb = StringBuilder("(0x").append(bits.toString(16))
    var started = false
    for ((bit, name) in names) {
        if (bits and bit == bit) {
            sb.append(if (started) " | " else ": ").append(name)
            started = true
        }
    }
    return sb.append(')').toString()
}
