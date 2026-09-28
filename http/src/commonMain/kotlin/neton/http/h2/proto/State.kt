package neton.http.h2.proto

import neton.http.h2.codec.UserError
import neton.http.h2.frame.Headers
import neton.http.h2.frame.Reason
import neton.http.h2.frame.Reset
import neton.http.h2.frame.StreamId

/** Which public API is asking for a stream's reset (`PollReset`). */
internal enum class PollReset {
    AwaitingHeaders,
    Streaming,
}

/**
 * The state of an HTTP/2 stream (`State`, `src/proto/streams/state.rs`; RFC 9113 §5.1):
 *
 * ```
 *                              +--------+
 *                      send PP |        | recv PP
 *                     ,--------|  idle  |--------.
 *                    /         |        |         \
 *                   v          +--------+          v
 *            +----------+          |           +----------+
 *            |          |          | send H /  |          |
 *     ,------| reserved |          | recv H    | reserved |------.
 *     |      | (local)  |          |           | (remote) |      |
 *     |      +----------+          v           +----------+      |
 *     |          |             +--------+             |          |
 *     |          |     recv ES |        | send ES     |          |
 *     |   send H |     ,-------|  open  |-------.     | recv H   |
 *     |          |    /        |        |        \    |          |
 *     |          v   v         +--------+         v   v          |
 *     |      +----------+          |           +----------+      |
 *     |      |   half   |          |           |   half   |      |
 *     |      |  closed  |          | send R /  |  closed  |      |
 *     |      | (remote) |          | recv R    | (local)  |      |
 *     |      +----------+          |           +----------+      |
 *     |           |                |                 |           |
 *     |           | send ES /      |       recv ES / |           |
 *     |           | send R /       v        send R / |           |
 *     |           | recv R     +--------+   recv R   |           |
 *     | send R /  `----------->|        |<-----------'  send R / |
 *     | recv R                 | closed |               recv R   |
 *     `----------------------->|        |<----------------------'
 *                              +--------+
 * ```
 *
 * The reference's `Inner` enum with its `Peer` and `Cause` payloads is flattened into Ints; a closing error is kept
 * as its parts (a reset's stream ID, reason and initiator) or as a shared [ProtoError] (a connection error), so that
 * resetting a stream does not allocate an exception. [error] builds the [ProtoError] when it is asked for.
 */
internal class State {
    private var inner = IDLE

    // Open: [local] and [remote]; HalfClosedLocal / HalfClosedRemote: [peer]. AWAITING_HEADERS or STREAMING.
    private var local = AWAITING_HEADERS
    private var remote = AWAITING_HEADERS
    private var peer = AWAITING_HEADERS

    // Closed: the cause, and for the error causes, the error.
    private var cause = CAUSE_NONE
    private var errReason = Reason.NO_ERROR
    private var errInitiator = Initiator.Library
    private var errStreamId = 0
    private var errShared: ProtoError? = null // a GOAWAY or I/O error; null for a reset

    /** Opens the send half (`send_open`). @return a [UserError] or null. */
    fun sendOpen(eos: Boolean): UserError? {
        when {
            inner == IDLE -> if (eos) halfClosedLocal(AWAITING_HEADERS) else open(STREAMING, AWAITING_HEADERS)
            inner == OPEN && local == AWAITING_HEADERS -> if (eos) halfClosedLocal(remote) else open(STREAMING, remote)
            inner == HALF_CLOSED_REMOTE && peer == AWAITING_HEADERS || inner == RESERVED_LOCAL ->
                if (eos) closed(CAUSE_END_STREAM) else halfClosedRemote(STREAMING)
            // All other transitions are a protocol error.
            else -> return UserError.UnexpectedFrameType
        }
        return null
    }

