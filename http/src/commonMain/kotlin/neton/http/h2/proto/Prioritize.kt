package neton.http.h2.proto

import neton.http.h2.codec.Codec
import neton.http.h2.codec.UserError
import neton.http.h2.frame.Data
import neton.http.h2.frame.Frame
import neton.http.h2.frame.PushPromise
import neton.http.h2.frame.Reason
import neton.http.h2.frame.Reset
import neton.http.h2.frame.StreamId
import neton.io.bytes.Bytes

/** Wakes the task driving the connection (the reference's `Option<Waker>` for the connection task). */
internal fun interface Task {
    fun wake()

    companion object {
        /** The reference's `&mut None`: the caller is the connection itself, nothing to wake. */
        val NONE: Task = Task {}
    }
}

/** Whether everything pending was buffered into the codec (`BufferStatus`). */
internal enum class BufferStatus {
    Complete,
    CodecFull,
}

/**
 * Send scheduling (`Prioritize`, `src/proto/streams/prioritize.rs`): which stream's frame is written next, and the
 * connection-level send window shared among the streams.
 *
 * Queued streams are ordered by stream ID where it matters: lower-numbered streams must send their HEADERS first,
 * since receiving a frame on a higher ID implicitly closes idle lower IDs. Connection capacity is handed out
 * first-come first-served, without weights.
 *
 * DATA frames in the codec: the reference hands the codec a `Take` of the frame's buffer (`Prioritized`) and gets
 * it back once written (`take_last_data_frame`) to requeue the rest. Here the frame sent is a zero-copy slice of the
 * payload; the rest of the payload and the END_STREAM flag wait in [inFlightRest] / [inFlightEos] until the codec
 * returns the frame, then are requeued the same way.
 */
internal class Prioritize(config: StreamsConfig) {
    /** Streams waiting to write a frame. */
    private val pendingSend = Queue(NextSend)

    /** Streams waiting for connection window to produce data. */
    private val pendingCapacity = Queue(NextSendCapacity)

    /**
     * Streams waiting to be opened because of the concurrency limit. Each `SendRequest` handle may buffer one such
     * request; it is ready again once that one is opened.
     */
    private val pendingOpen = Queue(NextOpen)

    /** Connection-level send flow control. */
    val flow = FlowControl()

    /** The DATA frame in the codec: none, the stream it belongs to, or dropped (its stream's queue was cleared). */
    private var inFlight = IN_FLIGHT_NOTHING
    private var inFlightStream: Stream? = null
    private var inFlightRest: Bytes = Bytes.EMPTY
    private var inFlightEos = false

    private var lastOpenedId = StreamId.ZERO

    /** The most DATA bytes a stream may buffer (`max_buffer_size`). */
    val maxBufferSize: Int = config.localMaxBufferSize

    init {
        flow.incWindow(config.remoteInitWindowSz)
        flow.assignCapacity(config.remoteInitWindowSz)
    }

    /** Whether any stream waits to write or to be opened (the connection has work). */
    val hasPendingSend: Boolean get() = !pendingSend.isEmpty

    /** Queues a frame to send (`queue_frame`). */
    fun queueFrame(frame: Frame, buffer: Buffer, stream: Stream, task: Task) {
        stream.pendingSend.pushBack(buffer, frame)
        scheduleSend(stream, task)
    }

    /** Schedules the stream for writing, unless it waits to be opened (`schedule_send`). */
    fun scheduleSend(stream: Stream, task: Task) {
        if (stream.isSendReady) {
            pendingSend.push(stream)
            task.wake()
        }
    }

    fun queueOpen(stream: Stream) {
        pendingOpen.push(stream)
    }

    /** Buffers a DATA frame (`send_data`). */
    fun sendData(frame: Data, buffer: Buffer, store: Store, stream: Stream, counts: Counts, task: Task): UserError? {
        val sz = frame.payload.size
        // (A payload larger than MAX_WINDOW_SIZE, `PayloadTooBig`, cannot be represented.)
        if (!stream.state.isSendStreaming) {
            return if (stream.state.isClosed) UserError.InactiveStreamId else UserError.UnexpectedFrameType
        }

        stream.bufferedSendData += sz

        // Implicitly request more send capacity if not enough has been requested yet.
        if (stream.requestedSendCapacity < stream.bufferedSendData) {
            stream.requestedSendCapacity = minOf(stream.bufferedSendData, U32_MAX)
            // Queues the stream on `pending_capacity` when the capacity cannot be assigned now.
            tryAssignCapacity(stream)
        }

        if (frame.isEndStream) {
            stream.state.sendClose()
            reserveCapacity(0, store, stream, counts)
        }

        // An empty DATA frame queued with nothing before it (end of stream) goes out even without window.
        if (stream.sendFlow.available > 0 || stream.bufferedSendData == 0L) {
            queueFrame(frame, buffer, stream, task)
        } else {
            // No capacity now: keep the frame, without waking the connection; it is flushed once capacity comes.
            stream.pendingSend.pushBack(buffer, frame)
        }
        return null
    }

