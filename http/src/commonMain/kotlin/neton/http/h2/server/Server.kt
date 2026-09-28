package neton.http.h2.server

import neton.http.Request
import neton.http.Response
import neton.http.h2.FlowControl
import neton.http.h2.H2Error
import neton.http.h2.PingPong
import neton.http.h2.RecvStream
import neton.http.h2.SendStream
import neton.http.h2.awaitReset
import neton.http.h2.codec.Codec
import neton.http.h2.codec.UserError
import neton.http.h2.frame.Reason
import neton.http.h2.frame.Settings
import neton.http.h2.frame.StreamId
import neton.http.h2.h2Call
import neton.http.h2.orThrow
import neton.http.h2.proto.ConnConfig
import neton.http.h2.proto.DEFAULT_LOCAL_RESET_COUNT_MAX
import neton.http.h2.proto.DEFAULT_MAX_SEND_BUFFER_SIZE
import neton.http.h2.proto.DEFAULT_REMOTE_RESET_STREAM_MAX
import neton.http.h2.proto.DEFAULT_RESET_STREAM_DURATION
import neton.http.h2.proto.DEFAULT_RESET_STREAM_MAX
import neton.http.h2.proto.DataFrameBudget
import neton.http.h2.proto.IoErrorKind
import neton.http.h2.proto.MAX_WINDOW_SIZE
import neton.http.h2.proto.PREFACE
import neton.http.h2.proto.Peer
import neton.http.h2.proto.PollReset
import neton.http.h2.proto.ProtoError
import neton.http.h2.proto.StreamRef
import neton.http.h2.proto.copySettings
import neton.io.bytes.Buffer
import neton.io.core.IoException
import neton.io.core.IoStream
import kotlin.time.Duration
import neton.http.h2.proto.Connection as ProtoConnection

// The HTTP/2 server (`h2::server`, `src/server.rs`).
//
// ```
// val connection = handshake(stream)
// launch { connection.run() }                      // drives the connection
// while (true) {
//     val (request, respond) = connection.accept() ?: break
//     launch { handle(request, respond) }
// }
// ```

/** Performs the server handshake over [io] with the default [Builder] (`server::handshake`). */
suspend fun handshake(io: IoStream): Connection = Builder().handshake(io)

/**
 * A server connection (`server::Connection`).
 *
 * ⚖️ The reference drives the connection from `accept` / `poll_closed` (the user must keep polling one of them).
 * Here [run] drives it — launch it — and [accept] only takes the next request.
 */
class Connection internal constructor(internal val inner: ProtoConnection) {
    /**
     * Drives the connection until it closes (the reference's `poll_closed`): after a GOAWAY once the streams are
     * done, when the client closes, or on error.
     * @throws H2Error the connection error.
     */
    suspend fun run() = h2Call { inner.run() }

    /**
     * The next request and the handle to respond with (`accept`), or null once the connection has closed.
     * @throws H2Error the connection error.
     */
    suspend fun accept(): Pair<Request<RecvStream>, SendResponse>? {
        while (true) {
            // A closed connection yields nothing, even with streams still queued (as the reference, whose `poll_accept`
            // first drives the connection: once it is closing, that returns its result when done).
            if (inner.isFinished || !inner.isOpen) {
                inner.awaitFinished()?.let { throw H2Error.from(it) }
                return null
            }
            val ref = inner.streams.nextIncoming()
            if (ref != null) {
                val head = inner.streams.takeRequest(ref.stream)
                val body = RecvStream(FlowControl(ref.clone()))
                return Request(head.parts, body) to SendResponse(ref)
            }
            inner.acceptWaiters.await()
        }
    }

    /** The target connection receive window (`set_target_window_size`). */
    fun setTargetWindowSize(size: Int) {
        require(size in 0..MAX_WINDOW_SIZE)
        inner.setTargetWindowSize(size)
    }

    /**
     * Sends new SETTINGS with the stream receive window (`set_initial_window_size`).
     * @throws H2Error while previous SETTINGS are not acknowledged.
     */
    fun setInitialWindowSize(size: Int) {
        require(size in 0..MAX_WINDOW_SIZE)
        inner.setInitialWindowSize(size).orThrow()
    }

    /** Sends new SETTINGS enabling extended CONNECT (`enable_connect_protocol`). @throws H2Error as above. */
    fun enableConnectProtocol() {
        inner.setEnableConnectProtocol().orThrow()
    }

    /** Closes the connection at once with GOAWAY [reason] (`abrupt_shutdown`). */
    fun abruptShutdown(reason: Reason) = inner.goAwayFromUser(reason)

    /**
     * Starts a graceful shutdown (`graceful_shutdown`): GOAWAY(2^31 - 1) and a PING; once the PONG arrives, GOAWAY
     * with the last stream processed; the connection closes when the remaining streams are done.
     */
    fun gracefulShutdown() = inner.goAwayGracefully()

    /** The ping handle, once (`ping_pong`). */
    fun pingPong(): PingPong? = inner.takeUserPings()?.let { PingPong(it) }

