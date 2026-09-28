package neton.http.h2.proto

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * One entry of a [Deque]: a frame queued for sending, or an event received for a stream. [kind] and [flag] describe
 * [value] (for received events: the event kind and whether a DATA payload is counted against the budget).
 */
internal class Slot {
    var value: Any? = null
    var kind: Int = 0
    var flag: Boolean = false
    var next: Slot? = null
}

/**
 * The frames buffered for all the streams of a connection (`Buffer<T>`, `src/proto/streams/buffer.rs`). The
 * reference keeps them in one slab; here the entries are [Slot]s recycled through a free list, so queueing a frame
 * or an event allocates nothing once the connection is warm.
 */
internal class Buffer {
    private var free: Slot? = null
    private var live = 0

    /** No entry is queued on any stream (`is_empty`). */
    val isEmpty: Boolean get() = live == 0

    fun take(value: Any?, kind: Int, flag: Boolean): Slot {
        val s = free
        val slot = if (s != null) {
            free = s.next
            s.next = null
            s
        } else {
            Slot()
        }
        slot.value = value
        slot.kind = kind
        slot.flag = flag
        live++
        return slot
    }

    fun give(slot: Slot) {
        slot.value = null
        slot.next = free
        free = slot
        live--
    }
}

/** A sequence of entries in a [Buffer] (`Deque`). */
internal class Deque {
    private var head: Slot? = null
    private var tail: Slot? = null

    val isEmpty: Boolean get() = head == null

    /** The first entry, or null. */
    fun peek(): Slot? = head

    fun pushBack(buf: Buffer, value: Any?, kind: Int = 0, flag: Boolean = false) {
        val slot = buf.take(value, kind, flag)
        val t = tail
        if (t == null) {
            head = slot
        } else {
            t.next = slot
        }
        tail = slot
    }

    fun pushFront(buf: Buffer, value: Any?, kind: Int = 0, flag: Boolean = false) {
        val slot = buf.take(value, kind, flag)
        slot.next = head
        head = slot
        if (tail == null) tail = slot
    }

    /** Removes the first entry (after reading it through [peek]). */
    fun removeFront(buf: Buffer) {
        val h = head ?: return
        head = h.next
        if (head == null) tail = null
        buf.give(h)
    }

    /** Removes and returns the first entry's value, or null when empty (`pop_front`). */
    fun popFront(buf: Buffer): Any? {
        val h = head ?: return null
        val v = h.value
        removeFront(buf)
        return v
    }
}

/**
 * The coroutines waiting for one kind of stream event (the reference's `Option<Waker>` fields: `send_task`,
 * `recv_task`, `push_task`). The reference keeps one waker and each poll replaces it; coroutines are separate
 * waiters, so every one registered is kept and all are resumed by [wake] (they re-check their condition).
 */
internal class WaitSlot {
    private var first: CancellableContinuation<Unit>? = null
    private var rest: ArrayList<CancellableContinuation<Unit>>? = null

    val isEmpty: Boolean get() = first == null

    fun add(c: CancellableContinuation<Unit>) {
        if (first == null) {
            first = c
        } else {
            (rest ?: ArrayList<CancellableContinuation<Unit>>(2).also { rest = it }).add(c)
        }
    }

    /** Resumes every waiter (`take().wake()`); a cancelled one is skipped. */
    fun wake() {
        val f = first ?: return
        first = null
        f.resumeIfActive()
        val r = rest
        if (r != null && r.isNotEmpty()) {
            val n = r.size
            for (i in 0 until n) r[i].resumeIfActive()
            r.clear()
        }
    }

    /** Suspends until [wake]. */
    suspend fun await() {
        suspendCancellableCoroutine { c -> add(c) }
    }

    private fun CancellableContinuation<Unit>.resumeIfActive() {
        if (isActive) resume(Unit)
    }
}