    /** Requests send capacity (`reserve_capacity`): [capacity] beyond what is already buffered, a u32. */
    fun reserveCapacity(capacity: Long, store: Store, stream: Stream, counts: Counts) {
        // The capacity is on top of the buffered data, which must be sent anyway.
        val cap = capacity + stream.bufferedSendData
        when {
            cap == stream.requestedSendCapacity -> {}
            cap < stream.requestedSendCapacity -> {
                stream.requestedSendCapacity = cap
                // Give back what is assigned beyond the request.
                val available = Window.asSize(stream.sendFlow.available)
                if (available > cap) {
                    val diff = (available - cap).toInt()
                    stream.sendFlow.claimCapacity(diff)
                    assignConnectionCapacity(diff, store, counts)
                }
            }
            else -> {
                // Adding capacity to a stream whose send half is closed does nothing.
                if (stream.state.isSendClosed) return
                stream.requestedSendCapacity = minOf(cap, U32_MAX)
                tryAssignCapacity(stream)
            }
        }
    }

    /** `recv_stream_window_update`. @throws FlowControlError on window overflow. */
    fun recvStreamWindowUpdate(inc: Int, stream: Stream) {
        // Nothing can be sent: nothing to do.
        if (stream.state.isSendClosed && stream.bufferedSendData == 0L) return
        stream.sendFlow.incWindow(inc)
        // Assigns capacity (if the connection has some) and notifies the producer.
        tryAssignCapacity(stream)
    }

    /** `recv_connection_window_update`. @throws FlowControlError on window overflow. */
    fun recvConnectionWindowUpdate(inc: Int, store: Store, counts: Counts) {
        flow.incWindow(inc)
        assignConnectionCapacity(inc, store, counts)
    }

    /** Gives all of a stream's assigned capacity back to the connection (`reclaim_all_capacity`). */
    fun reclaimAllCapacity(store: Store, stream: Stream, counts: Counts) {
        val available = Window.asSize(stream.sendFlow.available)
        if (available > 0) {
            stream.sendFlow.claimCapacity(available)
            assignConnectionCapacity(available, store, counts)
        }
    }

    /** Gives back the reserved capacity not covering buffered data (`reclaim_reserved_capacity`). */
    fun reclaimReservedCapacity(store: Store, stream: Stream, counts: Counts) {
        val available = Window.asSize(stream.sendFlow.available)
        if (available > stream.bufferedSendData) {
            val reserved = (available - stream.bufferedSendData).toInt()
            stream.sendFlow.claimCapacity(reserved)
            assignConnectionCapacity(reserved, store, counts)
        }
    }

    fun clearPendingCapacity(store: Store, counts: Counts) {
        while (true) {
            val stream = pendingCapacity.pop() ?: break
            counts.transition(store, stream) {}
        }
    }

    /** Adds connection capacity and hands it to the streams waiting for some (`assign_connection_capacity`). */
    fun assignConnectionCapacity(inc: Int, store: Store, counts: Counts) {
        flow.assignCapacity(inc)
        while (flow.available > 0) {
            val stream = pendingCapacity.pop() ?: return
            // A stream reset while waiting wants no capacity: drop it from the queue without a transition.
            if (!(stream.state.isSendStreaming || stream.bufferedSendData > 0)) continue
            // Requeued on `pending_capacity` if the connection cannot cover the whole request.
            counts.transition(store, stream) { tryAssignCapacity(it) }
        }
    }

