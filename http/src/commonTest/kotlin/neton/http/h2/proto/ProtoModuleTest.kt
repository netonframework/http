package neton.http.h2.proto

import neton.http.h2.frame.DEFAULT_INITIAL_WINDOW_SIZE
import neton.http.h2.frame.Headers
import neton.http.h2.frame.Pseudo
import neton.http.h2.frame.Reason
import neton.http.h2.frame.Reset
import neton.http.h2.frame.StreamId
import neton.http.header.HeaderMap
import neton.io.bytes.Bytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration

// The in-module tests of h2 0.4.19 `src/proto`: `streams/counts.rs` (6), `connection.rs` (3), `streams/state.rs` (1),
// `streams/recv.rs` (1) and `streams/flow_control.rs` (1).

class ProtoModuleTest {
    private fun config() = StreamsConfig(
        initialMaxSendStreams = 0,
        localMaxBufferSize = 0,
        localNextStreamId = StreamId(2),
        localPushEnabled = false,
        extendedConnectProtocolEnabled = false,
        localResetDuration = Duration.ZERO,
        localResetMax = 0,
        remoteResetMax = 0,
        remoteInitWindowSz = DEFAULT_INITIAL_WINDOW_SIZE,
        remoteMaxInitiated = null,
        localMaxErrorResetStreams = null,
        dataFrameBudget = DEFAULT_DATA_FRAME_BUDGET,
    )

    private fun counts() = Counts(Peer.Server, config())

    // ===== counts.rs =====

    @Test
    fun budgetIsBounded() {
        val budget = Budget(10)
        assertTrue(budget.consume(4))
        budget.replenish(20)
        assertEquals(10, budget.available)
    }

    @Test
    fun budgetReportsExhaustionWithoutUnderflowing() {
        val budget = Budget(10)
        assertTrue(budget.consume(10))
        assertFalse(budget.consume(1))
        assertEquals(0, budget.available)
    }

    @Test
    fun goodSizedDataFramesDoNotExhaustBudget() {
        val counts = counts()
        repeat(1_000_000) { assertTrue(counts.recordDataFrame(DEFAULT_DATA_FRAME_OVERHEAD_THRESHOLD)) }
    }

    @Test
    fun consumedSmallDataFramesDoNotExhaustBudget() {
        val counts = counts()
        repeat(1_000_000) {
            assertTrue(counts.recordDataFrame(1))
            counts.releaseDataFrame(1)
        }
    }

    @Test
    fun emptyDataFramesDoNotConsumeDataFrameBudget() {
        val counts = counts()
        counts.dataFrameBudget = Budget(0)

        repeat(MAX_RECV_EMPTY_DATA_FRAMES) { assertTrue(counts.recordDataFrame(0)) }

        // Empty frames have their own limit, while a non-empty small frame still consumes the independently
        // configured DATA frame budget.
        assertFalse(counts.recordDataFrame(0))
        assertFalse(counts.recordDataFrame(1))
    }

    @Test
    fun largeDataFramesDoNotReplenishEmptyDataFrameLimit() {
        val counts = counts()
        repeat(MAX_RECV_EMPTY_DATA_FRAMES) {
            assertTrue(counts.recordDataFrame(0))
            assertTrue(counts.recordDataFrame(DEFAULT_DATA_FRAME_OVERHEAD_THRESHOLD * 2))
        }
        assertFalse(counts.recordDataFrame(0))
    }

    // ===== connection.rs =====

    @Test
    fun autoDataFrameBudgetScalesWithConnectionWindow() {
        assertEquals(DEFAULT_INITIAL_WINDOW_SIZE / 2, DataFrameBudget.Auto.resolve(null))
        assertEquals(DEFAULT_INITIAL_WINDOW_SIZE / 2, DataFrameBudget.Auto.resolve(DEFAULT_INITIAL_WINDOW_SIZE))
        assertEquals(512 * 1024, DataFrameBudget.Auto.resolve(1024 * 1024))
    }

    @Test
    fun autoDataFrameBudgetHasMinimum() {
        assertEquals(DEFAULT_DATA_FRAME_BUDGET, DataFrameBudget.Auto.resolve(1))
        assertEquals(MAX_WINDOW_SIZE / 2, DataFrameBudget.Auto.resolve(MAX_WINDOW_SIZE))
    }

    @Test
    fun configuredDataFrameBudgetIsUnchanged() {
        assertEquals(123, DataFrameBudget.Configured(123).resolve(MAX_WINDOW_SIZE))
    }

    // ===== state.rs =====

    @Test
    fun recvResetPreservesReceivedEndStream() {
        val streamId = StreamId(1)
        val state = State()
        assertEquals(null, state.sendOpen(false))

        val headers = Headers(streamId, Pseudo(), HeaderMap())
        headers.setEndStream()
        state.recvOpen(headers)
        assertTrue(state.isRecvEndStream)

        state.recvReset(Reset(streamId, Reason.NO_ERROR), true)

        assertTrue(state.isRecvEndStream)
        assertEquals(false, state.ensureRecvOpen())
        assertEquals(Reason.NO_ERROR, state.ensureReason(PollReset.Streaming))
    }

    // ===== recv.rs =====

    @Test
    fun clearRecvBufferCapsCapacityBeforeOverflow() {
        val frameLen = 1 shl 20
        val frameCount = (0xffffffffL / frameLen + 1).toInt()

        val config = config()
        val recv = Recv(Peer.Server, config)
        val store = Store()
        val stream = store.insert(Stream(StreamId(1), 0, DEFAULT_INITIAL_WINDOW_SIZE))
        val data = Bytes.wrap(ByteArray(frameLen))

        repeat(frameCount) { stream.pendingRecv.pushBack(recv.buffer, data, EV_DATA, true) }
        stream.inFlightRecvData = DEFAULT_INITIAL_WINDOW_SIZE
        recv.inFlightData = DEFAULT_INITIAL_WINDOW_SIZE

        val counts = Counts(Peer.Server, config)
        recv.clearRecvBuffer(stream, Task.NONE, counts)

        assertTrue(stream.pendingRecv.isEmpty)
        assertEquals(0, stream.inFlightRecvData)
        assertEquals(0, recv.inFlightData)
    }

    // ===== flow_control.rs =====

    @Test
    fun sanityUnclaimedRatio() {
        assertTrue(UNCLAIMED_NUMERATOR < UNCLAIMED_DENOMINATOR)
        assertTrue(UNCLAIMED_NUMERATOR >= 0)
        assertTrue(UNCLAIMED_DENOMINATOR > 0)
    }
}
