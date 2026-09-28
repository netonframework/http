package neton.http.h3

/**
 * An HTTP/3 application error code (`h3::error::Code`, `src/error/codes.rs`): RFC 9114 §8.1, RFC 9204 §6 and
 * RFC 9297 §5.2. Any 62-bit value can be received; the named ones are constants of the companion.
 */
class Code(val value: Long) {
    override fun equals(other: Any?): Boolean = other is Code && other.value == value

    override fun hashCode(): Int = (value xor (value ushr 32)).toInt()

    /** The constant's name, or the value in hexadecimal (the reference's `Debug` and `Display`). */
    override fun toString(): String = NAMES[value] ?: "0x" + value.toString(16)

    companion object {
        /** Datagram or capsule parse error (RFC 9297 §5.2). */
        val H3_DATAGRAM_ERROR = Code(0x33)

        /** No error: the connection or stream needs to be closed, but there is no error to signal. */
        val H3_NO_ERROR = Code(0x100)

        /** A protocol violation that matches no more specific code, or the endpoint declines to use it. */
        val H3_GENERAL_PROTOCOL_ERROR = Code(0x101)

        /** An internal error in the HTTP stack. */
        val H3_INTERNAL_ERROR = Code(0x102)

        /** The peer created a stream that will not be accepted. */
        val H3_STREAM_CREATION_ERROR = Code(0x103)

        /** A stream required by the connection was closed or reset. */
        val H3_CLOSED_CRITICAL_STREAM = Code(0x104)

        /** A frame not permitted in the current state or on the current stream. */
        val H3_FRAME_UNEXPECTED = Code(0x105)

        /** A frame that fails to satisfy layout requirements or has an invalid size. */
        val H3_FRAME_ERROR = Code(0x106)

        /** The peer exhibits a behavior that might be generating excessive load. */
        val H3_EXCESSIVE_LOAD = Code(0x107)

        /** A stream ID or push ID was used incorrectly (exceeding or reducing a limit, reused). */
        val H3_ID_ERROR = Code(0x108)

        /** An error in the payload of a SETTINGS frame. */
        val H3_SETTINGS_ERROR = Code(0x109)

        /** No SETTINGS frame at the beginning of the control stream. */
        val H3_MISSING_SETTINGS = Code(0x10a)

        /** The server rejected a request without any application processing. */
        val H3_REQUEST_REJECTED = Code(0x10b)

        /** The request or its response is cancelled. */
        val H3_REQUEST_CANCELLED = Code(0x10c)

        /** The client's stream terminated without a fully formed request. */
        val H3_REQUEST_INCOMPLETE = Code(0x10d)

        /** A malformed HTTP message. */
        val H3_MESSAGE_ERROR = Code(0x10e)

        /** The TCP connection of a CONNECT request was reset or abnormally closed. */
        val H3_CONNECT_ERROR = Code(0x10f)

        /** The operation cannot be served over HTTP/3; retry over HTTP/1.1. */
        val H3_VERSION_FALLBACK = Code(0x110)

        /** The decoder failed to interpret an encoded field section. */
        val QPACK_DECOMPRESSION_FAILED = Code(0x200)

        /** The decoder failed to interpret an instruction received on the encoder stream. */
        val QPACK_ENCODER_STREAM_ERROR = Code(0x201)

        /** The encoder failed to interpret an instruction received on the decoder stream. */
        val QPACK_DECODER_STREAM_ERROR = Code(0x202)

        private val NAMES: Map<Long, String> = mapOf(
            0x33L to "H3_DATAGRAM_ERROR",
            0x100L to "H3_NO_ERROR",
            0x101L to "H3_GENERAL_PROTOCOL_ERROR",
            0x102L to "H3_INTERNAL_ERROR",
            0x103L to "H3_STREAM_CREATION_ERROR",
            0x104L to "H3_CLOSED_CRITICAL_STREAM",
            0x105L to "H3_FRAME_UNEXPECTED",
            0x106L to "H3_FRAME_ERROR",
            0x107L to "H3_EXCESSIVE_LOAD",
            0x108L to "H3_ID_ERROR",
            0x109L to "H3_SETTINGS_ERROR",
            0x10aL to "H3_MISSING_SETTINGS",
            0x10bL to "H3_REQUEST_REJECTED",
            0x10cL to "H3_REQUEST_CANCELLED",
            0x10dL to "H3_REQUEST_INCOMPLETE",
            0x10eL to "H3_MESSAGE_ERROR",
            0x10fL to "H3_CONNECT_ERROR",
            0x110L to "H3_VERSION_FALLBACK",
            0x200L to "QPACK_DECOMPRESSION_FAILED",
            0x201L to "QPACK_ENCODER_STREAM_ERROR",
            0x202L to "QPACK_DECODER_STREAM_ERROR",
        )
    }
}

/**
 * An error of the HTTP/3 protocol core, with the [code] the connection layer uses to close the connection or reset the
 * stream (the reference returns `Result`s; the errors here are thrown only on error paths, never for incomplete input).
 */
open class H3Exception(val code: Code, message: String) : Exception(message)
