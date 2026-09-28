package neton.http.h2.proto

import neton.http.Method
import neton.http.Request
import neton.http.Response
import neton.http.h2.Protocol
import neton.http.h2.codec.Codec
import neton.http.h2.codec.UserError
import neton.http.h2.frame.Data
import neton.http.h2.frame.GoAway
import neton.http.h2.frame.Headers
import neton.http.h2.frame.PushPromise
import neton.http.h2.frame.Reason
import neton.http.h2.frame.Reset
import neton.http.h2.frame.Settings
import neton.http.h2.frame.StreamId
import neton.http.h2.frame.WindowUpdate
import neton.http.header.HeaderMap
import neton.http.header.HeaderValue
import neton.io.bytes.Bytes

/**
 * The streams of one connection and the operations on them (`Streams`, `DynStreams`, `Inner` and `Actions`,
 * `src/proto/streams/streams.rs`).
 *
 * ⚖️ Concurrency: the reference shares this state between the connection task and the user handles through
 * `Arc<Mutex<Inner>>` (plus a second mutex for the send buffer). Here the connection's coroutines and the user
 * handles all run on the connection's reactor thread, so the state is a plain object without locks; every operation
 * is one synchronous step, as a critical section of the reference is.
 *
 * Errors are thrown where the reference returns them: [ProtoError] for stream and connection errors,
 * [UserErrorException] for user errors. A closure passed to `transition` in the reference that returns an error still
 * lets the transition's cleanup run; here such errors are carried out of the `transition` block as values and thrown
 * after it.
 */
internal class Streams(val peer: Peer, config: StreamsConfig) {
    val counts = Counts(peer, config)
    val recv = Recv(peer, config)
    val send = Send(config)
    val store = Store()

    /** The frames queued for sending, for all streams (`send_buffer`). */
    val sendBuffer = Buffer()

    /** Wakes the connection's driver (`actions.task`). */
    var task: Task = Task.NONE

    /** The connection error, kept for the handles once the connection failed (`conn_error`). */
    var connError: ProtoError? = null
        private set

    /** Handles to this state: the connection itself, `SendRequest` handles, stream handles (`refs`). */
    var refs = 1
        private set

    fun setTargetConnectionWindowSize(size: Int) {
        try {
            recv.setTargetConnectionWindow(size, task)
        } catch (e: FlowControlError) {
            // (The reference debug-asserts this does not happen.)
        }
    }

    /** The next stream to accept, with a handle to it (`next_incoming`). */
    fun nextIncoming(): StreamRef? {
        val stream = recv.nextIncoming() ?: return null
        refs++
        // Remotely reset streams pending accept are counted; this one is accepted now.
        if (stream.state.isRemoteReset) counts.decNumRemoteResetStreams()
        return StreamRef(this, stream)
    }

    fun clearExpiredResetStreams() = recv.clearExpiredResetStreams(store, counts)

    /** Buffers the pending frames into [dst]: WINDOW_UPDATEs first, then the streams' frames (`buffer_pending`). */
    fun bufferPending(dst: Codec): BufferStatus {
        // TODO in the reference: interleave window updates with data frames.
        if (recv.bufferPending(store, counts, dst) == BufferStatus.CodecFull) return BufferStatus.CodecFull
        if (send.bufferPending(sendBuffer, store, counts, dst) == BufferStatus.CodecFull) return BufferStatus.CodecFull
        return BufferStatus.Complete
    }

    fun reclaimWrittenFrame(dst: Codec): Boolean = send.reclaimWrittenFrame(sendBuffer, dst)

    /** @throws ProtoError on a window overflow. */
    fun applyRemoteSettings(frame: Settings, isInitial: Boolean) {
        counts.applyRemoteSettings(frame, isInitial)
        send.applyRemoteSettings(frame, sendBuffer, store, counts, task)
    }

    /** @throws ProtoError on a window overflow. */
    fun applyLocalSettings(frame: Settings) = recv.applyLocalSettings(frame, store)