    /** Assigns connection capacity to a stream that requested some (`try_assign_capacity`). */
    private fun tryAssignCapacity(stream: Stream) {
        // Streams over the concurrency limit get none, so they do not starve the open ones.
        if (stream.isPendingOpen) return

        val totalRequested = stream.requestedSendCapacity
        val avail = Window.asSize(stream.sendFlow.available).toLong()
        // Never more than the stream's window allows (u32 arithmetic, as in the reference).
        val additional = minOf(u32Sub(totalRequested, avail), u32Sub(stream.sendFlow.windowSize.toLong(), avail))
        if (additional == 0L) return

        // The stream may have been reset or closed since capacity was requested.
        if (!stream.state.isSendStreaming && stream.bufferedSendData == 0L) return

        val connAvailable = Window.asSize(flow.available)
        if (connAvailable > 0) {
            val assign = minOf(connAvailable.toLong(), additional).toInt()
            stream.assignCapacity(assign, maxBufferSize)
            flow.claimCapacity(assign)
        }

        // The stream wants more and its window has room, but the connection has none: wait for connection capacity.
        if (stream.sendFlow.available < stream.requestedSendCapacity && stream.sendFlow.hasUnavailable()) {
            pendingCapacity.push(stream)
        }

        // Buffered data and ready to send: schedule the stream.
        if (stream.bufferedSendData > 0 && stream.isSendReady) pendingSend.push(stream)
    }

    /**
     * Buffers the queued frames into [dst] until it is full or nothing is left (`buffer_pending`), opening streams
     * waiting for the concurrency limit on the way.
     */
    fun bufferPending(buffer: Buffer, store: Store, counts: Counts, dst: Codec): BufferStatus {
        // Reclaim any frame previously written.
        reclaimFrame(buffer, dst)
        val maxFrameLen = dst.maxSendFrameSize
        while (true) {
            if (!dst.hasSendCapacity()) return BufferStatus.CodecFull

            val opened = popPendingOpen(counts)
            if (opened != null) {
                pendingSend.pushFront(opened)
                tryAssignCapacity(opened)
            }

            val frame = popFrame(buffer, store, maxFrameLen, counts) ?: return BufferStatus.Complete
            check(inFlight == IN_FLIGHT_NOTHING)
            if (frame is Data) inFlight = IN_FLIGHT_DATA
            val err = dst.buffer(frame)
            check(err == null) { "invalid frame: $err" }
            // A small DATA frame is fully encoded by `buffer`: reclaim it before the next one reuses the slot.
            reclaimFrame(buffer, dst)
        }
    }

    /** `reclaim_written_frame`: requeues the rest of a DATA frame the codec has written; true when requeued. */
    fun reclaimWrittenFrame(buffer: Buffer, dst: Codec): Boolean = reclaimFrame(buffer, dst)

    /** Takes back the DATA frame last written by the codec and requeues the rest of its payload (`reclaim_frame`). */
    private fun reclaimFrame(buffer: Buffer, dst: Codec): Boolean {
        dst.takeLastDataFrame() ?: return false
        val state = inFlight
        inFlight = IN_FLIGHT_NOTHING
        val stream = inFlightStream
        val rest = inFlightRest
        val eos = inFlightEos
        inFlightStream = null
        inFlightRest = Bytes.EMPTY
        when (state) {
            IN_FLIGHT_NOTHING -> throw IllegalStateException("wasn't expecting a frame to reclaim")
            IN_FLIGHT_DROP -> return false // not reclaiming the frame of a cancelled stream
        }
        if (rest.size > 0) {
            val frame = Data(stream!!.id, rest)
            if (eos) frame.setEndStream(true)
            pushBackFrame(frame, buffer, stream)
            return true
        }
        return false
    }

    /** Pushes a frame to the front of the stream's queue and schedules it if it has capacity (`push_back_frame`). */
    private fun pushBackFrame(frame: Frame, buffer: Buffer, stream: Stream) {
        stream.pendingSend.pushFront(buffer, frame)
        if (stream.sendFlow.available > 0) pendingSend.push(stream)
    }

    /** Drops the stream's queued frames (`clear_queue`). */
    fun clearQueue(buffer: Buffer, stream: Stream) {
        while (stream.pendingSend.popFront(buffer) != null) {
            // dropping
        }
        stream.bufferedSendData = 0
        stream.requestedSendCapacity = 0
        // The stream may be cleaned up now: its frame in the codec must not be reclaimed.
        if (inFlight == IN_FLIGHT_DATA && inFlightStream === stream) inFlight = IN_FLIGHT_DROP
    }

    fun clearPendingSend(store: Store, counts: Counts) {
        while (true) {
            val stream = pendingSend.pop() ?: break
            val isPendingReset = stream.isPendingResetExpiration
            stream.state.scheduledReset?.let { stream.setReset(it, Initiator.Library) }
            counts.transitionAfter(store, stream, isPendingReset)
        }
    }

