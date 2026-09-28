package neton.http.h2

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import neton.http.Body
import neton.http.Frame
import neton.http.HttpError
import neton.http.Method
import neton.http.h1.InlineCall
import neton.http.h2.frame.Reason
import neton.http.h2.proto.PollReset
import neton.http.header.HeaderMap
import neton.http.header.HeaderName
import neton.http.header.HeaderValue
import neton.io.bytes.Bytes
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED

// hyper's HTTP/2 wiring shared by the client and the server (hyper 1.11.1 `src/proto/h2/mod.rs`, and the helpers of
// `src/headers.rs` it uses): connection header stripping and the body pump into a `SendStream`.

/** The default initial window size of the HTTP/2 specification (`SPEC_WINDOW_SIZE`). */
internal const val SPEC_WINDOW_SIZE: Int = 65_535

private val KEEP_ALIVE = HeaderName.fromStatic("keep-alive")
private val PROXY_CONNECTION = HeaderName.fromStatic("proxy-connection")

// The connection headers of RFC 9110 §7.6.1 (`CONNECTION_HEADERS`); TE is allowed in requests as `trailers`.
private val CONNECTION_HEADERS = arrayOf(KEEP_ALIVE, PROXY_CONNECTION, HeaderName.TRANSFER_ENCODING, HeaderName.UPGRADE)

private val TRAILERS = "trailers".encodeToByteArray()

/**
 * Removes the headers HTTP/2 forbids (`strip_connection_headers`): keep-alive, proxy-connection, transfer-encoding,
 * upgrade, a `te` other than `trailers` in a request (any `te` in a response), and `connection` with the headers it
 * names.
 */
internal fun stripConnectionHeaders(headers: HeaderMap<HeaderValue>, isRequest: Boolean) {
    for (h in CONNECTION_HEADERS) headers.remove(h) // "Connection header illegal in HTTP/2"
    if (isRequest) {
        val te = headers[HeaderName.TE]
        // "TE headers not set to "trailers" are illegal in HTTP/2 requests"
        if (te != null && !te.bytesEqual(TRAILERS)) headers.remove(HeaderName.TE)
    } else {
        headers.remove(HeaderName.TE) // "TE headers illegal in HTTP/2 responses"
    }
    val connection = headers.remove(HeaderName.CONNECTION) ?: return
    // A `Connection` header may list the other headers meant only for this connection: remove them too.
    val contents = connection.tryToStr() ?: return
    for (name in contents.split(',')) headers.remove(name.trim())
}

private fun HeaderValue.bytesEqual(b: ByteArray): Boolean {
    if (length != b.size) return false
    for (i in b.indices) if (byteAt(i) != b[i]) return false
    return true
}

/**
 * The content length of [headers] (`content_length_parse_all`): every `content-length` value (comma-separated lists
 * included) must be the same decimal number; null otherwise, or when absent. -1 stands for none (u64 values above
 * `Long.MAX_VALUE` are none too).
 */
internal fun contentLengthParseAll(headers: HeaderMap<HeaderValue>): Long {
    if (!headers.containsKey(HeaderName.CONTENT_LENGTH)) return -1
    var result = -1L
    for (v in headers.getAll(HeaderName.CONTENT_LENGTH)) {
        val line = v.tryToStr() ?: return -1
        for (part in line.split(',')) {
            val n = fromDigits(part.trim())
            if (n < 0) return -1
            if (result < 0) result = n else if (result != n) return -1
        }
    }
    return result
}

/** `from_digits`: decimal digits only (no sign), without overflow; -1 when invalid. */
private fun fromDigits(s: String): Long {
    if (s.isEmpty()) return -1
    var r = 0L
    for (c in s) {
        if (c !in '0'..'9') return -1
        val d = c - '0'
        if (r > (Long.MAX_VALUE - d) / 10) return -1
        r = r * 10 + d
    }
    return r
}

/** `method_has_defined_payload_semantics`. */
internal fun methodHasDefinedPayloadSemantics(method: Method): Boolean =
    method != Method.GET && method != Method.HEAD && method != Method.DELETE && method != Method.CONNECT

/** `set_content_length_if_missing`. */
internal fun setContentLengthIfMissing(headers: HeaderMap<HeaderValue>, len: Long) {
    if (!headers.containsKey(HeaderName.CONTENT_LENGTH)) headers.insert(HeaderName.CONTENT_LENGTH, HeaderValue.from(len))
}

/** hyper `Error::new_h2`: an I/O error of the connection is [HttpError.Kind.Io], anything else [HttpError.Kind.Http2]. */
internal fun newH2(e: H2Error): HttpError = HttpError(if (e.isIo) HttpError.Kind.Io else HttpError.Kind.Http2, e)

/** hyper `Error::new_body_write`. */
internal fun newBodyWrite(cause: Throwable): HttpError = HttpError(HttpError.Kind.BodyWrite, cause)

/**
 * Watches a stream for a reset while the owner waits on user code (a service, a body) that does not know about the
 * stream: the reference registers for `poll_reset` on every poll. Started only once the user code suspends; on a reset
 * (or a connection error) it records it in [reset] and cancels the owner's [target] job.
 */
