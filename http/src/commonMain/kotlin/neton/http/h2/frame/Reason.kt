package neton.http.h2.frame

/**
 * An HTTP/2 error code (`h2::Reason`, `src/frame/reason.rs`; RFC 9113 §7), as carried by RST_STREAM and GOAWAY.
 * Codes other than the 14 defined ones (0x0–0xd) are kept as received.
 *
 * [code] holds the unsigned 32-bit code; values of 2^31 and above are negative as an Int.
 *
 * `toString` is the reference's `Debug` form (the constant's name, or `Reason(<hex>)` for an unknown code);
 * [description] is its `Display` form.
 */
value class Reason(val code: Int) {
    /** A description of the error code (`Reason::description`). */
    fun description(): String = when (code) {
        0 -> "not a result of an error"
        1 -> "unspecific protocol error detected"
        2 -> "unexpected internal error encountered"
        3 -> "flow-control protocol violated"
        4 -> "settings ACK not received in timely manner"
        5 -> "received frame when stream half-closed"
        6 -> "frame with invalid size"
        7 -> "refused stream before processing any application logic"
        8 -> "stream no longer needed"
        9 -> "unable to maintain the header compression context"
        10 -> "connection established in response to a CONNECT request was reset or abnormally closed"
        11 -> "detected excessive load generating behavior"
        12 -> "security properties do not meet minimum requirements"
        13 -> "endpoint requires HTTP/1.1"
        else -> "unknown reason"
    }

    override fun toString(): String = when (code) {
        0 -> "NO_ERROR"
        1 -> "PROTOCOL_ERROR"
        2 -> "INTERNAL_ERROR"
        3 -> "FLOW_CONTROL_ERROR"
        4 -> "SETTINGS_TIMEOUT"
        5 -> "STREAM_CLOSED"
        6 -> "FRAME_SIZE_ERROR"
        7 -> "REFUSED_STREAM"
        8 -> "CANCEL"
        9 -> "COMPRESSION_ERROR"
        10 -> "CONNECT_ERROR"
        11 -> "ENHANCE_YOUR_CALM"
        12 -> "INADEQUATE_SECURITY"
        13 -> "HTTP_1_1_REQUIRED"
        else -> "Reason(${code.toUInt().toString(16)})"
    }

    companion object {
        /** The condition is not a result of an error (e.g. a graceful GOAWAY). */
        val NO_ERROR: Reason = Reason(0)

        /** An unspecific protocol error. */
        val PROTOCOL_ERROR: Reason = Reason(1)

        /** An unexpected internal error. */
        val INTERNAL_ERROR: Reason = Reason(2)

        /** The peer violated the flow-control protocol. */
        val FLOW_CONTROL_ERROR: Reason = Reason(3)

        /** A SETTINGS frame was not acknowledged in time. */
        val SETTINGS_TIMEOUT: Reason = Reason(4)

        /** A frame was received after the stream was half-closed. */
        val STREAM_CLOSED: Reason = Reason(5)

        /** A frame had an invalid size. */
        val FRAME_SIZE_ERROR: Reason = Reason(6)

        /** The stream was refused before any application processing. */
        val REFUSED_STREAM: Reason = Reason(7)

        /** The stream is no longer needed. */
        val CANCEL: Reason = Reason(8)

        /** The header compression context cannot be maintained. */
        val COMPRESSION_ERROR: Reason = Reason(9)

        /** The connection of a CONNECT request was reset or abnormally closed. */
        val CONNECT_ERROR: Reason = Reason(10)

        /** The peer is generating excessive load. */
        val ENHANCE_YOUR_CALM: Reason = Reason(11)

        /** The transport does not meet minimum security requirements. */
        val INADEQUATE_SECURITY: Reason = Reason(12)

        /** HTTP/1.1 is required instead of HTTP/2. */
        val HTTP_1_1_REQUIRED: Reason = Reason(13)
    }
}
