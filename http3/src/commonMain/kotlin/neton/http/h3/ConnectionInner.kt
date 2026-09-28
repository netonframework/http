package neton.http.h3

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import neton.http.h3.proto.Frame
import neton.http.h3.proto.FrameError
import neton.http.h3.proto.FrameException
import neton.http.h3.proto.Side
import neton.http.h3.proto.StreamType
import neton.http.h3.proto.StreamTypeDecoder
import neton.http.h3.proto.UniStreamHeader
import neton.http.h3.proto.WriteBuf
import neton.http.h3.qpack.DecoderStreamReceiver
import neton.http.h3.qpack.EncoderStreamReceiver
import neton.http.h3.quic.Connection
import neton.http.h3.quic.ConnectionErrorIncoming
import neton.http.h3.quic.RecvStream
import neton.http.h3.quic.SendStream
import neton.http.h3.quic.StreamErrorIncoming
import neton.io.bytes.Buffer

/**
 * The part of an HTTP/3 connection common to client and server (`ConnectionInner`, `src/connection.rs`): the local
 * control stream (SETTINGS first) and QPACK encoder / decoder streams opened at start, the peer's unidirectional
 * streams accepted by type, the control stream rules, the critical streams, GOAWAY, and the GREASE stream.
 *
 * ⚖️ Driving: the reference has no driver task; whoever polls `accept` / `poll_close` reads the control stream, and
 * the QPACK streams are accepted but never read. Here [drive] (the public `run()`) reads every peer unidirectional
 * stream in its own coroutine, so a stalled stream never holds up another, and it reads the QPACK encoder and decoder
 * streams and checks their instructions with phase A's validators (SPEC §5: "照常打开并读取 … 不得忽略这两条流").
 */
