package neton.http.h1

import neton.http.Frame
import neton.http.HttpError
import neton.http.Method
import neton.http.RequestParts
import neton.http.ResponseParts
import neton.http.Version
import neton.http.h1.parse.ParseStatus
import neton.http.header.HeaderName
import neton.http.header.HeaderValue
import neton.io.bytes.Bytes
import neton.io.core.IoException
import neton.io.core.TimeoutException

/**
 * The state of one HTTP/1 connection (hyper `proto/h1/conn.rs` `Conn` + `State`, SPEC §3.10): the read and write
 * sides, keep-alive, and the operations the server and client dispatchers drive, as suspending functions instead of
 * polls. The rules (state transitions, keep-alive, version enforcement, `100 Continue`, errors) are hyper's.
 */
internal class H1Conn(val io: H1Io, val isServer: Boolean, val config: H1Config) : neton.http.Incoming.Source {
    var reading = Reading.INIT; private set
    var writing = Writing.INIT; private set
    var keepAlive = KA.BUSY; private set
    private var decoder: BodyDecoder? = null
    private var encoder: BodyEncoder? = null
    // One of each per connection, reset per message (hyper's are values; here they would be objects per message).
    private val plan = EncodePlan()
    private val reusableEncoder = BodyEncoder.length(0)
    private val reusableDecoder = BodyDecoder.length(0)

    /** The request method (hyper `state.method`): parsed by the server, sent by the client. */
    var method: Method? = null
    var version: Version = Version.HTTP_11; private set
    var allowTrailerFields = false; private set
    var allowHalfClose = false
    var dateHeader = true
    var h09Responses = config.h09Responses

    /** An error kept to be reported when the connection ends (hyper `state.error`). */
    var error: HttpError? = null

    /** Identifies the body being read: an old request's body cannot read the next one's. */
    var bodyGeneration = 0; private set
    /** Remaining length for a length-delimited body's size hint, -1 when unknown. */
    var bodyRemaining = -1L; private set
    private var bodyRead = 0L

    private val serverParser = if (isServer) ServerHeadParser(config) else null
    private val clientParser = if (isServer) null else ClientHeadParser(config)
    private var partialLen = -1

    enum class Reading { INIT, CONTINUE, BODY, KEEP_ALIVE, CLOSED }
    enum class Writing { INIT, BODY, KEEP_ALIVE, CLOSED }
    enum class KA { IDLE, BUSY, DISABLED }

    // ---- reading ---------------------------------------------------------------------------------------------

    /** hyper `can_read_head`. */
    val canReadHead: Boolean
        get() = reading == Reading.INIT && (isServer || writing != Writing.INIT)

    /** hyper `can_read_body`. */
    val canReadBody: Boolean get() = reading == Reading.BODY || reading == Reading.CONTINUE

    val isReadClosed: Boolean get() = reading == Reading.CLOSED
    val isWriteClosed: Boolean get() = writing == Writing.CLOSED

    /** hyper `has_initial_read_write_state`. */
    val hasInitialReadWriteState: Boolean
        get() = reading == Reading.INIT && writing == Writing.INIT && io.readBuf.readableBytes == 0

    // The last request head's facts (the server's [readHead] returns only its parts).
    var headBodyLength = 0L; private set
    var headWantsUpgrade = false; private set

    /** Result of [readHead] for the client. */
    class ResponseHead(val parts: ResponseParts, val bodyLength: Long, val wantsUpgrade: Boolean, val informational: List<ResponseParts>)

