package neton.http.h2.client

import neton.http.Request
import neton.http.Response
import neton.http.h2.FlowControl
import neton.http.h2.H2Error
import neton.http.h2.PingPong
import neton.http.h2.RecvStream
import neton.http.h2.SendStream
import neton.http.h2.codec.Codec
import neton.http.h2.frame.Settings
import neton.http.h2.frame.StreamId
import neton.http.h2.h2Call
import neton.http.h2.proto.ConnConfig
import neton.http.h2.proto.DEFAULT_LOCAL_RESET_COUNT_MAX
import neton.http.h2.proto.DEFAULT_MAX_SEND_BUFFER_SIZE
import neton.http.h2.proto.DEFAULT_REMOTE_RESET_STREAM_MAX
import neton.http.h2.proto.DEFAULT_RESET_STREAM_DURATION
import neton.http.h2.proto.DEFAULT_RESET_STREAM_MAX
import neton.http.h2.proto.DataFrameBudget
import neton.http.h2.proto.MAX_WINDOW_SIZE
import neton.http.h2.proto.PREFACE
import neton.http.h2.proto.Peer
import neton.http.h2.proto.Recv
import neton.http.h2.proto.StreamRef
import neton.http.h2.proto.copySettings
import neton.io.bytes.Buffer
import neton.io.core.IoException
import neton.io.core.IoStream
import kotlin.time.Duration
import neton.http.h2.proto.Connection as ProtoConnection

// The HTTP/2 client (`h2::client`, `src/client.rs`).
//
// ```
// val (client, connection) = handshake(stream)
// launch { connection.run() }                      // drives the connection (the reference spawns it)
// val (response, body) = client.sendRequest(request, endOfStream = false)
// body.sendData(bytes, endOfStream = true)
// val res = response.await()
// ```

/** Performs the client handshake over [io] with the default [Builder] (`client::handshake`). */
suspend fun handshake(io: IoStream): Pair<SendRequest, Connection> = Builder().handshake(io)

/**
 * Sends requests on a connection (`SendRequest`). [clone] gives another handle; the connection closes gracefully
 * once every handle and every stream is gone, so close handles no longer used.
 *
 * Backpressure: when the peer's concurrency limit is reached, one request per handle is queued until a stream is
 * available; wait with [ready] before sending the next one.
 */
class SendRequest internal constructor(internal val conn: ProtoConnection) : AutoCloseable {
    private var pending: StreamRef? = null
    private var closed = false

    init {
        conn.streams.cloneHandle()
    }

    /**
     * Waits until a new request may be sent (`poll_ready` / `ready`): the previous request of this handle has been
     * given a stream.
     * @throws H2Error the connection error, or [neton.http.h2.codec.UserError.OverflowedStreamId].
     */
    suspend fun ready() {
        while (true) {
            live()
            val ok = h2Call { conn.streams.pollPendingOpen(pending) }
            if (ok) {
                pending?.drop()
                pending = null
                return
            }
            pending!!.stream.sendTask.await()
        }
    }

    /**
     * Sends a request's head (`send_request`): returns the future response and the stream to send the body with
     * (nothing may be sent on it when [endOfStream]). The URI must be absolute (an HTTP/1 request relative URI gets
     * `http`); an extended CONNECT carries its [neton.http.h2.Protocol] in the extensions.
     * @throws H2Error the connection error, or a user error (request not ready, malformed headers...).
     */
    fun sendRequest(request: Request<*>, endOfStream: Boolean): Pair<ResponseFuture, SendStream> {
        live()
        val (stream, isFull) = h2Call { conn.streams.sendRequest(request, endOfStream, pending) }
        // Only block the next request when the queue of pending opens is full.
        if (stream.stream.isPendingOpen && isFull) {
            pending?.drop()
            pending = stream.clone()
        }
        val response = ResponseFuture(stream.clone())
        return response to SendStream(stream)
    }

    /** Whether the server enabled extended CONNECT (`is_extended_connect_protocol_enabled`). */
    val isExtendedConnectProtocolEnabled: Boolean get() = conn.streams.isExtendedConnectProtocolEnabled

