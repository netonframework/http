package neton.http.h2.proto

import neton.http.h2.codec.Codec
import neton.http.h2.codec.UserError
import neton.http.h2.frame.Data
import neton.http.h2.frame.Headers
import neton.http.h2.frame.PushPromise
import neton.http.h2.frame.Reason
import neton.http.h2.frame.Reset
import neton.http.h2.frame.Settings
import neton.http.h2.frame.StreamId
import neton.http.h2.frame.StreamIdOverflow
import neton.http.header.HeaderMap
import neton.http.header.HeaderName
import neton.http.header.HeaderValue

/** No stream ID left (`Err(StreamIdOverflow)` in the reference's `next_stream_id`). */
internal const val STREAM_ID_OVERFLOW: Int = -1

/** The next ID after [id], or [STREAM_ID_OVERFLOW]. */
internal fun nextIdOrOverflow(id: StreamId): Int = try {
    id.nextId().value
} catch (e: StreamIdOverflow) {
    STREAM_ID_OVERFLOW
}

/** Result of [Send.pollCapacity] when no more capacity will come (`Ready(None)`). */
internal const val CAPACITY_NONE: Int = -1

/** Result of [Send.pollCapacity] when the caller must wait (`Pending`). */
internal const val CAPACITY_PENDING: Int = -2

/** State transitions caused by outbound frames (`Send`, `src/proto/streams/send.rs`). */
internal class Send(config: StreamsConfig) {
    /** The ID of the next local stream, or [STREAM_ID_OVERFLOW]. */
    private var nextStreamId: Int = config.localNextStreamId.value

    /** Streams with a higher ID are ignored; lowered when a GOAWAY is received. */
    private var maxStreamId = StreamId.MAX

    /** The initial window of local streams (the peer's SETTINGS_INITIAL_WINDOW_SIZE). */
    var initWindowSz: Int = config.remoteInitWindowSz
        private set

    val prioritize = Prioritize(config)

    private var isPushEnabled = true

    /** Whether the peer enabled extended CONNECT. */
    var isExtendedConnectProtocolEnabled = false
        private set

    fun open(): StreamId {
        val id = ensureNextStreamId()
        nextStreamId = nextIdOrOverflow(id)
        return id
    }

    fun reserveLocal(): StreamId {
        val id = ensureNextStreamId()
        nextStreamId = nextIdOrOverflow(id)
        return id
    }

    fun sendPushPromise(frame: PushPromise, buffer: Buffer, stream: Stream, task: Task): UserError? {
        if (!isPushEnabled) return UserError.PeerDisabledServerPush
        checkHeaders(frame.fields)?.let { return it }
        prioritize.queueFrame(frame, buffer, stream, task)
        return null
    }

    fun sendHeaders(frame: Headers, buffer: Buffer, stream: Stream, counts: Counts, task: Task): UserError? {
        checkHeaders(frame.fields)?.let { return it }
        stream.state.sendOpen(frame.isEndStream)?.let { return it }

        var pendingOpen = false
        if (counts.peer.isLocalInit(frame.streamId) && !stream.isPendingPush) {
            prioritize.queueOpen(stream)
            pendingOpen = true
        }

        // New streams are in the open queue, so they are not pushed on pending_send here.
        prioritize.queueFrame(frame, buffer, stream, task)

        // `queue_frame` only notifies for pending_send.
        if (pendingOpen) task.wake()
        return null
    }

    /** Sends a 1xx response without changing the stream state (`send_interim_informational_headers`). */
    fun sendInterimInformationalHeaders(frame: Headers, buffer: Buffer, stream: Stream, task: Task): UserError? {
        checkHeaders(frame.fields)?.let { return it }
        check(frame.isInformational && !frame.isEndStream)
        prioritize.queueFrame(frame, buffer, stream, task)
        return null
    }

    /** Sends an explicit RST_STREAM (`send_reset`). */
    fun sendReset(reason: Reason, initiator: Initiator, buffer: Buffer, store: Store, stream: Stream, counts: Counts, task: Task) {
        val isReset = stream.state.isReset
        val isClosed = stream.state.isClosed
        val isEmpty = stream.pendingSend.isEmpty

        // Don't double reset.
        if (isReset) return

        // Transition the state to reset no matter what.
        stream.setReset(reason, initiator)

        // Closed and flushed: no explicit reset can be sent either (implicit ones still can).
        if (isClosed && isEmpty) return

        // A stream not yet opened keeps its queued HEADERS: RST_STREAM must not be the first frame of an idle stream
        // (RFC 9113 §5.1, §6.4). Otherwise the queued DATA / HEADERS are dropped and only the reset is sent. The queue
        // is cleared before `reclaim_all_capacity` transitions the stream.
        if (!stream.isPendingOpen) prioritize.clearQueue(buffer, stream)

        prioritize.queueFrame(Reset(stream.id, reason), buffer, stream, task)
        prioritize.reclaimAllCapacity(store, stream, counts)
    }

