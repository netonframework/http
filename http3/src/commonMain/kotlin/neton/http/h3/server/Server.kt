package neton.http.h3.server

import neton.http.Request
import neton.http.RequestParts
import neton.http.Response
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
import neton.http.h3.StreamError
import neton.http.h3.connectionErrorOnStream
import neton.http.h3.proto.Frame
import neton.http.h3.proto.FrameException
import neton.http.h3.proto.Header
import neton.http.h3.proto.HeaderException
import neton.http.h3.proto.Side
import neton.http.h3.proto.StreamId
import neton.http.h3.quic.BidiStream
import neton.http.h3.quic.Connection as QuicConnection
import neton.http.h3.quic.ConnectionErrorIncoming
import neton.http.h3.quic.StreamErrorIncoming
import neton.http.h3.streamError
import neton.http.header.HeaderMap
import neton.http.header.HeaderValue
import neton.io.bytes.Bytes

// The HTTP/3 server (`h3::server`: `src/server/{builder,connection,request,stream}.rs`).
//
// ```
// val connection = neton.http.h3.server.builder().build(quic)
// launch { connection.run() }                       // drives the connection
// while (true) {
//     val resolver = connection.accept() ?: break   // null: graceful end
//     launch {
//         val (request, stream) = resolver.resolveRequest()
//         stream.sendResponse(Response.builder().status(200).body(Unit))
//         stream.sendData(body)
//         stream.finish()
//     }
// }
// ```

/** A server connection builder with the default [Config] (`server::builder`). */
fun builder(): Builder = Builder()

/** A server connection over [conn] with the default configuration (`Connection::new`). @throws ConnectionError */
suspend fun newConnection(conn: QuicConnection): Connection = builder().build(conn)

/**
 * Builds server connections (`server::Builder`). ⛔ The reference's `enable_webtransport`, `enable_extended_connect`,
 * `enable_datagram` and `max_webtransport_sessions` are not in the first version (SPEC §5); they stay off.
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
     * Opens the control stream (with SETTINGS) and the QPACK streams on [conn] and returns the connection (`build`).
     * @throws ConnectionError
     */
    suspend fun build(conn: QuicConnection): Connection =
        Connection(ConnectionInner.create(conn, config.copy(), Side.Server))
}

/**
 * A server connection (`server::Connection`).
 *
 * ⚖️ Driving: the reference reads the control stream while `accept` is polled. Here [run] drives the connection —
 * launch it — and [accept] takes the next request (as `neton.http.h2.server.Connection`). [run] rejects request
 * streams past a GOAWAY on its own, whether or not [accept] is called.
 *
 * Not thread-safe: use it on the reactor its QUIC connection runs on.
 */
class Connection internal constructor(internal val inner: ConnectionInner) : AutoCloseable {
    private val shared = inner.shared
    private val incoming = ArrayDeque<BidiStream>()
    private val ongoing = HashSet<Long>()
    private var lastAccepted: StreamId? = null

    init {
        inner.onControlFrame = ::onControlFrame
    }

    /**
     * Drives the connection until it closes or fails, and returns the connection error (for a close without error,
     * one for which [ConnectionError.isH3NoError] holds): reads the client's control and QPACK streams, accepts request
     * streams for [accept].
     */
    suspend fun run(): ConnectionError = inner.drive { acceptRequestStreams() }

    private suspend fun acceptRequestStreams() {
        while (true) {
            val s = try {
                inner.conn.acceptBi()
            } catch (e: ConnectionErrorIncoming) {
                inner.handleConnectionError(ErrorOrigin.Quic(e))
                return
            }
            if (isRejected(s.sendId)) {
                reject(s)
            } else {
                incoming.addLast(s)
                shared.changed.notifyAll()
            }
        }
    }

    /**
     * ⚖️ Whether a request stream is past the GOAWAY sent: RFC 9114 §5.2, the GOAWAY carries the first stream ID that
     * will not be processed, so IDs greater than **or equal to** it are rejected. The reference rejects only greater
     * IDs (`send_id() > max_id`), accepting the request at the boundary (SPEC §5).
     */
    private fun isRejected(id: StreamId): Boolean {
        val max = inner.sentClosing ?: return false
        return id.value >= max
    }

