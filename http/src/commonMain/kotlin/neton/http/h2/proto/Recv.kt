package neton.http.h2.proto

import neton.http.Request
import neton.http.Response
import neton.http.StatusCode
import neton.http.h2.codec.Codec
import neton.http.h2.codec.UserError
import neton.http.h2.frame.DEFAULT_INITIAL_WINDOW_SIZE
import neton.http.h2.frame.Data
import neton.http.h2.frame.Headers
import neton.http.h2.frame.Pseudo
import neton.http.h2.frame.PushPromise
import neton.http.h2.frame.Reason
import neton.http.h2.frame.Reset
import neton.http.h2.frame.Settings
import neton.http.h2.frame.StreamId
import neton.http.h2.frame.WindowUpdate
import neton.http.h2.frame.parseU64
import neton.http.header.HeaderMap
import neton.http.header.HeaderName
import neton.http.header.HeaderValue
import neton.io.bytes.Bytes

// Kinds of the events queued on a stream for the user (`Event`).
internal const val EV_HEADERS = 0
internal const val EV_DATA = 1
internal const val EV_TRAILERS = 2
internal const val EV_INFORMATIONAL = 3

/**
 * An oversized header block (`RecvHeaderBlockError::Oversize`): on the server the stream is answered with [response]
 * (431) and then refused; otherwise it is reset.
 */
internal class OversizeHeaders(val response: Headers?) : Exception("header block over size")

/**
 * State transitions caused by inbound frames (`Recv`, `src/proto/streams/recv.rs`): receive flow control, the accept
 * queue, pending WINDOW_UPDATEs, locally reset streams kept for a while, refused streams.
 *
 * Events for the user are queued on the stream as [Slot]s of a shared [Buffer]: the kind, the value (a message head,
 * a DATA payload or trailers) and, for DATA, whether the payload counts against the DATA frame budget.
 */
internal class Recv(peer: Peer, config: StreamsConfig) {
    /** The initial window of remote-initiated streams (ours, once acknowledged). */
    var initWindowSz: Int = DEFAULT_INITIAL_WINDOW_SIZE
        private set

    /** Connection-level receive flow control. */
    val flow = FlowControl()

    /** Connection window used by the streams' unreleased data. */
    var inFlightData: Int = 0
        internal set

    /** The lowest stream ID still idle, or [STREAM_ID_OVERFLOW]. */
    private var nextStreamId: Int = if (peer.isServer) 1 else 2

    /** The ID of the last stream processed. */
    var lastProcessedId: StreamId = StreamId.ZERO
        private set

    /** Streams with a higher ID are ignored; lowered when a GOAWAY is sent. */
    var maxStreamId: StreamId = StreamId.MAX
        private set

    /** Streams with WINDOW_UPDATEs to send. */
    val pendingWindowUpdates = Queue(NextWindowUpdate)

    /** New streams waiting to be accepted. */
    private val pendingAccept = Queue(NextAccept)

    /** Locally reset streams, reaped once they expire. */
    private val pendingResetExpired = Queue(NextResetExpire)

    /** How long locally reset streams keep ignoring frames. */
    private val resetDurationNanos: Long = config.localResetDuration.inWholeNanoseconds

    /** The events waiting to be read, for all streams. */
    val buffer = Buffer()

    /** A refused stream whose RST_STREAM must be sent, or 0. */
    var refused: Int = 0
        private set

    private val isPushEnabled: Boolean = config.localPushEnabled

    private var isExtendedConnectProtocolEnabled: Boolean = config.extendedConnectProtocolEnabled

    /** Called when a stream is pushed on the accept queue (wakes `accept`). */
    var onIncoming: () -> Unit = {}

    init {
        // A connection always starts with the default window, whatever the settings.
        flow.incWindow(DEFAULT_INITIAL_WINDOW_SIZE)
        flow.assignCapacity(DEFAULT_INITIAL_WINDOW_SIZE)
    }

