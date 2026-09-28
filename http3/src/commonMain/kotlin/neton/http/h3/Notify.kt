package neton.http.h3

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Wakes every coroutine waiting in [await] when [notifyAll] is called: the reference's wakers (`AtomicWaker`, the
 * request-end channel) for coroutines that re-check a condition after each wake-up. A waiter must check its condition
 * and call [await] without suspending in between, so no notification is lost. Not thread-safe: an HTTP/3 connection and
 * its streams live on one reactor (SPEC §5, concurrency model).
 */
internal class Notify {
    private var waiters = ArrayList<CancellableContinuation<Unit>>(2)

    /** Suspends until the next [notifyAll]. Cancellable. */
    suspend fun await() {
        suspendCancellableCoroutine { c ->
            waiters.add(c)
            c.invokeOnCancellation { waiters.remove(c) }
        }
    }

    /** Resumes every waiter. */
    fun notifyAll() {
        if (waiters.isEmpty()) return
        val w = waiters
        waiters = ArrayList(2)
        for (c in w) if (c.isActive) c.resume(Unit)
    }
}
