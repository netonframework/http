package neton.http.h2.proto

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import neton.http.h2.codec.Codec
import neton.http.h2.codec.UserError
import neton.http.h2.frame.GoAway
import neton.http.h2.frame.Ping
import neton.http.h2.frame.Reason
import neton.http.h2.frame.Settings
import neton.http.h2.frame.StreamId
import kotlin.coroutines.resume

// The connection-level control state: SETTINGS exchange (`src/proto/settings.rs`), GOAWAY (`go_away.rs`) and
// PING / PONG (`ping_pong.rs`). Each buffers its frames into the codec from the connection's driver, which ensures
// the codec has room first (the reference's `poll_ready` checks).

/**
 * Our SETTINGS and the peer's (`Settings`): local settings are ToSend → WaitingAck → Synced and take effect on the
 * receive side only once acknowledged; a received SETTINGS is acknowledged (and applied) before any further frame
 * is read.
 */
internal class SettingsState(local: Settings) {
    // The initial local SETTINGS were flushed during the handshake: waiting for their ACK.
    private var localToSend: Settings? = null
    private var localWaitingAck: Settings? = local

    /** A received SETTINGS pending its ACK. */
    var remote: Settings? = null
        private set

    private var hasReceivedRemoteInitialSettings = false

    /** Whether frames must be buffered before the next frame is read: an ACK to send, or new local settings. */
    val hasPending: Boolean get() = remote != null || localToSend != null

    /**
     * A received SETTINGS (`recv_settings`): an ACK applies our settings to the codec and the streams; a non-ACK is
     * kept for [pollSend].
     * @throws ProtoError a GOAWAY PROTOCOL_ERROR for an unexpected ACK, or a window error.
     */
    fun recvSettings(frame: Settings, codec: Codec, streams: Streams) {
        if (frame.isAck) {
            val local = localWaitingAck ?: throw ProtoError.libraryGoAway(Reason.PROTOCOL_ERROR) // "unexpected settings ack"
            local.maxFrameSize?.let { codec.setMaxRecvFrameSize(it.toInt()) }
            local.maxHeaderListSize?.let { codec.setMaxRecvHeaderListSize(clampToInt(it)) }
            local.headerTableSize?.let { codec.setRecvHeaderTableSize(clampToInt(it)) }
            streams.applyLocalSettings(local)
            localWaitingAck = null
        } else {
            // Every SETTINGS is acknowledged before more frames are read.
            check(remote == null)
            remote = frame
        }
    }

    /** Queues new local SETTINGS (`send_settings`); only once the previous ones were acknowledged. */
    fun sendSettings(frame: Settings): UserError? {
        check(!frame.isAck)
        if (localToSend != null || localWaitingAck != null) return UserError.SendSettingsWhilePending
        localToSend = frame
        return null
    }

    /**
     * Buffers the ACK of a received SETTINGS and applies it, then our pending SETTINGS (`poll_send`). The codec
     * must have room for each frame; returns false when it had none (buffer again after a flush).
     * @throws ProtoError from applying the peer's settings.
     */
    fun pollSend(dst: Codec, streams: Streams): Boolean {
        val settings = remote
        if (settings != null) {
            if (!dst.hasSendCapacity()) return false
            check(dst.buffer(Settings.ack()) == null)
            val isInitial = !hasReceivedRemoteInitialSettings
            hasReceivedRemoteInitialSettings = true
            remote = null
            streams.applyRemoteSettings(settings, isInitial)
            settings.headerTableSize?.let { dst.setSendHeaderTableSize(clampToInt(it)) }
            settings.maxFrameSize?.let { dst.setMaxSendFrameSize(it.toInt()) }
        }
        val local = localToSend
        if (local != null) {
            if (!dst.hasSendCapacity()) return false
            check(dst.buffer(local) == null)
            localToSend = null
            localWaitingAck = local
        }
        return true
    }

    private fun clampToInt(v: Long): Int = if (v > Int.MAX_VALUE) Int.MAX_VALUE else v.toInt()
}