    /**
     * Opens a stream for [request] (`send_request`); returns the stream handle and whether the next stream will
     * reach the concurrency limit. [pending] is the `SendRequest`'s previous stream, which must be opened first.
     * @throws ProtoError the connection error; @throws UserErrorException.
     */
    fun sendRequest(request: Request<*>, endOfStream: Boolean, pending: StreamRef?): Pair<StreamRef, Boolean> {
        var protocol: Protocol? = null
        request.parts.extensionsOrNull?.let {
            protocol = it.remove(Protocol::class)
            it.clear()
        }

        // TODO in the reference: assigning the stream ID before prioritization could implicitly close earlier IDs
        // if new streams were reordered (hyperium/h2#11).
        ensureNoConnError()
        send.ensureNextStreamId()

        // The client's previous stream is still waiting to be opened: it must use `ready` first.
        if (pending != null && pending.stream.isPendingOpen) throw UserErrorException(UserError.Rejected)

        // Servers cannot open streams; a push promise must be reserved first.
        if (peer.isServer) throw UserErrorException(UserError.UnexpectedFrameType)

        val streamId = send.open()
        val stream = Stream(streamId, send.initWindowSz, recv.initWindowSz)
        if (request.method == Method.HEAD) stream.contentLength = ContentLength.HEAD

        val headers = Peer.clientConvertSendMessage(streamId, request, protocol, endOfStream)
        store.insert(stream)

        val err = send.sendHeaders(headers, sendBuffer, stream, counts, task)
        if (err != null) {
            // Forget the stream.
            store.unlink(stream)
            store.remove(stream)
            throw UserErrorException(err)
        }
        check(!stream.state.isClosed)

        refs++
        val isFull = counts.nextSendStreamWillReachCapacity()
        return StreamRef(this, stream) to isFull
    }

    val isExtendedConnectProtocolEnabled: Boolean get() = send.isExtendedConnectProtocolEnabled

    val maxSendStreams: Int get() = counts.maxSendStreams

    val maxRecvStreams: Int get() = counts.maxRecvStreams

    // ===== received frames (`DynStreams`) =====

    /** @throws ProtoError */
    fun recvHeaders(frame: Headers) {
        val id = frame.streamId

        // A GOAWAY was sent: streams above its last ID are ignored.
        if (id > recv.maxStreamId) return

        var stream = store.find(id)
        if (stream == null) {
            // Client: a request may have been reset while the response HEADERS were in transit. (A server cannot reset
            // a stream before receiving its request headers.)
            if (!peer.isServer && mayHaveForgottenStream(id)) {
                throw ProtoError.libraryReset(id, Reason.STREAM_CLOSED) // "recv_headers for old stream"
            }
            val sid = recv.open(id, Open.Headers, counts) ?: return
            stream = store.insert(Stream(sid, send.initWindowSz, recv.initWindowSz))
        }

        // "recv_headers: received frame on idle stream"
        if (stream.isPendingOpen) throw ProtoError.libraryGoAway(Reason.PROTOCOL_ERROR)

        // Locally reset streams ignore frames for some time: the peer may have sent trailers before our RST_STREAM.
        if (stream.state.isLocalError) return

        val err = counts.transition(store, stream) { s ->
            val res: ProtoError? = if (s.state.isRecvHeaders) {
                try {
                    recv.recvHeaders(frame, s, counts)
                    null
                } catch (o: OversizeHeaders) {
                    val resp = o.response
                    if (resp != null) {
                        val sent = send.sendHeaders(resp, sendBuffer, s, counts, task)
                        check(sent == null) { "oversize response should not fail" }
                        send.scheduleImplicitReset(store, s, Reason.PROTOCOL_ERROR, counts, task)
                        recv.enqueueResetExpiration(s, counts)
                        null
                    } else {
                        ProtoError.libraryReset(s.id, Reason.PROTOCOL_ERROR)
                    }
                } catch (e: ProtoError) {
                    e
                }
            } else if (!frame.isEndStream) {
                // Trailers without END_STREAM make the message malformed: a stream error, returned as is.
                return@transition ProtoError.libraryReset(s.id, Reason.PROTOCOL_ERROR)
            } else {
                try {
                    recv.recvTrailers(frame, s)
                    null
                } catch (e: ProtoError) {
                    e
                }
            }
            resetOnRecvStreamErr(s, res)
        }
        if (err != null) throw err
    }

