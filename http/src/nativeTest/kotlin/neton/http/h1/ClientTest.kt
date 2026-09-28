package neton.http.h1

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import neton.http.Body
import neton.http.EmptyBody
import neton.http.Frame
import neton.http.FullBody
import neton.http.HttpError
import neton.http.Incoming
import neton.http.Method
import neton.http.Request
import neton.http.Response
import neton.http.StatusCode
import neton.http.upgradeOn
import neton.io.bytes.Buffer
import neton.io.bytes.Bytes
import neton.io.core.IoStream
import neton.io.core.memoryStreamPair
import neton.io.net.runReactor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Client connection basics (SPEC §3.10) against a scripted server and against [Http1Connection]. */
class ClientTest {
    private fun bytesOf(s: String) = Bytes.copyOf(s.encodeToByteArray())
    private suspend fun IoStream.send(s: String) { write(Buffer().also { it.writeBytes(s.encodeToByteArray()) }) }
    private suspend fun IoStream.readUntil(marker: String): String {
        val acc = Buffer()
        while (!acc.peekAll().decodeToString().contains(marker)) { if (read(acc) < 0) break }
        return acc.readAll().decodeToString()
    }
    private suspend fun Incoming.readAllText(): String {
        val sb = StringBuilder()
        while (true) { val f = nextFrame() ?: break; if (f is Frame.Data) sb.append(f.bytes.toByteArray().decodeToString()) }
        return sb.toString()
    }
    private fun get(path: String): Request<Body> = Request.builder().uri(path).body(EmptyBody as Body)

    @Test
    fun requestAndResponseOverScriptedServer() = runReactor {
        val (a, b) = memoryStreamPair()
        val (sender, connection) = http1Handshake(a)
        val run = launch { connection.run() }
        val res = async { sender.sendRequest(Request.builder().method(Method.POST).uri("/x").body(FullBody(bytesOf("hi")) as Body)) }
        assertEquals("POST /x HTTP/1.1\r\ncontent-length: 2\r\n\r\nhi", b.readUntil("\r\n\r\nhi"))
        b.send("HTTP/1.1 200 OK\r\ncontent-length: 5\r\n\r\nhello")
        val r = withTimeout(5_000) { res.await() }
        assertEquals(StatusCode.OK, r.status)
        assertEquals("hello", r.body.readAllText())
        // Keep-alive: a second request on the same connection.
        sender.ready()
        val res2 = async { sender.sendRequest(get("/y")) }
        assertEquals("GET /y HTTP/1.1\r\n\r\n", b.readUntil("\r\n\r\n"))
        b.send("HTTP/1.1 204 No Content\r\n\r\n")
        assertEquals(StatusCode.NO_CONTENT, withTimeout(5_000) { res2.await() }.status)
        // The server closes the idle connection: the client connection ends cleanly.
        b.close()
        withTimeout(5_000) { run.join() }
        assertTrue(sender.isClosed)
    }

    @Test
    fun notReadyIsCanceled() = runReactor {
        val (a, b) = memoryStreamPair()
        val (sender, connection) = http1Handshake(a)
        val run = launch { runCatching { connection.run() } }
        val first = async { sender.sendRequest(get("/1")) }
        b.readUntil("\r\n\r\n")
        val e = assertFailsWith<HttpError> { sender.sendRequest(get("/2")) }
        assertTrue(e.isCanceled())
        b.send("HTTP/1.1 200 OK\r\ncontent-length: 0\r\n\r\n")
        withTimeout(5_000) { first.await() }
        b.close()
        withTimeout(5_000) { run.join() }
    }

    @Test
    fun informationalResponsesAreSkippedAndReported() = runReactor {
        val (a, b) = memoryStreamPair()
        val (sender, connection) = http1Handshake(a)
        launch { runCatching { connection.run() } }
        val seen = ArrayList<Int>()
        val req = get("/").also { it.extensions.insert(OnInformational { p -> seen.add(p.status.asU16()) }) }
        val res = async { sender.sendRequest(req) }
        b.readUntil("\r\n\r\n")
        b.send("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 103 Early Hints\r\n\r\nHTTP/1.1 200 OK\r\ncontent-length: 0\r\n\r\n")
        assertEquals(StatusCode.OK, withTimeout(5_000) { res.await() }.status)
        assertEquals(listOf(100, 103), seen)
        b.close()
    }

    @Test
    fun serverClosingMidResponseIsIncomplete() = runReactor {
        val (a, b) = memoryStreamPair()
        val (sender, connection) = http1Handshake(a)
        val run = async { runCatching { connection.run() } }
        val res = async { runCatching { sender.sendRequest(get("/")) } }
        b.readUntil("\r\n\r\n")
        b.send("HTTP/1.1 200 OK\r\ncontent-le")
        b.close()
        val e = withTimeout(5_000) { res.await() }.exceptionOrNull()
        assertTrue(e is HttpError && e.isIncompleteMessage(), "$e")
        assertTrue(withTimeout(5_000) { run.await() }.isFailure)
    }

    @Test
    fun endToEndWithServerIncludingChunkedAndUpgrade() = runReactor {
        val (a, b) = memoryStreamPair()
        val serverCfg = Http1ServerConfig(headerReadTimeoutMillis = 0, keepAliveIdleTimeoutMillis = 0, autoDateHeader = false, upgrades = true)
        val server = async {
            serverCfg.serveConnection(b) { req ->
                when (req.uri.toString()) {
                    "/echo" -> Response.builder().body(req.body as Body)                                   // streams the request back
                    "/upgrade" -> {
                        launch {
                            val up = upgradeOn(req.extensions)
                            up.write(Buffer().also { it.writeBytes("raw!".encodeToByteArray()) })
                            up.close()
                        }
                        Response.builder().status(101).header("upgrade", "foo").header("connection", "upgrade").body(EmptyBody as Body)
                    }
                    else -> Response.builder().body(FullBody(bytesOf("ok")) as Body)
                }
            }.serve()
        }
        val (sender, connection) = Http1ClientConfig(upgrades = true).handshake(a)
        val client = async { connection.run() }
        // A chunked request body (unknown length) echoed back chunked.
        val chunks = object : Body {
            var i = 0
            override suspend fun nextFrame(): Frame? = if (i < 3) Frame.Data(bytesOf("part${i++};")) else null
        }
        val r1 = sender.sendRequest(Request.builder().method(Method.POST).uri("/echo").body(chunks as Body))
        assertEquals("part0;part1;part2;", r1.body.readAllText())
        sender.ready()
        assertEquals("ok", sender.sendRequest(get("/plain")).body.readAllText())
        sender.ready()
        val r3 = sender.sendRequest(Request.builder().uri("/upgrade").header("upgrade", "foo").header("connection", "upgrade").body(EmptyBody as Body))
        assertEquals(StatusCode.SWITCHING_PROTOCOLS, r3.status)
        val up = withTimeout(5_000) { upgradeOn(r3.extensions) }
        val acc = Buffer()
        while (up.read(acc) >= 0) { }
        assertEquals("raw!", acc.readAll().decodeToString())
        withTimeout(5_000) { client.await(); server.await() }
    }
}
