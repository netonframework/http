package neton.http.h3

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import neton.io.bytes.Bytes
import neton.io.net.runReactor
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Runs [block] on a fresh reactor with a generous overall timeout (the Mac running the tests is often loaded), then
 * cancels whatever it left running (drivers, watchdogs).
 */
fun h3Test(timeout: Duration = 60.seconds, block: suspend CoroutineScope.() -> Unit) = runReactor {
    withTimeout(timeout) {
        coroutineScope {
            block()
            coroutineContext[Job]!!.children.forEach { it.cancel() }
        }
    }
}

/** Polls [condition] every few milliseconds until it holds, failing after [timeout] (generous: loaded machines). */
suspend fun eventually(timeout: Duration = 20.seconds, message: String = "condition not reached", condition: () -> Boolean) {
    val start = TimeSource.Monotonic.markNow()
    while (!condition()) {
        if (start.elapsedNow() > timeout) fail(message)
        delay(2)
    }
}

fun bytes(s: String): Bytes = Bytes.wrap(s.encodeToByteArray())

fun bytes(vararg b: Int): Bytes = Bytes.wrap(ByteArray(b.size) { b[it].toByte() })