    /**
     * Opens the receive half on a HEADERS frame (`recv_open`); returns whether this is the stream's initial HEADERS.
     * @throws ProtoError a GOAWAY PROTOCOL_ERROR in any other state.
     */
    fun recvOpen(frame: Headers): Boolean {
        var initial = false
        val eos = frame.isEndStream
        when {
            inner == IDLE -> {
                initial = true
                if (eos) {
                    halfClosedRemote(AWAITING_HEADERS)
                } else {
                    // 1xx response headers are skipped.
                    open(AWAITING_HEADERS, if (frame.isInformational) AWAITING_HEADERS else STREAMING)
                }
            }
            inner == RESERVED_REMOTE -> {
                initial = true
                if (eos) {
                    closed(CAUSE_END_STREAM)
                } else if (frame.isInformational) {
                    // stays ReservedRemote
                } else {
                    halfClosedLocal(STREAMING)
                }
            }
            inner == OPEN && remote == AWAITING_HEADERS -> {
                if (eos) halfClosedRemote(local) else open(local, if (frame.isInformational) AWAITING_HEADERS else STREAMING)
            }
            inner == HALF_CLOSED_LOCAL && peer == AWAITING_HEADERS -> {
                if (eos) {
                    closed(CAUSE_END_STREAM)
                } else if (!frame.isInformational) {
                    halfClosedLocal(STREAMING)
                }
            }
            // "recv_open: in unexpected state"
            else -> throw ProtoError.libraryGoAway(Reason.PROTOCOL_ERROR)
        }
        return initial
    }

    /** Idle → ReservedRemote (`reserve_remote`). @throws ProtoError a GOAWAY PROTOCOL_ERROR otherwise. */
    fun reserveRemote() {
        if (inner != IDLE) throw ProtoError.libraryGoAway(Reason.PROTOCOL_ERROR)
        inner = RESERVED_REMOTE
    }

    /** Idle → ReservedLocal (`reserve_local`). */
    fun reserveLocal(): UserError? {
        if (inner != IDLE) return UserError.UnexpectedFrameType
        inner = RESERVED_LOCAL
        return null
    }

    /** The remote will send no more data (`recv_close`). @throws ProtoError a GOAWAY PROTOCOL_ERROR in other states. */
    fun recvClose() {
        when (inner) {
            OPEN -> halfClosedRemote(local)
            HALF_CLOSED_LOCAL -> closed(CAUSE_END_STREAM)
            else -> throw ProtoError.libraryGoAway(Reason.PROTOCOL_ERROR)
        }
    }

    /** The remote sent RST_STREAM (`recv_reset`); [queued]: the stream has frames waiting to be sent. */
    fun recvReset(frame: Reset, queued: Boolean) {
        // A closed stream stays as it is, unless frames are still queued: then the reset overrides the state so that
        // the queue is cleared by `pop_frame` (a scheduled reset, or an EOS that was queued but not yet sent).
        if (inner == CLOSED && !queued) return
        val recvEndStream = isRecvEndStream
        closedWithReset(if (recvEndStream) CAUSE_ERROR_AFTER_END_STREAM else CAUSE_ERROR, frame.streamId, frame.reason, Initiator.Remote)
    }

    /** A connection-level error (`handle_error`). */
    fun handleError(err: ProtoError) {
        if (inner == CLOSED) return
        closedWithShared(CAUSE_ERROR, err)
    }

    /** The connection ended (`recv_eof`): a broken pipe. */
    fun recvEof() {
        if (inner == CLOSED) return
        closedWithShared(CAUSE_ERROR, STREAM_BROKEN_PIPE)
    }

    /** The local side will send no more data (`send_close`). */
    fun sendClose() {
        when (inner) {
            OPEN -> halfClosedLocal(remote)
            HALF_CLOSED_REMOTE -> closed(CAUSE_END_STREAM)
            else -> throw IllegalStateException("send_close: unexpected state $this")
        }
    }

    /** Reset locally (`set_reset`). */
    fun setReset(streamId: StreamId, reason: Reason, initiator: Initiator) {
        closedWithReset(CAUSE_ERROR, streamId, reason, initiator)
    }