    /**
     * A remote stream is opening (`open`): returns its ID, or null when it is refused (concurrency limit; the
     * RST_STREAM REFUSED_STREAM is pending in [refused]).
     * @throws ProtoError a GOAWAY PROTOCOL_ERROR for an ID the peer may not open or lower than the next one.
     */
    fun open(id: StreamId, mode: Open, counts: Counts): StreamId? {
        check(refused == 0)
        counts.peer.ensureCanOpen(id, mode)
        val next = nextStreamIdOrThrow()
        if (id.value < next) throw ProtoError.libraryGoAway(Reason.PROTOCOL_ERROR) // "id < next_id"
        nextStreamId = nextIdOrOverflow(id)
        if (!counts.canIncNumRecvStreams()) {
            refused = id.value
            return null
        }
        return id
    }

    /**
     * The stream received its (non-trailer) HEADERS (`recv_headers`).
     * @throws OversizeHeaders when the header block was over SETTINGS_MAX_HEADER_LIST_SIZE.
     * @throws ProtoError a stream or connection error.
     */
    fun recvHeaders(frame: Headers, stream: Stream, counts: Counts) {
        val isInitial = stream.state.recvOpen(frame)

        // 1xx responses leave a remotely reserved stream ReservedRemote, so `recv_open` reports each as initial:
        // count the stream once.
        if (isInitial && !stream.isCounted) {
            if (frame.streamId > lastProcessedId) lastProcessedId = frame.streamId
            counts.incNumRecvStreams(stream)
        }

        if (stream.contentLength != ContentLength.HEAD) {
            val cl = frame.fields[HeaderName.CONTENT_LENGTH]
            if (cl != null) {
                val v = parseU64(cl.array, cl.offset, cl.length)
                    ?: throw ProtoError.libraryReset(stream.id, Reason.PROTOCOL_ERROR) // "could not parse content-length"
                stream.contentLength = ContentLength.REMAINING
                stream.contentLengthRemaining = v
                // END_STREAM on HEADERS with a non-zero content-length is malformed (RFC 9113 §8.1.1), except 204 / 304.
                val status = frame.pseudo.status
                if (frame.isEndStream && v > 0UL && (status == null || status != StatusCode.NO_CONTENT && status != StatusCode.NOT_MODIFIED)) {
                    throw ProtoError.libraryReset(stream.id, Reason.PROTOCOL_ERROR)
                }
            }
        }

        if (frame.isOverSize) {
            // RFC 9113 §10.5.1: a server may answer an oversized header block with 431. Either way the stream is then
            // reset by the caller, since its DATA frames are not wanted either.
            if (counts.peer.isServer && isInitial) {
                val res = Headers(stream.id, Pseudo.response(StatusCode.REQUEST_HEADER_FIELDS_TOO_LARGE), HeaderMap())
                res.setEndStream()
                throw OversizeHeaders(res)
            }
            throw OversizeHeaders(null)
        }

        val streamId = frame.streamId
        val pseudo = frame.pseudo
        val fields = frame.fields

        if (pseudo.protocol != null && counts.peer.isServer && !isExtendedConnectProtocolEnabled) {
            throw ProtoError.libraryReset(stream.id, Reason.PROTOCOL_ERROR) // ":protocol with extended CONNECT disabled"
        }
        if (pseudo.status != null && counts.peer.isServer) {
            throw ProtoError.libraryReset(stream.id, Reason.PROTOCOL_ERROR) // ":status header in a request"
        }

        val message = counts.peer.convertPollMessage(pseudo, fields, streamId)
        if (!pseudo.isInformational) {
            stream.pendingRecv.pushBack(buffer, message, EV_HEADERS)
            stream.notifyRecv()
            // Only servers receive HEADERS opening a stream (checked by the caller). Never on the accept queue
            // without its HEADERS event.
            if (counts.peer.isServer) {
                pendingAccept.push(stream)
                onIncoming()
            }
        } else {
            // A 1xx response, polled separately.
            stream.pendingRecv.pushBack(buffer, message, EV_INFORMATIONAL)
            stream.notifyRecv()
        }
    }

    /** The request of an accepted stream (`take_request`). */
    @Suppress("UNCHECKED_CAST")
    fun takeRequest(stream: Stream): Request<Unit> {
        val s = stream.pendingRecv.peek()
        check(s != null && s.kind == EV_HEADERS) { "server stream queue must start with Headers" }
        val request = s.value as Request<Unit>
        stream.pendingRecv.removeFront(buffer)
        return request
    }

