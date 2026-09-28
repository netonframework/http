package neton.http.h1

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import neton.http.Body
import neton.http.EmptyBody
import neton.http.HttpError
import neton.http.OnUpgrade
import neton.http.Request
import neton.http.Response
import neton.http.StatusCode
import neton.http.Version
import neton.io.bytes.Buffer
import neton.io.core.IoStream
import neton.io.core.memoryStreamPair
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * hyper 1.11.1 `tests/server.rs`, the HTTP/1 tests, test for test (names are the Rust names in camelCase; the
 * `response_body_lengths` module's tests keep their own names). Skipped: the HTTP/2-only tests (`http2_*`, `h2_*`).
 *
 * ⚖️ tests (SPEC §3.9) run twice where the baseline changes the outcome: with the option restoring hyper's behaviour
 * (asserting hyper's result) and with the default (asserting the baseline's). The header-timeout tests are scaled down
 * ten times (hyper waits seconds); hyper's single timer is `headerReadTimeoutMillis == keepAliveIdleTimeoutMillis`.
 */
class HyperServerTest {

    /** Rust `str::lines()`: split on `\n`, strip a trailing `\r`, no empty last line after a final newline. */
    private fun rustLines(s: String): List<String> {
        val parts = s.split("\n").toMutableList()
        if (s.endsWith("\n")) parts.removeAt(parts.size - 1)
        return parts.map { it.removeSuffix("\r") }
    }

    /** hyper: `lines.next() == first`, then skipping the header lines the blank line is the last one (no body). */
    private fun assertStatusAndNoBody(response: String, first: String) {
        val lines = rustLines(response)
        assertEquals(first, lines.firstOrNull(), response)
        val rest = lines.drop(1).dropWhile { it.isNotEmpty() }
        assertEquals(listOf(""), rest, response)
    }

    @Test
    fun getShouldIgnoreBody() = hyperTest {
        val server = serve()
        val req = connectLocal(server.port)
        // Connection: close = don't try to parse the body as a new request
        req.writeAll("GET / HTTP/1.1\r\nHost: example.domain\r\nConnection: close\r\n\r\nI shouldn't be read.\r\n")
        req.readOnce()
        assertEquals("", s(server.body()))
    }

    @Test
    fun getWithBody() = hyperTest {
        val server = serve()
        val req = connectLocal(server.port)
        req.writeAll("GET / HTTP/1.1\r\nHost: example.domain\r\nContent-Length: 19\r\n\r\nI'm a good request.\r\n")
        req.readOnce()
        // note: doesn't include trailing \r\n, cause Content-Length wasn't 21
        assertEquals("I'm a good request.", s(server.body()))
    }

    // ---- mod response_body_lengths -------------------------------------------------------------------------------

    private sealed class Bd(val s: String) {
        class Known(s: String) : Bd(s)
        class Unknown(s: String) : Bd(s)
    }

    private class TestCase(
        val version: Int,
        val headers: List<Pair<String, String>>,
        val body: Bd,
        val expectsChunked: Boolean,
        val expectsConLen: Boolean,
    )

    private fun runTest(case: TestCase) = hyperTest {
        check(case.version == 0 || case.version == 1) { "TestCase.version must 0 or 1" }
        val server = serve()
        val reply = server.reply()
        for ((n, v) in case.headers) reply.header(n, v)
        val bodyStr = when (val b = case.body) {
            is Bd.Known -> { reply.body(b.s); b.s }
            is Bd.Unknown -> { reply.bodyStream(listOf(b.s.encodeToByteArray())); b.s }
        }
        val req = connectLocal(server.port)
        req.writeAll("GET / HTTP/1.${case.version}\r\nHost: example.domain\r\nConnection: close\r\n\r\n")
        val body = s(req.readToEnd())

        assertEquals(case.expectsChunked, hasHeader(body, "transfer-encoding:"), "expects_chunked, headers = $body")
        assertEquals(case.expectsChunked, hasHeader(body, "chunked\r\n"), "expects_chunked, headers = $body")
        assertEquals(case.expectsConLen, hasHeader(body, "content-length:"), "expects_con_len, headers = $body")

        val n = body.indexOf("\r\n\r\n") + 4
        if (case.expectsChunked) {
            if (bodyStr.isNotEmpty()) {
                val len = body.length
                assertEquals("\r\n", body.substring(n + 1, n + 3), "expected body chunk size header")
                assertEquals(bodyStr, body.substring(n + 3, len - 7), "expected body")
                assertEquals("\r\n0\r\n\r\n", body.substring(len - 7), "expected body final chunk size header")
            } else {
                assertEquals("0\r\n\r\n", body.substring(n))
            }
        } else {
            assertEquals(bodyStr, body.substring(n), "expected body")
        }
    }

    @Test
    fun fixedResponseKnown() = runTest(TestCase(1, listOf("content-length" to "11"), Bd.Known("foo bar baz"), expectsChunked = false, expectsConLen = true))

    @Test
    fun fixedResponseUnknown() = runTest(TestCase(1, listOf("content-length" to "11"), Bd.Unknown("foo bar baz"), expectsChunked = false, expectsConLen = true))

    @Test
    fun fixedResponseKnownEmpty() = runTest(TestCase(1, listOf("content-length" to "0"), Bd.Known(""), expectsChunked = false, expectsConLen = true))

    @Test
    fun chunkedResponseKnown() =
        // even though we know the length, don't strip user's TE header
        runTest(TestCase(1, listOf("transfer-encoding" to "chunked"), Bd.Known("foo bar baz"), expectsChunked = true, expectsConLen = false))

    @Test
    fun chunkedResponseKnownEmpty() =
        // should still send chunked, and 0\r\n\r\n
        runTest(TestCase(1, listOf("transfer-encoding" to "chunked"), Bd.Known(""), expectsChunked = true, expectsConLen = false))

    @Test
    fun chunkedResponseUnknown() = runTest(TestCase(1, listOf("transfer-encoding" to "chunked"), Bd.Unknown("foo bar baz"), expectsChunked = true, expectsConLen = false))

    @Test
    fun teResponseAddsChunked() = runTest(TestCase(1, listOf("transfer-encoding" to "gzip"), Bd.Unknown("foo bar baz"), expectsChunked = true, expectsConLen = false))

    /** Ignored in hyper too: "This used to be the case, but providing this functionality got in the way of performance." */
    @Ignore
    @Test
    fun chunkedResponseTrumpsLength() = runTest(
        TestCase(1, listOf("transfer-encoding" to "chunked", "content-length" to "11"), Bd.Known("foo bar baz"), expectsChunked = true, expectsConLen = false),
    )

    @Test
    fun autoResponseWithUnknownLength() = runTest(TestCase(1, emptyList(), Bd.Unknown("foo bar baz"), expectsChunked = true, expectsConLen = false))

    @Test
    fun autoResponseWithKnownLength() = runTest(TestCase(1, emptyList(), Bd.Known("foo bar baz"), expectsChunked = false, expectsConLen = true))

    @Test
    fun autoResponseKnownEmpty() = runTest(TestCase(1, emptyList(), Bd.Known(""), expectsChunked = false, expectsConLen = true))

    @Test
    fun http10AutoResponseWithUnknownLength() = runTest(TestCase(0, emptyList(), Bd.Unknown("foo bar baz"), expectsChunked = false, expectsConLen = false))

    @Test
    fun http10ChunkedResponse() =
        // http/1.0 should strip this header, even when we don't know the length
        runTest(TestCase(0, listOf("transfer-encoding" to "chunked"), Bd.Unknown("foo bar baz"), expectsChunked = false, expectsConLen = false))

    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun getResponseCustomReasonPhrase() = hyperTest {
        val server = serve()
        server.reply().reasonPhrase("Cool")
        val req = connectLocal(server.port)
        req.writeAll("GET / HTTP/1.1\r\nHost: example.domain\r\nConnection: close\r\n\r\n")
        assertStatusAndNoBody(s(req.readToEnd()), "HTTP/1.1 200 Cool")
    }

    @Test
    fun getChunkedResponseWithKa() = hyperTest {
        val fooBarChunk = "\r\nfoo bar baz\r\n0\r\n\r\n"
        val server = serve()
        server.reply().header("transfer-encoding", "chunked").body("foo bar baz")
        val req = connectLocal(server.port)
        req.writeAll("GET / HTTP/1.1\r\nHost: example.domain\r\nConnection: keep-alive\r\n\r\n")
        req.readUntil { it.endsWith(fooBarChunk) }

        // try again!
        val quux = "zar quux"
        server.reply().header("content-length", quux.length.toString()).body(quux)
        req.writeAll("GET /quux HTTP/1.1\r\nHost: example.domain\r\nConnection: close\r\n\r\n")
        req.readUntil { it.endsWith(quux) }
    }

    @Test
    fun postWithContentLengthBody() = hyperTest {
        val server = serve()
        val req = connectLocal(server.port)
        req.writeAll("POST / HTTP/1.1\r\nContent-Length: 5\r\n\r\nhello")
        req.readOnce()
        assertEquals("hello", s(server.body()))
    }

    @Test
    fun postWithInvalidPrefixContentLength() = hyperTest {
        val server = serve()
        val req = connectLocal(server.port)
        req.writeAll("POST / HTTP/1.1\r\nContent-Length: +5\r\n\r\nhello")
        val expected = "HTTP/1.1 400 Bad Request\r\n"
        assertTrue(s(req.readOnce()).startsWith(expected))
    }

    @Test
    fun postWithChunkedBody() = hyperTest {
        val server = serve()
        val req = connectLocal(server.port)
        req.writeAll("POST / HTTP/1.1\r\nHost: example.domain\r\nTransfer-Encoding: chunked\r\n\r\n1\r\nq\r\n2\r\nwe\r\n2\r\nrt\r\n0\r\n\r\n")
        req.readOnce()
        assertEquals("qwert", s(server.body()))
    }

    /**
     * ⚖️ SPEC §3.4 / §3.9: a chunk size has at most 16 hex digits. hyper reports the 17-digit size as an overflow; the
     * baseline rejects it at the 17th digit ("too many digits"), which has no option to turn off, so the baseline error
     * is asserted. Either way the body fails and the smuggled `GET /sneaky` is never served.
     */
    @Test
    fun postWithChunkedOverflow() = hyperTest {
        val server = serve()
        val req = connectLocal(server.port)
        req.writeAll(
            "POST / HTTP/1.1\r\nHost: example.domain\r\nTransfer-Encoding: chunked\r\n\r\n" +
                "f0000000000000003\r\nabc\r\n0\r\n\r\nGET /sneaky HTTP/1.1\r\n\r\n",
        )
        req.readOnce()
        val err = server.bodyErr()
        val source = err.cause as BodyDecodeException
        assertEquals(BodyDecodeError.TOO_MANY_SIZE_DIGITS, source.error, "$err")
        assertEquals(BodyErrorKind.INVALID_DATA, source.error.kind)
    }

    @Test
    fun postWithIncompleteBody() = hyperTest {
        val server = serve()
        val req = connectLocal(server.port)
        req.writeAll("POST / HTTP/1.1\r\nHost: example.domain\r\nContent-Length: 10\r\n\r\n12345")
        req.shutdownOutput()
        server.bodyErr()
        req.readOnce()
    }

    @Test
    fun postWithChunkedMissingFinalDigit() = hyperTest {
        val server = serve()
        val req = connectLocal(server.port)
        req.writeAll("POST / HTTP/1.1\r\nHost: example.domain\r\ntransfer-encoding: chunked\r\n\r\n1\r\nZ\r\n\r\n\r\n")
        server.bodyErr()
        req.readOnce()
    }

    @Test
    fun headResponseCanSendContentLength() = hyperTest {
        val server = serve()
        server.reply().header("content-length", "1024")
        val req = connectLocal(server.port)
        req.writeAll("HEAD / HTTP/1.1\r\nHost: example.domain\r\nConnection: close\r\n\r\n")
        val response = s(req.readToEnd())
        assertTrue(response.contains("content-length: 1024\r\n"), response)
        assertStatusAndNoBody(response, "HTTP/1.1 200 OK")
    }

    @Test
    fun headResponseDoesntSendBody() = hyperTest {
        val server = serve()
        server.reply().body("foo bar baz")
        val req = connectLocal(server.port)
        req.writeAll("HEAD / HTTP/1.1\r\nHost: example.domain\r\nConnection: close\r\n\r\n")
        val response = s(req.readToEnd())
        assertTrue(response.contains("content-length: 11\r\n"), response)
        assertStatusAndNoBody(response, "HTTP/1.1 200 OK")
    }

    @Test
    fun responseDoesNotSetChunkedIfBodyNotAllowed() = hyperTest {
        val server = serve()
        server.reply().status(StatusCode.NOT_MODIFIED).header("transfer-encoding", "chunked")
        val req = connectLocal(server.port)
        req.writeAll("GET / HTTP/1.1\r\nHost: example.domain\r\nConnection: close\r\n\r\n")
        val response = s(req.readToEnd())
        assertFalse(response.contains("transfer-encoding"), response)
        // no body or 0\r\n\r\n
        assertStatusAndNoBody(response, "HTTP/1.1 304 Not Modified")
    }

    @Test
    fun keepAlive() = hyperTest {
        val fooBar = "foo bar baz"
        val server = serve()
        server.reply().header("content-length", fooBar.length.toString()).body(fooBar)
        val req = connectLocal(server.port)
        req.writeAll("GET / HTTP/1.1\r\nHost: example.domain\r\n\r\n")
        req.readUntil { it.endsWith(fooBar) }

        // try again!
        val quux = "zar quux"
        server.reply().header("content-length", quux.length.toString()).body(quux)
        req.writeAll("GET /quux HTTP/1.1\r\nHost: example.domain\r\nConnection: close\r\n\r\n")
        req.readUntil { it.endsWith(quux) }
    }

    @Test
    fun http10KeepAlive() = hyperTest {
        val fooBar = "foo bar baz"
        val server = serve()
        // Response version 1.1 with no keep-alive header will downgrade to 1.0 when served
        server.reply().header("content-length", fooBar.length.toString()).body(fooBar)
        val req = connectLocal(server.port)
        req.writeAll("GET / HTTP/1.0\r\nHost: example.domain\r\nConnection: keep-alive\r\n\r\n")

        // Connection: keep-alive header should be added when downgrading to a 1.0 response
        val sres = s(req.readUntil { it.endsWith(fooBar) })
        assertTrue(sres.contains("connection: keep-alive\r\n"), "HTTP/1.0 response should have sent keep-alive: $sres")

        // try again!
        val quux = "zar quux"
        server.reply().header("content-length", quux.length.toString()).body(quux)
        req.writeAll("GET /quux HTTP/1.0\r\nHost: example.domain\r\n\r\n")
        req.readUntil { it.endsWith(quux) }
    }

    @Test
    fun http10CloseOnNoKa() = hyperTest {
        val fooBar = "foo bar baz"
        val server = serve()
        // A server response with version 1.0 but no keep-alive header
        server.reply().version(Version.HTTP_10).header("content-length", fooBar.length.toString()).body(fooBar)
        val req = connectLocal(server.port)
        // The client request with version 1.0 that may have the keep-alive header
        req.writeAll("GET / HTTP/1.0\r\nHost: example.domain\r\nConnection: keep-alive\r\n\r\n")

        // server isn't keeping-alive, so the socket should be closed after writing the response.
        val buf = s(req.readToEnd())
        assertTrue(buf.endsWith(fooBar), buf)
        assertFalse(buf.contains("connection: keep-alive\r\n"), "HTTP/1.0 response shouldn't have sent keep-alive: $buf")
    }

    @Test
    fun disableKeepAlive() = hyperTest {
        val fooBar = "foo bar baz"
        val server = serve(ServeOptions(keepAlive = false))
        server.reply().header("content-length", fooBar.length.toString()).body(fooBar)
        val req = connectLocal(server.port)
        req.writeAll("GET / HTTP/1.1\r\nHost: example.domain\r\nConnection: keep-alive\r\n\r\n")
        // server isn't keeping-alive, so the socket should be closed after writing the response.
        assertTrue(req.readToEnd().endsWith(fooBar))
    }

    @Test
    fun headerConnectionClose() = hyperTest {
        val fooBar = "foo bar baz"
        val server = serve()
        server.reply().header("content-length", fooBar.length.toString()).header("connection", "close").body(fooBar)
        val req = connectLocal(server.port)
        req.writeAll("GET / HTTP/1.1\r\nHost: example.domain\r\nConnection: keep-alive\r\n\r\n")
        // server isn't keeping-alive, so the socket should be closed after writing the response.
        val buf = s(req.readToEnd())
        assertTrue(buf.endsWith(fooBar), buf)
        assertTrue(buf.contains("connection: close\r\n"), "response should have sent close: $buf")
    }

    private suspend fun HyperScope.expectContinue(expectation: String) {
        val server = serve()
        val req = connectLocal(server.port)
        server.reply()
        req.writeAll("POST /foo HTTP/1.1\r\nHost: example.domain\r\nExpect: $expectation\r\nContent-Length: 5\r\nConnection: Close\r\n\r\n")
        val msg = "HTTP/1.1 100 Continue\r\n\r\n"
        val acc = Buffer()
        assertEquals(msg, s(req.readExact(msg.length, acc)))
        req.writeAll("hello")
        req.readToEnd()
        assertEquals("hello", s(server.body()))
    }

    @Test
    fun expectContinueSends100() = hyperTest { expectContinue("100-continue") }

    @Test
    fun expectContinueAcceptsUpperCasedExpectation() = hyperTest { expectContinue("100-Continue") }

    @Test
    fun expectContinueButHttp10IsIgnored() = hyperTest {
        val server = serve()
        val req = connectLocal(server.port)
        server.reply()
        req.writeAll("POST /foo HTTP/1.0\r\nHost: example.domain\r\nExpect: 100-Continue\r\nContent-Length: 5\r\nConnection: Close\r\n\r\n")
        req.writeAll("hello")
        val sLine = "HTTP/1.0 200 OK\r\n"
        val acc = Buffer()
        assertEquals(sLine, s(req.readExact(sLine.length, acc)))
        while (req.read(acc) >= 0) { }
        assertEquals("hello", s(server.body()))
    }

    @Test
    fun expectContinueButNoBodyIsIgnored() = hyperTest {
        val server = serve()
        val req = connectLocal(server.port)
        server.reply()
        // no content-length or transfer-encoding means no body!
        req.writeAll("POST /foo HTTP/1.1\r\nHost: example.domain\r\nExpect: 100-continue\r\nConnection: Close\r\n\r\n")
        val resp = s(req.readToEnd())
        assertTrue(resp.startsWith("HTTP/1.1 200 OK\r\n"), resp)
    }

    @Test
    fun expectContinueWaitsForBodyPoll() = hyperTest {
        val (listener, port) = listenLocal()
        val child = spawn {
            val tcp = connectLocal(port)
            tcp.writeAll("POST /foo HTTP/1.1\r\nHost: example.domain\r\nExpect: 100-continue\r\nContent-Length: 100\r\nConnection: Close\r\n\r\n")
            val resp = s(tcp.readToEnd())
            assertTrue(resp.startsWith("HTTP/1.1 400 Bad Request\r\n"), resp)
        }
        val socket = listener.accept().closeAtEnd()
        Http1ServerConfig().serveConnection(socket) { req ->
            assertEquals("100-continue", req.headers["expect"]!!.str())
            // But! We're never going to poll the body!
            delay(50)
            Response.builder().status(StatusCode.BAD_REQUEST).body(EmptyBody as Body)
        }.serve()
        child.await()
    }

    @Test
    fun pipelineDisabled() = hyperTest {
        val server = serve()
        val req = connectLocal(server.port)
        server.reply().header("content-length", "12").body("Hello World!")
        server.reply().header("content-length", "12").body("Hello World!")
        req.writeAll("GET / HTTP/1.1\r\nHost: example.domain\r\n\r\nGET / HTTP/1.1\r\nHost: example.domain\r\n\r\n")
        val n = req.readOnce().size
        assertNotEquals(0, n)
        // hyper: "wishy-washy because of race conditions": the responses may come in one read or two. The socket stays
        // open (no close requested), so a second read, if the first did not take both, is not EOF.
        val first = n
        if (first < 2 * 12) assertNotEquals(0, req.readOnce().size)
    }

    @Test
    fun pipelineEnabled() = hyperTest {
        val server = serve(ServeOptions(pipeline = true))
        val req = connectLocal(server.port)
        server.reply().header("content-length", "12").body("Hello World\n")
        server.reply().header("content-length", "12").body("Hello World\n")
        req.writeAll(
            "GET / HTTP/1.1\r\nHost: example.domain\r\n\r\n" +
                "GET / HTTP/1.1\r\nHost: example.domain\r\nConnection: close\r\n\r\n",
        )
        val buf = req.readOnce()
        assertNotEquals(0, buf.size)
        val lines = s(buf).split("\n").iterator()
        assertEquals("HTTP/1.1 200 OK\r", lines.next())
        assertEquals("content-length: 12\r", lines.next())
        lines.next() // Date
        assertEquals("\r", lines.next())
        assertEquals("Hello World", lines.next())

        assertEquals("HTTP/1.1 200 OK\r", lines.next())
        assertEquals("content-length: 12\r", lines.next())
        // close because the last request said to close
        assertEquals("connection: close\r", lines.next())
        lines.next() // Date
        assertEquals("\r", lines.next())
        assertEquals("Hello World", lines.next())

        // with pipeline enabled, both responses should have been in the first read so a second read should be EOF
        assertEquals(0, req.readOnce().size)
    }

    @Test
    fun http10RequestReceivesHttp10Response() = hyperTest {
        val server = serve()
        val req = connectLocal(server.port)
        req.writeAll("GET / HTTP/1.0\r\n\r\n")
        val expected = "HTTP/1.0 200 OK\r\ncontent-length: 0\r\n"
        val got = s(req.readUntil { it.size >= expected.length })
        assertEquals(expected, got.substring(0, expected.length))
    }

    /**
     * ⚖️ SPEC §3.3 / §3.9: hyper answers a URI over 65534 bytes with 414; the baseline limits the request line to 8 KiB
     * (also 414). Run once with the request-line and head limits raised (hyper's 65534 rule gives the 414) and once with
     * the defaults (the 8 KiB rule gives it).
     */
    @Test
    fun http11UriTooLong() {
        for (config in listOf(Http1ServerConfig(maxRequestLineSize = 1 shl 20, maxHeaderSectionSize = 1 shl 20), Http1ServerConfig())) {
            hyperTest {
                val server = serve(config = config)
                val longPath = "a".repeat(65534)
                val req = connectLocal(server.port)
                req.writeAll("GET /$longPath HTTP/1.1\r\n\r\n")
                val expected = "HTTP/1.1 414 URI Too Long\r\nconnection: close\r\ncontent-length: 0\r\n"
                val got = s(req.readUntil { it.size >= expected.length })
                assertEquals(expected, got.substring(0, expected.length))
            }
        }
    }

    @Test
    fun disableKeepAliveMidRequest() = hyperTest {
        val (clientIo, serverIo) = memoryStreamPair(1024)
        val tx1 = CompletableDeferred<Unit>()
        val tx2 = CompletableDeferred<Unit>()
        val clientTask = spawn {
            // Send partial request
            clientIo.writeAll("GET / HTTP/1.1\r\n")
            // Signal server that partial request sent
            tx1.complete(Unit)
            // Wait for server to be ready for rest of request
            tx2.await()
            // Send rest of request
            clientIo.writeAll("Host: localhost\r\n\r\n")
            // Read response
            val buf = s(clientIo.readToEnd())
            assertTrue(buf.startsWith("HTTP/1.1 200 OK\r\n"), "should receive OK response, but buf: $buf")
            assertTrue(buf.contains("connection: close\r\n"), "response should have sent close: $buf")
        }
        val conn = memoryServerConfig().serveConnection(serverIo, helloWorld)
        val srv = spawn { conn.serve() }
        tx1.await()
        // hyper polls the connection (which reads the partial head) before it sees the signal.
        delay(20)
        assertFalse(srv.isCompleted, "expected rx first")
        conn.gracefulShutdown()
        tx2.complete(Unit)
        srv.await()
        clientTask.await()
    }

    /** hyper `DebugStream` + `Dropped`: records when the transport is dropped (closed). */
    private class DroppedStream(inner: IoStream) : ForwardingStream(inner) {
        var dropped = false
        override fun close() { dropped = true; super.close() }
    }

    @Test
    fun disableKeepAlivePostRequest() = hyperTest {
        val (listener, port) = listenLocal()
        val tx1 = CompletableDeferred<Unit>()
        val child = spawn {
            val req = connectLocal(port)
            req.writeAll("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")
            req.readUntil { it.endsWith(HELLO) }
            // Connection should get closed *after* tx is sent on
            tx1.complete(Unit)
            assertEquals(0, req.readOnce().size, "keep-alive reading")
        }
        val transport = DroppedStream(listener.accept().closeAtEnd())
        val server = Http1ServerConfig().serveConnection(transport, helloWorld)
        val fut = spawn { server.serve() }
        tx1.await()
        assertFalse(fut.isCompleted, "expected rx first")
        // hyper checks `!dropped` right after `graceful_shutdown()`: its transport is dropped with the finished future.
        // Here `gracefulShutdown` of an idle connection closes the transport itself (that wakes the parked head read),
        // so the check is made before the call.
        assertFalse(transport.dropped)
        server.gracefulShutdown()
        fut.await()
        assertTrue(transport.dropped)
        child.await()
    }

    @Test
    fun http1GracefulShutdownAfterUpgrade() = hyperTest {
        val (listener, port) = listenLocal()
        val read101 = CompletableDeferred<Unit>()
        spawn {
            val tcp = connectLocal(port)
            tcp.writeAll("GET / HTTP/1.1\r\nUpgrade: foobar\r\nConnection: upgrade\r\n\r\neagerly optimistic")
            val response = s(tcp.readOnce())
            assertTrue(response.startsWith("HTTP/1.1 101 Switching Protocols\r\n"), response)
            assertFalse(hasHeader(response, "content-length"), response)
            read101.complete(Unit)
        }
        val upgrades = ArrayDeque<OnUpgrade>()
        val svc = HttpService { req ->
            upgrades.addLast(req.extensions.get<OnUpgrade>()!!)
            Response.builder().status(101).header("upgrade", "foobar").body(EmptyBody as Body)
        }
        val socket = listener.accept().closeAtEnd()
        val conn = Http1ServerConfig(upgrades = true).serveConnection(socket, svc)
        conn.serve()

        val onUpgrade = upgrades.removeFirst()
        // wait so that we don't write until other side saw 101 response
        read101.await()
        val upgraded = onUpgrade.await()
        val (_, readBuf) = upgraded.downcast()
        assertEquals("eagerly optimistic", s(readBuf.toByteArray()))

        // graceful shutdown doesn't cause issues or panic. It should be ignored after upgrade
        conn.gracefulShutdown()
    }

    @Test
    fun emptyParseEofDoesNotReturnError() = hyperTest {
        val (listener, port) = listenLocal()
        spawn { connectLocal(port).close() }
        val socket = listener.accept().closeAtEnd()
        Http1ServerConfig().serveConnection(socket, helloWorld).serve()        // empty parse eof is ok
    }

    @Test
    fun nonemptyParseEofReturnsError() = hyperTest {
        val (listener, port) = listenLocal()
        spawn {
            val tcp = connectLocal(port)
            tcp.writeAll("GET / HTTP/1.1")
            tcp.close()
        }
        val socket = listener.accept().closeAtEnd()
        assertFailsWith<HttpError>("partial parse eof is error") { Http1ServerConfig().serveConnection(socket, helloWorld).serve() }
    }

    @Test
    fun http1AllowHalfClose() = hyperTest {
        val (listener, port) = listenLocal()
        val t1 = spawn {
            val tcp = connectLocal(port)
            tcp.writeAll("GET / HTTP/1.1\r\n\r\n")
            tcp.shutdownOutput()
            val expected = "HTTP/1.1 200 OK\r\n"
            assertEquals(expected, s(tcp.readUntil { it.size >= expected.length }).substring(0, expected.length))
        }
        val socket = listener.accept().closeAtEnd()
        Http1ServerConfig(halfClose = true).serveConnection(socket) {
            delay(500)
            Response(EmptyBody as Body)
        }.serve()
        t1.await()
    }

    @Test
    fun disconnectAfterReadingRequestBeforeResponding() = hyperTest {
        val (listener, port) = listenLocal()
        spawn {
            val tcp = connectLocal(port)
            tcp.writeAll("GET / HTTP/1.1\r\n\r\n")
            tcp.close()
        }
        val socket = listener.accept().closeAtEnd()
        assertFailsWith<HttpError>("socket disconnected") {
            Http1ServerConfig(halfClose = false).serveConnection(socket) {
                delay(2_000)
                error("response future should have been dropped")
            }.serve()
        }
    }

    @Test
    fun returning1xxResponseIsError() = hyperTest {
        val (listener, port) = listenLocal()
        val client = spawn {
            val tcp = connectLocal(port)
            tcp.writeAll("GET / HTTP/1.1\r\n\r\n")
            val expected = "HTTP/1.1 500 "
            assertEquals(expected, s(tcp.readUntil { it.size >= expected.length }).substring(0, expected.length))
        }
        val socket = listener.accept().closeAtEnd()
        assertFailsWith<HttpError>("1xx status code should error") {
            Http1ServerConfig().serveConnection(socket) {
                Response.builder().status(StatusCode.CONTINUE).body(EmptyBody as Body)
            }.serve()
        }
        client.await()
    }

    /**
     * ⚖️ SPEC §3.7: hyper answers a header name of 64 KiB or more with 431; the baseline's 64 KiB head limit answers the
     * same request with 431 first. Run once with the head limit raised (hyper's name rule) and once with the default.
     */
    @Test
    fun headerNameTooLong() {
        for (config in listOf(Http1ServerConfig(maxHeaderSectionSize = 1 shl 20), Http1ServerConfig())) {
            hyperTest {
                val server = serve(config = config)
                val req = connectLocal(server.port)
                req.writeAll("GET / HTTP/1.1\r\n" + "x".repeat(1024 * 64) + ": foo\r\n\r\n")
                val expected = "HTTP/1.1 431 Request Header Fields Too Large\r\n"
                assertTrue(s(req.readUntil { it.size >= expected.length }).startsWith(expected))
            }
        }
    }

    // ---- header read timeouts (⚖️ SPEC §3.7: 10 s from the first byte + 60 s keep-alive idle; hyper: one 30 s timer
    // from the start of each wait). Scaled 1:10; each runs with hyper's single timer (both options equal) and with the
    // baseline split (a much longer idle timeout).

    private fun hyperTimer(ms: Long) = Http1ServerConfig(headerReadTimeoutMillis = ms, keepAliveIdleTimeoutMillis = ms)
    private fun baselineTimer(ms: Long) = Http1ServerConfig(headerReadTimeoutMillis = ms, keepAliveIdleTimeoutMillis = 60_000)

    private fun okService() = HttpService { Response.builder().status(200).body(EmptyBody as Body) }

    @Test
    fun headerReadTimeoutSlowWrites() {
        for (config in listOf(hyperTimer(500), baselineTimer(500))) {
            hyperTest {
                val (listener, port) = listenLocal()
                spawn {
                    val tcp = connectLocal(port)
                    tcp.writeAll("GET / HTTP/1.1\r\n")
                    delay(300)
                    tcp.writeAll("Something: 1\r\n")
                    delay(600)
                    runCatching { tcp.writeAll("Works: 0\r\n\r\n") }         // hyper: expect_err("write 3"), not joined
                }
                val socket = listener.accept().closeAtEnd()
                val e = assertFailsWith<HttpError>("header timeout") { config.serveConnection(socket, okService()).serve() }
                assertTrue(e.isTimeout(), "$e")
            }
        }
    }

    @Test
    fun headerReadTimeoutStartsImmediately() {
        for (config in listOf(hyperTimer(200), baselineTimer(200))) {
            hyperTest {
                val (listener, port) = listenLocal()
                val child = spawn {
                    val tcp = connectLocal(port)
                    delay(300)
                    assertEquals(0, tcp.readOnce().size) // eof
                }
                val socket = listener.accept().closeAtEnd()
                val e = assertFailsWith<HttpError> { config.serveConnection(socket, unreachableService).serve() }
                assertTrue(e.isTimeout(), "$e")
                child.await()
            }
        }
    }

    @Test
    fun headerReadTimeoutSlowWritesMultipleRequests() {
        for (config in listOf(hyperTimer(500), baselineTimer(500))) {
            hyperTest {
                val (listener, port) = listenLocal()
                spawn {
                    val tcp = connectLocal(port)
                    tcp.writeAll("GET / HTTP/1.1\r\n")
                    delay(300)
                    tcp.writeAll("Something: 1\r\n\r\n")
                    delay(300)
                    tcp.writeAll("GET / HTTP/1.1\r\n")
                    delay(300)
                    tcp.writeAll("Something: 1\r\n\r\n")
                    delay(600)
                    runCatching {
                        tcp.writeAll("GET / HTTP/1.1\r\nSomething: 1\r\n")
                        delay(600)
                        tcp.writeAll("Works: 0\r\n\r\n")                     // hyper: expect_err("write 6"), not joined
                    }
                }
                val socket = listener.accept().closeAtEnd()
                val e = assertFailsWith<HttpError> { config.serveConnection(socket, okService()).serve() }
                assertTrue(e.isTimeout(), "$e")
            }
        }
    }

    /**
     * ⚖️ hyper's header timer also runs while a kept-alive connection is idle; the baseline's idle wait has its own
     * (longer) timeout. With both set equal (hyper) the idle connection times out; with the baseline split the second
     * request, sent after twice the header timeout, is still served.
     */
    @Test
    fun headerReadTimeoutAsIdleTimeout() {
        hyperTest {
            val (listener, port) = listenLocal()
            spawn {
                val tcp = connectLocal(port)
                tcp.writeAll("GET / HTTP/1.1\r\n\r\n")
                delay(600)
                runCatching { tcp.writeAll("GET / HTTP/1.1\r\n\r\n") }       // hyper: expect_err("request 2"), not joined
            }
            val socket = listener.accept().closeAtEnd()
            val e = assertFailsWith<HttpError> { hyperTimer(300).serveConnection(socket, okService()).serve() }
            assertTrue(e.isTimeout(), "$e")
        }
        hyperTest {
            val (listener, port) = listenLocal()
            val client = spawn {
                val tcp = connectLocal(port)
                tcp.writeAll("GET / HTTP/1.1\r\n\r\n")
                tcp.readUntil { s(it).endsWith("\r\n\r\n") }
                delay(600)
                tcp.writeAll("GET / HTTP/1.1\r\nconnection: close\r\n\r\n")
                s(tcp.readToEnd())
            }
            val socket = listener.accept().closeAtEnd()
            baselineTimer(300).serveConnection(socket, okService()).serve()
            assertTrue(client.await().startsWith("HTTP/1.1 200 OK\r\n"))
        }
    }

    // ---- upgrades ----------------------------------------------------------------------------------------------

    /**
     * hyper's `upgrades` / `http_connect` recover the connection with `Connection::without_shutdown()` (`Parts`);
     * this library has no `into_parts` / `without_shutdown`, the switch is always handed over through [OnUpgrade]
     * ([Http1ServerConfig.upgrades]), so they run like `upgrades_new` / `http_connect_new` without the service asking.
     */
    private suspend fun HyperScope.upgradeWithoutShutdown(request: String, expected: String) {
        val (listener, port) = listenLocal()
        val tx = CompletableDeferred<Unit>()
        val client = spawn {
            val tcp = connectLocal(port)
            tcp.writeAll(request)
            val got = s(tcp.readUntil { it.size >= expected.length })
            assertEquals(expected, got.substring(0, expected.length))
            tx.complete(Unit)
            assertEquals("foo=bar", s(tcp.readOnce()))
            tcp.writeAll("bar=foo")
            tcp.close()
        }
        var onUpgrade: OnUpgrade? = null
        val socket = listener.accept().closeAtEnd()
        Http1ServerConfig(upgrades = true).serveConnection(socket) { req ->
            onUpgrade = req.extensions.get<OnUpgrade>()
            if (req.method == neton.http.Method.CONNECT) Response.builder().status(200).body(EmptyBody as Body)
            else Response.builder().status(101).header("upgrade", "foobar").body(EmptyBody as Body)
        }.serve()
        val (io, readBuf) = onUpgrade!!.await().downcast()
        assertEquals("eagerly optimistic", s(readBuf.toByteArray()))

        // wait so that we don't write until other side saw 101 response
        tx.await()
        io.writeAll("foo=bar")
        assertEquals("bar=foo", s(io.readToEnd()))
        client.await()
    }

    @Test
    fun upgrades() = hyperTest {
        upgradeWithoutShutdown("GET / HTTP/1.1\r\nUpgrade: foobar\r\nConnection: upgrade\r\n\r\neagerly optimistic", "HTTP/1.1 101 Switching Protocols\r\n")
    }

    @Test
    fun httpConnect() = hyperTest {
        upgradeWithoutShutdown("CONNECT localhost:80 HTTP/1.1\r\n\r\neagerly optimistic", "HTTP/1.1 200 OK\r\n")
    }

    @Test
    fun upgradesNew() = hyperTest {
        val (listener, port) = listenLocal()
        val read101 = CompletableDeferred<Unit>()
        val client = spawn {
            val tcp = connectLocal(port)
            tcp.writeAll("GET / HTTP/1.1\r\nUpgrade: foobar\r\nConnection: upgrade\r\n\r\neagerly optimistic")
            val response = s(tcp.readOnce())
            assertTrue(response.startsWith("HTTP/1.1 101 Switching Protocols\r\n"), response)
            assertFalse(hasHeader(response, "content-length"), response)
            read101.complete(Unit)
            assertEquals("foo=bar", s(tcp.readOnce()))
            tcp.writeAll("bar=foo")
            tcp.close()
        }
        val upgrades = ArrayDeque<OnUpgrade>()
        val svc = HttpService { req ->
            upgrades.addLast(req.extensions.get<OnUpgrade>()!!)
            Response.builder().status(101).header("upgrade", "foobar").body(EmptyBody as Body)
        }
        val socket = listener.accept().closeAtEnd()
        Http1ServerConfig(upgrades = true).serveConnection(socket, svc).serve()

        val onUpgrade = upgrades.removeFirst()
        // wait so that we don't write until other side saw 101 response
        read101.await()
        val upgraded = onUpgrade.await()
        val (io, readBuf) = upgraded.downcast()
        assertEquals("eagerly optimistic", s(readBuf.toByteArray()))
        io.writeAll("foo=bar")
        assertEquals("bar=foo", s(io.readToEnd()))
        client.await()
    }

    @Test
    fun upgradesIgnored() = hyperTest {
        val (listener, port) = listenLocal()
        val svc = HttpService { req ->
            assertEquals("yolo", req.headers["upgrade"]!!.str())
            Response(EmptyBody as Body)
        }
        val serverTasks = ArrayList<kotlinx.coroutines.Deferred<Unit>>()
        spawn {
            while (true) {
                val socket = listener.accept().closeAtEnd()
                serverTasks.add(spawn { Http1ServerConfig(upgrades = true).serveConnection(socket, svc).serve() })
            }
        }
        fun makeReq(): Request<Body> =
            Request.builder().uri("http://127.0.0.1:$port/").header("upgrade", "yolo").header("connection", "upgrade").body(EmptyBody as Body)

        val res1 = clientRequest(port, makeReq())
        assertEquals(200, res1.status.asU16())
        val res2 = clientRequest(port, makeReq())
        assertEquals(200, res2.status.asU16())
        for (t in serverTasks) if (t.isCompleted) t.await()          // hyper: .expect("server task")
    }

    @Test
    fun httpConnectNew() = hyperTest {
        val (listener, port) = listenLocal()
        val read200 = CompletableDeferred<Unit>()
        val client = spawn {
            val tcp = connectLocal(port)
            tcp.writeAll("CONNECT localhost HTTP/1.1\r\n\r\neagerly optimistic")
            val expected = "HTTP/1.1 200 OK\r\n"
            assertEquals(expected, s(tcp.readUntil { it.size >= expected.length }).substring(0, expected.length))
            read200.complete(Unit)
            assertEquals("foo=bar", s(tcp.readOnce()))
            tcp.writeAll("bar=foo")
            tcp.close()
        }
        val upgrades = ArrayDeque<OnUpgrade>()
        val svc = HttpService { req ->
            upgrades.addLast(req.extensions.get<OnUpgrade>()!!)
            Response.builder().status(200).body(EmptyBody as Body)
        }
        val socket = listener.accept().closeAtEnd()
        Http1ServerConfig(upgrades = true).serveConnection(socket, svc).serve()

        val onUpgrade = upgrades.removeFirst()
        // wait so that we don't write until other side saw 200
        read200.await()
        val (io, readBuf) = onUpgrade.await().downcast()
        assertEquals("eagerly optimistic", s(readBuf.toByteArray()))
        io.writeAll("foo=bar")
        assertEquals("bar=foo", s(io.readToEnd()))
        client.await()
    }

    // ---------------------------------------------------------------------------------------------------------------

    @Test
    fun parseErrorsSend4xxResponse() = hyperTest {
        val (listener, port) = listenLocal()
        val client = spawn {
            val tcp = connectLocal(port)
            tcp.writeAll("GE T / HTTP/1.1\r\n\r\n")
            val expected = "HTTP/1.1 400 "
            assertEquals(expected, s(tcp.readUntil { it.size >= expected.length }).substring(0, expected.length))
        }
        val socket = listener.accept().closeAtEnd()
        assertFailsWith<HttpError>("HTTP parse error") { Http1ServerConfig().serveConnection(socket, helloWorld).serve() }
        client.await()
    }

    @Test
    fun illegalRequestLengthReturns400Response() = hyperTest {
        val (listener, port) = listenLocal()
        val client = spawn {
            val tcp = connectLocal(port)
            tcp.writeAll("POST / HTTP/1.1\r\nContent-Length: foo\r\n\r\n")
            val expected = "HTTP/1.1 400 "
            assertEquals(expected, s(tcp.readUntil { it.size >= expected.length }).substring(0, expected.length))
        }
        val socket = listener.accept().closeAtEnd()
        assertFailsWith<HttpError>("illegal Content-Length should error") { Http1ServerConfig().serveConnection(socket, helloWorld).serve() }
        client.await()
    }

    /** hyper panics in `Builder::max_buf_size`; here the options are checked when a connection is made from them. */
    @Test
    fun maxBufSizePanicTooSmall() {
        val (a, _) = memoryStreamPair()
        assertFailsWith<IllegalArgumentException> { memoryServerConfig(maxBufSize = 8191).serveConnection(a, helloWorld) }
    }

    @Test
    fun maxBufSizeNoPanic() {
        val (a, _) = memoryStreamPair()
        memoryServerConfig(maxBufSize = 8193).serveConnection(a, helloWorld)
    }

    /**
     * ⚖️ SPEC §3.9: an unterminated request line past 8 KiB is answered with 414 by the baseline; hyper only sees the
     * read buffer reaching `max_buf_size` (431). Run once with the request-line limit raised (hyper's 431) and once with
     * the default (414).
     */
    @Test
    fun maxBufSize() {
        val max = 16_000
        for ((config, status) in listOf(
            Http1ServerConfig(maxBufSize = max, maxRequestLineSize = 1 shl 20) to "431",
            Http1ServerConfig(maxBufSize = max) to "414",
        )) {
            hyperTest {
                val (listener, port) = listenLocal()
                val client = spawn {
                    val tcp = connectLocal(port)
                    tcp.writeAll("POST /")
                    tcp.writeAll(ByteArray(max) { 'a'.code.toByte() })
                    val expected = "HTTP/1.1 $status "
                    assertEquals(expected, s(tcp.readUntil { it.size >= expected.length }).substring(0, expected.length))
                }
                val socket = listener.accept().closeAtEnd()
                assertFailsWith<HttpError>("should TooLarge error") { config.serveConnection(socket, helloWorld).serve() }
                client.await()
            }
        }
    }

    @Test
    fun maxBufSizeSplitHeaderBoundary() = hyperTest {
        val max = 8192
        val (listener, port) = listenLocal()
        val client = spawn {
            val tcp = connectLocal(port)
            tcp.writeAll("GET / HTTP/1.1\r\nHost: x\r\nConnection: close\r\nX: ")
            tcp.writeAll(ByteArray(7000) { 'a'.code.toByte() })
            delay(100)
            tcp.writeAll(ByteArray(5000) { 'a'.code.toByte() })
            tcp.writeAll("\r\n\r\n")
            val buf = s(tcp.readToEnd())
            assertTrue(buf.startsWith("HTTP/1.1 431 "), buf)
        }
        val socket = listener.accept().closeAtEnd()
        assertFailsWith<HttpError>("should TooLarge error") { Http1ServerConfig(maxBufSize = max).serveConnection(socket, helloWorld).serve() }
        client.await()
    }

    @Test
    fun gracefulShutdownBeforeFirstRequestNoBlock() = hyperTest {
        val (listener, port) = listenLocal()
        val server = spawn {
            val socket = listener.accept().closeAtEnd()
            val future = Http1ServerConfig().serveConnection(socket, helloWorld)
            future.gracefulShutdown()
            future.serve()
        }
        val stream = connectLocal(port)
        kotlinx.coroutines.withTimeout(5_000) { stream.readToEnd() }      // "timed out waiting for graceful shutdown"
        server.await()
    }

    @Test
    fun streamingBody() = hyperTest {
        // disable keep-alive so we can use read_to_end
        val server = serve(ServeOptions(keepAlive = false))
        server.reply().bodyStream(List(100) { ByteArray(1_000) { 'x'.code.toByte() } })
        val tcp = connectLocal(server.port)
        tcp.writeAll("GET / HTTP/1.1\r\n\r\n")
        val buf = tcp.readToEnd()
        assertTrue(s(buf).startsWith("HTTP/1.1 200 OK\r\n"), "response is 200 OK")
        assertEquals(100_808, buf.size, "full streamed body read")
    }

    @Test
    fun http1ResponseWithHttp2Version() = hyperTest {
        val server = serve()
        server.reply().version(Version.HTTP_2)
        clientRequest(server.port, Request.get("http://127.0.0.1:${server.port}/").body(EmptyBody as Body))
    }

    /**
     * hyper connects with an HTTP/2-only client and expects an error. The HTTP/2 client is not part of this port; its
     * first bytes (the connection preface) are sent instead: the HTTP/1 connection fails (`VersionH2`) without writing
     * an HTTP/1 response.
     */
    @Test
    fun http1Only() = hyperTest {
        val (listener, port) = listenLocal()
        val client = spawn {
            val tcp = connectLocal(port)
            tcp.writeAll(H1Conn.H2_PREFACE)
            s(tcp.readToEnd())
        }
        val socket = listener.accept().closeAtEnd()
        val e = assertFailsWith<HttpError> { Http1ServerConfig().serveConnection(socket, helloWorld).serve() }
        assertTrue(e.isParseVersionH2(), "$e")
        assertEquals("", client.await())
    }

    @Test
    fun skipsContentLengthFor304Responses() = hyperTest {
        val server = serve()
        server.reply().status(StatusCode.NOT_MODIFIED).body("foo")
        val req = connectLocal(server.port)
        req.writeAll("GET / HTTP/1.1\r\nHost: example.domain\r\nConnection: close\r\n\r\n")
        val response = s(req.readToEnd())
        assertFalse(response.contains("content-length:"), response)
    }

    @Test
    fun skipsContentLengthAndBodyFor304Responses() = hyperTest {
        val server = serve()
        server.reply().status(StatusCode.NOT_MODIFIED).body("foo")
        val req = connectLocal(server.port)
        req.writeAll("GET / HTTP/1.1\r\nHost: example.domain\r\nConnection: close\r\n\r\n")
        val response = s(req.readToEnd())
        assertFalse(response.contains("content-length:"), response)
        assertStatusAndNoBody(response, "HTTP/1.1 304 Not Modified")
    }

    @Test
    fun noImplicitZeroContentLengthForHeadResponses() = hyperTest {
        val server = serve()
        server.reply().status(StatusCode.OK).body(ByteArray(0))
        val req = connectLocal(server.port)
        req.writeAll("HEAD / HTTP/1.1\r\nHost: example.domain\r\nConnection: close\r\n\r\n")
        val response = s(req.readToEnd())
        assertFalse(response.contains("content-length:"), response)
    }

    // ---- trailers ----------------------------------------------------------------------------------------------

    private suspend fun HyperScope.trailerTest(trailerHeader: String, te: List<String>, trailers: List<Pair<String, String>>, expectedBody: String) {
        val headers = headerMapOf(*trailers.toTypedArray())
        val server = serve()
        server.reply()
            .header("transfer-encoding", "chunked")
            .header("trailer", trailerHeader)
            .bodyStreamWithTrailers(listOf("hello".encodeToByteArray()), headers)
        val req = connectLocal(server.port)
        req.writeAll("GET / HTTP/1.1\r\nHost: example.domain\r\nConnection: keep-alive\r\n" + te.joinToString("") { "TE: $it\r\n" } + "\r\n")
        // hyper reads until the trailer chunk, or the last chunk when no trailer is expected
        val end = if (expectedBody.contains("chunky-trailer")) "\r\nchunky-trailer: header data\r\n\r\n" else "\r\n0\r\n\r\n"
        val sres = s(req.readUntil { it.endsWith(end) })
        val expectedHead = "HTTP/1.1 200 OK\r\ntransfer-encoding: chunked\r\ntrailer: $trailerHeader\r\n"
        assertEquals(expectedHead, sres.substring(0, expectedHead.length))
        // skip the date header
        val dateFragment = "GMT\r\n\r\n"
        val pos = sres.indexOf(dateFragment)
        assertTrue(pos >= 0, "find GMT")
        assertEquals(expectedBody, sres.substring(pos + dateFragment.length))
    }

    private val sendTrailers = listOf(
        "chunky-trailer" to "header data",
        // Invalid trailer field that should not be sent
        "Host" to "www.example.com",
        // Not specified in Trailer header, so should not be sent
        "foo" to "bar",
    )

    @Test
    fun http1TrailerSendFields() = hyperTest {
        trailerTest("chunky-trailer", listOf("trailers"), sendTrailers, "5\r\nhello\r\n0\r\nchunky-trailer: header data\r\n\r\n")
    }

    @Test
    fun http1TrailerSendFieldsTitlecase() = hyperTest {
        trailerTest("Chunky-Trailer", listOf("trailers"), sendTrailers, "5\r\nhello\r\n0\r\nchunky-trailer: header data\r\n\r\n")
    }

    @Test
    fun http1TrailerFieldsNotAllowed() = hyperTest {
        // TE: trailers is not specified in request headers: no trailer fields should be sent
        trailerTest("chunky-trailer", emptyList(), listOf("chunky-trailer" to "header data"), "5\r\nhello\r\n0\r\n\r\n")
    }

    @Test
    fun http1TrailerFieldsAllowedWithCommaSeparatedTe() = hyperTest {
        trailerTest("chunky-trailer", listOf("gzip, Trailers"), listOf("chunky-trailer" to "header data"), "5\r\nhello\r\n0\r\nchunky-trailer: header data\r\n\r\n")
    }

    @Test
    fun http1TrailerFieldsAllowedWithMultipleTeHeaders() = hyperTest {
        trailerTest("chunky-trailer", listOf("gzip", "trailers"), listOf("chunky-trailer" to "header data"), "5\r\nhello\r\n0\r\nchunky-trailer: header data\r\n\r\n")
    }

    @Test
    fun http1TrailerRecvFields() = hyperTest {
        val server = serve()
        val req = connectLocal(server.port)
        req.writeAll(
            "POST / HTTP/1.1\r\ntrailer: chunky-trailer\r\nhost: example.domain\r\ntransfer-encoding: chunked\r\n\r\n" +
                "5\r\nhello\r\n0\r\nchunky-trailer: header data\r\n\r\n",
        )
        assertEquals("hello", s(server.body()))
        assertEquals("header data", server.trailers()["chunky-trailer"]?.str())
    }

    @Test
    fun http1TrailerRecvKeepAlive() = hyperTest {
        val server = serve()
        server.reply().header("content-length", "2").body("ok")
        val req = connectLocal(server.port)

        // First request: chunked POST with trailers
        req.writeAll(
            "POST / HTTP/1.1\r\ntrailer: chunky-trailer\r\nhost: example.domain\r\ntransfer-encoding: chunked\r\n\r\n" +
                "5\r\nhello\r\n0\r\nchunky-trailer: header data\r\n\r\n",
        )
        assertEquals("hello", s(server.body()))
        assertEquals("header data", server.trailers()["chunky-trailer"]?.str())
        req.readUntil { it.endsWith("ok") }

        // Second request: reuse the same connection to verify keep-alive
        val quux = "zar quux"
        server.reply().header("content-length", quux.length.toString()).body(quux)
        req.writeAll("GET /quux HTTP/1.1\r\nHost: example.domain\r\nConnection: close\r\n\r\n")
        req.readUntil { it.endsWith(quux) }
    }
}
