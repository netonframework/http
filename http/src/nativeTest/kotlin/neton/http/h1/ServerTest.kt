package neton.http.h1

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import neton.http.Body
import neton.http.EmptyBody
import neton.http.Frame
import neton.http.FullBody
import neton.http.HttpError
import neton.http.Incoming
import neton.http.Response
import neton.http.SizeHint
import neton.http.StatusCode
import neton.http.header.HeaderMap
import neton.http.header.HeaderValue
import neton.io.bytes.Buffer
import neton.io.bytes.Bytes
import neton.io.core.IoStream
import neton.io.core.memoryStreamPair
import neton.io.net.runReactor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Server connection basics over in-memory streams (SPEC §3.10); hyper's `tests/server.rs` port is in ServerHyperTest. */
class ServerTest {
    private val noTimeouts = Http1ServerConfig(headerReadTimeoutMillis = 0, keepAliveIdleTimeoutMillis = 0, autoDateHeader = false)

    private fun bytesOf(s: String) = Bytes.copyOf(s.encodeToByteArray())

    private suspend fun IoStream.send(s: String) { write(Buffer().also { it.writeBytes(s.encodeToByteArray()) }) }

    /** Reads until [n] bytes or EOF. */
    private suspend fun IoStream.readText(n: Int = Int.MAX_VALUE): String {
        val acc = Buffer()
        while (acc.readableBytes < n) { if (read(acc) < 0) break }
        return acc.readAll().decodeToString()
    }

    /** Reads until [marker] appears (or EOF). */
    private suspend fun IoStream.readUntil(marker: String): String {
        val acc = Buffer()
        while (!acc.peekAll().decodeToString().contains(marker)) { if (read(acc) < 0) break }
        return acc.readAll().decodeToString()
    }

    private fun text(s: String): Response<Body> = Response.builder().body(FullBody(bytesOf(s)) as Body)

    private suspend fun Incoming.readAllText(): String {
        val sb = StringBuilder()
        while (true) {
            val f = nextFrame() ?: break
            if (f is Frame.Data) sb.append(f.bytes.toByteArray().decodeToString())
        }
        return sb.toString()
    }

    @Test
    fun getKeepAliveAndClose() = runReactor {
        val (server, client) = memoryStreamPair()
        val done = async { noTimeouts.serveConnection(server) { req -> text("hello ${req.uri}") }.serve() }
        client.send("GET /a HTTP/1.1\r\nHost: x\r\n\r\n")
        assertEquals("HTTP/1.1 200 OK\r\ncontent-length: 8\r\n\r\nhello /a", client.readUntil("hello /a"))
        client.send("GET /b HTTP/1.1\r\nHost: x\r\n\r\n")
        assertEquals("HTTP/1.1 200 OK\r\ncontent-length: 8\r\n\r\nhello /b", client.readUntil("hello /b"))
        client.close()
        withTimeout(5_000) { done.await() }
    }

    @Test
    fun pipelinedRequestsAnsweredInOrder() = runReactor {
        val (server, client) = memoryStreamPair()
        val done = async { noTimeouts.serveConnection(server) { req -> text(req.uri.toString()) }.serve() }
        client.send("GET /1 HTTP/1.1\r\n\r\nGET /2 HTTP/1.1\r\n\r\nGET /3 HTTP/1.1\r\nconnection: close\r\n\r\n")
        val all = client.readText()
        assertEquals(
            "HTTP/1.1 200 OK\r\ncontent-length: 2\r\n\r\n/1HTTP/1.1 200 OK\r\ncontent-length: 2\r\n\r\n/2" +
                "HTTP/1.1 200 OK\r\nconnection: close\r\ncontent-length: 2\r\n\r\n/3",
            all,
        )
        withTimeout(5_000) { done.await() }
    }

    @Test
    fun http10ClosesByDefaultAndKeepsAliveOnRequest() = runReactor {
        val (server, client) = memoryStreamPair()
        val done = async { noTimeouts.serveConnection(server) { text("x") }.serve() }
        client.send("GET / HTTP/1.0\r\nconnection: keep-alive\r\n\r\n")
        assertEquals("HTTP/1.0 200 OK\r\nconnection: keep-alive\r\ncontent-length: 1\r\n\r\nx", client.readUntil("\r\n\r\nx"))
        client.send("GET / HTTP/1.0\r\n\r\n")
        assertEquals("HTTP/1.0 200 OK\r\ncontent-length: 1\r\n\r\nx", client.readText())
        withTimeout(5_000) { done.await() }
    }

