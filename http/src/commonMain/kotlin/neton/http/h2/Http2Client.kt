package neton.http.h2

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import neton.http.Body
import neton.http.HttpError
import neton.http.Incoming
import neton.http.Method
import neton.http.OnUpgrade
import neton.http.Request
import neton.http.Response
import neton.http.StatusCode
import neton.http.Upgraded
import neton.http.h1.WriteGate
import neton.http.h2.client.ResponseFuture
import neton.http.h2.frame.Reason
import neton.http.h2.proto.IoErrorKind
import neton.io.bytes.Bytes
import neton.io.core.IoStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// hyper's HTTP/2 client (hyper 1.11.1 `src/proto/h2/client.rs` and `src/client/conn/http2.rs`) on the h2 client of
// this package: requests with connection headers stripped and a content-length from the body's size, request bodies
// pumped into their streams, `Response<Incoming>` bodies, CONNECT tunnels, keep-alive and BDP pings.

/**
 * HTTP/2 client connection options (hyper `client::conn::http2::Builder`), with hyper's defaults (not the h2 layer's):
 * a 5 MiB connection window, 2 MiB stream windows, 16 KiB frames, a 16 KiB header list, a 1 MiB send buffer per
 * stream, 100 streams before the server's SETTINGS arrive, server push disabled. Setters return this builder;
 * [handshake] takes the options as they are then.
 *
 * ⛔ hyper's `timer` and executor: the connection runs on its reactor, whose clock serves the pings.
 */
class Http2ClientConfig {
    internal var adaptiveWindow = false
    internal var initialConnWindowSize = DEFAULT_CONN_WINDOW
    internal var initialStreamWindowSize = DEFAULT_STREAM_WINDOW
    internal var initialMaxSendStreams = DEFAULT_INITIAL_MAX_SEND_STREAMS
    internal var maxFrameSize: Int? = DEFAULT_MAX_FRAME_SIZE
    internal var maxHeaderListSize = DEFAULT_MAX_HEADER_LIST_SIZE
    internal var keepAliveInterval: Duration? = null
    internal var keepAliveTimeout: Duration = 20.seconds
    internal var keepAliveWhileIdle = false
    internal var maxConcurrentResetStreams: Int? = null
    internal var maxSendBufferSize = DEFAULT_MAX_SEND_BUF_SIZE
    internal var maxPendingAcceptResetStreams: Int? = null
    internal var maxLocalErrorResetStreams: Int? = 1024
    internal var headerTableSize: Int? = null
    internal var maxConcurrentStreams: Int? = null
    internal var resetStreamDuration: Duration? = null

    /** SETTINGS_INITIAL_WINDOW_SIZE (default 2 MiB); turns the adaptive window off. Null leaves it unchanged. */
    fun initialStreamWindowSize(sz: Int?) = apply {
        if (sz != null) {
            adaptiveWindow = false
            initialStreamWindowSize = sz
        }
    }

    /** The connection receive window (default 5 MiB); turns the adaptive window off. Null leaves it unchanged. */
    fun initialConnectionWindowSize(sz: Int?) = apply {
        if (sz != null) {
            adaptiveWindow = false
            initialConnWindowSize = sz
        }
    }

    /**
     * How many streams may be opened before the server's SETTINGS arrive (default 100, the minimum the specification
     * recommends endpoints to advertise, so the server is unlikely to refuse them). Null leaves it unchanged.
     */
    fun initialMaxSendStreams(initial: Int?) = apply { if (initial != null) initialMaxSendStreams = initial }

    /**
     * Adapts the windows to the measured bandwidth-delay product (BDP pings); enabling it starts both windows at the
     * specification's 65,535 and overrides earlier window sizes (default off).
     */
    fun adaptiveWindow(enabled: Boolean) = apply {
        adaptiveWindow = enabled
        if (enabled) {
            initialConnWindowSize = SPEC_WINDOW_SIZE
            initialStreamWindowSize = SPEC_WINDOW_SIZE
        }
    }

    /** SETTINGS_MAX_FRAME_SIZE (default 16 KiB; null: none sent, the h2 default). */
    fun maxFrameSize(sz: Int?) = apply { maxFrameSize = sz }