    /**
     * Reads and parses the next head (hyper `poll_read_head` + `Buffered::parse`). Returns the head; or null when
     * there is none: the peer closed an idle connection, or a parse error was answered with an automatic response
     * already buffered and [error] set (hyper `on_parse_error`). Throws [HttpError] for the other errors.
     *
     * Timeouts (⚖️ SPEC §3.7): [idleTimeoutMillis] while no byte of the head has arrived (0: none) — expiring is a
     * quiet close; [headerTimeoutMillis] from the first byte (or from the start, for the first request) to the end
     * of the head — expiring is [HttpError.Kind.HeaderTimeout].
     */
    suspend fun readHead(idleTimeoutMillis: Long, headerTimeoutMillis: Long, firstRequest: Boolean): Any? {
        check(canReadHead)
        val timed = idleTimeoutMillis > 0 || headerTimeoutMillis > 0
        var deadline = 0L
        if (timed && (firstRequest || io.readBuf.readableBytes > 0) && headerTimeoutMillis > 0) {
            deadline = neton.io.core.monotonicNanos() / 1_000_000 + headerTimeoutMillis
        }
        while (true) {
            val parsed = tryParse()
            if (parsed != null) {
                if (timed) io.stream.setReadTimeout(0)
                return parsed
            }
            if (error != null) return null
            val len = io.readBuf.readableBytes
            if (len >= io.maxBufSize) return onReadHeadError(HttpError(HttpError.Kind.ParseTooLarge))
            partialLen = if (len > 0) len else -1
            if (timed) {
                if (len > 0 && deadline == 0L && headerTimeoutMillis > 0) deadline = neton.io.core.monotonicNanos() / 1_000_000 + headerTimeoutMillis
                val wait = if (deadline > 0) (deadline - neton.io.core.monotonicNanos() / 1_000_000).coerceAtLeast(1) else idleTimeoutMillis
                io.stream.setReadTimeout(wait)
            }
            val n = try {
                io.readFromIo()
            } catch (e: TimeoutException) {
                if (deadline == 0L) { closeRead(); closeWrite(); return null }        // idle: a quiet close
                closeRead()
                throw HttpError(HttpError.Kind.HeaderTimeout)
            } catch (e: IoException) {
                close()
                throw HttpError(HttpError.Kind.Io, e)
            }
            if (n == 0) return onReadHeadError(HttpError(HttpError.Kind.IncompleteMessage))
        }
    }

    /** One parse attempt over the read buffer; null when the head is incomplete (or failed, see [error]). */
    private fun tryParse(): Any? {
        val buf = io.readBuf
        val len = buf.readableBytes
        if (len == 0) return null
        val arr = buf.backingArray()
        val off = buf.readerIndex()
        if (partialLen >= 0 && !isCompleteFast(arr, off, len, partialLen)) return null
        return if (isServer) parseRequest(arr, off, len) else parseResponse(arr, off, len)
    }

    private fun parseRequest(arr: ByteArray, off: Int, len: Int): Any? {
        val p = serverParser!!
        val n = p.parse(arr, off, len)
        if (n == ParseStatus.PARTIAL) return null
        if (n < 0) { onReadHeadErrorSync(parseError(p.error!!)); return null }
        io.readBuf.skip(n)
        partialLen = -1
        val parts = p.parts!!
        method = parts.method
        afterHead(p.keepAlive, parts.version, p.bodyLength, p.expectContinue)
        allowTrailerFields = H1Headers.teIsTrailers(parts.headers)
        headBodyLength = p.bodyLength
        headWantsUpgrade = p.wantsUpgrade
        return parts
    }

    private fun parseResponse(arr: ByteArray, off: Int, len: Int): Any? {
        val p = clientParser!!
        val n = p.parse(arr, off, len, method, h09Responses)
        if (n == ParseStatus.PARTIAL) {
            return null
        }
        if (n < 0) { onReadHeadErrorSync(parseError(p.error!!)); return null }
        io.readBuf.skip(n)
        partialLen = -1
        h09Responses = false
        val parts = p.parts!!
        afterHead(p.keepAlive, parts.version, p.bodyLength, false)
        allowTrailerFields = H1Headers.teIsTrailers(parts.headers)
        return ResponseHead(parts, p.bodyLength, p.wantsUpgrade, ArrayList(p.informational))
    }

    /** The rest of hyper `poll_read_head` after a successful parse. */
    private fun afterHead(msgKeepAlive: Boolean, msgVersion: Version, length: Long, expectContinue: Boolean) {
        busy()
        if (!msgKeepAlive) keepAlive = KA.DISABLED
        version = msgVersion
        bodyGeneration++
        bodyRead = 0
        bodyRemaining = if (length >= 0) length else -1
        if (length == 0L) {
            reading = Reading.KEEP_ALIVE
            decoder = null
            if (!isServer) tryKeepAlive()
        } else {
            decoder = reusableDecoder.also { it.reset(length, config.maxHeaders) }
            reading = if (expectContinue && msgVersion != Version.HTTP_10 && msgVersion != Version.HTTP_09) Reading.CONTINUE else Reading.BODY
        }
    }