    fun scheduleImplicitReset(store: Store, stream: Stream, reason: Reason, counts: Counts, task: Task) {
        if (stream.state.isClosed) return
        stream.state.setScheduledReset(reason)
        prioritize.reclaimReservedCapacity(store, stream, counts)
        prioritize.scheduleSend(stream, task)
    }

    fun sendData(frame: Data, buffer: Buffer, store: Store, stream: Stream, counts: Counts, task: Task): UserError? =
        prioritize.sendData(frame, buffer, store, stream, counts, task)

    fun sendTrailers(frame: Headers, buffer: Buffer, store: Store, stream: Stream, counts: Counts, task: Task): UserError? {
        // Trailers are a HEADERS frame and may not carry connection-specific fields either (RFC 9113 §8.2.2); checked
        // before the state changes so that valid trailers can still be sent after a rejected call.
        checkHeaders(frame.fields)?.let { return it }
        if (!stream.state.isSendStreaming) return UserError.UnexpectedFrameType
        stream.state.sendClose()
        prioritize.queueFrame(frame, buffer, stream, task)
        // Release any excess capacity.
        prioritize.reserveCapacity(0, store, stream, counts)
        return null
    }

    fun bufferPending(buffer: Buffer, store: Store, counts: Counts, dst: Codec): BufferStatus =
        prioritize.bufferPending(buffer, store, counts, dst)

    fun reclaimWrittenFrame(buffer: Buffer, dst: Codec): Boolean = prioritize.reclaimWrittenFrame(buffer, dst)

    fun reserveCapacity(capacity: Long, store: Store, stream: Stream, counts: Counts) =
        prioritize.reserveCapacity(capacity, store, stream, counts)

    /**
     * `poll_capacity`: the capacity once it grew, [CAPACITY_NONE] when the stream no longer sends,
     * [CAPACITY_PENDING] (after which the caller waits on [Stream.sendTask]).
     */
    fun pollCapacity(stream: Stream): Int {
        if (!stream.state.isSendStreaming) return CAPACITY_NONE
        if (!stream.sendCapacityInc) return CAPACITY_PENDING
        stream.sendCapacityInc = false
        val capacity = capacity(stream)
        // Capacity back to 0 (a SETTINGS race, for instance): wait instead of returning 0.
        if (capacity == 0) return CAPACITY_PENDING
        return capacity
    }

    fun capacity(stream: Stream): Int = stream.capacity(prioritize.maxBufferSize)

    /** `poll_reset`: the reason once reset, or null while not (then the caller waits on [Stream.sendTask]). */
    fun pollReset(stream: Stream, mode: PollReset): Reason? = stream.state.ensureReason(mode)

    /** @throws FlowControlError on window overflow. */
    fun recvConnectionWindowUpdate(frame: neton.http.h2.frame.WindowUpdate, store: Store, counts: Counts) =
        prioritize.recvConnectionWindowUpdate(frame.sizeIncrement, store, counts)

    /**
     * `recv_stream_window_update`: an overflowing window resets the stream with FLOW_CONTROL_ERROR.
     * @throws FlowControlError after sending that reset.
     */
    fun recvStreamWindowUpdate(sz: Int, buffer: Buffer, store: Store, stream: Stream, counts: Counts, task: Task) {
        try {
            prioritize.recvStreamWindowUpdate(sz, stream)
        } catch (e: FlowControlError) {
            sendReset(Reason.FLOW_CONTROL_ERROR, Initiator.Library, buffer, store, stream, counts, task)
            throw e
        }
    }

    /** @throws ProtoError a GOAWAY PROTOCOL_ERROR when the peer raises its last stream ID. */
    fun recvGoAway(lastStreamId: StreamId) {
        // A GOAWAY naming a stream we never sent, or higher than a previous GOAWAY: illegal.
        if (lastStreamId > maxStreamId) throw ProtoError.libraryGoAway(Reason.PROTOCOL_ERROR)
        maxStreamId = lastStreamId
    }

    fun handleError(buffer: Buffer, store: Store, stream: Stream, counts: Counts) {
        // Clear all pending outbound frames.
        prioritize.clearQueue(buffer, stream)
        prioritize.reclaimAllCapacity(store, stream, counts)
    }

