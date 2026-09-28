package neton.http.h3.client

import neton.http.Method
import neton.http.Request
import neton.http.Response
import neton.http.ResponseParts
import neton.http.StatusCode
import neton.http.Version
import neton.http.h3.Code
import neton.http.h3.Config
import neton.http.h3.ConnectionError
import neton.http.h3.ConnectionInner
import neton.http.h3.ErrorOrigin
import neton.http.h3.InternalConnectionError
import neton.http.h3.RequestStreamInner
import neton.http.h3.Settings
import neton.http.h3.SharedState
import neton.http.h3.StreamError
import neton.http.h3.connectionErrorOnStream
import neton.http.h3.proto.Frame
import neton.http.h3.proto.FrameException
import neton.http.h3.proto.Header
import neton.http.h3.proto.HeaderException
import neton.http.h3.proto.Side
import neton.http.h3.proto.StreamId
import neton.http.h3.proto.WriteBuf
import neton.http.h3.quic.Connection as QuicConnection
import neton.http.h3.quic.ConnectionErrorIncoming
import neton.http.h3.quic.OpenStreams
import neton.http.h3.quic.StreamErrorIncoming
import neton.http.h3.streamError
import neton.http.h3.writeBuf
import neton.http.header.HeaderMap
import neton.http.header.HeaderValue
import neton.io.bytes.Bytes

// The HTTP/3 client (`h3::client`: `src/client/{builder,connection,stream}.rs`).
//
// ```
// val (connection, sendRequest) = neton.http.h3.client.newClient(quic)
// launch { connection.run() }                       // drives the connection
// val stream = sendRequest.sendRequest(Request.get("https://example.com/").body(Unit))
// stream.finish()
// val response = stream.recvResponse()
// while (true) { val data = stream.recvData() ?: break; ... }
// sendRequest.close()                               // the last sender closes the connection (H3_NO_ERROR)
// ```

/** A client connection builder with the default [Config] (`client::builder`). */
fun builder(): Builder = Builder()

/** A client connection over [conn] with the default configuration (`client::new`). @throws ConnectionError */
suspend fun newClient(conn: QuicConnection): Pair<Connection, SendRequest> = builder().build(conn)

/**
 * Builds client connections (`client::Builder`). ⛔ The reference's `enable_datagram` and `enable_extended_connect` are
 * not in the first version (SPEC §5); they stay off.
 */
class Builder internal constructor() {
    internal val config = Config()

    /** The largest decoded field section accepted, advertised as SETTINGS_MAX_FIELD_SECTION_SIZE (default 64 KiB). */
    fun maxFieldSectionSize(value: Long) = apply {
        require(value >= 0)
        config.maxFieldSectionSize = value
    }

    /** ⚖️ The largest encoded HEADERS payload accepted (default 64 KiB, SPEC §5). */
    fun maxHeadersFrameSize(value: Int) = apply {
        require(value >= 0)
        config.maxHeadersFrameSize = value
    }

    /** ⚖️ The most field lines accepted in a field section (default 100, SPEC §5). */
    fun maxFieldCount(value: Int) = apply {
        require(value >= 0)
        config.maxFieldCount = value
    }

    /** Whether to send GREASE (default true). */
    fun sendGrease(value: Boolean) = apply { config.sendGrease = value }

    /** Test hook (the reference's `send_settings`): false leaves the control and QPACK streams unwritten. */
    internal fun sendSettings(value: Boolean) = apply { config.sendSettings = value }

    /**
     * Opens the control stream (with SETTINGS) and the QPACK streams on [conn] (`build`): the connection to drive
     * and the first request sender.
     * @throws ConnectionError
     */
    suspend fun build(conn: QuicConnection): Pair<Connection, SendRequest> {
        val cfg = config.copy()
        val inner = ConnectionInner.create(conn, cfg, Side.Client)
        return Connection(inner) to SendRequest(conn.opener(), inner.shared, cfg, SenderCount(), cfg.sendGrease)
    }
}

/**
 * A client connection (`client::Connection`). [run] drives it — launch it: it reads the server's control and QPACK
 * streams (settings, GOAWAY) and returns the connection error once the connection closes.
 */
class Connection internal constructor(internal val inner: ConnectionInner) {
    private val shared = inner.shared

    init {
        inner.onControlFrame = ::onControlFrame
    }

    /**
     * Drives the connection until it closes or fails, and returns the connection error (`poll_close`, `wait_idle`;
     * for a close without error [ConnectionError.isH3NoError] holds). A server-initiated bidirectional stream is a
     * connection error of H3_STREAM_CREATION_ERROR (RFC 9114 §6.1).
     */
    suspend fun run(): ConnectionError = inner.drive { refuseBidiStreams() }

    private suspend fun refuseBidiStreams() {
        try {
            inner.conn.acceptBi()
        } catch (e: ConnectionErrorIncoming) {
            inner.handleConnectionError(ErrorOrigin.Quic(e))
            return
        }
        inner.handleConnectionError(
            Code.H3_STREAM_CREATION_ERROR,
            "client received a server-initiated bidirectional stream",
        )
    }