    /** @throws ProtoError */
    fun recvData(frame: Data) {
        val id = frame.streamId
        val stream = store.find(id)
        if (stream == null) {
            // A GOAWAY was sent: DATA above its last ID is ignored, but still counts against the connection window.
            if (id > recv.maxStreamId) {
                recv.ignoreData(frame.flowControlledLen())
                return
            }
            if (mayHaveForgottenStream(id)) {
                recv.ignoreData(frame.flowControlledLen())
                throw ProtoError.libraryReset(id, Reason.STREAM_CLOSED) // "recv_data for old stream"
            }
            throw ProtoError.libraryGoAway(Reason.PROTOCOL_ERROR) // "recv_data: stream not found"
        }

        val err = counts.transition(store, stream) { s ->
            val sz = frame.flowControlledLen()
            val isEndStream = frame.isEndStream
            val payloadLen = frame.payload.size
            var res: ProtoError? = try {
                recv.recvData(frame, s)
                null
            } catch (e: ProtoError) {
                e
            }
            // One final DATA frame per stream cannot create unbounded overhead: only the others are budgeted.
            if (res == null && !isEndStream && !counts.recordDataFrame(payloadLen)) {
                res = ProtoError.libraryGoAwayData(Reason.ENHANCE_YOUR_CALM, "too_many_data_frames")
            }
            // After a stream error the user never gets the data, so it cannot release the capacity: done here.
            if (res is ProtoError.Reset) recv.releaseConnectionCapacity(sz, Task.NONE)
            resetOnRecvStreamErr(s, res)
        }
        if (err != null) throw err
    }

    /** @throws ProtoError */
    fun recvReset(frame: Reset) {
        val id = frame.streamId
        if (id.isZero) throw ProtoError.libraryGoAway(Reason.PROTOCOL_ERROR) // "invalid stream ID 0"

        // A GOAWAY was sent: streams above its last ID are ignored.
        if (id > recv.maxStreamId) return

        val stream = store.find(id)
        if (stream == null) {
            // TODO in the reference: other error cases?
            ensureNotIdle(id)?.let { throw ProtoError.libraryGoAway(it) }
            return
        }

        // "recv_reset: received frame on idle stream"
        if (stream.isPendingOpen) throw ProtoError.libraryGoAway(Reason.PROTOCOL_ERROR)

        val err = counts.transition(store, stream) { s ->
            try {
                recv.recvReset(frame, s, counts)
            } catch (e: ProtoError) {
                return@transition e
            }
            send.handleError(sendBuffer, store, s, counts)
            check(s.state.isClosed)
            null
        }
        if (err != null) throw err
    }

    /** @throws ProtoError */
    fun recvWindowUpdate(frame: WindowUpdate) {
        val id = frame.streamId
        if (id.isZero) {
            try {
                send.recvConnectionWindowUpdate(frame, store, counts)
            } catch (e: FlowControlError) {
                throw ProtoError.libraryGoAway(e.reason)
            }
            return
        }
        // The peer may send window updates for streams already closed locally: fine.
        val stream = store.find(id)
        if (stream != null) {
            // "recv_window_update: received frame on idle stream"
            if (stream.isPendingOpen) throw ProtoError.libraryGoAway(Reason.PROTOCOL_ERROR)
            val res: ProtoError? = try {
                send.recvStreamWindowUpdate(frame.sizeIncrement, sendBuffer, store, stream, counts, task)
                null
            } catch (e: FlowControlError) {
                ProtoError.libraryReset(id, e.reason)
            }
            resetOnRecvStreamErr(stream, res)?.let { throw it }
        } else {
            ensureNotIdle(id)?.let { throw ProtoError.libraryGoAway(it) }
        }
    }

    /** Notifies all streams of a connection error; returns the last processed stream ID (`handle_error`). */
    fun handleError(err: ProtoError): StreamId {
        val lastProcessedId = recv.lastProcessedId
        store.forEach { stream ->
            counts.transition(store, stream) { s ->
                recv.handleError(err, s)
                send.handleError(sendBuffer, store, s, counts)
            }
        }
        connError = err
        return lastProcessedId
    }

    /** @throws ProtoError a GOAWAY PROTOCOL_ERROR for an increased last stream ID. */
    fun recvGoAway(frame: GoAway) {
        val lastStreamId = frame.lastStreamId
        send.recvGoAway(lastStreamId)
        val err = ProtoError.remoteGoAway(frame.debugData, frame.reason)
        store.forEach { stream ->
            if (stream.id > lastStreamId && peer.isLocalInit(stream.id)) {
                counts.transition(store, stream) { s ->
                    recv.handleError(err, s)
                    send.handleError(sendBuffer, store, s, counts)
                }
            }
        }
        connError = err
    }

