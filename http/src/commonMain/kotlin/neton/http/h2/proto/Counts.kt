package neton.http.h2.proto

import neton.http.h2.frame.Settings

/** A connection-level byte budget (`Budget`, `src/proto/streams/counts.rs`). */
internal class Budget(val max: Int) {
    var available: Int = max
        internal set

    /** Consumes [amount]; false when the budget cannot cover it (`BudgetExhausted`), leaving it unchanged. */
    fun consume(amount: Int): Boolean {
        if (amount > available) return false
        available -= amount
        return true
    }

    fun replenish(amount: Int) {
        val v = available.toLong() + amount
        available = minOf(v, max.toLong()).toInt()
    }
}

/**
 * Stream counts and limits of a connection (`Counts`): concurrency in both directions, locally reset streams kept for
 * a while, remotely reset streams still pending accept (rapid reset), library resets over the connection's lifetime,
 * and the DATA framing budgets.
 *
 * Limits that are `usize::MAX` in the reference are [Int.MAX_VALUE].
 */
internal class Counts(val peer: Peer, config: StreamsConfig) {
    /** Max locally initiated streams (the peer's SETTINGS_MAX_CONCURRENT_STREAMS). */
    var maxSendStreams: Int = config.initialMaxSendStreams
        private set
    var numSendStreams: Int = 0
        private set

    /** Max remote-initiated streams (our SETTINGS_MAX_CONCURRENT_STREAMS). */
    val maxRecvStreams: Int = config.remoteMaxInitiated ?: Int.MAX_VALUE
    var numRecvStreams: Int = 0
        private set

    private val maxLocalResetStreams: Int = config.localResetMax
    private var numLocalResetStreams: Int = 0

    val maxRemoteResetStreams: Int = config.remoteResetMax
    private var numRemoteResetStreams: Int = 0

    /** null: unlimited. */
    val maxLocalErrorResets: Int? = config.localMaxErrorResetStreams
    private var numLocalErrorResetStreams: Int = 0

    internal var dataFrameBudget = Budget(config.dataFrameBudget)
    private var numRecvEmptyDataFrames: Int = 0

    /**
     * Records the framing overhead of a received DATA frame (`record_data_frame`); false when a budget is exhausted:
     * more than [MAX_RECV_EMPTY_DATA_FRAMES] empty frames, or small frames beyond the DATA frame budget (large frames
     * replenish it).
     */
    fun recordDataFrame(payloadLen: Int): Boolean {
        return if (payloadLen == 0) {
            if (numRecvEmptyDataFrames == Int.MAX_VALUE) return false
            numRecvEmptyDataFrames++
            numRecvEmptyDataFrames <= MAX_RECV_EMPTY_DATA_FRAMES
        } else if (payloadLen < DEFAULT_DATA_FRAME_OVERHEAD_THRESHOLD) {
            dataFrameBudget.consume(DEFAULT_DATA_FRAME_OVERHEAD_THRESHOLD - payloadLen)
        } else {
            dataFrameBudget.replenish(payloadLen - DEFAULT_DATA_FRAME_OVERHEAD_THRESHOLD)
            true
        }
    }

    /** Gives back the overhead of a DATA frame no longer buffered (`release_data_frame`). */
    fun releaseDataFrame(payloadLen: Int) {
        if (payloadLen != 0 && payloadLen < DEFAULT_DATA_FRAME_OVERHEAD_THRESHOLD) {
            dataFrameBudget.replenish(DEFAULT_DATA_FRAME_OVERHEAD_THRESHOLD - payloadLen)
        }
    }

    /**
     * Whether the next opened stream reaches the send concurrency limit (`next_send_stream_will_reach_capacity`):
     * streams are counted in `prioritize`, so `send_request` has to guess.
     */
    fun nextSendStreamWillReachCapacity(): Boolean = maxSendStreams.toLong() <= numSendStreams.toLong() + 1

    fun hasStreams(): Boolean = numSendStreams != 0 || numRecvStreams != 0

    fun canIncNumLocalErrorResets(): Boolean {
        val max = maxLocalErrorResets ?: return true
        return max > numLocalErrorResetStreams
    }

    fun incNumLocalErrorResets() {
        check(canIncNumLocalErrorResets())
        numLocalErrorResetStreams++
    }

    fun canIncNumRecvStreams(): Boolean = maxRecvStreams > numRecvStreams

    fun incNumRecvStreams(stream: Stream) {
        check(canIncNumRecvStreams())
        check(!stream.isCounted)
        numRecvStreams++
        stream.isCounted = true
    }

    fun canIncNumSendStreams(): Boolean = maxSendStreams > numSendStreams

    fun incNumSendStreams(stream: Stream) {
        check(canIncNumSendStreams())
        check(!stream.isCounted)
        numSendStreams++
        stream.isCounted = true
    }

    fun canIncNumResetStreams(): Boolean = maxLocalResetStreams > numLocalResetStreams

    fun incNumResetStreams() {
        check(canIncNumResetStreams())
        numLocalResetStreams++
    }

    fun canIncNumRemoteResetStreams(): Boolean = maxRemoteResetStreams > numRemoteResetStreams

    fun incNumRemoteResetStreams() {
        check(canIncNumRemoteResetStreams())
        numRemoteResetStreams++
    }

    fun decNumRemoteResetStreams() {
        check(numRemoteResetStreams > 0)
        numRemoteResetStreams--
    }

    fun applyRemoteSettings(settings: Settings, isInitial: Boolean) {
        val v = settings.maxConcurrentStreams
        if (v != null) {
            maxSendStreams = if (v > Int.MAX_VALUE) Int.MAX_VALUE else v.toInt()
        } else if (isInitial) {
            maxSendStreams = Int.MAX_VALUE
        }
    }

    /**
     * Runs [f], which may change the stream's state, then cleans up after a close (`transition`).
     */
    inline fun <U> transition(store: Store, stream: Stream, f: (Stream) -> U): U {
        val isPendingReset = stream.isPendingResetExpiration
        val ret = f(stream)
        transitionAfter(store, stream, isPendingReset)
        return ret
    }

    /**
     * After a possible state change (`transition_after`): a closed stream is unlinked (unless held for reset
     * expiration) and no longer counted; a released one is removed.
     */
    fun transitionAfter(store: Store, stream: Stream, isResetCounted: Boolean) {
        if (stream.isClosed) {
            if (!stream.isPendingResetExpiration) {
                store.unlink(stream)
                if (isResetCounted) decNumResetStreams()
            }
            if (!stream.state.isScheduledReset && stream.isCounted) decNumStreams(stream)
        }
        if (stream.isReleased) store.remove(stream)
    }

    private fun decNumStreams(stream: Stream) {
        check(stream.isCounted)
        if (peer.isLocalInit(stream.id)) {
            check(numSendStreams > 0)
            numSendStreams--
        } else {
            check(numRecvStreams > 0)
            numRecvStreams--
        }
        stream.isCounted = false
    }

    private fun decNumResetStreams() {
        check(numLocalResetStreams > 0)
        numLocalResetStreams--
    }
}
