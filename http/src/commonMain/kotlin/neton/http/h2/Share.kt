package neton.http.h2

import neton.http.h2.codec.UserError
import neton.http.h2.frame.Reason
import neton.http.h2.frame.StreamId
import neton.http.h2.proto.CAPACITY_NONE
import neton.http.h2.proto.CAPACITY_PENDING
import neton.http.h2.proto.MAX_WINDOW_SIZE
import neton.http.h2.proto.PollReset
import neton.http.h2.proto.ProtoError
import neton.http.h2.proto.Recv
import neton.http.h2.proto.StreamRef
import neton.http.h2.proto.UserPings
import neton.http.h2.proto.Window
import neton.http.header.HeaderMap
import neton.http.header.HeaderValue
import neton.io.bytes.Bytes

// The stream handles shared by clients and servers (`src/share.rs`).
//
// ⚖️ Handles and threads: every handle must be used on the connection's reactor thread (the reference's handles are
// `Send` and synchronise through a mutex). ⚖️ Handles are released with `close()` where the reference releases them
// on drop; `close()` is idempotent, and a handle not closed keeps its stream until the connection ends. The
// reference's `poll_*` functions are suspend functions here.

/**
 * Sends the body of a request or response (`SendStream`): DATA frames, then optionally trailers.
 *
 * Flow control: [sendData] buffers data whatever the window; to send without buffering unboundedly, request capacity
 * with [reserveCapacity], wait for it with [awaitCapacity], and send at most [capacity] bytes. Buffered data per
 * stream counts against the connection's `maxSendBufferSize`.
 *
 * [close] releases the handle; if no other handle to the stream remains and the stream is still open, it is reset
 * with CANCEL (or NO_ERROR, for a server whose response is complete but whose request body is still arriving).
 */
class SendStream internal constructor(internal val ref: StreamRef) : AutoCloseable {
    /** The stream's ID (`stream_id`). */
    val streamId: StreamId get() = ref.streamId

    /**
     * Requests capacity to send [capacity] bytes beyond what is already buffered (`reserve_capacity`); a smaller
     * value than before gives capacity back.
     */
    fun reserveCapacity(capacity: Int) {
        require(capacity >= 0)
        val r = live()
        r.streams.reserveCapacity(r.stream, capacity.toLong())
    }

    /** The capacity currently assigned to the stream (`capacity`). */
    fun capacity(): Int {
        val r = live()
        return r.streams.capacity(r.stream)
    }

    /**
     * Waits until the stream's capacity grows and returns it (`poll_capacity`); null once the stream can no longer
     * send (it was reset or its send half closed).
     */
    suspend fun awaitCapacity(): Int? {
        while (true) {
            val r = live()
            val c = r.streams.send.pollCapacity(r.stream)
            if (c == CAPACITY_NONE) return null
            if (c != CAPACITY_PENDING) return c
            r.stream.sendTask.await()
        }
    }

    /** One non-suspending [awaitCapacity] step: the capacity, [CAPACITY_NONE] or [CAPACITY_PENDING] (tests). */
    internal fun pollCapacityNow(): Int {
        val r = live()
        return r.streams.send.pollCapacity(r.stream)
    }

    /**
     * Sends [data] (`send_data`), ending the stream when [endOfStream]. The data is buffered until flow control lets
     * it go; a payload of 256 bytes or more is written without copying (keep [data] unchanged until then).
     * @throws H2Error when the stream cannot send data any more.
     */
    fun sendData(data: Bytes, endOfStream: Boolean) {
        val r = live()
        r.streams.sendData(r.stream, data, endOfStream).orThrow()
    }

    /** Sends trailers, ending the stream (`send_trailers`). @throws H2Error */
    fun sendTrailers(trailers: HeaderMap<HeaderValue>) {
        val r = live()
        r.streams.sendTrailers(r.stream, trailers).orThrow()
    }

    /** Resets the stream with [reason] (`send_reset`); buffered data is discarded. */
    fun sendReset(reason: Reason) {
        val r = live()
        r.streams.sendResetFromUser(r.stream, reason)
    }

    /**
     * Waits until the peer resets the stream and returns the reason (`poll_reset`); a stream closed by the library
     * or the connection returns its reason too.
     * @throws H2Error for an I/O error.
     */
    suspend fun awaitReset(): Reason = awaitReset(ref, PollReset.Streaming)

    override fun close() = ref.drop()

    private fun live(): StreamRef {
        check(!ref.isDropped) { "SendStream used after close()" }
        return ref
    }

    override fun toString(): String = "SendStream { stream_id: ${streamId.value} }"
}

/** `poll_reset` of a [SendStream] or a server's `SendResponse`. */
internal suspend fun awaitReset(ref: StreamRef, mode: PollReset): Reason {
    while (true) {
        check(!ref.isDropped) { "stream handle used after close()" }
        val reason = h2Call { ref.streams.send.pollReset(ref.stream, mode) }
        if (reason != null) return reason
        ref.stream.sendTask.await()
    }
}

/**
 * Receives the body of a request or response (`RecvStream`): DATA payloads, then optionally trailers.
 *
 * Received data counts against the stream's and the connection's receive windows until it is released with
 * [flowControl]`.releaseCapacity`: without that, the peer stops sending once the window is used up.
 *
 * [close] drops the data not yet read (releasing its capacity) and stops delivering more; the stream itself is only
 * reset once no handle to it remains (so a server may still send its response).
 */
