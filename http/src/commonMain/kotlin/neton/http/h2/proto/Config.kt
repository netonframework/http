package neton.http.h2.proto

import neton.http.h2.frame.DEFAULT_INITIAL_WINDOW_SIZE
import neton.http.h2.frame.Settings
import neton.http.h2.frame.StreamId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

// Constants and configuration of the protocol layer (`h2::proto`, `src/proto/mod.rs`, `connection.rs`,
// `streams/mod.rs`).
//
// Window sizes (`WindowSize = u32`) are Ints: every window value is at most 2^31 - 1. Quantities the reference keeps
// as `usize` (buffered bytes, limits) are Longs or Ints as their range needs.

/** The largest flow-control window, 2^31 - 1 (`MAX_WINDOW_SIZE`). */
const val MAX_WINDOW_SIZE: Int = Int.MAX_VALUE

/** Default `max_pending_accept_reset_streams` (`DEFAULT_REMOTE_RESET_STREAM_MAX`). */
const val DEFAULT_REMOTE_RESET_STREAM_MAX: Int = 20

/** Default `max_local_error_reset_streams` (`DEFAULT_LOCAL_RESET_COUNT_MAX`). */
const val DEFAULT_LOCAL_RESET_COUNT_MAX: Int = 1024

/**
 * About the memory of one buffered DATA event (`DEFAULT_DATA_FRAME_OVERHEAD_THRESHOLD`): payloads smaller than this
 * cost more in bookkeeping than they carry.
 */
const val DEFAULT_DATA_FRAME_OVERHEAD_THRESHOLD: Int = 256

/** The smallest automatic DATA frame budget (`DEFAULT_DATA_FRAME_BUDGET`). */
const val DEFAULT_DATA_FRAME_BUDGET: Int = DEFAULT_DATA_FRAME_OVERHEAD_THRESHOLD * 100

/** Empty non-final DATA frames allowed over a connection's lifetime (`MAX_RECV_EMPTY_DATA_FRAMES`). */
const val MAX_RECV_EMPTY_DATA_FRAMES: Int = 100

/** Default `max_concurrent_reset_streams` (`DEFAULT_RESET_STREAM_MAX`). */
const val DEFAULT_RESET_STREAM_MAX: Int = 50

/** Default `reset_stream_duration` (`DEFAULT_RESET_STREAM_SECS`). */
val DEFAULT_RESET_STREAM_DURATION: Duration = 1.seconds

/** Default `max_send_buffer_size` (`DEFAULT_MAX_SEND_BUFFER_SIZE`). */
const val DEFAULT_MAX_SEND_BUFFER_SIZE: Int = 1024 * 400

/** The client connection preface (RFC 9113 §3.4). */
internal val PREFACE: ByteArray = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".encodeToByteArray()

/** How the DATA frame budget is chosen (`DataFrameBudget`). */
sealed class DataFrameBudget {
    /** Half the initial connection window, at least [DEFAULT_DATA_FRAME_BUDGET]. */
    data object Auto : DataFrameBudget()

    /** A fixed budget in bytes. */
    data class Configured(val budget: Int) : DataFrameBudget()

    /** The budget for a connection whose target receive window is [connectionWindow] (null: the default window). */
    fun resolve(connectionWindow: Int?): Int = when (this) {
        is Configured -> budget
        Auto -> maxOf((connectionWindow ?: DEFAULT_INITIAL_WINDOW_SIZE) / 2, DEFAULT_DATA_FRAME_BUDGET)
    }
}

/** Connection configuration (`proto::Config`). */
internal class ConnConfig(
    val nextStreamId: StreamId,
    val initialMaxSendStreams: Int,
    val maxSendBufferSize: Int,
    val resetStreamDuration: Duration,
    val resetStreamMax: Int,
    val remoteResetStreamMax: Int,
    /** null: no limit. */
    val localErrorResetStreamsMax: Int?,
    val settings: Settings,
    val dataFrameBudget: Int,
)

/** Configuration of the stream layer (`streams::Config`). */
internal class StreamsConfig(
    /** Initial maximum number of locally initiated streams, until the peer's SETTINGS say otherwise. */
    val initialMaxSendStreams: Int,
    /** Max DATA bytes to buffer per stream. */
    val localMaxBufferSize: Int,
    /** The stream ID of the next local stream. */
    val localNextStreamId: StreamId,
    /** Whether the local peer accepts push promises. */
    val localPushEnabled: Boolean,
    /** Whether extended CONNECT is enabled locally. */
    val extendedConnectProtocolEnabled: Boolean,
    /** How long a locally reset stream keeps ignoring frames. */
    val localResetDuration: Duration,
    /** Max locally reset streams kept at a time. */
    val localResetMax: Int,
    /** Max remotely reset streams still pending accept; beyond is a connection error. */
    val remoteResetMax: Int,
    /** Initial window of remote-initiated streams. */
    val remoteInitWindowSz: Int,
    /** Max remote-initiated streams; null: unlimited. */
    val remoteMaxInitiated: Int?,
    /** Max library resets over the connection's lifetime; null: unlimited. */
    val localMaxErrorResetStreams: Int?,
    /** The connection-level budget for DATA framing overhead. */
    val dataFrameBudget: Int,
)

/** A copy of builder settings (a builder can hand out several connections). */
internal fun copySettings(s: Settings): Settings {
    val c = Settings()
    c.headerTableSize = s.headerTableSize
    s.isPushEnabled?.let { c.setEnablePush(it) }
    c.maxConcurrentStreams = s.maxConcurrentStreams
    c.initialWindowSize = s.initialWindowSize
    c.maxFrameSize = s.maxFrameSize
    c.maxHeaderListSize = s.maxHeaderListSize
    c.enableConnectProtocol = s.enableConnectProtocol
    return c
}

private val clockOrigin = TimeSource.Monotonic.markNow()

/** A monotonic clock in nanoseconds (`Instant::now`), for reset-stream expiry. */
internal fun monotonicNanos(): Long = clockOrigin.elapsedNow().inWholeNanoseconds

/** `a - b` as unsigned 32-bit arithmetic (wrapping), the way the reference subtracts `WindowSize`s. */
internal fun u32Sub(a: Long, b: Long): Long = (a - b) and 0xffffffffL