    /**
     * Sends GOAWAY (`shutdown`): the client accepts no push, so the push ID is 0. New requests then fail with
     * [StreamError.RemoteClosing].
     * @throws ConnectionError
     */
    suspend fun shutdown(maxPush: Int = 0) {
        require(maxPush >= 0)
        inner.shutdown(0)
    }

    /** The client's control-stream frames (`poll_close`). */
    private fun onControlFrame(frame: Frame): InternalConnectionError? = when (frame) {
        is Frame.Goaway -> {
            // RFC 9114 §7.2.6: from a server, GOAWAY carries a client-initiated bidirectional stream ID.
            if (!StreamId(frame.id).isRequest) {
                InternalConnectionError(Code.H3_ID_ERROR, "non-request StreamId in a GoAway frame: ${frame.id}")
            } else {
                inner.processGoaway(frame.id)
            }
        }
        // RFC 9114 §7.2.7: MAX_PUSH_ID from a server; the reference also refuses CANCEL_PUSH (and any other frame).
        else -> InternalConnectionError(Code.H3_FRAME_UNEXPECTED, "on client control stream: $frame")
    }

    /** The server's settings, once received. */
    val peerSettings: Settings? get() = shared.peerSettings

    /** Whether a GOAWAY was received or sent. */
    val isClosing: Boolean get() = shared.closing

    /** The connection error, if the connection has failed or closed. */
    val error: ConnectionError? get() = inner.connectionError()
}

/** How many [SendRequest]s share a connection (the reference's `sender_count`). */
internal class SenderCount {
    var count = 1
}

/**
 * Sends requests (`SendRequest`). [clone] makes another sender for the same connection; [close] releases this one,
 * and closing the last one closes the connection with H3_NO_ERROR (the reference does this on `Drop`).
 */
class SendRequest internal constructor(
    private val open: OpenStreams,
    private val shared: SharedState,
    private val config: Config,
    private val senders: SenderCount,
    private var sendGreaseFrame: Boolean,
) : AutoCloseable {
    private var closed = false

    /**
     * Opens a request stream and sends [request]'s head (`send_request`); the body goes through the returned stream,
     * which [RequestStream.finish] ends.
     * @throws StreamError [StreamError.RemoteClosing] after a GOAWAY; [StreamError.HeaderTooBig] beyond the server's
     * SETTINGS_MAX_FIELD_SECTION_SIZE (the stream was already opened; it is ended empty, as the reference's dropped
     * stream is); [StreamError.Connection] when the connection is closed.
     */
    suspend fun sendRequest(request: Request<*>): RequestStream {
        check(!closed) { "SendRequest used after close()" }
        if (shared.closing) throw StreamError.RemoteClosing()
        val header = try {
            Header.request(request.method, request.uri, request.headers)
        } catch (e: HeaderException) {
            // ⚖️ The reference makes this a connection error of H3_INTERNAL_ERROR; a request the caller built wrong
            // fails alone here.
            throw StreamError.Stream(Code.H3_INTERNAL_ERROR, "Failed to build request headers: ${e.message}")
        }
        // RFC 9114 §4.1: a client sends a single request on a given stream.
        val stream = try {
            open.openBi()
        } catch (e: StreamErrorIncoming) {
            throw shared.streamError(e)
        }
        val inner = RequestStreamInner(stream, stream, shared, config, sendGreaseFrame)
        val block = try {
            inner.encode(header)
        } catch (e: StreamError.HeaderTooBig) {
            try {
                stream.finish()
            } catch (_: StreamErrorIncoming) {
            }
            throw e
        }
        try {
            stream.writeBuf(WriteBuf.of(Frame.Headers(block)))
        } catch (e: StreamErrorIncoming) {
            throw shared.streamError(e)
        }
        // Send the GREASE frame only once.
        sendGreaseFrame = false
        return RequestStream(inner, request.method)
    }

    /** Another sender for the same connection (`Clone`). */
    fun clone(): SendRequest {
        check(!closed) { "SendRequest used after close()" }
        senders.count++
        return SendRequest(open, shared, config, senders, sendGreaseFrame)
    }

    /** The server's settings, or the defaults before they arrive (`settings`). */
    val settings: Settings get() = shared.settings

    /** Test hook (`set_settings`): behave as if the server's SETTINGS had arrived, unless they already have. */
    internal fun setPeerSettings(settings: Settings) = shared.setSettings(settings)

    /** Releases this sender; the last one closes the connection with H3_NO_ERROR (`Drop`). */
    override fun close() {
        if (closed) return
        closed = true
        if (--senders.count == 0) {
            shared.connectionErrorOnStream(InternalConnectionError(Code.H3_NO_ERROR, "Connection closed by client"))
        }
    }
}