    /** Whether any stream is open (`has_streams`). */
    fun hasStreams(): Boolean = inner.hasStreams()

    /** The max concurrent streams this server may push (`max_concurrent_send_streams`). */
    fun maxConcurrentSendStreams(): Int = inner.maxSendStreams

    /** The max concurrent streams the client may open (`max_concurrent_recv_streams`). */
    fun maxConcurrentRecvStreams(): Int = inner.maxRecvStreams

    /** Streams still held (`num_wired_streams`, unstable in the reference). */
    fun numWiredStreams(): Int = inner.streams.store.wiredCount

    override fun toString(): String = "Connection"
}

/**
 * Responds to a request (`SendResponse`): 1xx responses, the response, pushes, or a reset. [close] releases the
 * handle (resetting the stream if nothing else holds it and it is still open).
 */
class SendResponse internal constructor(internal val ref: StreamRef) : AutoCloseable {
    val streamId: StreamId get() = ref.streamId

    /**
     * Sends a 1xx response (`send_informational`); several may precede the response.
     * @throws H2Error [UserError.InvalidInformationalStatusCode] for another status; a user error for malformed
     * headers.
     */
    fun sendInformational(response: Response<*>) {
        live()
        if (!response.status.isInformational()) throw H2Error.fromUser(UserError.InvalidInformationalStatusCode)
        val frame = Peer.serverConvertSendMessage(ref.streamId, response, false)
        ref.streams.sendInformationalHeaders(ref.stream, frame).orThrow()
    }

    /**
     * Sends the response head (`send_response`); returns the stream to send the body with (nothing may be sent on it
     * when [endOfStream]).
     * @throws H2Error a user error.
     */
    fun sendResponse(response: Response<*>, endOfStream: Boolean): SendStream {
        live()
        ref.streams.sendResponse(ref.stream, response, endOfStream).orThrow()
        return SendStream(ref.clone())
    }

    /**
     * Pushes a request (`push_request`): a PUSH_PROMISE on this stream, and the handle to respond on the pushed one.
     * Only GET or HEAD without a body may be pushed.
     * @throws H2Error [UserError.PeerDisabledServerPush], or [UserError.MalformedHeaders].
     */
    fun pushRequest(request: Request<*>): SendPushedResponse {
        live()
        val child = h2Call { ref.streams.sendPushPromise(ref.stream, request) }
        return SendPushedResponse(SendResponse(child))
    }

    /** Resets the stream (`send_reset`). */
    fun sendReset(reason: Reason) {
        live()
        ref.streams.sendResetFromUser(ref.stream, reason)
    }

    /**
     * Waits until the client resets the stream (`poll_reset`), for instance to stop working on a request whose
     * client went away.
     * @throws H2Error [UserError.PollResetAfterSendResponse] once the response is sent (use the [SendStream]'s).
     */
    suspend fun awaitReset(): Reason = awaitReset(ref, PollReset.AwaitingHeaders)

    override fun close() = ref.drop()

    private fun live() = check(!ref.isDropped) { "SendResponse used after close()" }

    override fun toString(): String = "SendResponse { stream_id: ${streamId.value} }"
}

/** Responds on a pushed stream (`SendPushedResponse`). */
class SendPushedResponse internal constructor(private val inner: SendResponse) : AutoCloseable {
    val streamId: StreamId get() = inner.streamId

    /** @throws H2Error a user error. */
    fun sendResponse(response: Response<*>, endOfStream: Boolean): SendStream = inner.sendResponse(response, endOfStream)

    fun sendReset(reason: Reason) = inner.sendReset(reason)

    /** @throws H2Error */
    suspend fun awaitReset(): Reason = inner.awaitReset()

    override fun close() = inner.close()

    override fun toString(): String = "SendPushedResponse { stream_id: ${streamId.value} }"
}

/**
 * Server connection options (`server::Builder`), with the reference's defaults. Sizes the reference takes as `u32`
 * are Ints here (values above 2^31 - 1 are not supported).
 */
class Builder {
    private var resetStreamDuration: Duration = DEFAULT_RESET_STREAM_DURATION
    private var resetStreamMax: Int = DEFAULT_RESET_STREAM_MAX
    private var pendingAcceptResetStreamMax: Int = DEFAULT_REMOTE_RESET_STREAM_MAX
    private val settings = Settings()
    private var initialTargetConnectionWindowSize: Int? = null
    private var maxSendBufferSize: Int = DEFAULT_MAX_SEND_BUFFER_SIZE
    private var localMaxErrorResetStreams: Int? = DEFAULT_LOCAL_RESET_COUNT_MAX
    private var dataFrameBudget: DataFrameBudget = DataFrameBudget.Auto

    /** SETTINGS_INITIAL_WINDOW_SIZE: the receive window of each stream (default 65,535). */
    fun initialWindowSize(size: Int) = apply { settings.initialWindowSize = size.toLong() }