    /** Rejects a request without processing it: H3_REQUEST_REJECTED both ways (RFC 9114 §4.1.1, §5.2). */
    private fun reject(s: BidiStream) {
        s.stopSending(Code.H3_REQUEST_REJECTED.value)
        s.reset(Code.H3_REQUEST_REJECTED.value)
    }

    /**
     * The next request (`accept`): a [RequestResolver] to read its head with. Returns null once the connection is
     * shutting down and done: after the client's GOAWAY, or after this server's GOAWAY once every stream below it
     * was accepted, when no accepted request is still in progress (the reference also sends a last GOAWAY then).
     * [run] must be running.
     * @throws ConnectionError the connection error.
     */
    suspend fun accept(): RequestResolver? {
        while (true) {
            shared.error?.let { throw it.toConnectionError() }
            val s = incoming.removeFirstOrNull()
            if (s != null) {
                val id = s.sendId
                lastAccepted = id
                ongoing.add(id.value)
                val stream = RequestStreamInner(s, s, shared, inner.config, inner.sendGreaseFrame)
                // Send the GREASE frame only once.
                inner.sendGreaseFrame = false
                return RequestResolver(stream, RequestEnd(this, id.value))
            }
            if (ongoing.isEmpty() && isDone()) {
                // Always send a last GOAWAY, so the client knows which was the last request not rejected.
                shutdown(0)
                return null
            }
            shared.changed.await()
        }
    }

    private fun isDone(): Boolean {
        if (inner.recvClosing != null) return true
        val max = inner.sentClosing ?: return false
        val next = lastAccepted?.let { it.value + 4 } ?: StreamId.FIRST_REQUEST.value
        return next >= max
    }

    internal fun requestEnded(id: Long) {
        ongoing.remove(id)
        shared.changed.notifyAll()
    }

    /**
     * Starts a graceful shutdown (`shutdown`, RFC 9114 §5.2): sends GOAWAY so that [maxRequests] more requests after
     * the last accepted one are still processed; later ones are rejected with H3_REQUEST_REJECTED. ⚖️ The GOAWAY ID is
     * the first stream ID not processed (the last accepted + [maxRequests] + 1 streams, or the first request stream +
     * [maxRequests] when none was accepted); the reference sends the last one processed. A GOAWAY never raises the ID
     * of an earlier one.
     * @throws ConnectionError
     */
    suspend fun shutdown(maxRequests: Int) {
        require(maxRequests >= 0)
        val maxId = lastAccepted?.let { it + (maxRequests.toLong() + 1) } ?: (StreamId.FIRST_REQUEST + maxRequests.toLong())
        inner.shutdown(maxId.value)
        // Streams already received past the GOAWAY are rejected too.
        val it = incoming.iterator()
        while (it.hasNext()) {
            val s = it.next()
            if (isRejected(s.sendId)) {
                reject(s)
                it.remove()
            }
        }
        shared.changed.notifyAll()
    }

    /** The server's control-stream frames (`poll_next_control`). */
    private fun onControlFrame(frame: Frame): InternalConnectionError? = when (frame) {
        is Frame.Goaway -> inner.processGoaway(frame.id).also { shared.changed.notifyAll() }
        // Push is not supported: MAX_PUSH_ID and CANCEL_PUSH are ignored, as in the reference.
        is Frame.MaxPushId, is Frame.CancelPush -> null
        else -> InternalConnectionError(Code.H3_FRAME_UNEXPECTED, "on server control stream: $frame")
    }

    /** The client's settings, once received. */
    val peerSettings: Settings? get() = shared.peerSettings

    /** Test hook (`set_settings`): behave as if the client's SETTINGS had arrived, unless they already have. */
    internal fun setPeerSettings(settings: Settings) = shared.setSettings(settings)

    /** The connection error, if the connection has failed or closed. */
    val error: ConnectionError? get() = inner.connectionError()

    /**
     * Closes the connection with H3_NO_ERROR (the reference's `Drop`), unless it already failed; operations still
     * pending fail with that error.
     */
    override fun close() {
        inner.handleConnectionError(Code.H3_NO_ERROR, "Connection was closed by the server")
    }
}

/** Tells the connection once when a request is over (the reference's `RequestEnd`, sent on drop). */
internal class RequestEnd(private val conn: Connection?, private val id: Long) {
    private var done = false

    fun end() {
        if (done) return
        done = true
        conn?.requestEnded(id)
    }

    companion object {
        /** For the receive part of a split stream: only the send part ends the request. */
        val NONE = RequestEnd(null, -1)
    }
}

