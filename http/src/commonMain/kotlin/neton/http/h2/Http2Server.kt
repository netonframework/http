package neton.http.h2

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import neton.http.Body
import neton.http.HttpError
import neton.http.Incoming
import neton.http.Method
import neton.http.OnUpgrade
import neton.http.Request
import neton.http.Response
import neton.http.Upgraded
import neton.http.h1.HttpDate
import neton.http.h1.HttpService
import neton.http.h1.InlineCall
import neton.http.h2.frame.Reason
import neton.http.h2.server.SendResponse
import neton.http.header.HeaderName
import neton.http.header.HeaderValue
import neton.io.bytes.Bytes
import neton.io.core.IoStream
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// hyper's HTTP/2 server (hyper 1.11.1 `src/proto/h2/server.rs` and `src/server/conn/http2.rs`) on the h2 server of
// this package: a service called per stream, `Request<Incoming>` bodies read from the stream, response bodies pumped
// into it, connection headers stripped, a Date header, CONNECT and extended CONNECT tunnels, keep-alive and BDP pings.

/**
 * HTTP/2 server connection options (hyper `server::conn::http2::Builder`), with hyper's defaults (not the h2 layer's):
 * 1 MiB connection and stream windows, 16 KiB frames, a 400 KiB send buffer per stream, a 16 KiB header list, 200
 * concurrent streams, a Date header. Setters return this builder; [serveConnection] takes the options as they are then.
 *
 * ⛔ hyper's `timer` and executor: the connection runs on its reactor, whose clock serves the pings.
 */
class Http2ServerConfig {
    internal var adaptiveWindow = false
    internal var initialConnWindowSize = DEFAULT_CONN_WINDOW
    internal var initialStreamWindowSize = DEFAULT_STREAM_WINDOW
    internal var maxFrameSize = DEFAULT_MAX_FRAME_SIZE
    internal var enableConnectProtocol = false
    internal var maxConcurrentStreams: Int? = 200
    internal var maxPendingAcceptResetStreams: Int? = null
    internal var maxLocalErrorResetStreams: Int? = DEFAULT_MAX_LOCAL_ERROR_RESET_STREAMS
    internal var keepAliveInterval: Duration? = null
    internal var keepAliveTimeout: Duration = 20.seconds
    internal var maxSendBufferSize = DEFAULT_MAX_SEND_BUF_SIZE
    internal var headerTableSize: Int? = null
    internal var maxHeaderListSize = DEFAULT_SETTINGS_MAX_HEADER_LIST_SIZE
    internal var dateHeader = true

    /** Remotely reset streams allowed pending accept before GOAWAY ENHANCE_YOUR_CALM (null: the h2 default, 20). */
    fun maxPendingAcceptResetStreams(max: Int?) = apply { maxPendingAcceptResetStreams = max }

    /** Library resets allowed over the connection's lifetime (default 1024; null: unlimited). */
    fun maxLocalErrorResetStreams(max: Int?) = apply { maxLocalErrorResetStreams = max }

    /** SETTINGS_INITIAL_WINDOW_SIZE (default 1 MiB); turns the adaptive window off. Null leaves it unchanged. */
    fun initialStreamWindowSize(sz: Int?) = apply {
        if (sz != null) {
            adaptiveWindow = false
            initialStreamWindowSize = sz
        }
    }

    /** The connection receive window (default 1 MiB); turns the adaptive window off. Null leaves it unchanged. */
    fun initialConnectionWindowSize(sz: Int?) = apply {
        if (sz != null) {
            adaptiveWindow = false
            initialConnWindowSize = sz
        }
    }

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

    /** SETTINGS_MAX_FRAME_SIZE (default 16 KiB). Null leaves it unchanged. */
    fun maxFrameSize(sz: Int?) = apply { if (sz != null) maxFrameSize = sz }

    /** SETTINGS_MAX_CONCURRENT_STREAMS (default 200; null: no limit sent). */
    fun maxConcurrentStreams(max: Int?) = apply { maxConcurrentStreams = max }

    /** Sends a PING after this interval without received frames (default: none). Pinged even while idle. */
    fun keepAliveInterval(interval: Duration?) = apply { keepAliveInterval = interval }

    /** Closes the connection when a keep-alive PING gets no PONG within this time (default 20 s). */
    fun keepAliveTimeout(timeout: Duration) = apply { keepAliveTimeout = timeout }

