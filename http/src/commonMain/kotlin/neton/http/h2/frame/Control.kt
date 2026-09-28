package neton.http.h2.frame

import neton.io.bytes.Buffer
import neton.io.bytes.Bytes

// The small control frames: GOAWAY, PING, PRIORITY, RST_STREAM and WINDOW_UPDATE (`src/frame/go_away.rs`, `ping.rs`,
// `priority.rs`, `reset.rs`, `window_update.rs`).

/** A GOAWAY frame (`h2::frame::GoAway`). */
class GoAway(
    val lastStreamId: StreamId,
    val reason: Reason,
    val debugData: Bytes = Bytes.EMPTY,
) : Frame() {
    /** Appends the frame (`GoAway::encode`). */
    fun encode(dst: Buffer) {
        Head(Kind.GoAway, 0, StreamId.ZERO).encode(8 + debugData.size, dst)
        dst.writeInt(lastStreamId.value)
        dst.writeInt(reason.code)
        dst.writeBytes(debugData)
    }

    override fun equals(other: Any?): Boolean =
        other is GoAway && other.lastStreamId == lastStreamId && other.reason == reason && other.debugData == debugData

    override fun hashCode(): Int = lastStreamId.value * 31 + reason.code

    override fun toString(): String =
        "GoAway { error_code: $reason, last_stream_id: ${lastStreamId.value}" +
            (if (debugData.isEmpty) "" else ", debug_data: ${debugData.decodeToString()}") + " }"

    companion object {
        /**
         * Parses a GOAWAY payload (`GoAway::load`). As in the reference the stream ID is not checked here; the debug
         * data is copied.
         * @throws FrameException [FrameError.BadFrameSize] when shorter than 8 bytes.
         */
        fun load(payload: ByteArray, offset: Int, length: Int): GoAway {
            if (length < 8) throw FrameException(FrameError.BadFrameSize)
            return GoAway(
                StreamId.parse(payload, offset),
                Reason(unpackOctets4(payload, offset + 4)),
                if (length == 8) Bytes.EMPTY else Bytes.copyOf(payload, offset + 8, offset + length),
            )
        }
    }
}

/**
 * A PING frame (`h2::frame::Ping`).
 *
 * ⚖️ The 8 opaque payload bytes are held as one big-endian Long instead of a `[u8; 8]`, so a PING allocates nothing
 * beyond the frame; [payloadBytes] gives the byte form.
 */
class Ping(val payload: Long, val isAck: Boolean = false) : Frame() {
    /** The payload as 8 bytes. */
    fun payloadBytes(): ByteArray = ByteArray(8) { (payload ushr (56 - 8 * it)).toByte() }

    /** Appends the frame (`Ping::encode`). */
    fun encode(dst: Buffer) {
        Head(Kind.Ping, if (isAck) ACK_FLAG else 0, StreamId.ZERO).encode(8, dst)
        dst.writeLong(payload)
    }

    override fun equals(other: Any?): Boolean = other is Ping && other.payload == payload && other.isAck == isAck

    override fun hashCode(): Int = payload.hashCode() * 31 + if (isAck) 1 else 0

    override fun toString(): String = "Ping { ack: $isAck, payload: 0x${payload.toULong().toString(16)} }"

    companion object {
        private const val ACK_FLAG = 0x1

        /** The payload of the PING sent on graceful shutdown (`Ping::SHUTDOWN`). */
        const val SHUTDOWN: Long = 0x0b7ba2f08b9bfe54L

        /** The payload of user PINGs (`Ping::USER`). */
        const val USER: Long = 0x3b7cdb7a0b8716b4L

        /** A PING response (`Ping::pong`). */
        fun pong(payload: Long): Ping = Ping(payload, true)

        /** The Long form of 8 payload bytes. */
        fun payloadOf(bytes: ByteArray, offset: Int = 0): Long =
            (unpackOctets4(bytes, offset).toLong() shl 32) or (unpackOctets4(bytes, offset + 4).toLong() and 0xffffffffL)

        /**
         * Parses a PING frame (`Ping::load`).
         * @throws FrameException [FrameError.InvalidStreamId] when not on stream 0, [FrameError.BadFrameSize] when the
         * payload is not 8 bytes.
         */
        fun load(head: Head, payload: ByteArray, offset: Int, length: Int): Ping {
            if (!head.streamId.isZero) throw FrameException(FrameError.InvalidStreamId)
            if (length != 8) throw FrameException(FrameError.BadFrameSize)
            return Ping(payloadOf(payload, offset), head.flag and ACK_FLAG != 0)
        }
    }
}