/**
 * Reads the head of an accepted request (`RequestResolver`). [close] abandons the request if it was not resolved.
 */
class RequestResolver internal constructor(private val inner: RequestStreamInner, private val end: RequestEnd) :
    AutoCloseable {
    private var used = false

    /** The request stream ID. */
    val id: StreamId get() = inner.id

    /**
     * Waits for the request head and returns the request and the stream to respond on (`resolve_request`).
     *
     * - The first frame must be HEADERS: another frame is a connection error of H3_FRAME_UNEXPECTED; the stream
     *   ending first resets it with H3_REQUEST_INCOMPLETE ([StreamError.Stream]).
     * - A head over one of the three limits of SPEC §5 is answered with 431 (see below), and
     *   [StreamError.HeaderTooBig] is thrown.
     * - A malformed head resets and stops the stream with H3_MESSAGE_ERROR ([StreamError.Stream]); a QPACK error is a
     *   connection error of QPACK_DECOMPRESSION_FAILED.
     *
     * The 431 mechanism (`ResolvedRequest::resolve`): the server sends a response HEADERS frame with status 431
     * (Request Header Fields Too Large) on the request stream and ends its sending side cleanly (FIN); then it stops
     * reading the request with STOP_SENDING(H3_NO_ERROR) (RFC 9114 §4.1: a complete response before the whole
     * request). The reference sends the same 431 HEADERS and leaves the rest to dropping the stream, which in quinn
     * finishes the send side and stops the receive side with code 0; ⚖️ here both are explicit, with H3_NO_ERROR as the
     * RFC recommends instead of 0, and no GREASE frame. The limits are checked as the head arrives: a HEADERS frame
     * longer than `maxHeadersFrameSize` from its frame header (the payload is never read), the decoded size and the
     * field count field line by field line.
     * @throws StreamError
     */
    suspend fun resolveRequest(): Pair<Request<Unit>, RequestStream> {
        check(!used) { "the request was already resolved" }
        used = true
        try {
            return resolve()
        } catch (e: Throwable) {
            end.end()
            throw e
        }
    }

    private suspend fun resolve(): Pair<Request<Unit>, RequestStream> {
        val shared = inner.shared
        val frame = try {
            inner.nextFrame()
        } catch (e: FrameException) {
            (inner.headerTooBig(e) as? StreamError.HeaderTooBig)?.let { reject431(it) }
            throw shared.connectionErrorOnStream(InternalConnectionError.of(e))
        } catch (e: StreamErrorIncoming) {
            throw shared.streamError(e)
        }
        val block = when (frame) {
            // RFC 9114 §4.1: a request stream ending without a complete request: H3_REQUEST_INCOMPLETE.
            null -> {
                inner.stopStream(Code.H3_REQUEST_INCOMPLETE)
                throw StreamError.Stream(Code.H3_REQUEST_INCOMPLETE, "stream terminated without headers")
            }
            is Frame.Headers -> frame.block
            // RFC 9114 §4.1: an invalid sequence of frames; §7.2.5: PUSH_PROMISE from a client.
            else -> throw shared.connectionErrorOnStream(
                InternalConnectionError(Code.H3_FRAME_UNEXPECTED, "first request frame is not headers"),
            )
        }
        val header = try {
            inner.decodeSection(block, "Failed to decode headers")
        } catch (e: StreamError.HeaderTooBig) {
            reject431(e)
        } catch (e: HeaderException) {
            throw malformed(e.message)
        }
        val parts = try {
            header.intoRequestParts()
        } catch (e: HeaderException) {
            throw malformed(e.message)
        }
        // ⚖️ Checks the reference lacks (RFC 9114 §4.3.1; RFC 9220 §3: :protocol only with extended CONNECT enabled).
        if (header.pseudo.status != null) throw malformed(":status in a request")
        if (parts.protocol != null) throw malformed(":protocol without extended CONNECT")
        inner.contentLength = header.contentLength
        val request = Request(RequestParts(parts.method, parts.uri, Version.HTTP_3, parts.headers), Unit)
        return request to RequestStream(inner, end)
    }

    /** RFC 9114 §4.1.2: a malformed request is a stream error of H3_MESSAGE_ERROR (reset and stop). */
    private fun malformed(reason: String?): StreamError = inner.malformed("request: ${reason ?: "invalid header section"}")

    /** Answers a head over a limit with 431 and ends the stream as described in [resolveRequest]. */
    private suspend fun reject431(error: StreamError.HeaderTooBig): Nothing {
        try {
            inner.sendHeaders(Header.response(StatusCode.REQUEST_HEADER_FIELDS_TOO_LARGE, HeaderMap.new()), grease = false)
            inner.finish()
        } finally {
            inner.stopSending(Code.H3_NO_ERROR)
        }
        throw error
    }

    /** Abandons the request if it was not resolved: H3_REQUEST_CANCELLED both ways. */
    override fun close() {
        if (used) return
        used = true
        inner.stopStream(Code.H3_REQUEST_CANCELLED)
        inner.stopSending(Code.H3_REQUEST_CANCELLED)
        end.end()
    }
}