    @Test
    fun postBodiesLengthAndChunked() = runReactor {
        val (server, client) = memoryStreamPair()
        val done = async { noTimeouts.serveConnection(server) { req -> text("got " + req.body.readAllText()) }.serve() }
        client.send("POST / HTTP/1.1\r\ncontent-length: 5\r\n\r\nhello")
        assertTrue(client.readUntil("got hello").endsWith("got hello"))
        client.send("POST / HTTP/1.1\r\ntransfer-encoding: chunked\r\n\r\n3\r\nabc\r\n2\r\nde\r\n0\r\n\r\n")
        assertTrue(client.readUntil("got abcde").endsWith("got abcde"))
        client.close()
        withTimeout(5_000) { done.await() }
    }

    @Test
    fun unreadBodyInBufferKeepsAliveOtherwiseCloses() = runReactor {
        val (server, client) = memoryStreamPair()
        val done = async { noTimeouts.serveConnection(server) { text("ok") }.serve() }
        // A small body already buffered with the head: drained, connection kept.
        client.send("POST / HTTP/1.1\r\ncontent-length: 3\r\n\r\nabc")
        client.readUntil("\r\n\r\nok")
        // A body not yet arrived: the connection closes after the response (hyper).
        client.send("POST / HTTP/1.1\r\ncontent-length: 100\r\n\r\nab")
        val r = client.readText()
        assertTrue(r.startsWith("HTTP/1.1 200 OK\r\n") && r.endsWith("ok"), r)
        withTimeout(5_000) { done.await() }
    }

    @Test
    fun streamingChunkedResponseWithTrailers() = runReactor {
        val (server, client) = memoryStreamPair()
        val release = CompletableDeferred<Unit>()
        val body = object : Body {
            var step = 0
            override suspend fun nextFrame(): Frame? = when (step++) {
                0 -> Frame.Data(bytesOf("one"))
                1 -> { release.await(); Frame.Data(bytesOf("two")) }
                2 -> Frame.Trailers(HeaderMap<HeaderValue>().also { it.insert("x-sum", HeaderValue.fromStatic("6")) })
                else -> null
            }
        }
        val done = async {
            noTimeouts.serveConnection(server) {
                Response.builder().header("trailer", "x-sum").body(body as Body)
            }.serve()
        }
        client.send("GET / HTTP/1.1\r\nte: trailers\r\n\r\n")
        // The first chunk is flushed while the body waits (hyper flushes when the body is not ready).
        assertEquals("HTTP/1.1 200 OK\r\ntrailer: x-sum\r\ntransfer-encoding: chunked\r\n\r\n3\r\none\r\n", client.readUntil("one\r\n"))
        release.complete(Unit)
        assertEquals("3\r\ntwo\r\n0\r\nx-sum: 6\r\n\r\n", client.readUntil("x-sum: 6\r\n\r\n"))
        client.close()
        withTimeout(5_000) { done.await() }
    }

    @Test
    fun malformedRequestGetsAutomaticResponse() = runReactor {
        for ((req, status) in listOf(
            "GET / HTTP/1.1\r\nbad header\r\n\r\n" to "400 Bad Request",
            "GET /" + "a".repeat(9000) + " HTTP/1.1\r\n\r\n" to "414 URI Too Long",
            "POST / HTTP/1.1\r\ncontent-length: 1\r\ntransfer-encoding: chunked\r\n\r\n" to "400 Bad Request",
        )) {
            val (server, client) = memoryStreamPair(256 * 1024)
            val done = async { runCatching { noTimeouts.serveConnection(server) { text("never") }.serve() } }
            client.send(req)
            val r = client.readText()
            assertTrue(r.startsWith("HTTP/1.1 $status\r\n"), r)
            val e = withTimeout(5_000) { done.await() }.exceptionOrNull()
            assertTrue(e is HttpError && e.isParse(), "$e")
        }
    }

    @Test
    fun expectContinueSentOnFirstBodyRead() = runReactor {
        val (server, client) = memoryStreamPair()
        val done = async { noTimeouts.serveConnection(server) { req -> text(req.body.readAllText()) }.serve() }
        client.send("POST / HTTP/1.1\r\nexpect: 100-continue\r\ncontent-length: 2\r\n\r\n")
        assertEquals("HTTP/1.1 100 Continue\r\n\r\n", client.readUntil("\r\n\r\n"))
        client.send("hi")
        assertTrue(client.readUntil("\r\n\r\nhi").endsWith("hi"))
        client.close()
        withTimeout(5_000) { done.await() }
    }

