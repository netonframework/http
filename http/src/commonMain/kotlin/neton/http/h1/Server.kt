package neton.http.h1

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import neton.http.Body
import neton.http.Frame
import neton.http.HttpError
import neton.http.Incoming
import neton.http.Method
import neton.http.OnUpgrade
import neton.http.Request
import neton.http.Response
import neton.http.StatusCode
import neton.http.Upgraded
import neton.http.h1.parse.ParserConfig
import neton.io.bytes.Bytes
import neton.io.core.ClosedException
import neton.io.core.IoException
import neton.io.core.IoStream
import neton.io.core.StreamCapability
import neton.io.core.closeGracefully
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

/** A request handler (hyper `service::HttpService`): one response per request. */
fun interface HttpService {
    suspend fun call(request: Request<Incoming>): Response<out Body>
}

/**
 * HTTP/1 server connection options (hyper `server::conn::http1::Builder`, SPEC §3.7). The ⚖️ ones are the safety
 * baseline's (SPEC §3.9).
 */
class Http1ServerConfig(
    /** Keep the connection open when the client half-closes while waiting for a response (hyper `half_close`). */
    val halfClose: Boolean = false,
    val keepAlive: Boolean = true,
    val titleCaseHeaders: Boolean = false,
    val preserveHeaderCase: Boolean = false,
    val maxHeaders: Int = 100,
    /** ⚖️ First byte to end of a head (hyper: 30 s from the start of the wait); 0 disables. Needs [StreamCapability.ReadTimeout]. */
    val headerReadTimeoutMillis: Long = 10_000,
    /** ⚖️ Waiting for the first byte of the next request on a kept-alive connection; 0 disables. Needs [StreamCapability.ReadTimeout]. */
    val keepAliveIdleTimeoutMillis: Long = 60_000,
    /** Vectored writes (queue strategy) or copying into one buffer; null: vectored (hyper picks by the stream). */
    val writev: Boolean? = null,
    val maxBufSize: Int = H1Io.DEFAULT_MAX_BUFFER_SIZE,
    val autoDateHeader: Boolean = true,
    /** Coalesce the responses of pipelined requests (hyper `pipeline_flush`). */
    val pipelineFlush: Boolean = false,
    val parser: ParserConfig = ParserConfig.DEFAULT,
    /** ⚖️ Request line limit (beyond → 414). */
    val maxRequestLineSize: Int = 8 * 1024,
    /** ⚖️ Head size limit (beyond → 431). */
    val maxHeaderSectionSize: Int = 64 * 1024,
    /** ⚖️ false: Transfer-Encoding with Content-Length → 400; true: hyper's behaviour. */
    val lenientTeWithCl: Boolean = false,
    /** ⚖️ Request body limit (beyond → 413 and close); 0 disables. hyper has none. */
    val maxRequestBodySize: Long = 10L * 1024 * 1024,
    /** Hand the connection over after a `101` / CONNECT `2xx` response (hyper `Connection::with_upgrades`). */
    val upgrades: Boolean = false,
    /**
     * Admission shared by the connections that should be limited together (neton-io §28.12, SPEC §3.10): an idle
     * connection holds no permit; one is taken once the first byte of a request is buffered and released when its
     * response has been written. No free permit within the admission's timeout fails the connection. Keep
     * [headerReadTimeoutMillis] on so a permit holder cannot stall its head.
     */
    val admission: neton.io.core.Admission? = null,
    /**
     * ⚖️ Time a request body may take once the server first has to wait for its bytes, to its end (hyper has none);
     * 0 disables. Expiry closes the read side and fails the body's read with [HttpError.Kind.Body] caused by a
     * [neton.io.core.TimeoutException] ([HttpError.isTimeout]); the service may still answer (e.g. 408), then the
     * connection closes. A body already buffered with its head never arms it. Needs [StreamCapability.ReadTimeout].
     */
    val bodyReadTimeoutMillis: Long = 0,
) {
    internal fun h1Config() = H1Config(
        parser = parser, maxHeaders = maxHeaders, maxHeaderSectionSize = maxHeaderSectionSize,
        maxRequestLineSize = maxRequestLineSize, lenientTeWithCl = lenientTeWithCl, titleCaseHeaders = titleCaseHeaders,
        preserveHeaderCase = preserveHeaderCase, maxRequestBodySize = maxRequestBodySize,
        bodyReadTimeoutMillis = bodyReadTimeoutMillis,
    )

    /** hyper `Builder::serve_connection`. */
    fun serveConnection(stream: IoStream, service: HttpService): Http1Connection = Http1Connection(stream, service, this)

    /** A copy with some options changed (the hyper builder's setters on a clone). */
    fun copy(
        halfClose: Boolean = this.halfClose,
        keepAlive: Boolean = this.keepAlive,
        titleCaseHeaders: Boolean = this.titleCaseHeaders,
        preserveHeaderCase: Boolean = this.preserveHeaderCase,
        maxHeaders: Int = this.maxHeaders,
        headerReadTimeoutMillis: Long = this.headerReadTimeoutMillis,
        keepAliveIdleTimeoutMillis: Long = this.keepAliveIdleTimeoutMillis,
        writev: Boolean? = this.writev,
        maxBufSize: Int = this.maxBufSize,
        autoDateHeader: Boolean = this.autoDateHeader,
        pipelineFlush: Boolean = this.pipelineFlush,
        parser: ParserConfig = this.parser,
        maxRequestLineSize: Int = this.maxRequestLineSize,
        maxHeaderSectionSize: Int = this.maxHeaderSectionSize,
        lenientTeWithCl: Boolean = this.lenientTeWithCl,
        maxRequestBodySize: Long = this.maxRequestBodySize,
        upgrades: Boolean = this.upgrades,
        admission: neton.io.core.Admission? = this.admission,
        bodyReadTimeoutMillis: Long = this.bodyReadTimeoutMillis,
    ) = Http1ServerConfig(
        halfClose, keepAlive, titleCaseHeaders, preserveHeaderCase, maxHeaders, headerReadTimeoutMillis,
        keepAliveIdleTimeoutMillis, writev, maxBufSize, autoDateHeader, pipelineFlush, parser, maxRequestLineSize,
        maxHeaderSectionSize, lenientTeWithCl, maxRequestBodySize, upgrades, admission, bodyReadTimeoutMillis,
    )
}

