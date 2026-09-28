package neton.http.h2.frame

import neton.io.bytes.Buffer

/** The default SETTINGS_HEADER_TABLE_SIZE (`DEFAULT_SETTINGS_HEADER_TABLE_SIZE`). */
const val DEFAULT_SETTINGS_HEADER_TABLE_SIZE: Int = 4_096

/** The default SETTINGS_INITIAL_WINDOW_SIZE (`DEFAULT_INITIAL_WINDOW_SIZE`). */
const val DEFAULT_INITIAL_WINDOW_SIZE: Int = 65_535

/** The default SETTINGS_MAX_FRAME_SIZE (`DEFAULT_MAX_FRAME_SIZE`), also its lower bound. */
const val DEFAULT_MAX_FRAME_SIZE: Int = 16_384

/** Upper bound of SETTINGS_INITIAL_WINDOW_SIZE (`MAX_INITIAL_WINDOW_SIZE`), 2^31 - 1. */
const val MAX_INITIAL_WINDOW_SIZE: Int = Int.MAX_VALUE

/** Upper bound of SETTINGS_MAX_FRAME_SIZE (`MAX_MAX_FRAME_SIZE`), 2^24 - 1. */
const val MAX_MAX_FRAME_SIZE: Int = (1 shl 24) - 1

/**
 * A SETTINGS frame (`h2::frame::Settings`, `src/frame/settings.rs`): either an acknowledgement ([isAck], no
 * settings) or a set of optional settings. Settings with unknown identifiers are ignored when parsed.
 *
 * ⚖️ Values are the unsigned 32-bit setting values held as Long (null when absent) instead of `Option<u32>`, so
 * values of 2^31 and above stay plain numbers.
 */
class Settings private constructor(private val flags: Int) : Frame() {
    /** An empty (non-ACK) SETTINGS frame. */
    constructor() : this(0)

    /** SETTINGS_HEADER_TABLE_SIZE (0x1). */
    var headerTableSize: Long? = null

    /** SETTINGS_ENABLE_PUSH (0x2), 0 or 1 when received; see [isPushEnabled]. */
    var enablePush: Long? = null
        private set

    /** SETTINGS_MAX_CONCURRENT_STREAMS (0x3). */
    var maxConcurrentStreams: Long? = null

    /** SETTINGS_INITIAL_WINDOW_SIZE (0x4). */
    var initialWindowSize: Long? = null

    /** SETTINGS_MAX_FRAME_SIZE (0x5); setting it checks the allowed range, as `set_max_frame_size` does. */
    var maxFrameSize: Long? = null
        set(value) {
            if (value != null) require(value in DEFAULT_MAX_FRAME_SIZE..MAX_MAX_FRAME_SIZE) { "max frame size out of range: $value" }
            field = value
        }

    /** SETTINGS_MAX_HEADER_LIST_SIZE (0x6). */
    var maxHeaderListSize: Long? = null

    /** SETTINGS_ENABLE_CONNECT_PROTOCOL (0x8, RFC 8441). */
    var enableConnectProtocol: Long? = null

    /** Whether this is a SETTINGS acknowledgement. */
    val isAck: Boolean get() = flags and ACK != 0

    /** Whether push is enabled, when the setting is present (`is_push_enabled`). */
    val isPushEnabled: Boolean? get() = enablePush?.let { it != 0L }

    /** Sets SETTINGS_ENABLE_PUSH (`set_enable_push`). */
    fun setEnablePush(enable: Boolean) {
        enablePush = if (enable) 1 else 0
    }

    /** Whether extended CONNECT is enabled, when the setting is present (`is_extended_connect_protocol_enabled`). */
    val isExtendedConnectProtocolEnabled: Boolean? get() = enableConnectProtocol?.let { it != 0L }

    /** Visits the present settings as (identifier, value), in the reference's order (`for_each`). */
    inline fun forEach(action: (id: Int, value: Long) -> Unit) {
        headerTableSize?.let { action(HEADER_TABLE_SIZE, it) }
        enablePush?.let { action(ENABLE_PUSH, it) }
        maxConcurrentStreams?.let { action(MAX_CONCURRENT_STREAMS, it) }
        initialWindowSize?.let { action(INITIAL_WINDOW_SIZE, it) }
        maxFrameSize?.let { action(MAX_FRAME_SIZE, it) }
        maxHeaderListSize?.let { action(MAX_HEADER_LIST_SIZE, it) }
        enableConnectProtocol?.let { action(ENABLE_CONNECT_PROTOCOL, it) }
    }

    private fun payloadLen(): Int {
        var len = 0
        forEach { _, _ -> len += 6 }
        return len
    }

