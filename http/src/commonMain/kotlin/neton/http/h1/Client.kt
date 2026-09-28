package neton.http.h1

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import neton.http.Body
import neton.http.Frame
import neton.http.HttpError
import neton.http.Incoming
import neton.http.OnUpgrade
import neton.http.Request
import neton.http.Response
import neton.http.ResponseParts
import neton.http.Upgraded
import neton.http.h1.parse.ParserConfig
import neton.io.bytes.Bytes
import neton.io.core.IoException
import neton.io.core.IoStream

/** HTTP/1 client connection options (hyper `client::conn::http1::Builder`). */
class Http1ClientConfig(
    /** Accept an HTTP/0.9 response to the first request. */
    val h09Responses: Boolean = false,
    val parser: ParserConfig = ParserConfig.DEFAULT,
    /** Vectored writes (queue strategy) or copying into one buffer; null: vectored. */
    val writev: Boolean? = null,
    val titleCaseHeaders: Boolean = false,
    val preserveHeaderCase: Boolean = false,
    val maxHeaders: Int = 100,
    val maxBufSize: Int = H1Io.DEFAULT_MAX_BUFFER_SIZE,
    /** ⚖️ Response head size limit (beyond → error); hyper bounds it only by [maxBufSize]. */
    val maxHeaderSectionSize: Int = 64 * 1024,
    /** Hand the connection over after a `101` / CONNECT `2xx` response (hyper `Connection::with_upgrades`). */
    val upgrades: Boolean = false,
) {
    internal fun h1Config() = H1Config(
        parser = parser, maxHeaders = maxHeaders, maxHeaderSectionSize = maxHeaderSectionSize,
        titleCaseHeaders = titleCaseHeaders, preserveHeaderCase = preserveHeaderCase, h09Responses = h09Responses,
    )

    /** hyper `Builder::handshake`: the request sender and the connection, which must be run ([Http1ClientConnection.run]). */
    fun handshake(stream: IoStream): Pair<SendRequest, Http1ClientConnection> {
        val c = Http1ClientConnection(stream, this)
        return SendRequest(c) to c
    }
}

/** hyper `client::conn::http1::handshake` with the default options. */
fun http1Handshake(stream: IoStream): Pair<SendRequest, Http1ClientConnection> = Http1ClientConfig().handshake(stream)

/**
 * Called with each informational (1xx) response before the final one (hyper `ext::on_informational`); put it in the
 * request's extensions.
 */
class OnInformational(val callback: (ResponseParts) -> Unit)

/**
 * Sends requests on an HTTP/1 client connection (hyper `SendRequest`): one at a time. The response body must be read
 * to its end (or the connection closed) before the connection can carry the next request.
 */
class SendRequest internal constructor(private val c: Http1ClientConnection) {
    /** Waits until the connection can take a request (hyper `ready`); throws when it is closed. */
    suspend fun ready() = c.ready()

    val isReady: Boolean get() = c.isReady
    val isClosed: Boolean get() = c.isClosed

    /**
     * Sends [request] and returns the response once its head arrived (hyper `send_request`). A connection that is not
     * ready fails with [HttpError.Kind.Canceled] ("connection was not ready"); the first request after the handshake
     * may be sent before the connection runs.
     */
    suspend fun sendRequest(request: Request<out Body>): Response<Incoming> = c.send(request)
}

/**
 * One HTTP/1 client connection (hyper `client::conn::http1::Connection` and the client dispatcher): [run] it in its own
 * coroutine; it writes the requests given to [SendRequest] — the body from a child coroutine, in parallel with
 * reading the response (hyper lets a server answer early) — and reads the responses.
 */
class Http1ClientConnection internal constructor(private val stream: IoStream, private val config: Http1ClientConfig) {
    private val io = H1Io(stream, config.maxBufSize)
    private val conn = H1Conn(io, isServer = false, config.h1Config())

    private val frameCall = InlineCall<Body, Frame?>(Body::nextFrame)
    private val noop: () -> Unit = {}

    // hyper's client dispatcher, arranged for Kotlin/Native: [run] is the connection's only reader. While idle it waits
    // in the read itself (hyper `require_empty_read`); a request's caller writes the head (the write lock orders
    // writers), so a request needs no wake-up of the connection, and the bytes that end the idle read are its response.
    // The response and the end of its body are handed over by reusable signals: a CompletableDeferred, a launched
    // coroutine or a cancellable suspension per step cost more than the exchange itself. Everything runs on the
    // connection's reactor thread; one caller at a time (hyper's `SendRequest` is `&mut`).
    private class Pending(val request: Request<out Body>, val onInformational: OnInformational?)

    private var pending: Pending? = null
    private val response = Slot<Response<Incoming>>()
    private var idle = false
    private var bufferedOnce = false
    private val readySignal = Signal()
    private var closedError: HttpError? = null
    private var closed = false
    private val bodyDone = Signal()
    private var awaitingBody = false
    private var scope: kotlinx.coroutines.CoroutineScope? = null
    private var writer: kotlinx.coroutines.Job? = null
    private var deferredBody: Body? = null
    private var sendFailure: Throwable? = null

    init {
        io.queueStrategy = config.writev ?: true
        conn.onBodyDone = { if (awaitingBody) bodyDone.raise() }
    }

    internal val isReady: Boolean get() = !closed && idle && pending == null
    internal val isClosed: Boolean get() = closed

    internal suspend fun ready() {
        while (true) {
            if (closed) throw closedError ?: HttpError(HttpError.Kind.ChannelClosed)
            if (isReady) return
            readySignal.await()
        }
    }