/** Serves HTTP/1 on [stream] until the connection ends (hyper `serve_connection(...).await`); see [Http1Connection.serve]. */
suspend fun serveHttp1(stream: IoStream, config: Http1ServerConfig = Http1ServerConfig(), service: HttpService) =
    config.serveConnection(stream, service).serve()

/**
 * One HTTP/1 server connection (hyper `server::conn::http1::Connection` and the server dispatcher, SPEC §3.10): one
 * coroutine reads a request head, calls the service, writes the response and its body, and decides on keep-alive —
 * one request at a time, pipelined bytes waiting in the read buffer.
 *
 * While a service or response body is in progress, the connection watches the read side like hyper: a client that
 * closes (with [Http1ServerConfig.halfClose] off) cancels the exchange and ends the connection with
 * [HttpError.Kind.IncompleteMessage].
 */
class Http1Connection internal constructor(
    private val stream: IoStream,
    private val service: HttpService,
    private val config: Http1ServerConfig,
    /** [Http1ServerConfig.upgrades], or the auto connection's choice (hyper-util calls `with_upgrades` or not). */
    private val upgrades: Boolean = config.upgrades,
    /** Bytes already read from [stream] (the auto connection's protocol detection), read before the stream. */
    replay: Bytes = Bytes.EMPTY,
) {
    private val io = H1Io(stream, config.maxBufSize)
    private val conn = H1Conn(io, isServer = true, config.h1Config())

    private lateinit var scope: CoroutineScope
    private lateinit var exchangeJob: CompletableJob
    private val serviceCall = InlineCall<ServiceCall, Response<out Body>>(ServiceCall::invoke)
    private val frameCall = InlineCall<Body, Frame?>(Body::nextFrame)
    private val call = ServiceCall(service)

    private var inFlight = false
    // True while the exchange waits for a suspended service call. A body finished before that (buffered with the head,
    // read by a service still running on this call) needs no watch: a service that then returns without suspending
    // leaves nothing to cancel, and one that suspends starts the watch at its suspension point in [exchange].
    private var serviceSuspended = false
    private var watch: Job? = null
    private var watchError: HttpError? = null
    private var pendingUpgrade: OnUpgrade? = null
    private var switched = false
    private var closing = false
    private var waitingForHead = false

    init {
        require(config.headerReadTimeoutMillis >= 0 && config.keepAliveIdleTimeoutMillis >= 0 && config.bodyReadTimeoutMillis >= 0)
        if (config.headerReadTimeoutMillis > 0 || config.keepAliveIdleTimeoutMillis > 0 || config.bodyReadTimeoutMillis > 0) {
            require(StreamCapability.ReadTimeout in stream.capabilities) {
                "header read / keep-alive idle / body read timeouts need a stream with ReadTimeout; set them to 0 for this stream"
            }
        }
        conn.allowHalfClose = config.halfClose
        conn.dateHeader = config.autoDateHeader
        if (!config.keepAlive) conn.disableKeepAlive()
        io.queueStrategy = config.writev ?: true
        io.flushPipeline = config.pipelineFlush
        conn.onBodyDone = { if (serviceSuspended) maybeStartWatch() }
        if (replay.size > 0) io.readBuf.writeBytes(replay)
    }

    /**
     * Serves requests until the connection ends. Returns normally on a clean end, including after an upgrade (then the
     * stream belongs to the [Upgraded] connection and is not closed); throws [HttpError] when the connection failed,
     * after sending the automatic response for a malformed request (400 / 414 / 431 / ⚖️ 413). The stream is closed
     * on return unless upgraded.
     */
    suspend fun serve() = coroutineScope {
        scope = this
        exchangeJob = Job(coroutineContext.job)
        val callContext = coroutineContext + exchangeJob
        serviceCall.context = callContext
        frameCall.context = callContext
        var upgraded = false
        try {
            loop()
            upgraded = finish()
        } catch (e: HttpError) {
            if (!closing) throw e
        } catch (e: ClosedException) {
            if (!closing) throw HttpError(HttpError.Kind.Io, e)
        } finally {
            watch?.cancel()
            exchangeJob.cancel()
            pendingUpgrade?.fail(HttpError(HttpError.Kind.Canceled))
            if (!upgraded) stream.close()
        }
    }

    /**
     * Starts a graceful shutdown (hyper `graceful_shutdown`): no more keep-alive; an idle connection closes now, a busy
     * one after the current exchange. Call it on the connection's reactor thread.
     */
    fun gracefulShutdown() {
        conn.disableKeepAlive()
        if (conn.isWriteClosed || conn.hasInitialReadWriteState) {
            closing = true
            conn.close()
            if (waitingForHead) stream.close()          // wakes the parked head read
        }
    }

    private suspend fun loop() {
        var first = true
        while (!closing) {
            watch?.let { w ->
                watch = null
                // hyper `is_done`: a connection that will not read another head ends now instead of waiting for the peer.
                if (!w.isCompleted && !conn.canReadHead) { w.cancel(); return }
                if (!w.isCompleted) awaitIdleRead(w)
                watchError?.let { throw it }
            }
            if (!conn.canReadHead) return
            val admission = config.admission
            if (admission != null) {
                waitingForHead = true
                try { conn.awaitHeadBytes(config.keepAliveIdleTimeoutMillis) } finally { waitingForHead = false }
                if (!io.readEof) {
                    if (!admission.tryAcquire()) {
                        conn.flushLocked()                   // pipelined responses go out before waiting for a permit
                        try { admission.acquire() } catch (e: neton.io.core.AdmissionTimeoutException) { conn.close(); throw HttpError(HttpError.Kind.Io, e) }
                    }
                    holdingPermit = true
                }
            }
            try {
                if (!serveOne(first)) return
            } finally {
                if (holdingPermit) { holdingPermit = false; admission!!.release() }
            }
            first = false
            if (switched) return
        }
    }

    private var holdingPermit = false

    /** Read one head and run its exchange; false when the connection ends instead. Inline: no suspending layer of its own. */
    @Suppress("NOTHING_TO_INLINE")
    private suspend inline fun serveOne(first: Boolean): Boolean {
        run {
            waitingForHead = true
            val parts = try {
                conn.readHead(config.keepAliveIdleTimeoutMillis, config.headerReadTimeoutMillis, first) as neton.http.RequestParts?
            } finally { waitingForHead = false }
            if (parts == null) return false
            val bodyLength = conn.headBodyLength
            if (config.maxRequestBodySize > 0 && bodyLength > config.maxRequestBodySize) {
                rejectBodyTooLarge()
                return false
            }
            val body = if (bodyLength == 0L) Incoming.EMPTY else Incoming(conn, conn.bodyGeneration, bodyLength)
            if (conn.headWantsUpgrade) {
                pendingUpgrade?.fail(HttpError(HttpError.Kind.Canceled))
                pendingUpgrade = OnUpgrade().also { parts.extensions.insert(it) }
            }
            exchange(Request(parts, body))
            if (conn.canReadBody) conn.drainOrCloseRead()
            conn.tryKeepAlive()
            return true
        }
    }

    /**
     * The idle wait for the next request when a read-side watch is already parked: bounded by the idle timeout, whose
     * expiry is [HttpError.Kind.HeaderTimeout] (hyper: the header read timer also covers the idle wait).
     */
    private suspend fun awaitIdleRead(w: Job) {
        val t = config.keepAliveIdleTimeoutMillis
        if (t <= 0) { w.join(); return }
        val done = withTimeoutOrNull(t) { w.join(); true }
        if (done == null) {
            w.cancel()
            conn.close()
            throw HttpError(HttpError.Kind.HeaderTimeout)
        }
    }

    /** ⚖️ A declared request body over [Http1ServerConfig.maxRequestBodySize]: 413 and close, without calling the service. */
    private suspend fun rejectBodyTooLarge() {
        conn.closeRead()
        conn.disableKeepAlive()
        conn.writeHead(neton.http.ResponseParts(status = StatusCode.PAYLOAD_TOO_LARGE), null)
        conn.flush()
        conn.error = HttpError(HttpError.Kind.UserBodyTooLarge)
    }

    private class ServiceCall(val service: HttpService) {
        lateinit var request: Request<Incoming>
        suspend fun invoke(): Response<out Body> = service.call(request)
    }

    /** One request: the service, then the response head and body (hyper `poll_write`). */
    @Suppress("UNCHECKED_CAST")
    private suspend fun exchange(request: Request<Incoming>) {
        inFlight = true
        try {
            call.request = request
            val response = try {
                val r = serviceCall.start(call)
                if (r === COROUTINE_SUSPENDED) {
                    serviceSuspended = true
                    maybeStartWatch()
                    try { serviceCall.await() } finally { serviceSuspended = false }
                } else r as Response<out Body>
            } catch (e: CancellationException) {
                throw watchError ?: e
            } catch (e: Throwable) {
                // The body's own limit error, however the service passed it on: 413 while no head is out yet.
                if (conn.canWriteHead && e.isBodyTooLarge()) { rejectBodyTooLarge(); throw conn.error!! }
                throw if (e is HttpError) e else HttpError(HttpError.Kind.UserService, e)
            }
            writeResponse(response)
        } finally {
            inFlight = false
        }
    }

    private fun Throwable.isBodyTooLarge(): Boolean {
        var c: Throwable? = this
        while (c != null) { if (c is HttpError && c.kind == HttpError.Kind.UserBodyTooLarge) return true; c = c.cause }
        return false
    }

    // Inline into [exchange]: one continuation less per request.
    @Suppress("NOTHING_TO_INLINE")
    private suspend inline fun writeResponse(response: Response<out Body>) {
        val body: Body = response.body
        val bodyLen: Long? = if (body.isEndStream) null else body.exactLength.let { if (it < 0) OutgoingBody.UNKNOWN else it }
        val status = response.status
        switched = upgrades && pendingUpgrade != null &&
            (status.asU16() == 101 || conn.method === Method.CONNECT && status.isSuccess())
        io.writeLock.withLock { conn.writeHead(response.parts, bodyLen) }
        conn.error?.let { e ->
            if (conn.isWriteClosed) { flushLocked(); throw e }
        }
        if (bodyLen != null) {
            try {
                conn.pumpBody(body, frameCall, onPending)
            } catch (e: CancellationException) {
                throw watchError ?: e
            }
        } else {
            // hyper `poll_write`: an empty body still ends a body the head started (a user `transfer-encoding: chunked`).
            if (conn.canWriteBody) io.writeLock.withLock { conn.endBody() }
            flushLocked()
        }
    }

    private suspend fun flushLocked() = conn.flushLocked()

    private val onPending: () -> Unit = { maybeStartWatch() }

    /**
     * hyper `mid_message_detect_eof`: while an exchange is in progress and the request has been read, read ahead to
     * notice a client closing; pipelined bytes simply stay in the read buffer for the next request.
     */
    private fun maybeStartWatch() {
        if (!inFlight || watch != null || conn.allowHalfClose || io.readEof || io.readBuf.readableBytes > 0) return
        if (conn.reading != H1Conn.Reading.KEEP_ALIVE) return
        // ⚖️ Not for a request asking for an upgrade: a read parked when the connection is handed over would race the
        // new owner (hyper polls, so it has no parked read). Such a client closing is noticed when the response is written.
        if (conn.headWantsUpgrade) return
        watch = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                if (io.readFromIo() == 0 && inFlight) {
                    watchError = HttpError(HttpError.Kind.IncompleteMessage)
                    conn.closeRead()
                    exchangeJob.cancel()
                }
            } catch (e: IoException) {
                if (e is ClosedException && closing) return@launch
                watchError = HttpError(HttpError.Kind.Io, e)
                conn.close()
                if (inFlight) exchangeJob.cancel()
            }
        }
    }

    /**
     * After an automatic error response (400 / 413 / 414 / 431): shut the write side (hyper does this before returning
     * the error), then ⚖️ drain what the client still sends until it closes, for at most [LINGER_MILLIS]. Closing with
     * unread input makes the kernel send a reset, which can destroy the response before the client reads it (seen with
     * io_uring on hyper's `max_buf_size` test). Streams without half-close or read timeouts are simply closed.
     */
    private suspend fun lingerClose() {
        val caps = stream.capabilities
        if (StreamCapability.HalfClose in caps && StreamCapability.ReadTimeout in caps) stream.closeGracefully(LINGER_MILLIS)
        else runCatching { if (StreamCapability.HalfClose in caps) stream.shutdownOutput() }
    }

    /** The end of the connection (hyper `poll_inner` when done): an upgrade, or flush and shut the write side. */
    private suspend fun finish(): Boolean {
        val pending = pendingUpgrade
        conn.error?.let { e ->
            runCatching { flushLocked() }
            lingerClose()
            throw e
        }
        if (pending != null) {
            pendingUpgrade = null
            when {
                !upgrades -> pending.fail(HttpError(HttpError.Kind.UserManualUpgrade))
                !switched -> pending.fail(HttpError(HttpError.Kind.UserNoUpgrade))
                else -> {
                    flushLocked()
                    val rest = if (io.readBuf.readableBytes > 0) io.readBuf.readSlice(io.readBuf.readableBytes) else Bytes.EMPTY
                    pending.fulfill(Upgraded(stream, rest))
                    return true
                }
            }
        }
        try {
            flushLocked()
            if (StreamCapability.HalfClose in stream.capabilities) stream.shutdownOutput()
        } catch (e: IoException) {
            throw HttpError(HttpError.Kind.Shutdown, e)
        }
        return false
    }

    private companion object {
        const val LINGER_MILLIS = 1_000L
    }
}
