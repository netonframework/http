package neton.http

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import neton.io.core.IoStream
import neton.io.core.memoryStreamPair
import neton.io.net.connect
import neton.io.net.listen
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

// SPEC §6: every test that runs over an in-memory stream pair must also run over real TCP. Tests take their
// connection from [testStreamPair]; NETON_HTTP_TEST_TRANSPORT=tcp switches it from neton-io's memoryStreamPair to a
// connected loopback TCP pair.

@OptIn(ExperimentalForeignApi::class)
val testTransportIsTcp: Boolean = getenv("NETON_HTTP_TEST_TRANSPORT")?.toKString() == "tcp"

/**
 * Two connected streams: `memoryStreamPair(capacity)` by default, a loopback TCP connection with NETON_HTTP_TEST_TRANSPORT=tcp.
 * Both ends are equivalent (as with memoryStreamPair): `first` is the accepted socket, `second` the connecting one.
 * Over TCP [capacity] does not apply (the kernel's socket buffers bound what is in flight); the two sockets are closed
 * when the test's reactor block completes.
 */
suspend fun testStreamPair(capacity: Int = 64 * 1024): Pair<IoStream, IoStream> {
    if (!testTransportIsTcp) return memoryStreamPair(capacity)
    val listener = listen("127.0.0.1", 0)
    val pair = try {
        val port = listener.localAddress.port
        val client = connect("127.0.0.1", port)
        listener.accept() to client
    } finally {
        listener.close()
    }
    rootJob(currentCoroutineContext().job).invokeOnCompletion {
        runCatching { pair.first.close() }
        runCatching { pair.second.close() }
    }
    return pair
}

/** The `runReactor` block's coroutine: the ancestor just below the reactor scope's own (never completing) Job. */
@OptIn(ExperimentalCoroutinesApi::class)
private fun rootJob(job: Job): Job {
    var j = job
    while (true) {
        val p = j.parent ?: return j
        if (p.parent == null) return j
        j = p
    }
}
