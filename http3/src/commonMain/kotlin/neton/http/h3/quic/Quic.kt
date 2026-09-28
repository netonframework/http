package neton.http.h3.quic

import neton.http.h3.Code
import neton.http.h3.proto.StreamId
import neton.io.bytes.Bytes

// The thin QUIC interface HTTP/3 runs on (`h3::quic`, `src/quic.rs`; SPEC §5: "本库直接使用 neton.quic 的连接与流，另保留
// 一层薄接口，以便测试时替换"). `com.netonstream:quic` implements it directly (phase C); tests use an in-memory double.
//
// ⚖️ Kotlin shape: the reference's `poll_*` trait methods are suspending functions, and its `Result`s are exceptions:
// a QUIC connection that is gone is a [ConnectionErrorIncoming] (thrown by `accept*`), a stream operation that fails is a
// [StreamErrorIncoming]. "Would block" (flow control, nothing received yet) is a suspension, never an exception. The
// reference's `send_data` + `poll_ready` pair (queue one `WriteBuf`, then wait until it is sent) is one suspending
// [SendStream.write]. One coroutine at a time may read a [RecvStream] and one may write a [SendStream]; the two halves
// of a bidirectional stream may be used concurrently.
//
// Mapping onto neton.quic (for phase C): `Connection.openUni/openBi/acceptUni/acceptBi` (a `Pair<SendStream,
// RecvStream>` is one [BidiStream]); `SendStream.writeChunk` / `finish` / `reset` / `stopped`; `RecvStream.readChunk`
// (the chunk's bytes; `null` at the end) / `stop`; `Connection.close(code, reason)`. neton.quic's `ConnectionError`
// maps to [ConnectionErrorIncoming] as h3-quinn's `convert_connection_error` does (ApplicationClosed →
// [ConnectionErrorIncoming.ApplicationClose], TimedOut → [ConnectionErrorIncoming.Timeout], the rest →
// [ConnectionErrorIncoming.Undefined]); `WriteError.Stopped` / `ReadError.Reset` → [StreamErrorIncoming.StreamTerminated];
// `*.ConnectionLost` → [StreamErrorIncoming.ConnectionLost]; other stream errors → [StreamErrorIncoming.Unknown].

/**
 * Why the QUIC connection is closed, as seen by HTTP/3 (`ConnectionErrorIncoming`). Thrown by [Connection.acceptUni]
 * and [Connection.acceptBi], and carried by [StreamErrorIncoming.ConnectionLost].
 */
sealed class ConnectionErrorIncoming(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The peer closed the connection with an HTTP/3 [errorCode] (CONNECTION_CLOSE of type 0x1d). */
    class ApplicationClose(val errorCode: Long) : ConnectionErrorIncoming("ApplicationClose: ${Code(errorCode)}") {
        override fun equals(other: Any?): Boolean = other is ApplicationClose && other.errorCode == errorCode
        override fun hashCode(): Int = errorCode.hashCode()
    }

    /** The QUIC idle timeout expired. */
    class Timeout : ConnectionErrorIncoming("Timeout") {
        override fun equals(other: Any?): Boolean = other is Timeout
        override fun hashCode(): Int = 1
    }

    /** An internal error in the QUIC implementation; HTTP/3 closes the connection with H3_INTERNAL_ERROR. */
    class InternalError(val reason: String) : ConnectionErrorIncoming("InternalError in the quic trait implementation: $reason")

    /** Any other reason (a transport error, a local close, ...), not relevant to HTTP/3. */
    class Undefined(val error: Throwable) : ConnectionErrorIncoming("Error undefined by h3: ${error.message ?: error}", error)
}