/** Our sending of GOAWAY frames (`GoAway`). */
internal class GoAwayState {
    /** Close now, or once idle. */
    private var closeNow = false

    /** The last GOAWAY sent (its last stream ID and reason), if any (`going_away`). */
    private var goingAwayId: StreamId? = null
    private var goingAwayReason: Reason = Reason.NO_ERROR

    /** The user started it with `abrupt_shutdown`. */
    var isUserInitiated = false
        private set

    /** A GOAWAY to buffer at once. */
    var pending: GoAway? = null
        private set

    /** Queues a GOAWAY; the connection runs until idle (`go_away`). */
    fun goAway(f: GoAway) {
        goingAwayId?.let {
            check(f.lastStreamId <= it) {
                "GOAWAY stream IDs shouldn't be higher; last_processed_id = $it, f.last_stream_id() = ${f.lastStreamId}"
            }
        }
        goingAwayId = f.lastStreamId
        goingAwayReason = f.reason
        pending = f
    }

    fun goAwayNow(f: GoAway) {
        closeNow = true
        // Never the same GOAWAY twice.
        if (goingAwayId == f.lastStreamId && goingAwayReason == f.reason) return
        goAway(f)
    }

    fun goAwayFromUser(f: GoAway) {
        isUserInitiated = true
        goAwayNow(f)
    }

    /** Whether a GOAWAY was ever scheduled. */
    val isGoingAway: Boolean get() = goingAwayId != null

    /** The reason of the GOAWAY sent, or null. */
    val goingAwayReasonOrNull: Reason? get() = if (goingAwayId != null) goingAwayReason else null

    val shouldCloseNow: Boolean get() = pending == null && closeNow

    /** Close once idle: a GOAWAY with a real last stream ID was sent, without closing now. */
    val shouldCloseOnIdle: Boolean
        get() = !closeNow && goingAwayId.let { it != null && it != StreamId.MAX }

    /**
     * Buffers the pending GOAWAY (`send_pending_go_away`): returns its reason when one was buffered, or the reason of
     * the one sent when the connection should close now, else null. The codec must have room ([Codec.hasSendCapacity]).
     */
    fun sendPendingGoAway(dst: Codec): Reason? {
        val frame = pending
        if (frame != null) {
            check(dst.buffer(frame) == null)
            pending = null
            return frame.reason
        }
        if (shouldCloseNow) return goingAwayReasonOrNull
        return null
    }
}

/** What a received PING means (`ReceivedPing`). */
internal enum class ReceivedPing {
    MustAck,
    Unknown,
    Shutdown,
}

/**
 * PING handling (`PingPong`): PONGs for the peer's PINGs, the shutdown PING of a graceful shutdown, and the user's
 * pings.
 */
internal class PingPongState {
    /** The payload of our PING (the shutdown one), and whether it was written. */
    private var pendingPing: Long = 0
    private var hasPendingPing = false
    private var pendingPingSent = false

    /** A PONG to send. */
    private var pendingPong: Long = 0
    var hasPendingPong = false
        private set

    var userPings: UserPings? = null
        private set

    /** The user's ping handle, once (`take_user_pings`). */
    fun takeUserPings(wake: Task): UserPings? {
        if (userPings != null) return null
        return UserPings(wake).also { userPings = it }
    }

    fun pingShutdown() {
        check(!hasPendingPing)
        pendingPing = Ping.SHUTDOWN
        hasPendingPing = true
        pendingPingSent = false
    }

    /** Whether frames must be buffered before the next frame is read. */
    val hasPending: Boolean
        get() = hasPendingPong ||
            // A user ping waits while the shutdown ping is in flight, as in `send_pending_ping`.
            if (hasPendingPing) !pendingPingSent else userPings?.state == UserPings.PENDING_PING