    val lastProcessedId: StreamId get() = recv.lastProcessedId

    /** @throws ProtoError */
    fun recvPushPromise(frame: PushPromise) {
        val id = frame.streamId
        val promisedId = frame.promisedId

        // The initiating stream must still be in a valid state.
        // "recv_push_promise: initiating stream is in an invalid state"
        val parent = store.find(id) ?: throw ProtoError.libraryGoAway(Reason.PROTOCOL_ERROR)
        // A GOAWAY was sent: streams above its last ID are ignored.
        if (id > recv.maxStreamId) return
        // "recv_push_promise: initiating stream is not opened"
        if (!parent.state.ensureRecvOpen()) throw ProtoError.libraryGoAway(Reason.PROTOCOL_ERROR)

        // TODO in the reference: reserved streams do not count towards the concurrency limit, yet should be capped.
        recv.ensureCanReserve()

        // Refused: nothing more to do.
        recv.open(promisedId, Open.PushPromise, counts) ?: return

        val child = store.insert(Stream(promisedId, send.initWindowSz, recv.initWindowSz))
        var err: ProtoError? = null
        val valid = counts.transition(store, child) { s ->
            val r = try {
                recv.recvPushPromise(frame, s)
                null
            } catch (e: ProtoError) {
                e
            }
            if (r == null) {
                true
            } else {
                err = resetOnRecvStreamErr(s, r)
                false
            }
        }
        err?.let { throw it }
        if (valid) {
            parent.pendingPushPromises.push(child)
            parent.notifyPush()
        }
    }

    /**
     * The connection ended: every stream gets a broken pipe (`recv_eof`); called again safely. [clearPendingAccept]
     * also drops the streams waiting to be accepted.
     */
    fun recvEof(clearPendingAccept: Boolean) {
        if (connError == null) connError = CONNECTION_BROKEN_PIPE
        store.forEach { stream ->
            counts.transition(store, stream) { s ->
                recv.recvEof(s)
                // Resets the send state of the stream.
                send.handleError(sendBuffer, store, s, counts)
            }
        }
        recv.clearQueues(clearPendingAccept, store, counts)
        send.clearQueues(store, counts)
    }

    /**
     * Resets stream [id] from the library (`send_reset`), creating the stream when unknown (a bad request reset
     * before being accepted, or a frame on a stream the peer should not have opened).
     * @return a GOAWAY ENHANCE_YOUR_CALM when too many streams were reset by the library, else null.
     */
    fun sendReset(id: StreamId, reason: Reason): ProtoError? {
        var stream = store.find(id)
        if (stream == null) {
            // Update our view of the next stream ID.
            if (peer.isLocalInit(id)) send.maybeResetNextStreamId(id) else recv.maybeResetNextStreamId(id)
            stream = store.insert(Stream(id, 0, 0))
        }
        return actionsSendReset(stream, reason, Initiator.Library)
    }

    /** A GOAWAY is sent: streams above [lastProcessedId] are ignored from now on (`send_go_away`). */
    fun sendGoAway(lastProcessedId: StreamId) = recv.goAway(lastProcessedId)

    /**
     * Whether the client may send another request (`poll_pending_open`): false while [pending] waits to be opened
     * (then wait on its send task).
     * @throws ProtoError the connection error; @throws UserErrorException [UserError.OverflowedStreamId].
     */
    fun pollPendingOpen(pending: StreamRef?): Boolean {
        ensureNoConnError()
        send.ensureNextStreamId()
        return !(pending != null && pending.stream.isPendingOpen)
    }

    fun hasStreams(): Boolean = counts.hasStreams()

    fun hasStreamsOrOtherReferences(): Boolean = counts.hasStreams() || refs > 1

    /**
     * Whether frames are waiting to be buffered for sending (the connection's driver has work): queued frames,
     * WINDOW_UPDATEs, a refusal, or a stream waiting to be opened that the concurrency limit now allows.
     */
    fun hasPendingWrites(): Boolean =
        send.hasPendingSend || recv.hasPendingWindowUpdates || recv.refused != 0 ||
            send.prioritize.hasPendingOpen && counts.canIncNumSendStreams()