    private fun parseError(e: H1ParseError): HttpError = HttpError(
        when (e) {
            H1ParseError.Method -> HttpError.Kind.ParseMethod
            H1ParseError.Version -> HttpError.Kind.ParseVersion
            H1ParseError.VersionH2 -> HttpError.Kind.ParseVersionH2
            H1ParseError.Uri -> HttpError.Kind.ParseUri
            H1ParseError.UriTooLong -> HttpError.Kind.ParseUriTooLong
            H1ParseError.HeaderToken -> HttpError.Kind.ParseHeaderToken
            H1ParseError.ContentLengthInvalid -> HttpError.Kind.ParseHeaderContentLengthInvalid
            H1ParseError.TransferEncodingInvalid -> HttpError.Kind.ParseHeaderTransferEncodingInvalid
            H1ParseError.TransferEncodingUnexpected -> HttpError.Kind.ParseHeaderTransferEncodingUnexpected
            H1ParseError.TransferEncodingWithContentLength -> HttpError.Kind.ParseHeaderTransferEncodingWithContentLength
            H1ParseError.TooLarge -> HttpError.Kind.ParseTooLarge
            H1ParseError.Status -> HttpError.Kind.ParseStatus
            H1ParseError.Internal -> HttpError.Kind.ParseInternal
        },
    )

    private fun onReadHeadError(e: HttpError): Any? {
        onReadHeadErrorSync(e)
        return null
    }

    /**
     * hyper `on_read_head_error`: a parse error, or an EOF in the middle of a head (or, for the client, while a
     * response is expected), is an error — answered, on the server, by an automatic response when one applies; an
     * EOF on an idle connection is a clean close.
     */
    private fun onReadHeadErrorSync(e: HttpError) {
        val mustError = !isServer && keepAlive != KA.IDLE          // should_error_on_eof: the client expects a response
        closeRead()
        io.consumeLeadingLines()
        val wasMidParse = e.isParse() || io.readBuf.readableBytes > 0
        if (wasMidParse || mustError) {
            onParseError(e)
        } else {
            closeWrite()
        }
    }

    /** hyper `on_parse_error`: answer with an automatic response where the role has one, else fail. */
    private fun onParseError(e: HttpError) {
        if (writing == Writing.INIT) {
            if (hasH2Prefix()) throw HttpError(HttpError.Kind.ParseVersionH2)
            val status = if (isServer) autoStatus(e.kind) else 0
            if (status != 0) {
                writeHead(ResponseParts(status = neton.http.StatusCode.fromU16(status)), null)
                error = e
                return
            }
        }
        throw e
    }

    /** hyper `Server::on_error`. */
    private fun autoStatus(kind: HttpError.Kind): Int = when (kind) {
        HttpError.Kind.ParseMethod, HttpError.Kind.ParseHeaderToken, HttpError.Kind.ParseHeaderContentLengthInvalid,
        HttpError.Kind.ParseHeaderTransferEncodingInvalid, HttpError.Kind.ParseHeaderTransferEncodingUnexpected,
        HttpError.Kind.ParseHeaderTransferEncodingWithContentLength, HttpError.Kind.ParseUri, HttpError.Kind.ParseVersion -> 400
        HttpError.Kind.ParseTooLarge -> 431
        HttpError.Kind.ParseUriTooLong -> 414
        else -> 0
    }

    private fun hasH2Prefix(): Boolean {
        val b = io.readBuf
        if (b.readableBytes < 24) return false
        for (i in 0 until 24) if (b.getByte(i) != H2_PREFACE[i]) return false
        return true
    }