    /** The receive window of the whole connection (default 65,535). */
    fun initialConnectionWindowSize(size: Int) = apply { initialTargetConnectionWindowSize = size }

    /** SETTINGS_MAX_FRAME_SIZE (16,384..16,777,215; default 16,384). */
    fun maxFrameSize(max: Int) = apply { settings.maxFrameSize = max.toLong() }

    /** SETTINGS_MAX_HEADER_LIST_SIZE (default: none sent; 16 MiB enforced). */
    fun maxHeaderListSize(max: Int) = apply { settings.maxHeaderListSize = max.toLong() }

    /** SETTINGS_HEADER_TABLE_SIZE (default 4,096). */
    fun headerTableSize(size: Int) = apply { settings.headerTableSize = size.toLong() }

    /** SETTINGS_MAX_CONCURRENT_STREAMS: concurrent client streams; more are refused (default: unlimited). */
    fun maxConcurrentStreams(max: Int) = apply { settings.maxConcurrentStreams = max.toLong() }

    /** How many locally reset streams are remembered for a while (default 50). */
    fun maxConcurrentResetStreams(max: Int) = apply { resetStreamMax = max }

    /** Library resets allowed over the connection's lifetime before GOAWAY (default 1024; null: unlimited). */
    fun maxLocalErrorResetStreams(max: Int?) = apply { localMaxErrorResetStreams = max }

    /** Remotely reset streams allowed pending accept before GOAWAY ENHANCE_YOUR_CALM (default 20). */
    fun maxPendingAcceptResetStreams(max: Int) = apply { pendingAcceptResetStreamMax = max }

    /** DATA bytes buffered per stream (default 409,600). */
    fun maxSendBufferSize(max: Int) = apply {
        require(max >= 0)
        maxSendBufferSize = max
    }

    /** How long locally reset streams are remembered (default 1 s). */
    fun resetStreamDuration(dur: Duration) = apply { resetStreamDuration = dur }

    /** SETTINGS_ENABLE_CONNECT_PROTOCOL: accept extended CONNECT (RFC 8441). */
    fun enableConnectProtocol() = apply { settings.enableConnectProtocol = 1 }

    /** The budget of small received DATA frames (default: half the connection window, at least 25,600). */
    fun dataFrameBudget(budget: Int) = apply { dataFrameBudget = DataFrameBudget.Configured(budget) }

    /**
     * Sends the server SETTINGS and reads the client preface over [io] (`handshake`).
     * @throws H2Error an I/O error, an EOF before the preface, or a GOAWAY PROTOCOL_ERROR for an invalid preface.
     */
    suspend fun handshake(io: IoStream): Connection {
        val local = copySettings(settings)
        val codec = Codec()
        local.maxFrameSize?.let { codec.setMaxRecvFrameSize(it.toInt()) }
        local.maxHeaderListSize?.let { codec.setMaxRecvHeaderListSize(minOf(it, Int.MAX_VALUE.toLong()).toInt()) }
        check(codec.buffer(local) == null)

        val readBuf = Buffer(READ_BUFFER_SIZE, pooled = true)
        try {
            // Flush the SETTINGS, then read the preface.
            val fw = codec.writer
            io.write(fw.writeBuffer)
            fw.unsetFrame()
            io.flush()
            readPreface(io, readBuf)
        } catch (e: IoException) {
            io.close()
            throw H2Error.fromIo(e)
        } catch (e: H2Error) {
            io.close()
            throw e
        }

        val inner = ProtoConnection(
            io, codec, readBuf,
            ConnConfig(
                nextStreamId = StreamId(2),
                // A server does not initiate streams (pushes are reserved).
                initialMaxSendStreams = 0,
                maxSendBufferSize = maxSendBufferSize,
                resetStreamDuration = resetStreamDuration,
                resetStreamMax = resetStreamMax,
                remoteResetStreamMax = pendingAcceptResetStreamMax,
                localErrorResetStreamsMax = localMaxErrorResetStreams,
                settings = local,
                dataFrameBudget = dataFrameBudget.resolve(initialTargetConnectionWindowSize),
            ),
            Peer.Server,
        )
        initialTargetConnectionWindowSize?.let { inner.setTargetWindowSize(it) }
        return Connection(inner)
    }

    private suspend fun readPreface(io: IoStream, buf: Buffer) {
        var pos = 0
        while (pos < PREFACE.size) {
            if (buf.readableBytes == 0 && io.read(buf) < 0) {
                throw H2Error.fromIo(IoErrorKind.UnexpectedEof, "connection closed before reading preface")
            }
            val n = minOf(buf.readableBytes, PREFACE.size - pos)
            for (i in 0 until n) {
                // "read_preface: invalid preface" (the reference does not write a GOAWAY either).
                if (buf.getByte(i) != PREFACE[pos + i]) throw H2Error.from(ProtoError.libraryGoAway(Reason.PROTOCOL_ERROR))
            }
            buf.skip(n)
            pos += n
        }
    }

    private companion object {
        const val READ_BUFFER_SIZE = 16 * 1024
    }
}
