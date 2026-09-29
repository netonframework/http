package neton.http.h3.proto

import neton.http.h3.Code
import neton.http.h3.H3Exception
import neton.io.bytes.Buffer
import neton.io.bytes.Bytes
import kotlin.random.Random

// HTTP/3 frames (RFC 9114 §7), `h3::proto::frame` (`src/proto/frame.rs`).

/** Frame type identifiers (`FrameType`). */
object FrameType {
    const val DATA: Long = 0x0
    const val HEADERS: Long = 0x1
    const val H2_PRIORITY: Long = 0x2
    const val CANCEL_PUSH: Long = 0x3
    const val SETTINGS: Long = 0x4
    const val PUSH_PROMISE: Long = 0x5
    const val H2_PING: Long = 0x6
    const val GOAWAY: Long = 0x7
    const val H2_WINDOW_UPDATE: Long = 0x8
    const val H2_CONTINUATION: Long = 0x9
    const val MAX_PUSH_ID: Long = 0xD

    /**
     * `WEBTRANSPORT_BI_STREAM` (draft-ietf-webtrans-http3). ⛔ WebTransport is not in the first version (SPEC §5):
     * without it this type has no meaning and is skipped like any unknown frame, by its length (RFC 9114 §9).
     */
    const val WEBTRANSPORT_BI_STREAM: Long = 0x41

    /** A reserved type of the form `0x1f * N + 0x21` used in the reference's tests (`FrameType::RESERVED`). */
    const val RESERVED: Long = 0x1fL * 1337 + 0x21

    /** A random reserved ("GREASE") frame type `0x1f * N + 0x21` within the varint range (`FrameType::grease`). */
    fun grease(random: Random = Random.Default): Long = greaseValue(random)

    /** Whether [type] is of the reserved form `0x1f * N + 0x21` (RFC 9114 §7.2.8). */
    fun isGrease(type: Long): Boolean = type >= 0x21 && (type - 0x21) % 0x1f == 0L
}

/** `0x1f * N + 0x21` for a random N in `0 until 0x210842108421083` (the reference's `grease()` range). */
internal fun greaseValue(random: Random): Long = random.nextLong(0, 0x210842108421083L) * 0x1f + 0x21

/**
 * An HTTP/3 frame (`Frame`). Decoding yields every kind but [Grease]; [Data] then carries only the payload length (the
 * payload is streamed separately, [FrameStream.nextData]).
 */
sealed class Frame {
    /**
     * DATA: [length] payload bytes. [payload] is set for a frame built to be sent. Two DATA frames are equal whatever
     * their payload, as in the reference's test comparison.
     */
    class Data(val length: Long, val payload: Bytes? = null) : Frame() {
        constructor(payload: Bytes) : this(payload.size.toLong(), payload)

        override fun equals(other: Any?): Boolean = other is Data
        override fun hashCode(): Int = 0
        override fun toString(): String = "Data: $length bytes"
    }

    /** HEADERS: an encoded field section ([block]). */
    data class Headers(val block: Bytes) : Frame() {
        override fun toString(): String = "Headers(${block.size} bytes)"
    }

    /** CANCEL_PUSH. */
    data class CancelPush(val pushId: Long) : Frame()

    /** GOAWAY: a stream ID (from the server) or a push ID (from the client). */
    data class Goaway(val id: Long) : Frame()

    /** MAX_PUSH_ID. */
    data class MaxPushId(val pushId: Long) : Frame()

    /** A reserved frame type with the payload `grease` (sending only). */
    object Grease : Frame() {
        override fun toString(): String = "Grease()"
    }

