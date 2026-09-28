package neton.http.h2.frame

import neton.http.h2.hpack.DecoderError

/** Size of a frame header (`HEADER_LEN`). */
const val HEADER_LEN: Int = 9

/**
 * An HTTP/2 frame (`h2::frame::Frame`, `src/frame/mod.rs`). CONTINUATION frames never appear here: on read they are
 * merged into the [Headers] or [PushPromise] they continue, on write they are produced from it.
 *
 * ⚖️ The reference is generic over the DATA payload type (`Frame<T = Bytes>`, the send side uses its own buffer
 * type); here a DATA payload is always a [neton.io.bytes.Bytes] slice.
 */
sealed class Frame

/** Why a frame could not be parsed (`h2::frame::Error`). */
enum class FrameError {
    /** A length other than 8 on PING, or a GOAWAY shorter than 8 bytes. */
    BadFrameSize,

    /** The padding length was larger than the payload allows. */
    TooMuchPadding,

    /** An invalid setting value was provided. */
    InvalidSettingValue,

    /** An invalid window update value (0). */
    InvalidWindowUpdateValue,

    /** The payload length was not the one required for the frame type. */
    InvalidPayloadLength,

    /** A SETTINGS payload that is not a multiple of 6 bytes (the reference's name). */
    InvalidPayloadAckSettings,

    /** A stream identifier that is invalid for the frame type (e.g. SETTINGS or PING not on stream 0). */
    InvalidStreamId,

    /** A request or response is malformed. */
    MalformedMessage,

    /** The decoded header list was too large to continue processing. */
    HeaderListWayTooLarge,

    /** A stream that depends on itself (HEADERS or PRIORITY). */
    InvalidDependencyId,

    /** HPACK decoding failed; the cause is [FrameException.hpack]. */
    Hpack,
}

/**
 * A frame could not be parsed. [hpack] is the HPACK decoder error when [error] is [FrameError.Hpack] (the
 * reference's `Error::Hpack(DecoderError)`).
 *
 * ⚖️ The frame `load` functions throw this where the reference returns `Err(frame::Error)`; parse errors are rare
 * and end the stream or the connection, so the exception stays off the normal path.
 */
class FrameException(val error: FrameError, val hpack: DecoderError? = null) :
    Exception(if (hpack == null) error.name else "Hpack($hpack)")

/** The big-endian 32-bit value at [offset] (the reference's `unpack_octets_4!` macro). */
internal fun unpackOctets4(buf: ByteArray, offset: Int): Int =
    ((buf[offset].toInt() and 0xff) shl 24) or ((buf[offset + 1].toInt() and 0xff) shl 16) or
        ((buf[offset + 2].toInt() and 0xff) shl 8) or (buf[offset + 3].toInt() and 0xff)

/** Flag bits of HEADERS, PUSH_PROMISE, CONTINUATION and DATA. */
internal const val END_STREAM: Int = 0x1
internal const val END_HEADERS: Int = 0x4
internal const val PADDED: Int = 0x8
internal const val PRIORITY: Int = 0x20