    @Test
    fun clientCloseCancelsSlowService() = runReactor {
        val (server, client) = memoryStreamPair()
        val cancelled = CompletableDeferred<Boolean>()
        val done = async {
            runCatching {
                noTimeouts.serveConnection(server) {
                    try { delay(60_000); text("late") } catch (e: kotlinx.coroutines.CancellationException) { cancelled.complete(true); throw e }
                }.serve()
            }
        }
        client.send("GET / HTTP/1.1\r\n\r\n")
        delay(50)
        client.shutdownOutput()
        assertTrue(withTimeout(5_000) { cancelled.await() })
        val e = withTimeout(5_000) { done.await() }.exceptionOrNull()
        assertTrue(e is HttpError && e.isIncompleteMessage(), "$e")
    }

    @Test
    fun halfCloseKeepsServing() = runReactor {
        val (server, client) = memoryStreamPair()
        val done = async {
            Http1ServerConfig(headerReadTimeoutMillis = 0, keepAliveIdleTimeoutMillis = 0, autoDateHeader = false, halfClose = true)
                .serveConnection(server) { delay(50); text("late") }.serve()
        }
        client.send("GET / HTTP/1.1\r\n\r\n")
        client.shutdownOutput()
        assertTrue(client.readText().endsWith("late"))
        withTimeout(5_000) { done.await() }
    }

    @Test
    fun serviceErrorClosesWithoutResponse() = runReactor {
        val (server, client) = memoryStreamPair()
        val done = async { runCatching { noTimeouts.serveConnection(server) { error("boom") }.serve() } }
        client.send("GET / HTTP/1.1\r\n\r\n")
        assertEquals("", client.readText())
        val e = withTimeout(5_000) { done.await() }.exceptionOrNull()
        assertTrue(e is HttpError && e.kind == HttpError.Kind.UserService, "$e")
    }

    @Test
    fun headResponseHasNoBodyAndKnownLengthIsKept() = runReactor {
        val (server, client) = memoryStreamPair()
        val done = async { noTimeouts.serveConnection(server) { text("12345") }.serve() }
        client.send("HEAD / HTTP/1.1\r\n\r\n")
        assertEquals("HTTP/1.1 200 OK\r\ncontent-length: 5\r\n\r\n", client.readUntil("\r\n\r\n"))
        client.send("GET / HTTP/1.1\r\nconnection: close\r\n\r\n")
        assertTrue(client.readText().endsWith("12345"))
        withTimeout(5_000) { done.await() }
    }

    @Test
    fun declaredBodyOverLimitIs413() = runReactor {
        val (server, client) = memoryStreamPair()
        val cfg = Http1ServerConfig(headerReadTimeoutMillis = 0, keepAliveIdleTimeoutMillis = 0, autoDateHeader = false, maxRequestBodySize = 10)
        val done = async { runCatching { cfg.serveConnection(server) { text("never") }.serve() } }
        client.send("POST / HTTP/1.1\r\ncontent-length: 11\r\n\r\n")
        assertTrue(client.readText().startsWith("HTTP/1.1 413 Payload Too Large\r\n"))
        val e = withTimeout(5_000) { done.await() }.exceptionOrNull()
        assertTrue(e is HttpError && e.kind == HttpError.Kind.UserBodyTooLarge, "$e")
    }

    @Test
    fun timeoutsNeedReadTimeoutCapability() {
        val (server, _) = memoryStreamPair()
        assertFailsWith<IllegalArgumentException> { Http1ServerConfig().serveConnection(server) { text("x") } }
    }

    @Test
    fun emptyBodyOmitsContentLengthOnlyWhereHyperDoes() = runReactor {
        val (server, client) = memoryStreamPair()
        val done = async {
            noTimeouts.serveConnection(server) { req ->
                val status = if (req.uri.toString() == "/204") StatusCode.NO_CONTENT else StatusCode.OK
                Response.builder().status(status).body(EmptyBody as Body)
            }.serve()
        }
        client.send("GET /204 HTTP/1.1\r\n\r\n")
        assertEquals("HTTP/1.1 204 No Content\r\n\r\n", client.readUntil("\r\n\r\n"))
        client.send("GET / HTTP/1.1\r\nconnection: close\r\n\r\n")
        assertEquals("HTTP/1.1 200 OK\r\nconnection: close\r\ncontent-length: 0\r\n\r\n", client.readText())
        withTimeout(5_000) { done.await() }
    }

    @Suppress("unused")
    private val sizeHintUnused = SizeHint.DEFAULT
}
