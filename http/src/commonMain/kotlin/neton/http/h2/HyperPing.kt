package neton.http.h2

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import neton.http.HttpError
import neton.io.core.TimeoutException
import neton.io.core.monotonicNanos
import kotlin.coroutines.resume
import kotlin.time.Duration

// hyper's HTTP/2 pings (hyper 1.11.1 `src/proto/h2/ping.rs`), for two optional purposes:
//
// 1. Adaptive flow control (BDP): when a DATA frame is received and no BDP ping is outstanding, record the time and
//    send a ping; count the received bytes; when the pong arrives, merge the round trip into a running average,
//    compute bytes / rtt, and when the sample is at least 2/3 of the current BDP, double the windows.
// 2. Keep-alive: a ping after an interval without received frames; no pong within the timeout ends the connection.
//
// One [PingShared] per connection. [Recorder]s given to the bodies record what they receive; [Ponger.run] is the
// connection's ping coroutine (the reference polls its `Ponger` with the connection future).
//
// ⚖️ The reference measures idleness by the strong count of the shared state (`Arc::strong_count <= 2`): each
// recorder clone held by a stream counts. Here clones are counted explicitly and released when their holder is done
// with them (a body read to its end or closed, a request body pumped, a response received, a tunnel closed).

/** Ping configuration (`ping::Config`). */
internal class PingConfig(
    /** Adaptive window: the initial stream window the BDP estimate starts from, or null when disabled. */
    val bdpInitialWindow: Int?,
    /** If no frames are received in this amount of time, a PING frame is sent. */
    val keepAliveInterval: Duration?,
    /** After sending a keep-alive PING, the connection is closed if no pong arrives within this time. */
    val keepAliveTimeout: Duration,
    /** Sends keep-alive pings even when there are no active streams. */
    val keepAliveWhileIdle: Boolean,
) {
    val isEnabled: Boolean get() = bdpInitialWindow != null || keepAliveInterval != null
}

/** The state shared by the recorders and the ponger (`Shared`). */
internal class PingShared(private val pingPong: PingPong, bdp: Boolean, keepAlive: Boolean) {
    /** When the ping in flight was sent (monotonic ns), or -1 (`ping_sent_at`). */
    var pingSentAt = -1L

    /** BDP enabled: the bytes read during the current sample; -1 when disabled (`bytes`). */
    var bytes: Long = if (bdp) 0 else -1

    /** No BDP ping before this time (monotonic ns); -1 when none (`next_bdp_at`). */
    var nextBdpAt: Long = if (bdp) monotonicNanos() else -1

    /** Keep-alive enabled: when the connection last read a frame (monotonic ns); -1 when disabled (`last_read_at`). */
    var lastReadAt: Long = if (keepAlive) monotonicNanos() else -1

    var isKeepAliveTimedOut = false

    /** Recorder clones held by streams: the connection is idle when there are none. */
    var refs = 0
        private set

    // The ponger, waiting for a ping to be sent or for the connection to become busy.
    private var ponger: CancellableContinuation<Unit>? = null

    val isPingSent: Boolean get() = pingSentAt >= 0

    val isIdle: Boolean get() = refs == 0

    fun retain() {
        if (refs++ == 0) wakePonger() // no longer idle
    }

    fun release() {
        refs--
    }

    fun sendPing() {
        try {
            pingPong.sendPing(Ping.opaque())
            pingSentAt = monotonicNanos() // "sent ping"
            wakePonger()
        } catch (_: H2Error) {
            // "error sending ping"
        }
    }

    suspend fun awaitPong() = pingPong.awaitPong()

    fun updateLastReadAt() {
        if (lastReadAt >= 0) lastReadAt = monotonicNanos()
    }

    suspend fun parkPonger() {
        suspendCancellableCoroutine { c -> ponger = c }
    }

    private fun wakePonger() {
        val c = ponger ?: return
        ponger = null
        if (c.isActive) c.resume(Unit)
    }
}

/**
 * Records received data for the BDP estimate and keep-alive (`Recorder`); the disabled one does nothing. The
 * connection's own recorder is not counted; a [clone] is, until [release]d.
 */
internal class Recorder private constructor(private val shared: PingShared?, private var counted: Boolean) {
    /** `record_data`: DATA received on a stream. */
    fun recordData(len: Int) {
        val s = shared ?: return
        s.updateLastReadAt()
        // Ready for another BDP ping? If not, the bytes are not recorded either.
        if (s.nextBdpAt >= 0) {
            if (monotonicNanos() < s.nextBdpAt) return
            s.nextBdpAt = -1
        }
        // No BDP ping when BDP is disabled.
        if (s.bytes < 0) return
        s.bytes += len
        if (!s.isPingSent) s.sendPing()
    }