internal class ResetWatch(private val ref: neton.http.h2.proto.StreamRef) {
    /** The reset (`h2::Error::from(reason)`) or the connection error seen, once the watch fired. */
    var reset: H2Error? = null
        private set
    private var job: Job? = null

    /** Starts watching (once), in a child of [context], whose job is cancelled on a reset. */
    fun start(context: CoroutineContext) {
        if (job != null) return
        val target = context.job
        job = CoroutineScope(context).launch(start = CoroutineStart.UNDISPATCHED) {
            reset = try {
                H2Error.fromReason(awaitReset(ref, PollReset.Streaming)) // "stream received RST_STREAM"
            } catch (e: H2Error) {
                e
            }
            target.cancel()
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}

/**
 * Pumps a body into a [SendStream] (`PipeToSendStream`): each DATA chunk waits for stream capacity (a claim of one
 * byte, not the chunk, so an idle body never pins connection window, hyper #4003), trailers end the stream, and the
 * end of the body without trailers sends an empty END_STREAM DATA frame. The body is read in the caller's coroutine
 * without a coroutine per frame; only when it suspends is a [ResetWatch] started, so a peer's reset still interrupts
 * a body that is not ready.
 */
internal class PipeToSendStream(private val body: Body, private val tx: SendStream, private val watch: ResetWatch) {
    private val frameCall = InlineCall<Body, Frame?>(Body::nextFrame)

    // A data chunk polled from the body that waits for capacity (`buffered_data`).
    private var buffered: Bytes? = null
    private var bufferedEos = false

    /**
     * Runs until the body is sent. @throws HttpError [HttpError.Kind.BodyWrite] when the stream can no longer send,
     * [HttpError.Kind.UserBody] when the body failed (the stream is then reset with the error's reason).
     */
    suspend fun run() {
        frameCall.context = coroutineContext
        try {
            loop()
        } catch (e: CancellationException) {
            throw watch.reset?.let { newBodyWrite(it) } ?: e
        }
    }

    private suspend fun loop() {
        while (true) {
            // Check for RST_STREAM before waiting for the next chunk or for capacity.
            tx.pollResetNow()?.let { reason ->
                // "stream received RST_STREAM"
                throw newBodyWrite(H2Error.fromReason(reason))
            }

            // A chunk waiting for stream capacity is sent first.
            val data = buffered
            if (data != null) {
                if (tx.capacity() == 0) {
                    // Woken by more capacity, a reset or the end of the stream: look again from the top.
                    if (tx.awaitCapacity() == null && tx.pollResetNow() == null) {
                        // The stream no longer sends: it was finished or the remote reset it.
                        throw newBodyWrite(IllegalStateException("send stream capacity unexpectedly closed"))
                    }
                    continue
                }
                buffered = null
                sendData(data, bufferedEos)
                if (bufferedEos) return
                continue
            }

            // Poll the next frame before reserving any capacity (#4003).
            val frame = try {
                val r = frameCall.start(body)
                if (r === COROUTINE_SUSPENDED) {
                    watch.start(coroutineContext)
                    frameCall.await()
                } else {
                    @Suppress("UNCHECKED_CAST")
                    r as Frame?
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // `on_user_err`
                val err = HttpError(HttpError.Kind.UserBody, e)
                runCatching { tx.sendReset(err.h2Reason()) }
                throw err
            }
            when (frame) {
                null -> {
                    // No more frames and no trailers: an empty END_STREAM DATA frame (`send_eos_frame`).
                    sendData(Bytes.EMPTY, true)
                    return
                }
                is Frame.Data -> {
                    val chunk = frame.bytes
                    val isEos = body.isEndStream
                    if (chunk.size == 0) {
                        // Zero-length DATA needs no capacity: send it through, so a trailing empty frame (an explicit
                        // end-of-stream marker) is delivered.
                        sendData(chunk, isEos)
                        if (isEos) return
                        continue
                    }
                    // A minimal claim on the window; h2 raises it to the buffered length in `send_data`.
                    tx.reserveCapacity(1)
                    buffered = chunk
                    bufferedEos = isEos
                }
                is Frame.Trailers -> {
                    // No more DATA: give any capacity back.
                    tx.reserveCapacity(0)
                    try {
                        tx.sendTrailers(frame.headers)
                    } catch (e: H2Error) {
                        throw newBodyWrite(e)
                    }
                    return
                }
            }
        }
    }

    private fun sendData(data: Bytes, eos: Boolean) {
        try {
            tx.sendData(data, eos)
        } catch (e: H2Error) {
            throw newBodyWrite(e)
        }
    }
}

/**
 * One non-suspending `poll_reset` of the stream: its reset reason, or null.
 * @throws HttpError [HttpError.Kind.BodyWrite] for a connection error.
 */
internal fun SendStream.pollResetNow(): Reason? =
    try {
        h2Call { ref.streams.send.pollReset(ref.stream, PollReset.Streaming) }
    } catch (e: H2Error) {
        throw newBodyWrite(e)
    }