    /**
     * Encodes the frame header (`Encode for Frame`): type and length for DATA and HEADERS (the payload is written
     * separately), the whole frame for the others; PUSH_PROMISE up to its push ID.
     */
    fun encode(buf: Buffer) {
        when (this) {
            is Data -> { VarInt.encode(FrameType.DATA, buf); VarInt.encode(length, buf) }
            is Headers -> { VarInt.encode(FrameType.HEADERS, buf); VarInt.encode(block.size.toLong(), buf) }
            is Settings -> encodeSettings(buf)
            is PushPromise -> encodePushPromiseHeader(buf)
            is CancelPush -> simpleFrameEncode(FrameType.CANCEL_PUSH, pushId, buf)
            is Goaway -> simpleFrameEncode(FrameType.GOAWAY, id, buf)
            is MaxPushId -> simpleFrameEncode(FrameType.MAX_PUSH_ID, pushId, buf)
            Grease -> {
                VarInt.encode(FrameType.grease(), buf)
                VarInt.encode(GREASE_PAYLOAD.size.toLong(), buf)
                buf.writeBytes(GREASE_PAYLOAD)
            }
        }
    }

    /** [encode] followed by the payload (`encode_with_payload`, test-only in the reference). */
    fun encodeWithPayload(buf: Buffer) {
        encode(buf)
        when (this) {
            is Data -> payload?.let { buf.writeBytes(it) }
            is Headers -> buf.writeBytes(block)
            is PushPromise -> buf.writeBytes(encoded)
            else -> Unit
        }
    }

    companion object {
        /** The largest encoded frame header (`Frame::MAX_ENCODED_SIZE`). */
        const val MAX_ENCODED_SIZE: Int = VarInt.MAX_SIZE * 7

        /** A HEADERS frame with [block] (`Frame::headers`, test helper in the reference). */
        fun headers(block: ByteArray): Headers = Headers(Bytes.wrap(block))

        /** A HEADERS frame with the bytes of [block]. */
        fun headers(block: String): Headers = headers(block.encodeToByteArray())

        internal val GREASE_PAYLOAD = "grease".encodeToByteArray()
    }
}

private fun simpleFrameEncode(type: Long, id: Long, buf: Buffer) {
    VarInt.encode(type, buf)
    VarInt.encode(VarInt.size(id).toLong(), buf)
    VarInt.encode(id, buf)
}

/**
 * PUSH_PROMISE (`PushPromise`): the push ID and the encoded field section. ⛔ Server push is not in the first version
 * (SPEC §5): the frame is parsed only.
 */
class PushPromise(val id: Long, val encoded: Bytes) : Frame() {
    internal fun encodePushPromiseHeader(buf: Buffer) {
        VarInt.encode(FrameType.PUSH_PROMISE, buf)
        VarInt.encode(VarInt.size(id).toLong() + encoded.size, buf)
        VarInt.encode(id, buf)
    }

    override fun equals(other: Any?): Boolean = other is PushPromise && other.id == id && other.encoded == encoded
    override fun hashCode(): Int = id.hashCode() * 31 + encoded.hashCode()
    override fun toString(): String = "PushPromise($id)"
}

/** Setting identifiers (`SettingId`, RFC 9114 §7.2.4.1, RFC 9204 §5, RFC 9220, RFC 9297). */
object SettingId {
    const val QPACK_MAX_TABLE_CAPACITY: Long = 0x1
    const val MAX_HEADER_LIST_SIZE: Long = 0x6
    const val QPACK_MAX_BLOCKED_STREAMS: Long = 0x7
    const val ENABLE_CONNECT_PROTOCOL: Long = 0x8
    const val H3_DATAGRAM: Long = 0x33
    const val ENABLE_WEBTRANSPORT: Long = 0x2B603742
    const val H3_SETTING_ENABLE_DATAGRAM_CHROME_SPECIFIC: Long = 0xFFD277
    const val WEBTRANSPORT_MAX_SESSIONS: Long = 0x2b603743

    /** A random reserved setting identifier `0x1f * N + 0x21` (`SettingId::grease`). */
    fun grease(random: Random = Random.Default): Long = greaseValue(random)

    /** The settings kept when received (`is_supported`); others are ignored. */
    fun isSupported(id: Long): Boolean = id == MAX_HEADER_LIST_SIZE || id == QPACK_MAX_TABLE_CAPACITY ||
        id == QPACK_MAX_BLOCKED_STREAMS || id == ENABLE_CONNECT_PROTOCOL || id == ENABLE_WEBTRANSPORT ||
        id == WEBTRANSPORT_MAX_SESSIONS || id == H3_DATAGRAM