    /** `record_non_data`: a frame other than DATA received (a head, trailers). */
    fun recordNonData() {
        shared?.updateLastReadAt()
    }

    /** Another counted handle (the reference's `Clone`). */
    fun clone(): Recorder {
        val s = shared ?: return this
        s.retain()
        return Recorder(s, true)
    }

    /** Releases a counted handle (the reference's `Drop`); idempotent. */
    fun release() {
        if (!counted) return
        counted = false
        shared!!.release()
    }

    /** The disabled recorder when the incoming stream already ended (`for_stream`); releases this one then. */
    fun forStream(isEndStream: Boolean): Recorder {
        if (!isEndStream) return this
        release()
        return DISABLED
    }

    /** @throws HttpError "keep-alive timed out" once the keep-alive timed out (`ensure_not_timed_out`). */
    fun ensureNotTimedOut() {
        if (shared?.isKeepAliveTimedOut == true) throw keepAliveTimedOut()
    }

    companion object {
        /** `ping::disabled()`. */
        val DISABLED = Recorder(null, false)

        /** The connection's recorder (`ping::channel`), not counted. */
        fun of(shared: PingShared): Recorder = Recorder(shared, false)
    }
}

/** hyper `KeepAliveTimedOut::crate_error`: an HTTP/2 error whose cause is a timeout (so `isTimeout()` holds). */
internal fun keepAliveTimedOut(): HttpError = HttpError(HttpError.Kind.Http2, TimeoutException("keep-alive timed out"))

/** The BDP estimator (`Bdp`). */
internal class Bdp(initialWindow: Int) {
    /** Current BDP in bytes. */
    var bdp: Int = initialWindow
        private set

    /** Largest bandwidth seen so far. */
    private var maxBandwidth = 0.0

    /** Round trip time in seconds. */
    private var rtt = 0.0

    /** Delay before the next ping (ns); changes with how stable the bandwidth is (`ping_delay`). */
    var pingDelayNanos: Long = 100_000_000L
        private set

    /** Round trips where the BDP stayed the same. */
    private var stableCount = 0

    /** `calculate`: the new window size, or -1 when unchanged. */
    fun calculate(bytes: Long, rttNanos: Long): Int {
        // No need to do any math if we're at the limit.
        if (bdp == BDP_LIMIT) {
            stabilizeDelay()
            return -1
        }
        // Average the RTT: the first sample is the RTT, later ones weigh 1/8 in a moving average.
        val sample = rttNanos / 1_000_000_000.0
        if (rtt == 0.0) rtt = sample else rtt += (sample - rtt) * 0.125

        // The current bandwidth.
        val bw = bytes / (rtt * 1.5)
        if (bw < maxBandwidth) {
            // Not a faster bandwidth, so don't update.
            stabilizeDelay()
            return -1
        }
        maxBandwidth = bw

        // A sample of at least 2/3 of the previous BDP: double the sample.
        if (bytes >= bdp.toLong() * 2 / 3) {
            bdp = minOf(bytes * 2, BDP_LIMIT.toLong()).toInt() // "BDP increased"
            stableCount = 0
            pingDelayNanos /= 2
            return bdp
        }
        stabilizeDelay()
        return -1
    }

    private fun stabilizeDelay() {
        if (pingDelayNanos < 10_000_000_000L) {
            stableCount += 1
            if (stableCount >= 2) {
                pingDelayNanos *= 4
                stableCount = 0
            }
        }
    }

    companion object {
        /** Any higher than this likely hits TCP flow control. */
        const val BDP_LIMIT = 16 * 1024 * 1024
    }
}

/**
 * The ping side of a connection (`Ponger`): keep-alive scheduling and timeout, and the pongs of BDP pings. [run] is
 * the connection's ping coroutine; [onSizeUpdate] and [onKeepAliveTimedOut] are the reference's `Ponged` results.
 */