/** A PRIORITY frame (`h2::frame::Priority`). Parsed and then ignored by the connection; never sent. */
class Priority(val streamId: StreamId, val dependency: StreamDependency) : Frame() {
    override fun equals(other: Any?): Boolean = other is Priority && other.streamId == streamId && other.dependency == dependency

    override fun hashCode(): Int = streamId.value * 31 + dependency.hashCode()

    override fun toString(): String = "Priority { stream_id: ${streamId.value}, dependency: $dependency }"

    companion object {
        /**
         * Parses a PRIORITY frame (`Priority::load`). The stream-0 check is the codec's.
         * @throws FrameException [FrameError.InvalidPayloadLength] when not 5 bytes, [FrameError.InvalidDependencyId]
         * when the stream depends on itself.
         */
        fun load(head: Head, payload: ByteArray, offset: Int, length: Int): Priority {
            val dependency = StreamDependency.load(payload, offset, length)
            if (dependency.dependencyId == head.streamId) throw FrameException(FrameError.InvalidDependencyId)
            return Priority(head.streamId, dependency)
        }
    }
}

/**
 * The stream dependency of a PRIORITY frame or a HEADERS frame with the PRIORITY flag (`StreamDependency`).
 * [weight] is 0..255 (the wire value; the RFC's weight is one more).
 */
data class StreamDependency(val dependencyId: StreamId, val weight: Int, val isExclusive: Boolean) {
    companion object {
        /**
         * Parses the 5-byte dependency (`StreamDependency::load`).
         * @throws FrameException [FrameError.InvalidPayloadLength] when [length] is not 5.
         */
        fun load(src: ByteArray, offset: Int, length: Int): StreamDependency {
            if (length != 5) throw FrameException(FrameError.InvalidPayloadLength)
            return StreamDependency(
                StreamId.parse(src, offset),
                src[offset + 4].toInt() and 0xff,
                StreamId.parseFlag(src, offset),
            )
        }
    }
}

/** An RST_STREAM frame (`h2::frame::Reset`). */
class Reset(val streamId: StreamId, val reason: Reason) : Frame() {
    /** Appends the frame (`Reset::encode`). */
    fun encode(dst: Buffer) {
        Head(Kind.Reset, 0, streamId).encode(4, dst)
        dst.writeInt(reason.code)
    }

    override fun equals(other: Any?): Boolean = other is Reset && other.streamId == streamId && other.reason == reason

    override fun hashCode(): Int = streamId.value * 31 + reason.code

    override fun toString(): String = "Reset { stream_id: ${streamId.value}, error_code: $reason }"

    companion object {
        /**
         * Parses an RST_STREAM frame (`Reset::load`). The stream-0 check is the connection's, as in the reference.
         * @throws FrameException [FrameError.InvalidPayloadLength] when the payload is not 4 bytes.
         */
        fun load(head: Head, payload: ByteArray, offset: Int, length: Int): Reset {
            if (length != 4) throw FrameException(FrameError.InvalidPayloadLength)
            return Reset(head.streamId, Reason(unpackOctets4(payload, offset)))
        }
    }
}

/** A WINDOW_UPDATE frame (`h2::frame::WindowUpdate`); [sizeIncrement] is 1..2^31 - 1. */
class WindowUpdate(val streamId: StreamId, val sizeIncrement: Int) : Frame() {
    /** Appends the frame (`WindowUpdate::encode`). */
    fun encode(dst: Buffer) {
        Head(Kind.WindowUpdate, 0, streamId).encode(4, dst)
        dst.writeInt(sizeIncrement)
    }

    override fun equals(other: Any?): Boolean =
        other is WindowUpdate && other.streamId == streamId && other.sizeIncrement == sizeIncrement

    override fun hashCode(): Int = streamId.value * 31 + sizeIncrement

    override fun toString(): String = "WindowUpdate { stream_id: ${streamId.value}, size_increment: $sizeIncrement }"

    companion object {
        /**
         * Parses a WINDOW_UPDATE frame (`WindowUpdate::load`); the reserved bit is ignored.
         * @throws FrameException [FrameError.BadFrameSize] when the payload is not 4 bytes,
         * [FrameError.InvalidWindowUpdateValue] for an increment of 0.
         */
        fun load(head: Head, payload: ByteArray, offset: Int, length: Int): WindowUpdate {
            if (length != 4) throw FrameException(FrameError.BadFrameSize)
            val increment = unpackOctets4(payload, offset) and StreamId.MASK.inv()
            if (increment == 0) throw FrameException(FrameError.InvalidWindowUpdateValue)
            return WindowUpdate(head.streamId, increment)
        }
    }
}