    /** DATA bytes buffered per stream (default 400 KiB). */
    fun maxSendBufSize(max: Int) = apply {
        require(max >= 0)
        maxSendBufferSize = max
    }

    /** SETTINGS_ENABLE_CONNECT_PROTOCOL: accept extended CONNECT (RFC 8441). */
    fun enableConnectProtocol() = apply { enableConnectProtocol = true }

    /** SETTINGS_HEADER_TABLE_SIZE (default: the h2 default, 4,096). */
    fun headerTableSize(size: Int?) = apply { headerTableSize = size }

    /** SETTINGS_MAX_HEADER_LIST_SIZE (default 16 KiB). */
    fun maxHeaderListSize(max: Int) = apply { maxHeaderListSize = max }

    /** Adds a Date header to responses that have none (default on). */
    fun autoDateHeader(enabled: Boolean) = apply { dateHeader = enabled }

    /** hyper `Builder::serve_connection`: the connection, to [Http2Connection.serve]. */
    fun serveConnection(stream: IoStream, service: HttpService): Http2Connection = serveConnection(stream, service, Bytes.EMPTY)

    /** [serveConnection] with [replay] already read from [stream] (the auto server's protocol detection). */
    internal fun serveConnection(stream: IoStream, service: HttpService, replay: Bytes): Http2Connection {
        val builder = neton.http.h2.server.Builder()
            .initialWindowSize(initialStreamWindowSize)
            .initialConnectionWindowSize(initialConnWindowSize)
            .maxFrameSize(maxFrameSize)
            .maxHeaderListSize(maxHeaderListSize)
            .maxLocalErrorResetStreams(maxLocalErrorResetStreams)
            .maxSendBufferSize(maxSendBufferSize)
        maxConcurrentStreams?.let { builder.maxConcurrentStreams(it) }
        maxPendingAcceptResetStreams?.let { builder.maxPendingAcceptResetStreams(it) }
        headerTableSize?.let { builder.headerTableSize(it) }
        if (enableConnectProtocol) builder.enableConnectProtocol()
        val ping = PingConfig(
            bdpInitialWindow = if (adaptiveWindow) initialStreamWindowSize else null,
            keepAliveInterval = keepAliveInterval,
            keepAliveTimeout = keepAliveTimeout,
            // A server with keep-alive always pings while idle, to close dead connections more aggressively.
            keepAliveWhileIdle = true,
        )
        return Http2Connection(stream, service, builder, ping, dateHeader, replay)
    }

    private companion object {
        // hyper's defaults are for the majority case, not resource constrained: the specification's 64 KiB window
        // can limit performance. A server more often has many clients, so it uses less than a client.
        const val DEFAULT_CONN_WINDOW = 1024 * 1024
        const val DEFAULT_STREAM_WINDOW = 1024 * 1024
        const val DEFAULT_MAX_FRAME_SIZE = 1024 * 16
        const val DEFAULT_MAX_SEND_BUF_SIZE = 1024 * 400
        const val DEFAULT_SETTINGS_MAX_HEADER_LIST_SIZE = 1024 * 16
        const val DEFAULT_MAX_LOCAL_ERROR_RESET_STREAMS = 1024
    }
}

/** Serves HTTP/2 on [stream] until the connection ends (hyper `serve_connection(...).await`); see [Http2Connection.serve]. */
suspend fun serveHttp2(stream: IoStream, config: Http2ServerConfig = Http2ServerConfig(), service: HttpService) =
    config.serveConnection(stream, service).serve()

/**
 * One HTTP/2 server connection (hyper `server::conn::http2::Connection`, `proto::h2::Server`): the handshake, then each
 * request stream is served by [HttpService] in its own coroutine (hyper spawns an `H2Stream` per stream), its body an
 * [Incoming] reading the stream; the response head is sent once the service returns and its body is pumped into the
 * stream with flow control.
 *
 * - A CONNECT request (or an extended CONNECT, its `:protocol` as a [Protocol] extension) gets an [OnUpgrade]: a 2xx
 *   response turns the stream into the [Upgraded] tunnel it yields; any other outcome cancels it.
 * - A client reset while the service or the body is pending cancels them.
 * - ⚖️ At the end of a stream's exchange (the response is sent, or failed), a request body not read to its end is
 *   dropped as hyper drops it with the service's future: its stream handle is released (h2 then resets a stream the
 *   client still sends on); reading it afterwards gives its end. Kotlin has no destructors to see a body kept by the
 *   service beyond its response.
 * - ⚖️ When the connection ends, stream coroutines still running are cancelled (hyper leaves its spawned tasks
 *   running; their responses could not be sent any more).
 */