    /**
     * The next frame of the body being read (hyper `poll_read_body`): data, trailers, or null at its end. Throws
     * [HttpError] ([HttpError.Kind.Body]) when the body is invalid or the connection fails. [generation] is the
     * body's [bodyGeneration]: a body that is no longer the current one has ended.
     */
    override suspend fun readBodyFrame(generation: Int): Frame? {
        if (generation != bodyGeneration) return null
        while (true) {
            when (reading) {
                Reading.CONTINUE -> {
                    if (writing == Writing.INIT) sendContinue()
                    if (generation != bodyGeneration) return null
                    reading = Reading.BODY
                }
                Reading.BODY -> {}
                Reading.CLOSED -> throw bodyClosedError()
                else -> return null
            }
            val d = decoder!!
            when (d.decode(io.readBuf, io.readEof)) {
                DecodeResult.DATA -> {
                    val data = d.data
                    bodyRead += data.size
                    if (bodyRemaining > 0) bodyRemaining -= data.size
                    if (config.maxRequestBodySize > 0 && isServer && bodyRead > config.maxRequestBodySize) {
                        closeRead()
                        bodyError = HttpError(HttpError.Kind.UserBodyTooLarge)
                        throw bodyError!!
                    }
                    if (d.isEof) { reading = Reading.KEEP_ALIVE; tryKeepAlive(); onBodyDone() }
                    return Frame.Data(data)
                }
                DecodeResult.TRAILERS -> {
                    reading = Reading.KEEP_ALIVE; tryKeepAlive(); onBodyDone()
                    return Frame.Trailers(d.trailers!!)
                }
                DecodeResult.END -> {
                    reading = Reading.KEEP_ALIVE; tryKeepAlive(); onBodyDone()
                    return null
                }
                DecodeResult.ERROR -> {
                    closeRead()
                    bodyError = HttpError(HttpError.Kind.Body, neton.http.h1.BodyDecodeException(d.error!!))
                    throw bodyError!!
                }
                DecodeResult.NEED_MORE -> {
                    val n = try {
                        io.readFromIo()
                    } catch (e: IoException) {
                        close()
                        bodyError = HttpError(HttpError.Kind.Body, e)
                        throw bodyError!!
                    }
                    if (generation != bodyGeneration) return null
                }
            }
        }
    }

    override fun remaining(generation: Int): Long = if (generation == bodyGeneration) bodyRemaining else 0

    override fun ended(generation: Int): Boolean = generation != bodyGeneration || !canReadBody

    /** Called when the body being read has ended (the server starts watching for EOF then). */
    var onBodyDone: () -> Unit = {}

    private var bodyError: HttpError? = null

    private fun bodyClosedError(): HttpError = bodyError ?: HttpError(HttpError.Kind.IncompleteMessage)

    /** Writes `100 Continue` now, unless a response head has been written meanwhile (hyper: only in `Writing::Init`). */
    private suspend fun sendContinue() {
        io.writeLock.withLock {
            if (writing != Writing.INIT) return
            io.headersBuf().writeBytes(CONTINUE_RESPONSE)
            io.flush()
        }
    }

    /**
     * hyper `poll_drain_or_close_read`, when the exchange has ended with the body not read to its end: take one step
     * of the body from what is already buffered; if that does not end it, stop reading (the connection then closes
     * after the response).
     */
    fun drainOrCloseRead() {
        if (reading == Reading.CONTINUE) reading = Reading.BODY
        if (reading == Reading.BODY) {
            when (decoder!!.decode(io.readBuf, io.readEof)) {
                DecodeResult.DATA -> if (decoder!!.isEof) reading = Reading.KEEP_ALIVE
                DecodeResult.TRAILERS, DecodeResult.END -> reading = Reading.KEEP_ALIVE
                else -> {}
            }
            bodyGeneration++                       // the service's body reader is detached from the connection now
        }
        when (reading) {
            Reading.INIT, Reading.KEEP_ALIVE -> {}
            else -> closeRead()
        }
    }

    // ---- writing ---------------------------------------------------------------------------------------------

    /** hyper `can_write_head`. */
    val canWriteHead: Boolean
        get() = if (!isServer && reading == Reading.CLOSED) false else writing == Writing.INIT && io.canHeadersBuf

    /** hyper `can_write_body`. */
    val canWriteBody: Boolean get() = writing == Writing.BODY

    /** hyper `write_head` for a response; an encoding failure sets [error] and closes the write side. */
    fun writeHead(parts: ResponseParts, body: Long?) {
        enforceVersion(parts)
        applyPlan(ServerHeadEncoder.encodeInto(parts, body, method, wantsKeepAlive, config, dateHeader, io.headersBuf(), plan))
    }

