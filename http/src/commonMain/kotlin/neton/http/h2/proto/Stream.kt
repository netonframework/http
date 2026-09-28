package neton.http.h2.proto

import neton.http.h2.frame.Reason
import neton.http.h2.frame.StreamId

/**
 * The state of one stream (`Stream`, `src/proto/streams/stream.rs`).
 *
 * Streams are referenced directly instead of through slab keys: the reference's `Key` carries the stream ID as an
 * ABA guard for reused slab slots, which object references do not need. The intrusive queue links (`next_*` with
 * their `is_*` flags) are kept as in the reference, so a stream is in each queue at most once and queueing
 * allocates nothing.
 *
 * [refCount] counts the user handles that reach the stream; a stream in an internal queue (an accept queue) is not
 * counted, so the count can be 0 while the stream must stay.
 */
internal class Stream(val id: StreamId, initSendWindow: Int, initRecvWindow: Int) {
    val state = State()

    /** Counted against the connection's max concurrent streams. */
    var isCounted = false

    /** Number of user handles pointing to this stream. */
    var refCount = 0

    /** Dropped from the [Store] (the reference's slab removal). */
    var removed = false

    // ===== Sending =====

    var nextPendingSend: Stream? = null
    var isPendingSend = false

    /** Send flow control. */
    val sendFlow = FlowControl()

    /** Send capacity requested but not yet assigned (a u32 in the reference, hence a Long). */
    var requestedSendCapacity = 0L

    /** DATA bytes buffered at the prioritization layer. */
    var bufferedSendData = 0L

    /** Tasks waiting for send capacity or a reset (`send_task`). */
    val sendTask = WaitSlot()

    /** Frames waiting to be written. */
    val pendingSend = Deque()

    var nextPendingSendCapacity: Stream? = null
    var isPendingSendCapacity = false

    /** Set when the send capacity grew (`send_capacity_inc`). */
    var sendCapacityInc = false

    var nextOpen: Stream? = null
    var isPendingOpen = false

    /** A PUSH_PROMISE for this stream still has to be sent on another stream. */
    var isPendingPush = false

    // ===== Receiving =====

    var nextPendingAccept: Stream? = null
    var isPendingAccept = false

    /** Receive flow control. */
    val recvFlow = FlowControl()

    /** Received DATA the user has not released yet. */
    var inFlightRecvData = 0

    var nextWindowUpdate: Stream? = null
    var isPendingWindowUpdate = false

    /** When the stream was locally reset (monotonic ns), or [NOT_RESET] (`reset_at`). */
    var resetAt = NOT_RESET

    var nextResetExpire: Stream? = null

    /** Events waiting to be read. */
    val pendingRecv = Deque()

    /** False once the receiving handle is gone: DATA is no longer delivered (`is_recv`). */
    var isRecv = true

    /** Tasks waiting for received events (`recv_task`). */
    val recvTask = WaitSlot()

    /** Tasks waiting for push promises (`push_task`). */
    val pushTask = WaitSlot()

    /** The pushed streams not yet taken by the user (linked through [nextPendingAccept]). */
    val pendingPushPromises = Queue(NextAccept)

    /** Content-length validation. */
    var contentLength = ContentLength.OMITTED

    /** The remaining content-length when [contentLength] is [ContentLength.REMAINING] (a u64). */
    var contentLengthRemaining = 0UL

    init {
        recvFlow.incWindow(initRecvWindow)
        recvFlow.assignCapacity(initRecvWindow)
        sendFlow.incWindow(initSendWindow)
    }

    fun refInc() {
        refCount++
    }

    fun refDec() {
        check(refCount > 0)
        refCount--
    }

    /** Held for some time because of a local reset (`is_pending_reset_expiration`). */
    val isPendingResetExpiration: Boolean get() = resetAt != NOT_RESET

    /**
     * Frames of this stream may be sent (`is_send_ready`): it is neither waiting to be opened (concurrency limit) nor
     * waiting for its PUSH_PROMISE to go out on another stream.
     */
    val isSendReady: Boolean get() = !isPendingOpen && !isPendingPush

