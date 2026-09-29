package neton.http.h3

import neton.http.h3.proto.Frame
import neton.http.h3.proto.FrameError
import neton.http.h3.proto.FrameException
import neton.http.h3.proto.Header
import neton.http.h3.proto.HeaderException
import neton.http.h3.proto.WriteBuf
import neton.http.h3.proto.StreamId
import neton.http.h3.qpack.Decoder
import neton.http.h3.qpack.DecoderError
import neton.http.h3.qpack.DecoderException
import neton.http.h3.qpack.Encoder
import neton.http.h3.quic.RecvStream
import neton.http.h3.quic.SendStream
import neton.http.h3.quic.StreamErrorIncoming
import neton.http.header.HeaderMap
import neton.http.header.HeaderValue
import neton.io.bytes.Buffer
import neton.io.bytes.Bytes

/**
 * A request stream, either side of it (`connection::RequestStream`, `src/connection.rs`): the frames of one HTTP
 * message exchange, read with phase A's sans-I/O [FrameStream] over the QUIC stream. [send] and [recv] are the two
 * halves; after a split each part has only one.
 *
 * Receiving (RFC 9114 §4.1): an optional body of DATA frames, then optional trailers (one HEADERS frame), then only
 * unknown frames. Any other known frame is a connection error of H3_FRAME_UNEXPECTED. Received header sections are
 * held to the three limits of SPEC §5 ([Config]); a limit exceeded is [StreamError.HeaderTooBig], not a connection
 * error.
 *
 * ⚖️ Beyond the reference:
 * - a `content-length` (parsed by phase A's header validation) is checked against the DATA received: more, or less at
 *   the end, makes the message malformed (RFC 9114 §4.1.2, H3_MESSAGE_ERROR, a stream error);
 * - trailers with pseudo-headers are malformed (RFC 9114 §4.3); the reference drops the pseudo-headers;
 * - an empty DATA frame does not end the body (the reference's `poll_data` returns `None` for it).
 */
