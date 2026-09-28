package neton.http.h1

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.concurrent.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume

/**
 * Runs a suspending call in the caller's coroutine and tells whether it completed at once, like one `poll` of a
 * future in hyper: the connection must act differently when the service or a response body is not ready yet (flush
 * what is buffered, watch the read side for EOF, SPEC §3.10). When the call completes at once nothing is allocated;
 * when it suspends, [await] waits for it.
 *
 * One call at a time; the object is reused for the next one. The call runs with [context] (the connection's, so its
 * suspension points are cancelled with the connection).
 */
internal class InlineCall<R, T>(function: suspend (R) -> T) : Continuation<T> {
    override var context: CoroutineContext = EmptyCoroutineContext

    // A suspend function value is called with its continuation as the last argument. Calling it directly with this
    // object as the completion is what `startCoroutineUninterceptedOrReturn` does, minus the wrapper it allocates per
    // call; [await] does the dispatching the wrapper would. Pass a function reference (`Body::nextFrame`), not a
    // lambda: a suspend lambda allocates a new instance per call.
    @Suppress("UNCHECKED_CAST")
    private val invoker = function as Function2<R, Continuation<T>, Any?>

    // null: running; a CancellableContinuation: the caller waits; a Result box: the call's outcome.
    private val state = AtomicReference<Any?>(null)

    /**
     * Starts the call on [receiver]. Returns its value when it completed without suspending, else
     * [COROUTINE_SUSPENDED] (then call [await]). A synchronous failure is thrown here.
     */
    fun start(receiver: R): Any? {
        state.value = null
        return invoker.invoke(receiver, this)
    }

    /** Waits for a call that [start] reported as suspended; returns its value or throws its failure. */
    @Suppress("UNCHECKED_CAST")
    suspend fun await(): T {
        if (state.value == null) {
            suspendCancellableCoroutine<Unit> { c -> if (!state.compareAndSet(null, c)) c.resume(Unit) }
        }
        return (state.value as Outcome).result.getOrThrow() as T
    }

    override fun resumeWith(result: Result<T>) {
        val prev = state.getAndSet(Outcome(result))
        if (prev is kotlinx.coroutines.CancellableContinuation<*>) {
            @Suppress("UNCHECKED_CAST")
            (prev as kotlinx.coroutines.CancellableContinuation<Unit>).resume(Unit)
        }
    }

    private class Outcome(val result: Result<Any?>)
}