    /**
     * Closed and fully flushed (`is_closed`): outbound frames change the state when they are queued, so the queue
     * must also be empty, and no DATA may remain buffered (a partly sent frame is not in the queue while written).
     */
    val isClosed: Boolean get() = state.isClosed && pendingSend.isEmpty && bufferedSendData == 0L

    /** No longer in use (`is_released`): closed, unreferenced and in no queue. */
    val isReleased: Boolean
        get() = isClosed && refCount == 0 && !isPendingSend && !isPendingSendCapacity && !isPendingAccept &&
            !isPendingWindowUpdate && !isPendingOpen && resetAt == NOT_RESET

    /** Every handle is gone but the stream is not closed: a reset must be sent (`is_canceled_interest`). */
    val isCanceledInterest: Boolean get() = refCount == 0 && !state.isClosed

    /** The current send capacity (`capacity`). */
    fun capacity(maxBufferSize: Int): Int {
        val available = Window.asSize(sendFlow.available).toLong()
        val c = minOf(available, maxBufferSize.toLong()) - bufferedSendData
        return if (c < 0) 0 else c.toInt()
    }

    fun assignCapacity(capacity: Int, maxBufferSize: Int) {
        val prev = capacity(maxBufferSize)
        sendFlow.assignCapacity(capacity)
        if (prev < capacity(maxBufferSize)) notifyCapacity()
    }

    fun sendData(len: Int, maxBufferSize: Int) {
        val prev = capacity(maxBufferSize)
        sendFlow.sendData(len)
        check(bufferedSendData >= len)
        bufferedSendData -= len
        requestedSendCapacity -= len
        if (prev < capacity(maxBufferSize)) notifyCapacity()
    }

    /** The capacity grew: wake the send task (`notify_capacity`). */
    fun notifyCapacity() {
        sendCapacityInc = true
        notifySend()
    }

    /** Counts [len] received bytes against the content-length; false on overflow (`dec_content_length`). */
    fun decContentLength(len: Int): Boolean {
        when (contentLength) {
            ContentLength.REMAINING -> {
                val l = len.toULong()
                if (l > contentLengthRemaining) return false
                contentLengthRemaining -= l
            }
            ContentLength.HEAD -> if (len != 0) return false
            else -> {}
        }
        return true
    }

    /** False when bytes of the content-length are still missing (`ensure_content_length_zero`). */
    fun ensureContentLengthZero(): Boolean = contentLength != ContentLength.REMAINING || contentLengthRemaining == 0UL

    fun notifySend() = sendTask.wake()

    fun notifyRecv() = recvTask.wake()

    fun notifyPush() = pushTask.wake()

    /** Closes the stream with a reset and wakes all its tasks (`set_reset`). */
    fun setReset(reason: Reason, initiator: Initiator) {
        state.setReset(id, reason, initiator)
        notifySend()
        notifyPush()
        notifyRecv()
    }

    override fun toString(): String =
        "Stream { id: ${id.value}, state: $state, is_counted: $isCounted, ref_count: $refCount, send_flow: $sendFlow, " +
            "requested_send_capacity: $requestedSendCapacity, buffered_send_data: $bufferedSendData, " +
            "recv_flow: $recvFlow, in_flight_recv_data: $inFlightRecvData }"

    companion object {
        const val NOT_RESET: Long = Long.MIN_VALUE
    }
}

/** How a stream's content-length is validated (`ContentLength`). */
internal enum class ContentLength {
    OMITTED,
    HEAD,
    REMAINING,
}

/** The intrusive links of one kind of queue (`store::Next`). */
internal interface Next {
    fun next(s: Stream): Stream?

    fun setNext(s: Stream, v: Stream?)

    fun isQueued(s: Stream): Boolean

    fun setQueued(s: Stream, v: Boolean)
}