    /** A reset to be sent once the send queue is flushed (`set_scheduled_reset`). */
    fun setScheduledReset(reason: Reason) {
        inner = CLOSED
        cause = CAUSE_SCHEDULED_LIBRARY_RESET
        errReason = reason
        errShared = null
    }

    /** The scheduled reset's reason, or null (`get_scheduled_reset`). */
    val scheduledReset: Reason?
        get() = if (inner == CLOSED && cause == CAUSE_SCHEDULED_LIBRARY_RESET) errReason else null

    val isScheduledReset: Boolean get() = inner == CLOSED && cause == CAUSE_SCHEDULED_LIBRARY_RESET

    /** Closed by a local error, or with a scheduled reset (`is_local_error`). */
    val isLocalError: Boolean
        get() {
            if (inner != CLOSED) return false
            return when (cause) {
                CAUSE_ERROR, CAUSE_ERROR_AFTER_END_STREAM -> errorIsLocal()
                CAUSE_SCHEDULED_LIBRARY_RESET -> true
                else -> false
            }
        }

    /** Closed by a RST_STREAM from the peer (`is_remote_reset`). */
    val isRemoteReset: Boolean
        get() = inner == CLOSED && (cause == CAUSE_ERROR || cause == CAUSE_ERROR_AFTER_END_STREAM) &&
            errShared == null && errInitiator == Initiator.Remote

    /** Already reset (`is_reset`): closed for any reason but END_STREAM. */
    val isReset: Boolean get() = inner == CLOSED && cause != CAUSE_END_STREAM

    val isSendStreaming: Boolean
        get() = inner == OPEN && local == STREAMING || inner == HALF_CLOSED_REMOTE && peer == STREAMING

    /** In a state to receive headers (`is_recv_headers`). */
    val isRecvHeaders: Boolean
        get() = inner == IDLE || inner == OPEN && remote == AWAITING_HEADERS ||
            inner == HALF_CLOSED_LOCAL && peer == AWAITING_HEADERS || inner == RESERVED_REMOTE

    val isRecvStreaming: Boolean
        get() = inner == OPEN && remote == STREAMING || inner == HALF_CLOSED_LOCAL && peer == STREAMING

    /** END_STREAM has been received (`is_recv_end_stream`). */
    val isRecvEndStream: Boolean
        get() = inner == CLOSED && (cause == CAUSE_END_STREAM || cause == CAUSE_ERROR_AFTER_END_STREAM) ||
            inner == HALF_CLOSED_REMOTE

    val isClosed: Boolean get() = inner == CLOSED

    val isSendClosed: Boolean get() = inner == CLOSED || inner == HALF_CLOSED_LOCAL || inner == RESERVED_REMOTE

    val isIdle: Boolean get() = inner == IDLE

    /**
     * Whether more may be received (`ensure_recv_open`): true while open, false once the receive half ended.
     * @throws ProtoError the stream's error when it was closed by one; a GOAWAY with the reason of a scheduled reset.
     */
    fun ensureRecvOpen(): Boolean {
        if (inner == CLOSED) {
            return when (cause) {
                CAUSE_ERROR -> throw error()
                CAUSE_SCHEDULED_LIBRARY_RESET -> throw ProtoError.libraryGoAway(errReason)
                else -> false // EndStream, ErrorAfterEndStream
            }
        }
        return !(inner == HALF_CLOSED_REMOTE || inner == RESERVED_LOCAL)
    }