    /** A new handle to the connection's streams (`Streams::clone`, for a `SendRequest`). */
    fun cloneHandle() {
        refs++
    }

    /** A handle to the connection's streams is gone (`Drop for Streams`). */
    fun dropHandle() {
        refs--
        if (refs == 1) task.wake()
    }

    // ===== Actions =====

    private fun actionsSendReset(stream: Stream, reason: Reason, initiator: Initiator): ProtoError? =
        counts.transition(store, stream) { s ->
            if (initiator.isLibrary) {
                if (counts.canIncNumLocalErrorResets()) {
                    counts.incNumLocalErrorResets()
                } else {
                    return@transition ProtoError.libraryGoAwayData(Reason.ENHANCE_YOUR_CALM, "too_many_internal_resets")
                }
            }
            send.sendReset(reason, initiator, sendBuffer, store, s, counts, task)
            recv.enqueueResetExpiration(s, counts)
            // A parked RecvStream must notice.
            s.notifyRecv()
            null
        }

    /**
     * A stream error found while receiving resets the stream, within the lifetime limit of library resets
     * (`reset_on_recv_stream_err`); other errors are returned as they are.
     */
    private fun resetOnRecvStreamErr(stream: Stream, res: ProtoError?): ProtoError? {
        if (res !is ProtoError.Reset) return res
        check(res.streamId == stream.id)
        if (!counts.canIncNumLocalErrorResets()) {
            return ProtoError.libraryGoAwayData(Reason.ENHANCE_YOUR_CALM, "too_many_internal_resets")
        }
        counts.incNumLocalErrorResets()
        send.sendReset(res.reason, res.initiator, sendBuffer, store, stream, counts, task)
        recv.enqueueResetExpiration(stream, counts)
        stream.notifyRecv()
        return null
    }

    private fun ensureNotIdle(id: StreamId): Reason? =
        if (peer.isLocalInit(id)) send.ensureNotIdle(id) else recv.ensureNotIdle(id)

    /** @throws ProtoError the connection error. */
    fun ensureNoConnError() {
        connError?.let { throw it }
    }

    /**
     * Whether stream [id] may have existed and been forgotten after a local reset (`may_have_forgotten_stream`): the
     * peer's in-flight frames then get another RST_STREAM(STREAM_CLOSED) instead of a GOAWAY.
     */
    private fun mayHaveForgottenStream(id: StreamId): Boolean {
        if (id.isZero) return false
        return if (peer.isLocalInit(id)) send.mayHaveCreatedStream(id) else recv.mayHaveCreatedStream(id)
    }

    // ===== stream handles (`StreamRef` / `OpaqueStreamRef`) =====

    /** A handle to [stream] is gone (`drop_stream_ref`). */
    fun dropStreamRef(stream: Stream) {
        refs--
        stream.refDec()

        // Unreferenced and already closed (no cancel below): the connection may be able to finish now.
        if (stream.refCount == 0 && stream.isClosed) task.wake()

        counts.transition(store, stream) { s ->
            maybeCancel(s)
            if (s.refCount == 0) {
                // No one can read the receive window any more: give it back.
                recv.releaseClosedCapacity(s, task, counts)
                // Nor the push promises.
                while (true) {
                    val promise = s.pendingPushPromises.pop() ?: break
                    counts.transition(store, promise) { maybeCancel(it) }
                }
            }
        }
    }

    private fun maybeCancel(stream: Stream) {
        if (stream.isCanceledInterest) {
            // A server may respond early without consuming the request body, but must then send RST_STREAM(NO_ERROR)
            // (RFC 9113 §8.1); other implementations take other codes as fatal (nginx ticket 2376).
            val reason = if (peer.isServer && stream.state.isSendClosed && stream.state.isRecvStreaming) {
                Reason.NO_ERROR
            } else {
                Reason.CANCEL
            }
            send.scheduleImplicitReset(store, stream, reason, counts, task)
            recv.enqueueResetExpiration(stream, counts)
        }
    }

    fun sendData(stream: Stream, data: Bytes, endStream: Boolean): UserError? = counts.transition(store, stream) { s ->
        val frame = Data(s.id, data)
        frame.setEndStream(endStream)
        send.sendData(frame, sendBuffer, store, s, counts, task)
    }