    fun writeHead(parts: RequestParts, body: Long?) {
        busy()
        if (H1Headers.connectionAnyClose(parts.headers)) keepAlive = KA.DISABLED
        enforceVersion(parts)
        method = parts.method
        val plan = ClientHeadEncoder.encode(parts, body, config, io.headersBuf())
        applyPlan(plan)
    }

    private fun applyPlan(plan: EncodePlan) {
        if (plan.error != null) {
            error = HttpError(
                if (plan.error == H1EncodeError.UnexpectedHeader) HttpError.Kind.UserUnexpectedHeader else HttpError.Kind.UserUnsupportedStatusCode,
            )
            writing = Writing.CLOSED
            keepAlive = KA.DISABLED
            return
        }
        val enc = reusableEncoder.also { it.reset(plan) }
        encoder = enc
        writing = when {
            !enc.isEof -> Writing.BODY
            enc.isLast -> Writing.CLOSED
            else -> Writing.KEEP_ALIVE
        }
    }

    /** hyper `write_body`: a non-empty chunk. */
    fun writeBody(data: Bytes) {
        val enc = encoder!!
        val n = enc.writeDataPrefix(data.size, io.framingBuf())
        if (n > 0) {
            io.bufferData(if (n == data.size) data else data.slice(0, n))
            enc.writeDataSuffix(io.framingBuf())
        }
        if (!enc.isEof) return
        writing = if (enc.isLast) Writing.CLOSED else Writing.KEEP_ALIVE
    }

    /** hyper `write_body_and_end`: the last, non-empty chunk. */
    fun writeBodyAndEnd(data: Bytes) {
        val enc = encoder!!
        val n = enc.writeDataPrefix(data.size, io.framingBuf())
        if (n > 0) {
            io.bufferData(if (n == data.size) data else data.slice(0, n))
            enc.writeDataSuffix(io.framingBuf())
        }
        // hyper `encode_and_end`: a length-delimited body still short after its last chunk cannot keep the connection.
        val canKeepAlive = when {
            enc.isChunked -> { enc.end(io.framingBuf()); !enc.isLast }
            enc.isCloseDelimited -> false
            else -> enc.remaining == 0L && !enc.isLast
        }
        writing = if (canKeepAlive) Writing.KEEP_ALIVE else Writing.CLOSED
    }

    /** hyper `write_trailers`: ignored by a server unless the request's `TE` allowed trailers. */
    fun writeTrailers(trailers: neton.http.header.HeaderMap<HeaderValue>) {
        if (isServer && !allowTrailerFields) return
        val enc = encoder!!
        if (enc.encodeTrailers(trailers, config.titleCaseHeaders, io.framingBuf())) {
            writing = if (enc.isLast || enc.isCloseDelimited) Writing.CLOSED else Writing.KEEP_ALIVE
        }
    }

    /** hyper `end_body`; a length-delimited body that ended early is [HttpError.Kind.UserBodyWriteAborted]. */
    fun endBody() {
        if (writing != Writing.BODY) return
        val enc = encoder!!
        if (enc.end(io.framingBuf())) {
            writing = if (enc.isLast || enc.isCloseDelimited) Writing.CLOSED else Writing.KEEP_ALIVE
        } else {
            writing = Writing.CLOSED
            keepAlive = KA.DISABLED
            throw HttpError(HttpError.Kind.UserBodyWriteAborted, IllegalStateException("${enc.remaining} bytes missing"))
        }
    }