    /** The current max concurrent streams this client may open (`current_max_send_streams`). */
    fun currentMaxSendStreams(): Int = conn.streams.maxSendStreams

    /** The current max concurrent streams the server may open (`current_max_recv_streams`). */
    fun currentMaxRecvStreams(): Int = conn.streams.maxRecvStreams

    /** Streams with an ID association (`num_active_streams`, unstable in the reference). */
    fun numActiveStreams(): Int = conn.streams.store.activeCount

    /** Streams still held (`num_wired_streams`, unstable in the reference). */
    fun numWiredStreams(): Int = conn.streams.store.wiredCount

    /** Another handle to the connection (`Clone`), without this one's pending request. */
    fun clone(): SendRequest {
        live()
        return SendRequest(conn)
    }

    override fun close() {
        if (closed) return
        closed = true
        pending?.drop()
        pending = null
        conn.streams.dropHandle()
    }

    private fun live() = check(!closed) { "SendRequest used after close()" }

    override fun toString(): String = "SendRequest"
}

/**
 * A client connection (`client::Connection`): [run] drives it (the reference's `Connection` future, which the user
 * spawns) and returns when it closes.
 */
class Connection internal constructor(internal val inner: ProtoConnection) {
    /**
     * Drives the connection until it closes: when every [SendRequest] handle and every stream is gone, after a
     * GOAWAY, or on error.
     * @throws H2Error the connection error.
     */
    suspend fun run() = h2Call { inner.run() }

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
        inner.setInitialWindowSize(size)?.let { throw H2Error.fromUser(it) }
    }

    /** The ping handle, once (`ping_pong`). */
    fun pingPong(): PingPong? = inner.takeUserPings()?.let { PingPong(it) }

    /** The max concurrent streams this client may open (`max_concurrent_send_streams`). */
    fun maxConcurrentSendStreams(): Int = inner.maxSendStreams

    /** The max concurrent streams the server may open (`max_concurrent_recv_streams`). */
    fun maxConcurrentRecvStreams(): Int = inner.maxRecvStreams

    /** Streams still held (`num_wired_streams`, unstable in the reference). */
    fun numWiredStreams(): Int = inner.streams.store.wiredCount

    override fun toString(): String = "Connection"
}

/**
 * A response on its way (`ResponseFuture`). [await] returns it; the future is then done. [close] abandons it (the
 * reference drops the future), which cancels the stream if nothing else holds it.
 */
class ResponseFuture internal constructor(private val ref: StreamRef) : AutoCloseable {
    private var pushPromiseConsumed = false

    /** The stream's ID (`stream_id`). */
    val streamId: StreamId get() = ref.streamId

    /**
     * Waits for the response (the future's `poll`); its body reads the stream.
     * @throws H2Error when the stream or the connection failed.
     */
    suspend fun await(): Response<RecvStream> {
        try {
            while (true) {
                check(!ref.isDropped) { "ResponseFuture already completed or closed" }
                val v = h2Call { ref.streams.recv.pollResponse(ref.stream) }
                if (v === Recv.PENDING) {
                    ref.stream.recvTask.await()
                    continue
                }
                val head = v as Response<*>
                val body = RecvStream(FlowControl(ref.clone()))
                return Response(head.parts, body)
            }
        } finally {
            // The future is consumed.
            ref.drop()
        }
    }

    /**
     * The next 1xx response (`poll_informational`), or null once the final response is next or the stream ended.
     * @throws H2Error when the stream or the connection failed.
     */
    suspend fun informational(): Response<Unit>? {
        while (true) {
            check(!ref.isDropped) { "ResponseFuture already completed or closed" }
            val v = h2Call { ref.streams.recv.pollInformational(ref.stream) }
            if (v === Recv.PENDING) {
                ref.stream.recvTask.await()
                continue
            }
            @Suppress("UNCHECKED_CAST")
            return v as Response<Unit>?
        }
    }

    /** The server pushes on this stream (`push_promises`); once. */
    fun pushPromises(): PushPromises {
        check(!pushPromiseConsumed) { "Reference to push promises stream taken!" }
        pushPromiseConsumed = true
        return PushPromises(ref.clone())
    }