    /** Appends the frame (`Settings::encode`). */
    fun encode(dst: Buffer) {
        Head(Kind.Settings, flags, StreamId.ZERO).encode(payloadLen(), dst)
        forEach { id, value ->
            dst.writeShort(id)
            dst.writeInt(value.toInt())
        }
    }

    override fun equals(other: Any?): Boolean =
        other is Settings && other.flags == flags && other.headerTableSize == headerTableSize &&
            other.enablePush == enablePush && other.maxConcurrentStreams == maxConcurrentStreams &&
            other.initialWindowSize == initialWindowSize && other.maxFrameSize == maxFrameSize &&
            other.maxHeaderListSize == maxHeaderListSize && other.enableConnectProtocol == enableConnectProtocol

    override fun hashCode(): Int {
        var h = flags
        forEach { id, value -> h = h * 31 + id * 17 + value.hashCode() }
        return h
    }

    override fun toString(): String = buildString {
        append("Settings { flags: ").append(debugFlags(flags, ACK to "ACK"))
        forEach { id, value -> append(", ").append(NAMES[id]).append(": ").append(value) }
        append(" }")
    }

    companion object {
        private const val ACK = 0x1

        const val HEADER_TABLE_SIZE: Int = 1
        const val ENABLE_PUSH: Int = 2
        const val MAX_CONCURRENT_STREAMS: Int = 3
        const val INITIAL_WINDOW_SIZE: Int = 4
        const val MAX_FRAME_SIZE: Int = 5
        const val MAX_HEADER_LIST_SIZE: Int = 6
        const val ENABLE_CONNECT_PROTOCOL: Int = 8

        private val NAMES = arrayOf(
            "", "header_table_size", "enable_push", "max_concurrent_streams", "initial_window_size",
            "max_frame_size", "max_header_list_size", "", "enable_connect_protocol",
        )

        /** A SETTINGS acknowledgement (`Settings::ack`). */
        fun ack(): Settings = Settings(ACK)

        /**
         * Parses a SETTINGS frame (`Settings::load`).
         * @throws FrameException [FrameError.InvalidStreamId] when not on stream 0, [FrameError.InvalidPayloadLength]
         * for an ACK with a payload, [FrameError.InvalidPayloadAckSettings] when the payload is not a multiple of 6
         * bytes, [FrameError.InvalidSettingValue] for ENABLE_PUSH or ENABLE_CONNECT_PROTOCOL other than 0 / 1, an
         * INITIAL_WINDOW_SIZE above 2^31 - 1 or a MAX_FRAME_SIZE outside 16,384..16,777,215.
         */
        fun load(head: Head, payload: ByteArray, offset: Int, length: Int): Settings {
            if (!head.streamId.isZero) throw FrameException(FrameError.InvalidStreamId)
            if (head.flag and ACK != 0) {
                if (length != 0) throw FrameException(FrameError.InvalidPayloadLength)
                return ack()
            }
            if (length % 6 != 0) throw FrameException(FrameError.InvalidPayloadAckSettings)

            val settings = Settings()
            var i = offset
            val end = offset + length
            while (i < end) {
                val id = ((payload[i].toInt() and 0xff) shl 8) or (payload[i + 1].toInt() and 0xff)
                val value = unpackOctets4(payload, i + 2).toLong() and 0xffffffffL
                i += 6
                when (id) {
                    HEADER_TABLE_SIZE -> settings.headerTableSize = value
                    ENABLE_PUSH -> {
                        if (value != 0L && value != 1L) throw FrameException(FrameError.InvalidSettingValue)
                        settings.enablePush = value
                    }
                    MAX_CONCURRENT_STREAMS -> settings.maxConcurrentStreams = value
                    INITIAL_WINDOW_SIZE -> {
                        if (value > MAX_INITIAL_WINDOW_SIZE) throw FrameException(FrameError.InvalidSettingValue)
                        settings.initialWindowSize = value
                    }
                    MAX_FRAME_SIZE -> {
                        if (value !in DEFAULT_MAX_FRAME_SIZE..MAX_MAX_FRAME_SIZE) {
                            throw FrameException(FrameError.InvalidSettingValue)
                        }
                        settings.maxFrameSize = value
                    }
                    MAX_HEADER_LIST_SIZE -> settings.maxHeaderListSize = value
                    ENABLE_CONNECT_PROTOCOL -> {
                        if (value != 0L && value != 1L) throw FrameException(FrameError.InvalidSettingValue)
                        settings.enableConnectProtocol = value
                    }
                    else -> {} // Unknown settings are ignored.
                }
            }
            return settings
        }
    }
}
