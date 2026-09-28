package neton.http.h1

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
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

    private val frameCall = InlineCall<Body, Frame?> { nextFrame() }
    private val noop: () -> Unit = {}

    private class Pending(val request: Request<out Body>, val response: CompletableDeferred<Response<Incoming>>)

    private var pending: Pending? = null
    private var wake: CompletableDeferred<Unit>? = null
    private var wanting = false
    private var bufferedOnce = false
    private var readyWaiters: CompletableDeferred<Unit>? = null
    private var closedError: HttpError? = null
    private var closed = false
    private var bodyDone: CompletableDeferred<Unit>? = null

    init {
        io.queueStrategy = config.writev ?: true
        conn.onBodyDone = { bodyDone?.complete(Unit) }
    }

    internal val isReady: Boolean get() = !closed && wanting && pending == null
    internal val isClosed: Boolean get() = closed

    internal suspend fun ready() {
        while (true) {
            if (closed) throw closedError ?: HttpError(HttpError.Kind.ChannelClosed)
            if (isReady) return
            (readyWaiters ?: CompletableDeferred<Unit>().also { readyWaiters = it }).await()
        }
    }

    internal suspend fun send(request: Request<out Body>): Response<Incoming> {
        if (closed || pending != null || !(wanting || !bufferedOnce)) {
            throw HttpError(HttpError.Kind.Canceled, IllegalStateException("connection was not ready"))
        }
        bufferedOnce = true
        val p = Pending(request, CompletableDeferred())
        pending = p
        wanting = false
        wake?.complete(Unit)
        return p.response.await()
    }

    /**
     * Runs the connection until it closes (hyper polling `Connection`). Returns normally on a clean close or after an
     * upgrade (the stream then belongs to the [Upgraded] connection); throws [HttpError] when it failed. The stream is
     * closed on return unless upgraded.
     */
    suspend fun run() = coroutineScope {
        frameCall.context = coroutineContext
        var upgraded = false
        var failure: Throwable? = null
        try {
            upgraded = loop()
        } catch (e: Throwable) {
            failure = e
            throw if (e is IoException) HttpError(HttpError.Kind.Io, e) else e
        } finally {
            closed = true
            closedError = (failure as? HttpError) ?: HttpError(HttpError.Kind.ChannelClosed)
            pending?.response?.completeExceptionally(
                if (failure is HttpError) failure else HttpError(HttpError.Kind.Canceled, failure),
            )
            pending = null
            readyWaiters?.complete(Unit)
            if (!upgraded) stream.close()
        }
    }

    private suspend fun kotlinx.coroutines.CoroutineScope.loop(): Boolean {
        while (true) {
            // Idle: wait for a request while watching the read side (hyper `require_empty_read`).
            val p = awaitRequest() ?: return false
            if (!exchange(p)) return true
            conn.tryKeepAlive()
            if (!conn.isIdle) {
                if (conn.isReadClosed || conn.isWriteClosed) return false
            }
        }
    }

    private var idleRead: Job? = null
    private var idleReadResult = 0
    private var idleReadError: Throwable? = null

    private suspend fun kotlinx.coroutines.CoroutineScope.awaitRequest(): Pending? {
        while (true) {
            pending?.let { return it }
            if (io.readBuf.readableBytes > 0) throw HttpError(HttpError.Kind.UnexpectedMessage)
            if (idleRead == null && !io.readEof) {
                idleReadError = null
                idleRead = launch(start = CoroutineStart.UNDISPATCHED) {
                    try { idleReadResult = io.readFromIo() } catch (e: IoException) { idleReadError = e }
                    wake?.complete(Unit)
                }
            }
            val w = CompletableDeferred<Unit>()
            wake = w
            wanting = true
            readyWaiters?.let { readyWaiters = null; it.complete(Unit) }
            if (pending == null && idleRead?.isCompleted != true) w.await()
            wake = null
            pending?.let { return it }
            val r = idleRead
            if (r != null && r.isCompleted) {
                idleRead = null
                idleReadError?.let { throw HttpError(HttpError.Kind.Io, it) }
                if (idleReadResult == 0) return null                       // the server closed an idle connection
                throw HttpError(HttpError.Kind.UnexpectedMessage)
            }
        }
    }

    /** One request / response exchange; false when the connection was upgraded. */
    private suspend fun kotlinx.coroutines.CoroutineScope.exchange(p: Pending): Boolean {
        wanting = false
        val request = p.request
        val body: Body = request.body
        val bodyLen: Long? = if (body.isEndStream) null else body.sizeHint.exact ?: OutgoingBody.UNKNOWN
        val onInformational = request.extensions.get<OnInformational>()
        io.writeLock.withLock { conn.writeHead(request.parts, bodyLen) }
        conn.error?.let { e -> conn.error = null; p.response.completeExceptionally(e); pending = null; return true }
        val writer = if (conn.canWriteBody) launch(start = CoroutineStart.UNDISPATCHED) { conn.pumpBody(body, frameCall, noop) } else {
            io.writeLock.withLock { conn.flush() }
            null
        }
        // The response: the parked idle read (if any) is the first read of its head.
        idleRead?.let { r -> r.join(); idleRead = null; idleReadError?.let { throw HttpError(HttpError.Kind.Io, it) } }
        val head = try {
            conn.readHead(0, 0, false) as H1Conn.ResponseHead?
        } catch (e: HttpError) {
            writer?.cancel()
            pending = null
            p.response.completeExceptionally(e)
            throw e
        }
        if (head == null) {
            val e = conn.error ?: HttpError(HttpError.Kind.IncompleteMessage)
            pending = null
            p.response.completeExceptionally(e)
            throw e
        }
        if (onInformational != null) for (info in head.informational) onInformational.callback(info)
        val upgrade = if (head.wantsUpgrade) OnUpgrade().also { head.parts.extensions.insert(it) } else null
        val incoming = if (head.bodyLength == 0L) Incoming.EMPTY else Incoming(conn, conn.bodyGeneration, head.bodyLength)
        val done = if (conn.canReadBody) CompletableDeferred<Unit>().also { bodyDone = it } else null
        pending = null
        p.response.complete(Response(head.parts, incoming))
        done?.await()
        bodyDone = null
        writer?.join()
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