    override fun close() = ref.drop()

    override fun toString(): String = "ResponseFuture { stream_id: ${streamId.value} }"
}

/** The pushes promised on a stream (`PushPromises`). */
class PushPromises internal constructor(private val ref: StreamRef) : AutoCloseable {
    /**
     * The next push promise, or null when no more will come (`push_promise`).
     * @throws H2Error when the stream or the connection failed.
     */
    suspend fun pushPromise(): PushPromise? {
        while (true) {
            check(!ref.isDropped) { "PushPromises used after close()" }
            val v = h2Call { ref.streams.recv.pollPushed(ref.stream) }
            when {
                v === Recv.PENDING -> ref.stream.pushTask.await()
                v == null -> return null
                else -> {
                    val pushed = v as Recv.PushedEvent
                    ref.streams.cloneHandle()
                    val response = PushedResponseFuture(ResponseFuture(StreamRef(ref.streams, pushed.stream)))
                    return PushPromise(pushed.request, response)
                }
            }
        }
    }

    override fun close() = ref.drop()

    override fun toString(): String = "PushPromises"
}

/** A pushed request and its future response (`PushPromise`). */
class PushPromise internal constructor(
    /** The promised request (`request`). */
    val request: Request<Unit>,
    /** The pushed response (`into_parts`). */
    val response: PushedResponseFuture,
) {
    operator fun component1(): Request<Unit> = request
    operator fun component2(): PushedResponseFuture = response
}

/** The response of a pushed stream (`PushedResponseFuture`). */
class PushedResponseFuture internal constructor(private val inner: ResponseFuture) : AutoCloseable {
    val streamId: StreamId get() = inner.streamId

    /** @throws H2Error when the stream or the connection failed. */
    suspend fun await(): Response<RecvStream> = inner.await()

    override fun close() = inner.close()
}

/**
 * Client connection options (`client::Builder`), with the reference's defaults. Sizes the reference takes as `u32`
 * are Ints here (values above 2^31 - 1 are not supported).
 */
class Builder {
    private var resetStreamDuration: Duration = DEFAULT_RESET_STREAM_DURATION
    private var initialMaxSendStreams: Int = Int.MAX_VALUE
    private var initialTargetConnectionWindowSize: Int? = null
    private var maxSendBufferSize: Int = DEFAULT_MAX_SEND_BUFFER_SIZE
    private var resetStreamMax: Int = DEFAULT_RESET_STREAM_MAX
    private var pendingAcceptResetStreamMax: Int = DEFAULT_REMOTE_RESET_STREAM_MAX
    private val settings = Settings()
    private var streamId: StreamId = StreamId(1)
    private var localMaxErrorResetStreams: Int? = DEFAULT_LOCAL_RESET_COUNT_MAX
    private var dataFrameBudget: DataFrameBudget = DataFrameBudget.Auto
    private var settingsAckTimeout: Duration? = null

    /** SETTINGS_INITIAL_WINDOW_SIZE: the receive window of each stream (default 65,535). */
    fun initialWindowSize(size: Int) = apply { settings.initialWindowSize = size.toLong() }

    /** The receive window of the whole connection (default 65,535). */
    fun initialConnectionWindowSize(size: Int) = apply { initialTargetConnectionWindowSize = size }

    /** SETTINGS_MAX_FRAME_SIZE (16,384..16,777,215; default 16,384). */
    fun maxFrameSize(max: Int) = apply { settings.maxFrameSize = max.toLong() }

    /** SETTINGS_MAX_HEADER_LIST_SIZE (default: none sent; 16 MiB enforced). */
    fun maxHeaderListSize(max: Int) = apply { settings.maxHeaderListSize = max.toLong() }

    /** SETTINGS_MAX_CONCURRENT_STREAMS: how many streams the server may push concurrently (default: unlimited). */
    fun maxConcurrentStreams(max: Int) = apply { settings.maxConcurrentStreams = max.toLong() }