    fun sendTrailers(stream: Stream, trailers: HeaderMap<HeaderValue>): UserError? = counts.transition(store, stream) { s ->
        send.sendTrailers(Headers.trailers(s.id, trailers), sendBuffer, store, s, counts, task)
    }

    fun sendResetFromUser(stream: Stream, reason: Reason) {
        // User resets do not count toward the local limit, so this cannot fail.
        check(actionsSendReset(stream, reason, Initiator.User) == null)
    }

    /** Sends a 1xx response without changing the stream state (`send_informational_headers`). */
    fun sendInformationalHeaders(stream: Stream, frame: Headers): UserError? = counts.transition(store, stream) { s ->
        check(frame.isInformational)
        if (frame.isEndStream) return@transition UserError.UnexpectedFrameType
        send.sendInterimInformationalHeaders(frame, sendBuffer, s, task)
    }

    fun sendResponse(stream: Stream, response: Response<*>, endOfStream: Boolean): UserError? {
        response.parts.extensionsOrNull?.clear()
        return counts.transition(store, stream) { s ->
            send.sendHeaders(Peer.serverConvertSendMessage(s.id, response, endOfStream), sendBuffer, s, counts, task)
        }
    }

    /** Reserves a stream and sends its PUSH_PROMISE on [stream] (`send_push_promise`). @throws UserErrorException */
    fun sendPushPromise(stream: Stream, request: Request<*>): StreamRef {
        request.parts.extensionsOrNull?.clear()
        val promisedId = send.reserveLocal()
        val child = store.insert(Stream(promisedId, send.initWindowSz, recv.initWindowSz))
        child.state.reserveLocal()?.let { throw UserErrorException(it) }
        child.isPendingPush = true

        val pushed: UserError? = try {
            val frame = Peer.convertPushMessage(stream.id, promisedId, request)
            send.sendPushPromise(frame, sendBuffer, stream, task)
        } catch (e: UserErrorException) {
            e.error
        }
        if (pushed != null) {
            store.unlink(child)
            store.remove(child)
            throw UserErrorException(pushed)
        }
        refs++
        return StreamRef(this, child)
    }

    fun takeRequest(stream: Stream): Request<Unit> = recv.takeRequest(stream)

    fun reserveCapacity(stream: Stream, capacity: Long) = send.reserveCapacity(capacity, store, stream, counts)

    fun capacity(stream: Stream): Int = send.capacity(stream)

    fun releaseCapacity(stream: Stream, capacity: Int): UserError? = recv.releaseCapacity(capacity, stream, task)

    /** Drops the received events and stops receiving DATA (`clear_recv_buffer`, when the RecvStream is gone). */
    fun clearRecvBuffer(stream: Stream) {
        stream.isRecv = false
        recv.clearRecvBuffer(stream, task, counts)
    }

    /** Gives back the budget of a DATA payload the user took (`poll_data`). */
    fun releaseDataFrame(payloadLen: Int) = counts.releaseDataFrame(payloadLen)

    private companion object {
        /** "connection closed because of a broken pipe" */
        val CONNECTION_BROKEN_PIPE: ProtoError =
            ProtoError.Io(IoErrorKind.BrokenPipe, "connection closed because of a broken pipe")
    }
}

/**
 * A handle to one stream (`StreamRef` / `OpaqueStreamRef`): counted in the stream's [Stream.refCount] and the
 * connection's [Streams.refs] until [drop].
 *
 * ⚖️ The reference releases a handle when it is dropped; Kotlin has no destructors, so the public handles release
 * theirs in `close()` (idempotent). A handle never closed keeps its stream until the connection ends.
 */
internal class StreamRef(val streams: Streams, val stream: Stream) {
    private var dropped = false

    init {
        stream.refInc()
    }

    val streamId: StreamId get() = stream.id

    val isDropped: Boolean get() = dropped

    /** A new handle to the same stream (`clone`). */
    fun clone(): StreamRef {
        streams.cloneHandle()
        return StreamRef(streams, stream)
    }

    /** Releases the handle (`Drop`); later calls do nothing. */
    fun drop() {
        if (dropped) return
        dropped = true
        streams.dropStreamRef(stream)
    }
}