class RecvStream internal constructor(private val flow: FlowControl) : AutoCloseable {
    private var closed = false

    /** The stream's ID. */
    val streamId: StreamId get() = flow.streamId

    /**
     * The next DATA payload, or null at the end of the data (`data`).
     * @throws H2Error when the stream or the connection failed.
     */
    suspend fun data(): Bytes? {
        while (true) {
            val r = flow.live()
            val v = h2Call { r.streams.recv.pollData(r.stream) }
            when {
                v === Recv.PENDING -> r.stream.recvTask.await()
                v == null -> return null
                else -> {
                    val payload = v as Bytes
                    if (r.streams.recv.lastDataBudgeted) r.streams.releaseDataFrame(payload.size)
                    return payload
                }
            }
        }
    }

    /**
     * The trailers once the data is consumed, or null when the stream ended without (`trailers`).
     * @throws H2Error when the stream or the connection failed.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun trailers(): HeaderMap<HeaderValue>? {
        while (true) {
            val r = flow.live()
            val v = h2Call { r.streams.recv.pollTrailers(r.stream) }
            when {
                v === Recv.PENDING -> r.stream.recvTask.await()
                else -> return v as HeaderMap<HeaderValue>?
            }
        }
    }

    /** Whether the peer ended the stream and everything was read (`is_end_stream`). */
    val isEndStream: Boolean
        get() {
            val r = flow.live()
            return r.streams.recv.isEndStream(r.stream)
        }

    /** The receive flow control of the stream (`flow_control`). */
    fun flowControl(): FlowControl = flow

    override fun close() {
        if (closed) return
        closed = true
        // Eagerly drop the received DATA frames: no one can read them any more. The stream is reset only once every
        // handle is gone, since the user may still want to send.
        val r = flow.ref
        if (!r.isDropped) r.streams.clearRecvBuffer(r.stream)
        flow.close()
    }

    override fun toString(): String = "RecvStream { stream_id: ${streamId.value} }"
}

/**
 * The receive flow control of a stream (`FlowControl`): releasing the capacity used by received data lets the peer
 * send more (WINDOW_UPDATE frames are sent once half a window is released).
 */
class FlowControl internal constructor(internal val ref: StreamRef) : AutoCloseable {
    /** The stream's ID. */
    val streamId: StreamId get() = ref.streamId

    /** The receive window the stream has available now; negative after a window decrease (`available_capacity`). */
    fun availableCapacity(): Int {
        val r = live()
        return r.stream.recvFlow.available
    }

    /** The received capacity not yet released (`used_capacity`). */
    fun usedCapacity(): Int = live().stream.inFlightRecvData

    /**
     * Releases [sz] bytes of received data (`release_capacity`).
     * @throws H2Error [UserError.ReleaseCapacityTooBig] for more than was received and not released.
     */
    fun releaseCapacity(sz: Int) {
        require(sz >= 0)
        if (sz > MAX_WINDOW_SIZE) throw H2Error.fromUser(UserError.ReleaseCapacityTooBig)
        val r = live()
        r.streams.releaseCapacity(r.stream, sz).orThrow()
    }

    /** Another handle to the same flow control (`Clone`); close it separately. */
    fun clone(): FlowControl = FlowControl(live().clone())

    override fun close() = ref.drop()

    internal fun live(): StreamRef {
        check(!ref.isDropped) { "stream handle used after close()" }
        return ref
    }

    override fun toString(): String = "FlowControl { stream_id: ${streamId.value} }"
}

/**
 * Sends PINGs and waits for their PONGs (`PingPong`), for keep-alive or round-trip measurement. At most one ping is
 * in flight.
 */
class PingPong internal constructor(private val inner: UserPings) {
    /**
     * Sends a PING and waits for its PONG (`ping`).
     * @throws H2Error [UserError.SendPingWhilePending] when a ping is already in flight; a broken pipe when the
     * connection is closed.
     */
    suspend fun ping(ping: Ping): Pong {
        sendPing(ping)
        return awaitPong()
    }

    /** Sends a PING without waiting (`send_ping`). @throws H2Error as [ping]. */
    fun sendPing(ping: Ping) {
        // The payload cannot be chosen yet (forward compatibility, as in the reference).
        when (val e = inner.sendPing()) {
            null -> {}
            is UserError -> throw H2Error.fromUser(e)
            is ProtoError -> throw H2Error.from(e)
        }
    }

    /** Waits for the PONG of the ping sent (`poll_pong`). @throws H2Error a broken pipe when the connection closes. */
    suspend fun awaitPong(): Pong {
        h2Call { inner.awaitPong() }
        return Pong()
    }

    override fun toString(): String = "PingPong"
}

/** A PING payload (`Ping`); only an opaque one can be sent for now. */
class Ping private constructor() {
    override fun toString(): String = "Ping"

    companion object {
        /** An opaque ping (`Ping::opaque`). */
        fun opaque(): Ping = Ping()
    }
}

/** A received PONG (`Pong`). */
class Pong internal constructor() {
    override fun toString(): String = "Pong"
}

/** The capacity as a window size, for the stream's send side (internal to tests). */
internal fun SendStream.sendWindow(): Int = Window.asSize(ref.stream.sendFlow.window)