    /** How many streams may be opened before the server's SETTINGS are known (default: unlimited). */
    fun initialMaxSendStreams(initial: Int) = apply { initialMaxSendStreams = initial }

    /** How many locally reset streams are remembered for a while (default 50). */
    fun maxConcurrentResetStreams(max: Int) = apply { resetStreamMax = max }

    /** How long locally reset streams are remembered (default 1 s). */
    fun resetStreamDuration(dur: Duration) = apply { resetStreamDuration = dur }

    /** Library resets allowed over the connection's lifetime before GOAWAY (default 1024; null: unlimited). */
    fun maxLocalErrorResetStreams(max: Int?) = apply { localMaxErrorResetStreams = max }

    /** Remotely reset streams allowed pending accept (default 20). */
    fun maxPendingAcceptResetStreams(max: Int) = apply { pendingAcceptResetStreamMax = max }

    /** DATA bytes buffered per stream (default 409,600). */
    fun maxSendBufferSize(max: Int) = apply {
        require(max >= 0)
        maxSendBufferSize = max
    }

    /** SETTINGS_ENABLE_PUSH (default: not sent, i.e. enabled). */
    fun enablePush(enabled: Boolean) = apply { settings.setEnablePush(enabled) }

    /** SETTINGS_HEADER_TABLE_SIZE (default 4,096). */
    fun headerTableSize(size: Int) = apply { settings.headerTableSize = size.toLong() }

    /** The budget of small received DATA frames (default: half the connection window, at least 25,600). */
    fun dataFrameBudget(budget: Int) = apply { dataFrameBudget = DataFrameBudget.Configured(budget) }

    /**
     * ⚖️ Closes the connection with GOAWAY SETTINGS_TIMEOUT when our SETTINGS are not acknowledged within [timeout]
     * (RFC 9113 §6.5.3); null (the default, as the reference) waits forever.
     */
    fun settingsAckTimeout(timeout: Duration?) = apply { settingsAckTimeout = timeout }

    /** The first stream ID (`initial_stream_id`, unstable in the reference); must be odd. */
    fun initialStreamId(streamId: Int) = apply {
        this.streamId = StreamId(streamId)
        require(this.streamId.isClientInitiated) { "stream id must be odd" }
    }

    /**
     * Writes the client preface over [io] and sets the connection up (`handshake`); the SETTINGS go out once the
     * connection runs.
     * @throws H2Error an I/O error writing the preface.
     */
    suspend fun handshake(io: IoStream): Pair<SendRequest, Connection> {
        // bind_connection: the preface.
        try {
            io.write(Buffer(PREFACE.size).also { it.writeBytes(PREFACE) })
        } catch (e: IoException) {
            io.close()
            throw H2Error.fromIo(e)
        }

        val local = copySettings(settings)
        val codec = Codec()
        local.maxFrameSize?.let { codec.setMaxRecvFrameSize(it.toInt()) }
        local.maxHeaderListSize?.let { codec.setMaxRecvHeaderListSize(minOf(it, Int.MAX_VALUE.toLong()).toInt()) }
        // The initial SETTINGS.
        check(codec.buffer(local) == null)

        val inner = ProtoConnection(
            io, codec, Buffer(READ_BUFFER_SIZE, pooled = true),
            ConnConfig(
                nextStreamId = streamId,
                initialMaxSendStreams = initialMaxSendStreams,
                maxSendBufferSize = maxSendBufferSize,
                resetStreamDuration = resetStreamDuration,
                resetStreamMax = resetStreamMax,
                remoteResetStreamMax = pendingAcceptResetStreamMax,
                localErrorResetStreamsMax = localMaxErrorResetStreams,
                settings = local,
                dataFrameBudget = dataFrameBudget.resolve(initialTargetConnectionWindowSize),
                settingsAckTimeout = settingsAckTimeout,
            ),
            Peer.Client,
        )
        val sendRequest = SendRequest(inner)
        initialTargetConnectionWindowSize?.let { inner.setTargetWindowSize(it) }
        return sendRequest to Connection(inner)
    }

    private companion object {
        const val READ_BUFFER_SIZE = 16 * 1024
    }
}