    /**
     * The next pushed stream of [stream] (`poll_pushed`): the promised request and the stream; null when no more
     * will come; [PENDING] to wait on [Stream.pushTask].
     * @throws ProtoError the stream's error.
     */
    @Suppress("UNCHECKED_CAST")
    fun pollPushed(stream: Stream): Any? {
        val pushed = stream.pendingPushPromises.pop()
        if (pushed != null) {
            val s = pushed.pendingRecv.peek()
            check(s != null && s.kind == EV_HEADERS) { "Headers not set on pushed stream" }
            val request = s.value as Request<Unit>
            pushed.pendingRecv.removeFront(buffer)
            return PushedEvent(request, pushed)
        }
        return if (stream.state.ensureRecvOpen()) PENDING else null
    }

    class PushedEvent(val request: Request<Unit>, val stream: Stream)

    /**
     * The response of [stream] (`poll_response`), skipping 1xx responses not taken by `poll_informational`; [PENDING]
     * to wait on [Stream.recvTask].
     * @throws ProtoError the stream's error, or a stream PROTOCOL_ERROR when it ended without a response.
     */
    @Suppress("UNCHECKED_CAST")
    fun pollResponse(stream: Stream): Any {
        while (true) {
            val s = stream.pendingRecv.peek()
            if (s == null) {
                if (!stream.state.ensureRecvOpen()) {
                    throw ProtoError.libraryReset(stream.id, Reason.PROTOCOL_ERROR) // "stream is not opened"
                }
                return PENDING
            }
            when (s.kind) {
                EV_HEADERS -> {
                    val response = s.value as Response<Unit>
                    stream.pendingRecv.removeFront(buffer)
                    return response
                }
                EV_INFORMATIONAL -> stream.pendingRecv.removeFront(buffer) // to be read with poll_informational
                else -> throw IllegalStateException("poll_response called after response returned")
            }
        }
    }

    /**
     * The next 1xx response of [stream] (`poll_informational`): null once the final response (or anything else) is
     * next or nothing more will come; [PENDING] to wait on [Stream.recvTask].
     */
    @Suppress("UNCHECKED_CAST")
    fun pollInformational(stream: Stream): Any? {
        val s = stream.pendingRecv.peek()
        if (s != null) {
            if (s.kind == EV_HEADERS) return null // the final response stays queued
            if (s.kind == EV_INFORMATIONAL) {
                val response = s.value as Response<Unit>
                stream.pendingRecv.removeFront(buffer)
                return response
            }
            // Not an informational response: it stays at the front.
        }
        return if (stream.state.ensureRecvOpen()) PENDING else null
    }

    /**
     * Trailers ended the stream (`recv_trailers`).
     * @throws ProtoError a stream PROTOCOL_ERROR when content-length bytes are missing; a GOAWAY when the state does
     * not allow it.
     */
    fun recvTrailers(frame: Headers, stream: Stream) {
        stream.state.recvClose()
        if (!stream.ensureContentLengthZero()) {
            throw ProtoError.libraryReset(stream.id, Reason.PROTOCOL_ERROR) // "content-length is not zero"
        }
        stream.pendingRecv.pushBack(buffer, frame.fields, EV_TRAILERS)
        stream.notifyRecv()
    }

    /** Releases connection capacity (`release_connection_capacity`). */
    fun releaseConnectionCapacity(capacity: Int, task: Task) {
        inFlightData -= capacity
        flow.assignCapacity(capacity)
        if (flow.unclaimedCapacity() >= 0) task.wake()
    }

    /** Releases capacity back to the connection and the stream (`release_capacity`). */
    fun releaseCapacity(capacity: Int, stream: Stream, task: Task): UserError? {
        if (capacity > stream.inFlightRecvData) return UserError.ReleaseCapacityTooBig
        releaseConnectionCapacity(capacity, task)
        stream.inFlightRecvData -= capacity
        stream.recvFlow.assignCapacity(capacity)
        if (stream.recvFlow.unclaimedCapacity() >= 0) {
            // Queue the stream for sending the WINDOW_UPDATE frame.
            pendingWindowUpdates.push(stream)
            task.wake()
        }
        return null
    }