    /** SETTINGS_MAX_HEADER_LIST_SIZE (default 16 KiB). */
    fun maxHeaderListSize(max: Int) = apply { maxHeaderListSize = max }

    /** SETTINGS_HEADER_TABLE_SIZE (default: the h2 default, 4,096). */
    fun headerTableSize(size: Int?) = apply { headerTableSize = size }

    /** SETTINGS_MAX_CONCURRENT_STREAMS: how many streams the server may push (default: none sent). */
    fun maxConcurrentStreams(max: Int?) = apply { maxConcurrentStreams = max }

    /** Sends a PING after this interval without received frames (default: none). */
    fun keepAliveInterval(interval: Duration?) = apply { keepAliveInterval = interval }

    /** Closes the connection when a keep-alive PING gets no PONG within this time (default 20 s). */
    fun keepAliveTimeout(timeout: Duration) = apply { keepAliveTimeout = timeout }

    /** Keep-alive pings also while no request is in progress (default off). */
    fun keepAliveWhileIdle(enabled: Boolean) = apply { keepAliveWhileIdle = enabled }

    /** How many locally reset streams are remembered for a while (default: the h2 default, 50). */
    fun maxConcurrentResetStreams(max: Int) = apply { maxConcurrentResetStreams = max }

    /** DATA bytes buffered per stream (default 1 MiB). */
    fun maxSendBufSize(max: Int) = apply {
        require(max >= 0)
        maxSendBufferSize = max
    }

    /** Remotely reset streams allowed pending accept (null: the h2 default, 20). */
    fun maxPendingAcceptResetStreams(max: Int?) = apply { maxPendingAcceptResetStreams = max }

    /** Library resets allowed over the connection's lifetime (default 1024; null: unlimited). */
    fun maxLocalErrorResetStreams(max: Int?) = apply { maxLocalErrorResetStreams = max }

    /** How long locally reset streams are remembered (default: the h2 default, 1 s). */
    fun resetStreamDuration(dur: Duration) = apply { resetStreamDuration = dur }

    /**
     * hyper `Builder::handshake`: writes the connection preface over [stream]; returns the request sender and the
     * connection, which must be run ([Http2ClientConnection.run]).
     * @throws HttpError an I/O error writing the preface.
     */
    suspend fun handshake(stream: IoStream): Pair<SendRequest, Http2ClientConnection> {
        val builder = neton.http.h2.client.Builder()
            .initialMaxSendStreams(initialMaxSendStreams)
            .initialWindowSize(initialStreamWindowSize)
            .initialConnectionWindowSize(initialConnWindowSize)
            .maxHeaderListSize(maxHeaderListSize)
            .maxSendBufferSize(maxSendBufferSize)
            .maxLocalErrorResetStreams(maxLocalErrorResetStreams)
            .enablePush(false)
        maxFrameSize?.let { builder.maxFrameSize(it) }
        maxConcurrentResetStreams?.let { builder.maxConcurrentResetStreams(it) }
        maxPendingAcceptResetStreams?.let { builder.maxPendingAcceptResetStreams(it) }
        headerTableSize?.let { builder.headerTableSize(it) }
        maxConcurrentStreams?.let { builder.maxConcurrentStreams(it) }
        resetStreamDuration?.let { builder.resetStreamDuration(it) }
        val ping = PingConfig(
            bdpInitialWindow = if (adaptiveWindow) initialStreamWindowSize else null,
            keepAliveInterval = keepAliveInterval,
            keepAliveTimeout = keepAliveTimeout,
            keepAliveWhileIdle = keepAliveWhileIdle,
        )
        val (h2tx, h2) = try {
            builder.handshake(stream)
        } catch (e: H2Error) {
            throw newH2(e)
        }
        val conn = Http2ClientConnection(h2tx, h2, ping)
        return SendRequest(conn) to conn
    }

    private companion object {
        // hyper's defaults are for the majority case, not resource constrained: the specification's 64 KiB window
        // can limit performance.
        const val DEFAULT_CONN_WINDOW = 1024 * 1024 * 5
        const val DEFAULT_STREAM_WINDOW = 1024 * 1024 * 2
        const val DEFAULT_MAX_FRAME_SIZE = 1024 * 16
        const val DEFAULT_MAX_SEND_BUF_SIZE = 1024 * 1024
        const val DEFAULT_MAX_HEADER_LIST_SIZE = 1024 * 16
        const val DEFAULT_INITIAL_MAX_SEND_STREAMS = 100
    }
}

