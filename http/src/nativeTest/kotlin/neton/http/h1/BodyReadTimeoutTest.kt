package neton.http.h1

import kotlinx.coroutines.delay
import neton.http.Body
import neton.http.FullBody
import neton.http.HttpError
import neton.http.Response
import neton.http.StatusCode
import neton.io.core.memoryStreamPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ⚖️ [Http1ServerConfig.bodyReadTimeoutMillis] (hyper has none): from the first wait for body bytes to the body's end.
 * A read timeout needs a socket, so these run over loopback TCP whatever NETON_HTTP_TEST_TRANSPORT says.
 */
class BodyReadTimeoutTest {
    private val config = Http1ServerConfig(
        headerReadTimeoutMillis = 0, keepAliveIdleTimeoutMillis = 0, autoDateHeader = false, bodyReadTimeoutMillis = 200,
    )

    /** Reads the whole body; answers 200 with its length, or 408 when the read timed out. */
    private val service = HttpService { req ->
        val status = try {
            var n = 0
            while (true) {
                val f = req.body.nextFrame() ?: break
                if (f is neton.http.Frame.Data) n += f.bytes.size
            }
            n.toString()
        } catch (e: HttpError) {
            if (e.isTimeout()) "timeout" else throw e
        }
        if (status == "timeout") Response<Body>(FullBody(bytesOf("timeout"))).also { it.parts.status = StatusCode.REQUEST_TIMEOUT }
        else Response<Body>(FullBody(bytesOf(status)))
    }

    private suspend fun HyperScope.pair(): Pair<neton.io.core.IoStream, neton.io.core.IoStream> {
        val (listener, port) = listenLocal()
        val client = connectLocal(port)
        return listener.accept().closeAtEnd() to client
    }

    @Test
    fun aBodyThatStopsArrivingIsAnsweredAndTheConnectionCloses() = hyperTest {
        val (server, client) = pair()
        val task = spawn { config.serveConnection(server, service).serve() }
        client.writeAll("POST / HTTP/1.1\r\nhost: x\r\ncontent-length: 10\r\n\r\nabc")    // 3 of 10 bytes, then nothing
        val reply = s(client.readToEnd())
        assertTrue(reply.startsWith("HTTP/1.1 408"), reply)
        assertTrue(reply.endsWith("timeout"), reply)
        runCatching { task.await() }                         // the connection ended (the read side was closed)
    }

    @Test
    fun aBodyBufferedWithItsHeadNeverArmsTheTimer() = hyperTest {
        val (server, client) = pair()
        val slow = HttpService { req ->
            delay(400)                                       // longer than the timeout, before reading a buffered body
            service.call(req)
        }
        spawn { config.serveConnection(server, slow).serve() }
        client.writeAll("POST / HTTP/1.1\r\nhost: x\r\ncontent-length: 3\r\n\r\nabc")
        val reply = s(client.readUntil { s(it).endsWith("\r\n\r\n3") })
        assertTrue(reply.startsWith("HTTP/1.1 200"), reply)
    }

    @Test
    fun aBodyThatArrivesInPartsWithinTheDeadlineIsRead() = hyperTest {
        val (server, client) = pair()
        spawn { config.serveConnection(server, service).serve() }
        client.writeAll("POST / HTTP/1.1\r\nhost: x\r\ntransfer-encoding: chunked\r\n\r\n")
        repeat(3) {
            delay(30)
            client.writeAll("2\r\nab\r\n")
        }
        client.writeAll("0\r\n\r\n")
        val reply = s(client.readUntil { s(it).endsWith("\r\n\r\n6") })
        assertTrue(reply.startsWith("HTTP/1.1 200"), reply)
    }

    @Test
    fun eachRequestOnAKeptAliveConnectionGetsItsOwnDeadline() = hyperTest {
        val (server, client) = pair()
        spawn { config.serveConnection(server, service).serve() }
        repeat(2) { i ->
            client.writeAll("POST / HTTP/1.1\r\nhost: x\r\ncontent-length: 4\r\n\r\nab")
            delay(120)                                       // 2 x 120 ms exceeds 200 ms: the second request must start afresh
            client.writeAll("cd")
            val reply = s(client.readUntil { s(it).endsWith("\r\n\r\n4") })
            assertTrue(reply.startsWith("HTTP/1.1 200"), "request ${i + 1}: $reply")
        }
    }

    @Test
    fun aStreamWithoutReadTimeoutsIsRefused() = hyperTest {
        val (server, _) = memoryStreamPair()
        val e = assertFailsWith<IllegalArgumentException> { config.serveConnection(server, service).serve() }
        assertTrue(e.message!!.contains("ReadTimeout"), e.message)
    }

    @Test
    fun zeroDisablesIt() = hyperTest {
        val (server, client) = pair()
        val off = config.copy(bodyReadTimeoutMillis = 0)
        spawn { off.serveConnection(server, service).serve() }
        client.writeAll("POST / HTTP/1.1\r\nhost: x\r\ncontent-length: 4\r\n\r\nab")
        delay(400)
        client.writeAll("cd")
        val reply = s(client.readUntil { s(it).endsWith("\r\n\r\n4") })
        assertEquals(true, reply.startsWith("HTTP/1.1 200"), reply)
    }
}