    /** Releases a closed stream's unclaimed capacity (`release_closed_capacity`). */
    fun releaseClosedCapacity(stream: Stream, task: Task, counts: Counts) {
        check(stream.refCount == 0)
        if (stream.inFlightRecvData != 0) {
            releaseConnectionCapacity(stream.inFlightRecvData, task)
            stream.inFlightRecvData = 0
        }
        clearRecvBuffer(stream, task, counts)
    }

    /**
     * Sets the target connection window (`set_target_connection_window`): WINDOW_UPDATEs bring the peer's view of the
     * window up to [target] as streams release capacity.
     * @throws FlowControlError on overflow.
     */
    fun setTargetConnectionWindow(target: Int, task: Task) {
        // The current target is what is available plus what streams hold.
        val current = Window.checkedSize(Window.add(flow.available, inFlightData))
        if (target > current) flow.assignCapacity(target - current) else flow.claimCapacity(current - target)
        // Enough new capacity to pass the update threshold: schedule a connection WINDOW_UPDATE.
        if (flow.unclaimedCapacity() >= 0) task.wake()
    }

    /** Applies our acknowledged SETTINGS (`apply_local_settings`). @throws ProtoError a GOAWAY on window overflow. */
    fun applyLocalSettings(settings: Settings, store: Store) {
        settings.isExtendedConnectProtocolEnabled?.let { isExtendedConnectProtocolEnabled = it }
        val t = settings.initialWindowSize ?: return
        val target = t.toInt()
        val old = initWindowSz
        initWindowSz = target
        // RFC 9113 §6.9.2: adjust every stream window by the difference.
        try {
            if (target < old) {
                val dec = old - target
                store.forEach { it.recvFlow.decRecvWindow(dec) }
            } else if (target > old) {
                val inc = target - old
                store.forEach {
                    it.recvFlow.incWindow(inc)
                    it.recvFlow.assignCapacity(inc)
                }
            }
        } catch (e: FlowControlError) {
            throw ProtoError.libraryGoAway(e.reason)
        }
    }

    /** No more events will come and none is queued (`is_end_stream`). */
    fun isEndStream(stream: Stream): Boolean = stream.state.isRecvEndStream && stream.pendingRecv.isEmpty

    /**
     * A DATA frame for the stream (`recv_data`).
     * @throws ProtoError a stream or connection error.
     */
    fun recvData(frame: Data, stream: Stream) {
        // Could include padding.
        val sz = frame.flowControlledLen()
        val isIgnoringFrame = stream.state.isLocalError

        if (!isIgnoringFrame && !stream.state.isRecvStreaming) {
            // Unexpected DATA is a protocol error (sometimes it could be a STREAM_CLOSED stream error: TODO there).
            throw ProtoError.libraryGoAway(Reason.PROTOCOL_ERROR)
        }

        // Locally reset: frames are ignored for some time.
        if (isIgnoringFrame) {
            ignoreData(sz)
            return
        }

        // Enough connection capacity before acting on the stream.
        consumeConnectionWindow(sz)

        if (stream.recvFlow.windowSize < sz) {
            // RFC 9113 §6.9: a stream or connection error of FLOW_CONTROL_ERROR; the reference picks a stream error.
            throw ProtoError.libraryReset(stream.id, Reason.FLOW_CONTROL_ERROR)
        }

        // The payload length (padding is not content).
        if (!stream.decContentLength(frame.payload.size)) {
            throw ProtoError.libraryReset(stream.id, Reason.PROTOCOL_ERROR) // "content-length overflow"
        }

        if (frame.isEndStream) {
            if (!stream.ensureContentLengthZero()) {
                throw ProtoError.libraryReset(stream.id, Reason.PROTOCOL_ERROR) // "content-length underflow"
            }
            try {
                stream.state.recvClose()
            } catch (e: ProtoError) {
                throw ProtoError.libraryGoAway(Reason.PROTOCOL_ERROR)
            }
        }

        // No one wants the data any more (h2 issue #648): give the capacity straight back.
        if (!stream.isRecv) {
            releaseConnectionCapacity(sz, Task.NONE)
            return
        }

        // Stream-level flow control.
        try {
            stream.recvFlow.sendData(sz)
        } catch (e: FlowControlError) {
            throw ProtoError.libraryGoAway(e.reason)
        }

        // The data is in flight until released.
        stream.inFlightRecvData += sz

        // The padding (length byte and padding bytes) is released at once: the user only sees the payload.
        val padding = sz - frame.payload.size
        if (padding > 0) releaseCapacity(padding, stream, Task.NONE)

        // An empty DATA frame without END_STREAM means nothing to the message: no event (padding already released).
        if (frame.payload.size == 0 && !frame.isEndStream) return

        stream.pendingRecv.pushBack(buffer, frame.payload, EV_DATA, !frame.isEndStream)
        stream.notifyRecv()
    }