/**
 * The server side of a request (`server::RequestStream`): reads the request body and trailers, sends the response.
 * The request counts as in progress for a graceful shutdown until the response is finished or the stream reset
 * ([finish], [stopStream], [close]).
 */
class RequestStream internal constructor(private val inner: RequestStreamInner, private val end: RequestEnd) :
    AutoCloseable {
    /** The stream ID (`id`, `send_id`). */
    val id: StreamId get() = inner.id

    /** The next piece of the request body, or null once it is complete (`recv_data`). @throws StreamError */
    suspend fun recvData(): Bytes? = inner.recvData()

    /** The request trailers, or null when there are none (`recv_trailers`). @throws StreamError */
    suspend fun recvTrailers(): HeaderMap<HeaderValue>? = inner.recvTrailers()

    /** Asks the client to stop sending the request (`stop_sending`). */
    fun stopSending(code: Code) = inner.stopSending(code)

    /**
     * Sends the response head (`send_response`); before any [sendData].
     * @throws StreamError [StreamError.HeaderTooBig] beyond the client's SETTINGS_MAX_FIELD_SECTION_SIZE.
     */
    suspend fun sendResponse(response: Response<*>) = ended { inner.sendHeaders(Header.response(response.status, response.headers)) }

    /** Sends a piece of the response body as a DATA frame (`send_data`). @throws StreamError */
    suspend fun sendData(data: Bytes) = ended { inner.sendData(data) }

    /** Sends the response trailers; [finish] still has to be called (`send_trailers`). @throws StreamError */
    suspend fun sendTrailers(trailers: HeaderMap<HeaderValue>) = ended { inner.sendTrailers(trailers) }

    /** Ends the response (`finish`). @throws StreamError */
    suspend fun finish() {
        try {
            inner.finish()
        } finally {
            end.end()
        }
    }

    /** Resets the response with [code] (`stop_stream`); H3_NO_ERROR is allowed. */
    fun stopStream(code: Code) {
        inner.stopStream(code)
        end.end()
    }

    /**
     * The send part and the receive part, for use from different coroutines (`split`); each part supports only its
     * side's operations (the other side's throw [IllegalStateException]). Not while a DATA frame is partly read.
     */
    fun split(): Pair<RequestStream, RequestStream> {
        val (send, recv) = inner.split()
        return RequestStream(send, end) to RequestStream(recv, RequestEnd.NONE)
    }

    /**
     * Releases the stream (the reference's `Drop`): an unfinished response is reset with H3_REQUEST_CANCELLED, and an
     * unfinished request is stopped with H3_NO_ERROR once the response is complete (RFC 9114 §4.1), else
     * H3_REQUEST_CANCELLED.
     *
     * Call it (or `use { }`) once done with the request, also after [finish]: where Rust drops the stream, Kotlin has to
     * close it. Until then a request whose end was not read keeps its QUIC stream open, and with it one of the
     * client's bidirectional stream credits; a server that never closes its streams stops receiving requests on the
     * connection once those credits are used up (100 by default).
     */
    override fun close() {
        val responded = inner.sendClosed || inner.send == null
        inner.stopStream(Code.H3_REQUEST_CANCELLED)
        inner.stopSending(if (responded) Code.H3_NO_ERROR else Code.H3_REQUEST_CANCELLED)
        end.end()
    }

    /** A send operation; a stream error or a lost connection ends the request. */
    private inline fun <T> ended(block: () -> T): T {
        try {
            return block()
        } catch (e: StreamError) {
            if (e !is StreamError.HeaderTooBig) end.end()
            throw e
        }
    }
}