/** The client side of a request (`client::RequestStream`). */
class RequestStream internal constructor(private val inner: RequestStreamInner, private val method: Method?) :
    AutoCloseable {
    /** The stream ID (`id`). */
    val id: StreamId get() = inner.id

    /**
     * Waits for the response head (`recv_response`).
     * - The stream ending without it is a connection error of H3_FRAME_UNEXPECTED, as is another first frame.
     * - A head over one of the three limits of SPEC §5 stops the stream with H3_REQUEST_CANCELLED and throws
     *   [StreamError.HeaderTooBig]; a malformed one stops it and throws [StreamError.Stream] (H3_MESSAGE_ERROR).
     * - The server resetting the stream is [StreamError.RemoteTerminate] (H3_REQUEST_REJECTED past a GOAWAY).
     * @throws StreamError
     */
    suspend fun recvResponse(): Response<Unit> {
        val shared = inner.shared
        val frame = try {
            inner.nextFrame()
        } catch (e: FrameException) {
            inner.headerTooBig(e)?.let {
                inner.stopSending(Code.H3_REQUEST_CANCELLED)
                throw it
            }
            throw shared.connectionErrorOnStream(InternalConnectionError.of(e))
        } catch (e: StreamErrorIncoming) {
            throw shared.streamError(e)
        }
        val block = when (frame) {
            null -> throw shared.connectionErrorOnStream(
                InternalConnectionError(Code.H3_FRAME_UNEXPECTED, "Stream finished without receiving response headers"),
            )
            is Frame.Headers -> frame.block
            else -> throw shared.connectionErrorOnStream(
                InternalConnectionError(Code.H3_FRAME_UNEXPECTED, "First response frame is not headers"),
            )
        }
        val header = try {
            inner.decodeSection(block, "Failed to decode headers")
        } catch (e: StreamError.HeaderTooBig) {
            inner.stopSending(Code.H3_REQUEST_CANCELLED)
            throw e
        } catch (e: HeaderException) {
            throw malformed()
        }
        val (status, headers) = try {
            header.intoResponseParts()
        } catch (e: HeaderException) {
            throw malformed()
        }
        // The body length is checked against content-length, except where the response has no body (RFC 9110 §6.4.1).
        val noBody = method == Method.HEAD || status.isInformational() || status == StatusCode.NO_CONTENT ||
            status == StatusCode.NOT_MODIFIED
        inner.contentLength = if (noBody) null else header.contentLength
        return Response(ResponseParts(status, Version.HTTP_3, headers), Unit)
    }

    /** A malformed response: stop reading with H3_REQUEST_CANCELLED, as the reference does. */
    private fun malformed(): StreamError {
        inner.stopSending(Code.H3_REQUEST_CANCELLED)
        return StreamError.Stream(Code.H3_MESSAGE_ERROR, "Received malformed header")
    }

    /** The next piece of the response body, or null once it is complete (`recv_data`). @throws StreamError */
    suspend fun recvData(): Bytes? = inner.recvData()

    /**
     * The response trailers, or null when there are none (`recv_trailers`); trailers over a limit also stop the
     * stream with H3_REQUEST_CANCELLED.
     * @throws StreamError
     */
    suspend fun recvTrailers(): HeaderMap<HeaderValue>? {
        try {
            return inner.recvTrailers()
        } catch (e: StreamError.HeaderTooBig) {
            inner.stopSending(Code.H3_REQUEST_CANCELLED)
            throw e
        }
    }

    /** Asks the server to stop sending (`stop_sending`), e.g. to cancel the request. */
    fun stopSending(code: Code) = inner.stopSending(code)

    /** Sends a piece of the request body as a DATA frame (`send_data`). @throws StreamError */
    suspend fun sendData(data: Bytes) = inner.sendData(data)

    /** Sends the request trailers; [finish] still has to be called (`send_trailers`). @throws StreamError */
    suspend fun sendTrailers(trailers: HeaderMap<HeaderValue>) = inner.sendTrailers(trailers)

    /** Ends the request (`finish`). @throws StreamError */
    suspend fun finish() = inner.finish()

    /** Resets the request with [code] (`stop_stream`). */
    fun stopStream(code: Code) = inner.stopStream(code)

    /**
     * The send part and the receive part, for use from different coroutines (`split`); each part supports only its
     * side's operations. Not while a DATA frame is partly read.
     */
    fun split(): Pair<RequestStream, RequestStream> {
        val (send, recv) = inner.split()
        return RequestStream(send, method) to RequestStream(recv, method)
    }

    /**
     * Cancels what is unfinished (the reference's `Drop`): an unfinished request is reset and an unfinished response
     * stopped, with H3_REQUEST_CANCELLED (RFC 9114 §4.1.1).
     */
    override fun close() {
        inner.stopStream(Code.H3_REQUEST_CANCELLED)
        inner.stopSending(Code.H3_REQUEST_CANCELLED)
    }
}