    /** A DATA frame that is ignored still counts against the connection window, then is released (`ignore_data`). */
    fun ignoreData(sz: Int) {
        consumeConnectionWindow(sz)
        // The user never sees it, so it cannot release it: done here. The WINDOW_UPDATE follows at the threshold.
        releaseConnectionCapacity(sz, Task.NONE)
    }

    /** @throws ProtoError a GOAWAY FLOW_CONTROL_ERROR when the connection window is exceeded. */
    fun consumeConnectionWindow(sz: Int) {
        if (flow.windowSize < sz) throw ProtoError.libraryGoAway(Reason.FLOW_CONTROL_ERROR)
        try {
            flow.sendData(sz)
        } catch (e: FlowControlError) {
            throw ProtoError.libraryGoAway(e.reason)
        }
        inFlightData += sz
    }

    /**
     * A PUSH_PROMISE reserved [stream] (`recv_push_promise`).
     * @throws ProtoError a stream PROTOCOL_ERROR on the promised stream for an oversized or invalid promised request.
     */
    fun recvPushPromise(frame: PushPromise, stream: Stream) {
        stream.state.reserveRemote()
        if (frame.isOverSize) {
            // As for HEADERS; the promised stream is reset with PROTOCOL_ERROR since its DATA is not wanted.
            throw ProtoError.libraryReset(frame.promisedId, Reason.PROTOCOL_ERROR)
        }
        val promisedId = frame.promisedId
        val req = Peer.serverConvertPollMessage(frame.pseudo, frame.fields, promisedId)
        if (PushPromise.validateRequest(req) != null) {
            // Not safe and cacheable, or an invalid content-length.
            throw ProtoError.libraryReset(promisedId, Reason.PROTOCOL_ERROR)
        }
        stream.pendingRecv.pushBack(buffer, req, EV_HEADERS)
        stream.notifyRecv()
        stream.notifyPush()
    }

    /** PROTOCOL_ERROR when remote stream [id] is still idle (implicitly closed ones are fine; `ensure_not_idle`). */
    fun ensureNotIdle(id: StreamId): Reason? {
        val next = nextStreamId
        if (next != STREAM_ID_OVERFLOW && id.value >= next) return Reason.PROTOCOL_ERROR
        return null
    }

    /**
     * The peer reset the stream (`recv_reset`). Streams reset before the user accepted them stay in the accept queue
     * without counting as concurrent streams, so they have their own limit (hyper issue #2877, CVE-2023-44487).
     * @throws ProtoError GOAWAY ENHANCE_YOUR_CALM "too_many_resets" beyond that limit.
     */
    fun recvReset(frame: Reset, stream: Stream, counts: Counts) {
        if (stream.isPendingAccept) {
            if (counts.canIncNumRemoteResetStreams()) {
                counts.incNumRemoteResetStreams()
            } else {
                throw ProtoError.libraryGoAwayData(Reason.ENHANCE_YOUR_CALM, "too_many_resets")
            }
        }
        stream.state.recvReset(frame, stream.isPendingSend)
        stream.notifySend()
        stream.notifyRecv()
        stream.notifyPush()
    }

    /** A connection-level error (`handle_error`). */
    fun handleError(err: ProtoError, stream: Stream) {
        stream.state.handleError(err)
        stream.notifySend()
        stream.notifyRecv()
        stream.notifyPush()
    }