    internal suspend fun send(request: Request<out Body>): Response<Incoming> {
        if (closed || pending != null || !(idle || !bufferedOnce)) {
            throw HttpError(HttpError.Kind.Canceled, IllegalStateException("connection was not ready"))
        }
        bufferedOnce = true
        idle = false
        response.reset()
        val body: Body = request.body
        val bodyLen: Long? = if (body.isEndStream) null else body.exactLength.let { if (it < 0) OutgoingBody.UNKNOWN else it }
        pending = Pending(request, request.extensions.get<OnInformational>())
        io.writeLock.withLock { conn.writeHead(request.parts, bodyLen) }
        conn.error?.let { e ->
            // Nothing was written: the connection stays usable (its read is still waiting).
            conn.error = null
            pending = null
            if (!closed) idle = true
            readySignal.raise()
            throw e
        }
        if (conn.canWriteBody) {
            // The body from a child of the connection, in parallel with reading the response (hyper lets a server
            // answer early); a body sent before the connection runs starts with it.
            val sc = scope
            if (sc != null) writer = sc.launch(start = CoroutineStart.UNDISPATCHED) { writeBody(body) } else deferredBody = body
        } else {
            try {
                io.writeLock.withLock { conn.flush() }
            } catch (e: HttpError) {
                // The connection fails with the write error; its parked read is woken by closing the stream.
                pending = null
                sendFailure = e
                stream.close()
                throw e
            }
        }
        return response.await()
    }

    /**
     * Runs the connection until it closes (hyper polling `Connection`). Returns normally on a clean close or after an
     * upgrade (the stream then belongs to the [Upgraded] connection); throws [HttpError] when it failed. The stream is
     * closed on return unless upgraded.
     */
    suspend fun run() = coroutineScope {
        frameCall.context = coroutineContext
        scope = this
        var upgraded = false
        var failure: Throwable? = null
        try {
            deferredBody?.let { b -> deferredBody = null; writer = launch(start = CoroutineStart.UNDISPATCHED) { writeBody(b) } }
            upgraded = loop()
        } catch (e: Throwable) {
            failure = sendFailure ?: e
            val f = failure
            throw if (f is IoException) HttpError(HttpError.Kind.Io, f) else f
        } finally {
            scope = null
            closed = true
            idle = false
            // hyper `SenderDropGuard`: a response body still being read ends with IncompleteMessage, not a clean end.
            if (conn.canReadBody) conn.closeRead()
            closedError = (failure as? HttpError) ?: HttpError(HttpError.Kind.ChannelClosed)
            if (pending != null) response.fail(if (failure is HttpError) failure else HttpError(HttpError.Kind.Canceled, failure))
            pending = null
            readySignal.raise()
            if (!upgraded) stream.close()
        }
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.loop(): Boolean {
        while (true) {
            sendFailure?.let { throw it }
            if (pending == null) {
                // Idle: take a request while watching the read side (hyper `require_empty_read`).
                if (io.readBuf.readableBytes > 0) throw HttpError(HttpError.Kind.UnexpectedMessage)
                idle = true
                readySignal.raise()
                if (!io.readEof) {
                    try {
                        io.readFromIo()
                    } catch (e: IoException) {
                        sendFailure?.let { throw it }
                        throw HttpError(HttpError.Kind.Io, e)
                    }
                }
                sendFailure?.let { throw it }
                if (pending == null) {
                    idle = false
                    if (io.readBuf.readableBytes > 0) throw HttpError(HttpError.Kind.UnexpectedMessage)
                    return false                                       // the server closed an idle connection
                }
                // A request was sent while waiting: what was read is the start of its response.
            }
            idle = false
            if (!exchange(pending!!)) return true
            conn.tryKeepAlive()
            if (!conn.isIdle) {
                if (conn.isReadClosed || conn.isWriteClosed) return false
            }
        }
    }

    /**
     * The request body (from the writer coroutine). A failure (the body's or the write's) is the pending request's
     * error, as hyper's dispatcher hands a write error to the request's callback before the connection ends.
     */
    private suspend fun writeBody(body: Body) {
        try {
            conn.pumpBody(body, frameCall, noop)
        } catch (e: HttpError) {
            if (pending != null) { pending = null; response.fail(e) }
            throw e
        }
    }

    /** Reads the response to the request in flight (its head was written by [send]); false when upgraded. */
    private suspend fun exchange(p: Pending): Boolean {
        val w = writer
        val head = try {
            conn.readHead(0, 0, false) as H1Conn.ResponseHead?
        } catch (e: HttpError) {
            w?.cancel()
            pending = null
            response.fail(e)
            throw e
        }
        if (head == null) {
            val e = conn.error ?: HttpError(HttpError.Kind.IncompleteMessage)
            pending = null
            response.fail(e)
            throw e
        }
        val onInformational = p.onInformational
        if (onInformational != null) for (info in head.informational) onInformational.callback(info)
        val upgrade = if (head.wantsUpgrade) OnUpgrade().also { head.parts.extensions.insert(it) } else null
        val incoming = if (head.bodyLength == 0L) Incoming.EMPTY else Incoming(conn, conn.bodyGeneration, head.bodyLength)
        val waitBody = conn.canReadBody
        if (waitBody) { bodyDone.clear(); awaitingBody = true }
        pending = null
        response.complete(Response(head.parts, incoming))
        if (waitBody) {
            try { bodyDone.await() } finally { awaitingBody = false }
        }
        if (w != null) { w.join(); writer = null }
        if (upgrade != null) {
            if (!config.upgrades) {
                upgrade.fail(HttpError(HttpError.Kind.UserManualUpgrade))
                return true
            }
            val rest = if (io.readBuf.readableBytes > 0) io.readBuf.readSlice(io.readBuf.readableBytes) else Bytes.EMPTY
            upgrade.fulfill(Upgraded(stream, rest))
            return false
        }
        return true
    }

}