    /**
     * The reason the stream was reset, or null (`ensure_reason`).
     * @throws ProtoError the stream's I/O error; [UserError.PollResetAfterSendResponse] as [UserErrorException] when
     * asked in [PollReset.AwaitingHeaders] mode after the response was sent.
     */
    fun ensureReason(mode: PollReset): Reason? {
        if (inner == CLOSED) {
            when (cause) {
                CAUSE_SCHEDULED_LIBRARY_RESET -> return errReason
                CAUSE_ERROR, CAUSE_ERROR_AFTER_END_STREAM -> {
                    val shared = errShared
                    return when {
                        shared == null -> errReason
                        shared is ProtoError.GoAway -> shared.reason
                        else -> throw shared
                    }
                }
            }
            return null
        }
        if (isSendStreaming) {
            if (mode == PollReset.AwaitingHeaders) throw UserErrorException(UserError.PollResetAfterSendResponse)
        }
        return null
    }

    /** The error a closed stream ended with (a reset is created here), for the error causes. */
    fun error(): ProtoError = errShared ?: ProtoError.Reset(StreamId(errStreamId), errReason, errInitiator)

    private fun errorIsLocal(): Boolean {
        val shared = errShared
        return shared?.isLocal ?: errInitiator.isLocal
    }

    private fun open(local: Int, remote: Int) {
        inner = OPEN
        this.local = local
        this.remote = remote
    }

    private fun halfClosedLocal(peer: Int) {
        inner = HALF_CLOSED_LOCAL
        this.peer = peer
    }

    private fun halfClosedRemote(peer: Int) {
        inner = HALF_CLOSED_REMOTE
        this.peer = peer
    }

    private fun closed(cause: Int) {
        inner = CLOSED
        this.cause = cause
        errShared = null
    }

    private fun closedWithReset(cause: Int, streamId: StreamId, reason: Reason, initiator: Initiator) {
        inner = CLOSED
        this.cause = cause
        errStreamId = streamId.value
        errReason = reason
        errInitiator = initiator
        errShared = null
    }

    private fun closedWithShared(cause: Int, err: ProtoError) {
        inner = CLOSED
        this.cause = cause
        errShared = err
    }

    override fun toString(): String = when (inner) {
        IDLE -> "Idle"
        RESERVED_LOCAL -> "ReservedLocal"
        RESERVED_REMOTE -> "ReservedRemote"
        OPEN -> "Open { local: ${peerName(local)}, remote: ${peerName(remote)} }"
        HALF_CLOSED_LOCAL -> "HalfClosedLocal(${peerName(peer)})"
        HALF_CLOSED_REMOTE -> "HalfClosedRemote(${peerName(peer)})"
        else -> when (cause) {
            CAUSE_END_STREAM -> "Closed(EndStream)"
            CAUSE_SCHEDULED_LIBRARY_RESET -> "Closed(ScheduledLibraryReset($errReason))"
            CAUSE_ERROR_AFTER_END_STREAM -> "Closed(ErrorAfterEndStream(${error()}))"
            else -> "Closed(Error(${error()}))"
        }
    }

    private companion object {
        const val IDLE = 0
        const val RESERVED_LOCAL = 1
        const val RESERVED_REMOTE = 2
        const val OPEN = 3
        const val HALF_CLOSED_LOCAL = 4
        const val HALF_CLOSED_REMOTE = 5
        const val CLOSED = 6

        const val AWAITING_HEADERS = 0
        const val STREAMING = 1

        const val CAUSE_NONE = 0
        const val CAUSE_END_STREAM = 1
        const val CAUSE_ERROR = 2
        const val CAUSE_ERROR_AFTER_END_STREAM = 3
        const val CAUSE_SCHEDULED_LIBRARY_RESET = 4

        fun peerName(p: Int) = if (p == STREAMING) "Streaming" else "AwaitingHeaders"

        /** "stream closed because of a broken pipe", shared by every stream closed by EOF. */
        val STREAM_BROKEN_PIPE: ProtoError = ProtoError.Io(IoErrorKind.BrokenPipe, "stream closed because of a broken pipe")
    }
}

/**
 * A [UserError] raised where the reference returns `Err(UserError)` from a stream operation that otherwise returns a
 * value; converted to the public error at the API boundary.
 */
internal class UserErrorException(val error: UserError) : Exception(error.message)