    /** HTTP/2 settings with no HTTP/3 counterpart (RFC 9114 §7.2.4.1): receiving one is H3_SETTINGS_ERROR. */
    fun isForbidden(id: Long): Boolean = id == 0x00L || id in 0x02L..0x05L
}

/**
 * A SETTINGS frame (`Settings`): at most [SETTINGS_LEN] entries, each identifier at most once.
 */
class Settings : Frame() {
    private val ids = LongArray(SETTINGS_LEN)
    private val values = LongArray(SETTINGS_LEN)

    /** The number of entries. */
    var len: Int = 0
        private set

    /**
     * Adds an entry (`insert`); returns the error instead of adding it when full ([SettingsError.Exceeded]) or when
     * [id] is already present ([SettingsError.Repeated]), else null.
     */
    fun insert(id: Long, value: Long): SettingsError? {
        if (len >= SETTINGS_LEN) return SettingsError.Exceeded
        for (i in 0 until len) if (ids[i] == id) return SettingsError.Repeated(id)
        ids[len] = id
        values[len] = value
        len++
        return null
    }

    /**
     * The value of [id], or null (`get`). ⚖️ Looks at the entries present only: the reference scans its whole fixed
     * array, so `get(0)` found an unused slot's zero.
     */
    fun get(id: Long): Long? {
        for (i in 0 until len) if (ids[i] == id) return values[i]
        return null
    }

    /** The identifier of entry [i]. */
    fun idAt(i: Int): Long = ids[i]

    /** The value of entry [i]. */
    fun valueAt(i: Int): Long = values[i]

    /** The payload length (`FrameHeader::len`). */
    fun payloadLength(): Int {
        var n = 0
        for (i in 0 until len) n += VarInt.size(ids[i]) + VarInt.size(values[i])
        return n
    }

    internal fun encodeSettings(buf: Buffer) {
        VarInt.encode(FrameType.SETTINGS, buf)
        VarInt.encode(payloadLength().toLong(), buf)
        for (i in 0 until len) {
            VarInt.encode(ids[i], buf)
            VarInt.encode(values[i], buf)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Settings || other.len != len) return false
        for (i in 0 until len) if (other.ids[i] != ids[i] || other.values[i] != values[i]) return false
        return true
    }

    override fun hashCode(): Int {
        var h = len
        for (i in 0 until len) h = (h * 31 + ids[i].hashCode()) * 31 + values[i].hashCode()
        return h
    }

    override fun toString(): String = buildString {
        append("Settings(")
        for (i in 0 until len) {
            if (i > 0) append(", ")
            append("0x").append(ids[i].toString(16)).append('=').append(values[i])
        }
        append(')')
    }

    companion object {
        /** The maximum number of entries (`SETTINGS_LEN`). */
        const val SETTINGS_LEN: Int = 8

        /** The largest encoded payload of the entries (`Settings::MAX_ENCODED_SIZE`). */
        const val MAX_ENCODED_SIZE: Int = SETTINGS_LEN * 2 * VarInt.MAX_SIZE

        /**
         * Decodes a SETTINGS payload `src[off, end)` (`Settings::decode`): HTTP/2-only identifiers are an error,
         * unknown (including GREASE) identifiers are ignored, supported ones are inserted (a repeated one is an
         * error).
         * @throws FrameException [FrameError.Settings].
         */
        fun decode(src: ByteArray, off: Int, end: Int): Settings {
            val settings = Settings()
            var p = off
            while (p < end) {
                // Less than twice the minimum varint size.
                if (end - p < 2) throw FrameException(FrameError.Settings(SettingsError.Malformed))
                val id = VarInt.decode(src, p, end)
                if (id < 0) throw FrameException(FrameError.Settings(SettingsError.Malformed))
                p += VarInt.encodedSize(src[p])
                val value = VarInt.decode(src, p, end)
                if (value < 0) throw FrameException(FrameError.Settings(SettingsError.Malformed))
                p += VarInt.encodedSize(src[p])
                if (SettingId.isForbidden(id)) throw FrameException(FrameError.Settings(SettingsError.InvalidSettingId(id)))
                if (SettingId.isSupported(id)) {
                    val err = settings.insert(id, value)
                    if (err != null) throw FrameException(FrameError.Settings(err))
                }
            }
            return settings
        }
    }
}