    /** A received PING (`recv_ping`). */
    fun recvPing(ping: Ping): ReceivedPing {
        // Pending pongs are sent before the next frame is read.
        check(!hasPendingPong)
        if (ping.isAck) {
            if (hasPendingPing && pendingPing == ping.payload) {
                check(pendingPing == Ping.SHUTDOWN) { "pending_ping should be for shutdown" }
                hasPendingPing = false
                return ReceivedPing.Shutdown
            }
            val users = userPings
            if (users != null && ping.payload == Ping.USER && users.receivePong()) return ReceivedPing.Unknown
            // A pong for a ping we never sent: nothing required by the spec, ignored.
            return ReceivedPing.Unknown
        }
        // Keep the payload for the acknowledgement.
        pendingPong = ping.payload
        hasPendingPong = true
        return ReceivedPing.MustAck
    }

    /** Buffers the pending PONG (`send_pending_pong`); false when the codec has no room. */
    fun sendPendingPong(dst: Codec): Boolean {
        if (!hasPendingPong) return true
        if (!dst.hasSendCapacity()) return false
        check(dst.buffer(Ping.pong(pendingPong)) == null)
        hasPendingPong = false
        return true
    }

    /** Buffers our pending PING, or the user's (`send_pending_ping`); false when the codec has no room. */
    fun sendPendingPing(dst: Codec): Boolean {
        if (hasPendingPing) {
            if (!pendingPingSent) {
                if (!dst.hasSendCapacity()) return false
                check(dst.buffer(Ping(pendingPing)) == null)
                pendingPingSent = true
            }
        } else {
            val users = userPings
            if (users != null && users.state == UserPings.PENDING_PING) {
                if (!dst.hasSendCapacity()) return false
                check(dst.buffer(Ping(Ping.USER)) == null)
                users.state = UserPings.PENDING_PONG
            }
        }
        return true
    }

    /** The connection is gone (`Drop for UserPingsRx`). */
    fun close() {
        userPings?.close()
    }
}

/**
 * The user's side of pings (`UserPings` / `UserPingsInner`): at most one ping in flight.
 *
 * ⚖️ The reference uses an atomic state shared with the connection task; here both sides are on the connection's
 * thread, so it is a plain field.
 */
internal class UserPings(private val wakeConnection: Task) {
    var state = EMPTY
    private var pongWaiter: CancellableContinuation<Unit>? = null

    /**
     * Requests a ping (`send_ping`): null when queued; [UserError.SendPingWhilePending] when one is in flight; a broken
     * pipe [ProtoError] when the connection is closed.
     */
    fun sendPing(): Any? {
        return when (state) {
            EMPTY -> {
                state = PENDING_PING
                wakeConnection.wake()
                null
            }
            CLOSED -> ProtoError.Io(IoErrorKind.BrokenPipe)
            else -> UserError.SendPingWhilePending
        }
    }

    /** Waits for the pong (`poll_pong`). @throws ProtoError a broken pipe when the connection closes. */
    suspend fun awaitPong() {
        while (true) {
            when (state) {
                RECEIVED_PONG -> {
                    state = EMPTY
                    return
                }
                CLOSED -> throw ProtoError.Io(IoErrorKind.BrokenPipe)
            }
            suspendCancellableCoroutine { c -> pongWaiter = c }
        }
    }

    /** The user's PONG arrived (`receive_pong`). */
    fun receivePong(): Boolean {
        if (state != PENDING_PONG) return false
        state = RECEIVED_PONG
        wakePong()
        return true
    }

    fun close() {
        state = CLOSED
        wakePong()
    }

    private fun wakePong() {
        val w = pongWaiter ?: return
        pongWaiter = null
        if (w.isActive) w.resume(Unit)
    }

    companion object {
        /** No user ping pending. */
        const val EMPTY = 0

        /** `send_ping` was called, the PING is not written yet. */
        const val PENDING_PING = 1

        /** The PING was written, waiting for the PONG. */
        const val PENDING_PONG = 2

        /** The PONG arrived, waiting for `poll_pong`. */
        const val RECEIVED_PONG = 3

        /** The connection is closed. */
        const val CLOSED = 4
    }
}