internal class ConnectionInner private constructor(
    val conn: Connection,
    val shared: SharedState,
    val config: Config,
    val side: Side,
    private val controlSend: SendStream,
    private val encoderSend: SendStream?,
    private val decoderSend: SendStream?,
) {
    private var gotControl = false
    private var gotEncoder = false
    private var gotDecoder = false
    private var gotPeerSettings = false
    private var running = false

    /** Whether the next request stream sends a GREASE frame before finishing (one per connection). */
    var sendGreaseFrame: Boolean = config.sendGrease

    /**
     * Handles GOAWAY, CANCEL_PUSH and MAX_PUSH_ID from the peer's control stream (the server's and client's
     * `poll_control`); returns the connection error they cause, or null.
     */
    lateinit var onControlFrame: (Frame) -> InternalConnectionError?

    /** Records [origin] as the connection error (closing the connection) and returns the error in effect. */
    fun handleConnectionError(origin: ErrorOrigin): ConnectionError = shared.setConnError(origin).toConnectionError()

    fun handleConnectionError(code: Code, message: String): ConnectionError =
        handleConnectionError(ErrorOrigin.Internal(InternalConnectionError(code, message)))

    /** The connection error, if there is one. */
    fun connectionError(): ConnectionError? = shared.error?.toConnectionError()

    /**
     * Writes the local streams' headers (`send_control_stream_headers`): the control stream type and SETTINGS
     * (RFC 9114 §6.2.1: the first frame), and the QPACK stream types (RFC 9204 §4.2). Failures on the QPACK streams are
     * ignored, as in the reference; a failure on the control stream is a connection error.
     */
    private suspend fun sendControlStreamHeaders() {
        if (!config.sendSettings) return
        val settings = config.toFrame()
        try {
            controlSend.writeBuf(WriteBuf.of(UniStreamHeader.Control(settings)))
        } catch (e: StreamErrorIncoming) {
            throw controlStreamWriteError(e)
        }
        decoderSend?.let { s -> runCatchingStream { s.writeBuf(WriteBuf.of(UniStreamHeader.Decoder)) } }
        encoderSend?.let { s -> runCatchingStream { s.writeBuf(WriteBuf.of(UniStreamHeader.Encoder)) } }
    }

    private fun controlStreamWriteError(e: StreamErrorIncoming): ConnectionError = when (e) {
        is StreamErrorIncoming.ConnectionLost -> handleConnectionError(ErrorOrigin.Quic(e.connectionError))
        // RFC 9114 §6.2.1: if either control stream is closed at any point, H3_CLOSED_CRITICAL_STREAM.
        is StreamErrorIncoming.StreamTerminated -> handleConnectionError(
            Code.H3_CLOSED_CRITICAL_STREAM,
            "control stream was requested to stop sending with error code ${e.errorCode}",
        )
        is StreamErrorIncoming.Unknown ->
            handleConnectionError(Code.H3_CLOSED_CRITICAL_STREAM, "an error occurred on the control stream ${e.error}")
    }

    /**
     * Drives the connection until it fails or closes, and returns the connection error (the reference's `poll_close`
     * and the control part of `accept`): accepts and reads the peer's unidirectional streams, watches the local
     * critical streams, sends the GREASE stream, and runs [sideLoop] (the server's request acceptor, the client's
     * check for server-initiated bidirectional streams). Everything stops once there is a connection error.
     */
    suspend fun drive(sideLoop: suspend () -> Unit): ConnectionError {
        connectionError()?.let { return it }
        check(!running) { "the connection is already running" }
        running = true
        return coroutineScope {
            val job = launch {
                launch { acceptUniStreams() }
                launch { watchCritical(controlSend, "control") }
                encoderSend?.let { s -> launch { watchCritical(s, "encoder") } }
                decoderSend?.let { s -> launch { watchCritical(s, "decoder") } }
                if (config.sendGrease) launch { sendGreaseStream() }
                launch { sideLoop() }
            }
            val error = shared.awaitError()
            job.cancel()
            error.toConnectionError()
        }
    }

    /** Accepts the peer's unidirectional streams (`poll_accept_recv`), each read in its own coroutine. */
    private suspend fun acceptUniStreams() = coroutineScope {
        while (true) {
            val stream = try {
                conn.acceptUni()
            } catch (e: ConnectionErrorIncoming) {
                handleConnectionError(ErrorOrigin.Quic(e))
                return@coroutineScope
            }
            launch { handleUniStream(stream) }
        }
    }

    /** Reads a peer unidirectional stream's type and dispatches it (`AcceptRecvStream::poll_type`, `into_stream`). */
    private suspend fun handleUniStream(stream: RecvStream) {
        val buf = Buffer()
        val header = StreamTypeDecoder()
        while (!header.decode(buf)) {
            val chunk = try {
                stream.read()
            } catch (e: StreamErrorIncoming) {
                if (e is StreamErrorIncoming.ConnectionLost) handleConnectionError(ErrorOrigin.Quic(e.connectionError))
                // RFC 9114 §6.2: a receiver MUST tolerate unidirectional streams being closed or reset prior to the
                // reception of the unidirectional stream header.
                return
            } ?: return
            buf.writeBytes(chunk)
        }
        when (header.type) {
            StreamType.CONTROL -> {
                // RFC 9114 §6.2.1: only one control stream per peer; a second is H3_STREAM_CREATION_ERROR.
                if (gotControl) return fail(Code.H3_STREAM_CREATION_ERROR, "got two control streams")
                gotControl = true
                readControl(stream, buf)
            }
            StreamType.ENCODER -> {
                // RFC 9204 §4.2: a second encoder or decoder stream is H3_STREAM_CREATION_ERROR.
                if (gotEncoder) return fail(Code.H3_STREAM_CREATION_ERROR, "got two encoder streams")
                gotEncoder = true
                val receiver = EncoderStreamReceiver()
                readQpackStream(stream, buf, "encoder") { receiver.receive(it) }
            }
            StreamType.DECODER -> {
                if (gotDecoder) return fail(Code.H3_STREAM_CREATION_ERROR, "got two decoder streams")
                gotDecoder = true
                val receiver = DecoderStreamReceiver()
                readQpackStream(stream, buf, "decoder") { receiver.receive(it) }
            }
            // ⚖️ The reference drops a push stream silently. Push is not in the first version (SPEC §5):
            // RFC 9114 §6.2.2: a client-initiated push stream is H3_STREAM_CREATION_ERROR; §4.6: a push ID above the
            // client's maximum (it never sends MAX_PUSH_ID, so every push ID) is H3_ID_ERROR.
            StreamType.PUSH ->
                if (side == Side.Server) {
                    fail(Code.H3_STREAM_CREATION_ERROR, "client opened a push stream")
                } else {
                    fail(Code.H3_ID_ERROR, "push stream with push ID ${header.pushId} but no MAX_PUSH_ID was sent")
                }
            // RFC 9114 §6.2: unknown stream types (GREASE included) are aborted with H3_STREAM_CREATION_ERROR; never
            // a connection error.
            else -> stream.stopSending(Code.H3_STREAM_CREATION_ERROR.value)
        }
    }

    private fun fail(code: Code, message: String) {
        handleConnectionError(code, message)
    }

    /**
     * Reads the peer's control stream (`poll_control`): SETTINGS must come first (else H3_MISSING_SETTINGS) and only
     * once (else H3_FRAME_UNEXPECTED); DATA, HEADERS and PUSH_PROMISE are H3_FRAME_UNEXPECTED; GOAWAY, CANCEL_PUSH and
     * MAX_PUSH_ID go to [onControlFrame]; the end or a reset of the stream is H3_CLOSED_CRITICAL_STREAM.
     */
    private suspend fun readControl(stream: RecvStream, leftover: Buffer) {
        val frames = FrameStream(config.maxHeadersFrameSize)
        frames.buffer.writeBytes(leftover.backingArray(), leftover.readerIndex(), leftover.readableBytes)
        while (true) {
            val frame = try {
                frames.nextFrame()
            } catch (e: FrameException) {
                return fail(controlFrameError(e))
            }
            if (frame == null) {
                if (frames.isFinished) return fail(Code.H3_CLOSED_CRITICAL_STREAM, "control stream was closed")
                if (!readInto(stream, frames, "control")) return
                continue
            }
            val error = when {
                frame is neton.http.h3.proto.Settings ->
                    if (!gotPeerSettings) {
                        gotPeerSettings = true
                        shared.setSettings(Settings.fromFrame(frame))
                        null
                    } else {
                        // RFC 9114 §7.2.4: a second SETTINGS frame is H3_FRAME_UNEXPECTED.
                        InternalConnectionError(Code.H3_FRAME_UNEXPECTED, "second settings frame received")
                    }
                // RFC 9114 §6.2.1: the first frame must be SETTINGS.
                !gotPeerSettings -> InternalConnectionError(Code.H3_MISSING_SETTINGS, "received frame $frame before settings")
                frame is Frame.Goaway || frame is Frame.CancelPush || frame is Frame.MaxPushId -> onControlFrame(frame)
                // RFC 9114 §7.2.1, §7.2.2, §7.2.5: DATA, HEADERS, PUSH_PROMISE on the control stream.
                else -> InternalConnectionError(Code.H3_FRAME_UNEXPECTED, "received unexpected frame $frame on control stream")
            }
            if (error != null) return fail(error)
        }
    }

    /**
     * A frame error on the control stream. ⚖️ A HEADERS frame over `maxHeadersFrameSize` is refused from its frame
     * header (H3_EXCESSIVE_LOAD in phase A); on the control stream HEADERS is not allowed at all, so it is reported as
     * the reference reports any HEADERS there: H3_MISSING_SETTINGS before SETTINGS, H3_FRAME_UNEXPECTED after.
     */
    private fun controlFrameError(e: FrameException): InternalConnectionError {
        if (e.error is FrameError.HeadersTooLarge) {
            return if (gotPeerSettings) {
                InternalConnectionError(Code.H3_FRAME_UNEXPECTED, "received HEADERS on control stream")
            } else {
                InternalConnectionError(Code.H3_MISSING_SETTINGS, "received HEADERS before settings")
            }
        }
        return InternalConnectionError.of(e)
    }

    private fun fail(error: InternalConnectionError) {
        handleConnectionError(ErrorOrigin.Internal(error))
    }

    /**
     * Reads the next chunk of a critical stream into [frames]; false once the connection error is recorded (a reset
     * of the stream is H3_CLOSED_CRITICAL_STREAM).
     */
    private suspend fun readInto(stream: RecvStream, frames: FrameStream, name: String): Boolean {
        val chunk = readCritical(stream, name) ?: return false
        if (chunk === END) frames.onEnd() else frames.onData(chunk)
        return true
    }

    /** The next chunk of a critical stream, [END] at its end, or null once the resulting connection error is recorded. */
    private suspend fun readCritical(stream: RecvStream, name: String): neton.io.bytes.Bytes? {
        try {
            return stream.read() ?: END
        } catch (e: StreamErrorIncoming) {
            when (e) {
                is StreamErrorIncoming.ConnectionLost -> handleConnectionError(ErrorOrigin.Quic(e.connectionError))
                is StreamErrorIncoming.StreamTerminated ->
                    fail(Code.H3_CLOSED_CRITICAL_STREAM, "$name stream was reset with error code ${e.errorCode}")
                is StreamErrorIncoming.Unknown ->
                    fail(Code.H3_CLOSED_CRITICAL_STREAM, "an error occurred on the $name stream ${e.error}")
            }
            return null
        }
    }

    /**
     * Reads a peer QPACK stream and checks its instructions with [receive] (phase A's [EncoderStreamReceiver] /
     * [DecoderStreamReceiver]): an invalid instruction is a connection error of QPACK_ENCODER_STREAM_ERROR /
     * QPACK_DECODER_STREAM_ERROR; the end or a reset of the stream is H3_CLOSED_CRITICAL_STREAM (RFC 9204 §4.2).
     */
    private suspend fun readQpackStream(stream: RecvStream, buf: Buffer, name: String, receive: (Buffer) -> Unit) {
        while (true) {
            try {
                receive(buf)
            } catch (e: H3Exception) {
                return fail(e.code, "invalid instruction on the $name stream: ${e.message}")
            }
            val chunk = readCritical(stream, name) ?: return
            if (chunk === END) return fail(Code.H3_CLOSED_CRITICAL_STREAM, "$name stream was closed")
            buf.writeBytes(chunk)
        }
    }

    /**
     * ⚖️ Watches a local critical stream: the peer asking to stop it (STOP_SENDING) closes it, which is
     * H3_CLOSED_CRITICAL_STREAM (RFC 9114 §6.2.1: "the receiver MUST NOT request that the sender close the control
     * stream"; RFC 9204 §4.2). The reference notices this only when it next writes to the stream.
     */
    private suspend fun watchCritical(stream: SendStream, name: String) {
        val code = try {
            stream.stopped()
        } catch (e: StreamErrorIncoming) {
            if (e is StreamErrorIncoming.ConnectionLost) handleConnectionError(ErrorOrigin.Quic(e.connectionError))
            return
        } ?: return
        fail(Code.H3_CLOSED_CRITICAL_STREAM, "$name stream was requested to stop sending with error code $code")
    }

    /**
     * Sends one GREASE stream (`poll_grease_stream`, RFC 9114 §6.2.3): a reserved stream type and a reserved frame,
     * then FIN. Failures are ignored.
     */
    private suspend fun sendGreaseStream() {
        runCatchingStream {
            val s = conn.openUni()
            s.writeBuf(WriteBuf.of(StreamType.grease(), Frame.Grease))
            s.finish()
        }
    }

    companion object {
        private val END = neton.io.bytes.Bytes.wrap(ByteArray(0))

        /**
         * Opens the control and QPACK streams and sends their headers (`ConnectionInner::new`, RFC 9114 §6.2: the
         * control stream and the streams of mandatory extensions first). The QPACK streams are optional, as in the
         * reference: failing to open one is not an error.
         * @throws ConnectionError
         */
        suspend fun create(conn: Connection, config: Config, side: Side): ConnectionInner {
            val control = try {
                conn.openUni()
            } catch (e: StreamErrorIncoming) {
                throw rawError(conn, e)
            }
            val encoder = try { conn.openUni() } catch (_: StreamErrorIncoming) { null }
            val decoder = try { conn.openUni() } catch (_: StreamErrorIncoming) { null }
            val shared = SharedState { code, reason -> conn.close(code, reason.encodeToByteArray()) }
            val inner = ConnectionInner(conn, shared, config, side, control, encoder, decoder)
            inner.sendControlStreamHeaders()
            return inner
        }

        /** An error opening the control stream, before the connection exists (`CloseRawQuicConnection`). */
        private fun rawError(conn: Connection, e: StreamErrorIncoming): ConnectionError = when (e) {
            is StreamErrorIncoming.ConnectionLost -> when (val c = e.connectionError) {
                is ConnectionErrorIncoming.Timeout -> ConnectionError.Timeout()
                is ConnectionErrorIncoming.InternalError -> {
                    conn.close(Code.H3_INTERNAL_ERROR, c.reason.encodeToByteArray())
                    ConnectionError.Local(LocalError.Application(Code.H3_INTERNAL_ERROR, c.reason))
                }
                else -> ConnectionError.Remote(c)
            }
            is StreamErrorIncoming.StreamTerminated -> closeRaw(
                conn, Code.H3_CLOSED_CRITICAL_STREAM,
                "control stream was requested to stop sending with error code ${e.errorCode}",
            )
            is StreamErrorIncoming.Unknown ->
                closeRaw(conn, Code.H3_CLOSED_CRITICAL_STREAM, "an error occurred on the control stream ${e.error}")
        }

        private fun closeRaw(conn: Connection, code: Code, reason: String): ConnectionError {
            conn.close(code, reason.encodeToByteArray())
            return ConnectionError.Local(LocalError.Application(code, reason))
        }
    }
}

/** Sends everything in [buf] (`stream::write`). @throws StreamErrorIncoming */
internal suspend fun SendStream.writeBuf(buf: WriteBuf) {
    while (buf.remaining > 0) {
        val chunk = buf.chunk()
        write(chunk)
        buf.advance(chunk.size)
    }
}

/** Runs [block], ignoring a failed stream operation. */
private inline fun runCatchingStream(block: () -> Unit) {
    try {
        block()
    } catch (_: StreamErrorIncoming) {
    }
}