/** Why a stream operation failed (`StreamErrorIncoming`). */
sealed class StreamErrorIncoming(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** The whole connection is closed (the reference's `ConnectionErrorIncoming { connection_error }`). */
    class ConnectionLost(val connectionError: ConnectionErrorIncoming) :
        StreamErrorIncoming("ConnectionError: ${connectionError.message}", connectionError)

    /** The peer reset its sending side (RESET_STREAM) or stopped our sending side (STOP_SENDING) with [errorCode]. */
    class StreamTerminated(val errorCode: Long) : StreamErrorIncoming("StreamClosed: ${Code(errorCode)}")

    /** Any other error of the QUIC implementation; HTTP/3 handles it like [StreamTerminated]. */
    class Unknown(val error: Throwable) : StreamErrorIncoming("Error undefined by h3: ${error.message ?: error}", error)
}

/** Opens outgoing streams and closes the connection (`OpenStreams`). */
interface OpenStreams {
    /**
     * Opens a bidirectional stream (`poll_open_bidi`), suspending while the peer's stream limit is reached. The peer
     * learns of the stream when something is sent on it.
     * @throws StreamErrorIncoming
     */
    suspend fun openBi(): BidiStream

    /**
     * Opens a unidirectional stream (`poll_open_send`), suspending while the peer's stream limit is reached.
     * @throws StreamErrorIncoming
     */
    suspend fun openUni(): SendStream

    /** Closes the connection at once with the HTTP/3 [code] and [reason] (`close`). Idempotent. */
    fun close(code: Code, reason: ByteArray)
}

/** A QUIC connection (`Connection`). */
interface Connection : OpenStreams {
    /**
     * The next unidirectional stream opened by the peer (`poll_accept_recv`).
     * @throws ConnectionErrorIncoming once the connection is closed.
     */
    suspend fun acceptUni(): RecvStream

    /**
     * The next bidirectional stream opened by the peer (`poll_accept_bidi`).
     * @throws ConnectionErrorIncoming once the connection is closed.
     */
    suspend fun acceptBi(): BidiStream

    /** A handle that opens streams on this connection, for a client's request senders (`opener`). */
    fun opener(): OpenStreams
}

/** The sending side of a stream (`SendStream`). */
interface SendStream {
    /** The stream ID (`send_id`). */
    val sendId: StreamId

    /**
     * Sends all of [data] (`send_data` + `poll_ready`), suspending while flow control blocks.
     * @throws StreamErrorIncoming [StreamErrorIncoming.StreamTerminated] with the peer's code once it stopped the stream.
     */
    suspend fun write(data: Bytes)

    /**
     * Ends the stream cleanly (FIN, `poll_finish`). A stream the peer stopped is not an error here.
     * @throws StreamErrorIncoming
     */
    suspend fun finish()

    /** Abandons the stream with [errorCode] (RESET_STREAM, `reset`). No effect once finished or reset. */
    fun reset(errorCode: Long)

    /**
     * Waits until the peer stops the stream (STOP_SENDING), returning its error code, or until everything sent was
     * dealt with (finished and read, or reset), returning null. Not in the reference's trait: HTTP/3 uses it to notice
     * a stopped critical stream without writing to it (RFC 9114 §6.2.1, RFC 9204 §4.2). neton.quic: `stopped()`.
     * @throws StreamErrorIncoming [StreamErrorIncoming.ConnectionLost] when the connection is closed first.
     */
    suspend fun stopped(): Long?
}

/** The receiving side of a stream (`RecvStream`). */
interface RecvStream {
    /** The stream ID (`recv_id`). */
    val recvId: StreamId

    /**
     * The next received bytes (`poll_data`), or null once the peer finished the stream and everything was read.
     * @throws StreamErrorIncoming [StreamErrorIncoming.StreamTerminated] with the peer's code once it reset the stream.
     */
    suspend fun read(): Bytes?

    /** Asks the peer to stop sending with [errorCode] (STOP_SENDING, `stop_sending`); unread data is discarded. */
    fun stopSending(errorCode: Long)
}

/** A bidirectional stream (`BidiStream`): both sides, which [split] separates. */
interface BidiStream : SendStream, RecvStream {
    /** The two sides, to be used from different coroutines (`split`). */
    fun split(): Pair<SendStream, RecvStream>
}