    fun clearPendingOpen(store: Store, counts: Counts) {
        while (true) {
            val stream = pendingOpen.pop() ?: break
            val isPendingReset = stream.isPendingResetExpiration
            counts.transitionAfter(store, stream, isPendingReset)
        }
    }

    /** The next frame to write (`pop_frame`), cutting DATA to the stream's window and the max frame size. */
    private fun popFrame(buffer: Buffer, store: Store, maxLen: Int, counts: Counts): Frame? {
        while (true) {
            val stream = pendingSend.pop() ?: return null

            // The stream may also be queued for reset expiration; always ask it.
            val isPendingReset = stream.isPendingResetExpiration

            val frame: Frame
            when (val head = stream.pendingSend.popFront(buffer)) {
                is Data -> {
                    val scheduled = stream.state.scheduledReset
                    // A reset scheduled for cancellation or an error discards buffered DATA; the RST_STREAM goes out
                    // on the next round. Not for NO_ERROR: such a reset may only follow a complete response (RFC 9113
                    // §8.1), so the queued DATA must be sent.
                    if (scheduled != null && scheduled != Reason.NO_ERROR) {
                        stream.pendingSend.pushFront(buffer, head)
                        clearQueue(buffer, stream)
                        reclaimAllCapacity(store, stream, counts)
                        pendingSend.push(stream)
                        continue
                    }

                    val streamCapacity = stream.sendFlow.available
                    val sz = head.payload.size

                    // Zero-length DATA always has capacity. (As in the reference, a negative capacity is not "0".)
                    if (sz > 0 && streamCapacity == 0) {
                        // No capacity left (the peer may have reduced the window): wait for a WINDOW_UPDATE.
                        stream.pendingSend.pushFront(buffer, head)
                        continue
                    }

                    // Only up to the max frame size and the stream's window.
                    val len = minOf(minOf(sz, maxLen), Window.asSize(streamCapacity))

                    // The window the peer knows may be smaller than the one we know.
                    if (len > 0 && len > stream.sendFlow.windowSize) {
                        stream.pendingSend.pushFront(buffer, head)
                        continue
                    }

                    // Stream flow control, and the capacity consumed from the stream goes back to the connection...
                    stream.sendData(len, maxBufferSize)
                    flow.assignCapacity(len)
                    // ...which then sends it.
                    flow.sendData(len)

                    val eos = head.isEndStream
                    frame = if (len == sz) {
                        head
                    } else {
                        inFlightRest = head.payload.slice(len)
                        Data(stream.id, head.payload.slice(0, len))
                    }
                    inFlightEos = eos
                    inFlightStream = stream
                }
                is PushPromise -> {
                    val pushed = checkNotNull(store.find(head.promisedId))
                    pushed.isPendingPush = false
                    // pending_push → pending_open (or straight to pending_send) if it has frames.
                    if (!pushed.pendingSend.isEmpty) {
                        if (counts.canIncNumSendStreams()) {
                            counts.incNumSendStreams(pushed)
                            pendingSend.push(pushed)
                        } else {
                            queueOpen(pushed)
                        }
                    }
                    frame = head
                }
                null -> {
                    val reason = stream.state.scheduledReset
                    if (reason != null) {
                        stream.setReset(reason, Initiator.Library)
                        frame = Reset(stream.id, reason)
                    } else {
                        // The peer reset the stream and `clear_queue` emptied it: drop the stream from the queue.
                        check(stream.state.isClosed)
                        counts.transitionAfter(store, stream, isPendingReset)
                        continue
                    }
                }
                else -> frame = head as Frame
            }

            // (The reference debug-asserts here that streams are opened in ID order.)
            if (stream.state.isIdle) lastOpenedId = stream.id

            // Requeue the stream if it has more to send.
            if (!stream.pendingSend.isEmpty || stream.state.isScheduledReset) pendingSend.push(stream)

            counts.transitionAfter(store, stream, isPendingReset)
            return frame
        }
    }

    /** Opens a stream that waited for the concurrency limit, if one may be opened now (`pop_pending_open`). */
    private fun popPendingOpen(counts: Counts): Stream? {
        if (!counts.canIncNumSendStreams()) return null
        val stream = pendingOpen.pop() ?: return null
        counts.incNumSendStreams(stream)
        stream.notifySend()
        return stream
    }

    private companion object {
        const val IN_FLIGHT_NOTHING = 0
        const val IN_FLIGHT_DATA = 1
        const val IN_FLIGHT_DROP = 2

        const val U32_MAX = 0xffffffffL
    }
}