/** hyper `client::conn::http2::handshake` with the default options. */
suspend fun http2Handshake(stream: IoStream): Pair<SendRequest, Http2ClientConnection> = Http2ClientConfig().handshake(stream)

/**
 * Sends requests on an HTTP/2 client connection (hyper `client::conn::http2::SendRequest`): any number concurrently.
 * [clone] gives another handle.
 *
 * ⚖️ Handles are released with [close] (hyper drops them): once every handle is closed, the connection closes when its
 * streams are done.
 */
class SendRequest internal constructor(private val c: Http2ClientConnection) : AutoCloseable {
    private var closed = false

    /** Throws [HttpError.Kind.ChannelClosed] once the connection is closed (hyper `ready` / `poll_ready`). */
    suspend fun ready() {
        if (c.isClosed) throw HttpError(HttpError.Kind.ChannelClosed)
    }

    /** Whether the connection takes requests (hyper `is_ready`). */
    val isReady: Boolean get() = !c.isClosed

    /** Whether the connection is closed (hyper `is_closed`). */
    val isClosed: Boolean get() = c.isClosed

    /**
     * Sends [request] and returns the response once its head arrived (hyper `send_request`). The request's URI must be
     * absolute, except for an HTTP/1-versioned request with a relative one (`http` is used). A body is pumped into the
     * stream in the background, so the response may come before it is all sent. Cancelling the call resets the stream.
     * @throws HttpError [HttpError.Kind.Canceled] when the connection was not ready (closed);
     * [HttpError.Kind.UserInvalidConnectWithBody] for a CONNECT with a non-zero content-length; [HttpError.Kind.Http2]
     * or [HttpError.Kind.Io] when the stream or the connection failed; a timeout ([HttpError.isTimeout]) after the
     * keep-alive timed out.
     */
    suspend fun sendRequest(request: Request<out Body>): Response<Incoming> {
        check(!closed) { "SendRequest used after close()" }
        return c.send(request)
    }

    /** Another handle to the connection (`Clone`). */
    fun clone(): SendRequest {
        check(!closed) { "SendRequest used after close()" }
        c.handles++
        return SendRequest(c)
    }

    /** Releases this handle (hyper drops it); idempotent. */
    override fun close() {
        if (closed) return
        closed = true
        c.handles--
        c.releaseIfUnused()
    }

    override fun toString(): String = "SendRequest"
}

/**
 * One HTTP/2 client connection (hyper `client::conn::http2::Connection`, `proto::h2::client::ClientTask`): [run] it in
 * its own coroutine. Requests are handed to h2 one at a time (a stream waiting for the server's concurrency limit
 * holds the next ones back, as hyper's dispatcher does); request bodies are pumped by coroutines of the connection.
 *
 * ⚖️ [run] returns when the h2 connection has closed: after every [SendRequest] is closed and the last stream is done,
 * after a GOAWAY, or on error. hyper's connection future ends as soon as the handles are gone, leaving the h2
 * connection to finish its streams on the executor.
 */