    /** A GOAWAY is being sent: streams above [lastProcessedId] are ignored from now on (`go_away`). */
    fun goAway(lastProcessedId: StreamId) {
        check(maxStreamId >= lastProcessedId)
        maxStreamId = lastProcessedId
    }

    fun recvEof(stream: Stream) {
        stream.state.recvEof()
        stream.notifySend()
        stream.notifyRecv()
        stream.notifyPush()
    }

    /** Drops the stream's queued events and releases their capacity (`clear_recv_buffer`). */
    fun clearRecvBuffer(stream: Stream, task: Task, counts: Counts) {
        var toRelease = 0L
        while (true) {
            val s = stream.pendingRecv.peek() ?: break
            if (s.kind == EV_DATA) {
                val len = (s.value as Bytes).size
                if (s.flag) counts.releaseDataFrame(len)
                toRelease = minOf(toRelease + len, stream.inFlightRecvData.toLong())
            }
            stream.pendingRecv.removeFront(buffer)
        }
        // Read but not released: release 0; released without reading: 0; dropped unread: all of it.
        if (toRelease > 0) {
            stream.inFlightRecvData -= toRelease.toInt()
            releaseConnectionCapacity(toRelease.toInt(), task)
        }
    }

    /** @throws ProtoError a GOAWAY PROTOCOL_ERROR once remote stream IDs are exhausted. */
    fun nextStreamIdOrThrow(): Int {
        val next = nextStreamId
        if (next == STREAM_ID_OVERFLOW) throw ProtoError.libraryGoAway(Reason.PROTOCOL_ERROR)
        return next
    }

    fun mayHaveCreatedStream(id: StreamId): Boolean {
        val next = nextStreamId
        return if (next == STREAM_ID_OVERFLOW) true else id.value < next
    }

    fun maybeResetNextStreamId(id: StreamId) {
        val next = nextStreamId
        if (next != STREAM_ID_OVERFLOW && id.value >= next) nextStreamId = nextIdOrOverflow(id)
    }

    /** @throws ProtoError a GOAWAY PROTOCOL_ERROR when push is disabled locally (`ensure_can_reserve`). */
    fun ensureCanReserve() {
        if (!isPushEnabled) throw ProtoError.libraryGoAway(Reason.PROTOCOL_ERROR)
    }

    /** Keeps a locally reset stream for a while, within the limit (`enqueue_reset_expiration`). */
    fun enqueueResetExpiration(stream: Stream, counts: Counts) {
        if (!stream.state.isLocalError || stream.isPendingResetExpiration) return
        if (counts.canIncNumResetStreams()) {
            counts.incNumResetStreams()
            pendingResetExpired.push(stream)
        }
        // else: dropped, over max_concurrent_reset_streams
    }

    /** Buffers the pending REFUSED_STREAM (`send_pending_refusal`). */
    fun sendPendingRefusal(dst: Codec): BufferStatus {
        val id = refused
        if (id != 0) {
            if (!dst.hasSendCapacity()) return BufferStatus.CodecFull
            check(dst.buffer(Reset(StreamId(id), Reason.REFUSED_STREAM)) == null)
        }
        refused = 0
        return BufferStatus.Complete
    }

    fun clearExpiredResetStreams(store: Store, counts: Counts) {
        if (pendingResetExpired.isEmpty) return
        val now = monotonicNanos()
        while (true) {
            val stream = pendingResetExpired.popIf { now - it.resetAt > resetDurationNanos } ?: break
            counts.transitionAfter(store, stream, true)
        }
    }

    fun clearQueues(clearPendingAccept: Boolean, store: Store, counts: Counts) {
        clearStreamWindowUpdateQueue(store, counts)
        clearAllResetStreams(store, counts)
        if (clearPendingAccept) clearAllPendingAccept(store, counts)
    }

    private fun clearStreamWindowUpdateQueue(store: Store, counts: Counts) {
        while (true) {
            val stream = pendingWindowUpdates.pop() ?: break
            counts.transition(store, stream) {}
        }
    }

