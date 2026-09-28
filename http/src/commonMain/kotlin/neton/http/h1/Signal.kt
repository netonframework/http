package neton.http.h1

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * A reusable event with one waiter, for coroutines of one connection on one reactor thread: what a
 * `CompletableDeferred<Unit>` per use did, without a Job (its state machine, completion handlers and parent links
 * cost more than the exchange they signal on Kotlin/Native). [raise] before [await] is remembered once.
 */
internal class Signal {
    private var waiter: CancellableContinuation<Unit>? = null
    private var raised = false

    fun raise() {
        val w = waiter
        if (w != null && w.isActive) {
            waiter = null
            w.resume(Unit)
        } else {
            waiter = null
            raised = true
        }
    }

    fun clear() { raised = false }

    suspend fun await() {
        if (raised) { raised = false; return }
        suspendCancellableCoroutine<Unit> { waiter = it }
    }
}

/**
 * A reusable one-value handoff with one waiter (the response of the request in flight): [complete] or [fail] once per
 * [reset]; [await] returns the value or throws the failure, also when it came first.
 */
internal class Slot<T : Any> {
    private var waiter: CancellableContinuation<T>? = null
    private var value: T? = null
    private var error: Throwable? = null

    fun reset() { waiter = null; value = null; error = null }

    fun complete(v: T) {
        val w = waiter
        waiter = null
        if (w != null && w.isActive) w.resume(v) else value = v
    }

    fun fail(e: Throwable) {
        val w = waiter
        waiter = null
        if (w != null && w.isActive) w.resumeWithException(e) else error = e
    }

    suspend fun await(): T {
        value?.let { value = null; return it }
        error?.let { error = null; throw it }
        return suspendCancellableCoroutine { waiter = it }
    }
}
