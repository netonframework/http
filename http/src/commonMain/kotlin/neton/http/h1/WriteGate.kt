package neton.http.h1

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Mutual exclusion for the writers of one connection: the connection itself and, for `100 Continue`, a request body
 * read in another coroutine. All of them run on the connection's reactor thread (its stream is bound to it), so taking
 * a free gate is a field write, with none of the atomic operations of a general `Mutex`; a waiter queues.
 */
internal class WriteGate {
    @PublishedApi internal var held = false
    private var waiters: ArrayDeque<CancellableContinuation<Unit>>? = null
    private var grantedTo: CancellableContinuation<Unit>? = null

    @Suppress("NOTHING_TO_INLINE")
    suspend inline fun lock() {
        if (!held) held = true else lockSlow()
    }

    @PublishedApi
    internal suspend fun lockSlow() {
        var me: CancellableContinuation<Unit>? = null
        try {
            suspendCancellableCoroutine<Unit> { c ->
                me = c
                (waiters ?: ArrayDeque<CancellableContinuation<Unit>>().also { waiters = it }).addLast(c)
            }
            if (grantedTo === me) grantedTo = null
        } catch (e: CancellationException) {
            if (grantedTo === me) { grantedTo = null; unlock() }     // handed the gate as we were cancelled: pass it on
            else waiters?.remove(me)
            throw e
        }
    }

    fun unlock() {
        val q = waiters
        while (q != null && q.isNotEmpty()) {
            val next = q.removeFirst()
            if (next.isActive) { grantedTo = next; next.resume(Unit); return }   // ownership moves to [next]
        }
        held = false
    }

    suspend inline fun <T> withLock(block: () -> T): T {
        lock()
        try { return block() } finally { unlock() }
    }
}
