package neton.http.h1

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
import neton.http.Version
import neton.http.h1.parse.ParserConfig
import neton.http.header.HeaderMap
import neton.http.header.HeaderValue
import neton.http.uri.Uri
import neton.http.upgradeOn
import neton.io.bytes.Buffer
import neton.io.core.IoException
import neton.io.core.IoStream
import neton.io.core.memoryStreamPair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * hyper 1.11.1 `tests/client.rs`: the `test!` cases and the `conn` module, HTTP/1 only, test for test (names are the
 * Rust names in camelCase).
 *
 * Not ported: the HTTP/2 tests (`http2_*`, `h2_*`); `test_try_send_request` (`try_send_request` hands a failed request
 * back for hyper-util's legacy pooled client to retry, no such API here); and three `test!` cases that only exercise
 * the macro's emulation of the legacy client (`client_requires_absolute_uri`, `client_h1_rejects_http2`,
 * `client_always_rejects_http09`: the errors come from the test harness, not from hyper). The harness's `HttpInfo`
 * check (the legacy `HttpConnector`'s extension) is dropped likewise.
 */
class HyperClientTest {

    // ---- the `test!` harness --------------------------------------------------------------------------------------

    private class ClientCase(
        val method: Method = Method.GET,
        val url: String,
        val headers: List<Pair<String, String>> = emptyList(),
        val version: Version? = null,
        val body: (() -> Body)? = null,
        val config: Http1ClientConfig = Http1ClientConfig(),
        val setHost: Boolean = true,
    )

    /**
     * hyper `test! { INNER }`: a scripted TCP server reads the expected request (`{addr}` is the server's address) and
     * writes [reply]; the client sends the request the way the macro builds it (a `host` header unless disabled, the
     * target reduced to the path, or to the authority for CONNECT). Returns the response, or the client's error.
     */
    private suspend fun HyperScope.runClientTest(expected: String, reply: ByteArray, case: ClientCase, serverMustSucceed: Boolean): Result<Response<Incoming>> {
        val (listener, port) = listenLocal()
        val addr = "127.0.0.1:$port"
        val server = spawn {
            val inc = listener.accept().closeAtEnd()
            val exp = expected.replace("{addr}", addr)
            val acc = Buffer()
            while (acc.readableBytes < 4096 && acc.readableBytes < exp.length) {
                check(inc.read(acc) >= 0) { "failed to read request, partially read = ${s(acc.peekAll())}" }
            }
            assertEquals(exp, s(acc.readAll()))
            inc.writeAll(reply)
            inc.close()                    // the thread ends: the socket is dropped
        }
        val res = runCatching {
            val req = Request.builder().method(case.method).uri(case.url.replace("{addr}", addr)).body(case.body?.invoke() ?: EmptyBody as Body)
            for ((n, v) in case.headers) req.headers.append(n, HeaderValue.fromStr(v))
            case.version?.let { req.version = it }
            val host = req.uri.host!!
            val p = req.uri.portU16 ?: 80
            val stream = connectLocal(p)
            check(host == "127.0.0.1")
            if (case.setHost) req.headers.append("Host", HeaderValue.fromStr("$host:${req.uri.portU16}"))
            val (sender, conn) = case.config.handshake(stream)
            spawn { conn.run() }                             // hyper: panics in the spawned task on an error
            req.uri = if (req.method == Method.CONNECT) Uri.parse("$host:${req.uri.portU16}")
            else Uri.parse(req.uri.pathAndQuery?.toString()?.takeIf { it.isNotEmpty() } ?: "/")
            sender.sendRequest(req)
        }
        if (serverMustSucceed) server.await()
        return res
    }

    /** hyper `test!` with a `response:` block. */
    private fun clientTest(
        expected: String,
        reply: String,
        case: ClientCase,
        status: StatusCode = StatusCode.OK,
        headers: List<Pair<String, String>> = emptyList(),
        body: String? = null,
        trailers: List<Pair<String, String>>? = null,
    ) = hyperTest {
        val res = runClientTest(expected, reply.encodeToByteArray(), case, serverMustSucceed = true).getOrThrow()
        assertEquals(status, res.status)
        for ((n, v) in headers) assertEquals(v, res.headers[n]?.str() ?: error("response header '$n'"), "response header '$n'")
        val (b, t) = res.body.collect()
        assertEquals(body ?: "", s(b))
        if (trailers != null) {
            assertNotNull(t, "trailers is None")
            for ((n, v) in trailers) assertEquals(v, t[n]?.str() ?: error("trailer header '$n'"), "trailer '$n'")
        }
    }

    /** hyper `test!` with an `error:` closure. */
    private fun clientErrorTest(expected: String, reply: ByteArray, case: ClientCase, check: (HttpError) -> Boolean) = hyperTest {
        val err = runClientTest(expected, reply, case, serverMustSucceed = false).exceptionOrNull()
        assertTrue(err is HttpError && check(err), "expected error, unexpected variant: $err")
    }

    private val replyOk = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"

    private fun full(s: String): () -> Body = { FullBody(bytesOf(s)) }
    private fun stream(s: String): () -> Body = { StreamBody(listOf(s.encodeToByteArray())) }

    @Test
    fun clientGet() = clientTest(
        "GET / HTTP/1.1\r\nhost: {addr}\r\n\r\n", replyOk,
        ClientCase(url = "http://{addr}/"),
        headers = listOf("Content-Length" to "0"),
    )

    @Test
    fun clientGetQuery() = clientTest(
        "GET /foo?key=val HTTP/1.1\r\nhost: {addr}\r\n\r\n", replyOk,
        ClientCase(url = "http://{addr}/foo?key=val#dont_send_me"),
        headers = listOf("Content-Length" to "0"),
    )

    @Test
    fun clientGetReqBodyImplicitlyEmpty() = clientTest(
        "GET / HTTP/1.1\r\nhost: {addr}\r\n\r\n", replyOk,
        ClientCase(url = "http://{addr}/", body = full("")), // not Body::empty
    )

    @Test
    fun clientGetReqBodyChunked() = clientTest(
        "GET / HTTP/1.1\r\ntransfer-encoding: chunked\r\nhost: {addr}\r\n\r\n5\r\nhello\r\n0\r\n\r\n", replyOk,
        ClientCase(url = "http://{addr}/", headers = listOf("transfer-encoding" to "chunked"), body = full("hello")),
    )

    @Test
    fun clientTransferEncodingRepair() = clientTest(
        "GET / HTTP/1.1\r\ntransfer-encoding: foo, chunked\r\nhost: {addr}\r\n\r\n5\r\nhello\r\n0\r\n\r\n", replyOk,
        ClientCase(url = "http://{addr}/", headers = listOf("transfer-encoding" to "foo"), body = full("hello")),
    )

    @Test
    fun clientGetReqBodyChunkedHttp10() = clientTest(
        "GET / HTTP/1.0\r\nhost: {addr}\r\ncontent-length: 5\r\n\r\nhello", "HTTP/1.0 200 OK\r\ncontent-length: 0\r\n\r\n",
        ClientCase(url = "http://{addr}/", headers = listOf("transfer-encoding" to "chunked"), version = Version.HTTP_10, body = full("hello")),
    )

    @Test
    fun clientGetReqBodyChunkedWithTrailer() = clientTest(
        "GET / HTTP/1.1\r\nhost: {addr}\r\n\r\n",
        "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\nTrailer: value\r\n\r\n",
        ClientCase(url = "http://{addr}/"),
        body = "hello",
    )

    @Test
    fun clientGetReqBodyChunkedWithMultipleTrailers() = clientTest(
        "GET / HTTP/1.1\r\nhost: {addr}\r\n\r\n",
        "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\nTrailer: value\r\nanother-trainer: another-value\r\n\r\n",
        ClientCase(url = "http://{addr}/"),
        body = "hello",
    )

    private fun chunkyTrailers(): HeaderMap<HeaderValue> = headerMapOf("chunky-trailer" to "header data")

    @Test
    fun clientPostReqBodyChunkedWithTrailer() = clientTest(
        "POST / HTTP/1.1\r\ntrailer: chunky-trailer\r\nhost: {addr}\r\ntransfer-encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\nchunky-trailer: header data\r\n\r\n",
        replyOk,
        ClientCase(
            method = Method.POST, url = "http://{addr}/", headers = listOf("trailer" to "chunky-trailer"),
            body = { streamBodyWithTrailers(listOf("hello".encodeToByteArray()), chunkyTrailers()) },
        ),
    )

    @Test
    fun clientPostReqBodyChunkedWithTrailerTitlecase() = clientTest(
        "POST / HTTP/1.1\r\ntrailer: Chunky-Trailer\r\nhost: {addr}\r\ntransfer-encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\nchunky-trailer: header data\r\n\r\n",
        replyOk,
        ClientCase(
            method = Method.POST, url = "http://{addr}/", headers = listOf("trailer" to "Chunky-Trailer"),
            body = { streamBodyWithTrailers(listOf("hello".encodeToByteArray()), chunkyTrailers()) },
        ),
    )

    @Test
    fun clientResBodyChunkedWithTrailer() = clientTest(
        "GET / HTTP/1.1\r\nte: trailers\r\nhost: {addr}\r\n\r\n",
        "HTTP/1.1 200 OK\r\ntransfer-encoding: chunked\r\ntrailer: chunky-trailer\r\n\r\n5\r\nhello\r\n0\r\nchunky-trailer: header data\r\n\r\n",
        ClientCase(url = "http://{addr}/", headers = listOf("te" to "trailers")),
        headers = listOf("Transfer-Encoding" to "chunked"),
        body = "hello",
        trailers = listOf("chunky-trailer" to "header data"),
    )

    @Test
    fun clientResBodyChunkedWithTrailerTitlecase() = clientTest(
        "GET / HTTP/1.1\r\nte: trailers\r\nhost: {addr}\r\n\r\n",
        "HTTP/1.1 200 OK\r\ntransfer-encoding: chunked\r\ntrailer: Chunky-Trailer\r\n\r\n5\r\nhello\r\n0\r\nchunky-trailer: header data\r\n\r\n",
        ClientCase(url = "http://{addr}/", headers = listOf("te" to "trailers")),
        headers = listOf("Transfer-Encoding" to "chunked"),
        body = "hello",
        trailers = listOf("chunky-trailer" to "header data"),
    )

    @Test
    fun clientResBodyChunkedWithPathologicalTrailers() = clientTest(
        "GET / HTTP/1.1\r\nte: trailers\r\nhost: {addr}\r\n\r\n",
        "HTTP/1.1 200 OK\r\ntransfer-encoding: chunked\r\n" +
            "trailer: chunky-trailer1, chunky-trailer2, chunky-trailer3, chunky-trailer4, chunky-trailer5\r\n\r\n" +
            "5\r\nhello\r\n0\r\n" +
            "chunky-trailer1: header data1\r\nchunky-trailer2: header data2\r\nchunky-trailer3: header data3\r\n" +
            "chunky-trailer4: header data4\r\nchunky-trailer5: header data5\r\nsneaky-trailer: not in trailer header\r\n" +
            "transfer-encoding: chunked\r\ncontent-length: 5\r\ntrailer: foo\r\n\r\n",
        ClientCase(url = "http://{addr}/", headers = listOf("te" to "trailers")),
        headers = listOf("Transfer-Encoding" to "chunked"),
        body = "hello",
        trailers = listOf(
            "chunky-trailer1" to "header data1", "chunky-trailer2" to "header data2", "chunky-trailer3" to "header data3",
            "chunky-trailer4" to "header data4", "chunky-trailer5" to "header data5", "sneaky-trailer" to "not in trailer header",
            "transfer-encoding" to "chunked", "content-length" to "5", "trailer" to "foo",
        ),
    )

    @Test
    fun clientGetReqBodySized() = clientTest(
        "GET / HTTP/1.1\r\ncontent-length: 5\r\nhost: {addr}\r\n\r\nhello", replyOk,
        // use a "stream" (where Body doesn't know length) with a content-length header
        ClientCase(url = "http://{addr}/", headers = listOf("Content-Length" to "5"), body = stream("hello")),
    )

    @Test
    fun clientGetReqBodyUnknown() = clientTest(
        "GET / HTTP/1.1\r\nhost: {addr}\r\n\r\n", replyOk,
        // the headers cannot tell the length and the method typically doesn't have a body: the body is ignored
        ClientCase(url = "http://{addr}/", body = stream("hello")),
    )

    @Test
    fun clientGetReqBodyUnknownHttp10() = clientTest(
        "GET / HTTP/1.0\r\nhost: {addr}\r\n\r\n", "HTTP/1.0 200 OK\r\ncontent-length: 0\r\n\r\n",
        ClientCase(url = "http://{addr}/", headers = listOf("transfer-encoding" to "chunked"), version = Version.HTTP_10, body = stream("hello")),
    )

    @Test
    fun clientPostSized() = clientTest(
        "POST /length HTTP/1.1\r\ncontent-length: 7\r\nhost: {addr}\r\n\r\nfoo bar", replyOk,
        ClientCase(method = Method.POST, url = "http://{addr}/length", headers = listOf("Content-Length" to "7"), body = full("foo bar")),
    )

    @Test
    fun clientPostChunked() = clientTest(
        "POST /chunks HTTP/1.1\r\ntransfer-encoding: chunked\r\nhost: {addr}\r\n\r\nB\r\nfoo bar baz\r\n0\r\n\r\n", replyOk,
        ClientCase(method = Method.POST, url = "http://{addr}/chunks", headers = listOf("Transfer-Encoding" to "chunked"), body = full("foo bar baz")),
    )

    @Test
    fun clientPostUnknown() = clientTest(
        "POST /chunks HTTP/1.1\r\nhost: {addr}\r\ntransfer-encoding: chunked\r\n\r\nB\r\nfoo bar baz\r\n0\r\n\r\n", replyOk,
        // use a stream to "hide" that the full amount is known
        ClientCase(method = Method.POST, url = "http://{addr}/chunks", body = stream("foo bar baz")),
    )

    @Test
    fun clientPostEmpty() = clientTest(
        "POST /empty HTTP/1.1\r\ncontent-length: 0\r\nhost: {addr}\r\n\r\n", replyOk,
        ClientCase(method = Method.POST, url = "http://{addr}/empty", headers = listOf("Content-Length" to "0")),
    )

    @Test
    fun clientHeadIgnoresBody() = clientTest(
        "HEAD /head HTTP/1.1\r\nhost: {addr}\r\n\r\n", "HTTP/1.1 200 OK\r\ncontent-Length: 11\r\n\r\nHello World",
        ClientCase(method = Method.HEAD, url = "http://{addr}/head"),
    )

    @Test
    fun clientResponseTransferEncodingNotChunked() = clientTest(
        "GET /te-not-chunked HTTP/1.1\r\nhost: {addr}\r\n\r\n", "HTTP/1.1 200 OK\r\ntransfer-encoding: yolo\r\n\r\nhallo",
        ClientCase(url = "http://{addr}/te-not-chunked"),
        headers = listOf("transfer-encoding" to "yolo"),
        body = "hallo",
    )

    @Test
    fun clientPipelineResponsesExtra() = clientTest(
        "GET /pipe HTTP/1.1\r\nhost: {addr}\r\n\r\n",
        "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n",
        ClientCase(url = "http://{addr}/pipe"),
    )

    @Test
    fun clientErrorUnexpectedEof() = clientErrorTest(
        "GET /err HTTP/1.1\r\nhost: {addr}\r\n\r\n", "HTTP/1.1 200 OK\r\n".encodeToByteArray(), // unexpected eof before double CRLF
        ClientCase(url = "http://{addr}/err"),
    ) { it.isIncompleteMessage() }

    @Test
    fun clientErrorParseVersion() = clientErrorTest(
        "GET /err HTTP/1.1\r\nhost: {addr}\r\n\r\n", "HEAT/1.1 200 OK\r\n\r\n".encodeToByteArray(),
        ClientCase(url = "http://{addr}/err"),
    ) { it.isParse() } // should get a Parse(Version) error

    /**
     * ⚖️ SPEC §3.7: hyper fails the 1 MB head when its read buffer reaches `max_buf_size`; the baseline's 64 KiB head
     * limit fails it first. Both are TooLarge: run with the head limit raised (hyper) and with the default.
     */
    @Test
    fun clientErrorParseTooLarge() {
        val longHeader = "A".repeat(500_000)
        val reply = "HTTP/1.1 200 OK\r\n$longHeader: $longHeader\r\n\r\n".encodeToByteArray()
        for (config in listOf(Http1ClientConfig(maxHeaderSectionSize = 1 shl 24), Http1ClientConfig())) {
            clientErrorTest("GET /err HTTP/1.1\r\nhost: {addr}\r\n\r\n", reply, ClientCase(url = "http://{addr}/err", config = config)) {
                it.isParse() && it.isParseTooLarge()   // should get a Parse(TooLarge) error
            }
        }
    }

    @Test
    fun clientErrorParseStatusOutOfRange() = clientErrorTest(
        "GET /err HTTP/1.1\r\nhost: {addr}\r\n\r\n", "HTTP/1.1 001 OK\r\n\r\n".encodeToByteArray(),
        ClientCase(url = "http://{addr}/err"),
    ) { it.isParse() && it.isParseStatus() }

    @Test
    fun clientErrorParseStatusSyntacticallyInvalid() = clientErrorTest(
        "GET /err HTTP/1.1\r\nhost: {addr}\r\n\r\n", "HTTP/1.1 1 OK\r\n\r\n".encodeToByteArray(),
        ClientCase(url = "http://{addr}/err"),
    ) { it.isParse() && it.isParseStatus() }

    @Test
    fun client100Continue() = clientTest(
        "POST /continue HTTP/1.1\r\ncontent-length: 7\r\nhost: {addr}\r\n\r\nfoo bar",
        "HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n",
        ClientCase(method = Method.POST, url = "http://{addr}/continue", headers = listOf("Content-Length" to "7"), body = full("foo bar")),
    )

    @Test
    fun clientConnectMethod() = clientTest(
        "CONNECT {addr} HTTP/1.1\r\nhost: {addr}\r\n\r\n", "HTTP/1.1 200 OK\r\n\r\n",
        ClientCase(method = Method.CONNECT, url = "{addr}"),
    )

    @Test
    fun clientConnectMethodWithAbsoluteUri() = clientTest(
        "CONNECT {addr} HTTP/1.1\r\nhost: {addr}\r\n\r\n", "HTTP/1.1 200 OK\r\n\r\n",
        ClientCase(method = Method.CONNECT, url = "http://{addr}"),
    )

    @Test
    fun clientSetHostFalse() = clientTest(
        // {addr} is here because format! requires it to exist in the string
        "GET /no-host/{addr} HTTP/1.1\r\n\r\n", "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n",
        ClientCase(url = "http://{addr}/no-host/{addr}", setHost = false),
    )

    @Test
    fun clientSetHttp1TitleCaseHeaders() = clientTest(
        "GET / HTTP/1.1\r\nX-Test-Header: test\r\nHost: {addr}\r\n\r\n", "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n",
        ClientCase(url = "http://{addr}/", headers = listOf("X-Test-Header" to "test"), config = Http1ClientConfig(titleCaseHeaders = true)),
    )

    @Test
    fun clientHandlesContentlengthValuesOnSameLine() = clientTest(
        "GET /foo HTTP/1.1\r\nhost: {addr}\r\n\r\n", "HTTP/1.1 200 OK\r\nContent-Length: 3,3\r\nContent-Length: 3,3\r\n\r\nabc\r\n",
        ClientCase(url = "http://{addr}/foo"),
        body = "abc",
    )

    @Test
    fun clientAllowsHttp09WhenRequested() = clientTest(
        "GET / HTTP/1.1\r\nhost: {addr}\r\n\r\n", "Mmmmh, baguettes.",
        ClientCase(url = "http://{addr}/", config = Http1ClientConfig(h09Responses = true)),
        body = "Mmmmh, baguettes.",
    )

    @Test
    fun clientObsFoldHeaders() = clientTest(
        "GET / HTTP/1.1\r\nhost: {addr}\r\n\r\n", "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nFold: just\r\n some\r\n\t folding\r\n\r\n",
        ClientCase(url = "http://{addr}/", config = Http1ClientConfig(parser = ParserConfig(allowObsoleteMultilineHeadersInResponses = true))),
        headers = listOf("fold" to "just some folding"),
    )

    // ---- mod conn -------------------------------------------------------------------------------------------------

    private fun get(uri: String): Request<Body> = Request.builder().uri(uri).body(EmptyBody as Body)

    @Test
    fun get() = hyperTest {
        val (listener, port) = listenLocal()
        val server = spawn {
            val sock = listener.accept().closeAtEnd()
            // Notably: just a path, since just a path was set; no host, since no host was set
            assertEquals("GET /a HTTP/1.1\r\n\r\n", s(sock.readOnce()))
            sock.writeAll("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n")
        }
        val (client, conn) = http1Handshake(connectLocal(port))
        spawn { conn.run() }
        val res = client.sendRequest(get("/a"))
        assertEquals(StatusCode.OK, res.status)
        assertNull(res.body.nextFrame())
        server.await()
    }

    @Test
    fun getCustomReasonPhrase() = hyperTest {
        val (listener, port) = listenLocal()
        val server = spawn {
            val sock = listener.accept().closeAtEnd()
            assertEquals("GET /a HTTP/1.1\r\n\r\n", s(sock.readOnce()))
            sock.writeAll("HTTP/1.1 200 Alright\r\nContent-Length: 0\r\n\r\n")
        }
        val (client, conn) = http1Handshake(connectLocal(port))
        spawn { conn.run() }
        val res = client.sendRequest(get("/a"))
        assertEquals(StatusCode.OK, res.status)
        assertEquals("Alright", s(res.extensions.get<ReasonPhrase>()!!.asBytes()), "custom reason phrase is present")
        assertEquals(1, res.headers.len())
        assertEquals("0", res.headers["content-length"]!!.str())
        assertNull(res.body.nextFrame())
        server.await()
    }

    @Test
    fun incomingContentLength() = hyperTest {
        val (listener, port) = listenLocal()
        val tx1 = CompletableDeferred<Unit>()
        spawn {
            val sock = listener.accept().closeAtEnd()
            assertEquals("GET / HTTP/1.1\r\n\r\n", s(sock.readOnce()))
            sock.writeAll("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello")
            tx1.complete(Unit)
        }
        val (client, conn) = http1Handshake(connectLocal(port))
        spawn { conn.run() }
        val res = client.sendRequest(get("/"))
        assertEquals(StatusCode.OK, res.status)
        assertEquals(5L, res.body.sizeHint.exact)
        assertFalse(res.body.isEndStream)
        val chunk = res.body.nextFrame()!!
        tx1.await()
        delay(200)
        assertEquals(5, (chunk as Frame.Data).bytes.size)
    }

    @Test
    fun droppedConnSendsIncompleteBodyError() = hyperTest {
        val (listener, port) = listenLocal()
        val release = CompletableDeferred<Unit>()
        val server = spawn {
            val sock = listener.accept().closeAtEnd()
            assertEquals("GET / HTTP/1.1\r\n\r\n", s(sock.readOnce()))
            sock.writeAll("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\n")
            release.await()
        }
        val (client, conn) = http1Handshake(connectLocal(port))
        val connTask = spawn { conn.run() }
        val res = client.sendRequest(get("/"))
        assertEquals(StatusCode.OK, res.status)
        assertEquals(5L, res.body.sizeHint.exact)
        assertFalse(res.body.isEndStream)

        connTask.cancel()
        connTask.join()
        assertTrue(connTask.isCancelled, "conn task should be aborted")

        val err = assertFailsWith<HttpError> { res.body.nextFrame() }
        assertTrue(err.isIncompleteMessage(), "$err")
        release.complete(Unit)
        server.await()
    }

    @Test
    fun abortedBodyIsntCompleted() = hyperTest {
        val (listener, port) = listenLocal()
        val tx = CompletableDeferred<Unit>()
        val server = spawn {
            val sock = listener.accept().closeAtEnd()
            val expected = "POST / HTTP/1.1\r\ntransfer-encoding: chunked\r\n\r\n5\r\nhello\r\n"
            val acc = Buffer()
            assertEquals(expected, s(sock.readExact(expected.length, acc)))
            tx.complete(Unit)
            assertEquals(-1, sock.read(acc), "read 2")
        }
        val (client, conn) = http1Handshake(connectLocal(port))
        spawn { conn.run() }
        val ch = Channel<Result<Frame>>(Channel.UNLIMITED)
        val sender = spawn {
            ch.send(Result.success(Frame.Data(bytesOf("hello"))))
            tx.await()
            // Aborts the body in an abnormal fashion.
            ch.send(Result.failure(IoException("body write aborted")))
        }
        val req = Request.builder().method(Method.POST).uri("/").body(ChannelBody(ch) as Body)
        assertFailsWith<HttpError> { client.sendRequest(req) }
        server.await()
        sender.await()
    }

    @Test
    fun uriAbsoluteForm() = hyperTest {
        val (listener, port) = listenLocal()
        val server = spawn {
            val sock = listener.accept().closeAtEnd()
            // Notably: still no Host header, since it wasn't set
            assertEquals("GET http://hyper.local/a HTTP/1.1\r\n\r\n", s(sock.readOnce()))
            sock.writeAll("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n")
        }
        val (client, conn) = http1Handshake(connectLocal(port))
        spawn { conn.run() }
        val res = client.sendRequest(get("http://hyper.local/a"))
        assertEquals(StatusCode.OK, res.status)
        res.body.concat()
        server.await()
    }

    @Test
    fun http1ConnCoercesHttp2Request() = hyperTest {
        val (listener, port) = listenLocal()
        val server = spawn {
            val sock = listener.accept().closeAtEnd()
            // Not HTTP/2, nor panicked
            assertEquals("GET /a HTTP/1.1\r\n\r\n", s(sock.readOnce()))
            sock.writeAll("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n")
        }
        val (client, conn) = http1Handshake(connectLocal(port))
        spawn { conn.run() }
        val res = client.sendRequest(Request.builder().uri("/a").version(Version.HTTP_2).body(EmptyBody as Body))
        assertEquals(StatusCode.OK, res.status)
        res.body.concat()
        server.await()
    }

    @Test
    fun pipeline() = hyperTest {
        val (listener, port) = listenLocal()
        val server = spawn {
            val sock = listener.accept().closeAtEnd()
            sock.readOnce()
            sock.writeAll("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n")
        }
        val (client, conn) = http1Handshake(connectLocal(port))
        spawn { conn.run() }
        val res1 = async(start = CoroutineStart.UNDISPATCHED) {
            val res = client.sendRequest(get("/a"))
            assertEquals(StatusCode.OK, res.status)
            res.body.concat()
        }
        // pipelined request will hit NotReady, and thus should return an Error::Cancel
        val err = assertFailsWith<HttpError>("res2") { client.sendRequest(get("/b")) }
        assertTrue(err.isCanceled(), "err not canceled, $err")
        res1.await()
        server.await()
    }

    /** hyper's `DebugStream`: records whether the connection shut the stream's write side. */
    private class ShutdownRecordingStream(inner: IoStream) : ForwardingStream(inner) {
        var shutdownCalled = false
        override suspend fun shutdownOutput() { shutdownCalled = true; super.shutdownOutput() }
    }

    /**
     * hyper's `upgrade` / `connect_method` drive the connection with `poll_without_shutdown` and take it back with
     * `into_parts`; this library has no such API, the connection is handed over through [neton.http.OnUpgrade]
     * ([Http1ClientConfig.upgrades]): the same checks are made on the [neton.http.Upgraded] connection.
     */
    private suspend fun HyperScope.upgradeLike(request: (port: Int) -> Request<Body>, reply: String, check: (Response<Incoming>) -> Unit) {
        val (listener, port) = listenLocal()
        val tx1 = CompletableDeferred<Unit>()
        val server = spawn {
            val sock = listener.accept().closeAtEnd()
            sock.readOnce()
            sock.writeAll(reply)
            tx1.complete(Unit)
            assertEquals("foo=bar", s(sock.readOnce()))
            sock.writeAll("bar=foo")
            sock.close()
        }
        val io = ShutdownRecordingStream(connectLocal(port))
        val (client, conn) = Http1ClientConfig(upgrades = true).handshake(io)
        val untilUpgrade = spawn { conn.run() }
        val res = client.sendRequest(request(port))
        check(res)
        assertEquals("", s(res.body.concat()))
        tx1.await()
        delay(200)
        untilUpgrade.await()
        // should not be ready now
        assertFalse(client.isReady)

        val (upgradedIo, buf) = upgradeOn(res.extensions).downcast()
        assertEquals("foobar=ready", s(buf.toByteArray()))
        assertFalse(io.shutdownCalled, "upgrade shouldn't shutdown AsyncWrite")
        assertFailsWith<HttpError> { client.ready() }

        upgradedIo.writeAll("foo=bar")
        assertEquals("bar=foo", s(upgradedIo.readToEnd()))
        server.await()
    }

    @Test
    fun upgrade() = hyperTest {
        upgradeLike({ get("/a") }, "HTTP/1.1 101 Switching Protocols\r\nUpgrade: foobar\r\n\r\nfoobar=ready") { res ->
            assertEquals(StatusCode.SWITCHING_PROTOCOLS, res.status)
            assertEquals("foobar", res.headers["Upgrade"]!!.str())
        }
    }

    @Test
    fun connectMethod() = hyperTest {
        upgradeLike({ port -> Request.builder().method("CONNECT").uri("127.0.0.1:$port").body(EmptyBody as Body) }, "HTTP/1.1 200 OK\r\n\r\nfoobar=ready") { res ->
            assertEquals(StatusCode.OK, res.status)
        }
    }

    @Test
    fun client100ThenHttp09() = hyperTest {
        val (listener, port) = listenLocal()
        spawn {
            val sock = listener.accept().closeAtEnd()
            sock.readOnce()
            sock.writeAll(
                "HTTP/1.1 100 Continue\r\nContent-Type: text/plain\r\nServer: BaseHTTP/0.6 Python/3.12.5\r\n" +
                    "Date: Mon, 16 Dec 2024 03:08:27 GMT\r\n",
            )
            // That it's separate writes is important to this test
            delay(50)
            sock.writeAll("\r\n")
            delay(50)
            sock.writeAll("This is a sample text/plain document, without final headers.\n\n")
        }
        val (client, conn) = Http1ClientConfig(h09Responses = true).handshake(connectLocal(port))
        spawn { conn.run() }
        client.sendRequest(get("/a"))
    }

    @Test
    fun clientOnInformationalExt() = hyperTest {
        val (listener, port) = listenLocal()
        spawn {
            val sock = listener.accept().closeAtEnd()
            sock.readOnce()
            sock.writeAll("HTTP/1.1 100 Continue\r\n\r\n")
            sock.writeAll("HTTP/1.1 200 OK\r\ncontent-length: 0\r\n\r\n")
        }
        val (client, conn) = http1Handshake(connectLocal(port))
        spawn { conn.run() }
        val req = get("/a")
        var cnt = 0
        req.extensions.insert(OnInformational { res -> assertEquals(100, res.status.asU16()); cnt++ })
        client.sendRequest(req)
        assertEquals(1, cnt)
    }

    @Test
    fun testBodyPanics() = hyperTest {
        val (clientIo, serverIo) = memoryStreamPair(1024)
        // spawn a server that reads but doesn't write
        spawn { serverIo.readToEnd() }
        val (client, conn) = http1Handshake(clientIo)
        spawn { conn.run() }
        val body = object : Body {
            override suspend fun nextFrame(): Frame? = throw IllegalStateException("oopsie")    // map_frame(|_| panic!)
            override val sizeHint = neton.http.SizeHint.withExact(8)                          // Full::from("baguette")
        }
        val error = assertFailsWith<HttpError> { client.sendRequest(Request.post("/a").body(body as Body)) }
        assertTrue(error.isUser(), "$error")
    }

    /** hyper's `CountingStream`: counts the connection's flushes. */
    private class CountingStream(inner: IoStream) : ForwardingStream(inner) {
        var flushCount = 0
        override suspend fun flush() { flushCount++; super.flush() }
    }

    // https://github.com/hyperium/hyper/issues/4085
    @Test
    fun http1HalfClosedPeerWithOpenRequestBodyDoesNotSpin() = hyperTest {
        val (listener, port) = listenLocal()
        val headersSeen = CompletableDeferred<Unit>()
        spawn {
            val sock = listener.accept().closeAtEnd()
            val received = Buffer()
            while (!s(received.peekAll()).contains("\r\n\r\n")) {
                check(sock.read(received) >= 0) { "client closed before sending request headers" }
            }
            headersSeen.complete(Unit)
            sock.shutdownOutput()                 // server half-close write
            delay(200)
        }
        val io = CountingStream(connectLocal(port))
        val (client, conn) = http1Handshake(io)
        val connTask = spawn { conn.run() }
        val ch = Channel<Result<Frame>>()
        val responseTask = spawn { runCatching { client.sendRequest(Request.post("/a").body(ChannelBody(ch) as Body)) } }

        headersSeen.await()
        delay(100)
        val flushes = io.flushCount
        assertTrue(flushes < 100, "client spun after peer half-close with open request body: poll_flush=$flushes")
        responseTask.cancel()
        connTask.cancel()
    }
}