/** Errors in a SETTINGS payload (`SettingsError`); all are H3_SETTINGS_ERROR. */
sealed class SettingsError {
    object Exceeded : SettingsError() {
        override fun toString() = "max settings number exceeded, check for duplicate entries"
    }

    object Malformed : SettingsError() {
        override fun toString() = "malformed settings frame"
    }

    data class Repeated(val id: Long) : SettingsError() {
        override fun toString() = "got setting 0x${id.toString(16)} twice"
    }

    data class InvalidSettingId(val id: Long) : SettingsError() {
        override fun toString() = "setting id 0x${id.toString(16)} is invalid"
    }

    data class InvalidSettingValue(val id: Long, val value: Long) : SettingsError() {
        override fun toString() = "setting 0x${id.toString(16)} has invalid value $value"
    }
}

/**
 * Frame decoding errors (`FrameError` / `FrameProtocolError`, `src/proto/frame.rs`, `src/frame.rs`), each with the
 * connection error [code] RFC 9114 gives it. The reference's `Incomplete` and `UnknownFrame` are not errors here (see
 * [FrameDecoder]).
 */
sealed class FrameError(val code: Code) {
    /** A frame whose payload does not match its layout (RFC 9114 §7.1). */
    data class Malformed(val type: Long) : FrameError(Code.H3_FRAME_ERROR) {
        override fun toString() = "frame 0x${type.toString(16)} is malformed"
    }

    /** An HTTP/2-only frame type (RFC 9114 §7.2.8), `UnsupportedFrame` / `ForbiddenFrame`. */
    data class UnsupportedFrame(val type: Long) : FrameError(Code.H3_FRAME_UNEXPECTED) {
        override fun toString() = "frame 0x${type.toString(16)} is not allowed h3"
    }

    /**
     * ⚖️ A control-stream frame type (SETTINGS, CANCEL_PUSH, GOAWAY, MAX_PUSH_ID) on a request stream (RFC 9114 §7.2.3,
     * §7.2.4, §7.2.6, §7.2.7), rejected from the frame header whatever its payload (see [neton.http.h3.FrameDecoder]).
     */
    data class Unexpected(val type: Long) : FrameError(Code.H3_FRAME_UNEXPECTED) {
        override fun toString() = "frame 0x${type.toString(16)} is not allowed on a request stream"
    }

    /** An invalid SETTINGS payload. */
    data class Settings(val error: SettingsError) : FrameError(Code.H3_SETTINGS_ERROR) {
        override fun toString() = "invalid settings: $error"
    }

    /**
     * ⚖️ A HEADERS or PUSH_PROMISE frame whose declared length exceeds `maxHeadersFrameSize` (SPEC §5, "the three
     * header limits"): raised from the frame header, before any of the payload is buffered. The connection layer
     * answers 431 (server) or fails the request (client); the code is for when it closes the stream instead.
     */
    data class HeadersTooLarge(val type: Long, val length: Long, val max: Int) : FrameError(Code.H3_EXCESSIVE_LOAD) {
        override fun toString() = "frame 0x${type.toString(16)} of $length bytes exceeds the limit of $max"
    }

    /** ⚖️ A frame other than DATA, HEADERS or PUSH_PROMISE longer than it may be (see [FrameDecoder]). */
    data class TooLarge(val type: Long, val length: Long) : FrameError(Code.H3_EXCESSIVE_LOAD) {
        override fun toString() = "frame 0x${type.toString(16)} of $length bytes is too large"
    }

    /** The stream ended inside a frame (`FrameStreamError::UnexpectedEnd`). */
    object UnexpectedEnd : FrameError(Code.H3_FRAME_ERROR) {
        override fun toString() = "stream ended inside a frame"
    }
}

/** A [FrameError] thrown. */
class FrameException(val error: FrameError) : H3Exception(error.code, error.toString())
