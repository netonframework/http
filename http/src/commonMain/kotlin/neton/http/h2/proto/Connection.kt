package neton.http.h2.proto

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import neton.http.h2.codec.Codec
import neton.http.h2.codec.UserError
import neton.http.h2.frame.Data
import neton.http.h2.frame.GoAway
import neton.http.h2.frame.Headers
import neton.http.h2.frame.Ping
import neton.http.h2.frame.Priority
import neton.http.h2.frame.PushPromise
import neton.http.h2.frame.Reason
import neton.http.h2.frame.Reset
import neton.http.h2.frame.Settings
import neton.http.h2.frame.StreamId
import neton.http.h2.frame.WindowUpdate
import neton.io.bytes.Buffer
import neton.io.bytes.Bytes
import neton.io.core.ClosedException
import neton.io.core.IoException
import neton.io.core.IoStream
import neton.io.core.StreamCapability
import kotlin.coroutines.resume

/**
 * An HTTP/2 connection over an [IoStream] (`proto::Connection`, `src/proto/connection.rs`): the frame codec, the
 * connection-level state (SETTINGS, PING, GOAWAY) and the [Streams].
 *
 * ## Runtime shape (⚖️)
 *
 * The reference is one `poll` state machine driven by a single task: it reads frames until the transport is not
 * ready, then writes what the streams queued (`poll_complete`), woken by the transport or by the user handles. Here
 * [run] drives the connection with two coroutines on the connection's reactor thread, sharing the state without
 * locks (each step is synchronous, as a critical section of the reference is):
 *
 * - the **driver** (the coroutine calling [run]) is the reference's `poll` without the frame reading: it buffers
 *   pending GOAWAY / PONG / PING / SETTINGS (ACK) / refused-stream frames and the streams' frames into the codec,
 *   writes them to the stream (a DATA payload of at least 256 bytes is not copied: it goes out with the frame
 *   headers in one vectored write), decides when the connection closes, and parks until woken (the reference's
 *   `task.wake()`: a handle queued a frame, released capacity, dropped its stream...);
 * - the **reader** (a child coroutine) is the frame reading of `poll2`: it reads from the stream into the read
 *   buffer, decodes frames and applies them to the state machine, then wakes the user coroutines waiting on the
 *   affected streams. Before reading the next frame it waits for the driver to buffer the control frames the last
 *   one required (a SETTINGS ACK, a PONG, a refusal), as the reference does in `poll_ready`.
 *
 * Reading and writing are thus independent (a blocked write never stops frame processing, a blocked read never
 * stops writes), no coroutine is launched per frame or per stream, and a frame costs no allocation beyond the frame
 * itself.
 */