    /**
     * Writes [body]'s frames (hyper `Dispatcher::poll_write` body loop) until the body or the write side ends, then
     * flushes. A frame that is not ready at once makes the buffered bytes go out first, as hyper flushes when the body
     * is pending; [onPending] is told then too. [frames] runs the body's `nextFrame` calls.
     */
    suspend fun pumpBody(body: neton.http.Body, frames: InlineCall<neton.http.Body, Frame?>, onPending: () -> Unit) {
        while (canWriteBody) {
            if (!io.canBuffer) flushLocked()
            val r = try { frames.start(body) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { throw HttpError(HttpError.Kind.UserBody, e) }
            val frame = if (r !== kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED) r as Frame? else {
                flushLocked()
                onPending()
                try { frames.await() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { throw HttpError(HttpError.Kind.UserBody, e) }
            }
            if (frame == null) {
                io.writeLock.withLock { endBody() }
                break
            }
            if (frame is Frame.Data) {
                val data = frame.bytes
                if (body.isEndStream) {
                    io.writeLock.withLock { if (data.size == 0) endBody() else writeBodyAndEnd(data) }
                    break
                }
                if (data.size == 0) continue
                io.writeLock.withLock { writeBody(data) }
            } else if (frame is Frame.Trailers) {
                io.writeLock.withLock {
                    writeTrailers(frame.headers)
                    if (canWriteBody) endBody()
                }
                break
            }
        }
        flushLocked()
    }

    /** [flush] holding the write lock; one suspending layer on the per-response path. */
    suspend fun flushLocked() {
        io.writeLock.lock()
        try {
            io.flush()
        } catch (e: IoException) {
            close()
            throw HttpError(HttpError.Kind.BodyWrite, e)
        } finally {
            io.writeLock.unlock()
        }
        tryKeepAlive()
    }

    /** hyper `poll_flush` + `try_keep_alive`. */
    suspend fun flush() {
        try {
            io.flush()
        } catch (e: IoException) {
            close()
            throw HttpError(HttpError.Kind.BodyWrite, e)
        }
        tryKeepAlive()
    }

    /** hyper `enforce_version` / `fix_keep_alive`, for the outgoing head. */
    private fun enforceVersion(parts: ResponseParts) {
        when (version) {
            Version.HTTP_10 -> { fixKeepAlive(parts.headers, parts.version); parts.version = Version.HTTP_10 }
            Version.HTTP_11 -> if (keepAlive == KA.DISABLED) parts.headers.insert(HeaderName.CONNECTION, HeaderValue.fromStatic("close"))
            else -> {}
        }
    }

    private fun enforceVersion(parts: RequestParts) {
        when (version) {
            Version.HTTP_10 -> { fixKeepAlive(parts.headers, parts.version); parts.version = Version.HTTP_10 }
            Version.HTTP_11 -> if (keepAlive == KA.DISABLED) parts.headers.insert(HeaderName.CONNECTION, HeaderValue.fromStatic("close"))
            else -> {}
        }
    }

    private fun fixKeepAlive(headers: neton.http.header.HeaderMap<HeaderValue>, headVersion: Version) {
        val outgoingIsKeepAlive = headers[HeaderName.CONNECTION]?.let { H1Headers.connectionKeepAlive(it) } ?: false
        if (!outgoingIsKeepAlive) {
            when (headVersion) {
                Version.HTTP_10 -> keepAlive = KA.DISABLED
                Version.HTTP_11 -> if (wantsKeepAlive) headers.insert(HeaderName.CONNECTION, HeaderValue.fromStatic("keep-alive"))
                else -> {}
            }
        }
    }

    // ---- keep-alive ------------------------------------------------------------------------------------------

    val wantsKeepAlive: Boolean get() = keepAlive != KA.DISABLED
    val isIdle: Boolean get() = keepAlive == KA.IDLE

    /** hyper `State::try_keep_alive`. */
    fun tryKeepAlive() {
        when {
            reading == Reading.KEEP_ALIVE && writing == Writing.KEEP_ALIVE -> if (keepAlive == KA.BUSY) idle() else close()
            reading == Reading.CLOSED && writing == Writing.KEEP_ALIVE || reading == Reading.KEEP_ALIVE && writing == Writing.CLOSED -> close()
        }
    }

    private fun idle() {
        method = null
        keepAlive = KA.IDLE
        reading = Reading.INIT
        writing = Writing.INIT
        decoder = null
        encoder = null
    }

    fun busy() { if (keepAlive != KA.DISABLED) keepAlive = KA.BUSY }

    /** hyper `Conn::disable_keep_alive` (server graceful shutdown). */
    fun disableKeepAlive() { if (isIdle) close() else keepAlive = KA.DISABLED }

    fun close() { reading = Reading.CLOSED; writing = Writing.CLOSED; keepAlive = KA.DISABLED }
    fun closeRead() { reading = Reading.CLOSED; keepAlive = KA.DISABLED }
    fun closeWrite() { writing = Writing.CLOSED; keepAlive = KA.DISABLED }

    companion object {
        val H2_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".encodeToByteArray()
        private val CONTINUE_RESPONSE = "HTTP/1.1 100 Continue\r\n\r\n".encodeToByteArray()
    }
}
