package neton.http.h2.proto

import neton.http.h2.frame.Reason

// Flow control (`src/proto/streams/flow_control.rs`).

// WINDOW_UPDATE frames are not sent for tiny changes: they are aggregated until the unclaimed part reaches this ratio
// of the window (1/2).
internal const val UNCLAIMED_NUMERATOR: Int = 1
internal const val UNCLAIMED_DENOMINATOR: Int = 2

/**
 * Thrown by the flow-control arithmetic where the reference returns `Err(Reason::FLOW_CONTROL_ERROR)`. Carries no
 * stack trace worth keeping; it is caught right away by the stream layer.
 */
internal class FlowControlError(val reason: Reason = Reason.FLOW_CONTROL_ERROR) : Exception(reason.toString())

/**
 * The current capacity of a flow-controlled window (`Window`): it goes negative when one side has used capacity the
 * other side has since reduced. Kept as a plain Int in [FlowControl]; these helpers are the reference's methods.
 */
internal object Window {
    /** The window as a size, 0 when negative (`as_size`). */
    fun asSize(w: Int): Int = if (w < 0) 0 else w

    /** `checked_size`: panics on a negative window. */
    fun checkedSize(w: Int): Int {
        check(w >= 0) { "negative Window" }
        return w
    }

    /** `decrease_by`: `w - other`, where `other` is a u32 cast to i32; overflow is a FLOW_CONTROL_ERROR. */
    fun decreaseBy(w: Int, other: Int): Int {
        val r = w.toLong() - other.toLong()
        if (r < Int.MIN_VALUE || r > Int.MAX_VALUE) throw FlowControlError()
        return r.toInt()
    }

    /** `add` / `increase_by`: `w + other`; overflow is a FLOW_CONTROL_ERROR. */
    fun add(w: Int, other: Int): Int {
        val r = w.toLong() + other.toLong()
        if (r < Int.MIN_VALUE || r > Int.MAX_VALUE) throw FlowControlError()
        return r.toInt()
    }
}

/**
 * One direction of flow control for a stream or the connection (`FlowControl`).
 *
 * - [windowSize]: the window the peer knows about; negative after a SETTINGS_INITIAL_WINDOW_SIZE decrease.
 * - [available]: the window the local side knows about (capacity assigned but not yet used); negative when a target
 *   smaller than what the peer knows is declared.
 */
internal class FlowControl {
    /** The window the peer knows (`window_size`), possibly negative. */
    var window: Int = 0
        private set

    /** Capacity available to the consumer (`available`), possibly negative. */
    var available: Int = 0
        private set

    /** The window size as known by the peer, 0 when negative (`window_size()`). */
    val windowSize: Int get() = Window.asSize(window)

    /** Whether there is window capacity not yet made available (`has_unavailable`). */
    fun hasUnavailable(): Boolean {
        if (window < 0) return false
        return window > available
    }

    /** `claim_capacity`. @throws FlowControlError */
    fun claimCapacity(capacity: Int) {
        available = Window.decreaseBy(available, capacity)
    }

    /** `assign_capacity`. @throws FlowControlError */
    fun assignCapacity(capacity: Int) {
        available = Window.add(available, capacity)
    }

    /**
     * The increment of a WINDOW_UPDATE to send, or -1 when none is due (`unclaimed_capacity`): available capacity the
     * peer does not know about yet, once it reaches half the window.
     */
    fun unclaimedCapacity(): Int {
        if (window >= available) return -1
        val unclaimed = available - window
        val threshold = window / UNCLAIMED_DENOMINATOR * UNCLAIMED_NUMERATOR
        return if (unclaimed < threshold) -1 else unclaimed
    }

    /**
     * Increases the window after a WINDOW_UPDATE (`inc_window`).
     * @throws FlowControlError when the window would exceed 2^31 - 1.
     */
    fun incWindow(sz: Int) {
        val v = window.toLong() + sz.toLong()
        if (v > MAX_WINDOW_SIZE || v < Int.MIN_VALUE) throw FlowControlError()
        window = v.toInt()
    }

    /** Decrements the send window after a lower SETTINGS_INITIAL_WINDOW_SIZE (`dec_send_window`). */
    fun decSendWindow(sz: Int) {
        // This can underflow from the bottom, as the reference notes.
        window = Window.decreaseBy(window, sz)
    }

    /** Decrements the receive window after a lower SETTINGS_INITIAL_WINDOW_SIZE is acknowledged (`dec_recv_window`). */
    fun decRecvWindow(sz: Int) {
        window = Window.decreaseBy(window, sz)
        available = Window.decreaseBy(available, sz)
    }

    /** Records that [sz] bytes were sent or received (`send_data`); the caller ensures the window has room. */
    fun sendData(sz: Int) {
        if (sz > 0) {
            check(window >= sz) { "window $window < $sz" }
            window = Window.decreaseBy(window, sz)
            available = Window.decreaseBy(available, sz)
        }
    }

    override fun toString(): String = "FlowControl { window_size: $window, available: $available }"
}