internal class Connection(
    private val io: IoStream,
    val codec: Codec,
    /** Bytes already read (after the server's preface check). */
    private val readBuf: Buffer,
    config: ConnConfig,
    val peer: Peer,
) {
    val streams: Streams

    private var state = STATE_OPEN
    private var closeReason = Reason.NO_ERROR
    private var closeInitiator = Initiator.Library

    /** A GOAWAY received from the peer, reported once complete (`error`). */
    private var error: GoAway? = null

    val goAway = GoAwayState()
    val pingPong = PingPongState()
    val settings = SettingsState(config.settings)

    // The driver's wake-up (the reference's connection task).
    private var driverWoken = false
    private var driverWaiter: CancellableContinuation<Unit>? = null

    // The reader waits here for the driver to buffer the control frames it made pending.
    private val readerGate = WaitSlot()

    /** An error that ends the connection at once (an I/O error), set by the reader. */
    private var fatal: ProtoError? = null

    /** Done: the connection ended, with [outcome] (null when clean). */
    var isFinished = false
        private set
    var outcome: ProtoError? = null
        private set
    private val finishWaiters = WaitSlot()

    private var started = false

    init {
        streams = Streams(
            peer,
            StreamsConfig(
                initialMaxSendStreams = config.initialMaxSendStreams,
                localMaxBufferSize = config.maxSendBufferSize,
                localNextStreamId = config.nextStreamId,
                localPushEnabled = config.settings.isPushEnabled ?: true,
                extendedConnectProtocolEnabled = config.settings.isExtendedConnectProtocolEnabled ?: false,
                localResetDuration = config.resetStreamDuration,
                localResetMax = config.resetStreamMax,
                remoteResetMax = config.remoteResetStreamMax,
                remoteInitWindowSz = neton.http.h2.frame.DEFAULT_INITIAL_WINDOW_SIZE,
                remoteMaxInitiated = config.settings.maxConcurrentStreams?.let { if (it > Int.MAX_VALUE) Int.MAX_VALUE else it.toInt() },
                localMaxErrorResetStreams = config.localErrorResetStreamsMax,
                dataFrameBudget = config.dataFrameBudget,
            ),
        )
        streams.task = Task { wakeDriver() }
        streams.recv.onIncoming = { acceptWaiters.wake() }
    }

    // ===== operations from the user handles =====

    /** Connection flow control: the target receive window (`set_target_window_size`). */
    fun setTargetWindowSize(size: Int) {
        streams.setTargetConnectionWindowSize(size)
    }

    /** Sends new SETTINGS with the initial window size (`set_initial_window_size`). */
    fun setInitialWindowSize(size: Int): UserError? {
        val s = Settings()
        s.initialWindowSize = size.toLong()
        return settings.sendSettings(s).also { if (it == null) wakeDriver() }
    }

    /** Sends new SETTINGS enabling extended CONNECT (`set_enable_connect_protocol`). */
    fun setEnableConnectProtocol(): UserError? {
        val s = Settings()
        s.enableConnectProtocol = 1
        return settings.sendSettings(s).also { if (it == null) wakeDriver() }
    }

    val maxSendStreams: Int get() = streams.maxSendStreams

    val maxRecvStreams: Int get() = streams.maxRecvStreams

    fun takeUserPings(): UserPings? = pingPong.takeUserPings(Task { wakeDriver() })

    /** An abrupt shutdown by the user (`go_away_from_user`). */
    fun goAwayFromUser(e: Reason) {
        val frame = GoAway(streams.lastProcessedId, e)
        goAway.goAwayFromUser(frame)
        // Tell every stream why the connection closes.
        streams.handleError(ProtoError.userGoAway(e))
        wakeDriver()
    }

    /** A graceful shutdown (`go_away_gracefully`; servers only). */
    fun goAwayGracefully() {
        if (goAway.isGoingAway) return
        // RFC 9113 §6.8: first a GOAWAY with the last stream ID 2^31 - 1 and NO_ERROR, then, after at least one round
        // trip (the reference waits for the pong of a PING), another with the real last stream ID.
        goAwayFrame(StreamId.MAX, Reason.NO_ERROR)
        pingPong.pingShutdown()
        wakeDriver()
    }

    fun hasStreams(): Boolean = streams.hasStreams()

    fun hasStreamsOrOtherReferences(): Boolean = streams.hasStreamsOrOtherReferences()

    /** Neither closing nor closed (`State::Open`). */
    val isOpen: Boolean get() = state == STATE_OPEN

    /** Waits until the connection ended; returns its error or null. */
    suspend fun awaitFinished(): ProtoError? {
        while (!isFinished) finishWaiters.await()
        return outcome
    }

    /** Coroutines waiting in `accept`: woken when a stream is queued for accepting, and when the connection ends. */
    val acceptWaiters = WaitSlot()

    // ===== running =====

    /**
     * Drives the connection until it closes (the reference's `Future for Connection`), then closes the stream.
     * @throws ProtoError the connection error.
     */
    suspend fun run() {
        check(!started) { "the connection is already running" }
        started = true
        var result: ProtoError? = null
        var completed = false
        try {
            coroutineScope {
                val reader = launch(start = CoroutineStart.UNDISPATCHED) { readLoop() }
                val watchdog = settingsAckTimeout?.let { t -> launch { settingsWatchdog(t) } }
                try {
                    result = try {
                        drive()
                    } catch (e: IoException) {
                        // A write failed (`poll_complete` / `shutdown` returning an I/O error).
                        ioError(e)
                    }
                    completed = true
                } finally {
                    reader.cancel()
                    watchdog?.cancel()
                    io.close() // wakes a read parked on the stream
                }
            }
        } finally {
            // `Drop for Connection` when the run is cancelled: every stream gets a broken pipe. A connection that ran to
            // its end already told its streams (EOF or the error), and keeps what is left (the pending-accept streams)
            // as the reference's `Connection` does until dropped.
            if (!completed) streams.recvEof(true)
            pingPong.close()
            io.close()
            outcome = result
            isFinished = true
            finishWaiters.wake()
            acceptWaiters.wake()
        }
        result?.let { throw it }
    }

    private val settingsAckTimeout: kotlin.time.Duration? = config.settingsAckTimeout
    private val settingsSent = WaitSlot()

    /**
     * ⚖️ SETTINGS_TIMEOUT (RFC 9113 §6.5.3, SPEC §4.3): our SETTINGS not acknowledged within [timeout] is a
     * connection error. The reference has no such timeout; the option is off by default.
     */
    private suspend fun settingsWatchdog(timeout: kotlin.time.Duration) {
        settings.onSent = { settingsSent.wake() }
        while (true) {
            val since = settings.waitingAckSince
            if (since < 0) {
                settingsSent.await()
                continue
            }
            val left = timeout.inWholeNanoseconds - (monotonicNanos() - since)
            if (left > 0) {
                kotlinx.coroutines.delay((left + 999_999) / 1_000_000)
                continue
            }
            if (state == STATE_OPEN) {
                handlePoll2Result(ProtoError.libraryGoAway(Reason.SETTINGS_TIMEOUT))?.let { fatal = it }
                wakeDriver()
            }
            return
        }
    }

    private fun wakeDriver() {
        driverWoken = true
        val w = driverWaiter ?: return
        driverWaiter = null
        if (w.isActive) w.resume(Unit)
    }

    private suspend fun parkDriver() {
        if (!driverWoken) suspendCancellableCoroutine { c -> driverWaiter = c }
        driverWoken = false
    }

    /**
     * The driver is writing the codec's bytes to the stream (it is suspended in a write or flush): nothing else may
     * touch the codec's write side meanwhile. Otherwise the codec is idle between two synchronous steps, and the
     * reader may buffer frames into it (see [readLoop]).
     */
    private var writing = false

    /** The reference's `poll` loop, without the frame reading. Returns the connection error, or null. */
    private suspend fun drive(): ProtoError? {
        while (true) {
            when (state) {
                STATE_OPEN -> {
                    driverWoken = false
                    fatal?.let { return it }
                    // The client closes once no stream and no handle is left (`maybe_close_connection_if_no_streams`).
                    if (peer == Peer.Client && !streams.hasStreamsOrOtherReferences()) goAwayNow(Reason.NO_ERROR)

                    streams.clearExpiredResetStreams()
                    when (bufferControl()) {
                        CONTROL_FULL -> {
                            flushCodec()
                            continue
                        }
                        CONTROL_CHANGED -> continue
                    }
                    // The reader may read the next frame.
                    readerGate.wake()

                    // Write what the streams queued (`poll_complete`).
                    val status = streams.bufferPending(codec)
                    flushCodec()
                    if (status == BufferStatus.CodecFull || streams.reclaimWrittenFrame(codec)) continue

                    // A GOAWAY was received or sent: close once the streams are done.
                    if ((error != null || goAway.shouldCloseOnIdle) && !streams.hasStreams()) {
                        goAwayNow(Reason.NO_ERROR)
                        continue
                    }
                    if (state == STATE_OPEN && fatal == null) parkDriver()
                }
                STATE_CLOSING -> {
                    // Flush, then shut the write side.
                    flushCodec()
                    writing = true
                    try {
                        if (StreamCapability.HalfClose in io.capabilities) io.shutdownOutput()
                    } finally {
                        writing = false
                    }
                    state = STATE_CLOSED
                }
                else -> return takeError(closeReason, closeInitiator)
            }
        }
    }

    /**
     * The start of `poll2`, synchronous: buffers a pending GOAWAY and, when the connection should close now, applies
     * the result (none for a user's abrupt shutdown, else a GOAWAY error); then buffers the pending PONG, PING,
     * SETTINGS ACK / SETTINGS and refusal (`poll_ready`). Returns [CONTROL_READY], [CONTROL_FULL] (the codec must be
     * flushed first) or [CONTROL_CHANGED] (the connection state changed). Only while not [writing].
     */
    private fun bufferControl(): Int {
        try {
            if (goAway.pending != null && !codec.hasSendCapacity()) return CONTROL_FULL
            val reason = goAway.sendPendingGoAway(codec)
            if (reason != null && goAway.shouldCloseNow) {
                // A user's abrupt shutdown does not report its own error back.
                handlePoll2Result(if (goAway.isUserInitiated) null else ProtoError.libraryGoAway(reason))?.let { fatal = it }
                return CONTROL_CHANGED
            }
            // (Only a graceful NO_ERROR GOAWAY waits for idle.)
            if (!pingPong.sendPendingPong(codec)) return CONTROL_FULL
            if (!pingPong.sendPendingPing(codec)) return CONTROL_FULL
            if (!settings.pollSend(codec, streams)) return CONTROL_FULL
            if (streams.recv.sendPendingRefusal(codec) == BufferStatus.CodecFull) return CONTROL_FULL
            return CONTROL_READY
        } catch (e: ProtoError) {
            // From applying the peer's settings.
            handlePoll2Result(e)?.let { fatal = it }
            return CONTROL_CHANGED
        }
    }

    // Two segments for vectored writes: the frame bytes and a borrowed DATA payload.
    private val payloadWrapper = Buffer(16)
    private val segments = arrayOf(codec.writer.writeBuffer, payloadWrapper)

    /**
     * Writes everything the codec holds (`FramedWrite::flush`), including the CONTINUATION frames of a large header
     * block, then flushes the stream.
     */
    private suspend fun flushCodec() {
        val fw = codec.writer
        var wrote = false
        writing = true
        try {
            while (true) {
                if (!fw.isEmpty) {
                    val payload = fw.queuedPayload
                    if (payload != null && payload.size > 0) {
                        payloadWrapper.borrow(payload)
                        try {
                            io.writev(segments, 2)
                        } finally {
                            payloadWrapper.borrow(Bytes.EMPTY) // do not keep the payload alive
                        }
                        fw.advance(payload.size)
                    } else {
                        io.write(fw.writeBuffer)
                    }
                    wrote = true
                }
                if (!fw.unsetFrame()) break
            }
            if (wrote) io.flush()
        } finally {
            writing = false
        }
    }

    /** `handle_poll2_result`: returns an error that ends the connection at once, or null. */
    private fun handlePoll2Result(result: ProtoError?): ProtoError? {
        when (result) {
            // The connection has shut down normally.
            null -> setState(STATE_CLOSING, Reason.NO_ERROR, Initiator.Library)
            // A connection error: GOAWAY, then close.
            is ProtoError.GoAway -> handleGoAway(result.reason, result.debugData, result.initiator)
            // A stream error: local ones are reported with RST_STREAM; the peer's resets were already applied and
            // are not echoed.
            is ProtoError.Reset -> {
                if (result.initiator == Initiator.Remote) return null
                val ga = streams.sendReset(result.streamId, result.reason)
                if (ga is ProtoError.GoAway) handleGoAway(ga.reason, ga.debugData, Initiator.Library)
            }
            // An I/O error: every stream is reset.
            is ProtoError.Io -> {
                streams.handleError(result)
                // A peer closing without notice (hyper issue #3427): a server with nothing more to send, or a client
                // that got GOAWAY(NO_ERROR), closes without error.
                if (streams.sendBuffer.isEmpty && result.kind == IoErrorKind.UnexpectedEof &&
                    (streams.peer.isServer || error?.reason == Reason.NO_ERROR)
                ) {
                    setState(STATE_CLOSED, Reason.NO_ERROR, Initiator.Library)
                    return null
                }
                return result
            }
        }
        return null
    }

    private fun handleGoAway(reason: Reason, debugData: Bytes, initiator: Initiator) {
        // Already going away for this error: just flush and close.
        if (goAway.goingAwayReasonOrNull == reason) {
            setState(STATE_CLOSING, reason, initiator)
            return
        }
        // Reset all active streams.
        streams.handleError(ProtoError.GoAway(debugData, reason, initiator))
        goAway.goAwayNow(GoAway(streams.lastProcessedId, reason, debugData))
    }

    private fun goAwayFrame(id: StreamId, e: Reason) {
        streams.sendGoAway(id)
        goAway.goAway(GoAway(id, e))
    }

    private fun goAwayNow(e: Reason) {
        goAway.goAwayNow(GoAway(streams.lastProcessedId, e))
    }

    private fun setState(s: Int, reason: Reason, initiator: Initiator) {
        state = s
        closeReason = reason
        closeInitiator = initiator
    }

    /** The result once closed (`take_error`): our error, unless the peer reported one (then theirs). */
    private fun takeError(ours: Reason, initiator: Initiator): ProtoError? {
        val frame = error
        error = null
        val theirs = frame?.reason ?: Reason.NO_ERROR
        return when {
            ours == Reason.NO_ERROR && theirs == Reason.NO_ERROR -> null
            theirs == Reason.NO_ERROR -> ProtoError.GoAway(Bytes.EMPTY, ours, initiator)
            // Both reported an error: ours was probably caused by theirs.
            else -> ProtoError.remoteGoAway(frame!!.debugData, theirs)
        }
    }

    // ===== the reader =====

    /** Frames must be buffered by the driver before the next frame is read (`poll_go_away` / `poll_ready`). */
    private fun readerMustWait(): Boolean =
        goAway.pending != null || pingPong.hasPending || settings.hasPending || streams.recv.refused != 0

    private fun readerMustStop(): Boolean = state != STATE_OPEN || goAway.shouldCloseNow || fatal != null

    /**
     * The frame reading of `poll2`. As in the reference, where one task reads a batch of frames and then buffers
     * what they caused before any user task runs, the reader buffers the control frames due before the next frame
     * (PONG, SETTINGS ACK, GOAWAY...) and, after a batch, the streams' frames (RST_STREAM, WINDOW_UPDATE...) into the
     * codec itself when the driver is not writing; the driver only has to write them. This keeps the reference's
     * frame order: user coroutines woken by the batch run after its frames are buffered.
     */
    private suspend fun readLoop() {
        var eof = false
        // Frames were applied since the last read: the reference's `poll_complete` runs once the transport has no
        // more data, not before the first frames are read.
        var processed = false
        while (true) {
            // Once per round, like the reference's poll2 (not per frame: the clock is read once).
            streams.clearExpiredResetStreams()
            while (true) {
                if (readerMustStop()) return
                if (!readerMustWait()) break
                if (!writing) {
                    val r = bufferControl()
                    wakeDriver() // to write them
                    if (r != CONTROL_FULL && !readerMustWait()) continue
                } else {
                    wakeDriver()
                }
                readerGate.await()
            }

            val frame = try {
                if (eof) codec.decodeEof(readBuf) else codec.decode(readBuf)
            } catch (e: ProtoError) {
                readerResult(e)
                continue
            }
            if (frame == null) {
                if (eof) {
                    // The codec is closed: every stream gets EOF, and the connection is done.
                    streams.recvEof(false)
                    readerResult(null)
                    return
                }
                // Nothing more to decode: buffer what these frames caused (`poll_complete`), let the driver write it,
                // then read more.
                if (processed && !writing && state == STATE_OPEN && !readerMustWait() && codec.hasSendCapacity()) {
                    streams.bufferPending(codec)
                }
                processed = false
                if (driverHasWork()) wakeDriver()
                val n = try {
                    io.read(readBuf)
                } catch (e: IoException) {
                    if (e is ClosedException && readerMustStop()) return
                    readerResult(ioError(e))
                    continue
                }
                if (n < 0) eof = true
                continue
            }
            processed = true
            try {
                recvFrame(frame)
            } catch (e: ProtoError) {
                readerResult(e)
            }
        }
    }

    /** Whether the driver should look at the connection after a batch of frames. */
    private fun driverHasWork(): Boolean =
        !codec.writer.isEmpty || streams.hasPendingWrites() || error != null || goAway.isGoingAway ||
            peer == Peer.Client && !streams.hasStreamsOrOtherReferences()

    /** A result of the reader's `poll2` (null: EOF); applied at once, the driver finishes the job. */
    private fun readerResult(r: ProtoError?) {
        val e = handlePoll2Result(r)
        if (e != null) fatal = e
        wakeDriver()
    }

    /** Applies one received frame (`recv_frame`). @throws ProtoError */
    private fun recvFrame(frame: neton.http.h2.frame.Frame) {
        when (frame) {
            is Headers -> streams.recvHeaders(frame)
            is Data -> streams.recvData(frame)
            is Reset -> streams.recvReset(frame)
            is PushPromise -> streams.recvPushPromise(frame)
            is Settings -> settings.recvSettings(frame, codec, streams)
            is GoAway -> {
                // No new streams from now on; the current ones continue until they end, then the connection closes.
                streams.recvGoAway(frame)
                error = frame
            }
            is Ping -> {
                if (pingPong.recvPing(frame) == ReceivedPing.Shutdown) {
                    check(goAway.isGoingAway) { "received unexpected shutdown ping" }
                    goAwayFrame(streams.lastProcessedId, Reason.NO_ERROR)
                }
            }
            is WindowUpdate -> streams.recvWindowUpdate(frame)
            is Priority -> {} // TODO in the reference: handle
        }
    }

    private companion object {
        /** An I/O error of the stream as a [ProtoError.Io]. */
        fun ioError(e: IoException): ProtoError = ProtoError.Io(
            when (e) {
                is neton.http.h2.UnexpectedEofException -> IoErrorKind.UnexpectedEof
                is ClosedException -> IoErrorKind.BrokenPipe
                else -> IoErrorKind.Other
            },
            e.message,
        )

        const val STATE_OPEN = 0
        const val STATE_CLOSING = 1
        const val STATE_CLOSED = 2

        const val CONTROL_READY = 0
        const val CONTROL_FULL = 1
        const val CONTROL_CHANGED = 2
    }
}