internal object NextAccept : Next {
    override fun next(s: Stream) = s.nextPendingAccept
    override fun setNext(s: Stream, v: Stream?) { s.nextPendingAccept = v }
    override fun isQueued(s: Stream) = s.isPendingAccept
    override fun setQueued(s: Stream, v: Boolean) { s.isPendingAccept = v }
}

internal object NextSend : Next {
    override fun next(s: Stream) = s.nextPendingSend
    override fun setNext(s: Stream, v: Stream?) { s.nextPendingSend = v }
    override fun isQueued(s: Stream) = s.isPendingSend
    override fun setQueued(s: Stream, v: Boolean) {
        // A stream queued for sending is not also queued to be opened.
        if (v) check(!s.isPendingOpen)
        s.isPendingSend = v
    }
}

internal object NextSendCapacity : Next {
    override fun next(s: Stream) = s.nextPendingSendCapacity
    override fun setNext(s: Stream, v: Stream?) { s.nextPendingSendCapacity = v }
    override fun isQueued(s: Stream) = s.isPendingSendCapacity
    override fun setQueued(s: Stream, v: Boolean) { s.isPendingSendCapacity = v }
}

internal object NextWindowUpdate : Next {
    override fun next(s: Stream) = s.nextWindowUpdate
    override fun setNext(s: Stream, v: Stream?) { s.nextWindowUpdate = v }
    override fun isQueued(s: Stream) = s.isPendingWindowUpdate
    override fun setQueued(s: Stream, v: Boolean) { s.isPendingWindowUpdate = v }
}

internal object NextOpen : Next {
    override fun next(s: Stream) = s.nextOpen
    override fun setNext(s: Stream, v: Stream?) { s.nextOpen = v }
    override fun isQueued(s: Stream) = s.isPendingOpen
    override fun setQueued(s: Stream, v: Boolean) {
        // A stream queued to be opened is not also queued for sending.
        if (v) check(!s.isPendingSend)
        s.isPendingOpen = v
    }
}

internal object NextResetExpire : Next {
    override fun next(s: Stream) = s.nextResetExpire
    override fun setNext(s: Stream, v: Stream?) { s.nextResetExpire = v }
    override fun isQueued(s: Stream) = s.resetAt != Stream.NOT_RESET
    override fun setQueued(s: Stream, v: Boolean) { s.resetAt = if (v) monotonicNanos() else Stream.NOT_RESET }
}

/** A queue of streams linked through their [Next] fields (`store::Queue`). */
internal class Queue(private val n: Next) {
    private var head: Stream? = null
    private var tail: Stream? = null

    val isEmpty: Boolean get() = head == null

    /** The first stream, without removing it. */
    fun peek(): Stream? = head

    /** Queues [stream] at the back; false when it is already queued (`push`). */
    fun push(stream: Stream): Boolean {
        if (n.isQueued(stream)) return false
        n.setQueued(stream, true)
        check(n.next(stream) == null)
        val t = tail
        if (t == null) head = stream else n.setNext(t, stream)
        tail = stream
        return true
    }

    /** Queues [stream] at the front; false when it is already queued (`push_front`). */
    fun pushFront(stream: Stream): Boolean {
        if (n.isQueued(stream)) return false
        n.setQueued(stream, true)
        check(n.next(stream) == null)
        val h = head
        if (h == null) {
            tail = stream
        } else {
            n.setNext(stream, h)
        }
        head = stream
        return true
    }

    fun pop(): Stream? {
        val s = head ?: return null
        if (s === tail) {
            check(n.next(s) == null)
            head = null
            tail = null
        } else {
            head = n.next(s)
            n.setNext(s, null)
        }
        n.setQueued(s, false)
        return s
    }

    /** Pops the first stream if [f] accepts it (`pop_if`). */
    inline fun popIf(f: (Stream) -> Boolean): Stream? {
        val h = peek() ?: return null
        return if (f(h)) pop() else null
    }

    /** Moves the whole queue out, leaving this one empty (`take`). */
    fun takeAll(into: Queue) {
        into.head = head
        into.tail = tail
        head = null
        tail = null
    }
}