class Http2ClientConnection internal constructor(
    private val h2tx: neton.http.h2.client.SendRequest,
    private val h2: neton.http.h2.client.Connection,
    private val pingConfig: PingConfig,
) {
    internal var handles = 1
    private var h2txClosed = false
    private var inFlight = 0
    private var running = false
    private var closed = false
    private val started = CompletableDeferred<Unit>()
    private var pipeScope: CoroutineScope? = null
    private var recorder: Recorder = Recorder.DISABLED
    private val gate = WriteGate()

    internal val isClosed: Boolean get() = closed

    /** Whether the server enabled extended CONNECT (`is_extended_connect_protocol_enabled`). */
    fun isExtendedConnectProtocolEnabled(): Boolean = h2tx.isExtendedConnectProtocolEnabled

    /** The max concurrent streams this client may open (`current_max_send_streams`). */
    fun currentMaxSendStreams(): Int = h2tx.currentMaxSendStreams()

    /** The max concurrent streams the server may open (`current_max_recv_streams`). */
    fun currentMaxRecvStreams(): Int = h2tx.currentMaxRecvStreams()

    /**
     * Runs the connection until it closes (hyper polling `Connection`). Returns normally on a clean close, including a
     * graceful shutdown by the server (GOAWAY NO_ERROR); throws [HttpError] when it failed ([HttpError.Kind.Http2],
     * [HttpError.Kind.Io]), or a timeout ([HttpError.isTimeout]) when the keep-alive got no PONG.
     */
    suspend fun run() {
        check(!running) { "the connection is already running" }
        running = true
        var outcome: H2Error? = null
        try {
            coroutineScope {
                val pipes = SupervisorJob(coroutineContext.job)
                pipeScope = CoroutineScope(coroutineContext + pipes)
                val runner = launch {
                    try {
                        h2.run()
                    } catch (e: H2Error) {
                        outcome = e
                    } finally {
                        // The h2 connection is gone: the dispatcher ends with it (hyper's `ClientTask` sees
                        // `poll_ready` fail), so the handles report closed before the streams' coroutines, woken with
                        // the connection's end, see their errors.
                        closed = true
                    }
                }
                val ponger = if (pingConfig.isEnabled) startPonger(runner) { outcome = it } else null
                started.complete(Unit)
                runner.join()
                ponger?.cancel()
                // The request bodies still pumping end with the connection.
                pipes.cancel()
            }
        } finally {
            closed = true
            pipeScope = null
            started.complete(Unit)
            releaseIfUnused()
        }
        recorder.ensureNotTimedOut()
        val e = outcome ?: return
        // "connection gracefully shutdown"
        if (e.reason() == Reason.NO_ERROR) return
        throw newH2(e)
    }

    private fun startPonger(runner: Job, drop: (H2Error) -> Unit): Job {
        val pp = h2.pingPong() ?: error("conn.ping_pong")
        val shared = PingShared(pp, pingConfig.bdpInitialWindow != null, pingConfig.keepAliveInterval != null)
        recorder = Recorder.of(shared)
        val ponger = Ponger(
            shared, pingConfig,
            onSizeUpdate = { wnd ->
                h2.setTargetWindowSize(wnd)
                try {
                    h2.setInitialWindowSize(wnd)
                } catch (e: H2Error) {
                    // The reference's connection future fails and the connection is dropped: its streams see a broken
                    // pipe, and so does the dispatcher.
                    drop(H2Error.fromIo(IoErrorKind.BrokenPipe))
                    runner.cancel()
                }
            },
            // "connection keep-alive timed out": the connection is dropped.
            onKeepAliveTimedOut = { runner.cancel() },
        )
        return pipeScope!!.launch { ponger.run() }
    }

    /** Closes the h2 request handle once no [SendRequest] and no request in progress needs it. */
    internal fun releaseIfUnused() {
        if (handles == 0 && inFlight == 0 && !h2txClosed) {
            h2txClosed = true
            h2tx.close()
        }
    }

    /** hyper `ClientTask::poll` for one request, then `poll_pipe` and `ResponseFutMap`. */
    internal suspend fun send(request: Request<out Body>): Response<Incoming> {
        if (closed) throw notReady()
        // hyper's dispatcher takes requests once the connection is polled.
        if (!started.isCompleted) started.await()
        val scope = pipeScope ?: throw notReady()
        inFlight++
        val fut: ResponseFuture
        val tx: SendStream
        val body: Body = request.body
        val isConnect: Boolean
        val eos: Boolean
        try {
            gate.lock()
            try {
                try {
                    h2tx.ready()
                } catch (e: H2Error) {
                    // The dispatcher ends with the connection; its queued requests are canceled.
                    throw HttpError(HttpError.Kind.Canceled, IllegalStateException("connection closed"))
                }
                val headers = request.headers
                stripConnectionHeaders(headers, isRequest = true)
                body.sizeHint.exact?.let { len ->
                    if (len != 0L || methodHasDefinedPayloadSemantics(request.method)) setContentLengthIfMissing(headers, len)
                }
                isConnect = request.method == Method.CONNECT
                eos = body.isEndStream
                if (isConnect && contentLengthParseAll(headers) > 0) {
                    // "h2 connect request with non-zero body not supported"
                    throw HttpError(HttpError.Kind.UserInvalidConnectWithBody)
                }
                // hyper converts its `Protocol` extension to h2's; here they are one type.
                val pair = try {
                    h2tx.sendRequest(request, !isConnect && eos)
                } catch (e: H2Error) {
                    // "client send request error"
                    throw newH2(e)
                }
                fut = pair.first
                tx = pair.second
                // A stream pending open (the server's concurrency limit) holds the next requests back until it opens.
                try {
                    h2tx.ready()
                } catch (e: H2Error) {
                    fut.close()
                    tx.close()
                    throw newH2(e)
                } catch (e: CancellationException) {
                    fut.close()
                    tx.close()
                    throw e
                }
            } finally {
                gate.unlock()
            }
        } finally {
            inFlight--
            releaseIfUnused()
        }
        return exchange(scope, body, fut, tx, isConnect, eos)
    }

    private fun notReady() = HttpError(HttpError.Kind.Canceled, IllegalStateException("connection was not ready"))

    private suspend fun exchange(
        scope: CoroutineScope,
        body: Body,
        fut: ResponseFuture,
        tx: SendStream,
        isConnect: Boolean,
        eos: Boolean,
    ): Response<Incoming> {
        var pipe: ClientPipe? = null
        var connectTx: SendStream? = null
        if (!isConnect) {
            if (!eos) {
                // Keep the ping recorder's knowledge of an open stream while the body is still sending.
                val p = ClientPipe(body, tx, recorder.clone())
                p.job = scope.launch(start = CoroutineStart.UNDISPATCHED) { p.run() }
                pipe = p
            } else {
                tx.close()
            }
        } else {
            connectTx = tx
        }
        val ping = recorder.clone()
        val res = try {
            fut.await()
        } catch (e: CancellationException) {
            // The caller gave up on the response (hyper `SendWhen` canceled): tell the pipe to reset the stream so a
            // RST_STREAM is sent and flow-control capacity is freed.
            pipe?.cancelByCaller()
            connectTx?.close()
            fut.close()
            ping.release()
            throw e
        } catch (e: H2Error) {
            connectTx?.close()
            ping.release()
            ping.ensureNotTimedOut()
            // "client response error"
            throw newH2(e)
        }
        // Record that we got the response headers.
        ping.recordNonData()
        val contentLength = contentLengthParseAll(res.headers)
        val recv = res.body
        if (connectTx != null && res.status == StatusCode.OK) {
            if (contentLength > 0) {
                // "h2 connect response with non-zero body not supported"
                connectTx.sendReset(Reason.INTERNAL_ERROR)
                connectTx.close()
                recv.close()
                ping.release()
                throw newH2(H2Error.fromReason(Reason.INTERNAL_ERROR))
            }
            val onUpgrade = OnUpgrade()
            onUpgrade.fulfill(Upgraded(H2Upgraded(connectTx, recv, ping), Bytes.EMPTY))
            val out = Response(res.parts, Incoming.EMPTY)
            out.extensions.insert(onUpgrade)
            return out
        }
        connectTx?.close()
        return Response(res.parts, h2Incoming(recv, contentLength, ping.forStream(recv.isEndStream)))
    }

    /** A request body pumped into its stream (hyper `PipeMap`). */
    private class ClientPipe(private val body: Body, private val tx: SendStream, private val ping: Recorder) {
        var job: Job? = null
        private var canceled = false
        private val watch = ResetWatch(tx.ref)

        fun cancelByCaller() {
            val j = job ?: return
            if (j.isCompleted) return
            canceled = true
            j.cancel()
        }

        suspend fun run() {
            try {
                PipeToSendStream(body, tx, watch).run()
            } catch (e: CancellationException) {
                if (canceled) {
                    // "client request body send cancelled, resetting stream"
                    tx.sendReset(Reason.CANCEL)
                } else if (watch.reset == null) {
                    throw e
                }
            } catch (e: Throwable) {
                // "client request body error"
            } finally {
                watch.stop()
                tx.close()
                ping.release()
            }
        }
    }

    override fun toString(): String = "Connection"
}