internal class Ponger(
    private val shared: PingShared,
    config: PingConfig,
    private val onSizeUpdate: (Int) -> Unit,
    private val onKeepAliveTimedOut: () -> Unit,
) {
    private val bdp = config.bdpInitialWindow?.let { Bdp(it) }
    private var keepAlive = config.keepAliveInterval != null
    private val interval = config.keepAliveInterval?.inWholeNanoseconds ?: 0L
    private val timeout = config.keepAliveTimeout.inWholeNanoseconds
    private val whileIdle = config.keepAliveWhileIdle

    // `KeepAliveState`: Init, Scheduled(at), PingSent (its timeout at [timeoutAt]).
    private var state = INIT
    private var scheduledAt = 0L
    private var timeoutAt = 0L

    /** Runs until the keep-alive times out or the connection closes (or the coroutine is cancelled). */
    suspend fun run() {
        while (true) {
            val now = monotonicNanos()
            val idle = shared.isIdle
            if (keepAlive) {
                maybeSchedule(idle)
                if (maybePing(now, idle)) continue
                if (state == PING_SENT && !shared.isPingSent) {
                    // The keep-alive ping could not be sent: the connection is closing (the reference is not polled
                    // again then). Wait to be cancelled with it.
                    shared.parkPonger()
                    continue
                }
            }
            if (!shared.isPingSent) {
                // Nothing in flight: wait for the keep-alive deadline, a recorder's ping, or the connection turning busy.
                waitFor(keepAliveDeadline(now))
                continue
            }
            // A ping is in flight (`poll_pong`).
            val deadline = when {
                !keepAlive -> -1L
                state == PING_SENT -> timeoutAt
                else -> keepAliveDeadline(now)
            }
            when (awaitPong(deadline)) {
                PONG -> {
                    val at = monotonicNanos()
                    val rtt = at - shared.pingSentAt
                    shared.pingSentAt = -1 // "recv pong"
                    if (keepAlive) shared.updateLastReadAt()
                    val b = bdp
                    if (b != null) {
                        val bytes = shared.bytes
                        shared.bytes = 0
                        // "received BDP ack"
                        val update = b.calculate(bytes, rtt)
                        shared.nextBdpAt = at + b.pingDelayNanos
                        if (update >= 0) onSizeUpdate(update)
                    }
                }
                // "pong error": the connection is closing.
                FAILED -> return
                else -> {
                    if (keepAlive && state == PING_SENT && monotonicNanos() >= timeoutAt) {
                        // "keep-alive timeout reached"
                        keepAlive = false
                        shared.isKeepAliveTimedOut = true
                        onKeepAliveTimedOut()
                        return
                    }
                }
            }
        }
    }

    /** When the scheduled keep-alive ping is due, or -1 (none, or due already but waiting for a busy connection). */
    private fun keepAliveDeadline(now: Long): Long =
        if (keepAlive && state == SCHEDULED && now < scheduledAt) scheduledAt else -1L

    private suspend fun waitFor(deadline: Long) {
        if (deadline < 0) {
            shared.parkPonger()
        } else {
            withTimeoutOrNull(nanosToMillis(deadline - monotonicNanos())) { shared.parkPonger() }
        }
    }

    private suspend fun awaitPong(deadline: Long): Int {
        try {
            if (deadline < 0) {
                shared.awaitPong()
                return PONG
            }
            val left = deadline - monotonicNanos()
            if (left <= 0) return TIMED_OUT
            return if (withTimeoutOrNull(nanosToMillis(left)) { shared.awaitPong() } != null) PONG else TIMED_OUT
        } catch (_: H2Error) {
            return FAILED
        }
    }

    /** `maybe_schedule`. */
    private fun maybeSchedule(idle: Boolean) {
        when (state) {
            INIT -> {
                if (!whileIdle && idle) return
                schedule()
            }
            PING_SENT -> {
                if (shared.isPingSent) return
                schedule()
            }
        }
    }

    private fun schedule() {
        scheduledAt = shared.lastReadAt + interval
        state = SCHEDULED
    }

    /** `maybe_ping`: true when the state went back to Init and the loop should look again. */
    private fun maybePing(now: Long, idle: Boolean): Boolean {
        if (state != SCHEDULED || now < scheduledAt) return false
        // A frame was received while scheduled: schedule again from it.
        if (shared.lastReadAt + interval > scheduledAt) {
            state = INIT
            return true
        }
        // "keep-alive no need to ping when idle and while_idle=false"
        if (!whileIdle && idle) return false
        // "keep-alive interval reached"
        shared.sendPing()
        state = PING_SENT
        timeoutAt = monotonicNanos() + timeout
        return false
    }

    private fun nanosToMillis(n: Long): Long = maxOf(1L, (n + 999_999) / 1_000_000)

    private companion object {
        const val INIT = 0
        const val SCHEDULED = 1
        const val PING_SENT = 2

        const val PONG = 0
        const val TIMED_OUT = 1
        const val FAILED = 2
    }
}