class Http2Connection internal constructor(
    private val stream: IoStream,
    private val service: HttpService,
    private val builder: neton.http.h2.server.Builder,
    private val pingConfig: PingConfig,
    private val dateHeader: Boolean,
    private val replay: Bytes = Bytes.EMPTY,
) {
    private var conn: neton.http.h2.server.Connection? = null
    private var closePending = false
    private var recorder: Recorder = Recorder.DISABLED

    /**
     * Serves the connection until it ends. Returns normally on a clean end (the client closed, or a graceful shutdown
     * completed); throws [HttpError] when it failed: [HttpError.Kind.Http2] for a protocol error (a GOAWAY),
     * [HttpError.Kind.Io] for an I/O error, a timeout ([HttpError.isTimeout]) when the keep-alive got no PONG. The
     * stream is closed on return.
     */
    suspend fun serve() {
        val h2 = try {
            builder.handshake(stream, replay)
        } catch (e: H2Error) {
            throw newH2(e)
        }
        conn = h2
        // graceful_shutdown was called before the handshake finished.
        if (closePending) h2.gracefulShutdown()
        coroutineScope {
            val runner = launch {
                try {
                    h2.run()
                } catch (_: H2Error) {
                    // The connection's error is reported by `accept`.
                }
            }
            val ponger = if (pingConfig.isEnabled) startPonger(h2, this) else null
            val streams = SupervisorJob(coroutineContext.job)
            val streamScope = CoroutineScope(coroutineContext + streams)
            var dropConnection = false
            try {
                while (true) {
                    val next = try {
                        h2.accept()
                    } catch (e: H2Error) {
                        throw newH2(e)
                    }
                    if (next == null) {
                        // No more incoming streams: the connection is complete.
                        recorder.ensureNotTimedOut()
                        break
                    }
                    if (!dispatch(next.first, next.second, streamScope)) {
                        // A CONNECT request with a body: the reference's server future returns here, dropping the
                        // connection.
                        dropConnection = true
                        break
                    }
                }
            } finally {
                ponger?.cancel()
                streams.cancel()
                if (dropConnection) runner.cancel()
            }
        }
    }

    /**
     * Starts a graceful shutdown (hyper `graceful_shutdown`): GOAWAY, then the connection closes once the streams in
     * progress are done. Before the handshake completed, the shutdown starts right after it. Call it on the
     * connection's reactor thread.
     */
    fun gracefulShutdown() {
        val c = conn
        if (c == null) closePending = true else c.gracefulShutdown()
    }

    private fun startPonger(h2: neton.http.h2.server.Connection, scope: CoroutineScope): Job {
        val pp = h2.pingPong() ?: error("conn.ping_pong")
        val shared = PingShared(pp, pingConfig.bdpInitialWindow != null, pingConfig.keepAliveInterval != null)
        recorder = Recorder.of(shared)
        val ponger = Ponger(
            shared, pingConfig,
            onSizeUpdate = { wnd ->
                h2.setTargetWindowSize(wnd)
                try {
                    h2.setInitialWindowSize(wnd)
                } catch (_: H2Error) {
                }
            },
            // "keep-alive timed out, closing connection"
            onKeepAliveTimedOut = { h2.abruptShutdown(Reason.NO_ERROR) },
        )
        return scope.launch { ponger.run() }
    }

    /** Hands an accepted stream to a service coroutine (`poll_server`); false for a CONNECT with a body. */
    private fun dispatch(req: Request<RecvStream>, respond: SendResponse, scope: CoroutineScope): Boolean {
        val contentLength = contentLengthParseAll(req.headers)
        val ping = recorder.clone()
        // Record the headers received.
        ping.recordNonData()
        val body: Incoming
        var connect: ConnectParts? = null
        if (req.method != Method.CONNECT) {
            body = h2Incoming(req.body, contentLength, ping)
        } else {
            if (contentLength > 0) {
                // "h2 connect request with non-zero body not supported"
                respond.sendReset(Reason.INTERNAL_ERROR)
                respond.close()
                req.body.close()
                ping.release()
                return false
            }
            val pending = OnUpgrade()
            req.extensions.insert(pending)
            body = Incoming.EMPTY
            connect = ConnectParts(pending, ping, req.body)
        }
        // hyper converts h2's `Protocol` extension to its own; here they are one type.
        val task = H2Stream(Request(req.parts, body), body, respond, connect)
        scope.launch(start = CoroutineStart.UNDISPATCHED) { task.run() }
        return true
    }

    private class ConnectParts(val pending: OnUpgrade, val ping: Recorder, val recv: RecvStream)

    /** One stream: the service, then the response (hyper `H2Stream`). */
    private inner class H2Stream(
        private val request: Request<Incoming>,
        private val body: Incoming,
        private val reply: SendResponse,
        private var connect: ConnectParts?,
    ) {
        private val watch = ResetWatch(reply.ref)
        private val serviceCall = InlineCall<H2Stream, Response<out Body>>(H2Stream::callService)

        suspend fun callService(): Response<out Body> = service.call(request)

        suspend fun run() {
            try {
                respond(awaitService() ?: return)
            } catch (e: CancellationException) {
                // A reset seen by the watch ends the stream; a cancelled connection ends it too.
                if (watch.reset == null) throw e
            } catch (e: Throwable) {
                // "stream error" (logged at debug level by the reference): the stream ends, not the connection.
            } finally {
                watch.stop()
                reply.close()
                body.close()
                connect?.let {
                    it.pending.fail(HttpError(HttpError.Kind.Canceled))
                    it.recv.close()
                    it.ping.release()
                }
            }
        }

        /** The service's response, or null when the stream failed (reset, or the service's error sent as a reset). */
        @Suppress("UNCHECKED_CAST")
        private suspend fun awaitService(): Response<out Body>? {
            val context = coroutineContext
            serviceCall.context = context
            try {
                val r = serviceCall.start(this)
                if (r !== COROUTINE_SUSPENDED) return r as Response<out Body>
                // Not ready: a client RST_STREAM cancels the request.
                watch.start(context)
                return serviceCall.await()
            } catch (e: CancellationException) {
                if (watch.reset != null || !currentCoroutineContext().isActive) throw e
                serviceFailed(e)
            } catch (e: Throwable) {
                serviceFailed(e)
            }
            return null
        }

        private fun serviceFailed(e: Throwable) {
            // "http2 service errored"
            val err = HttpError(HttpError.Kind.UserService, e)
            reply.sendReset(err.h2Reason())
        }

        /** Sends the response (`reply!`) and its body. @throws HttpError when the stream failed. */
        private suspend fun respond(res: Response<out Body>) {
            val headers = res.headers
            stripConnectionHeaders(headers, isRequest = false)
            // Set the Date header if it isn't already set.
            if (dateHeader && !headers.containsKey(HeaderName.DATE)) {
                headers.insert(HeaderName.DATE, HeaderValue.fromMaybeSharedUnchecked(HttpDate.nowBytes()))
            }
            val cp = connect
            if (cp != null && res.status.isSuccess()) {
                if (contentLengthParseAll(headers) > 0) {
                    // "h2 successful response to CONNECT request with body not supported"
                    reply.sendReset(Reason.INTERNAL_ERROR)
                    throw HttpError(HttpError.Kind.UserUnexpectedHeader)
                }
                // "successful response to CONNECT request disallows content-length header"
                headers.remove(HeaderName.CONTENT_LENGTH)
                val sendStream = sendResponse(res, false)
                connect = null
                cp.pending.fulfill(Upgraded(H2Upgraded(sendStream, cp.recv, cp.ping), Bytes.EMPTY))
                return
            }
            val body = res.body
            if (!body.isEndStream) {
                // Content-Length from the body's exact size.
                body.sizeHint.exact?.let { setContentLengthIfMissing(headers, it) }
                val tx = sendResponse(res, false)
                try {
                    PipeToSendStream(body, tx, watch).run()
                } finally {
                    tx.close()
                }
                return
            }
            sendResponse(res, true).close()
        }

        private fun sendResponse(res: Response<out Body>, endOfStream: Boolean): SendStream =
            try {
                reply.sendResponse(res, endOfStream)
            } catch (e: H2Error) {
                // "send response error"
                reply.sendReset(Reason.INTERNAL_ERROR)
                throw newH2(e)
            }
    }
}