    /** @throws ProtoError a GOAWAY FLOW_CONTROL_ERROR when a window over- or underflows. */
    fun applyRemoteSettings(settings: Settings, buffer: Buffer, store: Store, counts: Counts, task: Task) {
        settings.isExtendedConnectProtocolEnabled?.let { isExtendedConnectProtocolEnabled = it }

        // RFC 9113 §6.9.2: a change of SETTINGS_INITIAL_WINDOW_SIZE adjusts every stream window by the difference; a
        // window may become negative, and nothing is sent on it until WINDOW_UPDATEs make it positive again.
        val v = settings.initialWindowSize
        if (v != null) {
            val value = v.toInt()
            val old = initWindowSz
            initWindowSz = value
            if (value < old) {
                // Decrease the (remote) window of every open stream.
                val dec = old - value
                var totalReclaimed = 0L
                store.forEach { stream ->
                    if (stream.state.isSendClosed && stream.bufferedSendData == 0L) return@forEach
                    try {
                        // This decrement can underflow based on received frames (TODO in the reference).
                        stream.sendFlow.decSendWindow(dec)
                        // The window may fall below the capacity assigned to the stream: take the excess back and
                        // hand it to other streams.
                        val windowSize = stream.sendFlow.windowSize
                        val available = Window.asSize(stream.sendFlow.available)
                        if (available > windowSize) {
                            val reclaim = available - windowSize
                            stream.sendFlow.claimCapacity(reclaim)
                            totalReclaimed += reclaim
                        }
                    } catch (e: FlowControlError) {
                        throw ProtoError.libraryGoAway(e.reason)
                    }
                }
                prioritize.assignConnectionCapacity(totalReclaimed.toInt(), store, counts)
            } else if (value > old) {
                val inc = value - old
                store.forEach { stream ->
                    try {
                        recvStreamWindowUpdate(inc, buffer, store, stream, counts, task)
                    } catch (e: FlowControlError) {
                        throw ProtoError.libraryGoAway(e.reason)
                    }
                }
            }
        }

        settings.isPushEnabled?.let { isPushEnabled = it }
    }

    fun clearQueues(store: Store, counts: Counts) {
        prioritize.clearPendingCapacity(store, counts)
        prioritize.clearPendingSend(store, counts)
        prioritize.clearPendingOpen(store, counts)
    }

    /** PROTOCOL_ERROR when local stream [id] is still idle (`ensure_not_idle`); null otherwise. */
    fun ensureNotIdle(id: StreamId): Reason? {
        val next = nextStreamId
        // An overflowed next ID is fine.
        if (next != STREAM_ID_OVERFLOW && id.value >= next) return Reason.PROTOCOL_ERROR
        return null
    }

    /** @throws UserErrorException [UserError.OverflowedStreamId]. */
    fun ensureNextStreamId(): StreamId {
        val next = nextStreamId
        if (next == STREAM_ID_OVERFLOW) throw UserErrorException(UserError.OverflowedStreamId)
        return StreamId(next)
    }

    fun mayHaveCreatedStream(id: StreamId): Boolean {
        val next = nextStreamId
        return if (next == STREAM_ID_OVERFLOW) true else id.value < next
    }

    fun maybeResetNextStreamId(id: StreamId) {
        val next = nextStreamId
        if (next != STREAM_ID_OVERFLOW && id.value >= next) nextStreamId = nextIdOrOverflow(id)
    }

    /** Whether any stream is waiting to write (the connection has work to do). */
    val hasPendingSend: Boolean get() = prioritize.hasPendingSend

    companion object {
        // Looked up as names, not strings: a string key is hashed and compared case-insensitively on every response.
        private val KEEP_ALIVE = HeaderName.fromStatic("keep-alive")
        private val PROXY_CONNECTION = HeaderName.fromStatic("proxy-connection")

        /** RFC 9113 §8.2.2 connection-specific fields are refused (`check_headers`). */
        fun checkHeaders(fields: HeaderMap<HeaderValue>): UserError? {
            if (fields.containsKey(HeaderName.CONNECTION) || fields.containsKey(HeaderName.TRANSFER_ENCODING) ||
                fields.containsKey(HeaderName.UPGRADE) || fields.containsKey(KEEP_ALIVE) ||
                fields.containsKey(PROXY_CONNECTION)
            ) {
                return UserError.MalformedHeaders
            }
            val te = fields[HeaderName.TE]
            if (te != null && !te.contentEquals("trailers")) return UserError.MalformedHeaders
            return null
        }
    }
}
