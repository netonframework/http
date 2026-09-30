package neton.http.h1

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import neton.http.Body
import neton.http.Frame
import neton.http.FullBody
import neton.http.HttpError
import neton.http.Response
import neton.io.bytes.Buffer
import neton.io.bytes.Bytes
import neton.io.core.IoStream
import neton.io.core.memoryStreamPair
import neton.io.net.runReactor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Deterministic tests: requests are buffered before service starts; no timing sleeps. */
class DeferredWatchTest {
    private val config = Http1ServerConfig(headerReadTimeoutMillis = 0, keepAliveIdleTimeoutMillis = 0, autoDateHeader = false)
    private val requests = listOf(
        "POST / HTTP/1.1\r\ncontent-length: 3\r\n\r\nabc",
        "POST / HTTP/1.1\r\ntransfer-encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\n",
    )

    private suspend fun IoStream.send(text: String) {
        write(Buffer().also { it.writeBytes(text.encodeToByteArray()) })
    }

    private fun response(): Response<Body> = Response(FullBody(Bytes.copyOf("ok".encodeToByteArray())))

    @Test
    fun bufferedPostDoesNotStartAReadBeforeSynchronousResponse() = runReactor {
        for (request in requests) {
            val (raw, client) = memoryStreamPair()
            var reads = 0
            var readsAtWrite = -1
            val stream = object : IoStream by raw {
                override suspend fun read(dst: Buffer): Int { reads++; return raw.read(dst) }
                override suspend fun write(src: Buffer): Int {
                    readsAtWrite = reads
                    return raw.write(src)
                }
            }
            client.send(request)
            val done = async {
                config.serveConnection(stream) { req ->
                    while (req.body.nextFrame() != null) { }
                    response()
                }.serve()
            }
            val output = Buffer()
            withTimeout(5_000) { while (!output.peekAll().decodeToString().endsWith("ok")) client.read(output) }
            client.close()
            withTimeout(5_000) { done.await() }
            assertEquals(1, readsAtWrite, "buffered POST must not create a speculative read before writing")
        }
    }

    @Test
    fun bufferedPostThenSuspendingServiceStillObservesEof() = runReactor {
        val (server, client) = memoryStreamPair()
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        client.send(requests.first())
        val done = async {
            runCatching {
                config.serveConnection(server) { req ->
                    while (req.body.nextFrame() != null) { }
                    entered.complete(Unit)
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                }.serve()
            }
        }
        withTimeout(5_000) { entered.await() }
        client.shutdownOutput()
        withTimeout(5_000) { cancelled.await() }
        val error = withTimeout(5_000) { done.await() }.exceptionOrNull()
        assertTrue(error is HttpError && error.isIncompleteMessage(), "$error")
        client.close()
    }

    @Test
    fun fragmentedBodyThenSuspendingServiceStillObservesEof() = runReactor {
        val (server, client) = memoryStreamPair()
        val waitingForBody = CompletableDeferred<Unit>()
        val bodyDone = CompletableDeferred<Unit>()
        client.send("POST / HTTP/1.1\r\ncontent-length: 3\r\n\r\n")
        val done = async {
            runCatching {
                config.serveConnection(server) { req ->
                    waitingForBody.complete(Unit)
                    while (req.body.nextFrame() != null) { }
                    bodyDone.complete(Unit)
                    awaitCancellation()
                }.serve()
            }
        }
        withTimeout(5_000) { waitingForBody.await() }
        client.send("abc")
        withTimeout(5_000) { bodyDone.await() }
        client.shutdownOutput()
        val error = withTimeout(5_000) { done.await() }.exceptionOrNull()
        assertTrue(error is HttpError && error.isIncompleteMessage(), "$error")
        client.close()
    }

    // Known defect, not caused by the deferred watch (fails the same way without it): a response write parked on
    // socket backpressure runs in the connection's own coroutine, outside exchangeJob, and nothing watches the read
    // side while it waits, so a client that closes is not noticed until the write completes. SPEC 11 (deferred
    // watch record) tracks the fix; hyper ends such an exchange with an incomplete-message error.
    @kotlin.test.Ignore
    @Test
    fun suspendedResponseWriteStillObservesEof() = runReactor {
        val (raw, client) = memoryStreamPair()
        val writing = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val server = object : IoStream by raw {
            override suspend fun write(src: Buffer): Int {
                writing.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
        }
        client.send(requests.first())
        val done = async {
            runCatching {
                config.serveConnection(server) { req ->
                    while (req.body.nextFrame() != null) { }
                    response()
                }.serve()
            }
        }
        withTimeout(5_000) { writing.await() }
        client.shutdownOutput()
        withTimeout(5_000) { cancelled.await() }
        val error = withTimeout(5_000) { done.await() }.exceptionOrNull()
        assertTrue(error is HttpError && error.isIncompleteMessage(), "$error")
        client.close()
    }

    @Test
    fun suspendedResponseBodyStillObservesEof() = runReactor {
        val (server, client) = memoryStreamPair()
        val producing = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val body = object : Body {
            override suspend fun nextFrame(): Frame? {
                producing.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
        }
        client.send(requests.first())
        val done = async {
            runCatching {
                config.serveConnection(server) { req ->
                    while (req.body.nextFrame() != null) { }
                    Response(body)
                }.serve()
            }
        }
        withTimeout(5_000) { producing.await() }
        client.shutdownOutput()
        withTimeout(5_000) { cancelled.await() }
        val error = withTimeout(5_000) { done.await() }.exceptionOrNull()
        assertTrue(error is HttpError && error.isIncompleteMessage(), "$error")
        client.close()
    }
}
