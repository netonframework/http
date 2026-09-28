package neton.http.h3

import neton.http.h3.quic.ConnectionErrorIncoming
import neton.http.h3.quic.StreamErrorIncoming

// The public errors of connections and request streams (`h3::error`: `src/error/error.rs`, `internal_error.rs`,
// `connection_error_creators.rs`).
//
// Connection errors vs stream errors (RFC 9114 §8): a connection error closes the whole QUIC connection with a code
// (every stream then fails with [StreamError.Connection]); a stream error only ends one request, by RESET_STREAM and/or
// STOP_SENDING with a code, and the connection goes on. The first connection error wins: it is kept in the connection's
// shared state, and every later operation reports that same error.

/** A connection-level error (`ConnectionError`): the connection is closed. */
sealed class ConnectionError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** This endpoint closed the connection because of [error] (`ConnectionError::Local`). */
    class Local(val error: LocalError) : ConnectionError("Local error: $error")

    /** The QUIC connection was closed by the peer or the transport (`ConnectionError::Remote`). */
    class Remote(val error: ConnectionErrorIncoming) : ConnectionError("Remote error: ${error.message}", error)

    /** The QUIC idle timeout expired (`ConnectionError::Timeout`). */
    class Timeout : ConnectionError("Timeout")

    /** Whether this is a close without an error: H3_NO_ERROR, locally or from the peer (`is_h3_no_error`). */
    fun isH3NoError(): Boolean = when (this) {
        is Local -> error is LocalError.Application && error.code == Code.H3_NO_ERROR
        is Remote -> error is ConnectionErrorIncoming.ApplicationClose && error.errorCode == Code.H3_NO_ERROR.value
        is Timeout -> false
    }

    /** The HTTP/3 code of a local close or of the peer's close, if there is one. */
    val code: Code?
        get() = when (this) {
            is Local -> (error as? LocalError.Application)?.code
            is Remote -> (error as? ConnectionErrorIncoming.ApplicationClose)?.let { Code(it.errorCode) }
            is Timeout -> null
        }
}

/** Why this endpoint closed the connection (`LocalError`). */
sealed class LocalError {
    /** An HTTP/3 error [code] with a [reason] (`LocalError::Application`). */
    class Application(val code: Code, val reason: String) : LocalError() {
        override fun toString(): String = "Application { code: $code, reason: \"$reason\" }"
    }

    /** The connection is closing (`LocalError::Closing`). */
    object Closing : LocalError() {
        override fun toString(): String = "Closing"
    }
}

/** An error of one request stream (`StreamError`). */
sealed class StreamError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** A stream error with [code]: the stream was (or is to be) reset / stopped (`StreamError::StreamError`). */
    class Stream(val code: Code, val reason: String) : StreamError("Stream error: $code - $reason")

    /** The peer reset or stopped the stream with [code] (`StreamError::RemoteTerminate`). */
    class RemoteTerminate(val code: Code) : StreamError("Remote reset: $code")

    /** The connection failed (`StreamError::ConnectionError`). */
    class Connection(val error: ConnectionError) : StreamError("Connection error: ${error.message}", error)

    /**
     * A header section (head or trailers) is larger than allowed: [actualSize] against [maxSize]
     * (`StreamError::HeaderTooBig`). Sending: the peer's SETTINGS_MAX_FIELD_SECTION_SIZE (decoded size). Receiving: one
     * of the three limits of SPEC §5 — the encoded HEADERS frame size (`maxHeadersFrameSize`), the decoded section size
     * (`maxFieldSectionSize`) or the field count (`maxFieldCount`; then the sizes are counts).
     */
    class HeaderTooBig(val actualSize: Long, val maxSize: Long) :
        StreamError("Header too big: actual size: $actualSize, max size: $maxSize")

    /** The peer is closing the connection (sent GOAWAY): no new request (`StreamError::RemoteClosing`). */
    class RemoteClosing : StreamError("Remote is closing the connection")

    /** An error of the QUIC implementation not defined by HTTP/3 (`StreamError::Undefined`). */
    class Undefined(error: Throwable) : StreamError("Undefined error: ${error.message ?: error}", error)

    /** Whether this is H3_NO_ERROR, of the stream or of the connection (`is_h3_no_error`). */
    fun isH3NoError(): Boolean = when (this) {
        is Stream -> code == Code.H3_NO_ERROR
        is Connection -> error.isH3NoError()
        else -> false
    }
}

/** A connection error found by this endpoint, not yet applied to the connection (`InternalConnectionError`). */
internal class InternalConnectionError(val code: Code, val message: String) {
    override fun toString(): String = "$code: $message"

    companion object {
        /**
         * A protocol error of the frame layer (`got_frame_error`): [H3Exception.code] is the code RFC 9114 gives it
         * (phase A's [neton.http.h3.proto.FrameError] codes: H3_FRAME_ERROR for malformed or truncated frames,
         * H3_FRAME_UNEXPECTED for HTTP/2 frame types, H3_SETTINGS_ERROR, H3_EXCESSIVE_LOAD).
         */
        fun of(e: H3Exception) = InternalConnectionError(e.code, e.message ?: e.code.toString())
    }
}

/** Where a connection error came from (`ErrorOrigin`). */
internal sealed class ErrorOrigin {
    class Internal(val error: InternalConnectionError) : ErrorOrigin()
    class Quic(val error: ConnectionErrorIncoming) : ErrorOrigin()

    /** The public error (`convert_to_connection_error`). */
    fun toConnectionError(): ConnectionError = when (this) {
        is Internal -> ConnectionError.Local(LocalError.Application(error.code, error.message))
        is Quic -> if (error is ConnectionErrorIncoming.Timeout) ConnectionError.Timeout() else ConnectionError.Remote(error)
    }

    override fun toString(): String = when (this) {
        is Internal -> "Internal Error: ${error.message}"
        is Quic -> "Quic Error: ${error.message}"
    }
}

/** The stream error for a failed QUIC stream operation (`handle_quic_stream_error`), recording a lost connection. */
internal fun SharedState.streamError(e: StreamErrorIncoming): StreamError = when (e) {
    is StreamErrorIncoming.ConnectionLost -> StreamError.Connection(setConnError(ErrorOrigin.Quic(e.connectionError)).toConnectionError())
    is StreamErrorIncoming.StreamTerminated -> StreamError.RemoteTerminate(Code(e.errorCode))
    is StreamErrorIncoming.Unknown -> StreamError.Undefined(e.error)
}

/** Records a connection error found on a stream and closes the connection (`handle_connection_error_on_stream`). */
internal fun SharedState.connectionErrorOnStream(error: InternalConnectionError): StreamError =
    StreamError.Connection(setConnError(ErrorOrigin.Internal(error)).toConnectionError())