    /** On EOF. */
    private fun clearAllResetStreams(store: Store, counts: Counts) {
        while (true) {
            val stream = pendingResetExpired.pop() ?: break
            counts.transitionAfter(store, stream, true)
        }
    }

    private fun clearAllPendingAccept(store: Store, counts: Counts) {
        while (true) {
            val stream = pendingAccept.pop() ?: break
            counts.transitionAfter(store, stream, false)
        }
    }

    /** Buffers the pending connection and stream WINDOW_UPDATEs (`buffer_pending`). */
    fun bufferPending(store: Store, counts: Counts, dst: Codec): BufferStatus {
        if (sendConnectionWindowUpdate(dst) == BufferStatus.CodecFull) return BufferStatus.CodecFull
        if (sendStreamWindowUpdates(store, counts, dst) == BufferStatus.CodecFull) return BufferStatus.CodecFull
        return BufferStatus.Complete
    }

    private fun sendConnectionWindowUpdate(dst: Codec): BufferStatus {
        val incr = flow.unclaimedCapacity()
        if (incr >= 0) {
            if (!dst.hasSendCapacity()) return BufferStatus.CodecFull
            check(dst.buffer(WindowUpdate(StreamId.ZERO, incr)) == null)
            flow.incWindow(incr)
        }
        return BufferStatus.Complete
    }

    private fun sendStreamWindowUpdates(store: Store, counts: Counts, dst: Codec): BufferStatus {
        while (true) {
            if (!dst.hasSendCapacity()) return BufferStatus.CodecFull
            val stream = pendingWindowUpdates.pop() ?: return BufferStatus.Complete
            counts.transition(store, stream) {
                // No updates for a stream no longer receiving data (TODO in the reference: a ReservedRemote stream
                // could get one).
                if (it.state.isRecvStreaming) {
                    val incr = it.recvFlow.unclaimedCapacity()
                    if (incr >= 0) {
                        check(dst.buffer(WindowUpdate(it.id, incr)) == null)
                        it.recvFlow.incWindow(incr)
                    }
                }
            }
        }
    }

    /** Whether a WINDOW_UPDATE is due (the connection has work to do). */
    val hasPendingWindowUpdates: Boolean get() = !pendingWindowUpdates.isEmpty || flow.unclaimedCapacity() >= 0

    /** The next stream to accept (`next_incoming`). */
    fun nextIncoming(): Stream? = pendingAccept.pop()

    /**
     * The next DATA payload (`poll_data`): the payload with its budget flag in [out]; null once trailers or the end
     * come; [PENDING] to wait on [Stream.recvTask].
     * @throws ProtoError the stream's error.
     */
    fun pollData(stream: Stream): Any? {
        val s = stream.pendingRecv.peek()
        if (s != null) {
            if (s.kind == EV_DATA) {
                val payload = s.value as Bytes
                lastDataBudgeted = s.flag
                stream.pendingRecv.removeFront(buffer)
                return payload
            }
            // Trailers: no more data. Notify in case `poll_trailers` is waiting (usually a no-op).
            stream.notifyRecv()
            return null
        }
        return scheduleRecv(stream)
    }

    /** Whether the payload last returned by [pollData] counts against the DATA frame budget. */
    var lastDataBudgeted = false
        private set

    /**
     * The trailers (`poll_trailers`): null when the stream ended without; [PENDING] to wait on [Stream.recvTask]
     * (also while DATA is still queued in front of them).
     * @throws ProtoError the stream's error.
     */
    @Suppress("UNCHECKED_CAST")
    fun pollTrailers(stream: Stream): Any? {
        val s = stream.pendingRecv.peek()
        if (s != null) {
            if (s.kind == EV_TRAILERS) {
                val trailers = s.value as HeaderMap<HeaderValue>
                stream.pendingRecv.removeFront(buffer)
                return trailers
            }
            // Not trailers: not ready to poll them yet.
            return PENDING
        }
        return scheduleRecv(stream)
    }

    private fun scheduleRecv(stream: Stream): Any? = if (stream.state.ensureRecvOpen()) PENDING else null

    companion object {
        /** A poll result meaning "not ready": the caller waits on the relevant [WaitSlot]. */
        val PENDING = Any()
    }
}