internal class RequestStreamInner(
    val send: SendStream?,
    val recv: RecvStream?,
    val shared: SharedState,
    private val config: Config,
    private var sendGreaseFrame: Boolean,
) {
    val id: StreamId get() = send?.sendId ?: recv!!.recvId

    /** The received frames; the head may already have been read through it. */
    val frames = FrameStream(config.maxHeadersFrameSize, requestStream = true)

    private val decoder = Decoder(config.maxFieldSectionSize, config.maxFieldCount)
    private var encoder: Encoder? = null

    /** A HEADERS frame read by [recvData] as the trailers, not yet decoded. */
    private var trailers: Bytes? = null

    /** A trailers HEADERS frame refused by its size (`maxHeadersFrameSize`), reported by [recvTrailers]. */
    private var trailersTooLarge: FrameError.HeadersTooLarge? = null

    /** The body is complete: trailers or the end of the stream were reached. */
    private var bodyDone = false

    /** The declared content-length of the received message, checked against the DATA received (null: unchecked). */
    var contentLength: ULong? = null
    private var received = 0L

    /** The send side was finished or reset. */
    var sendClosed = false
        private set

    /** The receive side reached its end, or was stopped. */
    var recvClosed = false
        private set

    // ---- receiving ----

    /**
     * The next frame, reading from the stream as needed (`FrameStream::poll_next`); null at the end of the stream.
     * @throws FrameException
     * @throws StreamErrorIncoming
     */
    suspend fun nextFrame(): Frame? {
        val r = checkNotNull(recv) { "this part of the stream cannot receive" }
        while (true) {
            val frame = frames.nextFrame()
            if (frame != null) return frame
            if (frames.isFinished) {
                recvClosed = true
                return null
            }
            val chunk = r.read()
            if (chunk == null) frames.onEnd() else frames.onData(chunk)
        }
    }

    /** The next piece of the current DATA payload, reading as needed. */
    private suspend fun nextData(): Bytes {
        while (true) {
            frames.nextData()?.let { return it }
            val chunk = recv!!.read()
            if (chunk == null) frames.onEnd() else frames.onData(chunk)
        }
    }

    /**
     * The next piece of the body (`poll_recv_data`), or null once the body is complete (trailers follow, or the stream
     * ended).
     * @throws StreamError
     */
    suspend fun recvData(): Bytes? = receiving { nextBodyPiece() }

    private suspend fun nextBodyPiece(): Bytes? {
        while (!frames.hasData) {
            if (bodyDone) return null
            val frame = try {
                nextFrame()
            } catch (e: FrameException) {
                val err = e.error
                if (err !is FrameError.HeadersTooLarge) throw e
                // Trailers too large: the body is over; recvTrailers reports it.
                trailersTooLarge = err
                bodyDone = true
                return null
            }
            when (frame) {
                null -> {
                    bodyDone = true
                    checkContentLengthAtEnd()
                    return null
                }
                is Frame.Headers -> {
                    // Trailers: no more data expected.
                    trailers = frame.block
                    bodyDone = true
                    checkContentLengthAtEnd()
                    return null
                }
                // An empty DATA frame is skipped.
                is Frame.Data -> Unit
                // RFC 9114 §4.1, §7.2.3–7.2.7: an invalid sequence of frames.
                else -> throw shared.connectionErrorOnStream(
                    InternalConnectionError(Code.H3_FRAME_UNEXPECTED, "unexpected frame: $frame"),
                )
            }
        }
        val data = nextData()
        accountData(data.size)
        return data
    }

    private fun accountData(size: Int) {
        received += size
        val cl = contentLength
        if (cl != null && received.toULong() > cl) throw malformed("more DATA than the content-length $cl")
    }

    /**
     * The trailers (`poll_recv_trailers`), or null when the message has none. Waits for the end of the stream: after
     * the trailers only unknown frames may come (RFC 9114 §4.1). Body bytes not yet read are discarded.
     * @throws StreamError [StreamError.HeaderTooBig] for trailers over a limit.
     */
    suspend fun recvTrailers(): HeaderMap<HeaderValue>? = receiving { nextTrailers() }

    private suspend fun nextTrailers(): HeaderMap<HeaderValue>? {
        trailersTooLarge?.let { throw StreamError.HeaderTooBig(it.length, it.max.toLong()) }
        val block = trailers ?: skipBodyToTrailers() ?: return null
        trailers = null
        // After the trailers, a known frame is an invalid sequence of frames.
        val next = nextFrame()
        if (next != null) {
            throw shared.connectionErrorOnStream(InternalConnectionError(Code.H3_FRAME_UNEXPECTED, "unexpected frame: $next"))
        }
        val header = decodeSection(block, "Failed to decode trailers")
        if (header.hasPseudo) throw malformed("pseudo-header in trailers")
        return header.intoFields()
    }

    /** Discards the rest of the body; the trailers' encoded section, or null when the message ends without them. */
    private suspend fun skipBodyToTrailers(): Bytes? {
        if (bodyDone) return null
        while (true) {
            while (frames.hasData) accountData(nextData().size)
            val frame = try {
                nextFrame()
            } catch (e: FrameException) {
                val err = e.error
                if (err !is FrameError.HeadersTooLarge) throw e
                bodyDone = true
                throw StreamError.HeaderTooBig(err.length, err.max.toLong())
            }
            when (frame) {
                is Frame.Data -> continue
                is Frame.Headers, null -> {
                    bodyDone = true
                    checkContentLengthAtEnd()
                    return (frame as Frame.Headers?)?.block
                }
                else -> throw shared.connectionErrorOnStream(
                    InternalConnectionError(Code.H3_FRAME_UNEXPECTED, "unexpected frame: $frame"),
                )
            }
        }
    }

    /**
     * Decodes a received header section with the limits of [config] (`qpack::decode_stateless`).
     * @throws StreamError [StreamError.HeaderTooBig] for a limit; a connection error of QPACK_DECOMPRESSION_FAILED for
     * a QPACK error; [HeaderException] (malformed) is passed on for the caller to handle.
     */
    fun decodeSection(block: Bytes, what: String): Header {
        try {
            return Header.decode(block, decoder)
        } catch (e: DecoderException) {
            limitError(e.error)?.let { throw it }
            throw shared.connectionErrorOnStream(InternalConnectionError(Code.QPACK_DECOMPRESSION_FAILED, what))
        }
    }

    /** The [StreamError.HeaderTooBig] for a decoding limit, or null for a QPACK error. */
    private fun limitError(e: DecoderError): StreamError? = when (e) {
        is DecoderError.HeaderTooLong -> StreamError.HeaderTooBig(e.size, config.maxFieldSectionSize)
        is DecoderError.TooManyFields -> StreamError.HeaderTooBig(e.count.toLong(), config.maxFieldCount.toLong())
        else -> null
    }

    /** The error for a limit exceeded by a received header section, from the frame layer or the decoder. */
    fun headerTooBig(e: H3Exception): StreamError? = when (e) {
        is FrameException -> (e.error as? FrameError.HeadersTooLarge)?.let { StreamError.HeaderTooBig(it.length, it.max.toLong()) }
        is DecoderException -> limitError(e.error)
        else -> null
    }

    private fun checkContentLengthAtEnd() {
        val cl = contentLength ?: return
        if (received.toULong() != cl) throw malformed("$received bytes of DATA for a content-length of $cl")
    }

    /**
     * A malformed message (RFC 9114 §4.1.2): a stream error of H3_MESSAGE_ERROR; both directions are abandoned with
     * that code.
     */
    fun malformed(reason: String): StreamError {
        stopSending(Code.H3_MESSAGE_ERROR)
        stopStream(Code.H3_MESSAGE_ERROR)
        return StreamError.Stream(Code.H3_MESSAGE_ERROR, "Malformed message: $reason")
    }

    /**
     * Runs a receive operation, mapping the errors of the frame and QUIC layers (`handle_frame_stream_error_on_request_stream`):
     * a frame error is a connection error (H3_FRAME_ERROR for a truncated frame), a failed stream operation a
     * [StreamError].
     */
    private inline fun <T> receiving(block: () -> T): T {
        try {
            return block()
        } catch (e: FrameException) {
            throw shared.connectionErrorOnStream(InternalConnectionError.of(e))
        } catch (e: StreamErrorIncoming) {
            throw shared.streamError(e)
        } catch (e: HeaderException) {
            throw malformed(e.message ?: "malformed header section")
        }
    }

    /** Asks the peer to stop sending (`stop_sending`). */
    fun stopSending(code: Code) {
        val r = recv ?: return
        if (recvClosed) return
        recvClosed = true
        r.stopSending(code.value)
    }

    // ---- sending ----

    private fun sender(): SendStream = checkNotNull(send) { "this part of the stream cannot send" }

    /**
     * QPACK-encodes [header] and sends it as a HEADERS frame (`send_response`, `send_trailers`, the request head).
     * @throws StreamError [StreamError.HeaderTooBig] when its decoded size exceeds the peer's
     * SETTINGS_MAX_FIELD_SECTION_SIZE (RFC 9114 §4.2.2: SHOULD NOT send; §7.2.4.2: MUST NOT send what the peer's
     * settings make invalid).
     */
    suspend fun sendHeaders(header: Header) {
        val s = sender()
        val block = encode(header)
        try {
            s.writeBuf(WriteBuf.of(Frame.Headers(block)))
        } catch (e: StreamErrorIncoming) {
            throw shared.streamError(e)
        }
    }

    /** The encoded [header], checked against the peer's SETTINGS_MAX_FIELD_SECTION_SIZE. @throws StreamError */
    fun encode(header: Header): Bytes {
        val enc = encoder ?: Encoder().also { encoder = it }
        val buf = Buffer()
        val size = header.encode(enc, buf)
        val max = shared.settings.maxFieldSectionSize
        if (size > max) throw StreamError.HeaderTooBig(size, max)
        return buf.readSlice(buf.readableBytes)
    }

    /** Sends [data] as one DATA frame (`send_data`). @throws StreamError */
    suspend fun sendData(data: Bytes) {
        val s = sender()
        try {
            s.writeBuf(WriteBuf.of(Frame.Data(data)))
        } catch (e: StreamErrorIncoming) {
            throw shared.streamError(e)
        }
    }

    /** Sends trailers (`send_trailers`). @throws StreamError */
    suspend fun sendTrailers(trailers: HeaderMap<HeaderValue>) = sendHeaders(Header.trailer(trailers))

    /**
     * Ends the sending side (`finish`), after a GREASE frame on the connection's first request stream (RFC 9114
     * §7.2.8).
     * @throws StreamError
     */
    suspend fun finish(grease: Boolean = true) {
        val s = sender()
        try {
            if (grease && sendGreaseFrame) {
                s.writeBuf(WriteBuf.of(Frame.Grease))
                sendGreaseFrame = false
            }
            s.finish()
            sendClosed = true
        } catch (e: StreamErrorIncoming) {
            throw shared.streamError(e)
        }
    }

    /** Resets the sending side with [code] (`stop_stream`). */
    fun stopStream(code: Code) {
        val s = send ?: return
        if (sendClosed) return
        sendClosed = true
        s.reset(code.value)
    }

    /** The two parts (`split`): the send part and the receive part, which keeps what was received. */
    fun split(): Pair<RequestStreamInner, RequestStreamInner> {
        check(!frames.hasData) { "split while a DATA frame is being read" }
        val sendPart = RequestStreamInner(send, null, shared, config, sendGreaseFrame)
        val recvPart = RequestStreamInner(null, recv, shared, config, false)
        recvPart.frames.buffer.writeBytes(frames.buffer.backingArray(), frames.buffer.readerIndex(), frames.buffer.readableBytes)
        if (frames.isEos) recvPart.frames.onEnd()
        recvPart.trailers = trailers
        recvPart.trailersTooLarge = trailersTooLarge
        recvPart.bodyDone = bodyDone
        recvPart.contentLength = contentLength
        recvPart.received = received
        recvPart.recvClosed = recvClosed
        sendPart.sendClosed = sendClosed
        return sendPart to recvPart
    }
}
